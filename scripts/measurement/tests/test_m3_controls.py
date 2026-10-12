"""M3-A2-C1 paired control falsification without provider network access."""
import copy
import hashlib
import json
import pathlib
import sys
import tempfile
import unittest

SCRIPT_DIR = pathlib.Path(__file__).resolve().parents[1]
REPO_ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from m3_batch import M3BatchError
from m3_controls import (
    ControlPilotError, PAIRS, build_control_plan, execute_controls,
    validate_control_plan,
)
from m3_matrix import M3MatrixError, build_plan
from test_m3_batch import FakeRunner, inputs, synthetic_registry


class ControlPlanTests(unittest.TestCase):
    def setUp(self):
        self.registry = synthetic_registry()
        self.matrix_plan = build_plan(self.registry)
        self.plan = build_control_plan(self.registry, self.matrix_plan)

    def test_committed_plan_matches_exact_repository_registry(self):
        from m3_matrix import load_registry
        root = REPO_ROOT / ".work" / "m3-a2"
        registry = load_registry(root / "case-registry-v1.json")
        matrix_plan = json.loads((root / "matrix-plan-v2.json").read_text(encoding="utf-8"))
        committed = json.loads((root / "control-plan-v1.json").read_text(encoding="utf-8"))
        validate_control_plan(committed, registry, matrix_plan)

    def test_plan_is_exact_four_cell_subset_of_original_matrix(self):
        self.assertEqual(4, len(self.plan["cells"]))
        self.assertEqual(PAIRS, tuple((c["caseClass"], c["lane"]) for c in self.plan["cells"]))
        self.assertEqual(128, self.plan["maxMetadataRequestsTotal"])
        self.assertEqual(32, self.plan["maxMediaProbesTotal"])
        self.assertFalse(self.plan["fullMatrixAccepted"])
        self.assertEqual("NOT_MADE_M3_A2", self.plan["globalDecision"])

    def test_plan_rejects_lane_reorder_or_added_probe(self):
        changed = copy.deepcopy(self.plan)
        changed["cells"].reverse()
        with self.assertRaises(ControlPilotError):
            validate_control_plan(changed, self.registry, self.matrix_plan)
        changed = copy.deepcopy(self.plan)
        changed["cells"].append(copy.deepcopy(changed["cells"][0]))
        with self.assertRaises(ControlPilotError):
            validate_control_plan(changed, self.registry, self.matrix_plan)

    def test_plan_rejects_weakening_unknown_or_adoption(self):
        for key, value in (
            ("fullMatrixAccepted", True),
            ("globalDecision", "VIABLE_ANONYMOUS"),
            ("maxMetadataRequestsTotal", 500),
        ):
            changed = copy.deepcopy(self.plan)
            changed[key] = value
            with self.subTest(key=key), self.assertRaises(ControlPilotError):
                validate_control_plan(changed, self.registry, self.matrix_plan)


class ControlExecutionTests(unittest.TestCase):
    def setUp(self):
        self.registry = synthetic_registry()
        self.matrix_plan = build_plan(self.registry)
        self.plan = build_control_plan(self.registry, self.matrix_plan)

    def run_controls(self, runner, *, provided_inputs=None):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            result = execute_controls(
                registry=self.registry, matrix_plan=self.matrix_plan,
                control_plan=self.plan,
                inputs=provided_inputs or inputs(),
                output_dir=root, probe_runner=runner,
            )
            report = json.loads(
                (root / "aggregate" / "control-matrix-v1.json").read_text(encoding="utf-8")
            )
            manifest = json.loads((root / "manifest-v1.json").read_text(encoding="utf-8"))
            for item in manifest["files"]:
                path = root / item["path"]
                self.assertTrue(path.is_file())
                self.assertEqual(item["sha256"], hashlib.sha256(path.read_bytes()).hexdigest())
            self.assertEqual(4, len(list((root / "probes").glob("*.json"))))
            return result, report, manifest

    def test_complete_paired_controls_do_not_accept_v1_or_full_matrix(self):
        runner = FakeRunner(self.registry, qualification_lane=None)
        summary, report, manifest = self.run_controls(runner)
        self.assertEqual(4, len(runner.calls))
        self.assertEqual(PAIRS, tuple((x["case_class"], x["lane"]) for x in runner.calls))
        self.assertEqual(4, summary["executedProviderProbes"])
        self.assertEqual(4, report["observedCellCount"])
        self.assertEqual(14, len(report["missingCells"]))
        self.assertFalse(report["complete"])
        self.assertEqual("NOT_EVALUATED", summary["v1Qualification"])
        self.assertFalse(summary["fullMatrixAccepted"])
        self.assertEqual("NOT_MADE_M3_A2", report["globalDecision"])
        self.assertEqual(["BOTH_UNRESOLVED", "BOTH_UNRESOLVED"],
                         [x["status"] for x in summary["pairStatus"]])
        self.assertEqual(4, len(manifest["files"])-3)  # 4 probes + summary + JSON/MD

    def test_wrong_provider_fingerprint_rejects_before_any_request(self):
        runner = FakeRunner(self.registry)
        supplied = inputs()
        bad = type(supplied)(
            v0_video_id="badrawid000", v1_video_id=supplied.v1_video_id,
            v2_video_id=supplied.v2_video_id,
            git_commit=supplied.git_commit, runner_image=supplied.runner_image,
            media_probe_mode=supplied.media_probe_mode,
        )
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(M3MatrixError):
                execute_controls(
                    registry=self.registry, matrix_plan=self.matrix_plan,
                    control_plan=self.plan, inputs=bad,
                    output_dir=pathlib.Path(tmp), probe_runner=runner,
                )
        self.assertEqual([], runner.calls)

    def test_wrong_profile_fails_closed(self):
        runner = FakeRunner(self.registry)
        def wrong_profile(**kw):
            artifact = runner(**kw)
            artifact["client"]["profileId"] = "yt-dlp:web"
            return artifact
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(M3MatrixError):
                execute_controls(
                    registry=self.registry, matrix_plan=self.matrix_plan,
                    control_plan=self.plan, inputs=inputs(),
                    output_dir=pathlib.Path(tmp), probe_runner=wrong_profile,
                )

    def test_mixed_git_commit_rejected(self):
        runner = FakeRunner(self.registry)
        def wrong_commit(**kw):
            artifact = runner(**kw)
            artifact["gitCommit"] = "f" * 40
            artifact["producer"]["revision"] = "f" * 40
            return artifact
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(M3BatchError):
                execute_controls(
                    registry=self.registry, matrix_plan=self.matrix_plan,
                    control_plan=self.plan, inputs=inputs(),
                    output_dir=pathlib.Path(tmp), probe_runner=wrong_commit,
                )

    def test_raw_provider_ids_never_appear_in_bundle(self):
        runner = FakeRunner(self.registry)
        supplied = inputs()
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            execute_controls(
                registry=self.registry, matrix_plan=self.matrix_plan,
                control_plan=self.plan, inputs=supplied,
                output_dir=root, probe_runner=runner,
            )
            for path in root.rglob("*"):
                if path.is_file():
                    text = path.read_text(encoding="utf-8")
                    for raw_id in (supplied.v0_video_id, supplied.v1_video_id, supplied.v2_video_id):
                        self.assertNotIn(raw_id, text)

    def test_raw_id_leak_in_artifact_fails_closed(self):
        runner = FakeRunner(self.registry)
        supplied = inputs()
        def leaky(**kw):
            artifact = runner(**kw)
            artifact["limitations"].append(f"raw candidate {supplied.v0_video_id}")
            return artifact
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises((M3MatrixError, M3BatchError, ValueError)):
                execute_controls(
                    registry=self.registry, matrix_plan=self.matrix_plan,
                    control_plan=self.plan, inputs=supplied,
                    output_dir=pathlib.Path(tmp), probe_runner=leaky,
                )


if __name__ == "__main__":
    unittest.main()
