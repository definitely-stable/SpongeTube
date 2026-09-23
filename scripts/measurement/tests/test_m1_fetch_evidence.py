import pathlib
import sys
import unittest

SCRIPT_DIR = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from m1_fetch_evidence import RESOURCE_LENGTH, verify


class M1FetchEvidenceTest(unittest.TestCase):
    def test_accepts_one_owner_one_correlated_origin_request(self):
        events = self.events()
        origin = [self.origin()]
        result = verify(events, origin)
        self.assertEqual("PASS", result["status"])
        self.assertEqual(1, result["originRequestCount"])

    def test_rejects_duplicate_physical_origin_requests(self):
        events = self.events()
        origin = [self.origin(), {**self.origin(), "requestId": 8}]
        with self.assertRaisesRegex(
            ValueError,
            "exactly one physical origin request",
        ):
            verify(events, origin)

    def test_rejects_missing_single_flight_join(self):
        events = self.events()
        events[2]["singleFlightJoined"] = False
        with self.assertRaisesRegex(ValueError, "single-flight"):
            verify(events, [self.origin()])

    def test_rejects_runtime_row_that_does_not_match_fetch_schema(self):
        events = self.events()
        events[0]["unexpectedField"] = "schema-drift"
        with self.assertRaisesRegex(ValueError, "unexpected property"):
            verify(events, [self.origin()])

    def test_rejects_transport_origin_correlation_mismatch(self):
        events = self.events()
        completed = next(
            row for row in events
            if row["event"] == "ATTEMPT_COMPLETED"
        )
        completed["transportCorrelationId"] = "999"
        with self.assertRaisesRegex(ValueError, "does not match"):
            verify(events, [self.origin()])

    @staticmethod
    def events():
        common = {
            "schemaVersion": 2,
            "sessionId": "m1-d-origin",
            "fetchId": "fetch-1",
            "fetchKey": "fixture:F1/audio-main/f1-audio-1/segment-1-00001",
            "attempt": None,
            "attemptCorrelationId": None,
            "transportCorrelationId": None,
            "consumerIds": ["reserve-origin"],
            "effectivePriority": "RESERVE",
            "requestedByteStart": 0,
            "requestedByteEndExclusive": RESOURCE_LENGTH,
            "networkBytes": 0,
            "uniqueRangeBytes": 0,
            "duplicateRangeBytes": 0,
            "rejectedOrUnmappedBytes": 0,
            "singleFlightJoined": False,
            "outcome": None,
        }
        rows = []
        def add(event, **changes):
            row = {
                **common,
                "eventSequence": len(rows),
                "eventElapsedRealtimeNs": len(rows),
                "event": event,
                **changes,
            }
            rows.append(row)

        add("OWNER_REGISTERED")
        add(
            "ATTEMPT_STARTED",
            attempt=1,
            attemptCorrelationId="fetch-1:attempt-1",
        )
        add(
            "CONSUMER_JOINED",
            consumerIds=["playback-origin", "reserve-origin"],
            effectivePriority="PLAYBACK",
            singleFlightJoined=True,
        )
        add(
            "PRIORITY_RAISED",
            consumerIds=["playback-origin", "reserve-origin"],
            effectivePriority="PLAYBACK",
            singleFlightJoined=True,
        )
        add(
            "ATTEMPT_COMPLETED",
            attempt=1,
            attemptCorrelationId="fetch-1:attempt-1",
            transportCorrelationId="7",
            consumerIds=["playback-origin", "reserve-origin"],
            effectivePriority="PLAYBACK",
            networkBytes=RESOURCE_LENGTH,
            uniqueRangeBytes=RESOURCE_LENGTH,
            outcome="SUCCESS",
        )
        add(
            "OWNER_COMPLETED",
            consumerIds=["playback-origin", "reserve-origin"],
            effectivePriority="PLAYBACK",
            networkBytes=RESOURCE_LENGTH,
            uniqueRangeBytes=RESOURCE_LENGTH,
            outcome="SUCCESS",
        )
        return rows

    @staticmethod
    def origin():
        return {
            "schemaVersion": 2,
            "sessionId": "m1-d-origin",
            "requestId": 7,
            "plane": "data",
            "path": "/fixtures/F1/segment-1-00001.m4s",
            "status": 206,
            "resolvedRangeStart": 0,
            "resolvedRangeEndExclusive": RESOURCE_LENGTH,
            "bodyBytesWritten": RESOURCE_LENGTH,
            "outcome": "SUCCESS",
        }


if __name__ == "__main__":
    unittest.main()
