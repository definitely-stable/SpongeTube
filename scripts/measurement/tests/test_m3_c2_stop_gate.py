"""Offline adversarial tests for the C2 evidence-stop decision gate."""
import copy
import json
import pathlib
import sys
import unittest

HERE = pathlib.Path(__file__).resolve().parents[1]
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))
from m3_c2_stop_gate import (
    PLAN_PATH, build_checkpoint, expected_plan, load_checkpoint, validate_plan,
)

class StopGateTests(unittest.TestCase):
    def setUp(self):
        self.plan = json.loads((ROOT / PLAN_PATH).read_text())
        self.ledgers = [
            json.loads((ROOT / entry["path"]).read_text())
            for entry in self.plan["evidence"]
        ]

    def assert_rejected(self, mutator):
        evidence = copy.deepcopy(self.ledgers)
        mutator(evidence)
        with self.assertRaises((ValueError, TypeError, KeyError)):
            build_checkpoint(self.plan, evidence)

    def test_frozen_plan_and_three_verified_evidence_ledgers(self):
        validate_plan(self.plan)
        self.assertEqual(expected_plan(), self.plan)
        self.assertEqual(load_checkpoint(ROOT),
                         build_checkpoint(self.plan, self.ledgers))
        report = load_checkpoint(ROOT)
        self.assertEqual("STOP_C2_SAME_ENVIRONMENT_ONLY", report["gateOutcome"])
        self.assertEqual("NOT_MADE_M3_A2", report["globalDecision"])
        self.assertEqual(False, report["productionAdapterAllowed"])
        self.assertEqual(16, report["metadataRequestsPerSlice"])
        self.assertEqual(0, report["mediaProbesPerSlice"])
        self.assertEqual(4, report["controlCount"])
        self.assertEqual(3, len(report["verifiedAgainst"]))

    def test_plan_forbids_new_provider_claims_or_budget_drift(self):
        for name, edit in [
            ("adapter", lambda x: x.update(productionAdapterAllowed=True)),
            ("global", lambda x: x.update(globalDecision="GO")),
            ("new-control", lambda x: x["controls"].append(x["controls"][0])),
            ("media", lambda x: x.update(maxMediaProbesPerSlice=8)),
            ("new-slice", lambda x: x["evidence"].pop()),
            ("new-oracle", lambda x: x["oracle"].update(version="latest")),
            ("new-gate", lambda x: x.update(gateOutcome="YOUTUBE_NO_GO")),
        ]:
            p = copy.deepcopy(self.plan)
            edit(p)
            with self.subTest(name=name), self.assertRaises(ValueError):
                validate_plan(p)

    def test_no_raw_payload_fields(self):
        self.assert_rejected(lambda docs:
            docs[2]["observations"][0].update(rawResponse="SECRET_SIGNED_URL"))
        self.assert_rejected(lambda docs:
            docs[1]["observations"][0]["postNormalizationResponses"][0]
                .update(url="SECRET_SIGNED_URL"))
        self.assert_rejected(lambda docs:
            docs[0].update(token="SECRET"))

    def test_media_and_metadata_are_not_reinterpreted(self):
        self.assert_rejected(lambda d:
            d[2]["observations"][0].update(mediaProbes=1))
        self.assert_rejected(lambda d:
            d[0]["observations"][0].update(metadataRequests=5))
        self.assert_rejected(lambda d:
            d[1]["observations"][0].update(http2xx=3))
        self.assert_rejected(lambda d:
            d[1].update(totalMetadataRequests=17))

    def test_no_inference_from_altered_d6_candidates_or_po_skip(self):
        self.assert_rejected(lambda d:
            d[1]["observations"][0].update(
                selectedInputCandidateState="PRESENT"))
        self.assert_rejected(lambda d:
            d[1]["observations"][0].update(poTokenSkipReportCalls=1))
        self.assert_rejected(lambda d:
            d[1]["observations"][0]["preNormalizationApiResponses"][0]
                .update(streamingData="ONE"))

    def test_no_inference_from_altered_d7_parsed_initial_or_manifest(self):
        self.assert_rejected(lambda d:
            d[2]["observations"][0].update(parsedEmbeddedInitialObservation="UNOBSERVED"))
        self.assert_rejected(lambda d:
            d[2]["observations"][0].update(
                parsedEmbeddedManifestState="PRESENT"))
        self.assert_rejected(lambda d:
            d[2]["observations"][0].update(
                parsedEmbeddedStreamingData="ONE"))
        self.assert_rejected(lambda d:
            d[2]["observations"][0].update(
                apiReturnManifestState="PRESENT"))
        self.assert_rejected(lambda d:
            d[2]["observations"][0].update(
                postNormalizationStreamingData=["ONE", "NULL"]))

    def test_binding_and_attestation_fail_closed(self):
        self.assert_rejected(lambda d:
            d[0].update(zipSha256="0"*64))
        self.assert_rejected(lambda d:
            d[2].update(gitCommit="0"*40))
        self.assert_rejected(lambda d:
            d[2]["independentAudit"].update(originalZipDigest="FAIL"))
        self.assert_rejected(lambda d:
            d[1]["files"].update({"raw.json": "0"*64}))
        self.assert_rejected(lambda d:
            d[2].update(globalDecision="PROVIDER_GO"))
        self.assert_rejected(lambda d:
            d[1].update(runnerImage="different@runner"))

    def test_ordinal_or_case_reassignment_cannot_make_a_positive_control(self):
        self.assert_rejected(lambda d:
            d[2]["observations"][0].update(caseClass="V1"))
        self.assert_rejected(lambda d:
            d[2]["observations"][0].update(ordinal=99))
        self.assert_rejected(lambda d:
            d[1]["observations"].reverse())
        self.assert_rejected(lambda d:
            d[0]["observations"][0].update(formatPopulation="NONEMPTY"))

    def test_strict_bool_vs_int_and_no_empty_checks(self):
        self.assert_rejected(lambda d:
            d[0]["observations"][0].update(mediaProbes=False))
        self.assert_rejected(lambda d:
            d[2].update(independentAudit={}))
        self.assert_rejected(lambda d:
            d[0].update(observations=[]))


if __name__ == "__main__":
    unittest.main()
