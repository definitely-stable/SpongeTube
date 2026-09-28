#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[2]
PREFLIGHT_FIXTURE = REPO_ROOT / "test-fixtures" / "media" / "F0" / "progressive.mp4"
PREFLIGHT_PATH = "/fixtures/F0/progressive.mp4"
PREFLIGHT_RANGE = "bytes=0-4095"
PREFLIGHT_BYTES = 4096
EXPECTED_STATUS = 206

SCENARIOS = {
    "VPN_RESTORE": {
        "path": "/fixtures/F1/segment-1-00001.m4s",
        "range": "bytes=0-81810",
        "bytes": 81811,
        "second_reason": "ROUTE_READY",
        "override": False,
    },
    "DIRECT_OVERRIDE": {
        "path": "/fixtures/F1/segment-1-00002.m4s",
        "range": "bytes=0-82462",
        "bytes": 82463,
        "second_reason": "EXPLICIT_DIRECT_OVERRIDE",
        "override": True,
    },
}

FORBIDDEN_PORTABLE_TOKENS = (
    "http://",
    "https://",
    "192.0.2.",
    "198.18.",
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
    require(isinstance(value, dict), f"{path}: expected object")
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
    text = path.read_text(encoding="utf-8")
    for token in FORBIDDEN_PORTABLE_TOKENS:
        require(token not in text, f"{path}: leaked endpoint material {token!r}")


def canonical_preflight_hash() -> str:
    try:
        data = PREFLIGHT_FIXTURE.read_bytes()[:PREFLIGHT_BYTES]
    except OSError as error:
        raise VerificationError(f"cannot read F0 fixture: {error}") from error
    require(len(data) == PREFLIGHT_BYTES, "preflight fixture is too short")
    return hashlib.sha256(data).hexdigest()


def verify_scenario(root: Path, scenario_id: str) -> dict[str, Any]:
    expected = SCENARIOS[scenario_id]
    directory = root / scenario_id
    case_path = directory / "case.json"
    route_path = directory / "route-events.json"
    budget_path = directory / "recovery-budget-events.json"
    failure_path = directory / "failure-decision-events.json"
    fetch_path = directory / "fetch-events.jsonl"

    case = load_object(case_path)
    route = load_object(route_path)
    budget = load_object(budget_path)
    failure = load_object(failure_path)
    fetch = load_jsonl(fetch_path)

    require(case.get("schemaVersion") == 1, f"{scenario_id}: case schema mismatch")
    require(case.get("phase") == "M2-F3", f"{scenario_id}: wrong phase")
    require(case.get("scenarioId") == scenario_id, f"{scenario_id}: case id mismatch")
    require(case.get("deviceApi") == 36, f"{scenario_id}: must run on API 36")
    require(case.get("status") == "PASS", f"{scenario_id}: device case did not pass")
    require(case.get("mediaPathUsesAdbReverse") is False, f"{scenario_id}: media used adb reverse")
    require(case.get("monitorStopped") is True, f"{scenario_id}: monitor did not stop")

    direct0 = case.get("initialDirectEpoch")
    vpn0 = case.get("initialVpnEpoch")
    direct1 = case.get("directReplacementEpoch")
    resume = case.get("resumeEpoch")
    for name, value in (
        ("initialDirectEpoch", direct0),
        ("initialVpnEpoch", vpn0),
        ("directReplacementEpoch", direct1),
        ("resumeEpoch", resume),
    ):
        require(
            isinstance(value, int) and not isinstance(value, bool) and value > 0,
            f"{scenario_id}: invalid {name}",
        )
    require(direct0 < vpn0 < direct1, f"{scenario_id}: initial route epochs are not monotonic")
    if scenario_id == "VPN_RESTORE":
        require(resume > direct1, "VPN_RESTORE: restored VPN epoch did not advance")
        require(case.get("resumedVpnReflectedPackets", 0) > 0, "VPN_RESTORE: owner 2 did not cross VPN TUN")
        require(case.get("resumedVpnReflectedBytes", 0) > 0, "VPN_RESTORE: owner 2 VPN byte count is zero")
    else:
        require(resume == direct1, "DIRECT_OVERRIDE: override must reuse current direct epoch")
        require(
            case.get("directPauseRouteEventWatermark") ==
            case.get("resumeRouteEventWatermark"),
            "DIRECT_OVERRIDE: override required a new route event instead of waking the paused gate",
        )

    require(case.get("resumePermitReason") == expected["second_reason"], f"{scenario_id}: wrong second permit reason")
    require(case.get("explicitDirectOverride") is expected["override"], f"{scenario_id}: final override state mismatch")
    require(case.get("vpnPreflightStatus") == EXPECTED_STATUS, f"{scenario_id}: VPN preflight status mismatch")
    require(case.get("vpnPreflightBytes") == PREFLIGHT_BYTES, f"{scenario_id}: VPN preflight byte count mismatch")
    require(case.get("vpnPreflightBodySha256") == canonical_preflight_hash(), f"{scenario_id}: VPN preflight bytes mismatch")
    require(case.get("initialVpnReflectedPackets", 0) > 0, f"{scenario_id}: VPN preflight did not cross TUN")
    require(case.get("initialVpnReflectedBytes", 0) > 0, f"{scenario_id}: VPN preflight TUN bytes are zero")
    require(case.get("gateCalls") == 2, f"{scenario_id}: expected two gate calls")
    require(case.get("chargesWhilePaused") == 1, f"{scenario_id}: charge occurred while VPN continuity paused")
    require(case.get("attemptsWhilePaused") == 1, f"{scenario_id}: owner started while VPN continuity paused")

    sentinel = case.get("sentinelExtentId")
    target = case.get("targetExtentId")
    before = case.get("persistedExtentIdsBefore")
    after = case.get("persistedExtentIdsAfter")
    require(isinstance(before, list) and sentinel in before, f"{scenario_id}: sentinel absent before")
    require(isinstance(after, list) and sentinel in after, f"{scenario_id}: sentinel invalidated")
    require(target in after, f"{scenario_id}: target not committed")
    require(set(before).issubset(set(after)), f"{scenario_id}: persisted media was invalidated")

    require(route.get("schemaVersion") == 1, f"{scenario_id}: route schema mismatch")
    require(route.get("androidApi") == 36, f"{scenario_id}: route API mismatch")
    require(route.get("clockDomain") == "ANDROID_MONOTONIC", f"{scenario_id}: route clock mismatch")
    events = route.get("events")
    evaluations = route.get("policyEvaluations")
    require(isinstance(events, list) and events, f"{scenario_id}: route events empty")
    require(isinstance(evaluations, list) and evaluations, f"{scenario_id}: policy evaluations empty")
    require(events[-1].get("signal") == "MONITOR_STOPPED", f"{scenario_id}: final route event is not MONITOR_STOPPED")

    available = [
        row.get("runtimeStateAfter")
        for row in events
        if isinstance(row, dict) and isinstance(row.get("runtimeStateAfter"), dict)
    ]
    require(
        any(state.get("routeEpoch") == vpn0 and state.get("vpn") == "TRUE" for state in available),
        f"{scenario_id}: initial VPN epoch not observed",
    )
    require(
        any(state.get("routeEpoch") == direct1 and state.get("vpn") == "FALSE" for state in available),
        f"{scenario_id}: direct replacement not observed",
    )

    direct_pauses = [
        row for row in evaluations
        if isinstance(row, dict)
        and row.get("routeEpoch") == direct1
        and row.get("decision") == "PAUSE"
        and row.get("reason") == "VPN_CONTINUITY_REQUIRED"
    ]
    require(direct_pauses, f"{scenario_id}: no VPN continuity pause on direct replacement")

    resumed = [
        row for row in evaluations
        if isinstance(row, dict)
        and row.get("routeEpoch") == resume
        and row.get("decision") == "ALLOW"
        and row.get("reason") == expected["second_reason"]
    ]
    require(resumed, f"{scenario_id}: expected resume policy evaluation missing")
    require(
        resumed[-1].get("explicitDirectOverride") is expected["override"],
        f"{scenario_id}: policy override evidence mismatch",
    )

    require(budget.get("schemaVersion") == 1, f"{scenario_id}: budget schema mismatch")
    budget_events = budget.get("events")
    require(isinstance(budget_events, list) and budget_events, f"{scenario_id}: budget events empty")
    chain_ids = {
        row.get("recoveryChainId")
        for row in budget_events
        if isinstance(row, dict)
    }
    require(chain_ids == {case.get("recoveryChainId")}, f"{scenario_id}: RecoveryChain identity changed")
    require(
        sum(1 for row in budget_events if row.get("kind") == "CHAIN_STARTED") == 1,
        f"{scenario_id}: expected one chain start",
    )
    charges = [row for row in budget_events if row.get("kind") == "CHARGE"]
    require(len(charges) == 2, f"{scenario_id}: expected two charges")
    require(
        [row.get("charge", {}).get("dimension") for row in charges] == ["REMOTE_ATTEMPT", "REMOTE_ATTEMPT"],
        f"{scenario_id}: wrong budget dimension",
    )
    require(
        [row.get("spent", {}).get("REMOTE_ATTEMPT") for row in charges] == [1, 2],
        f"{scenario_id}: REMOTE_ATTEMPT ledger reset",
    )
    permits = [
        row.get("permit")
        for row in budget_events
        if row.get("kind") == "ATTEMPT_PERMIT_GRANTED"
    ]
    require(len(permits) == 2, f"{scenario_id}: expected two permits")
    require(
        [permits[0].get("routeEpoch"), permits[1].get("routeEpoch")] == [vpn0, resume],
        f"{scenario_id}: permit epochs mismatch",
    )
    require(permits[0].get("reason") == "ROUTE_READY", f"{scenario_id}: first permit was not VPN route ready")
    require(permits[1].get("reason") == expected["second_reason"], f"{scenario_id}: second permit reason mismatch")
    terminals = [row for row in budget_events if row.get("kind") == "CHAIN_TERMINATED"]
    require(len(terminals) == 1 and terminals[0].get("terminalReason") == "SUCCESS", f"{scenario_id}: chain terminal mismatch")

    require(failure.get("schemaVersion") == 2, f"{scenario_id}: failure schema mismatch")
    failures = failure.get("failures")
    require(isinstance(failures, list) and len(failures) == 1, f"{scenario_id}: expected one old-VPN failure")
    first_failure = failures[0]
    require(first_failure.get("routeEpoch") == vpn0, f"{scenario_id}: failure lost initial VPN epoch")
    require(first_failure.get("classification") == "TRANSIENT_TRANSPORT", f"{scenario_id}: old VPN failure classification mismatch")
    require(first_failure.get("action", {}).get("kind") == "SCHEDULE_BACKOFF", f"{scenario_id}: retry ownership escaped coordinator")

    starts = [row for row in fetch if row.get("event") == "ATTEMPT_STARTED"]
    require(len(starts) == 2, f"{scenario_id}: expected two physical owners")
    require(len({row.get("fetchId") for row in starts}) == 2, f"{scenario_id}: owners did not get distinct fetch ids")
    require(len({row.get("fetchKey") for row in starts}) == 1, f"{scenario_id}: fetch identity changed")

    for path in (case_path, route_path, budget_path, failure_path, fetch_path):
        privacy_scan(path)

    return {
        "scenarioId": scenario_id,
        "recoveryChainId": case.get("recoveryChainId"),
        "initialVpnEpoch": vpn0,
        "directReplacementEpoch": direct1,
        "resumeEpoch": resume,
    }


def verify_origin_trace(path: Path) -> None:
    rows = load_jsonl(path)
    data_rows = [
        row for row in rows
        if row.get("plane") == "data" and row.get("method") == "GET"
    ]
    require(len(data_rows) == 4, f"expected four data GETs (2 preflight + 2 recovery), got {len(data_rows)}")

    preflight = [row for row in data_rows if row.get("path") == PREFLIGHT_PATH]
    require(len(preflight) == 2, f"expected two VPN preflight GETs, got {len(preflight)}")
    for row in preflight:
        require(row.get("rangeHeader") == PREFLIGHT_RANGE, "VPN preflight range mismatch")
        require(row.get("status") == EXPECTED_STATUS, "VPN preflight origin status mismatch")
        require(row.get("bodyBytesWritten") == PREFLIGHT_BYTES, "VPN preflight origin bytes mismatch")

    for scenario_id, expected in SCENARIOS.items():
        matching = [row for row in data_rows if row.get("path") == expected["path"]]
        require(len(matching) == 1, f"{scenario_id}: expected exactly one recovery origin GET")
        row = matching[0]
        require(row.get("rangeHeader") == expected["range"], f"{scenario_id}: recovery range mismatch")
        require(row.get("status") == EXPECTED_STATUS, f"{scenario_id}: recovery status mismatch")
        require(row.get("bodyBytesWritten") == expected["bytes"], f"{scenario_id}: recovery byte count mismatch")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("evidence_dir", type=Path)
    parser.add_argument("origin_trace", type=Path)
    args = parser.parse_args()

    results = [
        verify_scenario(args.evidence_dir, scenario_id)
        for scenario_id in SCENARIOS
    ]
    verify_origin_trace(args.origin_trace)

    summary = {
        "schemaVersion": 1,
        "phase": "M2-F3",
        "status": "PASS",
        "scenarios": results,
        "checks": {
            "usableInitialVpnProved": True,
            "vpnContinuityPausedDirectReplacement": True,
            "noOwnerOrChargeWhilePaused": True,
            "restoredVpnResumedSameChain": True,
            "overrideWokeSameDirectEpoch": True,
            "ledgerMonotonic": True,
            "persistedMediaPreserved": True,
            "realOriginRequestsCrossChecked": True,
            "mediaAdbReverseAbsent": True,
            "portableEvidencePrivacyClean": True,
        },
    }
    output = args.evidence_dir / "verification.json"
    output.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("M2-F3 VPN continuity evidence verified")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except VerificationError as error:
        raise SystemExit(f"M2-F3 verification failed: {error}")
