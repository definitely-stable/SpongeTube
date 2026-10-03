#!/usr/bin/env python3
"""Verify M2-G2-E paired exact-route replacement evidence."""
from __future__ import annotations

import argparse
import copy
import importlib.util
import json
import pathlib
import sys
from typing import Any, Mapping

ROOT = pathlib.Path(__file__).resolve().parents[2]
MEASUREMENT = ROOT / "scripts" / "measurement"
sys.path.insert(0, str(MEASUREMENT))

from m2_contracts import scan_evidence_privacy  # noqa: E402
from m2_transport_evaluation_oracle import analyze_trials  # noqa: E402
from m2_transport_pair_plan import (  # noqa: E402
    planned_trial_schedule,
    validate_plan,
    validate_trials_against_plan,
)

NETWORK_VERIFIER_PATH = ROOT / "scripts" / "ci" / "verify-m2-g2-network.py"
_spec = importlib.util.spec_from_file_location("m2_g2_shared_verifier", NETWORK_VERIFIER_PATH)
if _spec is None or _spec.loader is None:
    raise RuntimeError("cannot load shared G2 verifier")
shared = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(shared)

RESOURCE_LENGTH = shared.RESOURCE_LENGTH
RESOURCE_SHA256 = shared.RESOURCE_SHA256
shared.SPECS["N6R"] = {
    "family": "N6",
    "variant": "DEFAULT_ROUTE_LOSS_RESTORE",
    "performanceEligible": True,
}


class RouteReplacementEvidenceError(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RouteReplacementEvidenceError(message)


def load_json(path: pathlib.Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    require(isinstance(value, dict), f"{path}: expected JSON object")
    return value


def load_jsonl(path: pathlib.Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            value = json.loads(line)
            require(isinstance(value, dict), f"{path}: expected object rows")
            rows.append(value)
    return rows


def load_count(path: pathlib.Path) -> int:
    raw = path.read_text(encoding="utf-8").strip()
    require(raw.isdigit(), f"{path}: invalid non-negative count")
    return int(raw)


def load_inputs(args: argparse.Namespace) -> dict[str, dict[str, Any]]:
    return {
        "work": load_json(args.work),
        "deviceState": load_json(args.device_state),
        "cacheState": load_json(args.cache_state),
        "recoveryPolicy": load_json(args.recovery_policy),
        "routePolicy": load_json(args.route_policy),
    }


def validate_scenario(scenario: Mapping[str, Any]) -> None:
    require(scenario.get("scenarioFamily") == "N6", "G2-E requires N6")
    require(
        scenario.get("variant") == "DEFAULT_ROUTE_LOSS_RESTORE",
        "G2-E scenario variant drift",
    )
    require(scenario.get("primaryPlane") == "ROUTE", "G2-E primary plane drift")
    require(scenario.get("randomSeed") is None, "G2-E must remain deterministic")
    faults = scenario.get("routeFaults")
    require(isinstance(faults, list) and len(faults) == 1, "G2-E requires one route fault")
    fault = faults[0]
    require(
        isinstance(fault, Mapping)
        and fault.get("kind") == "DEFAULT_ROUTE_LOSS_RESTORE"
        and fault.get("stochastic") is False
        and fault.get("parameters") == {
            "replacementDefault": "NON_VPN",
            "resume": "NON_VPN",
        },
        "G2-E route fault parameters drift",
    )
    require(
        scenario.get("requiresActualDefaultNetwork") is True,
        "G2-E requires actual Android default network",
    )


def validate_route_evidence(
    proof: Mapping[str, Any],
    *,
    trial_id: str,
) -> None:
    before = proof.get("routeEpochBefore")
    after = proof.get("routeEpochAfter")
    require(
        type(before) is int and type(after) is int and 0 < before < after,
        f"{trial_id}: route epoch did not advance",
    )
    require(proof.get("routeUnavailableObserved") is True, f"{trial_id}: route loss not retained")
    require(proof.get("chargesBeforeRestore") == 1, f"{trial_id}: second charge occurred while route unavailable")
    require(proof.get("attemptsBeforeRestore") == 1, f"{trial_id}: second owner started while route unavailable")
    require(proof.get("gateCalls") == 2, f"{trial_id}: route gate invocation count drift")

    route = proof.get("routeEvidence")
    require(isinstance(route, Mapping), f"{trial_id}: route evidence missing")
    require(route.get("schemaVersion") == 1, f"{trial_id}: route evidence schema drift")
    require(route.get("androidApi") == 36, f"{trial_id}: route evidence API drift")
    require(route.get("clockDomain") == "ANDROID_MONOTONIC", f"{trial_id}: route clock drift")
    events = route.get("events")
    evaluations = route.get("policyEvaluations")
    require(isinstance(events, list) and events, f"{trial_id}: route events empty")
    require(isinstance(evaluations, list) and evaluations, f"{trial_id}: route policy evidence empty")
    signals = [row.get("signal") for row in events if isinstance(row, Mapping)]
    require("LOST" in signals, f"{trial_id}: actual default route LOST not observed")
    require(signals[-1] == "MONITOR_STOPPED", f"{trial_id}: route monitor stop not terminal")
    decisions = [
        (row.get("decision"), row.get("reason"), row.get("routeEpoch"))
        for row in evaluations if isinstance(row, Mapping)
    ]
    require(
        ("PAUSE", "NO_USABLE_DEFAULT", None) in decisions,
        f"{trial_id}: route policy did not pause while default route absent",
    )
    allowed = [
        row.get("routeEpoch")
        for row in evaluations
        if isinstance(row, Mapping) and row.get("decision") == "ALLOW"
    ]
    require(before in allowed and after in allowed, f"{trial_id}: old/replacement route permits missing")


def finalize_row(
    row: dict[str, Any],
    *,
    trial_origin: list[dict[str, Any]],
    raw_proof: Mapping[str, Any],
    summary_proof: Mapping[str, Any],
) -> dict[str, Any]:
    trial_id = row["trialId"]
    require(len(trial_origin) == 1, f"{trial_id}: expected exactly one post-restore origin GET")
    origin = trial_origin[0]
    shared.validate_origin_row(origin, trial_id=trial_id)
    shared.validate_successful_origin_row(origin, trial_id=trial_id)

    attempts = raw_proof["physicalAttempts"]
    require(len(attempts) == 2, f"{trial_id}: expected two application owners")
    require(
        attempts[0].get("terminal") == "ATTEMPT_FAILED"
        and attempts[0].get("transportCorrelationId") is None,
        f"{trial_id}: failed old-route owner unexpectedly reached origin",
    )
    require(
        attempts[1].get("terminal") == "ATTEMPT_COMPLETED"
        and attempts[1].get("transportCorrelationId") is not None,
        f"{trial_id}: replacement-route owner lacks successful correlation",
    )
    successful_id = summary_proof["successfulRequestId"]
    require(origin.get("requestId") == successful_id, f"{trial_id}: origin row not owned by successful replacement request")
    require(
        summary_proof["correlatedRequestIds"] == [successful_id],
        f"{trial_id}: origin correlation escaped route-replacement boundary",
    )

    result = copy.deepcopy(row)
    result["recovery"]["originRequestCount"] = 1
    result["recovery"]["internalRetryVisibility"] = "OBSERVABLE"
    result["recovery"]["internalRetryCount"] = 0
    result["performanceSampleEligible"] = True
    result["limitations"] = [
        item for item in result["limitations"]
        if item != "RAW_DEVICE_ROW_REQUIRES_HOST_RETRY_FINALIZATION"
    ]
    result["limitations"].append(
        "FAILED_OLD_ROUTE_OWNER_PROVEN_ORIGIN_SILENT_BEFORE_REPLACEMENT"
    )
    return result


def verify(args: argparse.Namespace) -> tuple[dict[str, Any], dict[str, Any]]:
    plan = load_json(args.plan)
    scenario = load_json(args.scenario)
    inputs = load_inputs(args)
    validate_scenario(scenario)
    shared.validate_frozen_work(inputs["work"])
    validate_plan(plan, scenario=scenario, inputs=inputs)

    schedule = planned_trial_schedule(plan)
    require(len(schedule) == 4, "G2-E requires two complete counterbalanced blocks")
    expected_names = {row["trialId"] + ".json" for row in schedule}
    actual_names = {path.name for path in args.raw_dir.glob("*.json")}
    require(actual_names == expected_names, "G2-E raw trial set differs from frozen plan")

    origin = load_jsonl(args.origin_trace)
    require(origin, "G2-E origin trace is empty")
    first_trial = schedule[0]["trialId"]
    previous_end = load_count(args.trial_root / first_trial / "origin-before-count.txt")
    require(previous_end == 1, "G2-E expected one Media Lab readiness prelude")
    prelude = origin[:previous_end]
    require(
        len(prelude) == 1
        and prelude[0].get("plane") == "control"
        and prelude[0].get("method") == "GET"
        and prelude[0].get("path") == "/__lab/config"
        and prelude[0].get("status") == 200,
        "G2-E unexpected Media Lab prelude",
    )

    rows: list[dict[str, Any]] = []
    proofs: list[dict[str, Any]] = []
    process_instances: list[str] = []
    process_keys: list[tuple[int, int]] = []

    for expected in schedule:
        trial_id = expected["trialId"]
        start = load_count(args.trial_root / trial_id / "origin-before-count.txt")
        end = load_count(args.trial_root / trial_id / "origin-after-count.txt")
        require(start == previous_end and start < end <= len(origin), f"{trial_id}: origin partition drift")
        trial_origin = origin[start:end]
        previous_end = end

        raw = load_json(args.raw_dir / f"{trial_id}.json")
        row, proof, _timing = shared.check_raw(
            profile="N6R",
            raw=raw,
            expected=expected,
            plan=plan,
            device_state=inputs["deviceState"],
            recovery_policy=inputs["recoveryPolicy"],
            expected_phase="M2-G2-E-ROUTE_REPLACEMENT",
            allow_route_replacement=True,
        )
        validate_route_evidence(raw["proof"], trial_id=trial_id)
        require(proof["physicalAttemptCount"] == 2, f"{trial_id}: owner count drift")
        require(
            raw["proof"]["recoveryFailureCount"] == 1
            and len(raw["proof"]["recoveryFailures"]) == 1
            and len(raw["proof"]["recoveryBackoffs"]) == 1,
            f"{trial_id}: route recovery lineage must have one failure/backoff",
        )
        require(
            raw["proof"]["recoveryJitterSamples"] == [367],
            f"{trial_id}: frozen first backoff drift",
        )

        rows.append(
            finalize_row(
                row,
                trial_origin=trial_origin,
                raw_proof=raw["proof"],
                summary_proof=proof,
            )
        )
        proofs.append(proof)
        process_instances.append(proof["processInstanceId"])
        process_keys.append((proof["processPid"], proof["processStartClockTicks"]))

    require(previous_end == len(origin), "G2-E unassigned origin trace rows remain")
    require(len(set(process_instances)) == 4, "G2-E COLD run reused instrumentation process UUID")
    require(len(set(process_keys)) == 4, "G2-E COLD run reused OS process identity/starttime")

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
            "ROUTE_REPLACEMENT_TIMINGS_INCLUDE_REAL_CONNECTIVITY_TRANSITION",
            "NO_PHYSICAL_DEVICE_PERFORMANCE_CLAIM",
            "NO_PRODUCTION_TRANSPORT_SELECTION",
        ],
    }
    validate_trials_against_plan(plan, trials, scenario=scenario, inputs=inputs)
    analysis = analyze_trials(trials)
    require(analysis["comparisonResult"]["correctnessEquivalent"] is True, "G2-E correctness equivalence failed")
    require(analysis["comparisonResult"]["recoveryEquivalent"] is True, "G2-E recovery equivalence failed")
    require(analysis["comparisonResult"]["routeBindingEquivalent"] is True, "G2-E exact-route equivalence failed")
    require(analysis["pairedPerformanceBlockCount"] == 2, "G2-E paired sample completeness drift")
    require(set(analysis["backendEligibility"].values()) == {"ELIGIBLE"}, "G2-E requires both backends eligible")

    verification = {
        "schemaVersion": 1,
        "phase": "M2-G2-E-ROUTE_REPLACEMENT",
        "status": "PASS",
        "claimScope": "API36_EMULATOR_DIRECTIONAL_ONLY",
        "trialCount": len(rows),
        "pairedBlockCount": analysis["pairedPerformanceBlockCount"],
        "correctnessEquivalent": True,
        "recoveryEquivalent": True,
        "routeBindingEquivalent": True,
        "oldRouteOriginSilent": True,
        "selectedBackend": None,
    }
    scan_evidence_privacy(trials)
    scan_evidence_privacy(verification)
    return trials, verification


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
    parser.add_argument("--output-dir", required=True, type=pathlib.Path)
    args = parser.parse_args()

    trials, verification = verify(args)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for name, value in {
        "transport-evaluation-trials-v1.json": trials,
        "verification.json": verification,
    }.items():
        (args.output_dir / name).write_text(
            json.dumps(value, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    print("M2-G2-E paired route-replacement evidence verified")


if __name__ == "__main__":
    main()
