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

RUN_ID = "m2-g2-route-api36"
TRIAL_ID = "trial-1"
SESSION_ID = f"m2-g2-route-{TRIAL_ID}"


def validate_route(proof):
    verifier.validate_route_evidence(
        proof,
        trial_id=TRIAL_ID,
        run_id=RUN_ID,
    )


def _route_state(state, epoch=None, capabilities_received=False):
    known = state == "AVAILABLE" and capabilities_received
    return {
        "state": state,
        "routeEpoch": epoch,
        "capabilitiesReceived": capabilities_received,
        "internet": "TRUE" if known else "UNKNOWN",
        "validated": "TRUE" if known else "UNKNOWN",
        "vpn": "FALSE" if known else "UNKNOWN",
        "metered": "FALSE" if known else "UNKNOWN",
        "restricted": "FALSE" if known else "UNKNOWN",
        "blocked": "UNKNOWN",
        "suspended": "FALSE" if known else "UNKNOWN",
    }


def _observed_capabilities():
    return {
        "internet": "TRUE",
        "validated": "TRUE",
        "vpn": "FALSE",
        "metered": "FALSE",
        "restricted": "FALSE",
        "suspended": "FALSE",
    }


def route_proof():
    return {
        "routeEpochBefore": 1,
        "routeEpochAfter": 2,
        "routeUnavailableObserved": True,
        "chargesBeforeRestore": 1,
        "attemptsBeforeRestore": 1,
        "gateCalls": 2,
        "replacementValidationRendezvousObserved": True,
        "physicalAttempts": [
            {
                "startElapsedRealtimeNs": 250,
                "endElapsedRealtimeNs": 350,
            },
            {
                "startElapsedRealtimeNs": 450,
                "endElapsedRealtimeNs": 550,
            },
        ],
        "routeEvidence": {
            "schemaVersion": 1,
            "runId": RUN_ID,
            "sessionId": SESSION_ID,
            "androidApi": 36,
            "clockDomain": "ANDROID_MONOTONIC",
            "events": [
                {
                    "sequence": 1,
                    "elapsedRealtimeNs": 100,
                    "source": "MONITOR_LIFECYCLE",
                    "signal": "MONITOR_STARTED",
                    "disposition": "APPLIED",
                    "platformRouteRef": None,
                    "routeEpochBefore": None,
                    "routeEpochAfter": None,
                    "observedCapabilities": None,
                    "observedBlocked": None,
                    "runtimeStateAfter": _route_state("INITIALIZING"),
                },
                {
                    "sequence": 2,
                    "elapsedRealtimeNs": 150,
                    "source": "DEFAULT_NETWORK_CALLBACK",
                    "signal": "AVAILABLE",
                    "disposition": "APPLIED",
                    "platformRouteRef": "p1",
                    "routeEpochBefore": None,
                    "routeEpochAfter": 1,
                    "observedCapabilities": None,
                    "observedBlocked": None,
                    "runtimeStateAfter": _route_state("AVAILABLE", 1),
                },
                {
                    "sequence": 3,
                    "elapsedRealtimeNs": 200,
                    "source": "DEFAULT_NETWORK_CALLBACK",
                    "signal": "CAPABILITIES_CHANGED",
                    "disposition": "APPLIED",
                    "platformRouteRef": "p1",
                    "routeEpochBefore": 1,
                    "routeEpochAfter": 1,
                    "observedCapabilities": _observed_capabilities(),
                    "observedBlocked": None,
                    "runtimeStateAfter": _route_state("AVAILABLE", 1, True),
                },
                {
                    "sequence": 4,
                    "elapsedRealtimeNs": 300,
                    "source": "DEFAULT_NETWORK_CALLBACK",
                    "signal": "LOST",
                    "disposition": "APPLIED",
                    "platformRouteRef": "p1",
                    "routeEpochBefore": 1,
                    "routeEpochAfter": None,
                    "observedCapabilities": None,
                    "observedBlocked": None,
                    "runtimeStateAfter": _route_state("UNAVAILABLE"),
                },
                {
                    "sequence": 5,
                    "elapsedRealtimeNs": 400,
                    "source": "DEFAULT_NETWORK_CALLBACK",
                    "signal": "AVAILABLE",
                    "disposition": "APPLIED",
                    "platformRouteRef": "p1",
                    "routeEpochBefore": None,
                    "routeEpochAfter": 2,
                    "observedCapabilities": None,
                    "observedBlocked": None,
                    "runtimeStateAfter": _route_state("AVAILABLE", 2),
                },
                {
                    "sequence": 6,
                    "elapsedRealtimeNs": 425,
                    "source": "DEFAULT_NETWORK_CALLBACK",
                    "signal": "CAPABILITIES_CHANGED",
                    "disposition": "APPLIED",
                    "platformRouteRef": "p1",
                    "routeEpochBefore": 2,
                    "routeEpochAfter": 2,
                    "observedCapabilities": _observed_capabilities(),
                    "observedBlocked": None,
                    "runtimeStateAfter": _route_state("AVAILABLE", 2, True),
                },
                {
                    "sequence": 7,
                    "elapsedRealtimeNs": 600,
                    "source": "MONITOR_LIFECYCLE",
                    "signal": "MONITOR_STOPPED",
                    "disposition": "APPLIED",
                    "platformRouteRef": None,
                    "routeEpochBefore": 2,
                    "routeEpochAfter": 2,
                    "observedCapabilities": None,
                    "observedBlocked": None,
                    "runtimeStateAfter": _route_state("AVAILABLE", 2, True),
                },
            ],
            "policyEvaluations": [
                {
                    "sequence": 1,
                    "routeEventSequenceWatermark": 3,
                    "routeEpoch": 1,
                    "guardBefore": "UNRESOLVED",
                    "guardAfter": "SYSTEM_DEFAULT_ALLOWED",
                    "explicitDirectOverride": False,
                    "decision": "ALLOW",
                    "reason": "ROUTE_READY",
                },
                {
                    "sequence": 2,
                    "routeEventSequenceWatermark": 4,
                    "routeEpoch": None,
                    "guardBefore": "SYSTEM_DEFAULT_ALLOWED",
                    "guardAfter": "SYSTEM_DEFAULT_ALLOWED",
                    "explicitDirectOverride": False,
                    "decision": "PAUSE",
                    "reason": "NO_USABLE_DEFAULT",
                },
                {
                    "sequence": 3,
                    "routeEventSequenceWatermark": 6,
                    "routeEpoch": 2,
                    "guardBefore": "SYSTEM_DEFAULT_ALLOWED",
                    "guardAfter": "SYSTEM_DEFAULT_ALLOWED",
                    "explicitDirectOverride": False,
                    "decision": "ALLOW",
                    "reason": "ROUTE_READY",
                },
            ],
        },
    }


class G2RouteVerifierTest(unittest.TestCase):
    def test_route_evidence_accepts_old_unavailable_replacement_sequence(self):
        validate_route(route_proof())

    def test_route_evidence_accepts_bootstrap_established_old_route(self):
        proof = route_proof()
        initial = proof["routeEvidence"]["events"][1]
        initial["signal"] = "BOOTSTRAP_SNAPSHOT"
        initial["source"] = "BOOTSTRAP_ACTIVE_NETWORK"
        validate_route(proof)

    def test_route_evidence_accepts_same_platform_route_ref_after_unavailable_gap(self):
        proof = route_proof()
        self.assertEqual(
            proof["routeEvidence"]["events"][1]["platformRouteRef"],
            proof["routeEvidence"]["events"][4]["platformRouteRef"],
        )
        validate_route(proof)

    def test_route_evidence_rejects_wrong_signal_source(self):
        proof = route_proof()
        proof["routeEvidence"]["events"][3]["source"] = "BOOTSTRAP_ACTIVE_NETWORK"
        with self.assertRaisesRegex(
            verifier.RouteReplacementEvidenceError,
            "route-events-v1 oracle failed",
        ):
            validate_route(proof)

    def test_route_evidence_rejects_lost_for_different_platform_ref(self):
        proof = route_proof()
        proof["routeEvidence"]["events"][3]["platformRouteRef"] = "p2"
        with self.assertRaisesRegex(
            verifier.RouteReplacementEvidenceError,
            "route-events-v1 oracle failed",
        ):
            validate_route(proof)

    def test_route_evidence_rejects_cross_run_route_artifact(self):
        proof = route_proof()
        proof["routeEvidence"]["runId"] = "other-run"
        with self.assertRaisesRegex(
            verifier.RouteReplacementEvidenceError,
            "route evidence runId drift",
        ):
            validate_route(proof)

    def test_route_evidence_rejects_cross_session_route_artifact(self):
        proof = route_proof()
        proof["routeEvidence"]["sessionId"] = "m2-g2-route-other-trial"
        with self.assertRaisesRegex(
            verifier.RouteReplacementEvidenceError,
            "route evidence sessionId drift",
        ):
            validate_route(proof)

    def test_route_evidence_rejects_initial_allow_before_route_established(self):
        proof = route_proof()
        proof["routeEvidence"]["policyEvaluations"][0]["routeEventSequenceWatermark"] = 2
        with self.assertRaisesRegex(
            verifier.RouteReplacementEvidenceError,
            "route-events-v1 oracle failed",
        ):
            validate_route(proof)

    def test_route_evidence_rejects_missing_old_route_establishment(self):
        proof = route_proof()
        proof["routeEvidence"]["events"][1]["disposition"] = "UNCHANGED"
        with self.assertRaisesRegex(
            verifier.RouteReplacementEvidenceError,
            "route-events-v1 oracle failed",
        ):
            validate_route(proof)

    def test_route_evidence_rejects_non_advancing_epoch(self):
        proof = route_proof()
        proof["routeEpochAfter"] = 1
        with self.assertRaisesRegex(
            verifier.RouteReplacementEvidenceError,
            "route epoch did not advance",
        ):
            validate_route(proof)

    def test_route_evidence_rejects_second_owner_before_restore(self):
        proof = route_proof()
        proof["attemptsBeforeRestore"] = 2
        with self.assertRaisesRegex(
            verifier.RouteReplacementEvidenceError,
            "second owner started",
        ):
            validate_route(proof)

    def test_route_evidence_rejects_missing_pause(self):
        proof = route_proof()
        evaluations = proof["routeEvidence"]["policyEvaluations"]
        proof["routeEvidence"]["policyEvaluations"] = [evaluations[0], evaluations[2]]
        with self.assertRaisesRegex(
            verifier.RouteReplacementEvidenceError,
            "route-events-v1 oracle failed",
        ):
            validate_route(proof)

    def test_route_evidence_rejects_missing_validation_rendezvous(self):
        proof = route_proof()
        proof["replacementValidationRendezvousObserved"] = False
        with self.assertRaisesRegex(
            verifier.RouteReplacementEvidenceError,
            "replacement validation rendezvous missing",
        ):
            validate_route(proof)

    def test_route_evidence_rejects_metered_initial_route(self):
        proof = route_proof()
        proof["routeEvidence"]["events"][2]["runtimeStateAfter"]["metered"] = "TRUE"
        proof["routeEvidence"]["events"][2]["observedCapabilities"]["metered"] = "TRUE"
        with self.assertRaisesRegex(
            verifier.RouteReplacementEvidenceError,
            "canonical old route was not validated/unmetered",
        ):
            validate_route(proof)

    def test_route_evidence_rejects_metered_replacement_route(self):
        proof = route_proof()
        proof["routeEvidence"]["events"][5]["runtimeStateAfter"]["metered"] = "TRUE"
        proof["routeEvidence"]["events"][5]["observedCapabilities"]["metered"] = "TRUE"
        # Keep the synthetic route-event stream internally consistent so the
        # generic route oracle accepts it and the G2-E-specific isolation rule
        # is the component that rejects the metered replacement.
        proof["routeEvidence"]["events"][6]["runtimeStateAfter"]["metered"] = "TRUE"
        with self.assertRaisesRegex(
            verifier.RouteReplacementEvidenceError,
            "canonical validated/unmetered rendezvous",
        ):
            validate_route(proof)

    def test_route_evidence_rejects_owner_before_validation_rendezvous(self):
        proof = route_proof()
        proof["physicalAttempts"][1]["startElapsedRealtimeNs"] = 410
        with self.assertRaisesRegex(
            verifier.RouteReplacementEvidenceError,
            "replacement owner started before validation rendezvous",
        ):
            validate_route(proof)

    def test_route_evidence_rejects_second_owner_before_replacement_available(self):
        proof = route_proof()
        proof["physicalAttempts"][1]["startElapsedRealtimeNs"] = 350
        with self.assertRaisesRegex(
            verifier.RouteReplacementEvidenceError,
            "physical owners are not causally separated",
        ):
            validate_route(proof)

    def test_route_evidence_rejects_pause_not_downstream_of_loss(self):
        proof = route_proof()
        proof["routeEvidence"]["policyEvaluations"][1]["routeEventSequenceWatermark"] = 3
        with self.assertRaisesRegex(
            verifier.RouteReplacementEvidenceError,
            "route-events-v1 oracle failed",
        ):
            validate_route(proof)

    def test_route_evidence_rejects_allow_before_replacement_available(self):
        proof = route_proof()
        proof["routeEvidence"]["policyEvaluations"][2]["routeEventSequenceWatermark"] = 4
        with self.assertRaisesRegex(
            verifier.RouteReplacementEvidenceError,
            "route-events-v1 oracle failed",
        ):
            validate_route(proof)

    def test_route_evidence_rejects_non_monotonic_event_time(self):
        proof = route_proof()
        proof["routeEvidence"]["events"][4]["elapsedRealtimeNs"] = 250
        with self.assertRaisesRegex(
            verifier.RouteReplacementEvidenceError,
            "route-events-v1 oracle failed",
        ):
            validate_route(proof)

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
