import copy
import json
import pathlib
import sys
import tempfile
import unittest

SCRIPT_DIR = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from m2_transport_decision import (  # noqa: E402
    EXPECTED_BACKENDS,
    TransportDecisionError,
    build_decision,
    verify_decision,
)

GIT_COMMIT = "a" * 40
HASH = "b" * 64


def experiment(experiment_id, family, variant, claim):
    return {
        "experimentId": experiment_id,
        "scenarioFamily": family,
        "scenarioVariant": variant,
        "scenarioHash": HASH,
        "status": "PASS",
        "resilienceClaim": claim,
        "trialCount": 4,
        "pairedBlockCount": 2,
        "trials": f"generated/{experiment_id}/transport-evaluation-trials-v1.json",
        "summary": f"generated/{experiment_id}/transport-evaluation-summary-v1.json",
        "verification": f"evidence/{experiment_id}/verification.json",
    }


def source_document():
    return {
        "schemaVersion": 1,
        "runId": "m2-g2-canonical-test",
        "gitCommit": GIT_COMMIT,
        "checkoutCommit": GIT_COMMIT,
        "status": "PASS",
        "gateId": "M2-ACC-10",
        "requiredExperimentCount": 6,
        "backendIds": list(EXPECTED_BACKENDS),
        "backendIdentities": [
            {
                "backendId": "HTTP_URL_CONNECTION_ROUTE_BOUND",
                "backendVersion": "control-v1",
                "implementationId": "http-url-connection-route-bound",
            },
            {
                "backendId": "PLATFORM_HTTP_ENGINE",
                "backendVersion": "platform-v1",
                "implementationId": "platform-http-engine",
            },
        ],
        "commonComparison": {
            "workFingerprint": HASH,
            "deviceStateFingerprint": HASH,
            "playbackMode": "SPONGE",
            "cacheStateFingerprint": HASH,
            "recoveryPolicyFingerprint": HASH,
            "routePolicyFingerprint": HASH,
            "connectionState": "COLD",
        },
        "orderingProtocol": "COUNTERBALANCED_PAIRS",
        "orderingSeed": 20261001,
        "deviceClass": "ANDROID_EMULATOR",
        "androidApi": 36,
        "experiments": [
            experiment("N0_CONTROL", "N0", "CONTROL", "CONTROL_EQUIVALENT"),
            experiment(
                "N2_HIGH_RTT_JITTER",
                "N2",
                "HIGH_RTT_JITTER",
                "OBSERVED_EFFECT_EQUIVALENT",
            ),
            experiment(
                "N3_BURST_PACKET_LOSS",
                "N3",
                "BURST_PACKET_LOSS",
                "OBSERVED_EFFECT_EQUIVALENT",
            ),
            experiment(
                "N5_BURST_LOSS",
                "N5",
                "BURST_LOSS",
                "INCONCLUSIVE_STOCHASTIC_EFFECT",
            ),
            experiment(
                "N6_TRANSPORT_RESET",
                "N6",
                "TRANSPORT_RESET",
                "TRANSPORT_RESET_EQUIVALENT",
            ),
            experiment(
                "N6_DEFAULT_ROUTE_LOSS_RESTORE",
                "N6",
                "DEFAULT_ROUTE_LOSS_RESTORE",
                "ROUTE_REPLACEMENT_EQUIVALENT",
            ),
        ],
        "aggregate": {
            "correctnessEquivalent": True,
            "recoveryEquivalent": True,
            "routeBindingEquivalent": True,
            "technicalEligibility": True,
            "pairedDirectionalMetricsAvailable": True,
            "physicalDeviceEvidence": False,
            "claimScope": "EMULATOR_DIRECTIONAL",
            "performanceSelectionAllowed": False,
            "selectedBackend": None,
            "n5ResilienceEffect": "INCONCLUSIVE_STOCHASTIC_EFFECT",
        },
        "artifacts": [
            {
                "path": "generated/N0_CONTROL/transport-evaluation-summary-v1.json",
                "sha256": HASH,
                "sizeBytes": 123,
            }
        ],
        "limitations": ["test fixture"],
    }


def write_source(root, value=None):
    path = root / "m2-g2-evidence-index-v1.json"
    path.write_text(
        json.dumps(source_document() if value is None else value, indent=2) + "\n",
        encoding="utf-8",
    )
    return path


class M2TransportDecisionTest(unittest.TestCase):
    def test_builds_no_selection_decision_from_canonical_g2(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = write_source(pathlib.Path(tmp))
            source = json.loads(path.read_text(encoding="utf-8"))
            decision = build_decision(
                source,
                source_path=path,
                expected_git_commit=GIT_COMMIT,
            )
            self.assertEqual("PASS", decision["status"])
            self.assertEqual(
                "TECHNICALLY_ELIGIBLE_NO_SELECTION",
                decision["decision"]["state"],
            )
            self.assertIsNone(decision["decision"]["selectedBackend"])
            self.assertFalse(
                decision["deviceEvidence"]["performanceSelectionAllowed"]
            )
            self.assertEqual(
                "M2_H_CANONICAL_ACCEPTANCE",
                decision["nextAllowedAction"],
            )
            verify_decision(
                decision,
                source,
                source_path=path,
                expected_git_commit=GIT_COMMIT,
            )

    def test_rejects_mixed_source_revision(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = source_document()
            source["checkoutCommit"] = "c" * 40
            path = write_source(pathlib.Path(tmp), source)
            with self.assertRaisesRegex(
                TransportDecisionError,
                "source/checkout commit mismatch",
            ):
                build_decision(
                    source,
                    source_path=path,
                    expected_git_commit=GIT_COMMIT,
                )

    def test_rejects_expected_commit_drift(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = write_source(pathlib.Path(tmp))
            source = source_document()
            with self.assertRaisesRegex(
                TransportDecisionError,
                "expected source revision",
            ):
                build_decision(
                    source,
                    source_path=path,
                    expected_git_commit="c" * 40,
                )

    def test_rejects_missing_or_duplicate_required_backend(self):
        with tempfile.TemporaryDirectory() as tmp:
            for backend_ids in (
                ["HTTP_URL_CONNECTION_ROUTE_BOUND"],
                [
                    "HTTP_URL_CONNECTION_ROUTE_BOUND",
                    "HTTP_URL_CONNECTION_ROUTE_BOUND",
                ],
            ):
                with self.subTest(backend_ids=backend_ids):
                    source = source_document()
                    source["backendIds"] = backend_ids
                    path = write_source(pathlib.Path(tmp), source)
                    with self.assertRaises(TransportDecisionError):
                        build_decision(
                            source,
                            source_path=path,
                            expected_git_commit=GIT_COMMIT,
                        )

    def test_rejects_lost_equivalence(self):
        with tempfile.TemporaryDirectory() as tmp:
            for field in (
                "correctnessEquivalent",
                "recoveryEquivalent",
                "routeBindingEquivalent",
            ):
                with self.subTest(field=field):
                    source = source_document()
                    source["aggregate"][field] = False
                    path = write_source(pathlib.Path(tmp), source)
                    with self.assertRaises(TransportDecisionError):
                        build_decision(
                            source,
                            source_path=path,
                            expected_git_commit=GIT_COMMIT,
                        )

    def test_rejects_emulator_performance_selection_permission(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = source_document()
            source["aggregate"]["performanceSelectionAllowed"] = True
            path = write_source(pathlib.Path(tmp), source)
            with self.assertRaises(TransportDecisionError):
                build_decision(
                    source,
                    source_path=path,
                    expected_git_commit=GIT_COMMIT,
                )

    def test_rejects_source_selected_backend(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = source_document()
            source["aggregate"]["selectedBackend"] = "PLATFORM_HTTP_ENGINE"
            path = write_source(pathlib.Path(tmp), source)
            with self.assertRaises(TransportDecisionError):
                build_decision(
                    source,
                    source_path=path,
                    expected_git_commit=GIT_COMMIT,
                )

    def test_rejects_n5_claim_upgrade(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = source_document()
            source["aggregate"]["n5ResilienceEffect"] = "OBSERVED_EFFECT_EQUIVALENT"
            for row in source["experiments"]:
                if row["experimentId"] == "N5_BURST_LOSS":
                    row["resilienceClaim"] = "OBSERVED_EFFECT_EQUIVALENT"
            path = write_source(pathlib.Path(tmp), source)
            with self.assertRaises(TransportDecisionError):
                build_decision(
                    source,
                    source_path=path,
                    expected_git_commit=GIT_COMMIT,
                )

    def test_verifier_rejects_decision_tampering(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = write_source(pathlib.Path(tmp))
            source = source_document()
            decision = build_decision(
                source,
                source_path=path,
                expected_git_commit=GIT_COMMIT,
            )
            broken = copy.deepcopy(decision)
            broken["decision"]["reasonCodes"][0] = "NO_PHYSICAL_DEVICE_EVIDENCE"
            with self.assertRaises(TransportDecisionError):
                verify_decision(
                    broken,
                    source,
                    source_path=path,
                    expected_git_commit=GIT_COMMIT,
                )

    def test_verifier_binds_source_artifact_digest(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            path = write_source(root)
            source = source_document()
            decision = build_decision(
                source,
                source_path=path,
                expected_git_commit=GIT_COMMIT,
            )
            path.write_text(path.read_text(encoding="utf-8") + " ", encoding="utf-8")
            with self.assertRaises(TransportDecisionError):
                verify_decision(
                    decision,
                    source,
                    source_path=path,
                    expected_git_commit=GIT_COMMIT,
                )


if __name__ == "__main__":
    unittest.main()
