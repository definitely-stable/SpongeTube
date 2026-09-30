"""Falsification tests for the independent G1 origin/cancellation verifier."""
from __future__ import annotations

import copy
import pathlib
import unittest

CI = pathlib.Path(__file__).resolve().parents[2] / "ci"
import importlib.util

spec = importlib.util.spec_from_file_location(
    "verify_m2_g1_platform", CI / "verify-m2-g1-platform.py",
)
assert spec and spec.loader
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
check, LENGTH, SHA = module.check, module.LENGTH, module.SHA


def baseline():
    trial = lambda backend, request_id: {
        "backend": backend,
        "backendVersion": "test",
        "implementationId": "test",
        "negotiatedProtocol": "UNKNOWN",
        "eligibility": "ELIGIBLE",
        "result": "SUCCESS",
        "attempts": 1,
        "committedBytes": LENGTH,
        "routeEpoch": 1,
        "sha256": SHA,
        "originRequestId": request_id,
    }
    case = {
        "schemaVersion": 1,
        "phase": "M2-G1",
        "api": 36,
        "deviceClass": "ANDROID_EMULATOR",
        "mediaPath": "ANDROID_DEFAULT_NETWORK",
        "trials": [
            trial("HTTP_URL_CONNECTION_ROUTE_BOUND", 2),
            trial("PLATFORM_HTTP_ENGINE", 3),
        ],
        "unboundCharges": 0,
        "cancelledRequestCharges": 1,
        "cancelledRequestAcknowledged": True,
        "cancelTerminal": "CANCELED",
        "cancelledOriginRequestId": 4,
    }
    origin = [{
        "plane": "data",
        "requestId": i,
        "method": "GET",
        "path": "/fixtures/F1/segment-1-00001.m4s",
        "rangeHeader": "bytes=0-81810",
        "status": 206,
        "bodyBytesWritten": LENGTH if i < 4 else LENGTH // 2,
    } for i in (2, 3, 4)]
    return case, origin


class G1VerifierFalsificationTests(unittest.TestCase):
    def test_valid_correlated_control_candidate_and_cancel(self):
        check(*baseline())

    def test_unmatched_trial_id_fails_despite_three_origin_requests(self):
        case, origin = baseline()
        case["trials"][1]["originRequestId"] = 99
        with self.assertRaises(ValueError):
            check(case, origin)

    def test_duplicate_trial_id_fails_despite_correct_row_count(self):
        case, origin = baseline()
        case["trials"][1]["originRequestId"] = 2
        with self.assertRaises(ValueError):
            check(case, origin)

    def test_swapped_sequential_attribution_fails(self):
        case, origin = baseline()
        case["trials"][0]["originRequestId"] = 3
        case["trials"][1]["originRequestId"] = 2
        with self.assertRaises(ValueError):
            check(case, origin)

    def test_failure_callback_cannot_masquerade_as_cancellation(self):
        case, origin = baseline()
        case["cancelTerminal"] = "FAILED"
        with self.assertRaises(ValueError):
            check(case, origin)

    def test_success_callback_cannot_masquerade_as_cancellation(self):
        case, origin = baseline()
        case["cancelTerminal"] = "SUCCEEDED"
        with self.assertRaises(ValueError):
            check(case, origin)

    def test_old_boolean_only_cancellation_evidence_is_rejected(self):
        case, origin = baseline()
        del case["cancelTerminal"]
        with self.assertRaises(ValueError):
            check(case, origin)

    def test_extra_origin_get_fails_instead_of_becoming_invisible_retry(self):
        case, origin = baseline()
        extra = copy.deepcopy(origin[2])
        extra["requestId"] = 5
        origin.append(extra)
        with self.assertRaises(ValueError):
            check(case, origin)

    def test_origin_range_mismatch_fails_with_correct_device_claim(self):
        case, origin = baseline()
        origin[1]["rangeHeader"] = "bytes=1-81810"
        with self.assertRaises(ValueError):
            check(case, origin)

    def test_missing_cancel_origin_fails(self):
        case, origin = baseline()
        origin.pop()
        with self.assertRaises(ValueError):
            check(case, origin)


if __name__ == "__main__":
    unittest.main()
