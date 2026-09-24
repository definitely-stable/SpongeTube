import copy
import hashlib
import json
import pathlib
import sys
import tempfile
import unittest

SCRIPT_DIR = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

import m1_acceptance as acceptance
from m1_acceptance import AcceptanceError, GATES, verify_index, verify_m1c


class M1AcceptanceIndexTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temp.name)
        self.evidence = self.root / "evidence"
        self.generated = self.root / "generated"
        self.evidence.mkdir()
        self.generated.mkdir()
        self.proof = self.evidence / "proof.json"
        self.proof.write_text("{}\n", encoding="utf-8")
        self.manifest = self.generated / "m1-run-manifest-v1.json"
        fixture_hash = acceptance.sha256(acceptance.FIXTURE_MANIFEST)
        self.manifest.write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "runId": "run-1",
                    "sessionId": "run-1-aggregate",
                    "createdAtUtc": "2026-09-24T12:00:00Z",
                    "gitCommit": "a" * 40,
                    "build": {"workflow": "test"},
                    "fixture": {"id": "F1", "sha256": fixture_hash},
                    "scenario": {"id": "M1-CANONICAL-16-MUST", "hash": "b" * 64},
                    "seed": {"seedId": "CANONICAL-MATRIX", "manifestSha256": "c" * 64},
                    "playbackMode": "SPONGE",
                    "device": {"canonicalApi": 36},
                    "runtime": {"media3": "1.11.1"},
                }
            ),
            encoding="utf-8",
        )
        self.index = {
            "schemaVersion": 1,
            "runId": "run-1",
            "gitCommit": "a" * 40,
            "status": "PASS",
            "gateCount": 16,
            "manifest": "generated/m1-run-manifest-v1.json",
            "gates": [
                {"gateId": gate, "status": "PASS", "proofs": ["proof.json"]}
                for gate in GATES
            ],
            "artifacts": [
                self.artifact("proof.json", self.proof),
                self.artifact("generated/m1-run-manifest-v1.json", self.manifest),
            ],
            "compatibility": {"api23": "PASS", "api34": "PASS"},
            "limitations": ["test"],
        }

    def tearDown(self):
        self.temp.cleanup()

    @staticmethod
    def artifact(name, path):
        return {
            "path": name,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "sizeBytes": path.stat().st_size,
        }

    def verify(self, payload=None):
        verify_index(
            payload or self.index,
            evidence_root=self.evidence,
            generated_root=self.generated,
            expected_git_commit="a" * 40,
        )

    def test_accepts_exact_16_gate_index(self):
        self.verify()

    def test_rejects_15_of_16(self):
        broken = copy.deepcopy(self.index)
        broken["gates"].pop()
        with self.assertRaisesRegex(AcceptanceError, "16/16"):
            self.verify(broken)

    def test_rejects_duplicate_gate(self):
        broken = copy.deepcopy(self.index)
        broken["gates"][-1]["gateId"] = broken["gates"][0]["gateId"]
        with self.assertRaisesRegex(AcceptanceError, "16/16"):
            self.verify(broken)

    def test_rejects_non_pass_gate(self):
        broken = copy.deepcopy(self.index)
        broken["gates"][0]["status"] = "UNKNOWN"
        with self.assertRaisesRegex(AcceptanceError, "non-PASS"):
            self.verify(broken)

    def test_rejects_index_commit_mismatch(self):
        broken = copy.deepcopy(self.index)
        broken["gitCommit"] = "b" * 40
        with self.assertRaisesRegex(AcceptanceError, "git commit mismatch"):
            self.verify(broken)

    def test_rejects_manifest_commit_mismatch(self):
        payload = json.loads(self.manifest.read_text(encoding="utf-8"))
        payload["gitCommit"] = "b" * 40
        self.manifest.write_text(json.dumps(payload), encoding="utf-8")
        self.index["artifacts"][1] = self.artifact(
            "generated/m1-run-manifest-v1.json", self.manifest
        )
        with self.assertRaisesRegex(AcceptanceError, "manifest git commit mismatch"):
            self.verify()

    def test_rejects_mixed_session_identity(self):
        payload = json.loads(self.manifest.read_text(encoding="utf-8"))
        payload["sessionId"] = "foreign-session"
        self.manifest.write_text(json.dumps(payload), encoding="utf-8")
        self.index["artifacts"][1] = self.artifact(
            "generated/m1-run-manifest-v1.json", self.manifest
        )
        with self.assertRaisesRegex(AcceptanceError, "sessionId mismatch"):
            self.verify()

    def test_rejects_wrong_fixture_hash(self):
        payload = json.loads(self.manifest.read_text(encoding="utf-8"))
        payload["fixture"]["sha256"] = "f" * 64
        self.manifest.write_text(json.dumps(payload), encoding="utf-8")
        self.index["artifacts"][1] = self.artifact(
            "generated/m1-run-manifest-v1.json", self.manifest
        )
        with self.assertRaisesRegex(AcceptanceError, "fixture hash mismatch"):
            self.verify()

    def test_rejects_mutated_artifact(self):
        self.proof.write_text('{"mutated":true}\n', encoding="utf-8")
        with self.assertRaisesRegex(AcceptanceError, "digest mismatch"):
            self.verify()

    def test_rejects_unindexed_proof(self):
        broken = copy.deepcopy(self.index)
        broken["gates"][0]["proofs"] = ["missing.json"]
        with self.assertRaisesRegex(AcceptanceError, "unindexed proof"):
            self.verify(broken)

    def test_m1c_rejects_missing_oracle(self):
        smoke = self.root / "smoke"
        with self.assertRaisesRegex(AcceptanceError, "missing M1-C evidence"):
            verify_m1c(smoke)


if __name__ == "__main__":
    unittest.main()
