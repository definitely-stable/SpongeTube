"""Negative M3-A2 sweep must be independently falsifiable and non-upgradable."""
import copy
import io
import json
import pathlib
import sys
import tempfile
import unittest
import zipfile

SCRIPT_DIR = pathlib.Path(__file__).resolve().parents[1]
REPO_ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from m3_a2_negative_audit import NegativeAuditError, audit_bundle
from m3_matrix import M3MatrixError, build_plan, validate_qualification_document
from test_m3_batch import base_artifact, synthetic_registry, GIT_SHA, RUNNER_IMAGE


def _negative_bundle(registry, *, mutation=None):
    plan = build_plan(registry)
    attempts = []
    entries = {}
    for i, lane in enumerate(registry["primaryLanes"], 1):
        probe = base_artifact(registry, case_class="V1", lane=lane)
        try:
            validate_qualification_document(probe, registry, case_class="V1")
        except M3MatrixError as exc:
            reason = str(exc)
        else:
            raise AssertionError("fixture unexpectedly qualified")
        attempts.append({
            "ordinal": i, "caseClass": "V1", "lane": lane,
            "outcome": probe["outcome"]["classification"],
            "mediaLayout": probe["delivery"]["mediaLayout"],
            "https": probe["capabilities"]["https"],
            "qualified": False, "reason": reason,
        })
        raw = (json.dumps(probe, indent=2, sort_keys=True) + "\n").encode()
        entries[f"probes/v1-{lane.lower()}.json"] = raw
        entries[f"qualification/v1-{lane.lower()}.json"] = raw
    summary = {
        "schemaVersion": 1,
        "status": "QUALIFICATION_NOT_MET",
        "gitCommit": GIT_SHA,
        "registryId": registry["registryId"],
        "planId": plan["planId"],
        "qualificationAttempts": attempts,
        "executedProviderProbeCount": 6,
        "matrixCellsExecuted": 6,
        "remainingCellsExecuted": 12,
        "reason": "V1 did not qualify on any frozen primary lane; V0/V2 cells were not executed.",
    }
    entries["batch-summary-v1.json"] = (json.dumps(summary) + "\n").encode()
    if mutation is not None:
        mutation(entries)
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, raw in entries.items():
            archive.writestr(name, raw)
    return output.getvalue(), plan


class M3NegativeAuditTest(unittest.TestCase):
    def setUp(self):
        self.registry = synthetic_registry()

    def _run(self, mutation=None):
        raw, plan = _negative_bundle(self.registry, mutation=mutation)
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "bundle.zip"
            path.write_bytes(raw)
            return audit_bundle(path, self.registry, plan)

    def test_negative_six_lane_sweep_is_not_promoted_to_matrix(self):
        report = self._run()
        self.assertEqual("NEGATIVE_V1_QUALIFICATION_AUDITED", report["status"])
        self.assertEqual(6, report["executedCells"])
        self.assertEqual(12, report["unexecutedCells"])
        self.assertFalse(report["completeMatrix"])
        self.assertEqual("NOT_MADE_M3_A2", report["globalDecision"])
        self.assertEqual([*self.registry["primaryLanes"]], [c["lane"] for c in report["cells"]])

    def test_rejects_unplanned_extra_member(self):
        with self.assertRaises(NegativeAuditError):
            self._run(lambda e: e.update({"probes/v0-web.json": b"{}"}))

    def test_rejects_missing_qualification_copy(self):
        with self.assertRaises(NegativeAuditError):
            self._run(lambda e: e.pop("qualification/v1-web.json"))

    def test_rejects_probe_qualification_divergence(self):
        with self.assertRaises(NegativeAuditError):
            self._run(lambda e: e.update({"qualification/v1-tv.json": b"{}"}))

    def test_rejects_fabricated_qualified_attempt(self):
        def mutate(entries):
            summary = json.loads(entries["batch-summary-v1.json"])
            summary["qualificationAttempts"][0]["qualified"] = True
            entries["batch-summary-v1.json"] = json.dumps(summary).encode()
        with self.assertRaises(NegativeAuditError):
            self._run(mutate)

    def test_rejects_mismatched_commit(self):
        def mutate(entries):
            name = "probes/v1-web.json"
            artifact = json.loads(entries[name])
            artifact["gitCommit"] = "f" * 40
            raw = json.dumps(artifact).encode()
            entries[name] = raw
            entries["qualification/v1-web.json"] = raw
        with self.assertRaises((NegativeAuditError, M3MatrixError)):
            self._run(mutate)

    def test_rejects_forged_negative_qualification(self):
        def mutate(entries):
            name = "probes/v1-tv.json"
            artifact = json.loads(entries[name])
            artifact["delivery"]["mediaLayout"] = "SPLIT_AV"
            artifact["capabilities"]["https"] = "PRESENT"
            artifact["budget"]["mediaProbes"] = [
                {"probeId": "v", "purpose": "VIDEO", "byteLimit": 65536, "bytesRead": 42},
                {"probeId": "a", "purpose": "AUDIO", "byteLimit": 65536, "bytesRead": 42},
            ]
            raw = json.dumps(artifact).encode()
            entries[name] = raw
            entries["qualification/v1-tv.json"] = raw
        with self.assertRaises(NegativeAuditError):
            self._run(mutate)


if __name__ == "__main__":
    unittest.main()
