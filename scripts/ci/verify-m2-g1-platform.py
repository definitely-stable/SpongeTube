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


def check(case: dict, origin: list[dict]) -> None:
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


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("evidence_dir", type=Path)
    parser.add_argument("origin_trace", type=Path)
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
    check(case, origin)
    (args.evidence_dir / "verification.json").write_text(
        json.dumps({"schemaVersion": 1, "phase": "M2-G1", "status": "PASS",
                    "claimScope": "API36_EMULATOR_CORRECTNESS_ONLY"}, indent=2) + "\n",
        encoding="utf-8",
    )
    print("M2-G1 API36 exact-route range proof verified; no performance claim")


if __name__ == "__main__":
    main()
