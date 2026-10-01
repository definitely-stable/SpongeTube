#!/usr/bin/env python3
"""Independent API36 G1 control/candidate correctness-to-origin cross-check.

G1 is NOT a counterbalanced G2 performance experiment: this proof never
scores timings, negotiated HTTP protocols or production transport choice.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

EXPECTED = (
    "HTTP_URL_CONNECTION_ROUTE_BOUND",
    "PLATFORM_HTTP_ENGINE",
)
SHA = "08ac93538dcb3f5eece5996b0abab1e4e7677afbc7b21cc3292a63c776ef4943"
LENGTH = 81811


def check(case: dict, origin: list[dict], faults: list[dict]) -> None:
    def require(ok: bool, message: str) -> None:
        if not ok:
            raise ValueError(message)

    require(case.get("schemaVersion") == 1 and case.get("phase") == "M2-G1", "wrong proof schema")
    require(case.get("api") == 36 and case.get("deviceClass") == "ANDROID_EMULATOR",
            "G1 must be API36 emulator correctness proof")
    require(case.get("mediaPath") == "ANDROID_DEFAULT_NETWORK", "media route is not real Android default")
    trials = case.get("trials")
    require(isinstance(trials, list) and len(trials) == 2, "expected two G1 trials")
    require(tuple(row.get("backend") for row in trials) == EXPECTED, "backend order/identity mismatch")
    epochs = {row.get("routeEpoch") for row in trials}
    require(len(epochs) == 1 and all(isinstance(x, int) and x > 0 for x in epochs),
            "route epoch changed during correctness comparison")
    for row in trials:
        require(isinstance(row.get("backendVersion"), str) and row["backendVersion"],
                "version evidence missing")
        require(isinstance(row.get("implementationId"), str) and row["implementationId"],
                "implementation identity evidence missing")
        require(row.get("negotiatedProtocol") in ("HTTP_1_1", "HTTP_2", "HTTP_3", "UNKNOWN"),
                "protocol must be descriptive normalized evidence")
        require(row.get("eligibility") == "ELIGIBLE" and row.get("result") == "SUCCESS",
                "backend not independently proven eligible and successful")
        require(row.get("attempts") == 1 and row.get("committedBytes") == LENGTH,
                "physical owner or committed extent count mismatch")
        require(row.get("sha256") == SHA, "fixture content identity mismatch")
        require(row.get("deliveryBindingRevision") == "binding-1"
                and row.get("deliveryBindingTargetResolved") is True,
                "both backends must preserve the same delivery-binding execution path")
        require(type(row.get("originRequestId")) is int and row["originRequestId"] > 0,
                "trial lacks a real physical origin correlation ID")

    require(case.get("unboundCharges") == 0, "unbound candidate charged a physical request")
    require(case.get("cancelledRequestCharges") == 1, "cancel case did not start exactly once")
    require(case.get("cancelledRequestAcknowledged") is True,
            "cancellation did not release platform terminal callback")
    require(case.get("cancelTerminal") == "CANCELED",
            "onFailed/onSucceeded must never count as cancellation proof")
    cancelled_id = case.get("cancelledOriginRequestId")
    request_ids = [row["originRequestId"] for row in trials] + [cancelled_id]
    require(type(cancelled_id) is int and cancelled_id > 0
            and len(set(request_ids)) == 3,
            "missing or duplicate physical origin request correlation")
    require(case.get("actualDefaultRouteLossObserved") is True,
            "Android did not observe real default network loss")
    require(case.get("staleRouteBoundRequestRejected") is True,
            "saved exact binding did not fail closed after route loss")
    require(case.get("staleRouteCorrelationAbsent") is True
            and case.get("staleRoutePublishedBytes") == 0,
            "old binding reached origin or emitted bytes")
    require(type(case.get("staleRouteCharges")) is int
            and case["staleRouteCharges"] in (0, 1),
            "old route attempt has invalid owner accounting")
    original_epoch = trials[0]["routeEpoch"]
    require(type(case.get("restoredRouteEpoch")) is int
            and case["restoredRouteEpoch"] > original_epoch,
            "device route did not recover to a new epoch")
    data = [x for x in origin if x.get("plane") == "data" and x.get("method") == "GET"]
    by_id = {row.get("requestId"): row for row in data}
    require(len(data) == 3 and len(by_id) == 3 and set(by_id) == set(request_ids),
            "each device owner must correlate with exactly one independent origin GET")
    # The G1 smoke test is sequential, not the G2 counterbalanced experiment.
    require(request_ids == sorted(request_ids),
            "G1 runtime trial sequence does not match physical request sequence")
    for trial in trials:
        row = by_id[trial["originRequestId"]]
        require(row.get("path") == "/fixtures/F1/segment-1-00001.m4s",
                "unexpected physical target")
        require(row.get("rangeHeader") == "bytes=0-81810" and row.get("status") == 206,
                "physical Range or response mismatch")
        require(row.get("bodyBytesWritten") == LENGTH, "origin response body incomplete")
    cancelled = by_id[cancelled_id]
    require(cancelled.get("path") == "/fixtures/F1/segment-1-00001.m4s"
            and cancelled.get("rangeHeader") == "bytes=0-81810"
            and cancelled.get("status") == 206, "cancel case did not reach the permitted origin")

    faults_evidence = case.get("responseFaults")
    require(isinstance(faults_evidence, list) and len(faults_evidence) == 4,
            "expected four candidate response-contract cases")
    expected_faults = (
        ("redirect", "HTTP_302", "CANCELED", 302, None, 0),
        ("wrong-content-range", "CONTENT_RANGE_MISMATCH", "CANCELED", 206, None, 0),
        ("overlong-body", "RESPONSE_BYTES_OUTSIDE_RANGE", "CANCELED", 206, None, None),
        ("binding", "SUCCESS", "SUCCEEDED", 206, "binding-1", LENGTH),
    )
    fault_by_id = {row.get("requestId"): row for row in faults}
    require(len(faults) == 4 and len(fault_by_id) == 4,
            "fault origin must receive exactly four physical GETs")
    observed_ids = []
    for evidence_row, expected in zip(faults_evidence, expected_faults):
        case_id, result, terminal, status, binding_revision, exact_emitted = expected
        require(evidence_row.get("case") == case_id, "fault case ordering/identity mismatch")
        require(evidence_row.get("result") == result, f"{case_id} client result mismatch")
        require(evidence_row.get("terminal") == terminal, f"{case_id} terminal mismatch")
        require(evidence_row.get("charges") == 1, f"{case_id} owner charge mismatch")
        if exact_emitted is None:
            emitted = evidence_row.get("emittedBytes")
            require(type(emitted) is int and 0 <= emitted <= LENGTH,
                    f"{case_id} emitted bytes outside immutable extent")
        else:
            require(evidence_row.get("emittedBytes") == exact_emitted,
                    f"{case_id} emitted byte count mismatch")
        require(evidence_row.get("deliveryBindingRevision") == binding_revision,
                f"{case_id} device binding evidence mismatch")
        request_id = evidence_row.get("originRequestId")
        require(type(request_id) is int and request_id > 0 and request_id not in observed_ids,
                f"{case_id} missing/duplicate fault-origin correlation")
        observed_ids.append(request_id)
        row = fault_by_id.get(request_id)
        require(row is not None, f"{case_id} has no independent fault-origin row")
        require(row.get("method") == "GET" and row.get("path") == "/" + case_id,
                f"{case_id} physical target mismatch")
        require(row.get("rangeHeader") == "bytes=0-81810"
                and row.get("acceptEncoding") == "identity"
                and row.get("attemptHeader") == "1",
                f"{case_id} request contract mismatch at fault origin")
        require(row.get("fetchKey") ==
                "fixture:F1/audio-main/f1-audio-1/m2g1:fault:" + case_id,
                f"{case_id} immutable fetch identity mismatch at fault origin")
        require(row.get("status") == status
                and row.get("responseVariant") == case_id,
                f"{case_id} response variant/status mismatch")
        require(row.get("bindingRevision") == binding_revision,
                f"{case_id} wire delivery binding mismatch")

    redirect_row = fault_by_id[observed_ids[0]]
    wrong_row = fault_by_id[observed_ids[1]]
    overlong_row = fault_by_id[observed_ids[2]]
    binding_row = fault_by_id[observed_ids[3]]
    require(redirect_row.get("plannedResponseBytes") == 0
            and wrong_row.get("plannedResponseBytes") == 0,
            "header-only malformed cases unexpectedly carried a body")
    require(overlong_row.get("plannedResponseBytes") == LENGTH + 1,
            "overlong origin did not actually plan one extra byte")
    require(binding_row.get("plannedResponseBytes") == LENGTH
            and binding_row.get("bodyBytesWritten") == LENGTH,
            "valid bound candidate response was incomplete")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("evidence_dir", type=Path)
    parser.add_argument("origin_trace", type=Path)
    parser.add_argument("fault_trace", type=Path)
    args = parser.parse_args()
    case_path = args.evidence_dir / "case.json"
    raw = case_path.read_text(encoding="utf-8")
    for sensitive in ("http://", "https://", "Authorization", "Cookie", "192.0.2."):
        if sensitive in raw:
            raise ValueError(f"portable evidence contains private locator/header: {sensitive}")
    case = json.loads(raw)
    origin = [
        json.loads(line)
        for line in args.origin_trace.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    faults = [
        json.loads(line)
        for line in args.fault_trace.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    check(case, origin, faults)
    (args.evidence_dir / "verification.json").write_text(
        json.dumps({"schemaVersion": 1, "phase": "M2-G1", "status": "PASS",
                    "claimScope": "API36_EMULATOR_CORRECTNESS_ONLY"}, indent=2) + "\n",
        encoding="utf-8",
    )
    print("M2-G1 API36 exact-route range proof verified; no performance claim")


if __name__ == "__main__":
    main()
