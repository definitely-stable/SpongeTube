import copy
import json
import pathlib
import sys
import tempfile
import unittest

SCRIPT_DIR = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from m1_bridge_evidence import EvidenceError, verify


class Evidence:
    """Builds a minimal passing E1-E6 evidence set in memory."""

    def __init__(self):
        self.cases = {}
        self.origin = []
        self._request_id = 0
        for case_id in ("E1", "E2", "E3", "E4", "E5", "E6"):
            self.cases[case_id] = {
                "case": {
                    "schemaVersion": 1,
                    "caseId": case_id,
                    "sessionId": f"m1-e-{case_id}",
                    "seededExtentIds": ["f1:video:0:init", "f1:video:0:1"],
                    "bridgeConfig": {"sessionId": f"m1-e-{case_id}"},
                    "loadErrorPolicy": {"status": "PROVISIONAL", "maxRetries": 3},
                    "playerErrors": [],
                },
                "bridge": [],
                "fetch": [],
            }
        self._e1()
        self._fetch_case("E2", "f1:video:0:10", marker_names=("SEEK_TO_MISSING_ISSUED", "SEEK_TO_MISSING_SETTLED"))
        self._e3()
        self._e4()
        self.cases["E5"]["case"]["seededExtentIds"] = ["f1:video:0:1"]
        self._fetch_case("E5", "f1:video:0:init", local=("f1:video:0:1",))
        self._e6()

    # --- row builders -------------------------------------------------
    def bridge(self, case_id, event, read_id="r1", **fields):
        rows = self.cases[case_id]["bridge"]
        row = {
            "schemaVersion": 1,
            "eventSequence": len(rows),
            "eventElapsedRealtimeNs": len(rows),
            "sessionId": f"m1-e-{case_id}",
            "readId": None if event == "HARNESS_MARKER" else read_id,
            "resourceKey": None if event == "HARNESS_MARKER" else "res",
            "requestedRangeStart": None,
            "requestedRangeEndExclusive": None,
            "event": event,
            "extentId": None,
            "dependencyExtentId": None,
            "fetchId": None,
            "fetchOutcome": None,
            "bytesLocal": None,
            "openReadCalls": None,
            "coverageRefreshCalls": None,
            "markerName": None,
            "fetchEventSequenceWatermark": None,
        }
        row.update(fields)
        rows.append(row)
        return row

    def fetch(self, case_id, event, fetch_id, **fields):
        rows = self.cases[case_id]["fetch"]
        row = {
            "schemaVersion": 2,
            "eventSequence": len(rows),
            "eventElapsedRealtimeNs": len(rows),
            "sessionId": f"m1-e-{case_id}",
            "fetchId": fetch_id,
            "fetchKey": "fixture:F1/k/" + fetch_id,
            "attempt": None,
            "attemptCorrelationId": None,
            "transportCorrelationId": None,
            "event": event,
            "consumerIds": ["bridge:r1:x"],
            "effectivePriority": "PLAYBACK",
            "requestedByteStart": None,
            "requestedByteEndExclusive": None,
            "networkBytes": 0,
            "uniqueRangeBytes": 0,
            "duplicateRangeBytes": 0,
            "rejectedOrUnmappedBytes": 0,
            "singleFlightJoined": False,
            "outcome": None,
        }
        row.update(fields)
        rows.append(row)
        return row

    def origin_row(self, start=0, end=100, path="/fixtures/F1/segment-0-00010.m4s"):
        self._request_id += 1
        self.origin.append({
            "requestId": self._request_id,
            "plane": "data",
            "path": path,
            "status": 206,
            "outcome": "SUCCESS",
            "resolvedRangeStart": start,
            "resolvedRangeEndExclusive": end,
            "bodyBytesWritten": end - start,
        })
        return str(self._request_id)

    def successful_fetch(self, case_id, fetch_id, start=None, end=None, consumer="bridge:r1:x", priority="PLAYBACK"):
        self.fetch(case_id, "OWNER_REGISTERED", fetch_id, consumerIds=[consumer], effectivePriority=priority,
                   requestedByteStart=start, requestedByteEndExclusive=end)
        attempt = {"attempt": 1, "attemptCorrelationId": f"{fetch_id}:attempt-1",
                   "requestedByteStart": start, "requestedByteEndExclusive": end}
        self.fetch(case_id, "ATTEMPT_STARTED", fetch_id, **attempt)
        request_id = self.origin_row(start or 0, end or 100)
        self.fetch(case_id, "ATTEMPT_COMPLETED", fetch_id, outcome="SUCCESS",
                   transportCorrelationId=request_id, networkBytes=100, uniqueRangeBytes=100, **attempt)
        self.fetch(case_id, "OWNER_COMPLETED", fetch_id, outcome="SUCCESS",
                   requestedByteStart=start, requestedByteEndExclusive=end)

    # --- cases --------------------------------------------------------
    def _e1(self):
        self.bridge("E1", "OPEN")
        self.bridge("E1", "LOCAL_SERVE", extentId="f1:video:0:1")
        self.bridge("E1", "HARNESS_MARKER", markerName="SEEK_ISSUED", fetchEventSequenceWatermark=0)
        self.bridge("E1", "OPEN", read_id="r2")
        self.bridge("E1", "LOCAL_SERVE", read_id="r2", extentId="f1:video:0:4")
        self.bridge("E1", "HARNESS_MARKER", markerName="SEEK_SETTLED", fetchEventSequenceWatermark=0)

    def _fetch_case(self, case_id, extent_id, marker_names=(), local=()):
        for name in marker_names[:1]:
            self.bridge(case_id, "HARNESS_MARKER", markerName=name, fetchEventSequenceWatermark=0)
        self.bridge(case_id, "OPEN")
        fetch_id = "fetch-1"
        self.bridge(case_id, "MISS", extentId=extent_id, fetchId=fetch_id)
        self.successful_fetch(case_id, fetch_id)
        self.bridge(case_id, "FETCH_WAIT_END", extentId=extent_id, fetchId=fetch_id, fetchOutcome="SUCCESS")
        self.bridge(case_id, "LOCAL_SERVE", extentId=extent_id)
        for extent in local:
            self.bridge(case_id, "LOCAL_SERVE", extentId=extent)
        for name in marker_names[1:]:
            self.bridge(case_id, "HARNESS_MARKER", markerName=name, fetchEventSequenceWatermark=4)
        self.bridge(case_id, "CLOSE", bytesLocal=100, openReadCalls=1, coverageRefreshCalls=1)

    def _e3(self):
        self.successful_fetch("E3", "fetch-1", consumer="harness-reserve", priority="RESERVE")
        # Insert the join + priority raise before the attempt completes.
        rows = self.cases["E3"]["fetch"]
        raised = dict(rows[0], event="PRIORITY_RAISED", effectivePriority="PLAYBACK",
                      consumerIds=["bridge:r1:f1:video:0:4", "harness-reserve"], singleFlightJoined=True)
        rows.insert(2, raised)
        for index, row in enumerate(rows):
            row["eventSequence"] = index
        self.bridge("E3", "OPEN")
        self.bridge("E3", "JOIN", extentId="f1:video:0:4", fetchId="fetch-1")
        self.bridge("E3", "FETCH_WAIT_END", extentId="f1:video:0:4", fetchId="fetch-1", fetchOutcome="SUCCESS")

    def _e4(self):
        self.cases["E4"]["case"]["splitExtentId"] = "f1:video:0:2"
        self.bridge("E4", "OPEN")
        for index, (start, end) in enumerate(((0, 30), (30, 60), (60, 90)), 1):
            extent = f"f1:video:0:2@{start}-{end}"
            fetch_id = f"fetch-{index}"
            self.bridge("E4", "MISS", extentId=extent, fetchId=fetch_id)
            self.successful_fetch("E4", fetch_id, start=start, end=end)
            self.bridge("E4", "FETCH_WAIT_END", extentId=extent, fetchId=fetch_id, fetchOutcome="SUCCESS")
            self.bridge("E4", "LOCAL_SERVE", extentId=extent)

    def _e6(self):
        self.cases["E6"]["case"].update({"releaseMs": 120, "blockedReadsClosedMs": 180})
        self.bridge("E6", "OPEN")
        self.bridge("E6", "MISS", extentId="f1:video:0:2", fetchId="fetch-1")
        self.fetch("E6", "OWNER_REGISTERED", "fetch-1")
        self.fetch("E6", "ATTEMPT_STARTED", "fetch-1", attempt=1, attemptCorrelationId="fetch-1:attempt-1")
        self.fetch("E6", "OWNER_CANCELLED", "fetch-1", outcome="CANCELLED_NO_CONSUMERS")
        self.bridge("E6", "CLOSE", bytesLocal=0, openReadCalls=0, coverageRefreshCalls=0)

    def write(self, root: pathlib.Path):
        for case_id, data in self.cases.items():
            directory = root / case_id
            directory.mkdir(parents=True)
            (directory / "case.json").write_text(json.dumps(data["case"]), encoding="utf-8")
            (directory / "bridge-events-v1.jsonl").write_text(
                "".join(json.dumps(row) + "\n" for row in data["bridge"]), encoding="utf-8")
            (directory / "fetch-events-v2.jsonl").write_text(
                "".join(json.dumps(row) + "\n" for row in data["fetch"]), encoding="utf-8")


class M1BridgeEvidenceTest(unittest.TestCase):
    def run_verify(self, evidence):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            evidence.write(root)
            return verify(root, evidence.origin)

    def test_accepts_consistent_evidence(self):
        result = self.run_verify(Evidence())
        self.assertEqual("PASS", result["status"])
        self.assertEqual(
            result["origin"]["originDataPlaneRequests"],
            result["origin"]["brokerCompletedAttempts"],
        )

    def test_rejects_hidden_upstream_request(self):
        evidence = Evidence()
        evidence.origin_row()
        with self.assertRaisesRegex(EvidenceError, "hidden upstream"):
            self.run_verify(evidence)

    def test_rejects_manifest_origin_request(self):
        evidence = Evidence()
        evidence.origin_row(path="/fixtures/F1/manifest.mpd")
        with self.assertRaisesRegex(EvidenceError, "packaged bytes"):
            self.run_verify(evidence)

    def test_rejects_miss_inside_cached_seek_window(self):
        evidence = Evidence()
        rows = evidence.cases["E1"]["bridge"]
        miss = dict(rows[3], event="MISS", extentId="f1:video:0:5", fetchId="fetch-9")
        rows.insert(4, miss)
        for index, row in enumerate(rows):
            row["eventSequence"] = index
        # A fully broker-attributed fetch still violates the cached-seek window.
        evidence.successful_fetch("E1", "fetch-9")
        with self.assertRaisesRegex(EvidenceError, "cached seek window"):
            self.run_verify(evidence)

    def test_rejects_broker_attempt_inside_cached_seek_watermarks(self):
        evidence = Evidence()
        evidence.cases["E1"]["bridge"][-1]["fetchEventSequenceWatermark"] = 2
        evidence.fetch("E1", "OWNER_REGISTERED", "fetch-7")
        evidence.fetch("E1", "ATTEMPT_STARTED", "fetch-7", attempt=1, attemptCorrelationId="fetch-7:attempt-1")
        with self.assertRaisesRegex(EvidenceError, "E1"):
            self.run_verify(evidence)

    def test_rejects_schema_drift(self):
        evidence = Evidence()
        evidence.cases["E2"]["bridge"][0]["unexpected"] = True
        with self.assertRaisesRegex(EvidenceError, "unexpected property"):
            self.run_verify(evidence)

    def test_rejects_fetch_of_pre_run_committed_extent(self):
        evidence = Evidence()
        evidence.cases["E2"]["case"]["seededExtentIds"].append("f1:video:0:10")
        with self.assertRaisesRegex(EvidenceError, "pre-run committed"):
            self.run_verify(evidence)

    def test_rejects_duplicate_range_bytes(self):
        evidence = Evidence()
        evidence.cases["E2"]["fetch"][2]["duplicateRangeBytes"] = 10
        evidence.cases["E2"]["fetch"][2]["networkBytes"] = 110
        with self.assertRaisesRegex(EvidenceError, "duplicate range"):
            self.run_verify(evidence)

    def test_rejects_miss_without_broker_owner(self):
        evidence = Evidence()
        evidence.cases["E5"]["bridge"][1]["fetchId"] = "fetch-404"
        with self.assertRaisesRegex(EvidenceError, "terminal"):
            self.run_verify(evidence)

    def test_rejects_preseek_fetch_as_e2_proof(self):
        evidence = Evidence()
        rows = evidence.cases["E2"]["bridge"]
        issued = next(
            row for row in rows
            if row["event"] == "HARNESS_MARKER"
            and row["markerName"] == "SEEK_TO_MISSING_ISSUED"
        )
        rows.remove(issued)
        settled_index = next(
            index for index, row in enumerate(rows)
            if row["event"] == "HARNESS_MARKER"
            and row["markerName"] == "SEEK_TO_MISSING_SETTLED"
        )
        issued["fetchEventSequenceWatermark"] = 4
        rows.insert(settled_index, issued)
        for index, row in enumerate(rows):
            row["eventSequence"] = index

        with self.assertRaisesRegex(EvidenceError, "seek window"):
            self.run_verify(evidence)

    def test_rejects_join_without_single_attempt(self):
        evidence = Evidence()
        rows = evidence.cases["E3"]["fetch"]
        extra = dict(rows[1], attempt=2, attemptCorrelationId="fetch-1:attempt-2")
        rows.insert(2, extra)
        for index, row in enumerate(rows):
            row["eventSequence"] = index
        with self.assertRaisesRegex(EvidenceError, "E3"):
            self.run_verify(evidence)

    def _recovery_owned_e3(self, evidence, reserve_fetch_id):
        for row in evidence.cases["E3"]["fetch"]:
            row["consumerIds"] = [
                "recovery:recovery-1:1" if consumer in ("harness-reserve",) else consumer
                for consumer in row["consumerIds"]
                if not consumer.startswith("bridge:")
            ]
        evidence.cases["E3"]["case"]["reserveFetchId"] = reserve_fetch_id

    def test_accepts_m2c_recovery_owned_reserve_owner(self):
        evidence = Evidence()
        self._recovery_owned_e3(evidence, "fetch-1")
        self.run_verify(evidence)

    def test_rejects_recovery_owner_not_bound_to_the_reserve_lease(self):
        evidence = Evidence()
        self._recovery_owned_e3(evidence, "fetch-9")
        with self.assertRaisesRegex(EvidenceError, "E3"):
            self.run_verify(evidence)

    def test_rejects_recovery_owner_without_reserve_fetch_id(self):
        evidence = Evidence()
        self._recovery_owned_e3(evidence, None)
        del evidence.cases["E3"]["case"]["reserveFetchId"]
        with self.assertRaisesRegex(EvidenceError, "E3"):
            self.run_verify(evidence)

    def test_rejects_slow_release(self):
        evidence = Evidence()
        evidence.cases["E6"]["case"]["blockedReadsClosedMs"] = 1_500
        with self.assertRaisesRegex(EvidenceError, "E6"):
            self.run_verify(evidence)

    def test_rejects_missing_case(self):
        evidence = Evidence()
        del evidence.cases["E4"]
        with self.assertRaisesRegex(EvidenceError, "missing case manifest"):
            self.run_verify(evidence)


if __name__ == "__main__":
    unittest.main()
