"""Offline D7 falsification; no live provider calls."""
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
from m3_c2_d7 import (
    ManifestProbe, snapshot, field, build_plan, validate_plan,
    envelope, validate_document, run_d7, verify_bundle)
from test_m3_c2_d6 import D6Tests


class InheritedSearch:
    def _search_json(self, start_pattern, *args, **kwargs):
        return {"streamingData": {"hlsManifestUrl": "PRIVATE_SIGNED_URL",
                                  "formats": [{"url": "SECRET"}]}}


class YoutubeIE(InheritedSearch):
    _YT_INITIAL_PLAYER_RESPONSE_RE = "INITIAL_PATTERN"

    def _extract_player_response(self):
        return {"streamingData": {"dashManifestUrl": "PRIVATE_DASH_URL"}}

    def _extract_player_responses(self):
        return [self._extract_player_response()], None

    def _list_formats(self, video_id, microformats, video_details,
                      player_responses, player_url, duration=None):
        return None, None, [], {}


YoutubeIE.__module__ = "yt_dlp.extractor.youtube"


class D7Tests(unittest.TestCase):
    def setUp(self):
        self.d6 = D6Tests("test_committed_plan_matches_frozen_ancestry")
        self.d6.setUp()
        self.registry = self.d6.registry
        self.d3 = self.d6.d3
        self.d6_plan = self.d6.plan
        self.plan = build_plan(self.registry, self.d6_plan)
        self.sha = self.d6.sha
        self.runner = self.d6.runner
        self.ids = self.d6.ids

    def record(self, step):
        base = self.d6.make_record(step)
        return envelope(step, base, {
            "parsedEmbeddedInitial": [{
                "streamingData": "ONE", "formats": "ONE",
                "adaptiveFormats": "ABSENT", "hls": "STRING_PRESENT", "dash": "ABSENT"}],
            "apiReturns": [{
                "streamingData": "ONE", "formats": "ONE",
                "adaptiveFormats": "ABSENT", "hls": "ABSENT", "dash": "NULL"}],
            "postNormalization": [{
                "streamingData": "ONE", "formats": "ONE",
                "adaptiveFormats": "ABSENT", "hls": "ABSENT", "dash": "EMPTY"}],
            "selectedInput": [{
                "streamingData": "ONE", "formats": "ONE",
                "adaptiveFormats": "ABSENT", "hls": "ABSENT", "dash": "EMPTY"}],
            "selectionCalls": 1,
        })

    def test_committed_plan_and_no_provider_budgets(self):
        directory = ROOT / ".work" / "m3-a2"
        registry = load_registry(directory / "case-registry-v1.json")
        ancestors = [json.loads((directory / p).read_text()) for p in (
            "control-plan-v1.json", "c2-d0-plan-v1.json", "c2-d1-plan-v1.json",
            "c2-d2-plan-v1.json", "c2-d3-plan-v1.json", "c2-d4-plan-v1.json",
            "c2-d5-plan-v1.json", "c2-d6-plan-v1.json")]
        plan = json.loads((directory / "c2-d7-plan-v1.json").read_text())
        validate_plan(plan, registry, ancestors)
        self.assertEqual(plan, self.plan)
        self.assertEqual([2, 3, 5, 8], [x["ordinal"] for x in plan["steps"]])
        self.assertEqual(plan["maxTotalMediaProbes"], 0)

    def test_closed_shape_no_raw_values(self):
        x = snapshot({"streamingData": {"hlsManifestUrl": "SECRET_SIGNED_URL"}})
        self.assertEqual(x["hls"], "STRING_PRESENT")
        self.assertEqual(x["dash"], "ABSENT")
        self.assertEqual(snapshot({"streamingData": None})["hls"], "ABSENT")
        self.assertEqual(snapshot({"streamingData": "PRIVATE"})["hls"], "UNKNOWN")
        self.assertEqual(field({"hlsManifestUrl": 1}, "hlsManifestUrl"), "OTHER")
        self.assertNotIn("SECRET", json.dumps(x))

    def test_inherited_parser_and_direct_hooks_restore(self):
        cls = YoutubeIE
        orig = {k: vars(cls).get(k) for k in (
            "_search_json", "_extract_player_response",
            "_extract_player_responses", "_list_formats")}
        p = ManifestProbe()
        with p.installed(cls):
            ie = cls()
            ie._search_json("UNRELATED", "PRIVATE")
            self.assertEqual([], p.initial)
            ie._search_json(ie._YT_INITIAL_PLAYER_RESPONSE_RE, "PRIVATE")
            self.assertEqual(p.initial[0]["hls"], "STRING_PRESENT")
            responses, _ = ie._extract_player_responses()
            self.assertEqual(p.api[0]["dash"], "STRING_PRESENT")
            ie._list_formats("fake", {}, {}, responses, None)
            self.assertEqual(p.selected[0]["dash"], "STRING_PRESENT")
            with self.assertRaises(ValueError):
                ie._list_formats("fake", {}, {}, responses, None)
        self.assertEqual(orig, {k: vars(cls).get(k) for k in orig})
        self.assertNotIn("PRIVATE", json.dumps(p.document()))

    def test_restore_on_exception(self):
        cls = YoutubeIE
        original = dict(vars(cls))
        with self.assertRaises(RuntimeError):
            with ManifestProbe().installed(cls):
                raise RuntimeError("SECRET_EXCEPTION")
        self.assertEqual(original, dict(vars(cls)))

    def test_reject_raw_payloads_and_cross_stage_forgery(self):
        step = self.plan["steps"][0]
        valid = self.record(step)
        validate_document(valid, self.registry, self.d3, step, self.sha, self.runner)
        mutations = [
            lambda x: x["provenance"]["selectedInput"][0].update(
                {"hls": "PRIVATE_SIGNED_URL"}),
            lambda x: x["provenance"]["selectedInput"][0].update(
                {"rawUrl": "PRIVATE_SIGNED_URL"}),
            lambda x: x["provenance"]["apiReturns"][0].update(
                {"formats": "ABSENT"}),
            lambda x: x["provenance"].update({"selectionCalls": 2}),
            lambda x: x["provenance"]["parsedEmbeddedInitial"].append(
                x["provenance"]["parsedEmbeddedInitial"][0]),
        ]
        for mutate in mutations:
            candidate = copy.deepcopy(valid)
            mutate(candidate)
            with self.assertRaises(ValueError):
                validate_document(candidate, self.registry, self.d3,
                                  step, self.sha, self.runner)

    def test_no_persisted_malicious_observer(self):
        with tempfile.TemporaryDirectory() as td:
            out = pathlib.Path(td) / "evidence"
            def malicious(step):
                doc = self.record(step)
                doc["provenance"]["originalWebpage"] = "SECRET"
                return doc
            with self.assertRaises(ValueError):
                run_d7(registry=self.registry, d6_plan=self.d6_plan,
                       d3_plan=self.d3, plan=self.plan, ids=self.ids,
                       git_commit=self.sha, runner_image=self.runner,
                       output=out, observer=malicious)
            self.assertEqual([], list(out.iterdir()))

    def test_bundle_and_summary_forgery(self):
        with tempfile.TemporaryDirectory() as td:
            out = pathlib.Path(td) / "evidence"
            result = run_d7(registry=self.registry, d6_plan=self.d6_plan,
                            d3_plan=self.d3, plan=self.plan, ids=self.ids,
                            git_commit=self.sha, runner_image=self.runner,
                            output=out, observer=self.record)
            self.assertEqual(result["controlCount"], 4)
            self.assertEqual(result["totalMediaProbes"], 0)
            self.assertEqual(6, len(list(out.iterdir())))
            self.assertEqual(result, verify_bundle(
                registry=self.registry, d6_plan=self.d6_plan,
                d3_plan=self.d3, plan=self.plan, ids=self.ids,
                git_commit=self.sha, runner_image=self.runner, root=out))
            for file in out.iterdir():
                data = file.read_text()
                for token in ("PRIVATE_SIGNED_URL", *self.ids.values()):
                    self.assertNotIn(token, data)
            summary = out / "summary-v1.json"
            altered = json.loads(summary.read_text())
            altered["globalDecision"] = "GO"
            summary.write_text(json.dumps(altered))
            manifest = out / "manifest-v1.json"
            man = json.loads(manifest.read_text())
            for item in man["files"]:
                if item["path"] == summary.name:
                    item["sha256"] = hashlib.sha256(summary.read_bytes()).hexdigest()
            manifest.write_text(json.dumps(man))
            with self.assertRaises(ValueError):
                verify_bundle(registry=self.registry, d6_plan=self.d6_plan,
                              d3_plan=self.d3, plan=self.plan, ids=self.ids,
                              git_commit=self.sha, runner_image=self.runner, root=out)


if __name__ == "__main__":
    unittest.main()
