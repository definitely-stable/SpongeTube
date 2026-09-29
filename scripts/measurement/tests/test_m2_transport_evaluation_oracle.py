import copy
import pathlib
import sys
import unittest

SCRIPT_DIR = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from m2_transport_evaluation_oracle import (
    TransportEvaluationError,
    analyze_trials,
    validate_summary,
)


SHA_A = "a" * 64
SHA_B = "b" * 64
SHA_C = "c" * 64
SHA_D = "d" * 64
SHA_E = "e" * 64
SHA_F = "f" * 64


def comparison():
    return {
        "workFingerprint": SHA_A,
        "scenarioHash": SHA_B,
        "deviceStateFingerprint": SHA_C,
        "playbackMode": "SPONGE",
        "cacheStateFingerprint": SHA_D,
        "recoveryPolicyFingerprint": SHA_E,
        "routePolicyFingerprint": SHA_F,
        "connectionState": "COLD",
    }


def successful_trial(trial_id, backend, block, position):
    return {
        "trialId": trial_id,
        "orderingBlock": block,
        "positionInBlock": position,
        "backendId": backend,
        "backendVersion": "baseline" if backend.startswith("HTTP_URL") else "platform",
        "implementationId": backend.lower(),
        "eligibility": "ELIGIBLE",
        "comparison": comparison(),
        "route": {
            "exactNetworkBound": True,
            "permitRouteEpoch": 1,
        },
        "result": "SUCCESS",
        "requestCorrectness": {
            "range": "PASS",
            "contentRange": "PASS",
            "responseBounds": "PASS",
            "publishedBytes": "PASS",
        },
        "recovery": {
            "recoveryChainCount": 1,
            "ownerCount": 1,
            "originRequestCount": 1,
            "internalRetryVisibility": "PROVEN_ZERO",
            "internalRetryCount": 0,
        },
        "metrics": {
            "firstByteUs": 10_000,
            "completionUs": 100_000,
            "cancellationLatencyUs": None,
            "cpuTimeUs": 20_000,
            "maxRssBytes": 50_000_000,
            "bytesRequested": 81_811,
            "bytesReceived": 81_811,
            "bytesPublished": 81_811,
        },
        "negotiatedProtocol": "HTTP_1_1",
        "performanceSampleEligible": True,
        "limitations": [],
    }


def trials_document():
    a = "HTTP_URL_CONNECTION_ROUTE_BOUND"
    b = "PLATFORM_HTTP_ENGINE"
    return {
        "schemaVersion": 1,
        "runId": "m2-g0-test",
        "pairId": "pair-n0-cold",
        "deviceClass": "ANDROID_EMULATOR",
        "androidApi": 36,
        "clockDomain": "ANDROID_MONOTONIC",
        "orderingProtocol": "COUNTERBALANCED_PAIRS",
        "orderingSeed": 7,
        "trials": [
            successful_trial("a1", a, 1, 1),
            successful_trial("b1", b, 1, 2),
            successful_trial("b2", b, 2, 1),
            successful_trial("a2", a, 2, 2),
        ],
        "limitations": ["Emulator measurements are directional only."],
    }


def summary_document():
    return {
        "schemaVersion": 1,
        "runId": "m2-g0-test",
        "pairId": "pair-n0-cold",
        "scenarioHash": SHA_B,
        "status": "PASS",
        "deviceClass": "ANDROID_EMULATOR",
        "backendResults": [
            {
                "backendId": "HTTP_URL_CONNECTION_ROUTE_BOUND",
                "eligibility": "ELIGIBLE",
                "trialCount": 2,
                "performanceSampleCount": 2,
            },
            {
                "backendId": "PLATFORM_HTTP_ENGINE",
                "eligibility": "ELIGIBLE",
                "trialCount": 2,
                "performanceSampleCount": 2,
            },
        ],
        "invariants": {
            "work": True,
            "scenario": True,
            "deviceState": True,
            "playbackMode": True,
            "cacheState": True,
            "recoveryPolicy": True,
            "routePolicy": True,
            "connectionState": True,
            "exactRouteBinding": True,
            "orderingBalanced": True,
        },
        "comparison": {
            "correctnessEquivalent": True,
            "recoveryEquivalent": True,
            "routeBindingEquivalent": True,
            "performanceClaimEligible": True,
        },
        "claimScope": "EMULATOR_DIRECTIONAL",
        "physicalDeviceEvidence": False,
        "decision": {
            "state": "TECHNICALLY_ELIGIBLE",
            "selectedBackend": None,
            "basis": "CORRECTNESS_AND_RESILIENCE",
            "reasonCodes": [
                "CORRECTNESS_EQUIVALENT",
                "RECOVERY_EQUIVALENT",
                "ROUTE_BINDING_EQUIVALENT",
                "PAIRED_METRICS_AVAILABLE",
                "NO_PERFORMANCE_CLAIM",
            ],
        },
        "gates": {"M2-ACC-10": True},
        "limitations": ["No representative physical-device performance claim."],
    }


class TransportEvaluationOracleTest(unittest.TestCase):
    def test_counterbalanced_pair_passes(self):
        trials = trials_document()
        computed = analyze_trials(trials)
        self.assertTrue(computed["comparisonResult"]["correctnessEquivalent"])
        self.assertTrue(computed["comparisonResult"]["recoveryEquivalent"])
        validate_summary(summary_document(), trials)

    def test_only_backend_may_change_comparison_fingerprint(self):
        trials = trials_document()
        trials["trials"][1]["comparison"]["scenarioHash"] = "1" * 64
        with self.assertRaises(TransportEvaluationError):
            analyze_trials(trials)

    def test_ambient_route_candidate_is_rejected(self):
        trials = trials_document()
        trials["trials"][1]["route"]["exactNetworkBound"] = False
        trials["trials"][1]["performanceSampleEligible"] = False
        with self.assertRaises(TransportEvaluationError):
            analyze_trials(trials)

    def test_unavailable_backend_is_not_a_latency_sample(self):
        trials = trials_document()
        row = trials["trials"][1]
        row["eligibility"] = "UNAVAILABLE_ON_DEVICE"
        row["result"] = "UNAVAILABLE"
        row["route"] = {"exactNetworkBound": False, "permitRouteEpoch": None}
        row["requestCorrectness"] = {
            "range": "NOT_APPLICABLE",
            "contentRange": "NOT_APPLICABLE",
            "responseBounds": "NOT_APPLICABLE",
            "publishedBytes": "NOT_APPLICABLE",
        }
        row["recovery"] = {
            "recoveryChainCount": 0,
            "ownerCount": 0,
            "originRequestCount": 0,
            "internalRetryVisibility": "OPAQUE",
            "internalRetryCount": None,
        }
        row["performanceSampleEligible"] = False
        row["metrics"]["firstByteUs"] = 999
        with self.assertRaises(TransportEvaluationError):
            analyze_trials(trials)

    def test_opaque_internal_recovery_cannot_be_performance_sample(self):
        trials = trials_document()
        row = trials["trials"][1]
        row["recovery"]["internalRetryVisibility"] = "OPAQUE"
        row["recovery"]["internalRetryCount"] = None
        with self.assertRaises(TransportEvaluationError):
            analyze_trials(trials)

    def test_ordering_bias_is_rejected(self):
        trials = trials_document()
        # Keep every block structurally valid but put the control first in both.
        trials["trials"][2], trials["trials"][3] = (
            trials["trials"][3],
            trials["trials"][2],
        )
        trials["trials"][2]["positionInBlock"] = 1
        trials["trials"][3]["positionInBlock"] = 2
        with self.assertRaises(TransportEvaluationError):
            analyze_trials(trials)

    def test_unavailable_candidate_makes_paired_performance_ineligible(self):
        trials = trials_document()
        for row in trials["trials"]:
            if row["backendId"] != "PLATFORM_HTTP_ENGINE":
                continue
            row["eligibility"] = "UNAVAILABLE_ON_DEVICE"
            row["result"] = "UNAVAILABLE"
            row["route"] = {
                "exactNetworkBound": False,
                "permitRouteEpoch": None,
            }
            row["requestCorrectness"] = {
                "range": "NOT_APPLICABLE",
                "contentRange": "NOT_APPLICABLE",
                "responseBounds": "NOT_APPLICABLE",
                "publishedBytes": "NOT_APPLICABLE",
            }
            row["recovery"] = {
                "recoveryChainCount": 0,
                "ownerCount": 0,
                "originRequestCount": 0,
                "internalRetryVisibility": "OPAQUE",
                "internalRetryCount": None,
            }
            row["metrics"] = {
                "firstByteUs": None,
                "completionUs": None,
                "cancellationLatencyUs": None,
                "cpuTimeUs": None,
                "maxRssBytes": None,
                "bytesRequested": None,
                "bytesReceived": None,
                "bytesPublished": None,
            }
            row["performanceSampleEligible"] = False

        computed = analyze_trials(trials)
        self.assertFalse(
            computed["comparisonResult"]["performanceClaimEligible"]
        )

    def test_technical_eligibility_requires_recovery_equivalence(self):
        trials = trials_document()
        summary = summary_document()
        trials["trials"][1]["recovery"]["ownerCount"] = 2
        trials["trials"][1]["recovery"]["originRequestCount"] = 2
        summary["comparison"]["recoveryEquivalent"] = False
        with self.assertRaises(TransportEvaluationError):
            validate_summary(summary, trials)

    def test_summary_cannot_hide_extra_owner(self):
        trials = trials_document()
        trials["trials"][1]["recovery"]["ownerCount"] = 2
        trials["trials"][1]["recovery"]["originRequestCount"] = 2
        summary = summary_document()
        with self.assertRaises(TransportEvaluationError):
            validate_summary(summary, trials)

    def test_emulator_cannot_make_performance_based_selection(self):
        trials = trials_document()
        summary = summary_document()
        summary["decision"] = {
            "state": "SELECTED",
            "selectedBackend": "PLATFORM_HTTP_ENGINE",
            "basis": "PERFORMANCE",
            "reasonCodes": [
                "CORRECTNESS_EQUIVALENT",
                "RECOVERY_EQUIVALENT",
                "ROUTE_BINDING_EQUIVALENT",
                "PAIRED_METRICS_AVAILABLE",
                "DEPENDENCY_POLICY_CLEAR",
            ],
        }
        with self.assertRaises(TransportEvaluationError):
            validate_summary(summary, trials)

    def test_physical_performance_claim_requires_physical_device(self):
        trials = trials_document()
        summary = summary_document()
        summary["claimScope"] = "PHYSICAL_DEVICE_PERFORMANCE"
        summary["physicalDeviceEvidence"] = True
        with self.assertRaises(TransportEvaluationError):
            validate_summary(summary, trials)

    def test_no_decision_cannot_smuggle_selected_backend(self):
        trials = trials_document()
        summary = summary_document()
        summary["decision"]["state"] = "NO_DECISION"
        summary["decision"]["selectedBackend"] = "PLATFORM_HTTP_ENGINE"
        with self.assertRaises(TransportEvaluationError):
            validate_summary(summary, trials)

    def test_protocol_is_descriptive_not_comparison_identity(self):
        trials = trials_document()
        trials["trials"][0]["negotiatedProtocol"] = "HTTP_1_1"
        trials["trials"][1]["negotiatedProtocol"] = "HTTP_3"
        # Protocol may differ without changing the comparison fingerprint.
        analyze_trials(trials)


if __name__ == "__main__":
    unittest.main()
