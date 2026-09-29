import json
import pathlib
import tempfile
import unittest

SCRIPT_DIR = pathlib.Path(__file__).resolve().parents[1]
REPO_ROOT = SCRIPT_DIR.parents[1]

import sys
sys.path.insert(0, str(SCRIPT_DIR))

from m2_contracts import scenario_sha256, validate_scenario_semantics
from m2_f_route_oracle import (
    CASE_CONFIGS,
    M2FOracleError,
    _verify_cross_identity,
    _verify_route_privacy,
    build_scenario,
    canonical_json_sha256,
    prepare,
    semantic_sequence,
)
from schema_subset import validate_instance


SCHEMAS = REPO_ROOT / ".work" / "schemas"


class M2FScenarioTest(unittest.TestCase):
    def test_all_canonical_cases_are_deterministic_actual_route_scenarios(self):
        for case_kind in CASE_CONFIGS:
            with self.subTest(case_kind=case_kind):
                scenario = build_scenario(case_kind)
                validate_scenario_semantics(scenario)
                self.assertEqual("ROUTE", scenario["primaryPlane"])
                self.assertTrue(scenario["requiresActualDefaultNetwork"])
                self.assertIsNone(scenario["randomSeed"])
                self.assertEqual(1, len(scenario["routeFaults"]))
                self.assertFalse(scenario["routeFaults"][0]["stochastic"])
                self.assertEqual(
                    scenario_sha256(scenario),
                    scenario_sha256(build_scenario(case_kind)),
                )

    def test_f2_semantic_digest_is_invariant_to_epoch_numbering(self):
        first = {
            "initialRouteEpoch": 1,
            "restoredRouteEpoch": 2,
        }
        second = {
            "initialRouteEpoch": 41,
            "restoredRouteEpoch": 900,
        }
        first_sequence = semantic_sequence(
            "F2_DEFAULT_ROUTE_LOSS_RESTORE",
            first,
        )
        second_sequence = semantic_sequence(
            "F2_DEFAULT_ROUTE_LOSS_RESTORE",
            second,
        )
        self.assertEqual(first_sequence, second_sequence)
        self.assertEqual(
            canonical_json_sha256(first_sequence),
            canonical_json_sha256(second_sequence),
        )

    def test_vpn_restore_and_direct_override_have_distinct_semantic_digests(self):
        restored = {
            "initialVpnEpoch": 2,
            "directReplacementEpoch": 3,
            "resumeEpoch": 4,
        }
        overridden = {
            "initialVpnEpoch": 2,
            "directReplacementEpoch": 3,
            "resumeEpoch": 3,
        }
        restored_digest = canonical_json_sha256(
            semantic_sequence("F3_VPN_CONTINUITY_RESTORE", restored),
        )
        override_digest = canonical_json_sha256(
            semantic_sequence(
                "F3_VPN_CONTINUITY_DIRECT_OVERRIDE",
                overridden,
            ),
        )
        self.assertNotEqual(restored_digest, override_digest)


class M2FManifestTest(unittest.TestCase):
    def test_prepare_binds_frozen_scenario_and_exact_commit(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            case = {
                "schemaVersion": 1,
                "phase": "M2-F2",
                "runId": "run-f4-test",
                "sessionId": "session-f4-test",
                "deviceApi": 36,
                "status": "PASS",
                "mediaPathUsesAdbReverse": False,
                "monitorStopped": True,
                "persistedExtentsBefore": [
                    {
                        "mediaAssetId": "fixture:test",
                        "extentId": "sentinel",
                        "trackId": "track",
                        "representationId": "rep",
                        "mediaStartUs": 0,
                        "mediaEndUs": 1,
                        "byteStart": 0,
                        "byteEndExclusive": 4,
                        "dependencyExtentIds": [],
                        "length": 4,
                        "sha256": "a" * 64,
                    }
                ],
                "persistedExtentsAfter": [
                    {
                        "mediaAssetId": "fixture:test",
                        "extentId": "sentinel",
                        "trackId": "track",
                        "representationId": "rep",
                        "mediaStartUs": 0,
                        "mediaEndUs": 1,
                        "byteStart": 0,
                        "byteEndExclusive": 4,
                        "dependencyExtentIds": [],
                        "length": 4,
                        "sha256": "a" * 64,
                    }
                ],
            }
            (root / "case.json").write_text(
                json.dumps(case),
                encoding="utf-8",
            )
            commit = "a" * 40
            prepare(
                root,
                "F2_DEFAULT_ROUTE_LOSS_RESTORE",
                commit,
                "2026-09-29T00:00:00Z",
            )
            scenario = json.loads((root / "scenario.json").read_text())
            manifest = json.loads((root / "run-manifest.json").read_text())

            self.assertEqual(commit, manifest["gitCommit"])
            self.assertEqual("ANDROID_DEFAULT_NETWORK", manifest["mediaPath"])
            self.assertEqual(scenario_sha256(scenario), manifest["scenario"]["hash"])
            self.assertEqual(case["runId"], manifest["runId"])
            self.assertEqual(case["sessionId"], manifest["sessionId"])
            validate_instance(
                json.loads(
                    (SCHEMAS / "m2-run-manifest-v1.schema.json")
                    .read_text(encoding="utf-8")
                ),
                manifest,
            )

    def test_cross_artifact_session_mismatch_is_rejected(self):
        case = {
            "runId": "run",
            "sessionId": "session-a",
            "recoveryChainId": "chain",
        }
        manifest = {"runId": "run", "sessionId": "session-a"}
        route = {"runId": "run", "sessionId": "session-b"}
        budget = {
            "runId": "run",
            "sessionId": "session-a",
            "events": [{"recoveryChainId": "chain"}],
        }
        failure = {
            "runId": "run",
            "sessionId": "session-a",
            "failures": [{"recoveryChainId": "chain"}],
        }
        fetch = [{"sessionId": "session-a"}]

        with self.assertRaises(M2FOracleError):
            _verify_cross_identity(
                case,
                manifest,
                route,
                budget,
                failure,
                fetch,
            )


class M2FPrivacyTest(unittest.TestCase):
    def test_vpn_restore_rejects_any_direct_allow_before_restored_vpn(self):
        case = {
            "directReplacementEpoch": 3,
            "resumeEpoch": 4,
        }
        route = {
            "policyEvaluations": [
                {
                    "routeEpoch": 3,
                    "decision": "PAUSE",
                    "reason": "VPN_CONTINUITY_REQUIRED",
                },
                {
                    "routeEpoch": 3,
                    "decision": "ALLOW",
                    "reason": "ROUTE_READY",
                },
                {
                    "routeEpoch": 4,
                    "decision": "ALLOW",
                    "reason": "ROUTE_READY",
                },
            ]
        }
        with self.assertRaises(M2FOracleError):
            _verify_route_privacy(
                "F3_VPN_CONTINUITY_RESTORE",
                case,
                route,
            )

    def test_direct_override_requires_same_route_event_watermark(self):
        case = {
            "directReplacementEpoch": 3,
            "resumeEpoch": 3,
            "directPauseRouteEventWatermark": 11,
            "resumeRouteEventWatermark": 12,
        }
        route = {
            "policyEvaluations": [
                {
                    "routeEpoch": 3,
                    "decision": "PAUSE",
                    "reason": "VPN_CONTINUITY_REQUIRED",
                },
                {
                    "routeEpoch": 3,
                    "decision": "ALLOW",
                    "reason": "EXPLICIT_DIRECT_OVERRIDE",
                },
            ]
        }
        with self.assertRaises(M2FOracleError):
            _verify_route_privacy(
                "F3_VPN_CONTINUITY_DIRECT_OVERRIDE",
                case,
                route,
            )


if __name__ == "__main__":
    unittest.main()
