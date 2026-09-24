import copy
import json
import pathlib
import sys
import tempfile
import unittest

SCRIPT_DIR = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from m1_storage_evidence import (
    EXPECTED_CASES,
    StorageEvidenceError,
    verify,
)


class M1StorageEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temp.name)
        self.cases = self.root / "cases"
        self.verified = self.root / "verified"
        self.cases.mkdir()
        self.verified.mkdir()
        for case_id, (gate, expected_end) in EXPECTED_CASES.items():
            self.write_case(case_id, gate, expected_end)

    def tearDown(self):
        self.temp.cleanup()

    def write_case(self, case_id, gate, expected_end):
        case_root = self.cases / case_id
        verified_root = self.verified / case_id
        case_root.mkdir()
        verified_root.mkdir()

        fault = None
        mutation = None
        if case_id.startswith("acc-02-"):
            fault = case_id.removeprefix("acc-02-").upper()
        if case_id.startswith("acc-03-"):
            mutation = case_id.removeprefix("acc-03-").upper()

        session = "session-" + case_id
        case = {
            "schemaVersion": 1,
            "gateId": gate,
            "caseId": case_id,
            "sessionId": session,
            "mediaAssetId": "fixture:M1-B",
            "trackId": "video-main",
            "representationId": "m1-b-video",
            "playheadUs": 0,
            "expectedPlayableEndUs": expected_end,
            "expectedQuarantinedExtents": 1 if gate == "M1-ACC-03" else 0,
            "faultPoint": fault,
            "mutation": mutation,
        }
        (case_root / "case.json").write_text(json.dumps(case), encoding="utf-8")

        if gate == "M1-ACC-01":
            states = ["RECEIVING", "SEALED", "VERIFIED", "DURABLE", "PUBLISHED"]
            recovery = self.recovery(verified=1)
        elif gate == "M1-ACC-02":
            if fault == "AFTER_METADATA_COMMIT_BEFORE_PUBLISHED_EVENT":
                states = ["RECEIVING", "SEALED", "VERIFIED", "DURABLE"]
                recovery = self.recovery(verified=1)
            elif fault == "AFTER_PUBLISH":
                states = ["RECEIVING", "SEALED", "VERIFIED", "DURABLE", "PUBLISHED"]
                recovery = self.recovery(verified=1)
            elif fault == "AFTER_DURABLE_BEFORE_PUBLISH":
                states = ["RECEIVING", "SEALED", "VERIFIED", "DURABLE"]
                recovery = self.recovery(orphan=1)
            elif fault == "AFTER_VERIFY":
                states = ["RECEIVING", "SEALED", "VERIFIED"]
                recovery = self.recovery(parts=1)
            elif fault == "AFTER_SEAL":
                states = ["RECEIVING", "SEALED"]
                recovery = self.recovery(parts=1)
            else:
                states = ["RECEIVING"]
                recovery = self.recovery(parts=1)
        else:
            states = ["RECEIVING", "SEALED", "VERIFIED", "DURABLE", "PUBLISHED"]
            recovery = self.recovery(quarantined=1)

        (case_root / "recovery-report.json").write_text(
            json.dumps(recovery),
            encoding="utf-8",
        )
        with (case_root / "extent-events-v1.jsonl").open("w", encoding="utf-8") as stream:
            for seq, state in enumerate(states):
                stream.write(json.dumps(self.event(session, seq, state)) + "\n")

        valid = expected_end is not None
        extents = (
            [
                {
                    "publicationState": "PUBLISHED",
                    "integrityState": "VALID",
                }
            ]
            if valid
            else []
        )
        (verified_root / "committed-extents.json").write_text(
            json.dumps({"sessionId": session, "extents": extents}),
            encoding="utf-8",
        )
        (verified_root / "verified-extent-files.json").write_text(
            json.dumps({"sessionId": session}),
            encoding="utf-8",
        )
        intervals = [] if expected_end is None else [{"startUs": 0, "endUs": expected_end}]
        (verified_root / "oracle-coverage.json").write_text(
            json.dumps(
                {
                    "playableIntervals": intervals,
                    "durablePlayableEndUs": expected_end,
                    "durableReserveUs": expected_end or 0,
                }
            ),
            encoding="utf-8",
        )

    @staticmethod
    def recovery(parts=0, orphan=0, quarantined=0, verified=0):
        return {
            "deletedPartFiles": parts,
            "deletedOrphanFiles": orphan,
            "deletedQuarantinedFiles": quarantined,
            "quarantinedExtents": quarantined,
            "verifiedPublishedExtents": verified,
        }

    @staticmethod
    def event(session, sequence, state):
        return {
            "schemaVersion": 1,
            "eventSequence": sequence,
            "eventElapsedRealtimeNs": sequence,
            "sessionId": session,
            "extentId": "extent",
            "fetchId": None,
            "state": state,
            "integrityState": "VALID" if state in {"VERIFIED", "DURABLE", "PUBLISHED"} else "UNKNOWN",
            "trackId": "video-main",
            "representationId": "m1-b-video",
            "mediaStartUs": 0,
            "mediaEndUs": 10_000_000,
            "dependencyExtentIds": [],
            "byteStart": 0,
            "byteEndExclusive": 10,
            "expectedLength": 10,
            "actualLength": 10 if state in {"VERIFIED", "DURABLE", "PUBLISHED"} else None,
            "sha256": "a" * 64 if state in {"VERIFIED", "DURABLE", "PUBLISHED"} else None,
            "storagePath": "extents/x/extent.bin" if state in {"DURABLE", "PUBLISHED"} else None,
        }

    def test_accepts_complete_canonical_case_set(self):
        summary = verify(self.cases, self.verified)
        self.assertEqual("PASS", summary["status"])
        self.assertEqual(9, summary["caseCount"])
        self.assertEqual(6, summary["gateCounts"]["M1-ACC-02"])

    def test_rejects_missing_canonical_case(self):
        target = self.cases / "acc-03-corrupt"
        for child in target.iterdir():
            child.unlink()
        target.rmdir()
        with self.assertRaisesRegex(StorageEvidenceError, "case set mismatch"):
            verify(self.cases, self.verified)

    def test_rejects_inflated_oracle_coverage(self):
        path = self.verified / "acc-02-after_receiving" / "oracle-coverage.json"
        bad = json.loads(path.read_text(encoding="utf-8"))
        bad["playableIntervals"] = [{"startUs": 0, "endUs": 10_000_000}]
        bad["durablePlayableEndUs"] = 10_000_000
        bad["durableReserveUs"] = 10_000_000
        path.write_text(json.dumps(bad), encoding="utf-8")
        with self.assertRaisesRegex(StorageEvidenceError, "zero reconstructed coverage"):
            verify(self.cases, self.verified)

    def test_rejects_published_event_before_metadata_commit(self):
        path = self.cases / "acc-02-after_verify" / "extent-events-v1.jsonl"
        rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
        bad = copy.deepcopy(rows[-1])
        bad["eventSequence"] = len(rows)
        bad["state"] = "PUBLISHED"
        rows.append(bad)
        path.write_text(
            "\n".join(json.dumps(row) for row in rows) + "\n",
            encoding="utf-8",
        )
        with self.assertRaisesRegex(StorageEvidenceError, "pre-publish crash emitted PUBLISHED"):
            verify(self.cases, self.verified)


if __name__ == "__main__":
    unittest.main()
