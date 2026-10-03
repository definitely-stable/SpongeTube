#!/usr/bin/env python3
"""M2-E TRANSPORT harness compiler/evidence producer.

This owns only Toxiproxy stream/connection faults. It does not retry requests
or interpret Android failures.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys
import time
from typing import Any

SCRIPT_DIR = pathlib.Path(__file__).resolve().parent
MEASUREMENT_DIR = SCRIPT_DIR.parent / "measurement"
sys.path.insert(0, str(MEASUREMENT_DIR))

from m2_contracts import (  # noqa: E402
    M2ContractError,
    scan_evidence_privacy,
    scenario_sha256,
    validate_m2e_harness_binding,
    validate_scenario_semantics,
)

import toxiproxy_control as toxi  # noqa: E402

HARNESS_ID = "sponge-transport-harness"
HARNESS_VERSION = "1"
TOOL_ID = "toxiproxy"
TOOL_VERSION = "2.12.0"
PROXY_NAME = "m2e-media"
TOXIC_NAME = "m2e-fault"

VARIANTS = {
    "TRANSPORT_READ_TIMEOUT": ("READ_TIMEOUT", "timeout", "downstream", {"timeout": 0}),
    "TRANSPORT_RESET": ("RESET_PEER", "reset_peer", "downstream", {"timeout": 0}),
    "TRUNCATED_STREAM": ("LIMIT_DATA", "limit_data", "downstream", None),
    "SLOW_CLOSE": ("SLOW_CLOSE", "slow_close", "downstream", None),
}


def load(path: str) -> dict[str, Any]:
    value = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise M2ContractError("scenario must be an object")
    return value


def transport_fault(scenario: dict[str, Any]) -> dict[str, Any]:
    validate_scenario_semantics(scenario)
    if scenario.get("primaryPlane") != "TRANSPORT":
        raise M2ContractError("transport harness requires primaryPlane TRANSPORT")
    faults = scenario.get("transportFaults") or []
    if len(faults) != 1:
        raise M2ContractError("transport harness requires exactly one TRANSPORT fault")
    fault = faults[0]
    for field in (
        "deliveryFaults",
        "networkFaults",
        "providerFaults",
        "routeFaults",
        "storageFaults",
    ):
        if scenario.get(field):
            raise M2ContractError(
                f"canonical transport harness run may not contain faults in {field}"
            )
    if fault.get("stochastic") is not False or scenario.get("randomSeed") is not None:
        raise M2ContractError("canonical E2 TRANSPORT faults are deterministic and seedless")
    validate_m2e_harness_binding("TRANSPORT", HARNESS_ID, TOOL_ID)
    return fault


def compile_toxic(scenario: dict[str, Any]) -> tuple[str, str, dict[str, int]]:
    fault = transport_fault(scenario)
    variant = scenario.get("variant")
    if variant not in VARIANTS:
        raise M2ContractError(f"unsupported M2-E transport variant {variant!r}")
    expected_kind, toxic_type, stream, fixed = VARIANTS[variant]
    if fault.get("kind") != expected_kind:
        raise M2ContractError(
            f"{variant} requires fault kind {expected_kind}, got {fault.get('kind')!r}"
        )
    params = fault.get("parameters") or {}
    if params.get("direction") != stream.upper():
        raise M2ContractError(f"{variant} requires direction {stream.upper()}")
    if params.get("scope") != "MEDIA_DATA_ONLY":
        raise M2ContractError(f"{variant} requires MEDIA_DATA_ONLY scope")
    if fixed is not None:
        if params.get("timeoutMs") != 0:
            raise M2ContractError(f"{variant} requires timeoutMs=0")
        attributes = dict(fixed)
    elif variant == "TRUNCATED_STREAM":
        value = params.get("limitBytes")
        if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
            raise M2ContractError("TRUNCATED_STREAM requires positive limitBytes")
        attributes = {"bytes": value}
    else:
        value = params.get("delayMs")
        if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
            raise M2ContractError("SLOW_CLOSE requires positive delayMs")
        attributes = {"delay": value}
    return toxic_type, stream, attributes


def expected_toxic(
    toxic_type: str,
    stream: str,
    attributes: dict[str, int],
) -> dict[str, Any]:
    return {
        "name": TOXIC_NAME,
        "type": toxic_type,
        "stream": stream,
        "toxicityPpm": 1_000_000,
        "attributes": attributes,
    }


def canonical_hash(value: Any) -> str:
    encoded = json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=False
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def read_document(path: pathlib.Path) -> dict[str, Any]:
    if not path.exists():
        raise M2ContractError(f"evidence file does not exist: {path}")
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise M2ContractError("evidence root must be object")
    return value


def write_document(path: pathlib.Path, value: dict[str, Any]) -> None:
    scan_evidence_privacy(value)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def append_event(
    doc: dict[str, Any],
    *,
    fault: dict[str, Any],
    operation: str,
    direction: str,
    parameters: dict[str, Any],
    state: dict[str, Any] | None,
    result: str,
) -> None:
    events = doc["events"]
    events.append(
        {
            "sequence": len(events) + 1,
            "elapsedRealtimeNs": time.monotonic_ns(),
            "faultEventId": f"fault-event-{len(events) + 1}",
            "faultId": fault["faultId"],
            "plane": "TRANSPORT",
            "kind": fault["kind"],
            "operation": operation,
            "direction": direction,
            "scope": "MEDIA_DATA_ONLY",
            "parameters": parameters,
            "observedStateHash": canonical_hash(state) if state is not None else None,
            "result": result,
        }
    )


def start(args: argparse.Namespace) -> None:
    scenario = load(args.scenario)
    fault = transport_fault(scenario)
    toxic_type, stream, attributes = compile_toxic(scenario)
    state_path = pathlib.Path(args.state)
    evidence_path = pathlib.Path(args.evidence)

    toxi.delete_proxy(args.api, PROXY_NAME)
    toxi.create_proxy(args.api, PROXY_NAME, args.listen, args.upstream)
    proxy_state = toxi.normalized_proxy_state(toxi.get_proxy(args.api, PROXY_NAME))
    if proxy_state["toxics"]:
        raise M2ContractError("new transport proxy unexpectedly has toxics")

    doc = {
        "schemaVersion": 1,
        "runId": args.run_id,
        "sessionId": args.session_id,
        "scenarioHash": scenario_sha256(scenario),
        "harness": {
            "harnessId": HARNESS_ID,
            "harnessVersion": HARNESS_VERSION,
            "toolId": TOOL_ID,
            "toolVersion": TOOL_VERSION,
        },
        "clockDomain": "HOST_FAULT_MONOTONIC",
        "events": [],
    }
    append_event(
        doc,
        fault=fault,
        operation="HARNESS_STARTED",
        direction="NONE",
        parameters={},
        state=proxy_state,
        result="OK",
    )
    append_event(
        doc,
        fault=fault,
        operation="FAULT_ARMED",
        direction=stream.upper(),
        parameters={
            "toxicType": toxic_type,
            **{key: int(value) for key, value in sorted(attributes.items())},
        },
        state=proxy_state,
        result="OK",
    )

    toxi.add_toxic(
        args.api, PROXY_NAME, TOXIC_NAME, toxic_type, stream, attributes
    )
    active = toxi.normalized_proxy_state(toxi.get_proxy(args.api, PROXY_NAME))
    expected = expected_toxic(toxic_type, stream, attributes)
    if active["toxics"] != [expected]:
        raise M2ContractError(f"toxic readback mismatch: {active['toxics']!r}")
    append_event(
        doc,
        fault=fault,
        operation="FAULT_APPLIED",
        direction=stream.upper(),
        parameters={
            "toxicType": toxic_type,
            **{key: int(value) for key, value in sorted(attributes.items())},
        },
        state=active,
        result="APPLIED",
    )
    write_document(evidence_path, doc)
    write_document(state_path, active)


def _validate_started_document(
    doc: dict[str, Any],
    *,
    scenario: dict[str, Any],
    run_id: str,
    session_id: str,
) -> dict[str, Any]:
    if doc.get("runId") != run_id:
        raise M2ContractError("runId does not match started harness")
    if doc.get("sessionId") != session_id:
        raise M2ContractError("sessionId does not match started harness")
    if doc.get("scenarioHash") != scenario_sha256(scenario):
        raise M2ContractError("scenario does not match started harness")
    return doc


def disarm(args: argparse.Namespace) -> None:
    """Remove the active toxic while keeping the proxy path alive.

    G2-D uses this after the first causally observed reset so RecoveryCoordinator
    can own the subsequent retry over the same proxy route.  The operation is
    explicit evidence; it never performs or schedules an application retry.
    """
    scenario = load(args.scenario)
    fault = transport_fault(scenario)
    toxic_type, stream, attributes = compile_toxic(scenario)
    evidence_path = pathlib.Path(args.evidence)
    state_path = pathlib.Path(args.state)
    doc = _validate_started_document(
        read_document(evidence_path),
        scenario=scenario,
        run_id=args.run_id,
        session_id=args.session_id,
    )
    if any(event.get("operation") == "FAULT_REMOVED" for event in doc["events"]):
        raise M2ContractError("transport fault already removed")

    active = toxi.normalized_proxy_state(toxi.get_proxy(args.api, PROXY_NAME))
    expected = expected_toxic(toxic_type, stream, attributes)
    if active["toxics"] != [expected]:
        raise M2ContractError(
            f"transport toxic readback drifted before disarm: {active['toxics']!r}"
        )

    toxi.remove_toxic(args.api, PROXY_NAME, TOXIC_NAME)
    clean_proxy = toxi.normalized_proxy_state(toxi.get_proxy(args.api, PROXY_NAME))
    if clean_proxy["toxics"]:
        raise M2ContractError("transport toxic leaked after disarm")
    append_event(
        doc,
        fault=fault,
        operation="FAULT_REMOVED",
        direction=stream.upper(),
        parameters={},
        state=clean_proxy,
        result="REMOVED",
    )
    write_document(evidence_path, doc)
    write_document(state_path, clean_proxy)


def stop(args: argparse.Namespace) -> None:
    scenario = load(args.scenario)
    fault = transport_fault(scenario)
    toxic_type, stream, attributes = compile_toxic(scenario)
    evidence_path = pathlib.Path(args.evidence)
    state_path = pathlib.Path(args.state)
    doc = _validate_started_document(
        read_document(evidence_path),
        scenario=scenario,
        run_id=args.run_id,
        session_id=args.session_id,
    )

    current = toxi.normalized_proxy_state(toxi.get_proxy(args.api, PROXY_NAME))
    removed = any(event.get("operation") == "FAULT_REMOVED" for event in doc["events"])
    if current["toxics"]:
        if removed:
            raise M2ContractError("transport toxic reappeared after recorded removal")
        expected = expected_toxic(toxic_type, stream, attributes)
        if current["toxics"] != [expected]:
            raise M2ContractError(
                f"transport toxic readback drifted during stop: {current['toxics']!r}"
            )
        toxi.remove_toxic(args.api, PROXY_NAME, TOXIC_NAME)
        clean_proxy = toxi.normalized_proxy_state(toxi.get_proxy(args.api, PROXY_NAME))
        if clean_proxy["toxics"]:
            raise M2ContractError("transport toxic leaked after removal")
        append_event(
            doc,
            fault=fault,
            operation="FAULT_REMOVED",
            direction=stream.upper(),
            parameters={},
            state=clean_proxy,
            result="REMOVED",
        )
    else:
        if not removed:
            raise M2ContractError("transport toxic disappeared without recorded removal")
        clean_proxy = current

    toxi.delete_proxy(args.api, PROXY_NAME)
    proxies = toxi.list_proxies(args.api)
    if proxies not in ({}, []):
        raise M2ContractError(f"transport proxy leaked: {proxies!r}")
    append_event(
        doc,
        fault=fault,
        operation="HARNESS_STOPPED",
        direction="NONE",
        parameters={},
        state={"proxies": 0, "toxics": 0},
        result="OK",
    )
    write_document(evidence_path, doc)
    write_document(state_path, {"proxies": 0, "toxics": 0})


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["start", "disarm", "stop"])
    parser.add_argument("--scenario", required=True)
    parser.add_argument("--api", required=True)
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--state", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--session-id", required=True)
    parser.add_argument("--listen")
    parser.add_argument("--upstream")
    args = parser.parse_args()
    try:
        if args.command == "start":
            if not args.listen or not args.upstream:
                parser.error("start requires --listen and --upstream")
            start(args)
        elif args.command == "disarm":
            disarm(args)
        else:
            stop(args)
    except (M2ContractError, toxi.ToxiproxyError) as error:
        parser.error(str(error))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
