"""Offline D6 format provenance falsification; no provider I/O."""
import copy
import hashlib
import json
import pathlib
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parents[1]
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))
from m3_matrix import load_registry
from m3_c2_d6 import (
    ShapeProbe, bucket, shape, selected_population, build_plan, validate_plan, envelope,
    validate_document, run_d6, verify_bundle,
)
from test_m3_c2_d5 import D5Tests


class FakeYoutube:
    __module__ = "yt_dlp.extractor.youtube"

    def _extract_player_response(self):
        return {"streamingData": {"formats": [{"secret": "PRIVATE_URL"}]}}

    def _extract_player_responses(self):
        return [self._extract_player_response()], None

    def _extract_formats_and_subtitles(self):
        yield {"format_id": "18", "url": "SECRET_SIGNED_URL"}
        yield {}

    def _list_formats(self):
        responses, _ = self._extract_player_responses()
        return None, None, list(self._extract_formats_and_subtitles())[:-1], {}

    def _report_pot_format_skipped(self):
        return "SECRET_WARNING"


FakeYoutube.__name__ = "YoutubeIE"


class D6Tests(unittest.TestCase):
    def setUp(self):
        self.f = D5Tests("test_committed_frozen_plan_and_ancestors")
        self.f.setUp()
        self.registry = self.f.registry
        self.d3 = self.f.d3
        self.d5_plan = self.f.plan
        self.plan = build_plan(self.registry, self.d5_plan)
        self.ids, self.sha, self.runner = self.f.ids, self.f.sha, self.f.runner

    def make_record(self, step):
        d5_record = self.f.make_document(step)
        return envelope(step, d5_record, {
            "preNormalizationApiResponses": [{
                "streamingData": "ONE", "formats": "ONE", "adaptiveFormats": "ABSENT"}],
            "postNormalizationResponses": [{
                "streamingData": "ONE", "formats": "ONE", "adaptiveFormats": "ABSENT"}],
            "selectedInputResponses": [{
                "streamingData": "ONE", "formats": "ONE", "adaptiveFormats": "ABSENT"}],
            "selectionCalls": 1, "emittedFormatMappings": 0,
            "poTokenSkipReportCalls": 1,
        })

    def test_committed_plan_matches_frozen_ancestry(self):
        root = ROOT / ".work" / "m3-a2"
        registry = load_registry(root / "case-registry-v1.json")
        ancestors = [json.loads((root / p).read_text()) for p in (
            "control-plan-v1.json", "c2-d0-plan-v1.json", "c2-d1-plan-v1.json",
            "c2-d2-plan-v1.json", "c2-d3-plan-v1.json", "c2-d4-plan-v1.json",
            "c2-d5-plan-v1.json")]
        plan = json.loads((root / "c2-d6-plan-v1.json").read_text())
        validate_plan(plan, registry, ancestors)
        self.assertEqual(self.plan, plan)
        self.assertEqual([2, 3, 5, 8], [s["ordinal"] for s in plan["steps"]])
        self.assertEqual(0, plan["maxTotalMediaProbes"])

    def test_bounded_shapes_and_synthetic_key_warning(self):
        self.assertEqual("UNKNOWN", bucket("NOT_A_LIST"))
        self.assertEqual("ABSENT", shape({})["streamingData"])
        self.assertEqual("NULL", shape({"streamingData": None})["streamingData"])
        self.assertEqual("EMPTY", shape({"streamingData": {}})["streamingData"])
        self.assertEqual("ONE", shape({"streamingData": {"formats": [1]}})["formats"])
        self.assertEqual("OVER_64", bucket([None] * 65))
        self.assertEqual("UNKNOWN", shape({"streamingData": "PRIVATE_JSON"})["formats"])
        self.assertEqual("UNKNOWN", selected_population([]))
        self.assertEqual("UNKNOWN", selected_population([shape({"streamingData": 12})]))
        self.assertEqual("NO_CANDIDATES_IN_SELECTED_INPUT",
                         selected_population([shape({"streamingData": {}})]))
        self.assertEqual("PRESENT", selected_population([
            shape({"streamingData": {"adaptiveFormats": [{"url": "SECRET"}]}})]))

    def test_hooks_preserve_generator_order_and_restore_on_exception(self):
        cls = FakeYoutube
        orig = {k: vars(cls)[k] for k in self.plan["observedMethods"]}
        probe = ShapeProbe()
        with probe.installed(cls):
            instance = cls()
            a = instance._extract_player_response()
            self.assertEqual("ONE", shape(a)["formats"])
            self.assertEqual(len(instance._extract_player_responses()[0]), 1)
            self.assertEqual(instance._report_pot_format_skipped(), "SECRET_WARNING")
            self.assertEqual(
                [x.get("format_id") for x in instance._extract_formats_and_subtitles()],
                ["18", None])
            with self.assertRaises(ValueError):
                instance._list_formats()
        self.assertEqual({k: vars(cls)[k] for k in orig}, orig)
        self.assertNotIn("SECRET", json.dumps(probe.document()))
        self.assertEqual(1, probe.yields)
        self.assertEqual(1, probe.po_reports)

    def test_provider_like_payload_is_not_persisted(self):
        p = ShapeProbe()
        with p.installed(FakeYoutube):
            FakeYoutube()._extract_player_response()
        data = json.dumps(p.document())
        for secret in ("PRIVATE_URL", "SECRET", "format_id", "url"):
            self.assertNotIn(secret, data)

    def test_validate_rejects_unsafe_fields_and_false_causality(self):
        step = self.plan["steps"][0]
        valid = self.make_record(step)
        validate_document(valid, self.registry, self.d3, step, self.sha, self.runner)
        for name, mutate in [
            ("rawLeak", lambda v: v["structural"].update({"url": "SECRET_URL"})),
            ("forgedClaim", lambda v: v["structural"]["selectedInputResponses"][0].update(
                {"jsRequired": "REQUIRED"})),
            ("badBucket", lambda v: v["structural"]["selectedInputResponses"][0].update(
                {"formats": "JS_REQUIRED"})),
            ("overCount", lambda v: v["structural"].update(
                {"emittedFormatMappings": 257})),
            ("badBoolean", lambda v: v["structural"].update({"selectionCalls": True})),
            ("contradictsD5", lambda v: v["structural"].update(
                {"emittedFormatMappings": 1})),
            ("missingSelection", lambda v: v["structural"].update(
                {"selectionCalls": 0})),
        ]:
            candidate = copy.deepcopy(valid)
            mutate(candidate)
            with self.subTest(name=name), self.assertRaises(ValueError):
                validate_document(candidate, self.registry, self.d3,
                                  step, self.sha, self.runner)

    def test_frozen_bundle_and_independent_recalculation(self):
        with tempfile.TemporaryDirectory() as td:
            path = pathlib.Path(td) / "evidence"
            result = run_d6(
                registry=self.registry, d5_plan=self.d5_plan, d3_plan=self.d3,
                plan=self.plan, ids=self.ids, git_commit=self.sha,
                runner_image=self.runner, output=path, observer=self.make_record)
            self.assertEqual(4, result["controlCount"])
            self.assertEqual("NOT_MADE_M3_A2", result["globalDecision"])
            self.assertEqual(result, verify_bundle(
                registry=self.registry, d5_plan=self.d5_plan, d3_plan=self.d3,
                plan=self.plan, ids=self.ids, git_commit=self.sha,
                runner_image=self.runner, root=path))
            self.assertEqual(6, len(list(path.iterdir())))
            for file in path.iterdir():
                for secret in ("SECRET_SIGNED_URL", *self.ids.values()):
                    self.assertNotIn(secret, file.read_text())
            summary = path / "summary-v1.json"
            changed = json.loads(summary.read_text())
            changed["globalDecision"] = "PROVIDER_GO"
            summary.write_text(json.dumps(changed))
            man = path / "manifest-v1.json"
            m = json.loads(man.read_text())
            for e in m["files"]:
                if e["path"] == summary.name:
                    e["sha256"] = hashlib.sha256(summary.read_bytes()).hexdigest()
            man.write_text(json.dumps(m))
            with self.assertRaises(ValueError):
                verify_bundle(
                    registry=self.registry, d5_plan=self.d5_plan,
                    d3_plan=self.d3, plan=self.plan, ids=self.ids,
                    git_commit=self.sha, runner_image=self.runner, root=path)


if __name__ == "__main__":
    unittest.main()
