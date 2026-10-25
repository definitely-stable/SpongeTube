"""D4 closed-phase instrumentation and evidence falsification; no live provider IO."""
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

from test_m3_batch import inputs, synthetic_registry
from test_m3_c2_d1 import FakeOracle
from test_m3_c2_d2 import D2Oracle
from m3_matrix import build_plan as build_matrix_plan, load_registry
from m3_controls import build_control_plan
from m3_c2_d0 import build_plan as build_d0_plan
from m3_c2_d1 import build_plan as build_d1_plan
from m3_c2_d2 import build_plan as build_d2_plan
from m3_c2_d3 import (build_plan as build_d3_plan, observe_versioned_cell,
                      observation_envelope, ORACLES)
from m3_c2_d4 import (HOOKS, SAFE_DIAGNOSTIC_PHASES, StageObserver, build_plan, envelope, run_d4,
                      validate_document, validate_plan, validate_trace,
                      verify_bundle, MAX_STAGE_EVENTS)


class YoutubeIE:
    def _real_extract(self):
        return self._initial_extract()

    def _initial_extract(self):
        return self._extract_player_responses()

    def _download_initial_webpage(self):
        return None

    def _download_initial_data(self):
        return None

    def _get_requested_clients(self):
        return []

    def _extract_player_responses(self):
        raise ValueError("SECRET_SIGNED_LINK token ID")

    def _extract_player_response(self):
        return None

    def _list_formats(self):
        return None


YoutubeIE.__module__ = "yt_dlp.extractor.youtube._video"


class D4Tests(unittest.TestCase):
    def setUp(self):
        self.registry = synthetic_registry()
        self.c1 = build_control_plan(self.registry, build_matrix_plan(self.registry))
        self.d0 = build_d0_plan(self.registry, self.c1)
        self.d1 = build_d1_plan(self.registry, self.c1, self.d0)
        self.d2 = build_d2_plan(self.registry, self.c1, self.d0, self.d1)
        self.d3 = build_d3_plan(self.registry, self.c1, self.d0, self.d1, self.d2)
        self.plan = build_plan(self.registry, self.d3)
        a = inputs()
        self.ids = {"V0": a.v0_video_id, "V1": a.v1_video_id, "V2": a.v2_video_id}
        self.sha, self.runner = a.git_commit, a.runner_image
        FakeOracle.network_calls = 0
        FakeOracle.configured_modes = []
        FakeOracle.configured_clients = []
        D2Oracle.behavior = "WRAPPED"

    def trace(self):
        tracer = StageObserver()
        original = YoutubeIE.__dict__["_real_extract"]
        with self.assertRaisesRegex(ValueError, "SECRET_SIGNED_LINK"):
            with tracer.installed(YoutubeIE):
                YoutubeIE()._real_extract()
        self.assertIs(YoutubeIE.__dict__["_real_extract"], original)
        validate_trace(tracer.events, False, "EXTRACT_INFO_RAISED")
        return tracer.events

    def observer(self, step):
        tracer = self.trace()
        oracle = ORACLES["B"]
        cell = next(x for x in self.d3["cells"]
                    if x["caseClass"] == step["caseClass"] and x["lane"] == step["lane"])
        obs = observe_versioned_cell(
            registry=self.registry, cell=cell, arm="B",
            raw_id=self.ids[step["caseClass"]], git_commit=self.sha,
            runner_image=self.runner,
            oracle_loader=lambda: (D2Oracle, oracle["version"], oracle["revision"]),
        )
        self.assertEqual("EXTRACT_INFO_RAISED", obs["extractorStage"])
        return envelope(step, observation_envelope(step, obs), tracer)

    def run_fixture(self, output):
        return run_d4(
            registry=self.registry, d3_plan=self.d3, plan=self.plan,
            ids=self.ids, git_commit=self.sha, runner_image=self.runner,
            output=output, observer=self.observer,
        )

    def test_frozen_committed_plan(self):
        folder = ROOT / ".work" / "m3-a2"
        registry = load_registry(folder / "case-registry-v1.json")
        plans = [json.loads((folder / path).read_text(encoding="utf-8"))
                 for path in ("control-plan-v1.json", "c2-d0-plan-v1.json",
                              "c2-d1-plan-v1.json", "c2-d2-plan-v1.json",
                              "c2-d3-plan-v1.json", "c2-d4-plan-v1.json")]
        validate_plan(plans[-1], registry, *plans[:-1])
        self.assertEqual(4, len(plans[-1]["steps"]))
        self.assertTrue(all(s["arm"] == "B" for s in plans[-1]["steps"]))
        self.assertEqual([2, 3, 5, 8], [s["ordinal"] for s in plans[-1]["steps"]])
        self.assertEqual(128, plans[-1]["maxTotalMetadataRequests"])
        self.assertEqual(0, plans[-1]["maxTotalMediaProbes"])

    def test_stage_observer_unwinds_nested_failures_without_payload(self):
        trace = self.trace()
        self.assertEqual([
            ("VIDEO_EXTRACT", "ENTER"), ("INITIAL_EXTRACT", "ENTER"),
            ("PLAYER_RESPONSES", "ENTER"), ("PLAYER_RESPONSES", "RAISE"),
            ("INITIAL_EXTRACT", "RAISE"), ("VIDEO_EXTRACT", "RAISE"),
        ], [(x["stage"], x["outcome"]) for x in trace])
        self.assertNotIn("SECRET_SIGNED_LINK", json.dumps(trace))

    def test_instrumentation_restores_even_if_setup_fails(self):
        class YoutubeIEBad:
            pass
        YoutubeIEBad.__name__ = "YoutubeIE"
        YoutubeIEBad.__module__ = "yt_dlp.extractor.youtube._video"
        with self.assertRaises(ValueError):
            with StageObserver().installed(YoutubeIEBad):
                pass
        original = YoutubeIE.__dict__["_real_extract"]
        with StageObserver().installed(YoutubeIE):
            self.assertIsNot(YoutubeIE.__dict__["_real_extract"], original)
        self.assertIs(YoutubeIE.__dict__["_real_extract"], original)

    def test_untrusted_phase_and_unbalanced_trace_rejected(self):
        trace = self.trace()
        for altered in (
            trace[:-1],
            trace[1:],
            trace + [{"ordinal": 7, "stage": "JS_REQUIRED", "outcome": "RETURN"}],
            trace + [{"ordinal": 7, "stage": "VIDEO_EXTRACT", "outcome": "ENTER",
                      "url": "PRIVATE"}],
            trace + [{"ordinal": 7, "stage": "VIDEO_EXTRACT", "outcome": "ENTER"}],
        ):
            with self.subTest(altered=altered), self.assertRaises(ValueError):
                validate_trace(altered, False, "EXTRACT_INFO_RAISED")
        with self.assertRaises(ValueError):
            validate_trace([], False, "EXTRACT_INFO_RAISED")
        with self.assertRaises(ValueError):
            validate_trace(trace, True, "MAPPING_RETURNED")

    def test_diagnostic_phases_are_fixed_and_payload_free(self):
        self.assertEqual({
            "READ_PLAN", "VALIDATE_PLAN", "REFERENCE_FIXTURE", "CHILD_DISPATCH",
            "LOAD_ORACLE", "BIND_STAGE_HOOKS", "RUN_REFERENCE",
            "VALIDATE_TRACE", "WRITE_EVIDENCE", "VERIFY_BUNDLE",
        }, SAFE_DIAGNOSTIC_PHASES)
        self.assertFalse(any("TOKEN" in stage or "SECRET" in stage or
                             "URL" in stage for stage in SAFE_DIAGNOSTIC_PHASES))

    def test_event_budget(self):
        recorder = StageObserver()
        for _ in range(MAX_STAGE_EVENTS):
            recorder.event("VIDEO_EXTRACT", "ENTER")
        with self.assertRaises(ValueError):
            recorder.event("VIDEO_EXTRACT", "RAISE")

    def test_no_d4_network_before_all_identities_pass(self):
        bad = dict(self.ids)
        bad["V2"] = "INVALID"
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaises(ValueError):
                run_d4(registry=self.registry, d3_plan=self.d3, plan=self.plan,
                       ids=bad, git_commit=self.sha, runner_image=self.runner,
                       output=pathlib.Path(temp), observer=self.observer)
        self.assertEqual(0, FakeOracle.network_calls)

    def test_exact_bundle_and_raw_secrets_redacted(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp) / "bundle"
            summary = self.run_fixture(root)
            self.assertEqual(4, summary["controlCount"])
            self.assertEqual(4, summary["totalMetadataRequests"])
            self.assertEqual(0, summary["totalMediaProbes"])
            self.assertEqual("NOT_MADE_M3_A2", summary["globalDecision"])
            self.assertEqual(4, FakeOracle.network_calls)
            self.assertTrue(all(x["firstRaisedPhase"] == "PLAYER_RESPONSES"
                                for x in summary["observations"]))
            self.assertEqual(summary, verify_bundle(
                registry=self.registry, d3_plan=self.d3, plan=self.plan,
                ids=self.ids, git_commit=self.sha, runner_image=self.runner,
                root=root))
            self.assertEqual(6, len(list(root.iterdir())))
            for file in root.iterdir():
                content = file.read_text(encoding="utf-8")
                for forbidden in ("SECRET_SIGNED_LINK", "PRIVATE_SIGNED_URL",
                                  "SECRET_SESSION", "www.youtube.com", *self.ids.values()):
                    self.assertNotIn(forbidden, content)

    def test_closed_plan_rejects_JS_token_more_hooks(self):
        for mutation in ("token", "js", "events", "profile", "missing", "version", "budget"):
            plan = copy.deepcopy(self.plan)
            if mutation == "token":
                plan["mode"]["poTokenProvider"] = "ENABLED"
            elif mutation == "js":
                plan["mode"]["jsRuntime"] = "ENABLED"
            elif mutation == "events":
                plan["maxStageEventsPerObservation"] += 1
            elif mutation == "profile":
                plan["steps"][0]["lane"] = "TV"
            elif mutation == "missing":
                plan["steps"].pop()
            elif mutation == "version":
                plan["oracle"]["version"] = "latest"
            else:
                plan["maxTotalMetadataRequests"] += 1
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                validate_plan(plan, self.registry, self.c1, self.d0,
                              self.d1, self.d2, self.d3)

    def test_bundle_tampering_even_when_manifest_recomputed(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp) / "bundle"
            self.run_fixture(root)
            fname = "summary-v1.json"
            data = json.loads((root / fname).read_text())
            data["globalDecision"] = "JS_REQUIRED"
            (root / fname).write_text(json.dumps(data))
            manifest = json.loads((root / "manifest-v1.json").read_text())
            for item in manifest["files"]:
                if item["path"] == fname:
                    item["sha256"] = hashlib.sha256((root / fname).read_bytes()).hexdigest()
            (root / "manifest-v1.json").write_text(json.dumps(manifest))
            with self.assertRaises(ValueError):
                verify_bundle(registry=self.registry, d3_plan=self.d3, plan=self.plan,
                              ids=self.ids, git_commit=self.sha,
                              runner_image=self.runner, root=root)


if __name__ == "__main__":
    unittest.main()
