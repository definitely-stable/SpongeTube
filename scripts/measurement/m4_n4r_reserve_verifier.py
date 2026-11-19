#!/usr/bin/env python3
"""Independent M4-A1b N4R physical HTTP-body failure / restore verifier.

Authority: F1 MPD and byte manifest; actual Media Lab origin + gate traces;
RecoveryCoordinator/FetchBroker event streams; SQLite + verified extent files.
No runtime assertion can self-certify the physical outage or durable reserve.
"""

from __future__ import annotations

import argparse
import json
import pathlib
from typing import Any, Mapping

from m1_fetch_evidence import read_jsonl, validate_fetch_events
from m2_recovery_oracle import verify_recovery
from m4_reserve_verifier import expected_f1_units, verify_storage

SESSION = "m4-a1b-n4r"
TARGET_US = 8_000_000
ATTEMPTS = 6


def verify_origin(
    trace: list[Mapping[str, Any]],
    units: list[Any],
) -> list[Mapping[str, Any]]:
    expected = [units[0], units[0], units[0], *units[1:]]
    rows = sorted(
        (row for row in trace if row.get("plane") == "data"),
        key=lambda r: r["requestId"],
    )
    if len(rows) != ATTEMPTS:
        raise ValueError(f"physical request count {len(rows)} != {ATTEMPTS}")
    ids = [r.get("requestId") for r in rows]
    if any(not isinstance(i, int) or i <= 0 for i in ids):
        raise ValueError("invalid Media Lab request id")
    if len(set(ids)) != ATTEMPTS:
        raise ValueError("duplicate Media Lab physical request id")

    for n, (r, unit) in enumerate(zip(rows, expected)):
        if r.get("sessionId") != SESSION or r.get("profileId") != "N4R":
            raise ValueError("origin session/profile is not N4R")
        if r.get("path") != "/fixtures/" + unit.path:
            raise ValueError(f"unexpected N4R origin request order at {n}")
        if r.get("method") != "GET" or r.get("status") != 206:
            raise ValueError("N4R must use real HTTP 206 Range")
        if r.get("resolvedRangeStart") != 0:
            raise ValueError("nonzero requested range start")
        if r.get("resolvedRangeEndExclusive") != unit.length:
            raise ValueError("origin Content-Range does not match F1")
        count = r.get("bodyBytesWritten")
        if not isinstance(count, int) or not 0 <= count <= unit.length:
            raise ValueError("invalid origin body length")
        if n >= 2:
            if count != unit.length or r.get("outcome") != "SUCCESS":
                raise ValueError("restored origin did not deliver complete F1 bytes")
        elif r.get("noProgressWaitMs", 0) <= 0:
            raise ValueError("timed-out origin showed no physical no-progress wait")
    return rows


def verify_gate(
    gate: list[Mapping[str, Any]],
    origin: list[Mapping[str, Any]],
) -> None:
    if not gate:
        raise ValueError("empty physical media body gate trace")
    seq = [r.get("serverEventSequence") for r in gate]
    if seq != sorted(set(seq)):
        raise ValueError("gate event sequence must be strictly increasing")
    if any(r.get("schemaVersion") != 1 for r in gate):
        raise ValueError("unexpected gate schema version")
    def exactly(kind: str, command: str) -> Mapping[str, Any]:
        rows = [
            r for r in gate
            if r.get("event") == kind and r.get("commandId") == command
        ]
        if len(rows) != 1:
            raise ValueError(f"missing or duplicate gate {kind}/{command}")
        return rows[0]

    closed = exactly("GATE_CLOSE_ACCEPTED", "m4-n4r-close")
    restored = exactly("GATE_OPEN_ACCEPTED", "m4-n4r-restore")
    if closed["serverEventSequence"] >= restored["serverEventSequence"]:
        raise ValueError("origin gate restored before it was closed")
    if closed.get("gateGeneration") != restored.get("gateGeneration"):
        raise ValueError("origin gate generation did not carry through restoration")

    # Each failed physical attempt was admitted while the real HTTP server
    # was stalled below response headers, not by any client-side fake wait.
    for row in origin[:2]:
        request_id = row["requestId"]
        blocked = [
            e for e in gate if e.get("event") == "REQUEST_BLOCKED"
            and e.get("requestId") == request_id
        ]
        released = [
            e for e in gate if e.get("event") == "REQUEST_RELEASED"
            and e.get("requestId") == request_id
        ]
        if len(blocked) != 1 or len(released) != 1:
            raise ValueError("missing real body block/release for timed-out request")
        b, r = blocked[0], released[0]
        if not (
            closed["serverEventSequence"] < b["serverEventSequence"]
            < restored["serverEventSequence"] < r["serverEventSequence"]
        ):
            raise ValueError("physical origin body did not wait across gate restore")
        if b.get("commandId") != "m4-n4r-close" or (
            r.get("commandId") != "m4-n4r-restore"
        ):
            raise ValueError("gate event has wrong command lineage")
        first_write = row.get("firstBodyWriteAtMonotonicNs")
        if first_write is not None and (
            first_write < restored["serverMonotonicNs"]
        ):
            raise ValueError("Media Lab wrote blocked bytes before restore")

    other_blocked = [
        e for e in gate if e.get("event") == "REQUEST_BLOCKED"
        and e.get("requestId") not in {r["requestId"] for r in origin[:2]}
    ]
    if other_blocked:
        raise ValueError("unexpected additional physical body stall")


def verify_fetch(
    events: list[dict[str, Any]],
    origin: list[Mapping[str, Any]],
    units: list[Any],
) -> None:
    validate_fetch_events(events)
    seq = [r.get("eventSequence") for r in events]
    if seq != sorted(set(seq)):
        raise ValueError("non-monotonic FetchBroker evidence")
    expected_keys = [units[0].fetch_key] * 3 + [u.fetch_key for u in units[1:]]
    kinds = (
        "OWNER_REGISTERED", "ATTEMPT_STARTED",
        "ATTEMPT_COMPLETED", "OWNER_COMPLETED",
    )
    matches = {
        k: [r for r in events if r["event"] == k]
        for k in kinds
    }
    for kind in kinds:
        if [r["fetchKey"] for r in matches[kind]] != expected_keys:
            raise ValueError(f"{kind}: incorrect physical owner attempt sequence")

    owner_ids = [r["fetchId"] for r in matches["OWNER_REGISTERED"]]
    if len(set(owner_ids)) != ATTEMPTS:
        raise ValueError("physical owner identity reused across retry attempts")
    for n, row in enumerate(origin):
        owner = owner_ids[n]
        start = matches["ATTEMPT_STARTED"][n]
        done = matches["ATTEMPT_COMPLETED"][n]
        end = matches["OWNER_COMPLETED"][n]
        if any(e.get("fetchId") != owner for e in (start, done, end)):
            raise ValueError("FetchBroker changed physical owner identity")
        correlation = f"{owner}:attempt-1"
        if start.get("attemptCorrelationId") != correlation:
            raise ValueError("fetch attempt lineage mismatch")
        if done.get("attemptCorrelationId") != correlation:
            raise ValueError("finished fetch attempt lineage mismatch")
        if done.get("attempt") != 1 or start.get("attempt") != 1:
            raise ValueError("FetchBroker spawned an internal retry")
        if str(done.get("transportCorrelationId")) != str(row["requestId"]):
            raise ValueError("origin request ID does not match physical owner")
        expected_outcome = "READ_TIMEOUT" if n < 2 else "SUCCESS"
        if done.get("outcome") != expected_outcome:
            raise ValueError("unexpected physical transport outcome")
        if end.get("outcome") != expected_outcome:
            raise ValueError("unexpected physical owner terminal outcome")
        if n >= 2:
            length = ([units[0]] * 3 + units[1:])[n].length
            if done.get("networkBytes") != length:
                raise ValueError("restored FetchBroker byte count incorrect")
            if done.get("uniqueRangeBytes") != length:
                raise ValueError("restored unique byte count incorrect")
        elif done.get("uniqueRangeBytes") != 0:
            raise ValueError("blocked owner exposed incomplete bytes")
    if any(r["event"] == "CONSUMER_JOINED" for r in events):
        raise ValueError("a second FetchBroker consumer bypassed RecoveryCoordinator")
    priority = [r for r in events if r["event"] == "PRIORITY_RAISED"]
    if len(priority) != 1 or (
        priority[0].get("fetchKey") != units[0].fetch_key
    ):
        raise ValueError("playback did not escalate first reserve owner")


def verify_chain(
    budget: Mapping[str, Any],
    failures: Mapping[str, Any],
    fetch: list[dict[str, Any]],
    origin: list[Mapping[str, Any]],
    units: list[Any],
) -> dict[str, Any]:
    independent = verify_recovery(
        failures,
        budget,
        fetch,
        origin=list(origin),
    )
    if independent["status"] != "PASS":
        raise ValueError("independent M2 recovery oracle rejected N4R")
    if independent["physicalAttemptCount"] != ATTEMPTS:
        raise ValueError("independent physical attempt count mismatch")
    if independent["chargedAttemptCount"] != ATTEMPTS:
        raise ValueError("recovery budget did not charge each actual request")
    if independent["chainCount"] != len(units):
        raise ValueError("reserve created a competing retry chain")
    if independent["failureCount"] != 2:
        raise ValueError("N4R did not produce two real failures")
    if independent["terminalCounts"] != {"SUCCESS": len(units)}:
        raise ValueError("not every reserve recovery chain terminated successfully")
    for key in ("M2-ACC-05", "M2-ACC-06"):
        if independent["gates"][key]["status"] != "PASS":
            raise ValueError(f"N4R recovery proof did not satisfy {key}")

    rows = budget["events"]
    first_key = units[0].fetch_key
    first = [r for r in rows if r.get("fetchKey") == first_key]
    if len({r["recoveryChainId"] for r in first}) != 1:
        raise ValueError("first retry changed logical RecoveryChain identity")
    joined = [
        r["consumerKind"] for r in first if r["kind"] == "CONSUMER_JOINED"
    ]
    if joined != ["RESERVE", "PLAYBACK"]:
        raise ValueError("playback did not join existing reserve chain")
    if len([r for r in first if r["kind"] == "PRIORITY_RAISED"]) != 1:
        raise ValueError("joined RecoveryChain did not escalate playback priority")
    if len([r for r in first if r["kind"] == "OWNER_STARTED"]) != 3:
        raise ValueError("missing two physical retries within one chain")
    if len([r for r in first if r["kind"] == "CHARGE"]) != 3:
        raise ValueError("retry owner opened without a budget charge")
    if [r.get("ownerOrdinal") for r in first if r["kind"] == "OWNER_STARTED"] != [
        1, 2, 3
    ]:
        raise ValueError("retry ordinals were reset")
    observed = [
        row["observation"].get("kind") for row in failures["failures"]
    ]
    if observed != ["READ_TIMEOUT", "READ_TIMEOUT"]:
        raise ValueError("non-timeout observations masqueraded as N4R body outage")
    return independent


def verify(
    summary: Mapping[str, Any],
    fetch: list[dict[str, Any]],
    budget: Mapping[str, Any],
    failures: Mapping[str, Any],
    origin: list[Mapping[str, Any]],
    gate: list[Mapping[str, Any]],
    committed: Mapping[str, Any],
    verified: Mapping[str, Any],
    oracle: Mapping[str, Any],
    manifest: Mapping[str, Any],
    mpd: bytes,
) -> dict[str, Any]:
    units, expected_end_us = expected_f1_units(mpd, manifest)
    expected_keys = [u.fetch_key for u in units]
    expected_summary = {
        "schemaVersion": 1,
        "sessionId": SESSION,
        "mediaAssetId": "fixture:F1",
        "targetReserveUs": TARGET_US,
        "initialReserveUs": 0,
        "blockedReserveUs": 0,
        "finalReserveUs": expected_end_us,
        "expectedPlayableEndUs": expected_end_us,
        "physicalAttempts": ATTEMPTS,
        "readTimeoutCount": 2,
        "requestedFetchKeys": expected_keys,
        "replayRequestedFetchKeys": [],
        "playbackJoinedFetchKey": expected_keys[0],
        "committedExtentCount": len(units),
        "blockedBodyObserved": True,
    }
    if set(summary) != set(expected_summary):
        raise ValueError("N4R summary contract is incomplete")
    for name, value in expected_summary.items():
        if summary[name] != value:
            raise ValueError(f"N4R summary {name} disagrees with F1 expectation")

    ordered = verify_origin(origin, units)
    verify_gate(gate, ordered)
    verify_storage(committed, verified, oracle, units, expected_end_us)
    verify_fetch(fetch, ordered, units)
    evidence = verify_chain(budget, failures, fetch, ordered, units)
    return {
        "schemaVersion": 1,
        "status": "PASS",
        "scenario": "M4-A1b/N4R",
        "sessionId": SESSION,
        "originRequestCount": ATTEMPTS,
        "logicalRecoveryChains": len(units),
        "failedReadTimeoutAttempts": 2,
        "independentDurableReserveUs": expected_end_us,
        "gateBlockReleasePairs": 2,
        "recoveryGates": {
            key: evidence["gates"][key]["status"]
            for key in ("M2-ACC-05", "M2-ACC-06")
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    for option in (
        "summary", "fetch-events", "recovery-budget", "failure-events",
        "origin-trace", "gate-trace", "committed", "verified-files",
        "oracle", "manifest", "mpd", "output",
    ):
        parser.add_argument("--" + option, required=True, type=pathlib.Path)
    args = parser.parse_args()
    result = verify(
        summary=json.loads(args.summary.read_text(encoding="utf-8")),
        fetch=read_jsonl(args.fetch_events),
        budget=json.loads(args.recovery_budget.read_text(encoding="utf-8")),
        failures=json.loads(args.failure_events.read_text(encoding="utf-8")),
        origin=read_jsonl(args.origin_trace),
        gate=read_jsonl(args.gate_trace),
        committed=json.loads(args.committed.read_text(encoding="utf-8")),
        verified=json.loads(args.verified_files.read_text(encoding="utf-8")),
        oracle=json.loads(args.oracle.read_text(encoding="utf-8")),
        manifest=json.loads(args.manifest.read_text(encoding="utf-8")),
        mpd=args.mpd.read_bytes(),
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, sort_keys=True, indent=2) + "\n")
    print(
        f"M4 N4R PASS: {result['originRequestCount']} physical requests, "
        f"{result['logicalRecoveryChains']} recovery chains, "
        f"{result['failedReadTimeoutAttempts']} read timeouts, "
        f"{result['independentDurableReserveUs']} us durable reserve"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
