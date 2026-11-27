"""Falsify isolated M4-A1c lifecycle origin/gate assertions."""
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from m4_ui_lifecycle_verifier import verify


class LifecycleVerifierTests(unittest.TestCase):
    def setUp(self):
        self.origin = [{"plane": "data", "sessionId": "m4-a1c-f1-debug",
                        "profileId": "N4R", "requestId": 7,
                        "firstBodyWriteAtMonotonicNs": None}]
        self.gate = [
            dict(schemaVersion=1, serverEventSequence=1, gateGeneration=1,
                 event="GATE_CLOSE_ACCEPTED", commandId="m4-ui-lifecycle-close"),
            dict(schemaVersion=1, serverEventSequence=2, gateGeneration=1,
                 event="REQUEST_BLOCKED", commandId="m4-ui-lifecycle-close", requestId=7),
            dict(schemaVersion=1, serverEventSequence=3, gateGeneration=1,
                 event="GATE_OPEN_ACCEPTED", commandId="m4-ui-lifecycle-restore",
                 serverMonotonicNs=300),
        ]

    def test_valid(self):
        verify(self.origin, self.gate)

    def test_rejects_duplicate_request(self):
        with self.assertRaisesRegex(ValueError, "exactly one"):
            verify(self.origin + [dict(self.origin[0], requestId=8)], self.gate)

    def test_rejects_no_request(self):
        with self.assertRaisesRegex(ValueError, "exactly one"):
            verify([], self.gate)

    def test_rejects_mismatched_request_identity(self):
        self.gate[1]["requestId"] = 8
        with self.assertRaisesRegex(ValueError, "identity"):
            verify(self.origin, self.gate)

    def test_rejects_missing_block(self):
        with self.assertRaisesRegex(ValueError, "REQUEST_BLOCKED"):
            verify(self.origin, [self.gate[0], self.gate[2]])

    def test_rejects_pre_restore_progress(self):
        self.origin[0]["firstBodyWriteAtMonotonicNs"] = 299
        with self.assertRaisesRegex(ValueError, "progressed"):
            verify(self.origin, self.gate)

    def test_rejects_wrong_generation(self):
        self.gate[1]["gateGeneration"] = 2
        with self.assertRaisesRegex(ValueError, "generation"):
            verify(self.origin, self.gate)

    def test_rejects_out_of_order_trace(self):
        with self.assertRaisesRegex(ValueError, "sequence"):
            verify(self.origin, list(reversed(self.gate)))


if __name__ == "__main__":
    unittest.main()
