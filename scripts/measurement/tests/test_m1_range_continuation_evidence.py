import copy
import pathlib
import sys
import unittest

SCRIPT_DIR = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from m1_range_continuation_evidence import RangeEvidenceError, verify


class M1RangeContinuationEvidenceTest(unittest.TestCase):
    def rows(self):
        base = {
            "schemaVersion": 1,
            "requestedByteStart": 100,
            "requestedByteEndExclusive": 1100,
            "resourceLength": 4096,
            "requestRange": "bytes=100-1099",
            "retryable": False,
        }
        return [
            {
                **base,
                "caseId": "MATCHING_206",
                "responseStatus": 206,
                "responseContentRange": "bytes 100-1099/4096",
                "outcome": "SUCCESS",
                "emittedBytes": 1000,
                "transportCorrelationId": "lab-1",
            },
            {
                **base,
                "caseId": "FULL_200",
                "responseStatus": 200,
                "responseContentRange": None,
                "outcome": "RANGE_REJECTED",
                "emittedBytes": 0,
                "transportCorrelationId": "lab-2",
            },
            {
                **base,
                "caseId": "WRONG_START_206",
                "responseStatus": 206,
                "responseContentRange": "bytes 101-1099/4096",
                "outcome": "RANGE_REJECTED",
                "emittedBytes": 0,
                "transportCorrelationId": "lab-3",
            },
            {
                **base,
                "caseId": "WRONG_TOTAL_206",
                "responseStatus": 206,
                "responseContentRange": "bytes 100-1099/4097",
                "outcome": "RANGE_REJECTED",
                "emittedBytes": 0,
                "transportCorrelationId": "lab-4",
            },
        ]

    def test_accepts_canonical_matrix(self):
        summary = verify(self.rows())
        self.assertEqual("PASS", summary["status"])
        self.assertEqual(4, summary["caseCount"])

    def test_rejects_missing_case(self):
        with self.assertRaisesRegex(RangeEvidenceError, "case set mismatch"):
            verify(self.rows()[:-1])

    def test_rejects_invalid_response_that_emitted_bytes(self):
        rows = self.rows()
        rows[1] = {**rows[1], "emittedBytes": 4096}
        with self.assertRaisesRegex(RangeEvidenceError, "emitted/appended bytes"):
            verify(rows)

    def test_rejects_invalid_response_marked_success(self):
        rows = self.rows()
        rows[2] = {**rows[2], "outcome": "SUCCESS"}
        with self.assertRaisesRegex(RangeEvidenceError, "was not rejected"):
            verify(rows)

    def test_rejects_wrong_range_header(self):
        rows = copy.deepcopy(self.rows())
        rows[0]["requestRange"] = "bytes=0-999"
        with self.assertRaisesRegex(RangeEvidenceError, "actual Range header mismatch"):
            verify(rows)


if __name__ == "__main__":
    unittest.main()
