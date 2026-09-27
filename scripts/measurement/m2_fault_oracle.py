#!/usr/bin/env python3
"""Independent M2-E fault-evidence oracle.

This module intentionally does not import the transport/network harness
implementations or their tool-control clients. It derives expected tool state
from the frozen M2 scenario contract and compares that expectation with
retained readback.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys
from typing import Any, Mapping

from m2_contracts import (
    M2ContractError,
    scan_evidence_privacy,
    scenario_sha256,
    validate_m2e_harness_binding,
    validate_run_manifest_semantics,
    validate_scenario_semantics,
)
from schema_subset import validate_instance

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCHEMAS = ROOT / ".work" / "schemas"
RESOURCE_LENGTH = 81_811

TRANSPORT_VARIANTS: dict[str, tuple[str, str, str]] = {
    "TRANSPORT_READ_TIMEOUT": ("READ_TIMEOUT", "timeout", "DOWNSTREAM"),
    "TRANSPORT_RESET": ("RESET_PEER", "reset_peer", "DOWNSTREAM"),
    "TRUNCATED_STREAM": ("LIMIT_DATA", "limit_data", "DOWNSTREAM"),
    "SLOW_CLOSE": ("SLOW_CLOSE", "slow_close", "DOWNSTREAM"),
}

PROVIDER_ACTIONS = {
    "REFRESH_DELIVERY_BINDING",
    "RERESOLVE_PROVIDER",
    "WAIT_UNTIL_PROVIDER",
}


class FaultOracleError(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise FaultOracleError(message)


def load_object(path: pathlib.Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise FaultOracleError(f"{path}: invalid JSON: {error}") from error
    if not isinstance(value, dict):
        raise FaultOracleError(f"{path}: expected JSON object")
    return value


def load_jsonl(path: pathlib.Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    try:
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip():
                continue
            value = json.loads(line)
            if not isinstance(value, dict):
                raise FaultOracleError(f"{path}:{number}: expected JSON object")
            rows.append(value)
    except (OSError, json.JSONDecodeError) as error:
        raise FaultOracleError(f"{path}: invalid JSONL: {error}") from error
    return rows


def canonical_hash(value: Any) -> str:
    encoded = json.dumps(
        value,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def marker(path: pathlib.Path, expected: str) -> bool:
    try:
        return path.read_text(encoding="utf-8").strip() == expected
    except OSError:
        return False


def read_counter(path: pathlib.Path) -> int:
    try:
        value = int(path.read_text(encoding="utf-8").strip())
    except (OSError, ValueError) as error:
        raise FaultOracleError(f"{path}: invalid counter") from error
    require(value >= 0, f"{path}: counter must be non-negative")
    return value


def validate_schema(name: str, document: Mapping[str, Any]) -> None:
    schema = load_object(SCHEMAS / name)
    validate_instance(schema, document)


def expected_transport_fault(
    scenario: Mapping[str, Any],
) -> tuple[dict[str, Any], str, str, dict[str, int]]:
    """Independently compile frozen TRANSPORT semantics for verification."""
    validate_scenario_semantics(scenario)
    require(scenario.get("primaryPlane") == "TRANSPORT", "primaryPlane must be TRANSPORT")

    faults = scenario.get("transportFaults")
    require(isinstance(faults, list) and len(faults) == 1, "exactly one TRANSPORT fault required")
    for field in (
        "deliveryFaults",
        "networkFaults",
        "providerFaults",
        "routeFaults",
        "storageFaults",
    ):
        require(not scenario.get(field), f"canonical E2 run contains non-TRANSPORT faults in {field}")

    fault = faults[0]
    require(isinstance(fault, dict), "transport fault must be an object")
    require(fault.get("plane") == "TRANSPORT", "transport fault plane mismatch")
    require(fault.get("stochastic") is False, "canonical E2 transport fault must be deterministic")
    require(scenario.get("randomSeed") is None, "deterministic TRANSPORT scenario must not carry a seed")

    variant = scenario.get("variant")
    require(variant in TRANSPORT_VARIANTS, f"unsupported E2 variant {variant!r}")
    expected_kind, toxic_type, direction = TRANSPORT_VARIANTS[str(variant)]
    require(fault.get("kind") == expected_kind, f"{variant} fault kind mismatch")

    params = fault.get("parameters")
    require(isinstance(params, dict), "fault parameters must be an object")
    require(params.get("direction") == direction, f"{variant} direction mismatch")
    require(params.get("scope") == "MEDIA_DATA_ONLY", f"{variant} scope mismatch")

    if variant in {"TRANSPORT_READ_TIMEOUT", "TRANSPORT_RESET"}:
        require(params.get("timeoutMs") == 0, f"{variant} requires timeoutMs=0")
        attributes = {"timeout": 0}
    elif variant == "TRUNCATED_STREAM":
        value = params.get("limitBytes")
        require(
            isinstance(value, int) and not isinstance(value, bool) and 0 < value < RESOURCE_LENGTH,
            "TRUNCATED_STREAM limitBytes must truncate the canonical fixture",
        )
        attributes = {"bytes": int(value)}
    else:
        value = params.get("delayMs")
        require(
            isinstance(value, int) and not isinstance(value, bool) and value > 0,
            "SLOW_CLOSE requires positive delayMs",
        )
        attributes = {"delay": int(value)}

    return fault, toxic_type, direction, attributes


def verify_transport(args: argparse.Namespace) -> dict[str, Any]:
    scenario = load_object(args.scenario)
    manifest = load_object(args.run_manifest)
    harness = load_object(args.harness)
    active = load_object(args.active_state)
    clean = load_object(args.clean_state)
    failures = load_object(args.failures)
    budget = load_object(args.budget)
    result = load_object(args.transport_result)
    fetch_rows = load_jsonl(args.fetch)
    origin_rows = load_jsonl(args.origin)

    validate_schema("m2-run-manifest-v1.schema.json", manifest)
    validate_schema("fault-harness-events-v1.schema.json", harness)
    validate_run_manifest_semantics(manifest, scenario)
    fault, toxic_type, direction, attributes = expected_transport_fault(scenario)

    expected_hash = scenario_sha256(scenario)
    require(manifest["scenario"]["hash"] == expected_hash, "manifest scenario hash mismatch")
    require(harness.get("scenarioHash") == expected_hash, "harness scenarioHash mismatch")
    require(harness.get("runId") == manifest.get("runId"), "harness/manifest runId mismatch")
    require(harness.get("sessionId") == manifest.get("sessionId"), "harness/manifest sessionId mismatch")
    require(failures.get("runId") == manifest.get("runId"), "failure/manifest runId mismatch")
    require(failures.get("sessionId") == manifest.get("sessionId"), "failure/manifest sessionId mismatch")
    require(budget.get("runId") == manifest.get("runId"), "budget/manifest runId mismatch")
    require(budget.get("sessionId") == manifest.get("sessionId"), "budget/manifest sessionId mismatch")

    harness_meta = harness.get("harness") or {}
    validate_m2e_harness_binding(
        "TRANSPORT",
        str(harness_meta.get("harnessId")),
        str(harness_meta.get("toolId")),
    )
    require(harness_meta.get("harnessVersion") == "1", "unexpected harness version")
    require(harness_meta.get("toolVersion") == "2.12.0", "unexpected Toxiproxy version")
    require(harness.get("clockDomain") == "HOST_FAULT_MONOTONIC", "wrong harness clock domain")

    events = harness.get("events") or []
    require(len(events) == 5, "canonical transport lifecycle must contain five events")
    require(
        [event.get("sequence") for event in events] == [1, 2, 3, 4, 5],
        "fault event sequence is not contiguous",
    )
    timestamps = [event.get("elapsedRealtimeNs") for event in events]
    require(
        all(isinstance(value, int) and not isinstance(value, bool) and value >= 0 for value in timestamps),
        "invalid HOST_FAULT_MONOTONIC timestamp",
    )
    require(
        all(b >= a for a, b in zip(timestamps, timestamps[1:])),
        "HOST_FAULT_MONOTONIC time regressed",
    )
    expected_ops = [
        "HARNESS_STARTED",
        "FAULT_ARMED",
        "FAULT_APPLIED",
        "FAULT_REMOVED",
        "HARNESS_STOPPED",
    ]
    require([event.get("operation") for event in events] == expected_ops, "unexpected harness lifecycle")

    for event in events:
        require(event.get("plane") == "TRANSPORT", "harness emitted non-TRANSPORT event")
        require(event.get("faultId") == fault.get("faultId"), "faultId mismatch")
        require(event.get("kind") == fault.get("kind"), "fault kind mismatch")
        require(event.get("scope") == "MEDIA_DATA_ONLY", "fault scope mismatch")

    require(events[0].get("direction") == "NONE", "HARNESS_STARTED direction mismatch")
    require(events[1].get("direction") == direction, "FAULT_ARMED direction mismatch")
    require(events[2].get("direction") == direction, "FAULT_APPLIED direction mismatch")
    require(events[4].get("direction") == "NONE", "HARNESS_STOPPED direction mismatch")

    expected_parameters = {"toxicType": toxic_type, **attributes}
    require(events[1].get("parameters") == expected_parameters, "FAULT_ARMED parameters mismatch")
    require(events[2].get("parameters") == expected_parameters, "FAULT_APPLIED parameters mismatch")
    require(events[2].get("result") == "APPLIED", "fault was not reported applied")
    require(events[3].get("result") == "REMOVED", "fault was not reported removed")
    require(events[4].get("result") == "OK", "harness did not stop cleanly")

    expected_active = {
        "name": "m2e-media",
        "enabled": True,
        "toxics": [
            {
                "name": "m2e-fault",
                "type": toxic_type,
                "stream": direction.lower(),
                "toxicityPpm": 1_000_000,
                "attributes": attributes,
            }
        ],
    }
    require(active == expected_active, f"active Toxiproxy readback mismatch: {active!r}")
    require(
        events[2].get("observedStateHash") == canonical_hash(active),
        "FAULT_APPLIED observed state hash mismatch",
    )
    require(clean == {"proxies": 0, "toxics": 0}, f"cleanup state mismatch: {clean!r}")
    require(
        events[4].get("observedStateHash") == canonical_hash(clean),
        "HARNESS_STOPPED observed state hash mismatch",
    )

    failure_rows = failures.get("failures") or []
    require(isinstance(failure_rows, list), "failure rows must be an array")
    for row in failure_rows:
        observation = row.get("observation") or {}
        require(observation.get("plane") == "TRANSPORT", "runtime failure plane is not TRANSPORT")
        require(row.get("classification") == "TRANSIENT_TRANSPORT", "persistent E2 fault was not transient transport")
        decision = row.get("decision") or {}
        require(decision.get("kind") not in PROVIDER_ACTIONS, "pure transport fault triggered provider action")

    attempts = sum(1 for row in fetch_rows if row.get("event") == "ATTEMPT_STARTED")
    require(attempts > 0, "no physical fetch attempt was observed")
    require(result.get("physicalAttempts") == attempts, "transport result/fetch attempt count mismatch")

    variant = str(scenario["variant"])
    data_rows = [
        row for row in origin_rows
        if row.get("plane") == "data" and row.get("method") == "GET"
    ]
    require(len(data_rows) <= attempts, "origin requests exceed physical attempts")

    if variant == "SLOW_CLOSE":
        require(result.get("terminalReason") == "SUCCESS", "SLOW_CLOSE must preserve completed body")
        require(result.get("published") is True, "SLOW_CLOSE complete body was not published")
        require(attempts == 1, "SLOW_CLOSE must complete in one physical attempt")
        require(not failure_rows, "SLOW_CLOSE success unexpectedly recorded failures")
        require(data_rows, "SLOW_CLOSE never reached deterministic origin")
    else:
        require(result.get("terminalReason") == "BUDGET_EXHAUSTED", "persistent fault did not exhaust one recovery chain")
        require(result.get("published") is False, "failed transport run published incomplete media")
        require(attempts == 4, f"persistent fault used {attempts} attempts, expected 4")
        require(len(failure_rows) == attempts, "each failed physical attempt must have one failure decision")
        if variant == "TRUNCATED_STREAM":
            require(data_rows, "TRUNCATED_STREAM never reached deterministic origin")

    before_packets = read_counter(args.media_packets_before)
    after_packets = read_counter(args.media_packets_after)
    require(after_packets > before_packets, "namespace media veth did not carry Android transport traffic")

    control_ok = all(
        (
            marker(args.adb_before, "device"),
            marker(args.adb_during, "device"),
            marker(args.adb_after, "device"),
            marker(args.control_before, "ok"),
            marker(args.control_during, "ok"),
            marker(args.control_after, "ok"),
            marker(args.media_reverse_absent, "ok"),
            marker(args.artifact_collection, "ok"),
        )
    )
    require(control_ok, "ADB/control/media-reverse isolation proof failed")
    require(marker(args.cleanup, "ok"), "transport cleanup proof failed")

    for document in (scenario, manifest, harness, active, clean, result):
        scan_evidence_privacy(document)

    checks = {
        "scenarioIdentity": True,
        "faultOwnership": True,
        "toolStateMatches": True,
        "mediaPathTraversed": True,
        "controlPathUnimpaired": True,
        "seedBound": True,
        "cleanupComplete": True,
        "privacyClean": True,
        "crossClockArithmeticAbsent": True,
    }
    summary = {
        "schemaVersion": 1,
        "runId": manifest["runId"],
        "sessionId": manifest["sessionId"],
        "scenarioHash": expected_hash,
        "status": "PASS",
        "primaryPlane": "TRANSPORT",
        "checks": checks,
        "gates": {
            "M2-ACC-01": True,
            "M2-ACC-02": True,
            "M2-ACC-09": all(checks.values()),
        },
        "limitations": [
            "API 36 emulator correctness and fault-attribution evidence; not representative transport performance."
        ],
    }
    validate_schema("fault-verification-summary-v1.schema.json", summary)
    scan_evidence_privacy(summary)
    return summary


def add_marker_arguments(parser: argparse.ArgumentParser) -> None:
    for name in (
        "adb-before",
        "adb-during",
        "adb-after",
        "control-before",
        "control-during",
        "control-after",
        "media-reverse-absent",
        "artifact-collection",
        "cleanup",
    ):
        parser.add_argument(f"--{name}", required=True, type=pathlib.Path)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    verify = sub.add_parser("verify-transport")
    verify.add_argument("--scenario", required=True, type=pathlib.Path)
    verify.add_argument("--run-manifest", required=True, type=pathlib.Path)
    verify.add_argument("--harness", required=True, type=pathlib.Path)
    verify.add_argument("--active-state", required=True, type=pathlib.Path)
    verify.add_argument("--clean-state", required=True, type=pathlib.Path)
    verify.add_argument("--failures", required=True, type=pathlib.Path)
    verify.add_argument("--budget", required=True, type=pathlib.Path)
    verify.add_argument("--fetch", required=True, type=pathlib.Path)
    verify.add_argument("--transport-result", required=True, type=pathlib.Path)
    verify.add_argument("--origin", required=True, type=pathlib.Path)
    verify.add_argument("--media-packets-before", required=True, type=pathlib.Path)
    verify.add_argument("--media-packets-after", required=True, type=pathlib.Path)
    verify.add_argument("--output", required=True, type=pathlib.Path)
    add_marker_arguments(verify)
    args = parser.parse_args(argv)

    try:
        summary = verify_transport(args)
    except (FaultOracleError, M2ContractError, ValueError) as error:
        print(f"M2-E fault verification failed: {error}", file=sys.stderr)
        return 1

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(summary, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(
        "M2-E fault verification PASS: "
        f"{summary['primaryPlane']} {summary['scenarioHash']} "
        f"ACC-01={summary['gates']['M2-ACC-01']} "
        f"ACC-02={summary['gates']['M2-ACC-02']} "
        f"ACC-09={summary['gates']['M2-ACC-09']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
