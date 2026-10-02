#!/usr/bin/env python3
"""Verify M2-G2-C paired NETWORK evidence.

The first accepted execution slice is N2/HIGH_RTT_JITTER. The verifier is
structured for N3/N5 extension, but fails closed on scenarios whose
scenario-specific effect oracle has not been frozen yet.
"""
from __future__ import annotations

import argparse
import copy
import json
import pathlib
import sys
from typing import Any, Mapping

ROOT = pathlib.Path(__file__).resolve().parents[2]
MEASUREMENT = ROOT / "scripts" / "measurement"
sys.path.insert(0, str(MEASUREMENT))

from m2_contracts import scan_evidence_privacy  # noqa: E402
from m2_transport_environment import verify as verify_environment  # noqa: E402
from m2_transport_evaluation_oracle import analyze_trials  # noqa: E402
from m2_transport_pair_plan import (  # noqa: E402
    planned_trial_schedule,
    validate_plan,
    validate_trials_against_plan,
)
from schema_subset import validate_instance  # noqa: E402

RESOURCE_PATH = "/fixtures/F1/segment-0-00001.m4s"
RESOURCE_LENGTH = 711_501
RESOURCE_SHA256 = "f3e8a844487d57a05c69975389566bde3bcb38d9afa1d53538be18c959d77fa3"
RECOVERY_JITTER_PROTOCOL = "SHA256_COUNTER_REJECTION_V1"
RECOVERY_JITTER_SEED = 424_243
FIRST_RESPONSE_TIMEOUT_MS = 12_000
READ_TIMEOUT_MS = 12_000
PHASE_SCHEMA = ROOT / ".work" / "schemas" / "transport-phase-timings-v1.schema.json"

N2_ACTIVE_STATE = {
    "delayCorrelationPpm": 250_000,
    "delayUs": 100_000,
    "direction": "DOWNSTREAM",
    "ipFamily": "IPV4",
    "jitterUs": 30_000,
    "l4Protocol": "TCP",
    "mediaPortScoped": True,
    "randomSeed": 424_242,
    "scope": "MEDIA_DATA_ONLY",
}


class NetworkEvidenceError(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise NetworkEvidenceError(message)


def load_json(path: pathlib.Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    require(isinstance(value, dict), f"{path}: expected JSON object")
    return value


def load_jsonl(path: pathlib.Path) -> list[dict[str, Any]]:
    values: list[dict[str, Any]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            value = json.loads(line)
            require(isinstance(value, dict), f"{path}: expected JSON object rows")
            values.append(value)
    return values


def load_inputs(args: argparse.Namespace) -> dict[str, dict[str, Any]]:
    return {
        "work": load_json(args.work),
        "deviceState": load_json(args.device_state),
        "cacheState": load_json(args.cache_state),
        "recoveryPolicy": load_json(args.recovery_policy),
        "routePolicy": load_json(args.route_policy),
    }


def validate_frozen_work(work: Mapping[str, Any]) -> None:
    require(
        work
        == {
            "fixtureId": "F1",
            "trackId": "video-main",
            "representationId": "f1-video-0",
            "byteStart": 0,
            "byteEndExclusive": RESOURCE_LENGTH,
            "expectedSha256": RESOURCE_SHA256,
        },
        "G2-C work fingerprint is not the frozen F1 video unit",
    )


def _find_kind(value: Any, kind: str) -> dict[str, Any] | None:
    if isinstance(value, dict):
        if value.get("kind") == kind:
            return value
        for child in value.values():
            found = _find_kind(child, kind)
            if found is not None:
                return found
    elif isinstance(value, list):
        for child in value:
            found = _find_kind(child, kind)
            if found is not None:
                return found
    return None


def _has_key_value(value: Any, key: str, expected: Any) -> bool:
    if isinstance(value, dict):
        if value.get(key) == expected:
            return True
        return any(_has_key_value(child, key, expected) for child in value.values())
    if isinstance(value, list):
        return any(_has_key_value(child, key, expected) for child in value)
    return False


def _ppm(value: Any, name: str) -> int:
    require(isinstance(value, (int, float)) and not isinstance(value, bool), f"{name} is not numeric")
    return round(float(value) * 1_000_000)


def _us(value: Any, name: str) -> int:
    require(isinstance(value, (int, float)) and not isinstance(value, bool), f"{name} is not numeric")
    return round(float(value) * 1_000_000)


def _stat(entry: Mapping[str, Any], name: str) -> int:
    value = entry.get(name)
    require(type(value) is int and value >= 0, f"netem {name} counter missing")
    return value


def validate_n2_harness(
    *,
    trial_dir: pathlib.Path,
    plan: Mapping[str, Any],
) -> None:
    harness_dir = trial_dir / "harness"
    active = load_json(harness_dir / "netem-active-state.json")
    require(active == N2_ACTIVE_STATE, "N2 active netem readback drift")

    clean = load_json(harness_dir / "netem-clean-state.json")
    require(clean == {"filters": 0, "netem": 0, "prio": 0}, "N2 netem cleanup proof drift")

    evidence = load_json(harness_dir / "fault-harness-events.json")
    require(evidence.get("runId") == plan["runId"], "N2 harness runId drift")
    require(evidence.get("scenarioHash") == plan["scenario"]["hash"], "N2 harness scenario hash drift")
    operations = [event.get("operation") for event in evidence.get("events", [])]
    require(
        operations
        == ["HARNESS_STARTED", "FAULT_ARMED", "FAULT_APPLIED", "FAULT_REMOVED", "HARNESS_STOPPED"],
        "N2 harness lifecycle is incomplete",
    )

    qdisc = load_json_array(harness_dir / "qdisc-final.json")
    netem = _find_kind(qdisc, "netem")
    require(isinstance(netem, dict), "N2 final qdisc has no netem node")
    require(netem.get("parent") == "1:1", "N2 netem left the scoped impaired band")
    options = netem.get("options")
    require(isinstance(options, dict), "N2 netem options missing")
    delay = options.get("delay")
    require(isinstance(delay, dict), "N2 delay readback missing")
    require(_us(delay.get("delay"), "delay") == 100_000, "N2 delay readback drift")
    require(_us(delay.get("jitter"), "jitter") == 30_000, "N2 jitter readback drift")
    require(_ppm(delay.get("correlation"), "delay correlation") == 250_000, "N2 correlation readback drift")
    require(options.get("seed") == 424_242, "N2 seed readback drift")
    require(_stat(netem, "packets") > 0 and _stat(netem, "bytes") > 0, "N2 netem saw no media traffic")

    filters = load_json_array(harness_dir / "filter.json")
    flower = next(
        (
            row for row in filters
            if isinstance(row, dict)
            and row.get("kind") == "flower"
            and isinstance(row.get("options"), dict)
        ),
        None,
    )
    require(isinstance(flower, dict), "N2 scoped flower classifier missing")
    require(_has_key_value(flower, "src_port", 18081), "N2 flower media port drift")
    require(_has_key_value(flower, "ip_proto", "tcp"), "N2 flower protocol drift")
    require(_has_key_value(flower, "eth_type", "ipv4"), "N2 flower IP family drift")
    require(_has_key_value(flower, "classid", "1:1"), "N2 flower class drift")

    before = load_json(trial_dir / "scope-before.json")
    after = load_json(trial_dir / "scope-after.json")
    require(after["mediaPackets"] > before["mediaPackets"], "N2 media packet counter did not advance")
    require(after["mediaBytes"] > before["mediaBytes"], "N2 media byte counter did not advance")


def load_json_array(path: pathlib.Path) -> list[Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    require(isinstance(value, list), f"{path}: expected JSON array")
    return value


def check_raw_n2(
    *,
    raw: dict[str, Any],
    expected: Mapping[str, Any],
    plan: Mapping[str, Any],
    device_state: Mapping[str, Any],
    recovery_policy: Mapping[str, Any],
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    trial_id = expected["trialId"]
    require(raw.get("schemaVersion") == 1, f"{trial_id}: wrong raw schema")
    require(raw.get("phase") == "M2-G2-C-NETWORK", f"{trial_id}: wrong phase")
    require(raw.get("scenarioFamily") == "N2", f"{trial_id}: wrong scenario family")
    require(raw.get("scenarioVariant") == "HIGH_RTT_JITTER", f"{trial_id}: wrong scenario variant")
    require(raw.get("runId") == plan["runId"] and raw.get("pairId") == plan["pairId"], f"{trial_id}: run identity drift")

    row = raw.get("trial")
    proof = raw.get("proof")
    require(isinstance(row, dict) and isinstance(proof, dict), f"{trial_id}: raw trial/proof missing")
    for field in ("trialId", "orderingBlock", "positionInBlock", "backendId"):
        require(row.get(field) == expected[field], f"{trial_id}: {field} drift")
    require(row.get("comparison") == plan["comparison"], f"{trial_id}: comparison fingerprint drift")
    require(row.get("eligibility") == "ELIGIBLE", f"{trial_id}: backend unavailable")
    require(row.get("result") == "SUCCESS", f"{trial_id}: N2 trial failed")
    require(isinstance(row.get("backendVersion"), str) and row["backendVersion"], f"{trial_id}: backend version missing")
    require(isinstance(row.get("implementationId"), str) and row["implementationId"], f"{trial_id}: implementation id missing")

    route = row.get("route")
    require(
        isinstance(route, dict)
        and route.get("exactNetworkBound") is True
        and type(route.get("permitRouteEpoch")) is int
        and route["permitRouteEpoch"] > 0,
        f"{trial_id}: exact-route proof missing",
    )
    correctness = row.get("requestCorrectness")
    require(isinstance(correctness, dict) and set(correctness.values()) == {"PASS"}, f"{trial_id}: correctness gate failed")

    recovery = row.get("recovery")
    require(
        recovery
        == {
            "recoveryChainCount": 1,
            "ownerCount": 1,
            "originRequestCount": 1,
            "internalRetryVisibility": "OPAQUE",
            "internalRetryCount": None,
        },
        f"{trial_id}: raw N2 recovery lineage drift",
    )
    require(row.get("performanceSampleEligible") is False, f"{trial_id}: raw row pre-authorized performance")
    limitations = row.get("limitations")
    require(isinstance(limitations, list), f"{trial_id}: limitations missing")
    for limitation in (
        "RAW_DEVICE_ROW_REQUIRES_HOST_RETRY_FINALIZATION",
        "FIRST_BYTE_IS_FIRST_ACCEPTED_FETCHBROKER_CHUNK_IN_RECOVERY_CHAIN",
        "COMPLETION_IS_RECOVERY_CHAIN_TERMINAL_AFTER_PUBLICATION",
        "TRANSPORT_PHASES_ARE_PHYSICAL_ATTEMPT_LEVEL",
    ):
        require(limitation in limitations, f"{trial_id}: missing measurement limitation {limitation}")

    metrics = row.get("metrics")
    require(isinstance(metrics, dict), f"{trial_id}: metrics missing")
    for field in ("firstByteUs", "completionUs", "cpuTimeUs", "bytesRequested", "bytesReceived", "bytesPublished"):
        require(type(metrics.get(field)) is int and metrics[field] >= 0, f"{trial_id}: {field} missing")
    require(metrics["firstByteUs"] <= metrics["completionUs"], f"{trial_id}: timing order invalid")
    require(metrics["bytesRequested"] == RESOURCE_LENGTH, f"{trial_id}: requested bytes drift")
    require(metrics["bytesReceived"] == RESOURCE_LENGTH, f"{trial_id}: received bytes drift")
    require(metrics["bytesPublished"] == RESOURCE_LENGTH, f"{trial_id}: published bytes drift")

    correlated = proof.get("correlatedOriginRequestIds")
    require(
        isinstance(correlated, list)
        and len(correlated) == 1
        and type(correlated[0]) is int
        and correlated[0] > 0,
        f"{trial_id}: N2 must correlate exactly one origin request",
    )
    require(proof.get("committedSha256") == RESOURCE_SHA256, f"{trial_id}: committed SHA drift")
    require(proof.get("committedBytes") == RESOURCE_LENGTH, f"{trial_id}: committed bytes drift")
    require(proof.get("extentStoreInitiallyEmpty") is True, f"{trial_id}: COLD store proof failed")
    require(proof.get("transportSessionFresh") is True, f"{trial_id}: transport session reset not asserted")
    require(
        proof.get("routeEpochBefore") == route["permitRouteEpoch"] == proof.get("routeEpochAfter"),
        f"{trial_id}: route epoch changed during NETWORK trial",
    )
    require(proof.get("bindingRevision") == "binding-1", f"{trial_id}: binding revision drift")
    require(proof.get("bindingTargetResolutionCount") == 1, f"{trial_id}: binding target count drift")
    require(proof.get("androidApi") == device_state["androidApi"], f"{trial_id}: Android API drift")
    require(proof.get("primaryAbi") == device_state["abi"], f"{trial_id}: ABI drift")
    require(type(proof.get("processPid")) is int and proof["processPid"] > 0, f"{trial_id}: pid missing")
    require(type(proof.get("processStartClockTicks")) is int and proof["processStartClockTicks"] > 0, f"{trial_id}: process starttime missing")
    require(isinstance(proof.get("processInstanceId"), str) and proof["processInstanceId"], f"{trial_id}: process UUID missing")

    chain_start = proof.get("chainStartedElapsedRealtimeNs")
    first_progress = proof.get("firstBrokerProgressElapsedRealtimeNs")
    chain_end = proof.get("chainTerminatedElapsedRealtimeNs")
    require(
        all(type(value) is int and value >= 0 for value in (chain_start, first_progress, chain_end)),
        f"{trial_id}: chain timing anchors missing",
    )
    require(chain_start <= first_progress <= chain_end, f"{trial_id}: chain timing anchors out of order")
    require(metrics["firstByteUs"] == (first_progress - chain_start) // 1_000, f"{trial_id}: firstByteUs is not reproducible")
    require(metrics["completionUs"] == (chain_end - chain_start) // 1_000, f"{trial_id}: completionUs is not reproducible")

    attempts = proof.get("physicalAttempts")
    require(isinstance(attempts, list) and len(attempts) == 1, f"{trial_id}: N2 unexpectedly retried")
    attempt = attempts[0]
    require(attempt.get("terminal") == "ATTEMPT_COMPLETED", f"{trial_id}: N2 physical attempt did not complete")
    require(attempt.get("transportCorrelationId") == str(correlated[0]), f"{trial_id}: origin correlation drift")
    require(attempt.get("networkBytes") == RESOURCE_LENGTH, f"{trial_id}: physical network bytes drift")
    start_ns = attempt.get("startElapsedRealtimeNs")
    end_ns = attempt.get("endElapsedRealtimeNs")
    require(type(start_ns) is int and type(end_ns) is int and chain_start <= start_ns <= end_ns <= chain_end, f"{trial_id}: physical attempt timing invalid")

    phases = proof.get("transportPhases")
    require(isinstance(phases, list) and len(phases) == 3, f"{trial_id}: N2 successful attempt must expose three transport phases")
    kinds = [phase.get("kind") for phase in phases]
    require(
        kinds == ["RESPONSE_HEADERS", "FIRST_BODY_BYTES", "RESPONSE_BODY_COMPLETE"],
        f"{trial_id}: transport phase order drift",
    )
    phase_ns = [phase.get("elapsedRealtimeNs") for phase in phases]
    require(
        all(type(value) is int for value in phase_ns)
        and start_ns <= phase_ns[0] <= phase_ns[1] <= phase_ns[2] <= end_ns,
        f"{trial_id}: transport phase timestamps invalid",
    )
    require(all(phase.get("attempt") == 1 for phase in phases), f"{trial_id}: transport phase attempt drift")

    require(proof.get("recoveryFailureCount") == 0, f"{trial_id}: N2 unexpectedly recorded a recovery failure")
    require(proof.get("recoveryBackoffs") == [], f"{trial_id}: N2 unexpectedly scheduled recovery backoff")
    require(proof.get("recoveryJitterProtocol") == RECOVERY_JITTER_PROTOCOL, f"{trial_id}: jitter protocol drift")
    require(proof.get("recoveryJitterSeed") == RECOVERY_JITTER_SEED, f"{trial_id}: jitter seed drift")
    require(proof.get("recoveryJitterSampleCount") == 0 and proof.get("recoveryJitterSamples") == [], f"{trial_id}: N2 unexpectedly consumed recovery jitter")
    require(proof.get("firstResponseTimeoutMs") == FIRST_RESPONSE_TIMEOUT_MS, f"{trial_id}: response timeout drift")
    require(proof.get("readTimeoutMs") == READ_TIMEOUT_MS, f"{trial_id}: read timeout drift")

    expected_recovery = {
        "policyId": recovery_policy["policyId"],
        "remoteAttemptLimit": recovery_policy["remoteAttemptLimit"],
        "deliveryBindingRefreshLimit": recovery_policy["deliveryBindingRefreshLimit"],
        "backoffBaseMs": recovery_policy["backoffBaseMs"],
        "backoffCapMs": recovery_policy["backoffCapMs"],
    }
    observed_recovery = {
        "policyId": proof.get("runtimeRecoveryPolicyId"),
        "remoteAttemptLimit": proof.get("runtimeRemoteAttemptLimit"),
        "deliveryBindingRefreshLimit": proof.get("runtimeDeliveryBindingRefreshLimit"),
        "backoffBaseMs": proof.get("runtimeBackoffBaseMs"),
        "backoffCapMs": proof.get("runtimeBackoffCapMs"),
    }
    require(observed_recovery == expected_recovery, f"{trial_id}: runtime recovery policy drift")

    timing_row = {
        "trialId": trial_id,
        "orderingBlock": expected["orderingBlock"],
        "positionInBlock": expected["positionInBlock"],
        "backendId": expected["backendId"],
        "firstBrokerChunkUs": metrics["firstByteUs"],
        "chainCompletionUs": metrics["completionUs"],
        "physicalAttempts": [
            {
                "ownerOrdinal": 1,
                "attemptStartUs": (start_ns - chain_start) // 1_000,
                "responseHeadersUs": (phase_ns[0] - chain_start) // 1_000,
                "firstTransportBodyUs": (phase_ns[1] - chain_start) // 1_000,
                "responseBodyCompleteUs": (phase_ns[2] - chain_start) // 1_000,
                "attemptTerminalUs": (end_ns - chain_start) // 1_000,
                "terminal": attempt["terminal"],
            }
        ],
    }
    return copy.deepcopy(row), {
        "requestId": correlated[0],
        "processInstanceId": proof["processInstanceId"],
        "processPid": proof["processPid"],
        "processStartClockTicks": proof["processStartClockTicks"],
    }, timing_row


def verify_n2(
    *,
    plan: dict[str, Any],
    raw_dir: pathlib.Path,
    trial_root: pathlib.Path,
    origin: list[dict[str, Any]],
    scenario: dict[str, Any],
    inputs: dict[str, dict[str, Any]],
    environment: dict[str, Any],
    fault_engine: dict[str, Any],
    link_state: dict[str, Any],
    android_runtime: dict[str, Any],
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    require(scenario.get("scenarioFamily") == "N2" and scenario.get("variant") == "HIGH_RTT_JITTER", "this G2-C slice accepts N2 only")
    require(scenario.get("randomSeed") == 424_242, "N2 canonical seed drift")
    validate_frozen_work(inputs["work"])
    validate_plan(plan, scenario=scenario, inputs=inputs)
    require(environment.get("runId") == plan["runId"], "environment runId drift")
    verify_environment(
        environment,
        fault_engine=fault_engine,
        link_state=link_state,
        android_runtime=android_runtime,
    )

    schedule = planned_trial_schedule(plan)
    require(len(schedule) == 4, "N2 requires two complete paired blocks")
    expected_names = {row["trialId"] + ".json" for row in schedule}
    actual_names = {path.name for path in raw_dir.glob("*.json")}
    require(actual_names == expected_names, "raw N2 trial set differs from frozen plan")

    rows: list[dict[str, Any]] = []
    proofs: list[dict[str, Any]] = []
    timing_rows: list[dict[str, Any]] = []
    for expected in schedule:
        trial_id = expected["trialId"]
        validate_n2_harness(trial_dir=trial_root / trial_id, plan=plan)
        raw = load_json(raw_dir / f"{trial_id}.json")
        row, proof, timing = check_raw_n2(
            raw=raw,
            expected=expected,
            plan=plan,
            device_state=inputs["deviceState"],
            recovery_policy=inputs["recoveryPolicy"],
        )
        rows.append(row)
        proofs.append(proof)
        timing_rows.append(timing)

    process_uuids = [proof["processInstanceId"] for proof in proofs]
    process_keys = [(proof["processPid"], proof["processStartClockTicks"]) for proof in proofs]
    require(len(set(process_uuids)) == 4, "N2 COLD run reused instrumentation process UUID")
    require(len(set(process_keys)) == 4, "N2 COLD run reused OS process identity/starttime")

    request_ids = [proof["requestId"] for proof in proofs]
    require(len(set(request_ids)) == 4 and request_ids == sorted(request_ids), "N2 origin request order/correlation drift")
    data = [
        row for row in origin
        if row.get("plane") == "data"
        and row.get("method") == "GET"
        and row.get("path") == RESOURCE_PATH
    ]
    by_id = {row.get("requestId"): row for row in data}
    require(len(data) == 4 and len(by_id) == 4, "N2 must have exactly four origin-visible media GETs")
    require(set(by_id) == set(request_ids), "N2 raw trials do not bijectively map to origin GETs")
    lab_hashes = {row.get("scenarioHash") for row in data}
    require(
        len(lab_hashes) == 1
        and isinstance(next(iter(lab_hashes)), str)
        and len(next(iter(lab_hashes))) == 64,
        "Media Lab configuration changed during N2 pair",
    )
    for expected, proof in zip(schedule, proofs):
        origin_row = by_id[proof["requestId"]]
        trial_id = expected["trialId"]
        require(origin_row.get("profileId") == "N0" and origin_row.get("scenarioId") == "N0", f"{trial_id}: Media Lab must remain deterministic N0")
        require(origin_row.get("rangeHeader") == f"bytes=0-{RESOURCE_LENGTH - 1}", f"{trial_id}: origin Range drift")
        require(origin_row.get("resolvedRangeStart") == 0 and origin_row.get("resolvedRangeEndExclusive") == RESOURCE_LENGTH, f"{trial_id}: origin resolved range drift")
        require(origin_row.get("status") == 206, f"{trial_id}: origin status drift")
        require(origin_row.get("plannedResponseBytes") == RESOURCE_LENGTH, f"{trial_id}: origin body plan drift")
        require(origin_row.get("bodyBytesWritten") == RESOURCE_LENGTH, f"{trial_id}: origin body incomplete")
        require(origin_row.get("outcome") == "SUCCESS", f"{trial_id}: origin outcome drift")

    finalized_rows: list[dict[str, Any]] = []
    for raw_row in rows:
        row = copy.deepcopy(raw_row)
        row["recovery"]["internalRetryVisibility"] = "OBSERVABLE"
        row["recovery"]["internalRetryCount"] = 0
        row["performanceSampleEligible"] = True
        row["limitations"] = [
            item for item in row["limitations"]
            if item != "RAW_DEVICE_ROW_REQUIRES_HOST_RETRY_FINALIZATION"
        ]
        row["limitations"].append(
            "INTERNAL_HTTP_REPLAY_ZERO_OBSERVED_FROM_EXACT_ORIGIN_GET_BIJECTION"
        )
        finalized_rows.append(row)

    trials = {
        "schemaVersion": 1,
        "runId": plan["runId"],
        "pairId": plan["pairId"],
        "deviceClass": inputs["deviceState"]["deviceClass"],
        "androidApi": inputs["deviceState"]["androidApi"],
        "clockDomain": "ANDROID_MONOTONIC",
        "orderingProtocol": plan["orderingProtocol"],
        "orderingSeed": plan["orderingSeed"],
        "trials": finalized_rows,
        "limitations": [
            "API36_EMULATOR_DIRECTIONAL_ONLY",
            "N2_HIGH_RTT_JITTER_ONLY",
            "NO_PRODUCTION_TRANSPORT_SELECTION",
            "NETEM_SEED_BINDS_CONFIGURATION_NOT_BIT_EXACT_EFFECT_REPLAY",
        ],
    }
    validate_trials_against_plan(plan, trials, scenario=scenario, inputs=inputs)
    analysis = analyze_trials(trials)
    require(analysis["comparisonResult"]["correctnessEquivalent"] is True, "N2 correctness equivalence failed")
    require(analysis["comparisonResult"]["recoveryEquivalent"] is True, "N2 recovery equivalence failed")
    require(analysis["comparisonResult"]["routeBindingEquivalent"] is True, "N2 exact-route equivalence failed")
    require(analysis["pairedPerformanceBlockCount"] == 2, "N2 lost a complete paired block")
    require(set(analysis["backendEligibility"].values()) == {"ELIGIBLE"}, "N2 requires both API36 backends eligible")

    timings = {
        "schemaVersion": 1,
        "runId": plan["runId"],
        "pairId": plan["pairId"],
        "scenarioFamily": "N2",
        "clockDomain": "ANDROID_MONOTONIC",
        "rows": timing_rows,
        "limitations": [
            "EMULATOR_DIRECTIONAL_TIMINGS_ONLY",
            "PHASES_ARE_RELATIVE_TO_RECOVERY_CHAIN_START",
            "NO_CROSS_CLOCK_DOMAIN_ARITHMETIC",
        ],
    }
    validate_instance(load_json(PHASE_SCHEMA), timings)
    scan_evidence_privacy(timings)

    verification = {
        "schemaVersion": 1,
        "phase": "M2-G2-C-N2",
        "status": "PASS",
        "claimScope": "API36_EMULATOR_DIRECTIONAL_ONLY",
        "trialCount": len(finalized_rows),
        "pairedBlockCount": analysis["pairedPerformanceBlockCount"],
        "correctnessEquivalent": analysis["comparisonResult"]["correctnessEquivalent"],
        "recoveryEquivalent": analysis["comparisonResult"]["recoveryEquivalent"],
        "routeBindingEquivalent": analysis["comparisonResult"]["routeBindingEquivalent"],
        "netemSeed": 424_242,
        "selectedBackend": None,
    }
    return trials, timings, verification


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--plan", required=True, type=pathlib.Path)
    parser.add_argument("--raw-dir", required=True, type=pathlib.Path)
    parser.add_argument("--trial-root", required=True, type=pathlib.Path)
    parser.add_argument("--origin-trace", required=True, type=pathlib.Path)
    parser.add_argument("--scenario", required=True, type=pathlib.Path)
    parser.add_argument("--work", required=True, type=pathlib.Path)
    parser.add_argument("--device-state", required=True, type=pathlib.Path)
    parser.add_argument("--cache-state", required=True, type=pathlib.Path)
    parser.add_argument("--recovery-policy", required=True, type=pathlib.Path)
    parser.add_argument("--route-policy", required=True, type=pathlib.Path)
    parser.add_argument("--environment", required=True, type=pathlib.Path)
    parser.add_argument("--fault-engine-fingerprint", required=True, type=pathlib.Path)
    parser.add_argument("--link-state", required=True, type=pathlib.Path)
    parser.add_argument("--android-runtime", required=True, type=pathlib.Path)
    parser.add_argument("--output-dir", required=True, type=pathlib.Path)
    args = parser.parse_args()

    plan = load_json(args.plan)
    scenario = load_json(args.scenario)
    inputs = load_inputs(args)
    trials, timings, verification = verify_n2(
        plan=plan,
        raw_dir=args.raw_dir,
        trial_root=args.trial_root,
        origin=load_jsonl(args.origin_trace),
        scenario=scenario,
        inputs=inputs,
        environment=load_json(args.environment),
        fault_engine=load_json(args.fault_engine_fingerprint),
        link_state=load_json(args.link_state),
        android_runtime=load_json(args.android_runtime),
    )
    args.output_dir.mkdir(parents=True, exist_ok=True)
    outputs = {
        "transport-evaluation-trials-v1.json": trials,
        "transport-phase-timings-v1.json": timings,
        "verification.json": verification,
    }
    for name, value in outputs.items():
        scan_evidence_privacy(value)
        (args.output_dir / name).write_text(
            json.dumps(value, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    print("M2-G2-C N2 paired API36 evidence verified; emulator directional evidence only")


if __name__ == "__main__":
    main()
