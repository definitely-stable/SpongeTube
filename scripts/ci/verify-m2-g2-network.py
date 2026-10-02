#!/usr/bin/env python3
"""Verify M2-G2-C paired NETWORK evidence for N2/N3/N5."""
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

COMMON_ACTIVE = {
    "direction": "DOWNSTREAM",
    "ipFamily": "IPV4",
    "l4Protocol": "TCP",
    "mediaPortScoped": True,
    "scope": "MEDIA_DATA_ONLY",
}
SPECS: dict[str, dict[str, Any]] = {
    "N2": {
        "family": "N2",
        "variant": "HIGH_RTT_JITTER",
        "seed": 424_242,
        "mtu": 1500,
        "packetizationProfile": "STANDARD_MTU1500_V1",
        "active": {
            **COMMON_ACTIVE,
            "delayUs": 100_000,
            "jitterUs": 30_000,
            "delayCorrelationPpm": 250_000,
            "randomSeed": 424_242,
        },
        "limitation": "N2_HIGH_RTT_JITTER_ONLY",
        "performanceEligible": True,
        "requireEffectEveryRow": False,
    },
    "N3": {
        "family": "N3",
        "variant": "BURST_PACKET_LOSS",
        "seed": None,
        "mtu": 1500,
        "packetizationProfile": "STANDARD_MTU1500_V1",
        "active": {**COMMON_ACTIVE, "lossPpm": 1_000_000},
        "limitation": "N3_1500MS_PACKET_BLACKOUT_ONLY",
        "performanceEligible": True,
        "requireEffectEveryRow": True,
    },
    "N5": {
        "family": "N5",
        "variant": "BURST_LOSS",
        "seed": 424_242,
        "mtu": 1500,
        "packetizationProfile": "STANDARD_MTU1500_V1",
        "active": {
            **COMMON_ACTIVE,
            "lossPpm": 20_000,
            "burstCorrelationPpm": 250_000,
            "randomSeed": 424_242,
        },
        "limitation": "N5_LEGACY_CORRELATED_RANDOM_CONFIGURATION_BOUND_ONLY",
        "performanceEligible": True,
        "requireEffectEveryRow": False,
    },
    "N5GE": {
        "family": "N5",
        "variant": "BURST_LOSS_GE_MOMENT_MATCH",
        "seed": 424_242,
        "mtu": 512,
        "packetizationProfile": "G2_EFFECT_MTU512_V1",
        "active": {
            **COMMON_ACTIVE,
            "goodToBadPpm": 15_000,
            "badToGoodPpm": 735_000,
            "badLossPpm": 1_000_000,
            "goodLossPpm": 0,
            "randomSeed": 424_242,
        },
        "limitation": "N5_GE_MOMENT_MATCH_V1",
        "performanceEligible": True,
        "requireEffectEveryRow": True,
    },
}

N2_ACTIVE_STATE = SPECS["N2"]["active"]
N3_ACTIVE_STATE = SPECS["N3"]["active"]
N5_ACTIVE_STATE = SPECS["N5"]["active"]
N5_GE_ACTIVE_STATE = SPECS["N5GE"]["active"]


class NetworkEvidenceError(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise NetworkEvidenceError(message)


def load_json(path: pathlib.Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    require(isinstance(value, dict), f"{path}: expected JSON object")
    return value


def load_json_array(path: pathlib.Path) -> list[Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    require(isinstance(value, list), f"{path}: expected JSON array")
    return value


def load_jsonl(path: pathlib.Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            value = json.loads(line)
            require(isinstance(value, dict), f"{path}: expected JSON object rows")
            rows.append(value)
    return rows


def load_count(path: pathlib.Path) -> int:
    raw = path.read_text(encoding="utf-8").strip()
    require(raw.isdigit(), f"{path}: expected non-negative line count")
    return int(raw)


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
        work == {
            "fixtureId": "F1",
            "trackId": "video-main",
            "representationId": "f1-video-0",
            "byteStart": 0,
            "byteEndExclusive": RESOURCE_LENGTH,
            "expectedSha256": RESOURCE_SHA256,
        },
        "G2-C work fingerprint is not the frozen F1 video unit",
    )


def scenario_spec(scenario: Mapping[str, Any]) -> tuple[str, dict[str, Any]]:
    family = scenario.get("scenarioFamily")
    variant = scenario.get("variant")
    matches = [
        (profile, spec)
        for profile, spec in SPECS.items()
        if spec["family"] == family and spec["variant"] == variant
    ]
    require(len(matches) == 1, f"unsupported G2-C NETWORK scenario {family!r}/{variant!r}")
    profile, spec = matches[0]
    require(scenario.get("primaryPlane") == "NETWORK", f"{profile}: primary plane drift")
    require(scenario.get("randomSeed") == spec["seed"], f"{profile}: canonical seed drift")
    return profile, spec


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


def _counter(entry: Mapping[str, Any], name: str) -> int:
    for container in (entry, entry.get("stats") or {}, entry.get("stats2") or {}):
        if isinstance(container, Mapping):
            value = container.get(name)
            if type(value) is int and value >= 0:
                return value
    raise NetworkEvidenceError(f"netem {name} counter missing")


def validate_netem_options(profile: str, netem: Mapping[str, Any]) -> None:
    options = netem.get("options")
    require(isinstance(options, Mapping), f"{profile}: netem options missing")
    if profile == "N2":
        delay = options.get("delay")
        require(isinstance(delay, Mapping), "N2 delay readback missing")
        require(_us(delay.get("delay"), "delay") == 100_000, "N2 delay readback drift")
        require(_us(delay.get("jitter"), "jitter") == 30_000, "N2 jitter readback drift")
        require(_ppm(delay.get("correlation"), "delay correlation") == 250_000, "N2 correlation readback drift")
        require(options.get("seed") == 424_242, "N2 seed readback drift")
    elif profile in {"N3", "N5"}:
        loss = options.get("loss-random")
        require(isinstance(loss, Mapping), f"{profile}: loss readback missing")
        expected_loss = 1_000_000 if profile == "N3" else 20_000
        require(_ppm(loss.get("loss"), "loss") == expected_loss, f"{profile}: loss readback drift")
        if profile == "N5":
            require(_ppm(loss.get("correlation"), "loss correlation") == 250_000, "N5 loss correlation drift")
            require(options.get("seed") == 424_242, "N5 seed readback drift")
    else:
        loss = options.get("loss-gemodel")
        require(isinstance(loss, Mapping), "N5GE Gilbert-Elliott readback missing")
        require(_ppm(loss.get("p"), "GE good-to-bad") == 15_000, "N5GE good-to-bad drift")
        require(_ppm(loss.get("r"), "GE bad-to-good") == 735_000, "N5GE bad-to-good drift")
        require(_ppm(loss.get("1-h"), "GE bad-state loss") == 1_000_000, "N5GE bad-state loss drift")
        require(_ppm(loss.get("1-k"), "GE good-state loss") == 0, "N5GE good-state loss drift")
        require(options.get("seed") == 424_242, "N5GE seed readback drift")


def validate_harness(
    *,
    profile: str,
    trial_dir: pathlib.Path,
    plan: Mapping[str, Any],
) -> dict[str, int]:
    spec = SPECS[profile]
    family = spec["family"]
    harness_dir = trial_dir / "harness"
    active = load_json(harness_dir / "netem-active-state.json")
    require(active == spec["active"], f"{family} active netem readback drift")

    clean = load_json(harness_dir / "netem-clean-state.json")
    require(clean == {"filters": 0, "netem": 0, "prio": 0}, f"{family} netem cleanup proof drift")

    evidence = load_json(harness_dir / "fault-harness-events.json")
    require(evidence.get("runId") == plan["runId"], f"{family} harness runId drift")
    require(evidence.get("scenarioHash") == plan["scenario"]["hash"], f"{family} harness scenario hash drift")
    operations = [event.get("operation") for event in evidence.get("events", [])]
    require(
        operations == ["HARNESS_STARTED", "FAULT_ARMED", "FAULT_APPLIED", "FAULT_REMOVED", "HARNESS_STOPPED"],
        f"{family} harness lifecycle is incomplete",
    )

    final_qdisc = load_json_array(harness_dir / "qdisc-final.json")
    final_netem = _find_kind(final_qdisc, "netem")
    require(isinstance(final_netem, Mapping), f"{family}: final qdisc has no netem node")
    require(final_netem.get("parent") == "1:1", f"{family}: netem left scoped impaired band")
    validate_netem_options(profile, final_netem)
    final_packets = _counter(final_netem, "packets")
    final_bytes = _counter(final_netem, "bytes")
    final_drops = _counter(final_netem, "drops")
    if family == "N3":
        # A 100% blackout may legitimately report only drops while the pulse is
        # active. End-to-end namespace counters below prove media-path traffic.
        require(final_drops > 0, "N3 blackout produced no scoped drop")
    else:
        require(
            final_packets > 0 and final_bytes > 0,
            f"{family}: netem saw no media traffic",
        )

    filters = load_json_array(harness_dir / "filter.json")
    flowers = [
        row for row in filters
        if isinstance(row, dict)
        and row.get("kind") == "flower"
        and isinstance(row.get("options"), dict)
    ]
    require(len(flowers) == 1, f"{family}: exactly one scoped flower classifier required")
    flower = flowers[0]
    require(_has_key_value(flower, "src_port", 18081), f"{family}: flower media port drift")
    require(_has_key_value(flower, "ip_proto", "tcp"), f"{family}: flower protocol drift")
    require(
        _has_key_value(flower, "eth_type", "ipv4") or _has_key_value(flower, "protocol", "ip"),
        f"{family}: flower IP family drift",
    )
    require(_has_key_value(flower, "classid", "1:1"), f"{family}: flower class drift")

    before = load_json(trial_dir / "scope-before.json")
    after = load_json(trial_dir / "scope-after.json")
    require(after["mediaPackets"] > before["mediaPackets"], f"{family}: media packet counter did not advance")
    require(after["mediaBytes"] > before["mediaBytes"], f"{family}: media byte counter did not advance")

    baseline_drops = 0
    if profile in {"N3", "N5", "N5GE"}:
        applied = load_json_array(harness_dir / "qdisc-applied.json")
        applied_netem = _find_kind(applied, "netem")
        require(isinstance(applied_netem, Mapping), f"{profile}: applied qdisc has no netem node")
        baseline_drops = _counter(applied_netem, "drops")
        if profile == "N3":
            require(baseline_drops > 0, "N3 blackout rendezvous did not observe its first scoped drop")
            require(final_drops >= baseline_drops, "N3 final drop counter regressed")
        elif spec["requireEffectEveryRow"]:
            require(
                final_drops > baseline_drops,
                f"{profile} measured trial observed zero scoped drops above its pre-trial baseline",
            )

    return {
        "packets": final_packets,
        "bytes": final_bytes,
        "baselineDrops": baseline_drops,
        "finalDrops": final_drops,
    }


def validate_failure_lineage(failures: Any, *, trial_id: str) -> None:
    require(isinstance(failures, list), f"{trial_id}: recovery failures missing")
    for failure in failures:
        require(isinstance(failure, Mapping), f"{trial_id}: malformed recovery failure")
        observation = failure.get("observation")
        decision = failure.get("decision")
        action = failure.get("action")
        require(
            isinstance(observation, Mapping)
            and observation.get("plane") == "TRANSPORT"
            and observation.get("type") == "TRANSPORT_IO",
            f"{trial_id}: NETWORK recovery failure escaped TRANSPORT observation plane",
        )
        require(
            failure.get("classification") == "TRANSIENT_TRANSPORT",
            f"{trial_id}: NETWORK failure classification drift",
        )
        require(
            isinstance(decision, Mapping) and decision.get("kind") == "RETRY_AFTER_BACKOFF",
            f"{trial_id}: NETWORK failure decision drift",
        )
        require(
            isinstance(action, Mapping) and action.get("kind") == "SCHEDULE_BACKOFF",
            f"{trial_id}: NETWORK recovery action drift",
        )


def check_raw(
    *,
    profile: str,
    raw: dict[str, Any],
    expected: Mapping[str, Any],
    plan: Mapping[str, Any],
    device_state: Mapping[str, Any],
    recovery_policy: Mapping[str, Any],
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    trial_id = expected["trialId"]
    spec = SPECS[profile]
    family = spec["family"]
    require(raw.get("schemaVersion") == 1, f"{trial_id}: wrong raw schema")
    require(raw.get("phase") == "M2-G2-C-NETWORK", f"{trial_id}: wrong phase")
    require(raw.get("scenarioFamily") == family, f"{trial_id}: wrong scenario family")
    require(raw.get("scenarioVariant") == spec["variant"], f"{trial_id}: wrong scenario variant")
    require(raw.get("runId") == plan["runId"] and raw.get("pairId") == plan["pairId"], f"{trial_id}: run identity drift")

    row = raw.get("trial")
    proof = raw.get("proof")
    require(isinstance(row, dict) and isinstance(proof, dict), f"{trial_id}: raw trial/proof missing")
    for field in ("trialId", "orderingBlock", "positionInBlock", "backendId"):
        require(row.get(field) == expected[field], f"{trial_id}: {field} drift")
    require(row.get("comparison") == plan["comparison"], f"{trial_id}: comparison fingerprint drift")
    require(row.get("eligibility") == "ELIGIBLE", f"{trial_id}: backend unavailable")
    require(row.get("result") == "SUCCESS", f"{trial_id}: trial did not recover to SUCCESS")
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
    require(isinstance(recovery, dict), f"{trial_id}: recovery row missing")
    owner_count = recovery.get("ownerCount")
    raw_origin_count = recovery.get("originRequestCount")
    require(recovery.get("recoveryChainCount") == 1, f"{trial_id}: recovery chain count drift")
    require(type(owner_count) is int and owner_count >= 1, f"{trial_id}: owner count invalid")
    require(type(raw_origin_count) is int and raw_origin_count >= 0, f"{trial_id}: raw origin count invalid")
    require(
        recovery.get("internalRetryVisibility") == "OPAQUE" and recovery.get("internalRetryCount") is None,
        f"{trial_id}: raw row must not self-certify transport-internal retries",
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
    require(metrics["bytesPublished"] == RESOURCE_LENGTH, f"{trial_id}: published bytes drift")
    require(metrics.get("cancellationLatencyUs") is None, f"{trial_id}: cancellation metric must stay null")

    correlated = proof.get("correlatedOriginRequestIds")
    require(isinstance(correlated, list), f"{trial_id}: origin correlation list missing")
    require(
        all(type(value) is int and value > 0 for value in correlated)
        and len(correlated) == len(set(correlated)),
        f"{trial_id}: malformed/reused origin correlation id",
    )
    require(raw_origin_count == len(correlated), f"{trial_id}: raw origin correlation count drift")
    require(proof.get("committedSha256") == RESOURCE_SHA256, f"{trial_id}: committed SHA drift")
    require(proof.get("committedBytes") == RESOURCE_LENGTH, f"{trial_id}: committed bytes drift")
    require(proof.get("extentStoreInitiallyEmpty") is True, f"{trial_id}: COLD store proof failed")
    require(proof.get("transportSessionFresh") is True, f"{trial_id}: transport session reset not asserted")
    require(
        proof.get("routeEpochBefore") == route["permitRouteEpoch"] == proof.get("routeEpochAfter"),
        f"{trial_id}: route epoch changed during NETWORK trial",
    )
    require(proof.get("bindingRevision") == "binding-1", f"{trial_id}: binding revision drift")
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
    require(isinstance(attempts, list) and attempts, f"{trial_id}: physical attempts missing")
    require(len(attempts) == owner_count, f"{trial_id}: owner/physical-attempt count drift")
    require(proof.get("bindingTargetResolutionCount") == owner_count, f"{trial_id}: binding target count drift")
    require(
        sum(1 for attempt in attempts if attempt.get("terminal") == "ATTEMPT_COMPLETED") == 1
        and attempts[-1].get("terminal") == "ATTEMPT_COMPLETED"
        and all(attempt.get("terminal") == "ATTEMPT_FAILED" for attempt in attempts[:-1]),
        f"{trial_id}: physical attempt terminal sequence invalid",
    )

    prior_end = chain_start
    total_network_bytes = 0
    for attempt in attempts:
        start_ns = attempt.get("startElapsedRealtimeNs")
        end_ns = attempt.get("endElapsedRealtimeNs")
        network_bytes = attempt.get("networkBytes")
        require(
            type(start_ns) is int
            and type(end_ns) is int
            and prior_end <= start_ns <= end_ns <= chain_end,
            f"{trial_id}: physical attempt timing invalid",
        )
        require(type(network_bytes) is int and network_bytes >= 0, f"{trial_id}: physical network bytes invalid")
        prior_end = end_ns
        total_network_bytes += network_bytes
    require(total_network_bytes == metrics["bytesReceived"], f"{trial_id}: accepted network byte accounting drift")
    require(metrics["bytesReceived"] >= RESOURCE_LENGTH, f"{trial_id}: successful chain accepted too few bytes")

    phases = proof.get("transportPhases")
    require(isinstance(phases, list), f"{trial_id}: transport phases missing")
    phase_times = [phase.get("elapsedRealtimeNs") for phase in phases]
    require(
        all(type(value) is int for value in phase_times)
        and phase_times == sorted(phase_times),
        f"{trial_id}: transport phase timestamps not ordered",
    )

    timing_attempts: list[dict[str, Any]] = []
    expected_prefix = ["RESPONSE_HEADERS", "FIRST_BODY_BYTES", "RESPONSE_BODY_COMPLETE"]
    for ordinal, attempt in enumerate(attempts, start=1):
        start_ns = attempt["startElapsedRealtimeNs"]
        end_ns = attempt["endElapsedRealtimeNs"]
        owned = [phase for phase in phases if start_ns <= phase["elapsedRealtimeNs"] <= end_ns]
        kinds = [phase.get("kind") for phase in owned]
        require(kinds == expected_prefix[:len(kinds)], f"{trial_id}: transport phase prefix drift in owner {ordinal}")
        if attempt["terminal"] == "ATTEMPT_COMPLETED":
            require(kinds == expected_prefix, f"{trial_id}: successful owner lacks complete transport phases")
        else:
            require("RESPONSE_BODY_COMPLETE" not in kinds, f"{trial_id}: failed owner reported completed body")
        values = {phase["kind"]: phase["elapsedRealtimeNs"] for phase in owned}
        timing_attempts.append({
            "ownerOrdinal": ordinal,
            "attemptStartUs": (start_ns - chain_start) // 1_000,
            "responseHeadersUs": (
                (values["RESPONSE_HEADERS"] - chain_start) // 1_000
                if "RESPONSE_HEADERS" in values else None
            ),
            "firstTransportBodyUs": (
                (values["FIRST_BODY_BYTES"] - chain_start) // 1_000
                if "FIRST_BODY_BYTES" in values else None
            ),
            "responseBodyCompleteUs": (
                (values["RESPONSE_BODY_COMPLETE"] - chain_start) // 1_000
                if "RESPONSE_BODY_COMPLETE" in values else None
            ),
            "attemptTerminalUs": (end_ns - chain_start) // 1_000,
            "terminal": attempt["terminal"],
        })

    failures = proof.get("recoveryFailures")
    validate_failure_lineage(failures, trial_id=trial_id)
    require(proof.get("recoveryFailureCount") == len(failures), f"{trial_id}: recovery failure count drift")
    require(len(failures) == len(attempts) - 1, f"{trial_id}: failure/attempt lineage drift")

    backoffs = proof.get("recoveryBackoffs")
    require(isinstance(backoffs, list) and len(backoffs) == len(failures), f"{trial_id}: recovery backoff lineage drift")
    require(
        [backoff.get("retryOrdinal") for backoff in backoffs] == list(range(1, len(backoffs) + 1)),
        f"{trial_id}: recovery retry ordinals drift",
    )
    require(proof.get("recoveryJitterProtocol") == RECOVERY_JITTER_PROTOCOL, f"{trial_id}: jitter protocol drift")
    require(proof.get("recoveryJitterSeed") == RECOVERY_JITTER_SEED, f"{trial_id}: jitter seed drift")
    samples = proof.get("recoveryJitterSamples")
    require(isinstance(samples, list), f"{trial_id}: jitter samples missing")
    require(proof.get("recoveryJitterSampleCount") == len(samples) == len(backoffs), f"{trial_id}: jitter sample count drift")
    require(samples == [backoff.get("delayMs") for backoff in backoffs], f"{trial_id}: jitter/backoff delay drift")
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
    require(owner_count <= recovery_policy["remoteAttemptLimit"], f"{trial_id}: remote attempt budget exceeded")

    timing_row = {
        "trialId": trial_id,
        "orderingBlock": expected["orderingBlock"],
        "positionInBlock": expected["positionInBlock"],
        "backendId": expected["backendId"],
        "firstBrokerChunkUs": metrics["firstByteUs"],
        "chainCompletionUs": metrics["completionUs"],
        "physicalAttempts": timing_attempts,
    }
    return copy.deepcopy(row), {
        "correlatedRequestIds": correlated,
        "physicalAttemptCount": len(attempts),
        "processInstanceId": proof["processInstanceId"],
        "processPid": proof["processPid"],
        "processStartClockTicks": proof["processStartClockTicks"],
    }, timing_row


def validate_origin_row(row: Mapping[str, Any], *, trial_id: str) -> None:
    require(row.get("plane") == "data" and row.get("method") == "GET", f"{trial_id}: unexpected origin trace row")
    require(row.get("path") == RESOURCE_PATH, f"{trial_id}: origin path drift")
    require(row.get("profileId") == "N0" and row.get("scenarioId") == "N0", f"{trial_id}: Media Lab must stay deterministic N0")
    require(row.get("rangeHeader") == f"bytes=0-{RESOURCE_LENGTH - 1}", f"{trial_id}: origin Range drift")
    require(row.get("resolvedRangeStart") == 0 and row.get("resolvedRangeEndExclusive") == RESOURCE_LENGTH, f"{trial_id}: origin resolved range drift")
    require(row.get("status") == 206, f"{trial_id}: origin status drift")
    require(row.get("plannedResponseBytes") == RESOURCE_LENGTH, f"{trial_id}: origin body plan drift")
    require(row.get("bodyBytesWritten") == RESOURCE_LENGTH, f"{trial_id}: origin body incomplete")
    require(row.get("outcome") == "SUCCESS", f"{trial_id}: origin outcome drift")


def verify_network(
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
    profile, spec = scenario_spec(scenario)
    family = spec["family"]
    validate_frozen_work(inputs["work"])
    validate_plan(plan, scenario=scenario, inputs=inputs)
    require(environment.get("runId") == plan["runId"], "environment runId drift")
    verify_environment(
        environment,
        fault_engine=fault_engine,
        link_state=link_state,
        android_runtime=android_runtime,
    )
    require(
        environment.get("mediaLink", {}).get("mtu") == spec["mtu"],
        f"{family}: media-link MTU drift",
    )
    require(
        environment.get("mediaLink", {}).get("packetizationProfile")
        == spec["packetizationProfile"],
        f"{family}: packetization profile drift",
    )

    schedule = planned_trial_schedule(plan)
    require(len(schedule) == 4, f"{family} requires two complete paired blocks")
    expected_names = {row["trialId"] + ".json" for row in schedule}
    actual_names = {path.name for path in raw_dir.glob("*.json")}
    require(actual_names == expected_names, f"raw {family} trial set differs from frozen plan")

    rows: list[dict[str, Any]] = []
    proofs: list[dict[str, Any]] = []
    timing_rows: list[dict[str, Any]] = []
    slices: list[list[dict[str, Any]]] = []
    effect_rows: list[dict[str, int]] = []

    first_trial_id = schedule[0]["trialId"]
    previous_end = load_count(
        trial_root / first_trial_id / "origin-before-count.txt"
    )
    require(
        previous_end == 1 and previous_end <= len(origin),
        f"{family}: expected exactly one traced Media Lab readiness prelude",
    )
    prelude = origin[:previous_end]
    require(
        len(prelude) == 1
        and prelude[0].get("plane") == "control"
        and prelude[0].get("method") == "GET"
        and prelude[0].get("path") == "/__lab/config"
        and prelude[0].get("status") == 200
        and prelude[0].get("outcome") == "SUCCESS",
        f"{family}: unexpected origin trace prelude",
    )

    for expected in schedule:
        trial_id = expected["trialId"]
        effect_rows.append(
            validate_harness(profile=profile, trial_dir=trial_root / trial_id, plan=plan)
        )
        start = load_count(trial_root / trial_id / "origin-before-count.txt")
        end = load_count(trial_root / trial_id / "origin-after-count.txt")
        require(start == previous_end and start <= end <= len(origin), f"{trial_id}: origin trace partition drift")
        trial_origin = origin[start:end]
        require(trial_origin, f"{trial_id}: no origin-visible media request")
        for origin_row in trial_origin:
            validate_origin_row(origin_row, trial_id=trial_id)
        previous_end = end
        slices.append(trial_origin)

        raw = load_json(raw_dir / f"{trial_id}.json")
        row, proof, timing = check_raw(
            profile=profile,
            raw=raw,
            expected=expected,
            plan=plan,
            device_state=inputs["deviceState"],
            recovery_policy=inputs["recoveryPolicy"],
        )
        rows.append(row)
        proofs.append(proof)
        timing_rows.append(timing)

    require(previous_end == len(origin), f"{family}: unassigned origin trace rows remain")

    process_uuids = [proof["processInstanceId"] for proof in proofs]
    process_keys = [(proof["processPid"], proof["processStartClockTicks"]) for proof in proofs]
    require(len(set(process_uuids)) == 4, f"{family} COLD run reused instrumentation process UUID")
    require(len(set(process_keys)) == 4, f"{family} COLD run reused OS process identity/starttime")

    lab_hashes = {row.get("scenarioHash") for rows_ in slices for row in rows_}
    require(
        len(lab_hashes) == 1
        and isinstance(next(iter(lab_hashes)), str)
        and len(next(iter(lab_hashes))) == 64,
        f"Media Lab configuration changed during {family} pair",
    )

    finalized_rows: list[dict[str, Any]] = []
    total_internal_replays = 0
    for raw_row, proof, trial_origin in zip(rows, proofs, slices):
        origin_ids = [row.get("requestId") for row in trial_origin]
        require(
            all(type(value) is int and value > 0 for value in origin_ids)
            and len(origin_ids) == len(set(origin_ids)),
            f"{raw_row['trialId']}: malformed/reused origin request id",
        )
        require(
            set(proof["correlatedRequestIds"]).issubset(set(origin_ids)),
            f"{raw_row['trialId']}: device correlation escaped its serialized trial origin slice",
        )
        physical = proof["physicalAttemptCount"]
        require(
            len(trial_origin) >= physical,
            f"{raw_row['trialId']}: origin visibility is insufficient to count internal HTTP replay",
        )
        internal_retries = len(trial_origin) - physical
        total_internal_replays += internal_retries

        row = copy.deepcopy(raw_row)
        row["recovery"]["originRequestCount"] = len(trial_origin)
        row["recovery"]["internalRetryVisibility"] = "OBSERVABLE"
        row["recovery"]["internalRetryCount"] = internal_retries
        row["performanceSampleEligible"] = bool(spec["performanceEligible"])
        row["limitations"] = [
            item for item in row["limitations"]
            if item != "RAW_DEVICE_ROW_REQUIRES_HOST_RETRY_FINALIZATION"
        ]
        row["limitations"].append(
            "INTERNAL_HTTP_REPLAY_COUNT_DERIVED_FROM_SERIALIZED_ORIGIN_TRACE_PARTITION"
        )
        if profile == "N5":
            row["limitations"].append(
                "LEGACY_NETEM_CORRELATED_RANDOM_STATE_IS_NOT_FULLY_SEED_REPRODUCIBLE"
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
            spec["limitation"],
            *(
                ["N5_GE_EFFECT_PROFILE_IS_DISTINCT_FROM_CANONICAL_M2E_N5"]
                if profile == "N5GE"
                else []
            ),
            "NO_PRODUCTION_TRANSPORT_SELECTION",
            "NETEM_SEED_BINDS_CONFIGURATION_NOT_BIT_EXACT_EFFECT_REPLAY",
        ],
    }
    validate_trials_against_plan(plan, trials, scenario=scenario, inputs=inputs)
    analysis = analyze_trials(trials)
    require(analysis["comparisonResult"]["correctnessEquivalent"] is True, f"{profile} correctness equivalence failed")
    require(analysis["comparisonResult"]["recoveryEquivalent"] is True, f"{profile} recovery equivalence failed")
    require(analysis["comparisonResult"]["routeBindingEquivalent"] is True, f"{profile} exact-route equivalence failed")
    expected_perf_blocks = 2 if spec["performanceEligible"] else 0
    require(
        analysis["pairedPerformanceBlockCount"] == expected_perf_blocks,
        f"{profile} paired performance eligibility drift",
    )
    require(set(analysis["backendEligibility"].values()) == {"ELIGIBLE"}, f"{profile} requires both API36 backends eligible")

    timings = {
        "schemaVersion": 1,
        "runId": plan["runId"],
        "pairId": plan["pairId"],
        "scenarioFamily": family,
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

    effect_positive = sum(
        row["finalDrops"] > row["baselineDrops"] for row in effect_rows
    )
    verification = {
        "schemaVersion": 1,
        "phase": f"M2-G2-C-{profile}",
        "status": "PASS",
        "claimScope": (
            "CONFIGURATION_BOUND_STOCHASTIC_OBSERVATION"
            if profile == "N5"
            else "API36_EMULATOR_DIRECTIONAL_ONLY"
        ),
        "trialCount": len(finalized_rows),
        "pairedBlockCount": analysis["pairedPerformanceBlockCount"],
        "correctnessEquivalent": analysis["comparisonResult"]["correctnessEquivalent"],
        "recoveryEquivalent": analysis["comparisonResult"]["recoveryEquivalent"],
        "routeBindingEquivalent": analysis["comparisonResult"]["routeBindingEquivalent"],
        "effectPositiveTrialCount": effect_positive,
        "resilienceClaim": (
            "INCONCLUSIVE_STOCHASTIC_EFFECT"
            if profile == "N5"
            else "OBSERVED_EFFECT_EQUIVALENT"
        ),
        "netemSeed": spec["seed"],
        "mediaLinkMtu": spec["mtu"],
        "originVisibleInternalRetryCount": total_internal_replays,
        "selectedBackend": None,
    }
    return trials, timings, verification


def verify_n2(**kwargs: Any) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    require(kwargs["scenario"].get("scenarioFamily") == "N2", "verify_n2 requires N2")
    return verify_network(**kwargs)


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
    trials, timings, verification = verify_network(
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
    print(
        f"M2-G2-C {scenario['scenarioFamily']} paired API36 evidence verified; "
        "emulator directional evidence only"
    )


if __name__ == "__main__":
    main()
