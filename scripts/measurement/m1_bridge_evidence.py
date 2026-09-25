#!/usr/bin/env python3
"""Independent M1-E PlaybackBridge evidence verifier.

Inputs are the device-produced per-case directories (bridge-events-v1,
fetch-events-v2, case.json) plus the Media Lab origin trace of the same run.
The verifier never compares clocks across domains: bridge rows join broker
rows by (sessionId, fetchId), broker attempts join origin requests by
transportCorrelationId == requestId, and cached-seek windows are checked by
event sequence plus the broker sequence watermark recorded on harness markers.
"""

from __future__ import annotations

import argparse
import json
import pathlib
from typing import Any

from schema_subset import validate_instance, validate_schema_definition


SCRIPT_DIR = pathlib.Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parents[1]
SCHEMAS = REPO_ROOT / ".work" / "schemas"
BRIDGE_SCHEMA_PATHS = {
    1: SCHEMAS / "bridge-events-v1.schema.json",
    2: SCHEMAS / "bridge-events-v2.schema.json",
}
FETCH_SCHEMA_PATHS = {
    2: SCHEMAS / "fetch-events-v2.schema.json",
    3: SCHEMAS / "fetch-events-v3.schema.json",
}

REQUIRED_CASES = ("E1", "E2", "E3", "E4", "E5", "E6")
# Cases whose transport gate never lets an attempt reach the origin.
GATED_OFFLINE_CASES = frozenset({"E6"})
TERMINAL_EVENTS = ("OWNER_COMPLETED", "OWNER_FAILED", "OWNER_CANCELLED")
BRIDGE_FETCH_EVENTS = ("MISS", "JOIN", "WAIT_EXISTING")
RELEASE_BUDGET_MS = 1_000


class EvidenceError(ValueError):
    pass


def read_jsonl(path: pathlib.Path, *, allow_empty: bool = False) -> list[dict[str, Any]]:
    if not path.is_file():
        raise EvidenceError(f"evidence file does not exist: {path}")
    rows: list[dict[str, Any]] = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError as exc:
            raise EvidenceError(f"{path}:{number}: invalid JSON") from exc
        if not isinstance(value, dict):
            raise EvidenceError(f"{path}:{number}: row must be an object")
        rows.append(value)
    if not rows and not allow_empty:
        raise EvidenceError(f"evidence file is empty: {path}")
    return rows


def _load_schema(path: pathlib.Path) -> dict[str, Any]:
    schema = json.loads(path.read_text(encoding="utf-8"))
    validate_schema_definition(schema)
    return schema


def _validate_rows(schema: dict[str, Any], rows: list[dict[str, Any]], label: str) -> None:
    for index, row in enumerate(rows):
        try:
            validate_instance(schema, row, path=f"{label}[{index}]", root_schema=schema)
        except ValueError as exc:
            raise EvidenceError(str(exc)) from exc


def _require_ordered(rows: list[dict[str, Any]], label: str) -> None:
    sequences = [row["eventSequence"] for row in rows]
    if sequences != sorted(sequences) or len(set(sequences)) != len(sequences):
        raise EvidenceError(f"{label}: eventSequence must be strictly ordered and unique")


class Case:
    def __init__(
        self,
        case_id: str,
        case: dict[str, Any],
        bridge: list[dict[str, Any]],
        fetch: list[dict[str, Any]],
    ) -> None:
        self.case_id = case_id
        self.case = case
        self.bridge = bridge
        self.fetch = fetch
        self.session_id = case.get("sessionId")
        self.seeded = set(case.get("seededExtentIds") or [])

    def bridge_events(self, kind: str) -> list[dict[str, Any]]:
        return [row for row in self.bridge if row["event"] == kind]

    def fetch_events(self, kind: str, fetch_id: str | None = None) -> list[dict[str, Any]]:
        return [
            row
            for row in self.fetch
            if row["event"] == kind and (fetch_id is None or row["fetchId"] == fetch_id)
        ]

    def terminal(self, fetch_id: str) -> dict[str, Any]:
        rows = [
            row for row in self.fetch
            if row["fetchId"] == fetch_id and row["event"] in TERMINAL_EVENTS
        ]
        if len(rows) != 1:
            raise EvidenceError(
                f"{self.case_id}: fetch {fetch_id} must have exactly one terminal "
                f"owner event, got {len(rows)}"
            )
        return rows[0]

    def marker(self, name: str) -> dict[str, Any]:
        rows = [
            row for row in self.bridge_events("HARNESS_MARKER") if row["markerName"] == name
        ]
        if len(rows) != 1:
            raise EvidenceError(f"{self.case_id}: expected one {name} marker, got {len(rows)}")
        return rows[0]


def load_case(root: pathlib.Path, case_id: str) -> Case:
    directory = root / case_id
    case_path = directory / "case.json"
    if not case_path.is_file():
        raise EvidenceError(f"missing case manifest: {case_path}")
    case = json.loads(case_path.read_text(encoding="utf-8"))
    if case.get("caseId") != case_id:
        raise EvidenceError(f"{case_id}: case.json caseId mismatch")
    bridge = read_jsonl(directory / "bridge-events-v1.jsonl")
    fetch_path = directory / "fetch-events-v3.jsonl"
    if not fetch_path.exists():
        fetch_path = directory / "fetch-events-v2.jsonl"
    fetch = read_jsonl(fetch_path, allow_empty=True)
    # Schema first, semantics second. M2-C writes bridge-events-v2 to the
    # historical filename used by the M1 harness; retained v1 stays valid.
    bridge_versions = {row.get("schemaVersion") for row in bridge}
    if len(bridge_versions) != 1:
        raise EvidenceError(f"{case_id}: mixed bridge event schema versions")
    bridge_schema_path = BRIDGE_SCHEMA_PATHS.get(next(iter(bridge_versions)))
    if bridge_schema_path is None:
        raise EvidenceError(f"{case_id}: unsupported bridge event schema version")
    _validate_rows(_load_schema(bridge_schema_path), bridge, f"{case_id}/bridge")
    if fetch:
        versions = {row.get("schemaVersion") for row in fetch}
        if len(versions) != 1:
            raise EvidenceError(f"{case_id}: mixed fetch event schema versions")
        fetch_schema_path = FETCH_SCHEMA_PATHS.get(next(iter(versions)))
        if fetch_schema_path is None:
            raise EvidenceError(f"{case_id}: unsupported fetch event schema version")
        _validate_rows(_load_schema(fetch_schema_path), fetch, f"{case_id}/fetch")
    _require_ordered(bridge, f"{case_id}/bridge")
    _require_ordered(fetch, f"{case_id}/fetch")
    loaded = Case(case_id, case, bridge, fetch)
    for row in bridge + fetch:
        if row["sessionId"] != loaded.session_id:
            raise EvidenceError(f"{case_id}: row from foreign session {row['sessionId']}")
    return loaded


def verify_case_common(case: Case) -> None:
    for row in [
        event
        for kind in BRIDGE_FETCH_EVENTS
        for event in case.bridge_events(kind)
    ]:
        fetch_id = row["fetchId"]
        terminal = case.terminal(fetch_id)
        waits = [
            wait for wait in case.bridge_events("FETCH_WAIT_END")
            if wait["fetchId"] == fetch_id and wait["readId"] == row["readId"]
        ]
        if waits and waits[0]["fetchOutcome"] != terminal["outcome"]:
            raise EvidenceError(
                f"{case.case_id}: bridge outcome for {fetch_id} disagrees with broker"
            )
        if row["extentId"] in case.seeded:
            raise EvidenceError(
                f"{case.case_id}: fetch touched pre-run committed extent {row['extentId']}"
            )
    for row in case.fetch:
        if row["duplicateRangeBytes"] != 0:
            raise EvidenceError(
                f"{case.case_id}: fetch {row['fetchId']} accepted duplicate range bytes"
            )


def verify_origin_bijection(cases: list[Case], origin: list[dict[str, Any]]) -> dict[str, Any]:
    data_rows = [
        row for row in origin
        if row.get("plane") == "data" and str(row.get("path", "")).startswith("/fixtures/")
    ]
    manifest_rows = [row for row in data_rows if str(row["path"]).endswith(".mpd")]
    if manifest_rows:
        raise EvidenceError("manifest must be served from packaged bytes, not the origin")

    by_request: dict[str, dict[str, Any]] = {}
    for row in data_rows:
        request_id = str(row.get("requestId"))
        if request_id in by_request:
            raise EvidenceError(f"duplicate origin requestId {request_id}")
        by_request[request_id] = row

    matched: set[str] = set()
    completed_count = 0
    for case in cases:
        completed = case.fetch_events("ATTEMPT_COMPLETED")
        if case.case_id in GATED_OFFLINE_CASES and completed:
            raise EvidenceError(f"{case.case_id}: gated case must not complete an attempt")
        for row in completed:
            completed_count += 1
            correlation = row.get("transportCorrelationId")
            if not correlation:
                raise EvidenceError(
                    f"{case.case_id}: completed attempt {row['attemptCorrelationId']} "
                    "has no transport correlation"
                )
            origin_row = by_request.get(str(correlation))
            if origin_row is None:
                raise EvidenceError(
                    f"{case.case_id}: attempt {row['attemptCorrelationId']} has no origin request"
                )
            if str(correlation) in matched:
                raise EvidenceError(f"origin request {correlation} matched twice")
            matched.add(str(correlation))
            if origin_row.get("status") != 206 or origin_row.get("outcome") != "SUCCESS":
                raise EvidenceError(f"origin request {correlation} was not a successful 206")
            expected_start = row["requestedByteStart"] if row["requestedByteStart"] is not None else 0
            if origin_row.get("resolvedRangeStart") != expected_start:
                raise EvidenceError(f"origin request {correlation} range start mismatch")
            if (
                row["requestedByteEndExclusive"] is not None
                and origin_row.get("resolvedRangeEndExclusive") != row["requestedByteEndExclusive"]
            ):
                raise EvidenceError(f"origin request {correlation} range end mismatch")

    unmatched = sorted(set(by_request) - matched)
    if unmatched:
        raise EvidenceError(
            "origin data-plane requests without a broker attempt (hidden upstream): "
            + ", ".join(unmatched)
        )
    return {
        "originDataPlaneRequests": len(data_rows),
        "brokerCompletedAttempts": completed_count,
        "manifestOriginRequests": 0,
    }


def verify_e1(case: Case) -> dict[str, Any]:
    issued = case.marker("SEEK_ISSUED")
    settled = case.marker("SEEK_SETTLED")
    if not issued["eventSequence"] < settled["eventSequence"]:
        raise EvidenceError("E1: seek markers out of order")
    inside = [
        row for row in case.bridge
        if issued["eventSequence"] < row["eventSequence"] < settled["eventSequence"]
        and row["event"] in BRIDGE_FETCH_EVENTS
    ]
    if inside:
        raise EvidenceError("E1: cached seek window contains bridge misses/joins")
    low = issued["fetchEventSequenceWatermark"]
    high = settled["fetchEventSequenceWatermark"]
    if low is None or high is None or high < low:
        raise EvidenceError("E1: invalid broker watermarks on seek markers")
    attempts = [
        row for row in case.fetch_events("ATTEMPT_STARTED")
        if low <= row["eventSequence"] < high
    ]
    if attempts:
        raise EvidenceError("E1: broker attempts inside the cached seek window")
    if any(case.bridge_events(kind) for kind in BRIDGE_FETCH_EVENTS) or case.fetch:
        raise EvidenceError("E1: S120 cached playback must not fetch at all")
    if not case.bridge_events("LOCAL_SERVE"):
        raise EvidenceError("E1: no local serve recorded")
    return {"localServes": len(case.bridge_events("LOCAL_SERVE"))}


def verify_e2(case: Case) -> dict[str, Any]:
    issued = case.marker("SEEK_TO_MISSING_ISSUED")
    settled = case.marker("SEEK_TO_MISSING_SETTLED")
    if not issued["eventSequence"] < settled["eventSequence"]:
        raise EvidenceError("E2: seek markers out of order")

    low = issued["fetchEventSequenceWatermark"]
    high = settled["fetchEventSequenceWatermark"]
    if low is None or high is None or high < low:
        raise EvidenceError("E2: invalid broker watermarks on seek markers")

    successful = []
    for row in case.bridge_events("MISS"):
        if not issued["eventSequence"] < row["eventSequence"] < settled["eventSequence"]:
            continue
        fetch_id = row["fetchId"]
        terminal = case.terminal(fetch_id)
        attempts = [
            attempt for attempt in case.fetch_events("ATTEMPT_STARTED", fetch_id)
            if low <= attempt["eventSequence"] < high
        ]
        completed = case.fetch_events("ATTEMPT_COMPLETED", fetch_id)
        local_after = [
            local for local in case.bridge_events("LOCAL_SERVE")
            if local["readId"] == row["readId"]
            and local["extentId"] == row["extentId"]
            and row["eventSequence"] < local["eventSequence"] < settled["eventSequence"]
        ]
        if (
            terminal["outcome"] == "SUCCESS"
            and attempts
            and completed
            and local_after
        ):
            successful.append(row)

    if not successful:
        raise EvidenceError(
            "E2: seek window has no miss -> broker attempt -> success -> local serve chain"
        )
    return {"successfulSeekMisses": len(successful)}


def _is_reserve_owner(case: Case, row: dict[str, Any], owner: dict[str, Any]) -> bool:
    """The joined owner was started for the harness reserve lease.

    M1 runtimes registered the reserve lease itself as the broker consumer.
    Since M2-C the RecoveryChain is the only broker consumer
    (`recovery:<chainId>:<ordinal>`), so the lease is joined to its owner
    through the fetchId the lease reported, recorded in case.json.
    """

    consumers = owner["consumerIds"]
    if "harness-reserve" in consumers:
        return True
    reserve_fetch_id = case.case.get("reserveFetchId")
    return (
        reserve_fetch_id is not None
        and reserve_fetch_id == row["fetchId"]
        and len(consumers) == 1
        and str(consumers[0]).startswith("recovery:")
    )


def verify_e3(case: Case) -> dict[str, Any]:
    joins = []
    for row in case.bridge_events("JOIN"):
        owner = case.fetch_events("OWNER_REGISTERED", row["fetchId"])
        if (
            len(owner) == 1
            and _is_reserve_owner(case, row, owner[0])
            and owner[0]["effectivePriority"] == "RESERVE"
        ):
            joins.append(row)
    if len(joins) != 1:
        raise EvidenceError(f"E3: expected one join onto the reserve owner, got {len(joins)}")
    fetch_id = joins[0]["fetchId"]
    if len(case.fetch_events("PRIORITY_RAISED", fetch_id)) != 1:
        raise EvidenceError("E3: playback join did not raise priority once")
    if len(case.fetch_events("ATTEMPT_STARTED", fetch_id)) != 1:
        raise EvidenceError("E3: joined fetch must have exactly one physical attempt")
    if case.terminal(fetch_id)["outcome"] != "SUCCESS":
        raise EvidenceError("E3: joined fetch did not succeed")
    return {"joinedFetchId": fetch_id}


def verify_e4(case: Case, origin: list[dict[str, Any]]) -> dict[str, Any]:
    split = case.case.get("splitExtentId")
    if not split:
        raise EvidenceError("E4: case.json is missing splitExtentId")
    misses = [row for row in case.bridge_events("MISS") if row["extentId"].startswith(split + "@")]
    if len(misses) < 2:
        raise EvidenceError("E4: expected misses for multiple ranged extents")
    ranged_attempts = []
    for row in misses:
        for attempt in case.fetch_events("ATTEMPT_COMPLETED", row["fetchId"]):
            if attempt["requestedByteStart"] is None:
                raise EvidenceError("E4: ranged extent fetched without a byte range")
            ranged_attempts.append(attempt)
    by_request = {str(row.get("requestId")): row for row in origin}
    non_zero = [
        attempt for attempt in ranged_attempts
        if (by_request.get(str(attempt["transportCorrelationId"])) or {}).get("resolvedRangeStart", 0) > 0
    ]
    if not non_zero:
        raise EvidenceError("E4: origin trace shows no mid-resource Range request")
    served: dict[str, set[str]] = {}
    for row in case.bridge_events("LOCAL_SERVE"):
        if row["extentId"].startswith(split + "@"):
            served.setdefault(row["readId"], set()).add(row["extentId"])
    if not any(len(ids) >= 2 for ids in served.values()):
        raise EvidenceError("E4: no single open read across ranged extent boundaries")
    return {"rangedMisses": len(misses), "midResourceRangeRequests": len(non_zero)}


def verify_e5(case: Case) -> dict[str, Any]:
    init_misses = [row for row in case.bridge_events("MISS") if row["extentId"] == "f1:video:0:init"]
    if len(init_misses) != 1:
        raise EvidenceError(f"E5: expected one init miss, got {len(init_misses)}")
    if case.terminal(init_misses[0]["fetchId"])["outcome"] != "SUCCESS":
        raise EvidenceError("E5: init fetch did not succeed")
    if not any(row["extentId"] == "f1:video:0:1" for row in case.bridge_events("LOCAL_SERVE")):
        raise EvidenceError("E5: published segment was not served locally")
    return {"initFetchId": init_misses[0]["fetchId"]}


def verify_e6(case: Case) -> dict[str, Any]:
    misses = case.bridge_events("MISS")
    if not misses:
        raise EvidenceError("E6: no blocked miss recorded")
    closed = {row["readId"] for row in case.bridge_events("CLOSE")}
    if not {row["readId"] for row in misses} <= closed:
        raise EvidenceError("E6: blocked reads were not closed")
    for owner in case.fetch_events("OWNER_REGISTERED"):
        if case.terminal(owner["fetchId"])["outcome"] != "CANCELLED_NO_CONSUMERS":
            raise EvidenceError("E6: blocked owner was not cancelled by lease release")
    for key in ("releaseMs", "blockedReadsClosedMs"):
        value = case.case.get(key)
        if not isinstance(value, int) or value >= RELEASE_BUDGET_MS:
            raise EvidenceError(f"E6: {key}={value} exceeds {RELEASE_BUDGET_MS} ms")
    return {
        "releaseMs": case.case["releaseMs"],
        "blockedReadsClosedMs": case.case["blockedReadsClosedMs"],
    }


def verify(cases_root: pathlib.Path, origin: list[dict[str, Any]]) -> dict[str, Any]:
    cases = [load_case(cases_root, case_id) for case_id in REQUIRED_CASES]
    for case in cases:
        verify_case_common(case)
    by_id = {case.case_id: case for case in cases}
    summary = {
        "schemaVersion": 1,
        "status": "PASS",
        "origin": verify_origin_bijection(cases, origin),
        "cases": {
            "E1": verify_e1(by_id["E1"]),
            "E2": verify_e2(by_id["E2"]),
            "E3": verify_e3(by_id["E3"]),
            "E4": verify_e4(by_id["E4"], origin),
            "E5": verify_e5(by_id["E5"]),
            "E6": verify_e6(by_id["E6"]),
        },
        "loadErrorPolicy": by_id["E2"].case.get("loadErrorPolicy"),
        "bridgeConfig": by_id["E2"].case.get("bridgeConfig"),
    }
    return summary


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cases-root", required=True, type=pathlib.Path)
    parser.add_argument("--origin-trace", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()

    summary = verify(args.cases_root, read_jsonl(args.origin_trace))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
