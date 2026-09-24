import copy
import json
import pathlib
import sys
import tempfile
import unittest

SCRIPT_DIR = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from m1_fetch_cancellation_evidence import (
    FetchCancellationEvidenceError,
    verify,
)


class M1FetchCancellationEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temp.name)
        self.write_joined_release()
        self.write_barrier_restart()
        self.write_late_success()

    def tearDown(self):
        self.temp.cleanup()

    def event(
        self,
        sequence,
        event,
        fetch_id,
        *,
        consumers=None,
        joined=False,
        outcome=None,
    ):
        return {
            "schemaVersion": 3,
            "eventSequence": sequence,
            "eventElapsedRealtimeNs": sequence,
            "sessionId": "m1-acc07",
            "fetchId": fetch_id,
            "fetchKey": "fixture:F1/video/0-4",
            "attempt": 1 if event.startswith("ATTEMPT_") else None,
            "attemptCorrelationId":
                f"{fetch_id}:attempt-1" if event.startswith("ATTEMPT_") else None,
            "transportCorrelationId": None,
            "event": event,
            "consumerIds": consumers or [],
            "effectivePriority": "RESERVE",
            "requestedByteStart": 0,
            "requestedByteEndExclusive": 4,
            "chunkByteStart": 0 if event == "ATTEMPT_PROGRESS" else None,
            "chunkByteEndExclusive": 4 if event == "ATTEMPT_PROGRESS" else None,
            "networkBytes": 4 if event in {"ATTEMPT_PROGRESS", "ATTEMPT_COMPLETED", "OWNER_COMPLETED"} else 0,
            "uniqueRangeBytes": 4 if event in {"ATTEMPT_PROGRESS", "ATTEMPT_COMPLETED", "OWNER_COMPLETED"} else 0,
            "duplicateRangeBytes": 0,
            "rejectedOrUnmappedBytes": 0,
            "singleFlightJoined": joined,
            "outcome": outcome,
        }

    def write_case(self, case_id, observation, events):
        root = self.root / case_id
        root.mkdir()
        (root / "case.json").write_text(json.dumps(observation), encoding="utf-8")
        (root / "fetch-events-v3.jsonl").write_text(
            "\n".join(json.dumps(row) for row in events) + "\n",
            encoding="utf-8",
        )

    def write_joined_release(self):
        fid = "fetch-1"
        self.write_case(
            "JOINED_CONSUMER_RELEASE",
            {
                "schemaVersion": 1,
                "caseId": "JOINED_CONSUMER_RELEASE",
                "executions": 1,
                "waitingBeforeTerminal": False,
                "replacementDisposition": None,
                "sameFetchId": True,
                "terminalOutcome": "SUCCESS",
            },
            [
                self.event(0, "OWNER_REGISTERED", fid, consumers=["one"]),
                self.event(1, "ATTEMPT_STARTED", fid, consumers=["one"]),
                self.event(2, "CONSUMER_JOINED", fid, consumers=["one", "two"], joined=True),
                self.event(3, "CONSUMER_RELEASED", fid, consumers=["two"], joined=True),
                self.event(4, "ATTEMPT_PROGRESS", fid, consumers=["two"], joined=True),
                self.event(5, "ATTEMPT_COMPLETED", fid, consumers=["two"], joined=True, outcome="SUCCESS"),
                self.event(6, "OWNER_COMPLETED", fid, consumers=["two"], joined=True, outcome="SUCCESS"),
            ],
        )

    def write_barrier_restart(self):
        first = "fetch-1"
        second = "fetch-2"
        self.write_case(
            "CANCELLING_BARRIER_RESTART",
            {
                "schemaVersion": 1,
                "caseId": "CANCELLING_BARRIER_RESTART",
                "executions": 2,
                "waitingBeforeTerminal": True,
                "replacementDisposition": "NEW_OWNER",
                "sameFetchId": False,
                "terminalOutcome": "SUCCESS",
            },
            [
                self.event(0, "OWNER_REGISTERED", first, consumers=["first-owner"]),
                self.event(1, "ATTEMPT_STARTED", first, consumers=["first-owner"]),
                self.event(2, "CONSUMER_RELEASED", first),
                self.event(3, "OWNER_CANCELLED", first, outcome="CANCELLED_NO_CONSUMERS"),
                self.event(4, "OWNER_REGISTERED", second, consumers=["replacement"]),
                self.event(5, "ATTEMPT_STARTED", second, consumers=["replacement"]),
                self.event(6, "ATTEMPT_PROGRESS", second, consumers=["replacement"]),
                self.event(7, "ATTEMPT_COMPLETED", second, consumers=["replacement"], outcome="SUCCESS"),
                self.event(8, "OWNER_COMPLETED", second, consumers=["replacement"], outcome="SUCCESS"),
            ],
        )

    def write_late_success(self):
        fid = "fetch-1"
        self.write_case(
            "CANCELLING_BARRIER_LATE_SUCCESS",
            {
                "schemaVersion": 1,
                "caseId": "CANCELLING_BARRIER_LATE_SUCCESS",
                "executions": 1,
                "waitingBeforeTerminal": True,
                "replacementDisposition": "WAITED_CANCELLING",
                "sameFetchId": True,
                "terminalOutcome": "SUCCESS",
            },
            [
                self.event(0, "OWNER_REGISTERED", fid, consumers=["late-success-owner"]),
                self.event(1, "ATTEMPT_STARTED", fid, consumers=["late-success-owner"]),
                self.event(2, "ATTEMPT_PROGRESS", fid, consumers=["late-success-owner"]),
                self.event(3, "CONSUMER_RELEASED", fid),
                self.event(4, "ATTEMPT_COMPLETED", fid, outcome="SUCCESS"),
                self.event(5, "OWNER_COMPLETED", fid, outcome="SUCCESS"),
            ],
        )

    def test_accepts_canonical_matrix(self):
        summary = verify(self.root)
        self.assertEqual("PASS", summary["status"])
        self.assertEqual(3, summary["caseCount"])

    def test_rejects_missing_case(self):
        target = self.root / "CANCELLING_BARRIER_LATE_SUCCESS"
        for child in target.iterdir():
            child.unlink()
        target.rmdir()
        with self.assertRaisesRegex(
            FetchCancellationEvidenceError,
            "case set mismatch",
        ):
            verify(self.root)

    def test_rejects_joined_release_that_cancelled_owner(self):
        path = self.root / "JOINED_CONSUMER_RELEASE" / "fetch-events-v3.jsonl"
        rows = [json.loads(line) for line in path.read_text().splitlines()]
        rows[-1]["event"] = "OWNER_CANCELLED"
        rows[-1]["outcome"] = "CANCELLED_NO_CONSUMERS"
        path.write_text("\n".join(json.dumps(row) for row in rows) + "\n")
        with self.assertRaisesRegex(
            FetchCancellationEvidenceError,
            "cancelled the shared owner",
        ):
            verify(self.root)

    def test_rejects_replacement_before_cancel_terminal(self):
        path = self.root / "CANCELLING_BARRIER_RESTART" / "fetch-events-v3.jsonl"
        rows = [json.loads(line) for line in path.read_text().splitlines()]
        rows[3], rows[4] = rows[4], rows[3]
        for index, row in enumerate(rows):
            row["eventSequence"] = index
            row["eventElapsedRealtimeNs"] = index
        path.write_text("\n".join(json.dumps(row) for row in rows) + "\n")
        with self.assertRaisesRegex(
            FetchCancellationEvidenceError,
            "before cancelling owner was terminal",
        ):
            verify(self.root)

    def test_rejects_late_success_refetch(self):
        case_path = self.root / "CANCELLING_BARRIER_LATE_SUCCESS" / "case.json"
        case = json.loads(case_path.read_text())
        case["executions"] = 2
        case_path.write_text(json.dumps(case))
        with self.assertRaisesRegex(
            FetchCancellationEvidenceError,
            "hidden refetch",
        ):
            verify(self.root)


if __name__ == "__main__":
    unittest.main()
