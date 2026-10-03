#!/usr/bin/env python3
"""Verify M2-G2-D paired N6 TRANSPORT_RESET evidence."""
from __future__ import annotations

import argparse
import copy
import importlib.util
import json
import pathlib
import re
import sys
from typing import Any, Mapping

ROOT = pathlib.Path(__file__).resolve().parents[2]
MEASUREMENT = ROOT / "scripts" / "measurement"
sys.path.insert(0, str(MEASUREMENT))

from m2_contracts import scan_evidence_privacy  # noqa: E402
from m2_transport_evaluation_oracle import analyze_trials  # noqa: E402
import m2_transport_reset_environment as reset_env  # noqa: E402
from m2_transport_pair_plan import (  # noqa: E402
    planned_trial_schedule,
    validate_plan,
    validate_trials_against_plan,
)
from schema_subset import validate_instance  # noqa: E402

NETWORK_VERIFIER_PATH = ROOT / "scripts" / "ci" / "verify-m2-g2-network.py"
_spec = importlib.util.spec_from_file_location("m2_g2_shared_verifier", NETWORK_VERIFIER_PATH)
if _spec is None or _spec.loader is None:
    raise RuntimeError("cannot load shared G2 verifier")
shared = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(shared)

RESOURCE_PATH = shared.RESOURCE_PATH
RESOURCE_LENGTH = shared.RESOURCE_LENGTH
RESOURCE_SHA256 = shared.RESOURCE_SHA256
PHASE_SCHEMA = shared.PHASE_SCHEMA
EXPECTED_ACTIVE = {
    "name": "m2e-media",
    "enabled": True,
    "toxics": [{
        "name": "m2e-fault",
        "type": "reset_peer",
        "stream": "downstream",
        "toxicityPpm": 1_000_000,
        "attributes": {"timeout": 0},
    }],
}
EXPECTED_DISARMED = {
    "name": "m2e-media",
    "enabled": True,
    "toxics": [],
}
EXPECTED_FINAL = {"proxies": 0, "toxics": 0}
# Reuse the hardened generic raw-device proof verifier from G2-C.  Only the
# scenario identity and expected phase differ; fault lifecycle is verified here.
shared.SPECS["N6"] = {
    "family": "N6",
    "variant": "TRANSPORT_RESET",
    "performanceEligible": True,
}


class TransportResetEvidenceError(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise TransportResetEvidenceError(message)


def load_json(path: pathlib.Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    require(isinstance(value, dict), f"{path}: expected JSON object")
    return value


def load_jsonl(path: pathlib.Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            row = json.loads(line)
            require(isinstance(row, dict), f"{path}: expected JSON object rows")
            rows.append(row)
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


def validate_environment(
    environment: Mapping[str, Any],
    android_runtime: Mapping[str, Any],
) -> None:
    try:
        reset_env.verify(
            environment,
            android_runtime=android_runtime,
            root=ROOT,
        )
    except (ValueError, OSError, KeyError) as error:
        raise TransportResetEvidenceError(
            f"G2-D environment provenance invalid: {error}"
        ) from error


def validate_scenario(scenario: Mapping[str, Any]) -> None:
    require(scenario.get("scenarioFamily") == "N6", "G2-D requires N6")
    require(scenario.get("variant") == "TRANSPORT_RESET", "G2-D variant drift")
    require(scenario.get("primaryPlane") == "TRANSPORT", "G2-D primary plane drift")
    require(scenario.get("randomSeed") is None, "N6 TRANSPORT_RESET must remain deterministic")
    faults = scenario.get("transportFaults")
    require(isinstance(faults, list) and len(faults) == 1, "N6 requires one transport fault")
    fault = faults[0]
    require(
        isinstance(fault, Mapping)
        and fault.get("kind") == "RESET_PEER"
        and fault.get("stochastic") is False
        and fault.get("parameters") == {
            "timeoutMs": 0,
            "direction": "DOWNSTREAM",
            "scope": "MEDIA_DATA_ONLY",
        },
        "N6 RESET_PEER parameters drift",
    )


def validate_harness(
    trial_dir: pathlib.Path,
    *,
    plan: Mapping[str, Any],
    trial_id: str,
) -> int:
    harness = trial_dir / "harness"
    active = load_json(harness / "toxiproxy-active-state.json")
    disarmed = load_json(harness / "toxiproxy-disarmed-state.json")
    final = load_json(harness / "toxiproxy-clean-state.json")
    require(active == EXPECTED_ACTIVE, f"{trial_id}: active Toxiproxy state drift")
    require(disarmed == EXPECTED_DISARMED, f"{trial_id}: disarmed proxy state drift")
    require(final == EXPECTED_FINAL, f"{trial_id}: final proxy cleanup drift")

    evidence = load_json(harness / "fault-harness-events.json")
    require(evidence.get("runId") == plan["runId"], f"{trial_id}: harness runId drift")
    require(evidence.get("scenarioHash") == plan["scenario"]["hash"], f"{trial_id}: scenario hash drift")
    require(evidence.get("clockDomain") == "HOST_FAULT_MONOTONIC", f"{trial_id}: harness clock drift")
    harness_id = evidence.get("harness")
    require(
        harness_id == {
            "harnessId": "sponge-transport-harness",
            "harnessVersion": "1",
            "toolId": "toxiproxy",
            "toolVersion": "2.12.0",
        },
        f"{trial_id}: pinned transport harness identity drift",
    )
    events = evidence.get("events")
    require(
        isinstance(events, list) and len(events) == 5
        and all(isinstance(event, Mapping) for event in events),
        f"{trial_id}: transport harness lifecycle incomplete",
    )
    require(
        [event.get("operation") for event in events] == [
            "HARNESS_STARTED",
            "FAULT_ARMED",
            "FAULT_APPLIED",
            "FAULT_REMOVED",
            "HARNESS_STOPPED",
        ],
        f"{trial_id}: transport harness lifecycle order drift",
    )
    times = [event.get("elapsedRealtimeNs") for event in events]
    require(
        all(type(value) is int and value >= 0 for value in times)
        and times == sorted(times),
        f"{trial_id}: harness timestamps not monotonic",
    )
    applied = events[2]
    require(
        applied.get("parameters") == {"timeout": 0, "toxicType": "reset_peer"},
        f"{trial_id}: RESET_PEER readback parameters drift",
    )

    trigger = load_json(trial_dir / "reset-trigger.json")
    require(trigger.get("schemaVersion") == 1, f"{trial_id}: trigger schema drift")
    require(trigger.get("trialId") == trial_id, f"{trial_id}: trigger trial binding drift")
    require(trigger.get("signal") == "ATTEMPT_FAILED", f"{trial_id}: wrong Android trigger")
    require(
        trigger.get("hostObservationClockDomain") == "HOST_FAULT_MONOTONIC",
        f"{trial_id}: trigger host clock domain drift",
    )
    observed = trigger.get("hostObservedAtElapsedRealtimeNs")
    fetch_id = trigger.get("fetchId")
    signal_count = trigger.get("failureSignalsObservedAtTrigger")
    require(type(observed) is int and observed >= 0, f"{trial_id}: trigger timestamp missing")
    require(isinstance(fetch_id, str) and fetch_id, f"{trial_id}: trigger fetchId missing")
    require(
        type(signal_count) is int and signal_count == 1,
        f"{trial_id}: multiple owner failures were visible before disarm",
    )
    require(
        applied["elapsedRealtimeNs"] <= observed <= events[3]["elapsedRealtimeNs"],
        f"{trial_id}: fault was not removed after Android observed ATTEMPT_FAILED",
    )

    signal_log = (trial_dir / "android-fault-signal.log").read_text(encoding="utf-8")
    all_signal_lines = signal_log.splitlines()
    ready_marker = f"ready trial={trial_id}"
    ready_indexes = [
        index for index, line in enumerate(all_signal_lines)
        if ready_marker in line
    ]
    require(
        len(ready_indexes) == 1,
        f"{trial_id}: expected exactly one Android readiness signal",
    )

    failure_marker = f"trial={trial_id} fetchId="
    failure_pairs = [
        (index, line)
        for index, line in enumerate(all_signal_lines)
        if failure_marker in line
    ]
    require(failure_pairs, f"{trial_id}: Android failure signal log is empty")
    require(
        ready_indexes[0] < failure_pairs[0][0],
        f"{trial_id}: failure signal preceded Android readiness",
    )
    parsed_ids: list[str] = []
    for _, line in failure_pairs:
        match = re.search(rf"trial={re.escape(trial_id)} fetchId=(\S+)", line)
        require(match is not None, f"{trial_id}: malformed Android failure signal")
        parsed_ids.append(match.group(1))
    require(parsed_ids[0] == fetch_id, f"{trial_id}: trigger fetchId/log mismatch")
    return fetch_id, parsed_ids


def finalize_retry_visibility(
    row: dict[str, Any],
    proof: Mapping[str, Any],
    trial_origin: list[dict[str, Any]],
) -> dict[str, Any]:
    origin_ids = [origin_row.get("requestId") for origin_row in trial_origin]
    require(
        all(type(value) is int and value > 0 for value in origin_ids)
        and len(origin_ids) == len(set(origin_ids)),
        f"{row['trialId']}: malformed/reused origin request id",
    )
    require(
        set(proof["correlatedRequestIds"]).issubset(set(origin_ids)),
        f"{row['trialId']}: device correlation escaped serialized origin partition",
    )
    visibility, internal_retries = shared.derive_internal_retry_visibility(
        physical_owner_count=proof["physicalAttemptCount"],
        correlated_owner_count=len(proof["correlatedRequestIds"]),
        origin_request_count=len(trial_origin),
    )
    finalized = copy.deepcopy(row)
    finalized["recovery"]["originRequestCount"] = len(trial_origin)
    finalized["recovery"]["internalRetryVisibility"] = visibility
    finalized["recovery"]["internalRetryCount"] = internal_retries
    finalized["performanceSampleEligible"] = visibility == "OBSERVABLE"
    finalized["limitations"] = [
        item for item in finalized["limitations"]
        if item != "RAW_DEVICE_ROW_REQUIRES_HOST_RETRY_FINALIZATION"
    ]
    finalized["limitations"].append(
        "INTERNAL_HTTP_REPLAY_COUNT_DERIVED_FROM_SERIALIZED_ORIGIN_TRACE_PARTITION"
        if visibility == "OBSERVABLE"
        else "INTERNAL_HTTP_REPLAY_ATTRIBUTION_AMBIGUOUS_WITH_UNCORRELATED_OWNER"
    )
    return finalized


def verify(args: argparse.Namespace) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    plan = load_json(args.plan)
    scenario = load_json(args.scenario)
    validate_environment(
        load_json(args.environment),
        load_json(args.android_runtime),
    )
    inputs = load_inputs(args)
    validate_scenario(scenario)
    shared.validate_frozen_work(inputs["work"])
    validate_plan(plan, scenario=scenario, inputs=inputs)

    schedule = planned_trial_schedule(plan)
    require(len(schedule) == 4, "N6 requires two complete counterbalanced blocks")
    expected_names = {row["trialId"] + ".json" for row in schedule}
    actual_names = {path.name for path in args.raw_dir.glob("*.json")}
    require(actual_names == expected_names, "raw N6 trial set differs from frozen plan")

    origin = load_jsonl(args.origin_trace)
    require(origin, "origin trace is empty")
    first_trial_id = schedule[0]["trialId"]
    previous_end = load_count(args.trial_root / first_trial_id / "origin-before-count.txt")
    require(previous_end == 1 and previous_end <= len(origin), "expected one Media Lab readiness prelude")
    prelude = origin[:previous_end]
    require(
        len(prelude) == 1
        and prelude[0].get("plane") == "control"
        and prelude[0].get("method") == "GET"
        and prelude[0].get("path") == "/__lab/config"
        and prelude[0].get("status") == 200
        and prelude[0].get("outcome") == "SUCCESS",
        "unexpected Media Lab trace prelude",
    )

    rows: list[dict[str, Any]] = []
    proofs: list[dict[str, Any]] = []
    timing_rows: list[dict[str, Any]] = []
    process_instances: list[str] = []
    process_keys: list[tuple[int, int]] = []
    reset_effect_rows = 0

    for expected in schedule:
        trial_id = expected["trialId"]
        trial_dir = args.trial_root / trial_id
        trigger_fetch_id, signal_fetch_ids = validate_harness(
            trial_dir,
            plan=plan,
            trial_id=trial_id,
        )

        start = load_count(trial_dir / "origin-before-count.txt")
        end = load_count(trial_dir / "origin-after-count.txt")
        require(start == previous_end and start <= end <= len(origin), f"{trial_id}: origin partition drift")
        trial_origin = origin[start:end]
        require(len(trial_origin) >= 2, f"{trial_id}: reset did not produce a recovery request")
        for origin_row in trial_origin:
            shared.validate_origin_row(origin_row, trial_id=trial_id)
        previous_end = end

        raw = load_json(args.raw_dir / f"{trial_id}.json")
        row, proof, timing = shared.check_raw(
            profile="N6",
            raw=raw,
            expected=expected,
            plan=plan,
            device_state=inputs["deviceState"],
            recovery_policy=inputs["recoveryPolicy"],
            expected_phase="M2-G2-D-TRANSPORT_RESET",
        )

        raw_attempts = raw["proof"]["physicalAttempts"]
        raw_failures = raw["proof"]["recoveryFailures"]
        require(raw_attempts and raw_failures, f"{trial_id}: N6 emitted no failed owner")
        require(
            raw_attempts[0].get("terminal") == "ATTEMPT_FAILED"
            and raw_attempts[0].get("fetchId") == trigger_fetch_id,
            f"{trial_id}: disarm trigger is not the first failed physical owner",
        )
        require(
            raw_failures[0].get("fetchId") == trigger_fetch_id,
            f"{trial_id}: disarm trigger is not bound to first recovery failure",
        )
        failed_fetch_ids = {
            failure.get("fetchId")
            for failure in raw_failures
            if isinstance(failure, Mapping)
        }
        require(
            set(signal_fetch_ids).issubset(failed_fetch_ids),
            f"{trial_id}: Android failure signal escaped retained recovery failures",
        )

        require(
            proof["physicalAttemptCount"] == 2,
            f"{trial_id}: G2-D requires exactly one failed owner followed by one successful owner",
        )
        require(
            raw["proof"]["recoveryFailureCount"] == 1
            and len(raw["proof"]["recoveryFailures"]) == 1
            and len(raw["proof"]["recoveryBackoffs"]) == 1,
            f"{trial_id}: G2-D recovery lineage must contain exactly one failure/backoff",
        )
        require(
            raw["proof"]["recoveryJitterSamples"] == [367],
            f"{trial_id}: first frozen G2 recovery backoff drifted",
        )

        origin_by_id = {
            origin_row.get("requestId"): origin_row
            for origin_row in trial_origin
            if type(origin_row.get("requestId")) is int
        }
        successful_origin = origin_by_id.get(proof["successfulRequestId"])
        require(isinstance(successful_origin, Mapping), f"{trial_id}: successful owner origin missing")
        shared.validate_successful_origin_row(successful_origin, trial_id=trial_id)

        finalized = finalize_retry_visibility(row, proof, trial_origin)
        internal = finalized["recovery"]["internalRetryCount"]
        require(
            raw["proof"]["recoveryFailureCount"] == 1,
            f"{trial_id}: injected reset did not remain scoped to the first application owner",
        )
        require(
            internal is not None,
            f"{trial_id}: transport replay attribution remained opaque",
        )
        reset_effect_rows += 1

        rows.append(finalized)
        proofs.append(proof)
        timing_rows.append(timing)
        process_instances.append(proof["processInstanceId"])
        process_keys.append((proof["processPid"], proof["processStartClockTicks"]))

    require(previous_end == len(origin), "unassigned origin trace rows remain")
    require(len(set(process_instances)) == 4, "COLD N6 run reused instrumentation process UUID")
    require(len(set(process_keys)) == 4, "COLD N6 run reused OS process identity/starttime")

    trials = {
        "schemaVersion": 1,
        "runId": plan["runId"],
        "pairId": plan["pairId"],
        "deviceClass": inputs["deviceState"]["deviceClass"],
        "androidApi": inputs["deviceState"]["androidApi"],
        "clockDomain": "ANDROID_MONOTONIC",
        "orderingProtocol": plan["orderingProtocol"],
        "orderingSeed": plan["orderingSeed"],
        "trials": rows,
        "limitations": [
            "API36_EMULATOR_DIRECTIONAL_ONLY",
            "N6_SINGLE_RESET_CAUSALLY_DISARMED_AFTER_FIRST_ORIGIN_REACH",
            "NO_PRODUCTION_TRANSPORT_SELECTION",
        ],
    }
    validate_trials_against_plan(plan, trials, scenario=scenario, inputs=inputs)
    analysis = analyze_trials(trials)
    require(analysis["comparisonResult"]["correctnessEquivalent"] is True, "N6 correctness equivalence failed")
    require(analysis["comparisonResult"]["recoveryEquivalent"] is True, "N6 recovery equivalence failed")
    require(analysis["comparisonResult"]["routeBindingEquivalent"] is True, "N6 exact-route equivalence failed")
    require(analysis["pairedPerformanceBlockCount"] == 2, "N6 paired performance eligibility drift")
    require(set(analysis["backendEligibility"].values()) == {"ELIGIBLE"}, "N6 requires both backends eligible")

    timings = {
        "schemaVersion": 1,
        "runId": plan["runId"],
        "pairId": plan["pairId"],
        "scenarioFamily": "N6",
        "clockDomain": "ANDROID_MONOTONIC",
        "rows": timing_rows,
        "limitations": [
            "EMULATOR_DIRECTIONAL_TIMINGS_ONLY",
            "PHASES_ARE_RELATIVE_TO_RECOVERY_CHAIN_START",
            "NO_CROSS_CLOCK_DOMAIN_ARITHMETIC",
        ],
    }
    validate_instance(shared.load_json(PHASE_SCHEMA), timings)
    scan_evidence_privacy(timings)

    verification = {
        "schemaVersion": 1,
        "phase": "M2-G2-D-N6",
        "status": "PASS",
        "claimScope": "API36_EMULATOR_DIRECTIONAL_ONLY",
        "trialCount": len(rows),
        "resetEffectTrialCount": reset_effect_rows,
        "pairedBlockCount": analysis["pairedPerformanceBlockCount"],
        "correctnessEquivalent": analysis["comparisonResult"]["correctnessEquivalent"],
        "recoveryEquivalent": analysis["comparisonResult"]["recoveryEquivalent"],
        "routeBindingEquivalent": analysis["comparisonResult"]["routeBindingEquivalent"],
        "selectedBackend": None,
    }
    scan_evidence_privacy(trials)
    scan_evidence_privacy(verification)
    return trials, timings, verification


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--plan", required=True, type=pathlib.Path)
    parser.add_argument("--raw-dir", required=True, type=pathlib.Path)
    parser.add_argument("--trial-root", required=True, type=pathlib.Path)
    parser.add_argument("--origin-trace", required=True, type=pathlib.Path)
    parser.add_argument("--scenario", required=True, type=pathlib.Path)
    parser.add_argument("--environment", required=True, type=pathlib.Path)
    parser.add_argument("--android-runtime", required=True, type=pathlib.Path)
    parser.add_argument("--work", required=True, type=pathlib.Path)
    parser.add_argument("--device-state", required=True, type=pathlib.Path)
    parser.add_argument("--cache-state", required=True, type=pathlib.Path)
    parser.add_argument("--recovery-policy", required=True, type=pathlib.Path)
    parser.add_argument("--route-policy", required=True, type=pathlib.Path)
    parser.add_argument("--output-dir", required=True, type=pathlib.Path)
    args = parser.parse_args()

    trials, timings, verification = verify(args)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for name, value in {
        "transport-evaluation-trials-v1.json": trials,
        "transport-phase-timings-v1.json": timings,
        "verification.json": verification,
    }.items():
        (args.output_dir / name).write_text(
            json.dumps(value, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    print("M2-G2-D N6 paired API36 transport-reset evidence verified")


if __name__ == "__main__":
    main()
