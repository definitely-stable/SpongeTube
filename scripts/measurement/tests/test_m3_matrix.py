"""M3-A2 registry, preflight, qualification and matrix aggregation tests."""

import copy
import json
import pathlib
import sys
import tempfile
import unittest

SCRIPT_DIR = pathlib.Path(__file__).resolve().parents[1]
REPO_ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

import m3_matrix
from m3_matrix import (
    EXPECTED_PRIMARY_LANES,
    M3MatrixError,
    aggregate_probes,
    build_plan,
    planned_cells,
    render_markdown,
    validate_plan,
    validate_case_input,
    validate_probe_against_registry,
    validate_qualification_document,
    validate_registry,
)


REGISTRY_PATH = REPO_ROOT / ".work" / "m3-a2" / "case-registry-v1.json"
PLAN_PATH = REPO_ROOT / ".work" / "m3-a2" / "matrix-plan-v1.json"
EXAMPLE_PATH = REPO_ROOT / ".work" / "schemas" / "examples" / "m3" / "m3-a-probe-v1.example.json"
GIT_SHA = "c" * 40


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


def registry():
    return load(REGISTRY_PATH)


def synthetic_probe(case_class, lane, *, outcome="INCONCLUSIVE"):
    reg = registry()
    case = next(item for item in reg["cases"] if item["caseClass"] == case_class)
    document = load(EXAMPLE_PATH)
    document["probeId"] = f"m3-a2:{case_class.lower()}:{lane.lower()}"
    document["gitCommit"] = GIT_SHA
    document["producer"] = {
        "toolId": reg["producer"]["toolId"],
        "version": reg["producer"]["version"],
        "revision": GIT_SHA,
    }
    document["client"] = {
        "lane": lane,
        "profileId": f"yt-dlp:{lane.lower()}",
        "profileVersion": reg["referenceOracle"]["version"],
    }
    document["videoCase"] = {
        "caseClass": case_class,
        "caseRevision": case["caseRevision"],
        "providerContentIdSha256": case["providerContentIdSha256"],
    }
    document["references"] = [{
        "id": reg["referenceOracle"]["id"],
        "version": reg["referenceOracle"]["version"],
        "revision": reg["referenceOracle"]["revision"],
        "role": "REFERENCE_ORACLE",
        "productionDependency": False,
    }]
    document["outcome"]["classification"] = outcome
    return document


def qualification_probe():
    document = synthetic_probe("V1", "WEB_EMBEDDED")
    document["delivery"]["mediaLayout"] = "SPLIT_AV"
    return document


def write_qualification(root, document=None):
    path = root / ".work" / "evidence" / "m3-a2" / "qualification" / "v1-apollo14-r1.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(document or qualification_probe()), encoding="utf-8")
    return path


class M3A2RegistryTest(unittest.TestCase):
    def test_committed_registry_is_valid_and_plans_eighteen_cells(self):
        reg = registry()
        validate_registry(reg)
        cells = planned_cells(reg)
        self.assertEqual(18, len(cells))
        self.assertEqual(("V0", "WEB_EMBEDDED"), cells[0])
        self.assertEqual(("V2", "WEB_SAFARI"), cells[-1])

    def test_committed_plan_is_exact_registry_expansion(self):
        reg = registry()
        plan = load(PLAN_PATH)
        validate_plan(plan, reg)
        self.assertEqual(build_plan(reg), plan)
        self.assertEqual(18, len(plan["cells"]))

    def test_registry_contains_fingerprints_not_raw_provider_ids(self):
        encoded = json.dumps(registry(), sort_keys=True)
        self.assertNotIn("youtube.com/watch", encoded)
        self.assertNotIn("youtu.be/", encoded)
        for case in registry()["cases"]:
            self.assertRegex(case["providerContentIdSha256"], r"^[0-9a-f]{64}$")

    def test_case_set_and_lane_order_are_fail_closed(self):
        reg = registry()
        reg["primaryLanes"] = list(reversed(reg["primaryLanes"]))
        with self.assertRaises(M3MatrixError):
            validate_registry(reg)

        reg = registry()
        reg["cases"].append(copy.deepcopy(reg["cases"][0]))
        with self.assertRaises(M3MatrixError):
            validate_registry(reg)

    def test_v1_must_remain_probe_qualified(self):
        reg = registry()
        reg["cases"][1]["qualification"]["mode"] = "REFERENCE_FIXTURE"
        with self.assertRaises(M3MatrixError):
            validate_registry(reg)


class M3A2InputPreflightTest(unittest.TestCase):
    def test_raw_input_must_hash_to_predeclared_fingerprint(self):
        reg = registry()
        synthetic_id = "synthetic-public-id"
        reg["cases"][0]["providerContentIdSha256"] = m3_matrix._sha256_text(synthetic_id)
        validate_registry(reg)

        case = validate_case_input(
            reg,
            case_class="V0",
            case_revision=reg["cases"][0]["caseRevision"],
            video_id=synthetic_id,
        )
        self.assertEqual("V0", case["caseClass"])

        with self.assertRaises(M3MatrixError):
            validate_case_input(
                reg,
                case_class="V0",
                case_revision=reg["cases"][0]["caseRevision"],
                video_id="different-id",
            )

    def test_deferred_lane_cannot_enter_primary_matrix_preflight(self):
        reg = registry()
        synthetic_id = "synthetic-public-id"
        reg["cases"][0]["providerContentIdSha256"] = m3_matrix._sha256_text(synthetic_id)
        with self.assertRaises(M3MatrixError):
            validate_case_input(
                reg,
                case_class="V0",
                case_revision=reg["cases"][0]["caseRevision"],
                video_id=synthetic_id,
                lane="ANDROID",
                purpose="matrix",
            )

    def test_case_revision_cannot_be_substituted(self):
        reg = registry()
        synthetic_id = "synthetic-public-id"
        reg["cases"][0]["providerContentIdSha256"] = m3_matrix._sha256_text(synthetic_id)
        with self.assertRaises(M3MatrixError):
            validate_case_input(
                reg,
                case_class="V0",
                case_revision="post-hoc-revision",
                video_id=synthetic_id,
            )


class M3A2QualificationTest(unittest.TestCase):
    def test_v1_split_av_qualification_is_valid(self):
        validate_qualification_document(
            qualification_probe(),
            registry(),
            case_class="V1",
        )

    def test_v1_muxed_only_does_not_qualify_split_av_case(self):
        document = qualification_probe()
        document["delivery"]["mediaLayout"] = "MUXED"
        with self.assertRaises(M3MatrixError):
            validate_qualification_document(document, registry(), case_class="V1")

    def test_qualification_must_match_frozen_v1_fingerprint(self):
        document = qualification_probe()
        document["videoCase"]["providerContentIdSha256"] = "d" * 64
        with self.assertRaises(M3MatrixError):
            validate_qualification_document(document, registry(), case_class="V1")


class M3A2ProbeBindingTest(unittest.TestCase):
    def test_probe_requires_exact_case_lane_producer_and_oracle(self):
        document = synthetic_probe("V0", "WEB_EMBEDDED")
        validate_probe_against_registry(document, registry())

        broken = copy.deepcopy(document)
        broken["producer"]["version"] = "2"
        with self.assertRaises(M3MatrixError):
            validate_probe_against_registry(broken, registry())

        broken = copy.deepcopy(document)
        broken["references"][0]["revision"] = "e" * 40
        with self.assertRaises(M3MatrixError):
            validate_probe_against_registry(broken, registry())

        broken = copy.deepcopy(document)
        broken["client"]["lane"] = "ANDROID"
        broken["client"]["profileId"] = "yt-dlp:android"
        with self.assertRaises(M3MatrixError):
            validate_probe_against_registry(broken, registry())

        broken = copy.deepcopy(document)
        broken["client"]["profileId"] = "yt-dlp:web"
        with self.assertRaises(M3MatrixError):
            validate_probe_against_registry(broken, registry())

    def test_unplanned_case_revision_and_fingerprint_fail(self):
        document = synthetic_probe("V2", "TV")

        broken = copy.deepcopy(document)
        broken["videoCase"]["caseRevision"] = "other"
        with self.assertRaises(M3MatrixError):
            validate_probe_against_registry(broken, registry())

        broken = copy.deepcopy(document)
        broken["videoCase"]["providerContentIdSha256"] = "e" * 64
        with self.assertRaises(M3MatrixError):
            validate_probe_against_registry(broken, registry())


class M3A2AggregationTest(unittest.TestCase):
    def _root_with_v1_qualification(self):
        tmp = tempfile.TemporaryDirectory()
        root = pathlib.Path(tmp.name)
        write_qualification(root)
        return tmp, root

    def test_partial_matrix_preserves_missing_cells_and_unknown(self):
        tmp, root = self._root_with_v1_qualification()
        self.addCleanup(tmp.cleanup)

        document = synthetic_probe("V0", "WEB_EMBEDDED")
        report = aggregate_probes(
            registry(),
            [document],
            require_complete=False,
            repo_root=root,
        )

        self.assertFalse(report["complete"])
        self.assertEqual(1, report["observedCellCount"])
        self.assertEqual(17, len(report["missingCells"]))
        assumptions = {
            item["name"]: item["status"]
            for item in report["assumptions"]
            if item["caseClass"] == "V0" and item["lane"] == "WEB_EMBEDDED"
        }
        self.assertEqual("UNKNOWN", assumptions["directHttps"])
        self.assertEqual("UNKNOWN", assumptions["range"])
        self.assertEqual("NOT_MADE_M3_A2", report["globalDecision"])

    def test_require_complete_rejects_partial_matrix(self):
        tmp, root = self._root_with_v1_qualification()
        self.addCleanup(tmp.cleanup)
        with self.assertRaises(M3MatrixError):
            aggregate_probes(
                registry(),
                [synthetic_probe("V0", "WEB_EMBEDDED")],
                require_complete=True,
                repo_root=root,
            )

    def test_duplicate_cell_is_rejected(self):
        tmp, root = self._root_with_v1_qualification()
        self.addCleanup(tmp.cleanup)
        probe = synthetic_probe("V0", "TV")
        with self.assertRaises(M3MatrixError):
            aggregate_probes(
                registry(),
                [probe, copy.deepcopy(probe)],
                require_complete=False,
                repo_root=root,
            )

    def test_mixed_git_commits_are_rejected(self):
        tmp, root = self._root_with_v1_qualification()
        self.addCleanup(tmp.cleanup)
        first = synthetic_probe("V0", "TV")
        second = synthetic_probe("V2", "TV")
        second["gitCommit"] = "f" * 40
        second["producer"]["revision"] = "f" * 40
        with self.assertRaises(M3MatrixError):
            aggregate_probes(
                registry(),
                [first, second],
                require_complete=False,
                repo_root=root,
            )

    def test_v1_cells_are_blocked_until_qualification_is_retained(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(M3MatrixError):
                aggregate_probes(
                    registry(),
                    [synthetic_probe("V1", "WEB_EMBEDDED")],
                    require_complete=False,
                    repo_root=pathlib.Path(tmp),
                )

    def test_full_matrix_is_deterministic_and_complete(self):
        tmp, root = self._root_with_v1_qualification()
        self.addCleanup(tmp.cleanup)

        documents = [
            synthetic_probe(case_class, lane)
            for case_class in ("V0", "V1", "V2")
            for lane in EXPECTED_PRIMARY_LANES
        ]
        report = aggregate_probes(
            registry(),
            documents,
            require_complete=True,
            repo_root=root,
        )

        self.assertTrue(report["complete"])
        self.assertEqual(18, report["observedCellCount"])
        self.assertEqual([], report["missingCells"])
        self.assertEqual(GIT_SHA, report["executionGitCommit"])
        self.assertEqual(18 * 8, len(report["assumptions"]))
        self.assertEqual(18 * 8, report["assumptionCounts"]["UNKNOWN"])
        self.assertEqual("NOT_MADE_M3_A2", report["globalDecision"])

        markdown = render_markdown(report)
        self.assertIn("Coverage: **18/18**", markdown)
        self.assertIn("All assumption statuses are scoped", markdown)

    def test_viable_cell_accepts_only_cell_scoped_anonymous_assumption(self):
        tmp, root = self._root_with_v1_qualification()
        self.addCleanup(tmp.cleanup)

        document = synthetic_probe("V0", "WEB_EMBEDDED", outcome="VIABLE_ANONYMOUS")
        document["capabilities"]["https"] = "PRESENT"
        document["requirements"]["gvsPoToken"] = "NOT_REQUIRED"
        document["requirements"]["playerPoToken"] = "NOT_REQUIRED"
        document["requirements"]["challengeRuntime"] = "NOT_REQUIRED"
        document["delivery"]["startup"] = "PRESENT"

        report = aggregate_probes(
            registry(),
            [document],
            require_complete=False,
            repo_root=root,
        )
        scoped = {
            item["name"]: item["status"]
            for item in report["assumptions"]
        }
        self.assertEqual("ACCEPTED", scoped["anonymousStartup"])
        self.assertEqual("REJECTED", scoped["gvsPoTokenRequired"])
        self.assertEqual("NOT_MADE_M3_A2", report["globalDecision"])


if __name__ == "__main__":
    unittest.main()
