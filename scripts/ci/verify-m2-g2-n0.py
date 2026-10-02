#!/usr/bin/env python3
"""Verify G2-B N0 API36 paired raw evidence and emit frozen G0 trials v1."""
from __future__ import annotations

import argparse
import importlib.util
import json
import pathlib
import sys
from typing import Any

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
MEASUREMENT = REPO_ROOT / "scripts" / "measurement"
sys.path.insert(0, str(MEASUREMENT))

from m2_transport_evaluation_oracle import analyze_trials  # noqa: E402
from m2_transport_pair_plan import (  # noqa: E402
    planned_trial_schedule,
    validate_plan,
    validate_trials_against_plan,
)

RESOURCE_PATH = "/fixtures/F1/segment-1-00001.m4s"
RESOURCE_LENGTH = 81_811
RESOURCE_SHA256 = "08ac93538dcb3f5eece5996b0abab1e4e7677afbc7b21cc3292a63c776ef4943"
RECOVERY_JITTER_PROTOCOL = "SHA256_COUNTER_REJECTION_V1"
RECOVERY_JITTER_SEED = 424_243


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def load_json(path: pathlib.Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    require(isinstance(value, dict), f"{path}: expected JSON object")
    return value


def load_jsonl(path: pathlib.Path) -> list[dict[str, Any]]:
    return [
        json.loads(line)
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]


def load_inputs(args: argparse.Namespace) -> dict[str, dict[str, Any]]:
    return {
        "work": load_json(args.work),
        "deviceState": load_json(args.device_state),
        "cacheState": load_json(args.cache_state),
        "recoveryPolicy": load_json(args.recovery_policy),
        "routePolicy": load_json(args.route_policy),
    }


def check_raw(
    *,
    raw: dict[str, Any],
    expected: dict[str, Any],
    plan: dict[str, Any],
) -> tuple[dict[str, Any], dict[str, Any]]:
    require(raw.get("schemaVersion") == 1, "wrong G2-B raw schema")
    require(raw.get("phase") == "M2-G2-B-N0", "wrong G2-B phase")
    require(raw.get("runId") == plan["runId"], "raw runId drift")
    require(raw.get("pairId") == plan["pairId"], "raw pairId drift")
    row = raw.get("trial")
    proof = raw.get("proof")
    require(isinstance(row, dict) and isinstance(proof, dict), "raw trial/proof missing")

    for field in ("trialId", "orderingBlock", "positionInBlock", "backendId"):
        require(row.get(field) == expected[field], f"{expected['trialId']}: {field} drift")
    require(row.get("comparison") == plan["comparison"], f"{expected['trialId']}: comparison drift")
    require(row.get("eligibility") == "ELIGIBLE", f"{expected['trialId']}: backend unavailable")
    require(row.get("result") == "SUCCESS", f"{expected['trialId']}: N0 trial failed")
    require(
        isinstance(row.get("backendVersion"), str) and row["backendVersion"],
        f"{expected['trialId']}: backend version missing",
    )
    require(
        isinstance(row.get("implementationId"), str) and row["implementationId"],
        f"{expected['trialId']}: implementation id missing",
    )
    require(
        row.get("negotiatedProtocol") in ("HTTP_1_1", "HTTP_2", "HTTP_3", "UNKNOWN"),
        f"{expected['trialId']}: unnormalized protocol",
    )

    route = row.get("route")
    require(
        isinstance(route, dict)
        and route.get("exactNetworkBound") is True
        and type(route.get("permitRouteEpoch")) is int
        and route["permitRouteEpoch"] > 0,
        f"{expected['trialId']}: exact route proof missing",
    )
    correctness = row.get("requestCorrectness")
    require(
        isinstance(correctness, dict)
        and set(correctness.values()) == {"PASS"},
        f"{expected['trialId']}: request correctness not PASS",
    )
    recovery = row.get("recovery")
    require(
        recovery == {
            "recoveryChainCount": 1,
            "ownerCount": 1,
            "originRequestCount": 1,
            "internalRetryVisibility": "OBSERVABLE",
            "internalRetryCount": 0,
        },
        f"{expected['trialId']}: N0 recovery lineage drift",
    )
    metrics = row.get("metrics")
    require(isinstance(metrics, dict), f"{expected['trialId']}: metrics missing")
    for name in ("firstByteUs", "completionUs", "cpuTimeUs", "bytesRequested", "bytesReceived", "bytesPublished"):
        require(type(metrics.get(name)) is int and metrics[name] >= 0, f"{expected['trialId']}: {name} missing")
    require(metrics["firstByteUs"] <= metrics["completionUs"], f"{expected['trialId']}: timing order invalid")
    require(metrics["bytesRequested"] == RESOURCE_LENGTH, f"{expected['trialId']}: requested bytes drift")
    require(metrics["bytesReceived"] == RESOURCE_LENGTH, f"{expected['trialId']}: received bytes drift")
    require(metrics["bytesPublished"] == RESOURCE_LENGTH, f"{expected['trialId']}: published bytes drift")
    require(metrics.get("cancellationLatencyUs") is None, f"{expected['trialId']}: N0 cancellation metric must be null")
    rss = metrics.get("maxRssBytes")
    require(rss is None or (type(rss) is int and rss > 0), f"{expected['trialId']}: max RSS malformed")
    require(row.get("performanceSampleEligible") is True, f"{expected['trialId']}: eligible N0 sample suppressed")

    request_id = proof.get("originRequestId")
    require(type(request_id) is int and request_id > 0, f"{expected['trialId']}: origin correlation missing")
    require(proof.get("committedSha256") == RESOURCE_SHA256, f"{expected['trialId']}: committed hash mismatch")
    require(proof.get("committedBytes") == RESOURCE_LENGTH, f"{expected['trialId']}: committed length mismatch")
    require(proof.get("extentStoreInitiallyEmpty") is True, f"{expected['trialId']}: cache was not COLD")
    require(proof.get("transportSessionFresh") is True, f"{expected['trialId']}: session reset not asserted")
    require(
        proof.get("routeEpochBefore") == route["permitRouteEpoch"] == proof.get("routeEpochAfter"),
        f"{expected['trialId']}: route epoch changed during trial",
    )
    require(proof.get("bindingRevision") == "binding-1", f"{expected['trialId']}: delivery revision drift")
    require(proof.get("bindingTargetResolved") is True, f"{expected['trialId']}: binding target unresolved")
    require(proof.get("attemptStartedCount") == 1, f"{expected['trialId']}: physical start count mismatch")
    require(proof.get("attemptCompletedCount") == 1, f"{expected['trialId']}: completion count mismatch")
    require(type(proof.get("attemptProgressCount")) is int and proof["attemptProgressCount"] >= 1,
            f"{expected['trialId']}: no body progress observed")
    require(proof.get("attemptCorrelationCount") == 1, f"{expected['trialId']}: correlation count mismatch")
    require(proof.get("remoteAttemptChargeCount") == 1, f"{expected['trialId']}: recovery charge mismatch")
    require(proof.get("recoveryJitterProtocol") == RECOVERY_JITTER_PROTOCOL,
            f"{expected['trialId']}: recovery jitter protocol drift")
    require(proof.get("recoveryJitterSeed") == RECOVERY_JITTER_SEED,
            f"{expected['trialId']}: recovery jitter seed drift")
    require(proof.get("recoveryJitterSampleCount") == 0 and proof.get("recoveryJitterSamples") == [],
            f"{expected['trialId']}: N0 unexpectedly consumed recovery jitter")
    process_id = proof.get("processInstanceId")
    require(isinstance(process_id, str) and process_id, f"{expected['trialId']}: process reset proof missing")
    return row, {"originRequestId": request_id, "processInstanceId": process_id}


def verify(
    *,
    plan: dict[str, Any],
    raw_dir: pathlib.Path,
    origin: list[dict[str, Any]],
    scenario: dict[str, Any],
    inputs: dict[str, dict[str, Any]],
) -> tuple[dict[str, Any], dict[str, Any]]:
    validate_plan(plan, scenario=scenario, inputs=inputs)
    schedule = planned_trial_schedule(plan)
    require(len(schedule) == 4, "G2-B N0 requires exactly two complete paired blocks")

    expected_names = {row["trialId"] + ".json" for row in schedule}
    actual_names = {path.name for path in raw_dir.glob("*.json")}
    require(actual_names == expected_names, "raw trial set differs from frozen plan")

    rows: list[dict[str, Any]] = []
    proofs: list[dict[str, Any]] = []
    for expected in schedule:
        raw = load_json(raw_dir / (expected["trialId"] + ".json"))
        row, proof = check_raw(raw=raw, expected=expected, plan=plan)
        rows.append(row)
        proofs.append(proof)

    process_ids = [item["processInstanceId"] for item in proofs]
    require(len(set(process_ids)) == len(process_ids),
            "COLD v1 requires a fresh instrumentation process for every trial")
    request_ids = [item["originRequestId"] for item in proofs]
    require(len(set(request_ids)) == len(request_ids), "origin correlation id reused between trials")
    require(request_ids == sorted(request_ids), "physical origin order differs from frozen trial order")

    data = [
        row for row in origin
        if row.get("plane") == "data"
        and row.get("method") == "GET"
        and row.get("path") == RESOURCE_PATH
    ]
    by_id = {row.get("requestId"): row for row in data}
    require(len(data) == 4 and len(by_id) == 4, "N0 origin must receive exactly four measured GETs")
    require(set(by_id) == set(request_ids), "raw trials do not bijectively map to origin GETs")
    for expected, proof in zip(schedule, proofs):
        row = by_id[proof["originRequestId"]]
        require(row.get("profileId") == "N0", f"{expected['trialId']}: wrong Media Lab profile")
        require(row.get("scenarioHash") == plan["scenario"]["hash"],
                f"{expected['trialId']}: origin scenario hash drift")
        require(row.get("rangeHeader") == "bytes=0-81810", f"{expected['trialId']}: Range drift")
        require(row.get("resolvedRangeStart") == 0, f"{expected['trialId']}: range start drift")
        require(row.get("resolvedRangeEndExclusive") == RESOURCE_LENGTH,
                f"{expected['trialId']}: range end drift")
        require(row.get("status") == 206, f"{expected['trialId']}: origin status drift")
        require(row.get("plannedResponseBytes") == RESOURCE_LENGTH,
                f"{expected['trialId']}: planned origin body drift")
        require(row.get("bodyBytesWritten") == RESOURCE_LENGTH,
                f"{expected['trialId']}: incomplete origin body")
        require(row.get("outcome") == "SUCCESS", f"{expected['trialId']}: origin outcome not success")

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
            "N0_ONLY_G2_B",
            "NO_PRODUCTION_TRANSPORT_SELECTION",
        ],
    }
    validate_trials_against_plan(plan, trials, scenario=scenario, inputs=inputs)
    analysis = analyze_trials(trials)
    require(analysis["comparisonResult"]["correctnessEquivalent"] is True,
            "N0 correctness equivalence failed")
    require(analysis["comparisonResult"]["recoveryEquivalent"] is True,
            "N0 recovery equivalence failed")
    require(analysis["comparisonResult"]["routeBindingEquivalent"] is True,
            "N0 route equivalence failed")
    require(analysis["pairedPerformanceBlockCount"] == 2,
            "N0 did not retain two complete paired metric blocks")
    require(set(analysis["backendEligibility"].values()) == {"ELIGIBLE"},
            "both G2-B backends must be eligible on API36")

    verification = {
        "schemaVersion": 1,
        "phase": "M2-G2-B-N0",
        "status": "PASS",
        "claimScope": "API36_EMULATOR_DIRECTIONAL_ONLY",
        "trialCount": len(rows),
        "pairedBlockCount": analysis["pairedPerformanceBlockCount"],
        "correctnessEquivalent": analysis["comparisonResult"]["correctnessEquivalent"],
        "recoveryEquivalent": analysis["comparisonResult"]["recoveryEquivalent"],
        "routeBindingEquivalent": analysis["comparisonResult"]["routeBindingEquivalent"],
        "selectedBackend": None,
    }
    return trials, verification


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--plan", required=True, type=pathlib.Path)
    parser.add_argument("--raw-dir", required=True, type=pathlib.Path)
    parser.add_argument("--origin-trace", required=True, type=pathlib.Path)
    parser.add_argument("--scenario", required=True, type=pathlib.Path)
    parser.add_argument("--work", required=True, type=pathlib.Path)
    parser.add_argument("--device-state", required=True, type=pathlib.Path)
    parser.add_argument("--cache-state", required=True, type=pathlib.Path)
    parser.add_argument("--recovery-policy", required=True, type=pathlib.Path)
    parser.add_argument("--route-policy", required=True, type=pathlib.Path)
    parser.add_argument("--output-dir", required=True, type=pathlib.Path)
    args = parser.parse_args()

    raw_text = "\n".join(
        path.read_text(encoding="utf-8")
        for path in sorted(args.raw_dir.glob("*.json"))
    )
    for sensitive in ("http://", "https://", "Authorization", "Cookie", "192.0.2."):
        if sensitive in raw_text:
            raise ValueError(f"portable G2-B evidence contains sensitive locator/header: {sensitive}")

    plan = load_json(args.plan)
    scenario = load_json(args.scenario)
    inputs = load_inputs(args)
    trials, verification = verify(
        plan=plan,
        raw_dir=args.raw_dir,
        origin=load_jsonl(args.origin_trace),
        scenario=scenario,
        inputs=inputs,
    )
    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / "transport-evaluation-trials-v1.json").write_text(
        json.dumps(trials, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    (args.output_dir / "verification.json").write_text(
        json.dumps(verification, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print("M2-G2-B N0 paired API36 evidence verified; emulator directional evidence only")


if __name__ == "__main__":
    main()
