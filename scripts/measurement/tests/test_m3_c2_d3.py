"""C2-D3 offline paired-oracle falsification; no provider traffic."""
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
from m3_controls import build_control_plan
from m3_matrix import build_plan as build_matrix_plan, load_registry
from m3_c2_d0 import build_plan as build_d0_plan
from m3_c2_d1 import build_plan as build_d1_plan
from m3_c2_d2 import build_plan as build_d2_plan
from m3_c2_d3 import (
    ORACLES, MAX_TOTAL, ORDER, build_plan, observe_versioned_cell,
    run_d3, validate_observation, validate_plan, verify_bundle,
)


class D3Tests(unittest.TestCase):
    def setUp(self):
        self.registry = synthetic_registry()
        self.c1 = build_control_plan(self.registry, build_matrix_plan(self.registry))
        self.d0 = build_d0_plan(self.registry, self.c1)
        self.d1 = build_d1_plan(self.registry, self.c1, self.d0)
        self.d2 = build_d2_plan(self.registry, self.c1, self.d0, self.d1)
        self.plan = build_plan(self.registry, self.c1, self.d0, self.d1, self.d2)
        a = inputs()
        self.ids = {"V0": a.v0_video_id, "V1": a.v1_video_id, "V2": a.v2_video_id}
        self.sha, self.runner = a.git_commit, a.runner_image
        FakeOracle.network_calls = 0
        FakeOracle.configured_clients = []
        FakeOracle.configured_modes = []

    def observer(self, step):
        # Differential fixture: only reference-version package differs.
        D2Oracle.behavior = "WRAPPED" if step["arm"] == "A" else "SUCCESS"
        oracle = ORACLES[step["arm"]]
        cell = next(x for x in self.plan["cells"]
                    if x["caseClass"] == step["caseClass"] and x["lane"] == step["lane"])
        return observe_versioned_cell(
            registry=self.registry, cell=cell, arm=step["arm"],
            raw_id=self.ids[step["caseClass"]], git_commit=self.sha,
            runner_image=self.runner,
            oracle_loader=lambda: (D2Oracle, oracle["version"], oracle["revision"]),
        )

    def run_bundle(self, root):
        return run_d3(
            registry=self.registry, c1=self.c1, d0=self.d0, d1_plan=self.d1,
            d2_plan=self.d2, plan=self.plan, ids=self.ids, git_commit=self.sha,
            runner_image=self.runner, output=root, observer=self.observer,
        )

    def test_committed_plan_exactly_matches_inherited_d2(self):
        folder = ROOT / ".work" / "m3-a2"
        registry = load_registry(folder / "case-registry-v1.json")
        c1 = json.loads((folder / "control-plan-v1.json").read_text())
        d0 = json.loads((folder / "c2-d0-plan-v1.json").read_text())
        d1 = json.loads((folder / "c2-d1-plan-v1.json").read_text())
        d2 = json.loads((folder / "c2-d2-plan-v1.json").read_text())
        committed = json.loads((folder / "c2-d3-plan-v1.json").read_text())
        validate_plan(committed, registry, c1, d0, d1, d2)
        self.assertEqual(8, len(committed["execution"]))
        self.assertEqual(256, committed["maxTotalMetadataRequests"])
        self.assertEqual(0, committed["maxTotalMediaProbes"])

    def test_balanced_order_with_exact_four_paired_cases(self):
        self.assertEqual((("A", "B"), ("B", "A"), ("B", "A"), ("A", "B")), ORDER)
        bycell = [tuple(self.plan["execution"][i + j]["arm"] for j in (0, 1))
                  for i in (0, 2, 4, 6)]
        self.assertEqual(list(ORDER), bycell)
        self.assertEqual(4, sum(step["arm"] == "A" for step in self.plan["execution"]))
        self.assertEqual(4, sum(step["arm"] == "B" for step in self.plan["execution"]))

    def test_forged_plan_variants_rejected(self):
        for name in ("newVersion", "oracleRevision", "order", "budget", "client",
                     "token", "js", "cell", "extra", "decision"):
            plan = copy.deepcopy(self.plan)
            if name == "newVersion":
                plan["oracles"]["B"]["version"] = "latest"
            elif name == "oracleRevision":
                plan["oracles"]["B"]["revision"] = "0" * 40
            elif name == "order":
                plan["execution"][0]["arm"] = "B"
            elif name == "budget":
                plan["maxTotalMetadataRequests"] += 1
            elif name == "client":
                plan["cells"][0]["profileId"] = "yt-dlp:tv_samsung"
            elif name == "token":
                plan["mode"]["poTokenProvider"] = "ENABLED"
            elif name == "js":
                plan["mode"]["jsRuntime"] = "DENO"
            elif name == "cell":
                plan["cells"][0]["caseClass"] = "V1"
            elif name == "extra":
                plan["execution"].append(dict(plan["execution"][0]))
            else:
                plan["globalDecision"] = "VERSION_B_VIABLE"
            with self.subTest(name=name), self.assertRaises(ValueError):
                validate_plan(plan, self.registry, self.c1, self.d0, self.d1, self.d2)

    def test_baseline_must_equal_exact_d2_oracle(self):
        registry = copy.deepcopy(self.registry)
        registry["referenceOracle"]["version"] = "2026.10.21"
        with self.assertRaises(ValueError):
            build_plan(registry, self.c1, self.d0, self.d1, self.d2)

    def test_empty_output_required_and_full_paired_bundle(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp) / "evidence"
            summary = self.run_bundle(root)
            self.assertEqual(8, summary["observations"])
            self.assertEqual(8, FakeOracle.network_calls)
            self.assertEqual(0, summary["totalMediaProbes"])
            self.assertEqual("NOT_MADE_M3_A2", summary["globalDecision"])
            self.assertEqual(8, summary["totalMetadataRequests"])
            self.assertTrue(all(p["completionDiffers"] for p in summary["pairedCells"]))
            self.assertEqual(summary, verify_bundle(
                registry=self.registry, plan=self.plan, ids=self.ids, root=root,
                git_commit=self.sha, runner_image=self.runner,
            ))
            self.assertEqual(10, len(list(root.iterdir())))
            self.assertTrue(all(not x["js"] and not x["remote"]
                                for x in FakeOracle.configured_modes))
            for path in root.iterdir():
                for secret in ("PRIVATE_SIGNED_URL", "SECRET_CAUSE", "SECRET_SESSION",
                               "www.youtube.com", *self.ids.values()):
                    self.assertNotIn(secret, path.read_text())
            with self.assertRaises(ValueError):
                self.run_bundle(root)

    def test_bad_case_identity_aborts_before_network(self):
        ids = dict(self.ids)
        ids["V2"] = "invalid VIDEO ID"
        with tempfile.TemporaryDirectory() as td:
            with self.assertRaises(ValueError):
                run_d3(registry=self.registry, c1=self.c1, d0=self.d0,
                       d1_plan=self.d1, d2_plan=self.d2, plan=self.plan,
                       ids=ids, git_commit=self.sha, runner_image=self.runner,
                       output=pathlib.Path(td), observer=self.observer)
        self.assertEqual(0, FakeOracle.network_calls)

    def test_untrusted_status_and_metadata_cannot_claim_viability(self):
        with tempfile.TemporaryDirectory() as td:
            root = pathlib.Path(td) / "d3"
            self.run_bundle(root)
            path = root / "02-v0-web_embedded-b.json"
            original = json.loads(path.read_text())
            for key, value in (("arm", "A"), ("ordinal", 7), ("lane", "TV")):
                forged = copy.deepcopy(original)
                forged[key] = value
                with self.subTest(key=key), self.assertRaises(ValueError):
                    validate_observation(forged, self.registry, self.plan,
                                         self.plan["execution"][1], self.sha, self.runner)
            forged = copy.deepcopy(original)
            forged["observation"]["decision"] = "VIABLE_ANONYMOUS"
            with self.assertRaises(ValueError):
                validate_observation(forged, self.registry, self.plan,
                                     self.plan["execution"][1], self.sha, self.runner)
            forged = copy.deepcopy(original)
            forged["observation"]["referenceOracle"]["version"] = "latest"
            with self.assertRaises(ValueError):
                validate_observation(forged, self.registry, self.plan,
                                     self.plan["execution"][1], self.sha, self.runner)
            forged = copy.deepcopy(original)
            forged["observation"]["gitCommit"] = "0" * 40
            with self.assertRaises(ValueError):
                validate_observation(forged, self.registry, self.plan,
                                     self.plan["execution"][1], self.sha, self.runner)

    def test_tamper_rejected_even_if_manifest_digest_recalculated(self):
        with tempfile.TemporaryDirectory() as td:
            root = pathlib.Path(td) / "d3"
            self.run_bundle(root)
            path = root / "summary-v1.json"
            summary = json.loads(path.read_text())
            summary["globalDecision"] = "B_REQUIRED"
            path.write_text(json.dumps(summary))
            manifestpath = root / "manifest-v1.json"
            manifest = json.loads(manifestpath.read_text())
            for item in manifest["files"]:
                if item["path"] == path.name:
                    item["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
            manifestpath.write_text(json.dumps(manifest))
            with self.assertRaises(ValueError):
                verify_bundle(registry=self.registry, plan=self.plan, ids=self.ids,
                              root=root, git_commit=self.sha, runner_image=self.runner)

    def test_missing_duplicate_and_unexpected_evidence_rejected(self):
        with tempfile.TemporaryDirectory() as td:
            root = pathlib.Path(td) / "d3"
            self.run_bundle(root)
            (root / "garbage.txt").write_text("sensitive")
            with self.assertRaises(ValueError):
                verify_bundle(registry=self.registry, plan=self.plan, ids=self.ids,
                              root=root, git_commit=self.sha, runner_image=self.runner)


if __name__ == "__main__":
    unittest.main()
