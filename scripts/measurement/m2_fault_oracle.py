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

BYPASS_PRIOMAP = [2] * 16

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



def expected_network_fault(
    scenario: Mapping[str, Any],
) -> tuple[dict[str, Any], dict[str, Any]]:
    """Independently compile frozen NETWORK semantics for verification."""
    validate_scenario_semantics(scenario)
    require(scenario.get("primaryPlane") == "NETWORK", "primaryPlane must be NETWORK")
    faults = scenario.get("networkFaults")
    require(isinstance(faults, list) and len(faults) == 1, "exactly one NETWORK fault required")
    for field in (
        "deliveryFaults",
        "transportFaults",
        "providerFaults",
        "routeFaults",
        "storageFaults",
    ):
        require(not scenario.get(field), f"canonical E3 run contains non-NETWORK faults in {field}")

    fault = faults[0]
    require(isinstance(fault, dict), "network fault must be an object")
    require(fault.get("plane") == "NETWORK", "network fault plane mismatch")
    params = fault.get("parameters") or {}
    require(params.get("scope") == "MEDIA_PATH_ONLY", "NETWORK scenario scope mismatch")
    require(params.get("direction") == "DOWNSTREAM", "NETWORK scenario direction mismatch")
    require(params.get("ipFamily") == "IPV4", "NETWORK scenario ipFamily mismatch")
    require(params.get("l4Protocol") == "TCP", "NETWORK scenario l4Protocol mismatch")
    variant = scenario.get("variant")
    seed = scenario.get("randomSeed")

    config: dict[str, Any] = {
        "direction": "DOWNSTREAM",
        "ipFamily": "IPV4",
        "l4Protocol": "TCP",
        "scope": "MEDIA_DATA_ONLY",
    }
    if variant == "HIGH_RTT_JITTER":
        require(fault.get("kind") == "HIGH_RTT_JITTER", "N2 fault kind mismatch")
        require(fault.get("stochastic") is True, "N2 jitter must be stochastic")
        require(seed == 424242, "N2 jitter seed must be 424242")
        require(params.get("delayUs") == 100000, "N2 delayUs mismatch")
        require(params.get("jitterUs") == 30000, "N2 jitterUs mismatch")
        require(params.get("delayCorrelationPpm") == 250000, "N2 correlation mismatch")
        config.update(
            delayUs=100000,
            jitterUs=30000,
            delayCorrelationPpm=250000,
            randomSeed=424242,
        )
    elif variant == "BURST_PACKET_LOSS":
        require(fault.get("kind") == "BURST_PACKET_LOSS", "N3 fault kind mismatch")
        require(fault.get("stochastic") is False, "N3 blackout must be deterministic")
        require(seed is None, "N3 blackout must be seedless")
        require(params.get("lossPpm") == 1000000, "N3 lossPpm mismatch")
        require(params.get("durationMs") == 1500, "N3 duration mismatch")
        config.update(lossPpm=1000000, durationMs=1500)
    elif variant == "BURST_LOSS":
        require(fault.get("kind") == "BURST_LOSS", "N5 fault kind mismatch")
        require(fault.get("stochastic") is True, "N5 burst loss must be stochastic")
        require(seed == 424242, "N5 seed must be 424242")
        require(params.get("lossPpm") == 20000, "N5 lossPpm mismatch")
        require(params.get("burstCorrelationPpm") == 250000, "N5 correlation mismatch")
        config.update(
            lossPpm=20000,
            burstCorrelationPpm=250000,
            randomSeed=424242,
        )
    else:
        raise FaultOracleError(f"unsupported NETWORK variant {variant!r}")

    validate_m2e_harness_binding("NETWORK", "sponge-network-harness", "netem")
    return fault, config


def _find_kind(value: Any, kind: str) -> dict[str, Any] | None:
    matches = _find_kinds(value, kind)
    return matches[0] if matches else None


def _find_kinds(value: Any, kind: str) -> list[dict[str, Any]]:
    matches: list[dict[str, Any]] = []
    if isinstance(value, dict):
        if value.get("kind") == kind:
            matches.append(value)
        for child in value.values():
            matches.extend(_find_kinds(child, kind))
    elif isinstance(value, list):
        for child in value:
            matches.extend(_find_kinds(child, kind))
    return matches


def _contains_key_value(value: Any, key: str, expected: Any) -> bool:
    if isinstance(value, dict):
        if value.get(key) == expected:
            return True
        return any(_contains_key_value(child, key, expected) for child in value.values())
    if isinstance(value, list):
        return any(_contains_key_value(child, key, expected) for child in value)
    return False


def _fraction_to_ppm(value: Any, name: str) -> int:
    require(
        isinstance(value, (int, float)) and not isinstance(value, bool),
        f"{name} readback must be numeric",
    )
    ppm = round(float(value) * 1_000_000)
    require(0 <= ppm <= 1_000_000, f"{name} readback outside [0,1]")
    return ppm


def _seconds_to_us(value: Any, name: str) -> int:
    require(
        isinstance(value, (int, float)) and not isinstance(value, bool),
        f"{name} readback must be numeric",
    )
    microseconds = round(float(value) * 1_000_000)
    require(microseconds >= 0, f"{name} readback is negative")
    return microseconds


def normalize_network_tc_state(
    qdiscs: Any,
    filters: Any,
    variant: str,
) -> dict[str, Any]:
    """Normalize raw pinned tc JSON without importing the harness implementation."""
    netem = _find_kind(qdiscs, "netem")
    require(netem is not None, "raw qdisc readback has no netem")
    require(netem.get("parent") == "1:1", "raw netem is not attached to impaired band 1:1")
    prio = _find_kind(qdiscs, "prio")
    require(prio is not None, "raw qdisc readback has no scoped prio")
    require(prio.get("handle") == "1:", "raw scoped prio must use handle 1:")
    prio_options = prio.get("options") or {}
    require(isinstance(prio_options, Mapping), "raw prio options must be an object")
    require(prio_options.get("bands") == 3, "raw prio readback has unexpected band count")
    require(
        prio_options.get("priomap") == BYPASS_PRIOMAP,
        "unmatched traffic is not pinned to the bypass band",
    )
    flowers = [
        item
        for item in _find_kinds(filters, "flower")
        if isinstance(item.get("options"), Mapping)
    ]
    require(
        len(flowers) == 1,
        "raw filter readback must contain exactly one effective flower",
    )
    flower = flowers[0]
    require(
        _contains_key_value(flower, "src_port", 18081),
        "flower classifier is not restricted to media source port",
    )
    require(
        _contains_key_value(flower, "ip_proto", "tcp"),
        "flower classifier is not restricted to TCP",
    )
    require(
        _contains_key_value(flower, "eth_type", "ipv4")
        or _contains_key_value(flower, "protocol", "ip"),
        "flower classifier is not restricted to IPv4",
    )
    require(
        _contains_key_value(flower, "classid", "1:1"),
        "media flower classifier is not directed to impaired band 1:1",
    )
    options = netem.get("options") or {}
    require(isinstance(options, Mapping), "netem options must be an object")

    state: dict[str, Any] = {
        "direction": "DOWNSTREAM",
        "ipFamily": "IPV4",
        "l4Protocol": "TCP",
        "scope": "MEDIA_DATA_ONLY",
        "mediaPortScoped": True,
    }
    if variant == "HIGH_RTT_JITTER":
        delay = options.get("delay") or {}
        require(isinstance(delay, Mapping), "N2 delay readback missing")
        state.update(
            delayUs=_seconds_to_us(delay.get("delay"), "delay"),
            jitterUs=_seconds_to_us(delay.get("jitter"), "jitter"),
            delayCorrelationPpm=_fraction_to_ppm(
                delay.get("correlation"), "delay correlation"
            ),
            randomSeed=int(options.get("seed", -1)),
        )
    elif variant == "BURST_PACKET_LOSS":
        loss = options.get("loss-random") or {}
        require(isinstance(loss, Mapping), "N3 loss readback missing")
        state.update(lossPpm=_fraction_to_ppm(loss.get("loss"), "loss"))
    elif variant == "BURST_LOSS":
        loss = options.get("loss-random") or {}
        require(isinstance(loss, Mapping), "N5 loss readback missing")
        state.update(
            lossPpm=_fraction_to_ppm(loss.get("loss"), "loss"),
            burstCorrelationPpm=_fraction_to_ppm(
                loss.get("correlation"), "loss correlation"
            ),
            randomSeed=int(options.get("seed", -1)),
        )
    else:
        raise FaultOracleError(f"unsupported NETWORK variant {variant!r}")
    return state


def _network_tool_config(config: Mapping[str, Any]) -> dict[str, Any]:
    result = {key: value for key, value in config.items() if key != "durationMs"}
    result["mediaPortScoped"] = True
    return result


def validate_network_effect(variant: str, counters: Mapping[str, Any]) -> None:
    packets = int(counters.get("packets", 0))
    drops = int(counters.get("drops", 0))
    require(packets >= 0 and drops >= 0, "negative netem counters")
    if variant == "BURST_PACKET_LOSS":
        require(drops > 0, "100% blackout produced no qdisc drops")
        require(packets + drops > 0, "netem qdisc observed no matching media effect")
    else:
        require(packets > 0, "netem qdisc saw no transmitted media packets")


def verify_network_markers(
    args: argparse.Namespace,
    calibration: Mapping[str, Any],
) -> None:
    """Independently re-check raw health/cleanup markers retained by CI.

    network-calibration-v1 is a producer artifact, not an oracle. The oracle
    therefore reads the original marker files itself and requires the
    calibration projection to agree with them.
    """

    raw_adb = {
        "adbHealthyBefore": marker(args.adb_before, "device"),
        "adbHealthyDuring": marker(args.adb_during, "device"),
        "adbHealthyAfter": marker(args.adb_after, "device"),
    }
    require(all(raw_adb.values()), "raw ADB health proof is incomplete")

    raw_control = {
        "before": marker(args.control_before, "ok"),
        "during": marker(args.control_during, "ok"),
        "after": marker(args.control_after, "ok"),
    }
    require(all(raw_control.values()), "raw Media Lab control health proof is incomplete")

    scope = calibration.get("scopeProof") or {}
    for key, value in raw_adb.items():
        require(
            scope.get(key) is value,
            f"calibration {key} does not match the raw ADB marker",
        )

    raw_cleanup = {
        "qdiscRemoved": marker(args.qdisc_clean, "ok"),
        "filtersRemoved": marker(args.filter_clean, "ok"),
        "namespaceRemoved": marker(args.namespace_clean, "ok"),
        "proxiesRemoved": marker(args.transport_tool_absent, "ok"),
        "toxicsRemoved": marker(args.transport_tool_absent, "ok"),
    }
    require(all(raw_cleanup.values()), "raw NETWORK cleanup proof is incomplete")
    require(
        calibration.get("cleanup") == raw_cleanup,
        "network calibration cleanup does not match raw cleanup markers",
    )

    require(marker(args.media_reverse_absent, "ok"), "NETWORK media path used adb reverse")
    require(marker(args.artifact_collection, "ok"), "NETWORK artifact collection failed")


def verify_network(args: argparse.Namespace) -> dict[str, Any]:
    scenario = load_object(args.scenario)
    manifest = load_object(args.run_manifest)
    harness = load_object(args.harness)
    qdisc = json.loads(args.qdisc_state.read_text(encoding="utf-8"))
    filters = json.loads(args.filter_state.read_text(encoding="utf-8"))
    clean = load_object(args.clean_state)
    calibration = load_object(args.calibration)
    fingerprint = load_object(args.engine_fingerprint)
    failures = load_object(args.failures)
    result = load_object(args.network_result)
    fetch_rows = load_jsonl(args.fetch)
    origin_rows = load_jsonl(args.origin)

    validate_schema("m2-run-manifest-v1.schema.json", manifest)
    validate_schema("fault-harness-events-v1.schema.json", harness)
    validate_schema("network-calibration-v1.schema.json", calibration)
    validate_schema("fault-engine-fingerprint-v1.schema.json", fingerprint)
    validate_run_manifest_semantics(manifest, scenario)
    fault, config = expected_network_fault(scenario)

    expected_hash = scenario_sha256(scenario)
    for name, value in (
        ("manifest", manifest["scenario"]["hash"]),
        ("harness", harness.get("scenarioHash")),
        ("calibration", calibration.get("scenarioHash")),
    ):
        require(value == expected_hash, f"{name} scenario hash mismatch")
    require(harness.get("runId") == manifest.get("runId"), "harness/manifest runId mismatch")
    require(harness.get("sessionId") == manifest.get("sessionId"), "harness/manifest sessionId mismatch")
    require(calibration.get("runId") == manifest.get("runId"), "calibration/manifest runId mismatch")
    require(calibration.get("sessionId") == manifest.get("sessionId"), "calibration/manifest sessionId mismatch")

    meta = harness.get("harness") or {}
    validate_m2e_harness_binding(
        "NETWORK",
        str(meta.get("harnessId")),
        str(meta.get("toolId")),
    )
    require(meta.get("harnessVersion") == "1", "unexpected network harness version")
    require(meta.get("toolVersion") == "iproute2-6.6.0", "unexpected netem tc version")
    require(harness.get("clockDomain") == "HOST_FAULT_MONOTONIC", "wrong harness clock domain")

    fingerprint_sha = hashlib.sha256(args.engine_fingerprint.read_bytes()).hexdigest()
    require(
        (manifest.get("runtime") or {}).get("faultEngineFingerprintSha256") == fingerprint_sha,
        "run manifest is not bound to the fault-engine fingerprint",
    )
    locked_tc = load_object(ROOT / "tools" / "fault-harness" / "toolchain.lock.json")["netem"]["labTc"]
    toolchain = fingerprint.get("toolchain") or {}
    require(
        toolchain.get("tcSourceSha256") == locked_tc.get("sha256"),
        "fault-engine tc source SHA does not match toolchain lock",
    )
    require(
        str(toolchain.get("tcVersion", "")).startswith(f"tc utility, iproute2-{locked_tc.get('version')}"),
        "fault-engine tc version does not match toolchain lock",
    )
    require(
        fingerprint.get("offloads") == {"gro": False, "gso": False, "tso": False},
        "canonical NETWORK packetization offloads are not disabled",
    )
    runner = fingerprint.get("runner") or {}
    require(bool(runner.get("kernelRelease")), "fault-engine kernel release missing")
    require(bool(runner.get("imageVersion")), "fault-engine runner image version missing")


    events = harness.get("events") or []
    require(len(events) == 5, "canonical NETWORK lifecycle must contain five events")
    require(
        [event.get("sequence") for event in events] == [1, 2, 3, 4, 5],
        "NETWORK fault event sequence is not contiguous",
    )
    expected_ops = [
        "HARNESS_STARTED",
        "FAULT_ARMED",
        "FAULT_APPLIED",
        "FAULT_REMOVED",
        "HARNESS_STOPPED",
    ]
    require([event.get("operation") for event in events] == expected_ops, "unexpected NETWORK lifecycle")
    timestamps = [event.get("elapsedRealtimeNs") for event in events]
    require(
        all(isinstance(value, int) and not isinstance(value, bool) and value >= 0 for value in timestamps),
        "invalid NETWORK HOST_FAULT_MONOTONIC timestamp",
    )
    require(
        all(b >= a for a, b in zip(timestamps, timestamps[1:])),
        "NETWORK HOST_FAULT_MONOTONIC time regressed",
    )

    for event in events:
        require(event.get("plane") == "NETWORK", "network harness emitted a non-NETWORK event")
        require(event.get("faultId") == fault.get("faultId"), "NETWORK faultId mismatch")
        require(event.get("kind") == fault.get("kind"), "NETWORK fault kind mismatch")
        require(event.get("scope") == "MEDIA_DATA_ONLY", "NETWORK scope mismatch")

    expected_params = {
        key: value
        for key, value in config.items()
        if key not in {"direction", "scope"}
    }
    require(events[0].get("direction") == "NONE", "HARNESS_STARTED direction mismatch")
    require(events[1].get("direction") == "DOWNSTREAM", "FAULT_ARMED direction mismatch")
    require(events[2].get("direction") == "DOWNSTREAM", "FAULT_APPLIED direction mismatch")
    require(events[4].get("direction") == "NONE", "HARNESS_STOPPED direction mismatch")
    require(events[1].get("parameters") == expected_params, "FAULT_ARMED parameters mismatch")
    require(events[2].get("parameters") == expected_params, "FAULT_APPLIED parameters mismatch")
    require(events[2].get("result") == "APPLIED", "NETWORK fault was not reported applied")
    require(events[3].get("result") == "REMOVED", "NETWORK fault was not reported removed")
    require(events[4].get("result") == "OK", "NETWORK harness did not stop cleanly")

    observed = normalize_network_tc_state(qdisc, filters, str(scenario["variant"]))
    expected_tool = _network_tool_config(config)
    require(observed == expected_tool, f"NETWORK tc readback mismatch: {observed!r}")
    require(
        events[2].get("observedStateHash") == canonical_hash(observed),
        "NETWORK FAULT_APPLIED observed state hash mismatch",
    )
    require(clean == {"filters": 0, "netem": 0, "prio": 0}, "NETWORK clean state mismatch")
    require(
        events[4].get("observedStateHash") == canonical_hash(clean),
        "NETWORK HARNESS_STOPPED state hash mismatch",
    )

    if scenario["variant"] == "BURST_PACKET_LOSS":
        duration_ns = timestamps[3] - timestamps[2]
        require(duration_ns >= 1_500_000_000, "N3 blackout ended before durationMs=1500")

    require(calibration.get("harnessId") == "sponge-network-harness", "calibration harness mismatch")
    require(calibration.get("harnessVersion") == "1", "calibration harness version mismatch")
    require(calibration.get("clockDomain") == "HOST_FAULT_MONOTONIC", "calibration clock mismatch")
    require(calibration.get("expectedConfig") == expected_tool, "calibration expectedConfig mismatch")
    require(calibration.get("observedConfig") == observed, "calibration observedConfig mismatch")

    verify_network_markers(args, calibration)
    scope = calibration.get("scopeProof") or {}
    require(scope.get("mediaPacketsAfter", 0) > scope.get("mediaPacketsBefore", -1), "media packet counter did not advance")
    require(scope.get("mediaBytesAfter", 0) > scope.get("mediaBytesBefore", -1), "media byte counter did not advance")
    require(scope.get("controlPacketsAfter", 0) > scope.get("controlPacketsBefore", -1), "control loopback counter did not advance")
    require(
        scope.get("adbHealthyBefore") is True
        and scope.get("adbHealthyDuring") is True
        and scope.get("adbHealthyAfter") is True,
        "ADB was not healthy across NETWORK fault",
    )
    counters = calibration.get("qdiscCounters") or {}
    validate_network_effect(str(scenario["variant"]), counters)
    require(all((calibration.get("cleanup") or {}).values()), "NETWORK cleanup is incomplete")

    failure_rows = failures.get("failures") or []
    require(isinstance(failure_rows, list), "failure rows must be an array")
    for row in failure_rows:
        observation = row.get("observation") or {}
        require(
            observation.get("plane") == "TRANSPORT",
            "NETWORK cause was incorrectly materialized as a runtime packet-loss observation",
        )
        require(
            row.get("classification") == "TRANSIENT_TRANSPORT",
            "NETWORK-induced socket failure was not classified transient transport",
        )
        require(
            (row.get("decision") or {}).get("kind") not in PROVIDER_ACTIONS,
            "pure NETWORK fault triggered provider action",
        )

    attempts = sum(1 for row in fetch_rows if row.get("event") == "ATTEMPT_STARTED")
    require(1 <= attempts <= 4, f"NETWORK run used invalid attempt count {attempts}")
    require(result.get("physicalAttempts") == attempts, "network result/fetch attempt mismatch")
    require(result.get("terminalReason") == "SUCCESS", "canonical NETWORK scenario did not recover")
    require(result.get("published") is True, "canonical NETWORK scenario did not publish complete media")
    require(result.get("failureCount") == len(failure_rows), "network result/failure count mismatch")
    if scenario["variant"] == "BURST_PACKET_LOSS":
        require(attempts >= 2, "N3 blackout did not force a recovery attempt")
        require(len(failure_rows) >= 1, "N3 blackout produced no runtime failure evidence")

    data_rows = [
        row for row in origin_rows
        if row.get("plane") == "data" and row.get("method") == "GET"
    ]
    require(len(data_rows) <= attempts, "origin requests exceed physical attempts")

    for document in (scenario, manifest, harness, clean, calibration, fingerprint, result):
        scan_evidence_privacy(document)

    seed_bound = True
    if "randomSeed" in expected_tool:
        seed_bound = observed.get("randomSeed") == expected_tool["randomSeed"]

    checks = {
        "scenarioIdentity": True,
        "faultOwnership": True,
        "toolStateMatches": True,
        "mediaPathTraversed": True,
        "controlPathUnimpaired": True,
        "seedBound": seed_bound,
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
        "primaryPlane": "NETWORK",
        "checks": checks,
        "gates": {
            "M2-ACC-01": True,
            "M2-ACC-02": True,
            "M2-ACC-09": all(checks.values()),
        },
        "limitations": [
            "API 36 emulator correctness and packet-fault attribution evidence; not representative network performance.",
            "Seed binding is verified. Correlated netem modes are not claimed to reproduce an identical per-packet effect sequence from the seed alone.",
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

    network = sub.add_parser("verify-network")
    network.add_argument("--scenario", required=True, type=pathlib.Path)
    network.add_argument("--run-manifest", required=True, type=pathlib.Path)
    network.add_argument("--harness", required=True, type=pathlib.Path)
    network.add_argument("--qdisc-state", required=True, type=pathlib.Path)
    network.add_argument("--filter-state", required=True, type=pathlib.Path)
    network.add_argument("--clean-state", required=True, type=pathlib.Path)
    network.add_argument("--calibration", required=True, type=pathlib.Path)
    network.add_argument("--engine-fingerprint", required=True, type=pathlib.Path)
    network.add_argument("--failures", required=True, type=pathlib.Path)
    network.add_argument("--budget", required=True, type=pathlib.Path)
    network.add_argument("--fetch", required=True, type=pathlib.Path)
    network.add_argument("--network-result", required=True, type=pathlib.Path)
    network.add_argument("--origin", required=True, type=pathlib.Path)
    for name in (
        "adb-before",
        "adb-during",
        "adb-after",
        "control-before",
        "control-during",
        "control-after",
        "qdisc-clean",
        "filter-clean",
        "namespace-clean",
        "transport-tool-absent",
        "media-reverse-absent",
        "artifact-collection",
    ):
        network.add_argument(f"--{name}", required=True, type=pathlib.Path)
    network.add_argument("--output", required=True, type=pathlib.Path)

    args = parser.parse_args(argv)

    try:
        summary = verify_transport(args) if args.command == "verify-transport" else verify_network(args)
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
