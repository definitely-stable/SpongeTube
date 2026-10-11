"""Deterministic tests for the one-run M3-A2 batch executor."""

import copy
import json
import pathlib
import sys
import tempfile
import unittest

SCRIPT_DIR = pathlib.Path(__file__).resolve().parents[1]
REPO_ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

import m3_batch
import m3_matrix
from m3_batch import BatchInputs, M3BatchError, derive_case_inputs_from_source, execute_batch
from m3_matrix import M3MatrixError, build_plan, validate_registry


REGISTRY_PATH = REPO_ROOT / ".work" / "m3-a2" / "case-registry-v1.json"
EXAMPLE_PATH = REPO_ROOT / ".work" / "schemas" / "examples" / "m3" / "m3-a-probe-v1.example.json"
GIT_SHA = "c" * 40
RUNNER_IMAGE = "ubuntu24@20261001.1"
RAW_IDS = {
    "V0": "AAAAAAAAAAA",
    "V1": "BBBBBBBBBBB",
    "V2": "CCCCCCCCCCC",
}


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


def synthetic_registry():
    reg = load(REGISTRY_PATH)
    for case in reg["cases"]:
        case["providerContentIdSha256"] = m3_matrix._sha256_text(RAW_IDS[case["caseClass"]])
    validate_registry(reg)
    return reg


def base_artifact(registry, *, case_class, lane, layout="UNKNOWN", outcome="INCONCLUSIVE"):
    case = next(item for item in registry["cases"] if item["caseClass"] == case_class)
    artifact = load(EXAMPLE_PATH)
    artifact["probeId"] = f"batch:{case_class.lower()}:{lane.lower()}"
    artifact["gitCommit"] = GIT_SHA
    artifact["producer"] = {
        "toolId": registry["producer"]["toolId"],
        "version": registry["producer"]["version"],
        "revision": GIT_SHA,
    }
    artifact["client"] = {
        "lane": lane,
        "profileId": registry["laneProfiles"][lane],
        "profileVersion": registry["referenceOracle"]["version"],
    }
    artifact["videoCase"] = {
        "caseClass": case_class,
        "caseRevision": case["caseRevision"],
        "providerContentIdSha256": case["providerContentIdSha256"],
    }
    artifact["environment"]["runnerImage"] = RUNNER_IMAGE
    artifact["references"] = [{
        "id": registry["referenceOracle"]["id"],
        "version": registry["referenceOracle"]["version"],
        "revision": registry["referenceOracle"]["revision"],
        "role": "REFERENCE_ORACLE",
        "productionDependency": False,
    }]
    artifact["delivery"]["mediaLayout"] = layout
    if case_class == "V1" and lane == "WEB_EMBEDDED" and layout in ("SPLIT_AV", "MULTIPLE"):
        artifact["capabilities"]["https"] = "PRESENT"
        artifact["budget"]["mediaProbes"] = [
            {
                "probeId": "q-video",
                "purpose": "VIDEO",
                "byteLimit": 65536,
                "bytesRead": 65536,
            },
            {
                "probeId": "q-audio",
                "purpose": "AUDIO",
                "byteLimit": 65536,
                "bytesRead": 65536,
            },
        ]
    artifact["outcome"]["classification"] = outcome
    if outcome == "PROVIDER_REJECTED":
        artifact["providerSignals"]["rejection"] = "PRESENT"
    return artifact


class FakeRunner:
    def __init__(self, registry, *, qualification_layout="SPLIT_AV", rejected_cell=None):
        self.registry = registry
        self.qualification_layout = qualification_layout
        self.rejected_cell = rejected_cell
        self.calls = []

    def __call__(
        self,
        *,
        video_id,
        case_class,
        case_revision,
        lane,
        git_commit,
        runner_image,
        media_probe_mode,
    ):
        self.calls.append({
            "video_id": video_id,
            "case_class": case_class,
            "case_revision": case_revision,
            "lane": lane,
            "git_commit": git_commit,
            "runner_image": runner_image,
            "media_probe_mode": media_probe_mode,
        })
        self.assertions(video_id, case_class, git_commit, runner_image, media_probe_mode)
        layout = "UNKNOWN"
        if case_class == "V1" and lane == "WEB_EMBEDDED":
            layout = self.qualification_layout
        outcome = (
            "PROVIDER_REJECTED"
            if self.rejected_cell == (case_class, lane)
            else "INCONCLUSIVE"
        )
        return base_artifact(
            self.registry,
            case_class=case_class,
            lane=lane,
            layout=layout,
            outcome=outcome,
        )

    def assertions(self, video_id, case_class, git_commit, runner_image, media_probe_mode):
        if video_id != RAW_IDS[case_class]:
            raise AssertionError("wrong raw ID routed to case")
        if git_commit != GIT_SHA:
            raise AssertionError("wrong git commit")
        if runner_image != RUNNER_IMAGE:
            raise AssertionError("wrong runner image")
        if media_probe_mode != "range-set":
            raise AssertionError("non-canonical media probe mode")


def inputs():
    return BatchInputs(
        v0_video_id=RAW_IDS["V0"],
        v1_video_id=RAW_IDS["V1"],
        v2_video_id=RAW_IDS["V2"],
        git_commit=GIT_SHA,
        runner_image=RUNNER_IMAGE,
    )


class M3A2SourceDerivationTest(unittest.TestCase):
    def test_derives_exact_case_ids_from_pinned_source_text(self):
        reg = synthetic_registry()
        source = "\n".join(
            f"{{'id': '{RAW_IDS[case_class]}'}}"
            for case_class in ("V0", "V1", "V2")
        )
        derived = derive_case_inputs_from_source(reg, source)
        self.assertEqual(RAW_IDS, derived)

    def test_duplicate_occurrences_of_same_id_are_deduplicated(self):
        reg = synthetic_registry()
        source = (
            f"'id': '{RAW_IDS['V0']}'\n"
            f"'again': '{RAW_IDS['V0']}'\n"
            f"'id': '{RAW_IDS['V1']}'\n"
            f"'id': '{RAW_IDS['V2']}'\n"
        )
        derived = derive_case_inputs_from_source(reg, source)
        self.assertEqual(RAW_IDS, derived)

    def test_missing_fingerprint_match_fails_closed(self):
        reg = synthetic_registry()
        source = (
            f"'id': '{RAW_IDS['V0']}'\n"
            f"'id': '{RAW_IDS['V2']}'\n"
        )
        with self.assertRaises(M3BatchError):
            derive_case_inputs_from_source(reg, source)

    def test_non_provider_like_strings_do_not_match(self):
        reg = synthetic_registry()
        source = (
            f"'id': '{RAW_IDS['V0']}'\n"
            f"'text': '{RAW_IDS['V1']}extra'\n"
            f"'id': '{RAW_IDS['V2']}'\n"
        )
        with self.assertRaises(M3BatchError):
            derive_case_inputs_from_source(reg, source)


class M3A2BatchExecutionTest(unittest.TestCase):
    def test_success_executes_exactly_eighteen_unique_cells_and_reuses_qualification(self):
        reg = synthetic_registry()
        plan = build_plan(reg)
        runner = FakeRunner(reg)

        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            summary = execute_batch(
                registry=reg,
                plan=plan,
                inputs=inputs(),
                output_dir=root,
                probe_runner=runner,
            )

            self.assertEqual("COMPLETE", summary["status"])
            self.assertEqual(18, summary["executedProviderProbeCount"])
            self.assertEqual(18, summary["matrixCellCount"])
            self.assertEqual("NOT_MADE_M3_A2", summary["globalDecision"])

            cells = [(call["case_class"], call["lane"]) for call in runner.calls]
            self.assertEqual(18, len(cells))
            self.assertEqual(18, len(set(cells)))
            self.assertEqual(("V1", "WEB_EMBEDDED"), cells[0])
            self.assertEqual(1, cells.count(("V1", "WEB_EMBEDDED")))

            probes = sorted((root / "probes").glob("*.json"))
            self.assertEqual(18, len(probes))
            report = json.loads((root / "aggregate" / "matrix-v1.json").read_text(encoding="utf-8"))
            self.assertTrue(report["complete"])
            self.assertEqual(18, report["observedCellCount"])

    def test_failed_qualification_stops_before_remaining_provider_requests(self):
        reg = synthetic_registry()
        runner = FakeRunner(reg, qualification_layout="MUXED")

        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            with self.assertRaises(M3BatchError):
                execute_batch(
                    registry=reg,
                    plan=build_plan(reg),
                    inputs=inputs(),
                    output_dir=root,
                    probe_runner=runner,
                )

            self.assertEqual(1, len(runner.calls))
            self.assertEqual("V1", runner.calls[0]["case_class"])
            self.assertEqual("WEB_EMBEDDED", runner.calls[0]["lane"])
            summary = json.loads((root / "batch-summary-v1.json").read_text(encoding="utf-8"))
            self.assertEqual("QUALIFICATION_NOT_MET", summary["status"])
            self.assertEqual(0, summary["remainingCellsExecuted"])
            self.assertTrue((root / "qualification" / "v1-web-embedded.json").is_file())
            self.assertFalse((root / "aggregate" / "matrix-v1.json").exists())

    def test_provider_rejection_is_retained_as_evidence_and_does_not_abort_batch(self):
        reg = synthetic_registry()
        runner = FakeRunner(reg, rejected_cell=("V2", "TV"))

        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            summary = execute_batch(
                registry=reg,
                plan=build_plan(reg),
                inputs=inputs(),
                output_dir=root,
                probe_runner=runner,
            )
            self.assertEqual("COMPLETE", summary["status"])
            report = json.loads((root / "aggregate" / "matrix-v1.json").read_text(encoding="utf-8"))
            cell = next(
                item
                for item in report["cells"]
                if item["caseClass"] == "V2" and item["lane"] == "TV"
            )
            self.assertEqual("PROVIDER_REJECTED", cell["outcome"]["classification"])

    def test_output_bundle_never_retains_raw_provider_ids(self):
        reg = synthetic_registry()
        runner = FakeRunner(reg)

        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            execute_batch(
                registry=reg,
                plan=build_plan(reg),
                inputs=inputs(),
                output_dir=root,
                probe_runner=runner,
            )
            retained = "\n".join(
                path.read_text(encoding="utf-8")
                for path in root.rglob("*")
                if path.is_file()
            )
            for raw_id in RAW_IDS.values():
                self.assertNotIn(raw_id, retained)

    def test_wrong_raw_id_fails_before_any_provider_request(self):
        reg = synthetic_registry()
        runner = FakeRunner(reg)
        bad = copy.replace(inputs(), v1_video_id="wrong-v1-id") if hasattr(copy, "replace") else BatchInputs(
            v0_video_id=RAW_IDS["V0"],
            v1_video_id="wrong-v1-id",
            v2_video_id=RAW_IDS["V2"],
            git_commit=GIT_SHA,
            runner_image=RUNNER_IMAGE,
        )

        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(M3MatrixError):
                execute_batch(
                    registry=reg,
                    plan=build_plan(reg),
                    inputs=bad,
                    output_dir=pathlib.Path(tmp),
                    probe_runner=runner,
                )
        self.assertEqual([], runner.calls)

    def test_noncanonical_probe_mode_fails_before_any_provider_request(self):
        reg = synthetic_registry()
        runner = FakeRunner(reg)
        bad = BatchInputs(
            v0_video_id=RAW_IDS["V0"],
            v1_video_id=RAW_IDS["V1"],
            v2_video_id=RAW_IDS["V2"],
            git_commit=GIT_SHA,
            runner_image=RUNNER_IMAGE,
            media_probe_mode="startup",
        )
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(M3BatchError):
                execute_batch(
                    registry=reg,
                    plan=build_plan(reg),
                    inputs=bad,
                    output_dir=pathlib.Path(tmp),
                    probe_runner=runner,
                )
        self.assertEqual([], runner.calls)


if __name__ == "__main__":
    unittest.main()
