"""Host falsification cases for N4R's *physical* body gate evidence."""

from __future__ import annotations

import copy
import json
import pathlib
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "measurement"))

from m4_n4r_reserve_verifier import verify_gate, verify_origin
from m4_reserve_verifier import expected_f1_units


class N4RReserveVerifierTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        fixture = ROOT / "test-fixtures" / "media"
        manifest = json.loads((fixture / "manifest.json").read_text())
        cls.units, cls.end_us = expected_f1_units(
            (fixture / "F1" / "manifest.mpd").read_bytes(),
            manifest,
        )

    def trace(self):
        expected = [self.units[0]] * 3 + self.units[1:]
        return [
            {
                "schemaVersion": 2,
                "requestId": n + 10,
                "sessionId": "m4-a1b-n4r",
                "profileId": "N4R",
                "plane": "data",
                "method": "GET",
                "path": "/fixtures/" + unit.path,
                "status": 206,
                "outcome": "SUCCESS" if n >= 2 else "CLIENT_DISCONNECTED",
                "resolvedRangeStart": 0,
                "resolvedRangeEndExclusive": unit.length,
                "bodyBytesWritten": unit.length if n >= 2 else 0,
                "noProgressWaitMs": 2100 if n < 2 else 0,
                "firstBodyWriteAtMonotonicNs": 280 if n < 2 else 280 + n,
            }
            for n, unit in enumerate(expected)
        ]

    def gate(self):
        return [
            dict(schemaVersion=1, serverEventSequence=1,
                 serverMonotonicNs=100, event="GATE_CLOSE_ACCEPTED",
                 gateGeneration=1, commandId="m4-n4r-close", requestId=None),
            dict(schemaVersion=1, serverEventSequence=2,
                 serverMonotonicNs=120, event="REQUEST_BLOCKED",
                 gateGeneration=1, commandId="m4-n4r-close", requestId=10),
            dict(schemaVersion=1, serverEventSequence=3,
                 serverMonotonicNs=140, event="REQUEST_BLOCKED",
                 gateGeneration=1, commandId="m4-n4r-close", requestId=11),
            dict(schemaVersion=1, serverEventSequence=4,
                 serverMonotonicNs=200, event="GATE_OPEN_ACCEPTED",
                 gateGeneration=1, commandId="m4-n4r-restore", requestId=None),
            dict(schemaVersion=1, serverEventSequence=5,
                 serverMonotonicNs=220, event="REQUEST_RELEASED",
                 gateGeneration=1, commandId="m4-n4r-restore", requestId=10),
            dict(schemaVersion=1, serverEventSequence=6,
                 serverMonotonicNs=240, event="REQUEST_RELEASED",
                 gateGeneration=1, commandId="m4-n4r-restore", requestId=11),
        ]

    def test_canonical_f1_horizon(self):
        self.assertEqual(9_941_333, self.end_us)
        self.assertEqual(4, len(self.units))

    def test_accepts_six_real_requests_two_block_release_pairs(self):
        rows = verify_origin(self.trace(), self.units)
        self.assertEqual(6, len(rows))
        verify_gate(self.gate(), rows)

    def test_rejects_replayed_physical_origin_request(self):
        rows = self.trace()
        rows.append(copy.deepcopy(rows[0]))
        with self.assertRaisesRegex(ValueError, "physical request count"):
            verify_origin(rows, self.units)

    def test_rejects_missing_physical_attempt(self):
        with self.assertRaisesRegex(ValueError, "physical request count"):
            verify_origin(self.trace()[:-1], self.units)

    def test_rejects_duplicate_origin_id(self):
        rows = self.trace()
        rows[1]["requestId"] = rows[0]["requestId"]
        with self.assertRaisesRegex(ValueError, "duplicate Media Lab"):
            verify_origin(rows, self.units)

    def test_rejects_wrong_origin_order(self):
        rows = self.trace()
        rows[3]["path"] = "/fixtures/F1/unrelated.m4s"
        with self.assertRaisesRegex(ValueError, "unexpected N4R origin"):
            verify_origin(rows, self.units)

    def test_rejects_wrong_origin_profile(self):
        rows = self.trace()
        rows[0]["profileId"] = "N0"
        with self.assertRaisesRegex(ValueError, "not N4R"):
            verify_origin(rows, self.units)

    def test_rejects_non_range_http(self):
        rows = self.trace()
        rows[2]["status"] = 200
        with self.assertRaisesRegex(ValueError, "HTTP 206"):
            verify_origin(rows, self.units)

    def test_rejects_unfinished_restored_body(self):
        rows = self.trace()
        rows[2]["bodyBytesWritten"] -= 1
        with self.assertRaisesRegex(ValueError, "did not deliver complete"):
            verify_origin(rows, self.units)

    def test_rejects_fake_timeout_without_physical_wait(self):
        rows = self.trace()
        rows[0]["noProgressWaitMs"] = 0
        with self.assertRaisesRegex(ValueError, "no physical no-progress"):
            verify_origin(rows, self.units)

    def test_rejects_missing_gate_release(self):
        gate = self.gate()
        gate.pop()
        with self.assertRaisesRegex(ValueError, "missing real body block/release"):
            verify_gate(gate, self.trace())

    def test_rejects_gate_resume_before_body_block(self):
        gate = self.gate()
        gate[1]["serverEventSequence"] = 9
        with self.assertRaisesRegex(ValueError, "gate event sequence|across gate restore"):
            verify_gate(gate, self.trace())

    def test_rejects_body_write_before_gate_open(self):
        rows = self.trace()
        rows[0]["firstBodyWriteAtMonotonicNs"] = 190
        with self.assertRaisesRegex(ValueError, "wrote blocked bytes"):
            verify_gate(self.gate(), rows)

    def test_rejects_wrong_gate_generation(self):
        gate = self.gate()
        gate[3]["gateGeneration"] = 2
        with self.assertRaisesRegex(ValueError, "generation"):
            verify_gate(gate, self.trace())

    def test_rejects_wrong_gate_command_lineage(self):
        gate = self.gate()
        gate[4]["commandId"] = "unrelated-open"
        with self.assertRaisesRegex(ValueError, "wrong command lineage"):
            verify_gate(gate, self.trace())

    def test_rejects_extra_blocked_request(self):
        gate = self.gate()
        extra = copy.deepcopy(gate[1])
        extra["serverEventSequence"] = 7
        extra["requestId"] = 12
        gate.append(extra)
        with self.assertRaisesRegex(ValueError, "unexpected additional physical"):
            verify_gate(gate, self.trace())


if __name__ == "__main__":
    unittest.main()
