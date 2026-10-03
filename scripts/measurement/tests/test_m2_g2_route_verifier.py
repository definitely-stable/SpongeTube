from __future__ import annotations

import importlib.util
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]


def load_module():
    path = ROOT / "scripts" / "ci" / "verify-m2-g2-route.py"
    spec = importlib.util.spec_from_file_location("verify_m2_g2_route", path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


verifier = load_module()


def route_proof():
    return {
        "routeEpochBefore": 3,
        "routeEpochAfter": 5,
        "routeUnavailableObserved": True,
        "chargesBeforeRestore": 1,
        "attemptsBeforeRestore": 1,
        "gateCalls": 2,
        "routeEvidence": {
            "schemaVersion": 1,
            "androidApi": 36,
            "clockDomain": "ANDROID_MONOTONIC",
            "events": [
                {
                    "sequence": 1,
                    "elapsedRealtimeNs": 100,
                    "signal": "MONITOR_STARTED",
                    "disposition": "APPLIED",
                    "routeEpochBefore": None,
                    "routeEpochAfter": None,
                    "runtimeStateAfter": {"state": "INITIALIZING"},
                },
                {
                    "sequence": 2,
                    "elapsedRealtimeNs": 200,
                    "signal": "AVAILABLE",
                    "disposition": "APPLIED",
                    "routeEpochBefore": None,
                    "routeEpochAfter": 3,
                    "runtimeStateAfter": {"state": "AVAILABLE"},
                },
                {
                    "sequence": 3,
                    "elapsedRealtimeNs": 300,
                    "signal": "LOST",
                    "disposition": "APPLIED",
                    "routeEpochBefore": 3,
                    "routeEpochAfter": None,
                    "runtimeStateAfter": {"state": "UNAVAILABLE"},
                },
                {
                    "sequence": 4,
                    "elapsedRealtimeNs": 400,
                    "signal": "AVAILABLE",
                    "disposition": "APPLIED",
                    "routeEpochBefore": None,
                    "routeEpochAfter": 5,
                    "runtimeStateAfter": {"state": "AVAILABLE"},
                },
                {
                    "sequence": 5,
                    "elapsedRealtimeNs": 500,
                    "signal": "MONITOR_STOPPED",
                    "disposition": "APPLIED",
                    "routeEpochBefore": 5,
                    "routeEpochAfter": 5,
                    "runtimeStateAfter": {"state": "AVAILABLE"},
                },
            ],
            "policyEvaluations": [
                {
                    "sequence": 1,
                    "routeEventSequenceWatermark": 2,
                    "decision": "ALLOW",
                    "reason": "ROUTE_READY",
                    "routeEpoch": 3,
                },
                {
                    "sequence": 2,
                    "routeEventSequenceWatermark": 3,
                    "decision": "PAUSE",
                    "reason": "NO_USABLE_DEFAULT",
                    "routeEpoch": None,
                },
                {
                    "sequence": 3,
                    "routeEventSequenceWatermark": 4,
                    "decision": "ALLOW",
                    "reason": "ROUTE_READY",
                    "routeEpoch": 5,
                },
            ],
        },
    }


class G2RouteVerifierTest(unittest.TestCase):
    def test_route_evidence_accepts_old_unavailable_replacement_sequence(self):
        verifier.validate_route_evidence(route_proof(), trial_id="trial-1")

    def test_route_evidence_rejects_non_advancing_epoch(self):
        proof = route_proof()
        proof["routeEpochAfter"] = 3
        with self.assertRaisesRegex(
            verifier.RouteReplacementEvidenceError,
            "route epoch did not advance",
        ):
            verifier.validate_route_evidence(proof, trial_id="trial-1")

    def test_route_evidence_rejects_second_owner_before_restore(self):
        proof = route_proof()
        proof["attemptsBeforeRestore"] = 2
        with self.assertRaisesRegex(
            verifier.RouteReplacementEvidenceError,
            "second owner started",
        ):
            verifier.validate_route_evidence(proof, trial_id="trial-1")

    def test_route_evidence_rejects_missing_pause(self):
        proof = route_proof()
        evaluations = proof["routeEvidence"]["policyEvaluations"]
        proof["routeEvidence"]["policyEvaluations"] = [evaluations[0], evaluations[2]]
        with self.assertRaisesRegex(
            verifier.RouteReplacementEvidenceError,
            "pause is not causally downstream",
        ):
            verifier.validate_route_evidence(proof, trial_id="trial-1")

    def test_route_evidence_rejects_pause_not_downstream_of_loss(self):
        proof = route_proof()
        proof["routeEvidence"]["policyEvaluations"][1]["routeEventSequenceWatermark"] = 2
        with self.assertRaisesRegex(
            verifier.RouteReplacementEvidenceError,
            "pause is not causally downstream",
        ):
            verifier.validate_route_evidence(proof, trial_id="trial-1")

    def test_route_evidence_rejects_allow_before_replacement_available(self):
        proof = route_proof()
        proof["routeEvidence"]["policyEvaluations"][2]["routeEventSequenceWatermark"] = 3
        with self.assertRaisesRegex(
            verifier.RouteReplacementEvidenceError,
            "replacement route ALLOW is not causally downstream",
        ):
            verifier.validate_route_evidence(proof, trial_id="trial-1")

    def test_route_evidence_rejects_non_monotonic_event_time(self):
        proof = route_proof()
        proof["routeEvidence"]["events"][3]["elapsedRealtimeNs"] = 250
        with self.assertRaisesRegex(
            verifier.RouteReplacementEvidenceError,
            "timestamps are not monotonic",
        ):
            verifier.validate_route_evidence(proof, trial_id="trial-1")

    def test_finalize_rejects_old_route_origin_reach(self):
        row = {
            "trialId": "trial-1",
            "recovery": {
                "originRequestCount": 1,
                "internalRetryVisibility": "OPAQUE",
                "internalRetryCount": None,
            },
            "performanceSampleEligible": False,
            "limitations": ["RAW_DEVICE_ROW_REQUIRES_HOST_RETRY_FINALIZATION"],
        }
        raw_proof = {
            "physicalAttempts": [
                {
                    "terminal": "ATTEMPT_FAILED",
                    "transportCorrelationId": "7",
                },
                {
                    "terminal": "ATTEMPT_COMPLETED",
                    "transportCorrelationId": "8",
                },
            ],
        }
        summary_proof = {
            "successfulRequestId": 8,
            "correlatedRequestIds": [7, 8],
        }
        with self.assertRaisesRegex(
            verifier.RouteReplacementEvidenceError,
            "unexpectedly reached origin",
        ):
            verifier.finalize_row(
                row,
                trial_origin=[{
                    "requestId": 8,
                    "plane": "data",
                    "method": "GET",
                    "path": verifier.shared.RESOURCE_PATH,
                    "profileId": "N0",
                    "scenarioId": "N0",
                    "rangeHeader": f"bytes=0-{verifier.RESOURCE_LENGTH - 1}",
                    "resolvedRangeStart": 0,
                    "resolvedRangeEndExclusive": verifier.RESOURCE_LENGTH,
                    "status": 206,
                    "plannedResponseBytes": verifier.RESOURCE_LENGTH,
                    "bodyBytesWritten": verifier.RESOURCE_LENGTH,
                    "outcome": "SUCCESS",
                }],
                raw_proof=raw_proof,
                summary_proof=summary_proof,
            )

    def test_finalize_marks_zero_internal_replay_after_proven_boundary(self):
        row = {
            "trialId": "trial-1",
            "recovery": {
                "originRequestCount": 1,
                "internalRetryVisibility": "OPAQUE",
                "internalRetryCount": None,
            },
            "performanceSampleEligible": False,
            "limitations": ["RAW_DEVICE_ROW_REQUIRES_HOST_RETRY_FINALIZATION"],
        }
        raw_proof = {
            "physicalAttempts": [
                {
                    "terminal": "ATTEMPT_FAILED",
                    "transportCorrelationId": None,
                },
                {
                    "terminal": "ATTEMPT_COMPLETED",
                    "transportCorrelationId": "9",
                },
            ],
        }
        summary_proof = {
            "successfulRequestId": 9,
            "correlatedRequestIds": [9],
        }
        origin = {
            "requestId": 9,
            "plane": "data",
            "method": "GET",
            "path": verifier.shared.RESOURCE_PATH,
            "profileId": "N0",
            "scenarioId": "N0",
            "rangeHeader": f"bytes=0-{verifier.RESOURCE_LENGTH - 1}",
            "resolvedRangeStart": 0,
            "resolvedRangeEndExclusive": verifier.RESOURCE_LENGTH,
            "status": 206,
            "plannedResponseBytes": verifier.RESOURCE_LENGTH,
            "bodyBytesWritten": verifier.RESOURCE_LENGTH,
            "outcome": "SUCCESS",
        }
        finalized = verifier.finalize_row(
            row,
            trial_origin=[origin],
            raw_proof=raw_proof,
            summary_proof=summary_proof,
        )
        self.assertEqual("OBSERVABLE", finalized["recovery"]["internalRetryVisibility"])
        self.assertEqual(0, finalized["recovery"]["internalRetryCount"])
        self.assertTrue(finalized["performanceSampleEligible"])


if __name__ == "__main__":
    unittest.main()
