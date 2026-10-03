from __future__ import annotations

import importlib.util
import json
import pathlib
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]


def load_module():
    path = ROOT / "scripts" / "ci" / "verify-m2-g2-transport.py"
    spec = importlib.util.spec_from_file_location("verify_m2_g2_transport", path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


verifier = load_module()


def write(path: pathlib.Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value), encoding="utf-8")


class G2TransportVerifierTest(unittest.TestCase):
    def setUp(self):
        self.plan = {"runId": "m2-g2-n6-api36", "scenario": {"hash": "a" * 64}}

    def make_trial(self, root: pathlib.Path, *, trigger_count: int = 1) -> pathlib.Path:
        trial_id = "block-01-pos-01-http-url-connection-route-bound"
        trial = root / trial_id
        harness = trial / "harness"
        write(harness / "toxiproxy-active-state.json", verifier.EXPECTED_ACTIVE)
        write(harness / "toxiproxy-disarmed-state.json", verifier.EXPECTED_DISARMED)
        write(harness / "toxiproxy-clean-state.json", verifier.EXPECTED_FINAL)
        write(
            harness / "fault-harness-events.json",
            {
                "runId": self.plan["runId"],
                "scenarioHash": self.plan["scenario"]["hash"],
                "clockDomain": "HOST_FAULT_MONOTONIC",
                "harness": {
                    "harnessId": "sponge-transport-harness",
                    "harnessVersion": "1",
                    "toolId": "toxiproxy",
                    "toolVersion": "2.12.0",
                },
                "events": [
                    {"operation": "HARNESS_STARTED", "elapsedRealtimeNs": 10},
                    {"operation": "FAULT_ARMED", "elapsedRealtimeNs": 20},
                    {
                        "operation": "FAULT_APPLIED",
                        "elapsedRealtimeNs": 30,
                        "parameters": {"timeout": 0, "toxicType": "reset_peer"},
                    },
                    {"operation": "FAULT_REMOVED", "elapsedRealtimeNs": 50},
                    {"operation": "HARNESS_STOPPED", "elapsedRealtimeNs": 60},
                ],
            },
        )
        write(
            trial / "reset-trigger.json",
            {
                "schemaVersion": 1,
                "trialId": trial_id,
                "signal": "ATTEMPT_FAILED",
                "fetchId": "fetch-1",
                "attemptCorrelationId": "fetch-1:attempt-1",
                "controlProtocol": "ADB_REVERSE_LOOPBACK_HTTP_V1",
                "hostObservationClockDomain": "HOST_FAULT_MONOTONIC",
                "hostObservedAtElapsedRealtimeNs": 40,
                "faultDisarmedAtElapsedRealtimeNs": 55,
                "failureSignalsObservedAtTrigger": trigger_count,
                "originCountBeforeTrial": 1,
                "originCountAtTrigger": 2,
                "originRequestIdsBeforeDisarm": [2],
            },
        )
        return trial

    def test_harness_binds_disarm_to_first_android_failure_barrier(self):
        with tempfile.TemporaryDirectory() as tmp:
            trial = self.make_trial(pathlib.Path(tmp))
            (
                fetch_id,
                attempt_correlation,
                origin_before,
                origin_at,
                first_owner_ids,
            ) = verifier.validate_harness(
                trial,
                plan=self.plan,
                trial_id=trial.name,
            )
            self.assertEqual("fetch-1", fetch_id)
            self.assertEqual("fetch-1:attempt-1", attempt_correlation)
            self.assertEqual(1, origin_before)
            self.assertEqual(2, origin_at)
            self.assertEqual([2], first_owner_ids)

    def test_harness_rejects_foreign_attempt_correlation(self):
        with tempfile.TemporaryDirectory() as tmp:
            trial = self.make_trial(pathlib.Path(tmp))
            trigger = json.loads((trial / "reset-trigger.json").read_text())
            trigger["attemptCorrelationId"] = "17"
            write(trial / "reset-trigger.json", trigger)
            with self.assertRaisesRegex(
                verifier.TransportResetEvidenceError,
                "application-attempt correlation drift",
            ):
                verifier.validate_harness(
                    trial,
                    plan=self.plan,
                    trial_id=trial.name,
                )

    def test_harness_rejects_multiple_failures_visible_before_disarm(self):
        with tempfile.TemporaryDirectory() as tmp:
            trial = self.make_trial(pathlib.Path(tmp), trigger_count=2)
            with self.assertRaisesRegex(
                verifier.TransportResetEvidenceError,
                "multiple owner failures",
            ):
                verifier.validate_harness(
                    trial,
                    plan=self.plan,
                    trial_id=trial.name,
                )

    def test_origin_request_ids_reject_duplicate_after_barrier(self):
        with self.assertRaisesRegex(
            verifier.TransportResetEvidenceError,
            "duplicate/missing origin request id",
        ):
            verifier.validate_origin_request_ids(
                [{"requestId": 7}, {"requestId": 7}],
                trial_id="trial-1",
            )

    def test_origin_request_ids_reject_missing_id(self):
        with self.assertRaisesRegex(
            verifier.TransportResetEvidenceError,
            "duplicate/missing origin request id",
        ):
            verifier.validate_origin_request_ids(
                [{"requestId": 7}, {"requestId": None}],
                trial_id="trial-1",
            )

    def test_barrier_retry_visibility_is_observable_per_owner(self):
        row = {
            "trialId": "trial-1",
            "recovery": {
                "originRequestCount": 0,
                "internalRetryVisibility": "OPAQUE",
                "internalRetryCount": None,
            },
            "performanceSampleEligible": False,
            "limitations": ["RAW_DEVICE_ROW_REQUIRES_HOST_RETRY_FINALIZATION"],
        }
        first = [{"requestId": 2}, {"requestId": 3}]
        second = [{"requestId": 4}]
        finalized = verifier.finalize_retry_visibility(
            row,
            first_owner_origin=first,
            second_owner_origin=second,
        )
        self.assertEqual("OBSERVABLE", finalized["recovery"]["internalRetryVisibility"])
        self.assertEqual(1, finalized["recovery"]["internalRetryCount"])
        self.assertEqual(3, finalized["recovery"]["originRequestCount"])
        self.assertTrue(finalized["performanceSampleEligible"])

    def test_phase_timing_schema_accepts_canonical_n6_family(self):
        schema = verifier.shared.load_json(verifier.PHASE_SCHEMA)
        document = {
            "schemaVersion": 1,
            "runId": "m2-g2-n6-api36",
            "pairId": "m2-g2-n6-api36-cold",
            "scenarioFamily": "N6",
            "clockDomain": "ANDROID_MONOTONIC",
            "rows": [{
                "trialId": "trial-1",
                "orderingBlock": 1,
                "positionInBlock": 1,
                "backendId": "HTTP_URL_CONNECTION_ROUTE_BOUND",
                "firstBrokerChunkUs": 10,
                "chainCompletionUs": 20,
                "physicalAttempts": [{
                    "ownerOrdinal": 1,
                    "attemptStartUs": 0,
                    "responseHeadersUs": None,
                    "firstTransportBodyUs": None,
                    "responseBodyCompleteUs": None,
                    "attemptTerminalUs": 5,
                    "terminal": "ATTEMPT_FAILED",
                }, {
                    "ownerOrdinal": 2,
                    "attemptStartUs": 6,
                    "responseHeadersUs": 7,
                    "firstTransportBodyUs": 8,
                    "responseBodyCompleteUs": 9,
                    "attemptTerminalUs": 10,
                    "terminal": "ATTEMPT_COMPLETED",
                }],
            }],
            "limitations": ["EMULATOR_DIRECTIONAL_TIMINGS_ONLY"],
        }
        verifier.validate_instance(schema, document)

    def test_scenario_rejects_noncanonical_reset_parameters(self):
        scenario = {
            "scenarioFamily": "N6",
            "variant": "TRANSPORT_RESET",
            "primaryPlane": "TRANSPORT",
            "randomSeed": None,
            "transportFaults": [{
                "kind": "RESET_PEER",
                "stochastic": False,
                "parameters": {
                    "timeoutMs": 1,
                    "direction": "DOWNSTREAM",
                    "scope": "MEDIA_DATA_ONLY",
                },
            }],
        }
        with self.assertRaisesRegex(
            verifier.TransportResetEvidenceError,
            "parameters drift",
        ):
            verifier.validate_scenario(scenario)


if __name__ == "__main__":
    unittest.main()
