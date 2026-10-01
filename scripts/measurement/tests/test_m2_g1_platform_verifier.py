"""Falsification tests for the independent G1 origin/cancellation verifier."""
from __future__ import annotations

import copy
import importlib.util
import pathlib
import unittest

CI = pathlib.Path(__file__).resolve().parents[2] / "ci"
spec = importlib.util.spec_from_file_location(
    "verify_m2_g1_platform", CI / "verify-m2-g1-platform.py",
)
assert spec and spec.loader
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
check, LENGTH, SHA = module.check, module.LENGTH, module.SHA


def baseline():
    def trial(backend, request_id):
        return {
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
            "deliveryBindingRevision": "binding-1",
            "deliveryBindingTargetResolved": True,
        }

    response_faults = [
        {
            "case": "redirect",
            "result": "HTTP_302",
            "terminal": "CANCELED",
            "originRequestId": 1,
            "charges": 1,
            "emittedBytes": 0,
            "deliveryBindingRevision": None,
        },
        {
            "case": "wrong-content-range",
            "result": "CONTENT_RANGE_MISMATCH",
            "terminal": "CANCELED",
            "originRequestId": 2,
            "charges": 1,
            "emittedBytes": 0,
            "deliveryBindingRevision": None,
        },
        {
            "case": "overlong-body",
            "result": "RESPONSE_BYTES_OUTSIDE_RANGE",
            "terminal": "CANCELED",
            "originRequestId": 3,
            "charges": 1,
            "emittedBytes": LENGTH,
            "deliveryBindingRevision": None,
        },
        {
            "case": "binding",
            "result": "SUCCESS",
            "terminal": "SUCCEEDED",
            "originRequestId": 4,
            "charges": 1,
            "emittedBytes": LENGTH,
            "deliveryBindingRevision": "binding-1",
        },
    ]
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
        "actualDefaultRouteLossObserved": True,
        "staleRouteBoundRequestRejected": True,
        "staleRouteCorrelationAbsent": True,
        "staleRoutePublishedBytes": 0,
        "staleRouteCharges": 1,
        "restoredRouteEpoch": 2,
        "responseFaults": response_faults,
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
    statuses = (302, 206, 206, 206)
    planned = (0, 0, LENGTH + 1, LENGTH)
    faults = [{
        "schemaVersion": 1,
        "requestId": i + 1,
        "method": "GET",
        "path": "/" + row["case"],
        "rangeHeader": "bytes=0-81810",
        "acceptEncoding": "identity",
        "bindingRevision": row["deliveryBindingRevision"],
        "fetchKey": "fixture:F1/audio-main/f1-audio-1/m2g1:fault:" + row["case"],
        "attemptHeader": "1",
        "status": statuses[i],
        "plannedResponseBytes": planned[i],
        "bodyBytesWritten": LENGTH if row["case"] == "binding" else 0,
        "responseVariant": row["case"],
        "outcome": "COMPLETE",
    } for i, row in enumerate(response_faults)]
    return case, origin, faults


class G1VerifierFalsificationTests(unittest.TestCase):
    def test_valid_full_g1_proof(self):
        check(*baseline())

    def mutate_case(self, mutate):
        case, origin, faults = baseline()
        mutate(case)
        with self.assertRaises(ValueError):
            check(case, origin, faults)

    def test_unmatched_trial_id_fails(self):
        self.mutate_case(lambda case: case["trials"][1].update(originRequestId=99))

    def test_duplicate_trial_id_fails(self):
        self.mutate_case(lambda case: case["trials"][1].update(originRequestId=2))

    def test_swapped_trial_attribution_fails(self):
        def mutate(case):
            case["trials"][0]["originRequestId"] = 3
            case["trials"][1]["originRequestId"] = 2
        self.mutate_case(mutate)

    def test_failure_callback_cannot_masquerade_as_cancellation(self):
        self.mutate_case(lambda case: case.update(cancelTerminal="FAILED"))

    def test_success_callback_cannot_masquerade_as_cancellation(self):
        self.mutate_case(lambda case: case.update(cancelTerminal="SUCCEEDED"))

    def test_extra_origin_get_fails(self):
        case, origin, faults = baseline()
        extra = copy.deepcopy(origin[2])
        extra["requestId"] = 5
        origin.append(extra)
        with self.assertRaises(ValueError):
            check(case, origin, faults)

    def test_stale_binding_origin_correlation_is_rejected(self):
        self.mutate_case(lambda case: case.update(staleRouteCorrelationAbsent=False))

    def test_delivery_binding_revision_drift_fails(self):
        self.mutate_case(
            lambda case: case["trials"][1].update(deliveryBindingRevision="binding-2")
        )

    def test_route_epoch_must_change_after_restoration(self):
        self.mutate_case(lambda case: case.update(restoredRouteEpoch=1))

    def test_redirect_follow_creating_a_fifth_fault_request_fails(self):
        case, origin, faults = baseline()
        extra = copy.deepcopy(faults[-1])
        extra["requestId"] = 5
        faults.append(extra)
        with self.assertRaises(ValueError):
            check(case, origin, faults)

    def test_fault_origin_range_header_mismatch_fails(self):
        case, origin, faults = baseline()
        faults[1]["rangeHeader"] = "bytes=1-81810"
        with self.assertRaises(ValueError):
            check(case, origin, faults)

    def test_fault_origin_missing_identity_encoding_fails(self):
        case, origin, faults = baseline()
        faults[2]["acceptEncoding"] = "gzip"
        with self.assertRaises(ValueError):
            check(case, origin, faults)

    def test_overlong_case_must_really_plan_extra_byte(self):
        case, origin, faults = baseline()
        faults[2]["plannedResponseBytes"] = LENGTH
        with self.assertRaises(ValueError):
            check(case, origin, faults)

    def test_binding_revision_must_be_present_on_wire(self):
        case, origin, faults = baseline()
        faults[3]["bindingRevision"] = None
        with self.assertRaises(ValueError):
            check(case, origin, faults)

    def test_candidate_must_report_overflow_not_success(self):
        self.mutate_case(
            lambda case: case["responseFaults"][2].update(result="SUCCESS")
        )


if __name__ == "__main__":
    unittest.main()
