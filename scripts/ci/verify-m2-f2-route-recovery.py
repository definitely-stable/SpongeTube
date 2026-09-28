#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

EXPECTED_PATH = "/fixtures/F1/segment-1-00001.m4s"
EXPECTED_RANGE = "bytes=0-81810"
EXPECTED_BYTES = 81811
EXPECTED_STATUS = 206
FORBIDDEN_PORTABLE_TOKENS = (
    "http://",
    "https://",
    "192.0.2.",
    "198.51.100.",
    "203.0.113.",
)


class VerificationError(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise VerificationError(message)


def load_object(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise VerificationError(f"{path}: invalid JSON: {error}") from error
    require(isinstance(value, dict), f"{path}: expected JSON object")
    return value


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    try:
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip():
                continue
            value = json.loads(line)
            require(isinstance(value, dict), f"{path}:{number}: expected object")
            rows.append(value)
    except (OSError, json.JSONDecodeError) as error:
        raise VerificationError(f"{path}: invalid JSONL: {error}") from error
    return rows


def privacy_scan(path: Path) -> None:
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as error:
        raise VerificationError(f"{path}: cannot read: {error}") from error
    for token in FORBIDDEN_PORTABLE_TOKENS:
        require(token not in text, f"{path}: leaked private endpoint token {token!r}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("evidence_dir", type=Path)
    parser.add_argument("origin_trace", type=Path)
    args = parser.parse_args()

    case_path = args.evidence_dir / "case.json"
    route_path = args.evidence_dir / "route-events.json"
    budget_path = args.evidence_dir / "recovery-budget-events.json"
    failure_path = args.evidence_dir / "failure-decision-events.json"
    fetch_path = args.evidence_dir / "fetch-events.jsonl"

    case = load_object(case_path)
    route = load_object(route_path)
    budget = load_object(budget_path)
    failure = load_object(failure_path)
    fetch = load_jsonl(fetch_path)
    origin = load_jsonl(args.origin_trace)

    require(case.get("schemaVersion") == 1, "case schema mismatch")
    require(case.get("phase") == "M2-F2", "wrong phase")
    require(case.get("deviceApi") == 36, "F2 must run on API 36")
    require(case.get("status") == "PASS", "device case did not pass")
    require(case.get("mediaPathUsesAdbReverse") is False, "media path used adb reverse")
    require(case.get("monitorStopped") is True, "route monitor was not stopped")
    initial_epoch = case.get("initialRouteEpoch")
    restored_epoch = case.get("restoredRouteEpoch")
    require(
        isinstance(initial_epoch, int) and not isinstance(initial_epoch, bool) and initial_epoch > 0,
        "invalid initial route epoch",
    )
    require(
        isinstance(restored_epoch, int) and not isinstance(restored_epoch, bool)
        and restored_epoch > initial_epoch,
        "replacement route epoch did not advance",
    )
    require(case.get("gateCalls") == 2, "expected exactly two RecoveryAttemptGate invocations")
    require(case.get("chargesBeforeRestore") == 1, "second charge occurred while route was paused")
    require(case.get("attemptsBeforeRestore") == 1, "second owner started while route was paused")

    sentinel = case.get("sentinelExtentId")
    target = case.get("targetExtentId")
    before = case.get("persistedExtentIdsBefore")
    after = case.get("persistedExtentIdsAfter")
    require(isinstance(before, list) and sentinel in before, "sentinel missing before route loss")
    require(isinstance(after, list) and sentinel in after, "sentinel invalidated by route replacement")
    require(target in after, "target extent was not committed after recovery")
    require(set(before).issubset(set(after)), "pre-existing committed media was invalidated")

    require(route.get("schemaVersion") == 1, "route-events schema mismatch")
    require(route.get("androidApi") == 36, "route-events API mismatch")
    require(route.get("clockDomain") == "ANDROID_MONOTONIC", "route clock domain mismatch")
    route_events = route.get("events")
    evaluations = route.get("policyEvaluations")
    require(isinstance(route_events, list) and route_events, "route-events is empty")
    require(isinstance(evaluations, list) and evaluations, "policy evaluations are empty")
    signals = [row.get("signal") for row in route_events if isinstance(row, dict)]
    require("LOST" in signals, "actual default route loss was not observed")
    require(signals[-1] == "MONITOR_STOPPED", "monitor stop is not the final route event")
    decisions = [
        (row.get("decision"), row.get("reason"), row.get("routeEpoch"))
        for row in evaluations
        if isinstance(row, dict)
    ]
    require(
        ("PAUSE", "NO_USABLE_DEFAULT", None) in decisions,
        "route policy never paused on no usable default",
    )
    allowed_epochs = [
        row.get("routeEpoch")
        for row in evaluations
        if isinstance(row, dict) and row.get("decision") == "ALLOW"
    ]
    require(initial_epoch in allowed_epochs, "initial epoch was not permitted")
    require(restored_epoch in allowed_epochs, "replacement epoch was not permitted")

    require(budget.get("schemaVersion") == 1, "budget schema mismatch")
    events = budget.get("events")
    require(isinstance(events, list) and events, "budget events are empty")
    chain_ids = {
        row.get("recoveryChainId")
        for row in events
        if isinstance(row, dict)
    }
    require(chain_ids == {case.get("recoveryChainId")}, "recovery chain identity changed")
    require(
        sum(1 for row in events if row.get("kind") == "CHAIN_STARTED") == 1,
        "expected one RecoveryChain start",
    )
    charges = [row for row in events if row.get("kind") == "CHARGE"]
    require(len(charges) == 2, f"expected two REMOTE_ATTEMPT charges, got {len(charges)}")
    require(
        [row.get("charge", {}).get("dimension") for row in charges] == ["REMOTE_ATTEMPT"] * 2,
        "unexpected budget dimension charged",
    )
    require(
        [row.get("spent", {}).get("REMOTE_ATTEMPT") for row in charges] == [1, 2],
        "REMOTE_ATTEMPT ledger reset or skipped",
    )
    permits = [
        row.get("permit")
        for row in events
        if row.get("kind") == "ATTEMPT_PERMIT_GRANTED"
    ]
    require(len(permits) == 2, "expected two granted permits")
    require(
        [permit.get("routeEpoch") for permit in permits] == [initial_epoch, restored_epoch],
        "permit route epochs do not match old -> replacement route",
    )
    terminals = [row for row in events if row.get("kind") == "CHAIN_TERMINATED"]
    require(len(terminals) == 1, "expected one terminal event")
    require(terminals[0].get("terminalReason") == "SUCCESS", "chain did not finish SUCCESS")

    require(failure.get("schemaVersion") == 2, "failure schema mismatch")
    failures = failure.get("failures")
    require(isinstance(failures, list) and len(failures) == 1, "expected one owner failure")
    first_failure = failures[0]
    require(first_failure.get("routeEpoch") == initial_epoch, "failure lost the old route epoch")
    require(
        first_failure.get("classification") == "TRANSIENT_TRANSPORT",
        "old-route failure was not classified as transient transport",
    )
    require(
        first_failure.get("action", {}).get("kind") == "SCHEDULE_BACKOFF",
        "first failure did not stay inside RecoveryCoordinator retry ownership",
    )

    starts = [row for row in fetch if row.get("event") == "ATTEMPT_STARTED"]
    require(len(starts) == 2, f"expected two physical owners, got {len(starts)}")
    require(len({row.get("fetchId") for row in starts}) == 2, "owners did not get distinct fetch ids")
    require(len({row.get("fetchKey") for row in starts}) == 1, "fetch identity changed across route replacement")

    data_rows = [
        row for row in origin
        if row.get("plane") == "data" and row.get("method") == "GET"
    ]
    require(len(data_rows) == 1, f"expected exactly one real origin GET, got {len(data_rows)}")
    origin_row = data_rows[0]
    require(origin_row.get("path") == EXPECTED_PATH, "origin path mismatch")
    require(origin_row.get("rangeHeader") == EXPECTED_RANGE, "origin range mismatch")
    require(origin_row.get("status") == EXPECTED_STATUS, "origin status mismatch")
    require(origin_row.get("bodyBytesWritten") == EXPECTED_BYTES, "origin bytes mismatch")

    for path in (case_path, route_path, budget_path, failure_path, fetch_path):
        privacy_scan(path)

    summary = {
        "schemaVersion": 1,
        "phase": "M2-F2",
        "status": "PASS",
        "checks": {
            "oneRecoveryChain": True,
            "routeLossObserved": True,
            "routePolicyPaused": True,
            "noOwnerWhilePaused": True,
            "replacementEpochPermitted": True,
            "ledgerMonotonicAcrossRouteReplacement": True,
            "oldRouteFailureAttributed": True,
            "preExistingMediaPreserved": True,
            "targetExtentCommitted": True,
            "singlePostRestoreOriginRequest": True,
            "mediaAdbReverseAbsent": True,
            "portableEvidencePrivacyClean": True,
        },
    }
    output = args.evidence_dir / "verification.json"
    output.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("M2-F2 route recovery evidence verified")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except VerificationError as error:
        raise SystemExit(f"M2-F2 verification failed: {error}")
