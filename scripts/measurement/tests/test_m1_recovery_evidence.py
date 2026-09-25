import copy
import pathlib
import sys
import unittest

SCRIPT_DIR = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from m1_recovery_evidence import RecoveryEvidenceError, verify


class M1RecoveryEvidenceTest(unittest.TestCase):
    def coverage(self):
        return {
            "schemaVersion": 2,
            "sessionId": "m1-f",
            "mediaAssetId": "fixture:F1",
            "playheadUs": 0,
            "requiredRepresentations": {
                "video-main": "f1-video-0",
                "audio-main": "f1-audio-1",
            },
            "perTrackPublishedIntervals": {
                "audio-main": [{"startUs": 0, "endUs": 10_000_000}],
                "video-main": [{"startUs": 0, "endUs": 10_000_000}],
            },
            "playableIntervals": [{"startUs": 0, "endUs": 10_000_000}],
            "durablePlayableEndUs": 10_000_000,
            "durableReserveUs": 10_000_000,
        }

    def case(self, scenario="PROCESS_DEATH"):
        return {
            "runId": "m1-f",
            "sessionId": "m1-f",
            "scenarioId": scenario,
            "maxAttemptsPerOwner": 2,
            "media3MaxRetries": 3,
            "prePublishedFetchKeys": [],
            "pidBefore": 101 if scenario == "PROCESS_DEATH" else None,
            "pidAfter": 202 if scenario == "PROCESS_DEATH" else None,
        }

    def test_process_death_requires_new_pid_and_exact_coverage(self):
        coverage = self.coverage()
        result = verify(
            self.case(),
            [],
            [],
            [],
            [],
            [],
            coverage,
            coverage,
            coverage,
        )
        self.assertEqual("PASS", result["status"])
        self.assertTrue(result["process"]["pidChanged"])

    def test_process_death_rejects_same_pid(self):
        case = self.case()
        case["pidAfter"] = case["pidBefore"]
        coverage = self.coverage()
        with self.assertRaisesRegex(RecoveryEvidenceError, "new target process"):
            verify(case, [], [], [], [], [], coverage, coverage, coverage)

    def test_rejects_oracle_mismatch(self):
        coverage = self.coverage()
        oracle = copy.deepcopy(coverage)
        oracle["durableReserveUs"] = 0
        with self.assertRaisesRegex(RecoveryEvidenceError, "independent oracle"):
            verify(self.case(), [], [], [], [], [], coverage, coverage, oracle)

    def test_rejects_overlapping_owners(self):
        case = self.case("N4R-SHORT")
        case["initialDurableReserveUs"] = 10_000_000
        fetch = [
            self.fetch(0, "OWNER_REGISTERED", "f1", "k"),
            self.fetch(1, "ATTEMPT_STARTED", "f1", "k", attempt=1),
            self.fetch(
                2,
                "ATTEMPT_CORRELATED",
                "f1",
                "k",
                attempt=1,
                request="7",
            ),
            self.fetch(3, "OWNER_REGISTERED", "f2", "k"),
            self.fetch(
                4,
                "OWNER_CANCELLED",
                "f2",
                "k",
                outcome="CANCELLED_NO_CONSUMERS",
            ),
            self.fetch(
                5,
                "OWNER_CANCELLED",
                "f1",
                "k",
                outcome="CANCELLED_NO_CONSUMERS",
            ),
        ]
        gate = self.short_gate()
        origin = [
            {
                "requestId": 7,
                "plane": "data",
                "path": "/fixtures/F1/a",
            },
        ]
        coverage = self.coverage()
        with self.assertRaisesRegex(RecoveryEvidenceError, "overlapping"):
            verify(
                case,
                [],
                fetch,
                [],
                gate,
                origin,
                None,
                coverage,
                coverage,
            )

    def test_rejects_gate_request_absent_from_origin_trace(self):
        case = self.case("N4R-SHORT")
        case["initialDurableReserveUs"] = 10_000_000
        coverage = self.coverage()
        with self.assertRaisesRegex(
            RecoveryEvidenceError,
            "absent from data-plane trace",
        ):
            verify(
                case,
                [],
                [],
                [],
                self.short_gate(),
                [],
                None,
                coverage,
                coverage,
            )

    def test_rejects_one_origin_request_claimed_by_two_attempts(self):
        case = self.case("N4R-SHORT")
        case["initialDurableReserveUs"] = 10_000_000
        fetch = [
            self.fetch(0, "OWNER_REGISTERED", "f1", "k1"),
            self.fetch(1, "ATTEMPT_STARTED", "f1", "k1", attempt=1),
            self.fetch(2, "ATTEMPT_PROGRESS", "f1", "k1", attempt=1, start=0, end=50, request="7"),
            self.fetch(3, "ATTEMPT_COMPLETED", "f1", "k1", attempt=1, request="7", outcome="SUCCESS"),
            self.fetch(4, "OWNER_COMPLETED", "f1", "k1", outcome="SUCCESS"),
            self.fetch(5, "OWNER_REGISTERED", "f2", "k2"),
            self.fetch(6, "ATTEMPT_STARTED", "f2", "k2", attempt=1),
            self.fetch(7, "ATTEMPT_PROGRESS", "f2", "k2", attempt=1, start=0, end=50, request="7"),
            self.fetch(8, "ATTEMPT_COMPLETED", "f2", "k2", attempt=1, request="7", outcome="SUCCESS"),
            self.fetch(9, "OWNER_COMPLETED", "f2", "k2", outcome="SUCCESS"),
        ]
        origin = [{"requestId": 7, "plane": "data", "path": "/fixtures/F1/a"}]
        coverage = self.coverage()
        with self.assertRaisesRegex(
            RecoveryEvidenceError,
            "multiple broker attempts",
        ):
            verify(
                case,
                [],
                fetch,
                [],
                self.short_gate(),
                origin,
                None,
                coverage,
                coverage,
            )

    def test_counts_duplicates_across_owner_lifetimes(self):
        case = self.case("N4R-SHORT")
        case["initialDurableReserveUs"] = 10_000_000
        fetch = [
            self.fetch(0, "OWNER_REGISTERED", "f1", "k"),
            self.fetch(1, "ATTEMPT_STARTED", "f1", "k", attempt=1),
            self.fetch(2, "ATTEMPT_PROGRESS", "f1", "k", attempt=1, start=0, end=100, request="7"),
            self.fetch(3, "ATTEMPT_FAILED", "f1", "k", attempt=1, request="7", outcome="RETRYABLE_TRANSPORT_FAILURE"),
            self.fetch(4, "OWNER_FAILED", "f1", "k", outcome="RETRYABLE_TRANSPORT_FAILURE"),
            self.fetch(5, "OWNER_REGISTERED", "f2", "k"),
            self.fetch(6, "ATTEMPT_STARTED", "f2", "k", attempt=1),
            self.fetch(7, "ATTEMPT_PROGRESS", "f2", "k", attempt=1, start=0, end=100, request="8"),
            self.fetch(8, "ATTEMPT_COMPLETED", "f2", "k", attempt=1, request="8", outcome="SUCCESS"),
            self.fetch(9, "OWNER_COMPLETED", "f2", "k", outcome="SUCCESS"),
        ]
        origin = [
            {"requestId": 7, "plane": "data", "path": "/fixtures/F1/a"},
            {"requestId": 8, "plane": "data", "path": "/fixtures/F1/a"},
        ]
        coverage = self.coverage()
        result = verify(
            case, [], fetch, [], self.short_gate(), origin, None, coverage, coverage
        )
        self.assertEqual(100, result["fetch"]["sessionDuplicateRangeBytes"])

    def _m2c_case(self):
        case = self.case("N4R-SHORT")
        case["initialDurableReserveUs"] = 10_000_000
        case.update(
            maxAttemptsPerOwner=1,
            media3MaxRetries=0,
            recoveryPolicyId="sponge-recovery-v1",
            recoveryRemoteAttemptLimit=4,
        )
        return case

    def _sequential_owners(self, count):
        fetch, origin, sequence = [], [], 0
        for index in range(1, count + 1):
            owner, request = f"f{index}", str(6 + index)
            last = index == count
            rows = [
                ("OWNER_REGISTERED", {}),
                ("ATTEMPT_STARTED", {"attempt": 1}),
                ("ATTEMPT_CORRELATED", {"attempt": 1, "request": request}),
                (
                    "ATTEMPT_COMPLETED" if last else "ATTEMPT_FAILED",
                    {
                        "attempt": 1,
                        "request": request,
                        "outcome": "SUCCESS" if last else "RETRYABLE_TRANSPORT_FAILURE",
                    },
                ),
                (
                    "OWNER_COMPLETED" if last else "OWNER_FAILED",
                    {"outcome": "SUCCESS" if last else "RETRYABLE_TRANSPORT_FAILURE"},
                ),
            ]
            for event, fields in rows:
                fetch.append(self.fetch(sequence, event, owner, "k", **fields))
                sequence += 1
            origin.append({"requestId": int(request), "plane": "data", "path": "/fixtures/F1/a"})
        return fetch, origin

    def test_m2c_case_accepts_four_single_attempt_owners_of_one_chain(self):
        fetch, origin = self._sequential_owners(4)
        coverage = self.coverage()
        result = verify(
            self._m2c_case(), [], fetch, [], self.short_gate(), origin, None, coverage, coverage
        )
        self.assertEqual(4, result["fetch"]["physicalAttemptCount"])
        self.assertEqual(1, result["fetch"]["maxAttemptsPerOwner"])
        self.assertEqual(0, result["fetch"]["media3MaxRetries"])

    def test_m2c_case_rejects_a_fifth_owner(self):
        fetch, origin = self._sequential_owners(5)
        coverage = self.coverage()
        with self.assertRaisesRegex(RecoveryEvidenceError, "owner budget exceeded"):
            verify(
                self._m2c_case(), [], fetch, [], self.short_gate(), origin, None,
                coverage, coverage,
            )

    def test_m2c_case_rejects_multiplied_inner_bounds(self):
        case = self._m2c_case()
        case["media3MaxRetries"] = 3
        coverage = self.coverage()
        with self.assertRaisesRegex(RecoveryEvidenceError, "M2-C recovery case"):
            verify(case, [], [], [], self.short_gate(), [], None, coverage, coverage)

    def test_accepts_exhaust_stall_when_remaining_reserve_is_already_player_buffered(self):
        case = self.case("N4R-EXHAUST")
        case["initialDurableReserveUs"] = 10_000_000
        case["observedStallEventSequence"] = 1
        timeline = [
            {
                "schemaVersion": 1,
                "eventSequence": 0,
                "eventElapsedRealtimeNs": 1,
                "sessionId": "m1-f",
                "playerInstanceId": "player-1",
                "event": "PLAYER_PLAYING",
                "playerPositionUs": 0,
                "playerBufferedAheadUs": 10_000_000,
                "durableReserveUs": 10_000_000,
                "playbackState": 3,
                "playWhenReady": True,
                "isPlaying": True,
            },
            {
                "schemaVersion": 1,
                "eventSequence": 1,
                "eventElapsedRealtimeNs": 2,
                "sessionId": "m1-f",
                "playerInstanceId": "player-1",
                "event": "PLAYER_STALL_STARTED",
                "playerPositionUs": 9_878_000,
                "playerBufferedAheadUs": 122_000,
                "durableReserveUs": 122_000,
                "playbackState": 2,
                "playWhenReady": True,
                "isPlaying": False,
            },
        ]
        gate = self.short_gate()
        origin = [
            {"requestId": 7, "plane": "data", "path": "/fixtures/F1/a"},
        ]
        fetch = [
            self.fetch(0, "OWNER_REGISTERED", "f1", "k"),
            self.fetch(1, "ATTEMPT_STARTED", "f1", "k", attempt=1),
            self.fetch(
                2,
                "ATTEMPT_CORRELATED",
                "f1",
                "k",
                attempt=1,
                request="7",
            ),
            self.fetch(
                3,
                "OWNER_CANCELLED",
                "f1",
                "k",
                outcome="CANCELLED_NO_CONSUMERS",
            ),
        ]
        coverage = self.coverage()
        result = verify(
            case,
            timeline,
            fetch,
            [],
            gate,
            origin,
            None,
            coverage,
            coverage,
        )
        self.assertEqual("PASS", result["status"])

    def test_rejects_exhaust_stall_with_durable_reserve_remaining(self):
        case = self.case("N4R-EXHAUST")
        case["initialDurableReserveUs"] = 10_000_000
        case["observedStallEventSequence"] = 0
        timeline = [
            {
                "schemaVersion": 1,
                "eventSequence": 0,
                "eventElapsedRealtimeNs": 1,
                "sessionId": "m1-f",
                "playerInstanceId": "player-1",
                "event": "PLAYER_STALL_STARTED",
                "playerPositionUs": 5_000_000,
                "playerBufferedAheadUs": 0,
                "durableReserveUs": 5_000_000,
                "playbackState": 2,
                "playWhenReady": True,
                "isPlaying": False,
            },
        ]
        gate = self.short_gate()
        origin = [
            {
                "requestId": 7,
                "plane": "data",
                "path": "/fixtures/F1/a",
            },
        ]
        fetch = [
            self.fetch(0, "OWNER_REGISTERED", "f1", "k"),
            self.fetch(1, "ATTEMPT_STARTED", "f1", "k", attempt=1),
            self.fetch(
                2,
                "ATTEMPT_CORRELATED",
                "f1",
                "k",
                attempt=1,
                request="7",
            ),
            self.fetch(
                3,
                "OWNER_CANCELLED",
                "f1",
                "k",
                outcome="CANCELLED_NO_CONSUMERS",
            ),
        ]
        coverage = self.coverage()
        with self.assertRaisesRegex(
            RecoveryEvidenceError,
            "durable reserve outside the Media3 buffered horizon",
        ):
            verify(
                case,
                timeline,
                fetch,
                [],
                gate,
                origin,
                None,
                coverage,
                coverage,
            )

    @staticmethod
    def short_gate():
        return [
            {
                "schemaVersion": 1,
                "serverEventSequence": 0,
                "serverMonotonicNs": 1_000_000,
                "event": "GATE_CLOSE_ACCEPTED",
                "gateGeneration": 1,
                "commandId": "close",
                "requestId": None,
            },
            {
                "schemaVersion": 1,
                "serverEventSequence": 1,
                "serverMonotonicNs": 2_000_000,
                "event": "REQUEST_BLOCKED",
                "gateGeneration": 1,
                "commandId": "close",
                "requestId": 7,
            },
            {
                "schemaVersion": 1,
                "serverEventSequence": 2,
                "serverMonotonicNs": 3_000_000,
                "event": "GATE_OPEN_ACCEPTED",
                "gateGeneration": 1,
                "commandId": "open",
                "requestId": None,
            },
            {
                "schemaVersion": 1,
                "serverEventSequence": 3,
                "serverMonotonicNs": 4_000_000,
                "event": "REQUEST_RELEASED",
                "gateGeneration": 1,
                "commandId": "open",
                "requestId": 7,
            },
        ]

    @staticmethod
    def fetch(seq, event, fetch_id, key, *, attempt=None, start=None, end=None, request=None, outcome=None):
        return {
            "schemaVersion": 3,
            "eventSequence": seq,
            "eventElapsedRealtimeNs": seq,
            "sessionId": "m1-f",
            "fetchId": fetch_id,
            "fetchKey": key,
            "attempt": attempt,
            "attemptCorrelationId": None if attempt is None else f"{fetch_id}:attempt-{attempt}",
            "transportCorrelationId": request,
            "event": event,
            "consumerIds": ["c"],
            "effectivePriority": "PLAYBACK",
            "requestedByteStart": 0,
            "requestedByteEndExclusive": 100,
            "chunkByteStart": start,
            "chunkByteEndExclusive": end,
            "networkBytes": 0 if end is None else end - (start or 0),
            "uniqueRangeBytes": 0 if end is None else end - (start or 0),
            "duplicateRangeBytes": 0,
            "rejectedOrUnmappedBytes": 0,
            "singleFlightJoined": False,
            "outcome": outcome,
        }


if __name__ == "__main__":
    unittest.main()
