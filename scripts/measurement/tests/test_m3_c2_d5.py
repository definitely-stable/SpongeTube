"""Offline falsification of D5: pinned hook restoration, privacy and evidence."""
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
from test_m3_c2_d4 import D4Tests, YoutubeIE as StageFake
from m3_matrix import load_registry
from m3_c2_d5 import (
    PLAN_ID, PROTOCOL, TerminalProbe, build_plan, envelope, run_d5,
    validate_document, validate_plan, verify_bundle,
)
from m3_c2_d3 import ORACLES


class TerminalBase:
    def raise_no_formats(self, msg=None, expected=False, video_id=None):
        raise ValueError("SECRET_EXCEPTION_MESSAGE; URL with cookies")

    def report_drm(self, video_id=None):
        self.raise_no_formats("secret drm", expected=True)


class YoutubeIE(StageFake, TerminalBase):
    def _list_formats(self):
        return None, "not_live", [], None


YoutubeIE.__module__ = "yt_dlp.extractor.youtube"


class D5Tests(unittest.TestCase):
    def setUp(self):
        self.fixture = D4Tests("test_frozen_committed_plan")
        self.fixture.setUp()
        f = self.fixture
        self.registry, self.d3, self.d4 = f.registry, f.d3, f.plan
        self.plan = build_plan(self.registry, self.d4)
        self.ids, self.sha, self.runner = f.ids, f.sha, f.runner

    def make_document(self, step):
        original = self.fixture.observer(step)  # Typed synthetic D3/D4 evidence
        # Reproduce the observed D4 post-format exception pattern; the base
        # fixture deliberately raises earlier inside PLAYER_RESPONSES.
        ordered = [
            ("VIDEO_EXTRACT", "ENTER"), ("INITIAL_EXTRACT", "ENTER"),
            ("WEBPAGE_FETCH", "ENTER"), ("WEBPAGE_FETCH", "RETURN"),
            ("INITIAL_DATA", "ENTER"), ("INITIAL_DATA", "RETURN"),
            ("CLIENT_SELECTION", "ENTER"), ("CLIENT_SELECTION", "RETURN"),
            ("PLAYER_RESPONSES", "ENTER"), ("PLAYER_RESPONSE", "ENTER"),
            ("PLAYER_RESPONSE", "RETURN"), ("PLAYER_RESPONSES", "RETURN"),
            ("INITIAL_EXTRACT", "RETURN"), ("FORMAT_SELECTION", "ENTER"),
            ("FORMAT_SELECTION", "RETURN"), ("VIDEO_EXTRACT", "RAISE"),
        ]
        original["stageTrace"] = [
            {"ordinal": i, "stage": stage, "outcome": outcome}
            for i, (stage, outcome) in enumerate(ordered, 1)
        ]
        return envelope(step, original, {
            "formatPopulation": "EMPTY", "formatObservations": 1,
            "raiseNoFormatsCalls": 1, "reportDrmCalls": 0,
        })

    def test_committed_frozen_plan_and_ancestors(self):
        folder = ROOT / ".work" / "m3-a2"
        reg = load_registry(folder / "case-registry-v1.json")
        inherited = [json.loads((folder / path).read_text())
                     for path in ("control-plan-v1.json", "c2-d0-plan-v1.json",
                                  "c2-d1-plan-v1.json", "c2-d2-plan-v1.json",
                                  "c2-d3-plan-v1.json", "c2-d4-plan-v1.json")]
        actual = json.loads((folder / "c2-d5-plan-v1.json").read_text())
        validate_plan(actual, reg, inherited)
        self.assertEqual(actual, build_plan(reg, inherited[-1]))
        self.assertEqual([2, 3, 5, 8], [x["ordinal"] for x in actual["steps"]])
        self.assertEqual(128, actual["maxTotalMetadataRequests"])
        self.assertEqual(0, actual["maxTotalMediaProbes"])

    def test_terminal_hook_observes_only_empty_boolean_and_restores_methods(self):
        target = YoutubeIE
        orig = {name: target.__dict__.get(name) for name in
                ("_list_formats", "raise_no_formats", "report_drm")}
        p = TerminalProbe()
        with p.installed(target):
            self.assertIsNone(YoutubeIE()._list_formats()[0])
            with self.assertRaises(ValueError):
                YoutubeIE().raise_no_formats("SECRET_SIGNED_URL")
            self.assertEqual("EMPTY", p.format_family)
            self.assertEqual(1, p.no_formats_calls)
        self.assertEqual(orig, {name: target.__dict__.get(name) for name in orig})
        data = json.dumps(p.document())
        self.assertNotIn("SECRET", data)
        self.assertNotIn("cookie", data)
        self.assertEqual(set(json.loads(data)), {
            "formatPopulation", "formatObservations",
            "raiseNoFormatsCalls", "reportDrmCalls",
        })

    def test_report_drm_delegation_and_no_raw_exception(self):
        p = TerminalProbe()
        with self.assertRaises(ValueError):
            with p.installed(YoutubeIE):
                YoutubeIE()._list_formats()
                YoutubeIE().report_drm("PRIVATE_VIDEO_ID")
        self.assertEqual(1, p.drm_calls)
        self.assertEqual(1, p.no_formats_calls)
        self.assertNotIn("PRIVATE_VIDEO_ID", json.dumps(p.document()))

    def test_reject_class_lookalike_and_hooks_not_inherited(self):
        bad = type("YoutubeIE", (), {})
        bad.__module__ = "yt_dlp.extractor.youtube"
        with self.assertRaises(ValueError):
            with TerminalProbe().installed(bad):
                pass
        original = YoutubeIE.__module__
        try:
            YoutubeIE.__module__ = "yt_dlp.extractor.youtube._video"
            with self.assertRaises(ValueError):
                with TerminalProbe().installed(YoutubeIE):
                    pass
        finally:
            YoutubeIE.__module__ = original

    def test_forged_terminal_values_and_inner_reference_rejected(self):
        step = self.plan["steps"][0]
        doc = self.make_document(step)
        validate_document(doc, self.registry, self.d3, step, self.sha, self.runner)
        for label, poison in [
            ("rawInput", {"videoId": "SECRET"}),
            ("fakeClaim", {"formatPopulation": "JS_REQUIRED"}),
            ("falseCount", {"formatObservations": 2}),
            ("negative", {"raiseNoFormatsCalls": -1}),
            ("drmVsCalls", {"reportDrmCalls": 2}),
            ("drmVsFormats", {"formatPopulation": "NONEMPTY", "reportDrmCalls": 1}),
            ("oversized", {"raiseNoFormatsCalls": 9}),
            ("type", {"raiseNoFormatsCalls": True}),
        ]:
            mutated = copy.deepcopy(doc)
            mutated["terminal"].update(poison)
            with self.subTest(label=label), self.assertRaises(ValueError):
                validate_document(mutated, self.registry, self.d3, step,
                                  self.sha, self.runner)
        changed = copy.deepcopy(doc)
        changed["reference"]["reference"]["observation"]["gitCommit"] = "0" * 40
        with self.assertRaises(ValueError):
            validate_document(changed, self.registry, self.d3, step,
                              self.sha, self.runner)

    def test_reject_changed_plan_or_oracle(self):
        for label in ("js", "token", "case", "budget", "version", "extra"):
            p = copy.deepcopy(self.plan)
            if label == "js":
                p["mode"]["jsRuntime"] = "ENABLED"
            elif label == "token":
                p["mode"]["poTokenProvider"] = "CONFIGURED"
            elif label == "case":
                p["steps"][0]["caseClass"] = "V1"
            elif label == "budget":
                p["maxTotalMetadataRequests"] = 200
            elif label == "version":
                p["oracle"]["version"] = "latest"
            else:
                p["terminalMethods"].append("unsafe")
            with self.subTest(label=label), self.assertRaises(ValueError):
                validate_plan(p, self.registry,
                              [self.fixture.c1, self.fixture.d0, self.fixture.d1,
                               self.fixture.d2, self.d3, self.d4])

    def test_four_synthetic_observations_bundle_and_privacy(self):
        with tempfile.TemporaryDirectory() as td:
            dest = pathlib.Path(td) / "data"
            result = run_d5(registry=self.registry, d4_plan=self.d4,
                            d3_plan=self.d3, plan=self.plan, ids=self.ids,
                            git_commit=self.sha, runner_image=self.runner,
                            output=dest, observer=self.make_document)
            self.assertEqual(4, result["controlCount"])
            self.assertEqual(0, result["totalMediaProbes"])
            self.assertEqual("NOT_MADE_M3_A2", result["globalDecision"])
            self.assertEqual(4, result["totalMetadataRequests"])
            self.assertEqual(result, verify_bundle(
                registry=self.registry, d4_plan=self.d4, d3_plan=self.d3,
                plan=self.plan, ids=self.ids, git_commit=self.sha,
                runner_image=self.runner, root=dest))
            self.assertEqual(6, len(list(dest.iterdir())))
            for p in dest.iterdir():
                for secret in ("SECRET_SIGNED_URL", "PRIVATE_VIDEO_ID",
                               "www.youtube.com", *self.ids.values()):
                    self.assertNotIn(secret, p.read_text())

    def test_recalculated_digest_cannot_override_summary(self):
        with tempfile.TemporaryDirectory() as td:
            out = pathlib.Path(td) / "bundle"
            run_d5(registry=self.registry, d4_plan=self.d4, d3_plan=self.d3,
                   plan=self.plan, ids=self.ids, git_commit=self.sha,
                   runner_image=self.runner, output=out, observer=self.make_document)
            fp = out / "summary-v1.json"
            content = json.loads(fp.read_text())
            content["globalDecision"] = "PROVIDER_GO"
            fp.write_text(json.dumps(content))
            manifest = json.loads((out / "manifest-v1.json").read_text())
            for e in manifest["files"]:
                if e["path"] == fp.name:
                    e["sha256"] = hashlib.sha256(fp.read_bytes()).hexdigest()
            (out / "manifest-v1.json").write_text(json.dumps(manifest))
            with self.assertRaises(ValueError):
                verify_bundle(registry=self.registry, d4_plan=self.d4,
                              d3_plan=self.d3, plan=self.plan, ids=self.ids,
                              git_commit=self.sha, runner_image=self.runner, root=out)

    def test_invalid_case_fails_before_oracle(self):
        invalid = dict(self.ids)
        invalid["V2"] = "not-a-valid-id"
        with tempfile.TemporaryDirectory() as td, self.assertRaises(ValueError):
            run_d5(registry=self.registry, d4_plan=self.d4,
                   d3_plan=self.d3, plan=self.plan, ids=invalid,
                   git_commit=self.sha, runner_image=self.runner,
                   output=pathlib.Path(td), observer=self.make_document)


if __name__ == "__main__":
    unittest.main()
