#!/usr/bin/env python3
"""Independent M1-F process-death and N4 recovery evidence verifier.

The verifier intentionally keeps Android elapsed-realtime and Media Lab monotonic
clock domains separate. Cross-domain causality is joined only through command IDs,
fetch/attempt correlation IDs and origin request IDs.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
from collections import defaultdict
from typing import Any, Iterable

from schema_subset import validate_instance, validate_schema_definition

SCRIPT_DIR = pathlib.Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parents[1]
SCHEMAS = REPO_ROOT / ".work" / "schemas"

TIMELINE_SCHEMA = SCHEMAS / "recovery-timeline-v1.schema.json"
EXTENT_SCHEMA = SCHEMAS / "extent-events-v1.schema.json"
FETCH_SCHEMA = SCHEMAS / "fetch-events-v3.schema.json"
GATE_SCHEMA = SCHEMAS / "origin-gate-events-v1.schema.json"
SUMMARY_SCHEMA = SCHEMAS / "recovery-summary-v2.schema.json"

TERMINAL_OWNER_EVENTS = {
    "OWNER_COMPLETED",
    "OWNER_FAILED",
    "OWNER_CANCELLED",
}
COVERAGE_FIELDS = (
    "mediaAssetId",
    "sessionId",
    "playheadUs",
    "requiredRepresentations",
    "perTrackPublishedIntervals",
    "playableIntervals",
    "durablePlayableEndUs",
    "durableReserveUs",
)


class RecoveryEvidenceError(ValueError):
    pass


def read_json(path: pathlib.Path) -> dict[str, Any]:
    if not path.is_file():
        raise RecoveryEvidenceError(f"missing evidence file: {path}")
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise RecoveryEvidenceError(f"{path}: expected JSON object")
    return value


def read_jsonl(
    path: pathlib.Path | None,
    *,
    allow_missing: bool = False,
) -> list[dict[str, Any]]:
    if path is None or not path.is_file():
        if allow_missing:
            return []
        raise RecoveryEvidenceError(f"missing evidence file: {path}")
    rows: list[dict[str, Any]] = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError as exc:
            raise RecoveryEvidenceError(f"{path}:{number}: invalid JSON") from exc
        if not isinstance(row, dict):
            raise RecoveryEvidenceError(f"{path}:{number}: expected object row")
        rows.append(row)
    return rows


def _schema(path: pathlib.Path) -> dict[str, Any]:
    schema = json.loads(path.read_text(encoding="utf-8"))
    validate_schema_definition(schema)
    return schema


def _validate_rows(
    rows: Iterable[dict[str, Any]],
    schema_path: pathlib.Path,
    label: str,
) -> None:
    schema = _schema(schema_path)
    for index, row in enumerate(rows):
        try:
            validate_instance(
                schema,
                row,
                path=f"{label}[{index}]",
                root_schema=schema,
            )
        except ValueError as exc:
            raise RecoveryEvidenceError(str(exc)) from exc


def _ordered(rows: list[dict[str, Any]], field: str, label: str) -> None:
    values = [row[field] for row in rows]
    if values != sorted(values) or len(values) != len(set(values)):
        raise RecoveryEvidenceError(
            f"{label}: {field} must be strictly ordered and unique"
        )


def _coverage_semantic(value: dict[str, Any]) -> dict[str, Any]:
    return {key: value.get(key) for key in COVERAGE_FIELDS if key in value}


def _digest(value: dict[str, Any] | None) -> str | None:
    if value is None:
        return None
    encoded = json.dumps(
        value,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _interval_overlap(
    accepted: list[tuple[int, int]],
    start: int,
    end: int,
) -> int:
    return sum(
        max(0, min(end, old_end) - max(start, old_start))
        for old_start, old_end in accepted
    )


def _add_interval(
    accepted: list[tuple[int, int]],
    start: int,
    end: int,
) -> None:
    merged: list[tuple[int, int]] = []
    for item in sorted(accepted + [(start, end)]):
        if not merged or item[0] > merged[-1][1]:
            merged.append(item)
        else:
            merged[-1] = (
                merged[-1][0],
                max(merged[-1][1], item[1]),
            )
    accepted[:] = merged


def _verify_fetch(
    case: dict[str, Any],
    rows: list[dict[str, Any]],
    origin: list[dict[str, Any]],
) -> tuple[dict[str, int], bool, bool, bool]:
    if rows:
        _validate_rows(rows, FETCH_SCHEMA, "fetch")
        _ordered(rows, "eventSequence", "fetch")

    max_attempts = int(case["maxAttemptsPerOwner"])
    media3_retries = int(case["media3MaxRetries"])
    retry_owner_ceiling = media3_retries + 1

    active_by_key: dict[str, str] = {}
    owner_key: dict[str, str] = {}
    terminal_owners: set[str] = set()
    owner_attempts: dict[str, set[int]] = defaultdict(set)
    owners_by_key: dict[str, set[str]] = defaultdict(set)
    attempt_transport: dict[str, set[str]] = defaultdict(set)
    attempts_seen: set[str] = set()
    no_overlap = True

    prepublished = set(case.get("prePublishedFetchKeys") or [])
    touched_prepublished = False

    accepted_by_key: dict[str, list[tuple[int, int]]] = defaultdict(list)
    session_duplicate = 0

    for row in rows:
        event = row["event"]
        fetch_id = row["fetchId"]
        fetch_key = row["fetchKey"]

        if event == "OWNER_REGISTERED":
            if fetch_id in owner_key:
                raise RecoveryEvidenceError(
                    f"{fetch_id}: duplicate OWNER_REGISTERED"
                )
            if fetch_key in active_by_key:
                no_overlap = False
            active_by_key[fetch_key] = fetch_id
            owner_key[fetch_id] = fetch_key
            owners_by_key[fetch_key].add(fetch_id)
            if fetch_key in prepublished:
                touched_prepublished = True

        if event == "ATTEMPT_STARTED":
            if owner_key.get(fetch_id) != fetch_key or fetch_id in terminal_owners:
                raise RecoveryEvidenceError(
                    f"{fetch_id}: attempt started outside active owner lifetime"
                )
            attempt = int(row["attempt"])
            owner_attempts[fetch_id].add(attempt)
            attempts_seen.add(row["attemptCorrelationId"])

        if event in {
            "ATTEMPT_CORRELATED",
            "ATTEMPT_PROGRESS",
            "ATTEMPT_COMPLETED",
            "ATTEMPT_FAILED",
        }:
            correlation = row.get("transportCorrelationId")
            attempt_correlation = row.get("attemptCorrelationId")
            if correlation and attempt_correlation:
                attempt_transport[attempt_correlation].add(str(correlation))

        if event == "ATTEMPT_PROGRESS":
            start = row.get("chunkByteStart")
            end = row.get("chunkByteEndExclusive")
            if not isinstance(start, int) or not isinstance(end, int) or end <= start:
                raise RecoveryEvidenceError(
                    "ATTEMPT_PROGRESS must carry a valid chunk range"
                )
            overlap = _interval_overlap(accepted_by_key[fetch_key], start, end)
            session_duplicate += overlap
            _add_interval(accepted_by_key[fetch_key], start, end)

        if event in TERMINAL_OWNER_EVENTS:
            if fetch_id in terminal_owners:
                raise RecoveryEvidenceError(
                    f"{fetch_id}: multiple terminal owner events"
                )
            terminal_owners.add(fetch_id)
            if active_by_key.get(fetch_key) != fetch_id:
                no_overlap = False
            else:
                del active_by_key[fetch_key]

    if active_by_key:
        raise RecoveryEvidenceError(
            "non-terminal fetch owners remain in evidence: "
            + ", ".join(sorted(active_by_key))
        )
    missing_terminal = sorted(set(owner_key) - terminal_owners)
    if missing_terminal:
        raise RecoveryEvidenceError(
            "registered fetch owners without terminal event: "
            + ", ".join(missing_terminal)
        )

    for fetch_id, attempts in owner_attempts.items():
        if attempts and max(attempts) > max_attempts:
            raise RecoveryEvidenceError(
                f"{fetch_id}: attempt budget exceeded "
                f"({max(attempts)} > {max_attempts})"
            )
        expected = set(range(1, max(attempts, default=0) + 1))
        if attempts != expected:
            raise RecoveryEvidenceError(
                f"{fetch_id}: attempts are not contiguous from 1"
            )

    for fetch_key, owners in owners_by_key.items():
        if len(owners) > retry_owner_ceiling:
            raise RecoveryEvidenceError(
                f"{fetch_key}: Media3 owner budget exceeded "
                f"({len(owners)} > {retry_owner_ceiling})"
            )

    for attempt_id, correlations in attempt_transport.items():
        if len(correlations) > 1:
            raise RecoveryEvidenceError(
                f"{attempt_id}: maps to multiple origin request IDs"
            )

    data_origin = [
        row for row in origin
        if row.get("plane") == "data"
        and str(row.get("path", "")).startswith("/fixtures/")
    ]
    request_ids = [str(row.get("requestId")) for row in data_origin]
    if len(request_ids) != len(set(request_ids)):
        raise RecoveryEvidenceError("origin request IDs must be unique")

    request_to_attempts: dict[str, list[str]] = defaultdict(list)
    for attempt_id, values in attempt_transport.items():
        if values:
            request_to_attempts[next(iter(values))].append(attempt_id)
    multiply_claimed = {
        request_id: attempts
        for request_id, attempts in request_to_attempts.items()
        if len(attempts) > 1
    }
    if multiply_claimed:
        details = "; ".join(
            f"{request_id}=>{','.join(sorted(attempts))}"
            for request_id, attempts in sorted(multiply_claimed.items())
        )
        raise RecoveryEvidenceError(
            "origin request is attributed to multiple broker attempts: "
            + details
        )

    correlated_requests = set(request_to_attempts)
    hidden = sorted(set(request_ids) - correlated_requests)
    if hidden:
        raise RecoveryEvidenceError(
            "origin requests without FetchBroker attempt correlation: "
            + ", ".join(hidden)
        )
    foreign = sorted(correlated_requests - set(request_ids))
    if foreign:
        raise RecoveryEvidenceError(
            "FetchBroker correlations without matching origin request: "
            + ", ".join(foreign)
        )

    return (
        {
            "ownerCount": len(owner_key),
            "physicalAttemptCount": len(attempts_seen),
            "maxAttemptsPerOwner": max_attempts,
            "media3MaxRetries": media3_retries,
            "sessionDuplicateRangeBytes": session_duplicate,
            "originRequestCount": len(data_origin),
        },
        no_overlap,
        not hidden and not foreign,
        not touched_prepublished,
    )


def _verify_gate(
    scenario: str,
    case: dict[str, Any],
    rows: list[dict[str, Any]],
) -> None:
    if scenario == "PROCESS_DEATH":
        if rows:
            raise RecoveryEvidenceError(
                "PROCESS_DEATH must not depend on origin gate evidence"
            )
        return

    _validate_rows(rows, GATE_SCHEMA, "originGate")
    _ordered(rows, "serverEventSequence", "originGate")

    generations = {
        row["gateGeneration"]
        for row in rows
        if row["event"] == "GATE_CLOSE_ACCEPTED"
    }
    if not generations:
        raise RecoveryEvidenceError("N4R scenario has no gate close generation")

    blocked = [row for row in rows if row["event"] == "REQUEST_BLOCKED"]
    released = [row for row in rows if row["event"] == "REQUEST_RELEASED"]

    if scenario in {"N4R-SHORT", "N4R-EXHAUST", "N4R-RESTORE"} and not blocked:
        raise RecoveryEvidenceError(f"{scenario}: no origin request was actually blocked")

    if scenario == "N4R-SHORT":
        initial_reserve = int(case["initialDurableReserveUs"])
        released_by = {
            (row["gateGeneration"], row["requestId"]): row
            for row in released
        }
        durations_us = []
        for row in blocked:
            end = released_by.get((row["gateGeneration"], row["requestId"]))
            if end is not None:
                durations_us.append(
                    (end["serverMonotonicNs"] - row["serverMonotonicNs"]) // 1000
                )
        if not durations_us:
            raise RecoveryEvidenceError("N4R-SHORT: no blocked request was released")
        if max(durations_us) >= initial_reserve:
            raise RecoveryEvidenceError(
                "N4R-SHORT: observed no-progress duration was not shorter "
                "than initial durable reserve"
            )

    if scenario == "N4R-RESTORE":
        command = case.get("restoreCommandId")
        if not command:
            raise RecoveryEvidenceError("N4R-RESTORE: missing restoreCommandId")
        if not any(
            row["event"] == "GATE_OPEN_ACCEPTED"
            and row.get("commandId") == command
            for row in rows
        ):
            raise RecoveryEvidenceError(
                "N4R-RESTORE: restore command is absent from origin gate evidence"
            )

    if scenario == "N4R-FLAP" and len(generations) < 3:
        raise RecoveryEvidenceError(
            "N4R-FLAP: expected at least three close generations"
        )


def _verify_playback(
    scenario: str,
    case: dict[str, Any],
    timeline: list[dict[str, Any]],
    extent_events: list[dict[str, Any]],
) -> tuple[int, bool | None, bool | None]:
    if scenario == "PROCESS_DEATH":
        return 0, None, None

    _validate_rows(timeline, TIMELINE_SCHEMA, "timeline")
    _ordered(timeline, "eventSequence", "timeline")
    if extent_events:
        _validate_rows(extent_events, EXTENT_SCHEMA, "extent")
        _ordered(extent_events, "eventSequence", "extent")
    if any(row["sessionId"] != case["sessionId"] for row in timeline):
        raise RecoveryEvidenceError("timeline contains a foreign session")

    stalls = [row for row in timeline if row["event"] == "PLAYER_STALL_STARTED"]
    if scenario == "N4R-SHORT" and stalls:
        raise RecoveryEvidenceError("N4R-SHORT: playback stalled")
    if scenario in {"N4R-EXHAUST", "N4R-RESTORE"} and not stalls:
        raise RecoveryEvidenceError(f"{scenario}: expected a true rebuffer stall")

    if scenario != "N4R-RESTORE":
        return len(stalls), None, None

    observed_sequence = case.get("observedStallEventSequence")
    stall = next(
        (
            row for row in stalls
            if row["eventSequence"] == observed_sequence
        ),
        None,
    )
    if stall is None:
        raise RecoveryEvidenceError(
            "N4R-RESTORE: controller-observed stall sequence is not in timeline"
        )
    player_id = stall.get("playerInstanceId")
    if not player_id:
        raise RecoveryEvidenceError("N4R-RESTORE: stalled player has no identity")

    progress = [
        row for row in timeline
        if row["eventSequence"] > stall["eventSequence"]
        and row["event"] == "PLAYBACK_PROGRESS"
        and row.get("playerInstanceId") == player_id
        and isinstance(row.get("playerPositionUs"), int)
        and row["playerPositionUs"] > (stall.get("playerPositionUs") or 0)
    ]
    ended = [
        row for row in timeline
        if row["eventSequence"] > stall["eventSequence"]
        and row["event"] == "PLAYER_STALL_ENDED"
        and row.get("playerInstanceId") == player_id
    ]
    same_player = bool(progress and ended)
    if not same_player:
        raise RecoveryEvidenceError(
            "N4R-RESTORE: the same player did not recover and advance"
        )

    first_progress = min(
        progress,
        key=lambda row: row["eventElapsedRealtimeNs"],
    )
    published_after_stall = [
        row for row in extent_events
        if row["state"] == "PUBLISHED"
        and row["eventElapsedRealtimeNs"] > stall["eventElapsedRealtimeNs"]
        and row["eventElapsedRealtimeNs"] <= first_progress["eventElapsedRealtimeNs"]
    ]
    if not published_after_stall:
        raise RecoveryEvidenceError(
            "N4R-RESTORE: playback advanced without a new PUBLISHED extent "
            "after the observed stall"
        )
    return len(stalls), True, True


def verify(
    case: dict[str, Any],
    timeline: list[dict[str, Any]],
    fetch: list[dict[str, Any]],
    extent_events: list[dict[str, Any]],
    gate: list[dict[str, Any]],
    origin: list[dict[str, Any]],
    coverage_before: dict[str, Any] | None,
    coverage_after: dict[str, Any],
    oracle_coverage: dict[str, Any],
) -> dict[str, Any]:
    scenario = str(case["scenarioId"])
    session = str(case["sessionId"])
    run_id = str(case["runId"])

    fetch_summary, no_overlap, origin_bijection, no_refetch = _verify_fetch(
        case,
        fetch,
        origin,
    )
    _verify_gate(scenario, case, gate)
    stall_count, same_player, post_restore = _verify_playback(
        scenario,
        case,
        timeline,
        extent_events,
    )

    after_semantic = _coverage_semantic(coverage_after)
    oracle_semantic = _coverage_semantic(oracle_coverage)
    if after_semantic != oracle_semantic:
        raise RecoveryEvidenceError(
            "post-recovery runtime coverage does not match independent oracle"
        )

    before_semantic = (
        _coverage_semantic(coverage_before)
        if coverage_before is not None
        else None
    )
    if scenario == "PROCESS_DEATH":
        if before_semantic is None:
            raise RecoveryEvidenceError(
                "PROCESS_DEATH requires pre-death coverage"
            )
        if before_semantic != after_semantic:
            raise RecoveryEvidenceError(
                "valid coverage changed across process death"
            )

    pid_before = case.get("pidBefore")
    pid_after = case.get("pidAfter")
    pid_changed: bool | None
    if scenario == "PROCESS_DEATH":
        if not isinstance(pid_before, int) or not isinstance(pid_after, int):
            raise RecoveryEvidenceError("PROCESS_DEATH requires both process IDs")
        pid_changed = pid_before != pid_after
        if not pid_changed:
            raise RecoveryEvidenceError(
                "PROCESS_DEATH did not create a new target process"
            )
    else:
        pid_before = None
        pid_after = None
        pid_changed = None

    summary = {
        "schemaVersion": 2,
        "runId": run_id,
        "sessionId": session,
        "scenarioId": scenario,
        "status": "PASS",
        "process": {
            "pidBefore": pid_before,
            "pidAfter": pid_after,
            "pidChanged": pid_changed,
        },
        "fetch": fetch_summary,
        "playback": {
            "stallCount": stall_count,
            "samePlayerRecovered": same_player,
            "postRestoreProgress": post_restore,
        },
        "coverage": {
            "beforeDigest": _digest(before_semantic),
            "afterDigest": _digest(after_semantic),
            "oracleDigest": _digest(oracle_semantic),
            "exactMatch": True,
        },
        "invariants": {
            "boundedAttempts": True,
            "noOverlappingOwners": no_overlap,
            "originBijection": origin_bijection,
            "noValidExtentRefetch": no_refetch,
            "clockDomainsKeptSeparate": True,
        },
    }
    if not no_overlap:
        raise RecoveryEvidenceError("overlapping FetchBroker owners detected")
    if not no_refetch:
        raise RecoveryEvidenceError("pre-published valid coverage was refetched")

    schema = _schema(SUMMARY_SCHEMA)
    validate_instance(schema, summary, root_schema=schema)
    return summary


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", required=True, type=pathlib.Path)
    parser.add_argument("--timeline", type=pathlib.Path)
    parser.add_argument("--fetch-events", type=pathlib.Path)
    parser.add_argument("--extent-events", type=pathlib.Path)
    parser.add_argument("--gate-events", type=pathlib.Path)
    parser.add_argument("--origin-trace", type=pathlib.Path)
    parser.add_argument("--coverage-before", type=pathlib.Path)
    parser.add_argument("--coverage-after", required=True, type=pathlib.Path)
    parser.add_argument("--oracle-coverage", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()

    summary = verify(
        read_json(args.case),
        read_jsonl(args.timeline, allow_missing=True),
        read_jsonl(args.fetch_events, allow_missing=True),
        read_jsonl(args.extent_events, allow_missing=True),
        read_jsonl(args.gate_events, allow_missing=True),
        read_jsonl(args.origin_trace, allow_missing=True),
        read_json(args.coverage_before) if args.coverage_before else None,
        read_json(args.coverage_after),
        read_json(args.oracle_coverage),
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(summary, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(
        f"M1-F {summary['scenarioId']} PASS: "
        f"owners={summary['fetch']['ownerCount']} "
        f"attempts={summary['fetch']['physicalAttemptCount']} "
        f"duplicates={summary['fetch']['sessionDuplicateRangeBytes']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
