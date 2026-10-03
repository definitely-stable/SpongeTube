#!/usr/bin/env python3
"""M2-E NETWORK harness compiler/evidence producer.

Owns only scoped Linux tc/netem packet/network faults. It never retries media
requests, classifies Android failures, or owns provider/route behavior.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import subprocess
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

HARNESS_ID = "sponge-network-harness"
HARNESS_VERSION = "1"
TOOL_ID = "netem"
TOOL_VERSION = "iproute2-6.6.0"
MEDIA_PORT = 18081
NETWORK_DIRECTION = "DOWNSTREAM"
NETWORK_IP_FAMILY = "IPV4"
NETWORK_L4_PROTOCOL = "TCP"
PULSE_ARM_TIMEOUT_MS = 30_000
BYPASS_PRIOMAP = [2] * 16

VARIANTS = {
    "HIGH_RTT_JITTER": "HIGH_RTT_JITTER",
    "BURST_PACKET_LOSS": "BURST_PACKET_LOSS",
    "BURST_LOSS": "BURST_LOSS",
    "BURST_LOSS_GE_MOMENT_MATCH": "BURST_LOSS_GE_MOMENT_MATCH",
}


def fail(message: str) -> None:
    raise M2ContractError(message)


def load(path: str | pathlib.Path) -> dict[str, Any]:
    value = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        fail("scenario/evidence must be an object")
    return value


def write(path: str | pathlib.Path, value: Any) -> None:
    scan_evidence_privacy(value)
    target = pathlib.Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + ".tmp")
    temporary.write_text(
        json.dumps(value, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    temporary.replace(target)


def canonical_hash(value: Any) -> str:
    encoded = json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=False
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def integer(value: Any, name: str, *, minimum: int = 0, maximum: int | None = None) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        fail(f"{name} must be an integer")
    if value < minimum or (maximum is not None and value > maximum):
        fail(f"{name} outside accepted range")
    return value


def network_fault(scenario: dict[str, Any]) -> dict[str, Any]:
    validate_scenario_semantics(scenario)
    if scenario.get("primaryPlane") != "NETWORK":
        fail("network harness requires primaryPlane NETWORK")
    faults = scenario.get("networkFaults") or []
    if len(faults) != 1:
        fail("network harness requires exactly one NETWORK fault")
    for field in (
        "deliveryFaults",
        "transportFaults",
        "providerFaults",
        "routeFaults",
        "storageFaults",
    ):
        if scenario.get(field):
            fail(f"canonical E3 NETWORK run may not contain faults in {field}")
    fault = faults[0]
    if fault.get("plane") != "NETWORK":
        fail("network fault plane mismatch")
    if scenario.get("variant") not in VARIANTS:
        fail(f"unsupported M2-E network variant {scenario.get('variant')!r}")
    if fault.get("kind") != VARIANTS[scenario["variant"]]:
        fail("network variant/kind mismatch")
    params = fault.get("parameters") or {}
    if params.get("scope") != "MEDIA_PATH_ONLY":
        fail("NETWORK scenario requires MEDIA_PATH_ONLY scenario scope")
    if params.get("direction") != NETWORK_DIRECTION:
        fail("NETWORK scenario direction must be DOWNSTREAM")
    if params.get("ipFamily") != NETWORK_IP_FAMILY:
        fail("NETWORK scenario ipFamily must be IPV4")
    if params.get("l4Protocol") != NETWORK_L4_PROTOCOL:
        fail("NETWORK scenario l4Protocol must be TCP")
    validate_m2e_harness_binding("NETWORK", HARNESS_ID, TOOL_ID)
    return fault


def compile_config(scenario: dict[str, Any]) -> dict[str, Any]:
    fault = network_fault(scenario)
    params = fault.get("parameters") or {}
    variant = scenario["variant"]
    seed = scenario.get("randomSeed")

    base: dict[str, Any] = {
        "direction": NETWORK_DIRECTION,
        "ipFamily": NETWORK_IP_FAMILY,
        "l4Protocol": NETWORK_L4_PROTOCOL,
        "scope": "MEDIA_DATA_ONLY",
    }
    if variant == "HIGH_RTT_JITTER":
        if fault.get("stochastic") is not True:
            fail("HIGH_RTT_JITTER must be stochastic")
        base.update(
            delayUs=integer(params.get("delayUs"), "delayUs", minimum=1),
            jitterUs=integer(params.get("jitterUs"), "jitterUs", minimum=1),
            delayCorrelationPpm=integer(
                params.get("delayCorrelationPpm"),
                "delayCorrelationPpm",
                maximum=1_000_000,
            ),
            randomSeed=integer(seed, "randomSeed"),
        )
        if (
            base["delayUs"] != 100_000
            or base["jitterUs"] != 30_000
            or base["delayCorrelationPpm"] != 250_000
        ):
            fail("HIGH_RTT_JITTER does not match the frozen N2 profile")
    elif variant == "BURST_PACKET_LOSS":
        if fault.get("stochastic") is not False or seed is not None:
            fail("BURST_PACKET_LOSS is deterministic and seedless")
        base.update(
            lossPpm=integer(params.get("lossPpm"), "lossPpm", maximum=1_000_000),
            durationMs=integer(params.get("durationMs"), "durationMs", minimum=1),
        )
        if base["lossPpm"] != 1_000_000 or base["durationMs"] != 1_500:
            fail("BURST_PACKET_LOSS does not match the frozen N3 profile")
    elif variant == "BURST_LOSS_GE_MOMENT_MATCH":
        if fault.get("stochastic") is not True:
            fail("BURST_LOSS_GE_MOMENT_MATCH must be stochastic")
        base.update(
            goodToBadPpm=integer(params.get("goodToBadPpm"), "goodToBadPpm", minimum=1, maximum=999_999),
            badToGoodPpm=integer(params.get("badToGoodPpm"), "badToGoodPpm", minimum=1, maximum=999_999),
            badLossPpm=integer(params.get("badLossPpm"), "badLossPpm", maximum=1_000_000),
            goodLossPpm=integer(params.get("goodLossPpm"), "goodLossPpm", maximum=1_000_000),
            randomSeed=integer(seed, "randomSeed"),
        )
        if (
            base["goodToBadPpm"] != 15_000
            or base["badToGoodPpm"] != 735_000
            or base["badLossPpm"] != 1_000_000
            or base["goodLossPpm"] != 0
            or base["randomSeed"] != 424_242
        ):
            fail("BURST_LOSS_GE_MOMENT_MATCH does not match N5_GE_MOMENT_MATCH_V1")
    else:
        if fault.get("stochastic") is not True:
            fail("BURST_LOSS must be stochastic")
        base.update(
            lossPpm=integer(params.get("lossPpm"), "lossPpm", minimum=1, maximum=999_999),
            burstCorrelationPpm=integer(
                params.get("burstCorrelationPpm"),
                "burstCorrelationPpm",
                maximum=1_000_000,
            ),
            randomSeed=integer(seed, "randomSeed"),
        )
        if (
            base["lossPpm"] != 20_000
            or base["burstCorrelationPpm"] != 250_000
            or base["randomSeed"] != 424_242
        ):
            fail("BURST_LOSS does not match the frozen N5 profile")
    return base


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


def _has_key_value(value: Any, key: str, expected: Any) -> bool:
    if isinstance(value, dict):
        if value.get(key) == expected:
            return True
        return any(_has_key_value(child, key, expected) for child in value.values())
    if isinstance(value, list):
        return any(_has_key_value(child, key, expected) for child in value)
    return False


def _ppm(value: Any, name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        fail(f"tc readback {name} is not numeric")
    result = round(float(value) * 1_000_000)
    if result < 0 or result > 1_000_000:
        fail(f"tc readback {name} outside [0,1]")
    return result


def _us(value: Any, name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        fail(f"tc readback {name} is not numeric")
    result = round(float(value) * 1_000_000)
    if result < 0:
        fail(f"tc readback {name} is negative")
    return result


def _qdisc_stat(entry: dict[str, Any] | None, name: str) -> int:
    if not isinstance(entry, dict):
        return 0
    for container in (entry, entry.get("stats") or {}, entry.get("stats2") or {}):
        if isinstance(container, dict):
            value = container.get(name)
            if isinstance(value, int) and not isinstance(value, bool):
                return max(0, value)
    return 0


def wait_for_first_effect(
    baseline_drops: int,
    *,
    timeout_ms: int = PULSE_ARM_TIMEOUT_MS,
) -> tuple[Any, Any]:
    deadline = time.monotonic_ns() + timeout_ms * 1_000_000
    while time.monotonic_ns() < deadline:
        qdiscs, filters = inspect()
        if _qdisc_stat(_find_kind(qdiscs, "netem"), "drops") > baseline_drops:
            return qdiscs, filters
        time.sleep(0.01)
    fail(f"NETWORK pulse saw no matching media drop within {timeout_ms} ms")


def normalize_tc_state(
    qdiscs: Any,
    filters: Any,
    variant: str,
) -> dict[str, Any]:
    netem = _find_kind(qdiscs, "netem")
    if netem is None:
        fail("tc readback has no netem qdisc")
    if netem.get("parent") != "1:1":
        fail("netem qdisc is not attached to impaired band 1:1")
    prio = _find_kind(qdiscs, "prio")
    if prio is None:
        fail("tc readback has no scoped prio qdisc")
    if prio.get("handle") != "1:":
        fail("scoped prio qdisc must use handle 1:")
    prio_options = prio.get("options") or {}
    if not isinstance(prio_options, dict):
        fail("prio options readback is not an object")
    if prio_options.get("bands") != 3:
        fail("scoped prio qdisc must expose three bands")
    if prio_options.get("priomap") != BYPASS_PRIOMAP:
        fail("unmatched traffic is not pinned to the bypass band")
    flowers = [
        item
        for item in _find_kinds(filters, "flower")
        if isinstance(item.get("options"), dict)
    ]
    if len(flowers) != 1:
        fail("tc readback must contain exactly one effective flower classifier")
    flower = flowers[0]
    if not _has_key_value(flower, "src_port", MEDIA_PORT):
        fail("flower readback is not scoped to media source port")
    if not _has_key_value(flower, "ip_proto", "tcp"):
        fail("flower readback is not scoped to TCP")
    if not (
        _has_key_value(flower, "eth_type", "ipv4")
        or _has_key_value(flower, "protocol", "ip")
    ):
        fail("flower readback is not scoped to IPv4")
    if not _has_key_value(flower, "classid", "1:1"):
        fail("media flower classifier is not directed to impaired band 1:1")

    options = netem.get("options") or {}
    if not isinstance(options, dict):
        fail("netem options readback is not an object")

    state: dict[str, Any] = {
        "direction": NETWORK_DIRECTION,
        "ipFamily": NETWORK_IP_FAMILY,
        "l4Protocol": NETWORK_L4_PROTOCOL,
        "scope": "MEDIA_DATA_ONLY",
        "mediaPortScoped": True,
    }
    if variant == "HIGH_RTT_JITTER":
        delay = options.get("delay") or {}
        if not isinstance(delay, dict):
            fail("delay readback is not an object")
        state.update(
            delayUs=_us(delay.get("delay"), "delay"),
            jitterUs=_us(delay.get("jitter"), "jitter"),
            delayCorrelationPpm=_ppm(delay.get("correlation"), "delay correlation"),
            randomSeed=integer(options.get("seed"), "tc seed"),
        )
    elif variant == "BURST_PACKET_LOSS":
        loss = options.get("loss-random") or {}
        if not isinstance(loss, dict):
            fail("loss readback is not an object")
        state.update(lossPpm=_ppm(loss.get("loss"), "loss"))
    elif variant == "BURST_LOSS":
        loss = options.get("loss-random") or {}
        if not isinstance(loss, dict):
            fail("loss readback is not an object")
        state.update(
            lossPpm=_ppm(loss.get("loss"), "loss"),
            burstCorrelationPpm=_ppm(loss.get("correlation"), "loss correlation"),
            randomSeed=integer(options.get("seed"), "tc seed"),
        )
    elif variant == "BURST_LOSS_GE_MOMENT_MATCH":
        loss = options.get("loss-gemodel") or {}
        if not isinstance(loss, dict):
            fail("Gilbert-Elliott readback is not an object")
        state.update(
            goodToBadPpm=_ppm(loss.get("p"), "GE good-to-bad"),
            badToGoodPpm=_ppm(loss.get("r"), "GE bad-to-good"),
            badLossPpm=_ppm(loss.get("1-h"), "GE bad-state loss"),
            goodLossPpm=_ppm(loss.get("1-k"), "GE good-state loss"),
            randomSeed=integer(options.get("seed"), "tc seed"),
        )
    else:
        fail(f"unknown network variant {variant}")
    return state


def helper(args: list[str]) -> str:
    command = [str(SCRIPT_DIR / "netem_control.sh"), *args]
    env = dict(os.environ)
    result = subprocess.run(
        command,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
    )
    if result.returncode != 0:
        fail(
            f"netem helper failed ({result.returncode}): "
            f"{result.stderr.strip() or result.stdout.strip()}"
        )
    return result.stdout


def inspect() -> tuple[Any, Any]:
    qdisc = json.loads(helper(["inspect-qdisc"]))
    filters = json.loads(helper(["inspect-filter"]))
    return qdisc, filters


def apply_config(scenario: dict[str, Any], config: dict[str, Any]) -> tuple[Any, Any, dict[str, Any]]:
    variant = scenario["variant"]
    if variant == "HIGH_RTT_JITTER":
        helper([
            "apply-delay-jitter",
            str(config["delayUs"]),
            str(config["jitterUs"]),
            str(config["delayCorrelationPpm"]),
            str(config["randomSeed"]),
        ])
    elif variant == "BURST_PACKET_LOSS":
        helper(["apply-blackout", str(config["lossPpm"])])
    elif variant == "BURST_LOSS_GE_MOMENT_MATCH":
        helper([
            "apply-ge-loss",
            str(config["goodToBadPpm"]),
            str(config["badToGoodPpm"]),
            str(config["badLossPpm"]),
            str(config["goodLossPpm"]),
            str(config["randomSeed"]),
        ])
    else:
        helper([
            "apply-burst-loss",
            str(config["lossPpm"]),
            str(config["burstCorrelationPpm"]),
            str(config["randomSeed"]),
        ])
    qdisc, filters = inspect()
    observed = normalize_tc_state(qdisc, filters, variant)
    expected_tool = {
        key: value for key, value in config.items()
        if key not in {"durationMs"}
    }
    expected_tool["mediaPortScoped"] = True
    if observed != expected_tool:
        fail(f"tc readback mismatch: expected {expected_tool!r}, got {observed!r}")
    return qdisc, filters, observed


def append_event(
    document: dict[str, Any],
    fault: dict[str, Any],
    *,
    operation: str,
    direction: str,
    parameters: dict[str, Any],
    state: dict[str, Any] | None,
    result: str,
) -> None:
    document["events"].append({
        "sequence": len(document["events"]) + 1,
        "elapsedRealtimeNs": time.monotonic_ns(),
        "faultEventId": f"fault-event-{len(document['events']) + 1}",
        "faultId": fault["faultId"],
        "plane": "NETWORK",
        "kind": fault["kind"],
        "operation": operation,
        "direction": direction,
        "scope": "MEDIA_DATA_ONLY",
        "parameters": parameters,
        "observedStateHash": canonical_hash(state) if state is not None else None,
        "result": result,
    })


def new_document(args: argparse.Namespace, scenario: dict[str, Any]) -> dict[str, Any]:
    return {
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


def event_parameters(config: dict[str, Any]) -> dict[str, Any]:
    return {
        key: value
        for key, value in config.items()
        if key not in {"direction", "scope"}
    }


def start_common(
    args: argparse.Namespace,
    *,
    defer_applied: bool = False,
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any], dict[str, Any], Any]:
    scenario = load(args.scenario)
    fault = network_fault(scenario)
    config = compile_config(scenario)
    document = new_document(args, scenario)
    append_event(document, fault, operation="HARNESS_STARTED", direction="NONE", parameters={}, state=None, result="OK")
    qdisc, filters, observed = apply_config(scenario, config)
    write(args.qdisc_state, qdisc)
    write(args.filter_state, filters)
    write(args.active_state, observed)
    append_event(
        document,
        fault,
        operation="FAULT_ARMED",
        direction=NETWORK_DIRECTION,
        parameters=event_parameters(config),
        state=None,
        result="OK",
    )
    if not defer_applied:
        append_event(
            document,
            fault,
            operation="FAULT_APPLIED",
            direction=NETWORK_DIRECTION,
            parameters=event_parameters(config),
            state=observed,
            result="APPLIED",
        )
    write(args.evidence, document)
    return scenario, config, document, observed, qdisc


def finish(args: argparse.Namespace, scenario: dict[str, Any], document: dict[str, Any]) -> None:
    fault = network_fault(scenario)
    final_qdisc, _ = inspect()
    write(args.final_qdisc_state, final_qdisc)
    helper(["remove"])
    helper(["assert-clean"])
    clean = {"filters": 0, "netem": 0, "prio": 0}
    append_event(document, fault, operation="FAULT_REMOVED", direction=NETWORK_DIRECTION, parameters={}, state=clean, result="REMOVED")
    append_event(document, fault, operation="HARNESS_STOPPED", direction="NONE", parameters={}, state=clean, result="OK")
    write(args.clean_state, clean)
    write(args.evidence, document)


def start(args: argparse.Namespace) -> None:
    scenario = load(args.scenario)
    if scenario["variant"] == "BURST_PACKET_LOSS":
        fail("BURST_PACKET_LOSS must use pulse so duration belongs to the harness")
    start_common(args)


def pulse(args: argparse.Namespace) -> None:
    scenario = load(args.scenario)
    if scenario["variant"] != "BURST_PACKET_LOSS":
        fail("pulse is reserved for BURST_PACKET_LOSS")

    scenario, config, document, observed, armed_qdisc = start_common(args, defer_applied=True)
    baseline_drops = _qdisc_stat(_find_kind(armed_qdisc, "netem"), "drops")
    try:
        applied_qdisc, applied_filters = wait_for_first_effect(baseline_drops)
        write(args.qdisc_state, applied_qdisc)
        write(args.filter_state, applied_filters)
        applied_observed = normalize_tc_state(applied_qdisc, applied_filters, scenario["variant"])
        if applied_observed != observed:
            fail("NETWORK configuration changed between arm and first effect")
        append_event(
            document,
            network_fault(scenario),
            operation="FAULT_APPLIED",
            direction=NETWORK_DIRECTION,
            parameters=event_parameters(config),
            state=applied_observed,
            result="APPLIED",
        )
        write(args.evidence, document)

        deadline = time.monotonic_ns() + int(config["durationMs"]) * 1_000_000
        while True:
            remaining = deadline - time.monotonic_ns()
            if remaining <= 0:
                break
            time.sleep(min(remaining / 1_000_000_000, 0.05))
        finish(args, scenario, document)
    except BaseException:
        try:
            helper(["remove"])
            helper(["assert-clean"])
            write(args.clean_state, {"filters": 0, "netem": 0, "prio": 0})
        finally:
            raise


def stop(args: argparse.Namespace) -> None:
    scenario = load(args.scenario)
    if scenario["variant"] == "BURST_PACKET_LOSS":
        fail("BURST_PACKET_LOSS pulse removes itself")
    document = load(args.evidence)
    if document.get("runId") != args.run_id or document.get("sessionId") != args.session_id:
        fail("stop identity does not match started harness")
    if document.get("scenarioHash") != scenario_sha256(scenario):
        fail("stop scenario does not match started harness")
    if [event.get("operation") for event in document.get("events", [])] != [
        "HARNESS_STARTED",
        "FAULT_ARMED",
        "FAULT_APPLIED",
    ]:
        fail("stop requires an active three-event harness lifecycle")
    finish(args, scenario, document)


def add_common(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--scenario", required=True)
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--qdisc-state", required=True)
    parser.add_argument("--filter-state", required=True)
    parser.add_argument("--final-qdisc-state", required=True)
    parser.add_argument("--active-state", required=True)
    parser.add_argument("--clean-state", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--session-id", required=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("start", "stop", "pulse"):
        add_common(sub.add_parser(name))
    args = parser.parse_args()
    try:
        if args.command == "start":
            start(args)
        elif args.command == "stop":
            stop(args)
        else:
            pulse(args)
    except (M2ContractError, OSError, ValueError, json.JSONDecodeError) as error:
        parser.error(str(error))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
