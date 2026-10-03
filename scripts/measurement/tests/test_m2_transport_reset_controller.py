from __future__ import annotations

import json
import pathlib
import sys
import tempfile
import unittest
from unittest import mock

ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "faults"))

import m2_transport_reset_controller as controller  # noqa: E402


class TransportResetControllerTest(unittest.TestCase):
    def test_signal_binds_fetch_and_application_attempt(self):
        fetch_id, correlation = controller.validate_signal(
            {
                "signal": "ATTEMPT_FAILED",
                "trialId": "trial-1",
                "fetchId": "fetch-1",
                "attemptCorrelationId": "fetch-1:attempt-1",
            },
            trial_id="trial-1",
        )
        self.assertEqual("fetch-1", fetch_id)
        self.assertEqual("fetch-1:attempt-1", correlation)

    def test_signal_rejects_transport_or_foreign_correlation(self):
        with self.assertRaisesRegex(
            controller.ResetControllerError,
            "application-attempt correlation drift",
        ):
            controller.validate_signal(
                {
                    "signal": "ATTEMPT_FAILED",
                    "trialId": "trial-1",
                    "fetchId": "fetch-1",
                    "attemptCorrelationId": "17",
                },
                trial_id="trial-1",
            )

    def test_origin_barrier_requires_origin_reach_and_freezes_complete_rows(self):
        with tempfile.TemporaryDirectory() as tmp:
            trace = pathlib.Path(tmp) / "requests.jsonl"
            trace.write_text(
                json.dumps({
                    "requestId": 1,
                    "plane": "control",
                    "method": "GET",
                })
                + "\n"
                + json.dumps({
                    "requestId": 2,
                    "plane": "data",
                    "method": "GET",
                })
                + "\n",
                encoding="utf-8",
            )
            count, ids = controller.settle_first_owner_partition(
                trace,
                before_count=1,
                stable_seconds=0.01,
                timeout_seconds=0.2,
            )
            self.assertEqual(2, count)
            self.assertEqual([2], ids)

    def test_origin_barrier_rejects_no_origin_effect(self):
        with tempfile.TemporaryDirectory() as tmp:
            trace = pathlib.Path(tmp) / "requests.jsonl"
            trace.write_text(
                json.dumps({
                    "requestId": 1,
                    "plane": "control",
                    "method": "GET",
                }) + "\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                controller.ResetControllerError,
                "did not settle",
            ):
                controller.settle_first_owner_partition(
                    trace,
                    before_count=1,
                    stable_seconds=0.01,
                    timeout_seconds=0.05,
                )

    def test_handle_persists_boundary_before_ack_and_disarms_once(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            trigger = root / "trigger.json"
            instance = controller.Controller(
                trial_id="trial-1",
                trace_path=root / "requests.jsonl",
                before_count=1,
                trigger_path=trigger,
                scenario=root / "scenario.json",
                api="http://control.invalid",
                evidence=root / "events.json",
                disarmed_state=root / "state.json",
                run_id="run-1",
                session_id="session-1",
            )
            payload = {
                "signal": "ATTEMPT_FAILED",
                "trialId": "trial-1",
                "fetchId": "fetch-1",
                "attemptCorrelationId": "fetch-1:attempt-1",
            }
            with mock.patch.object(
                controller,
                "settle_first_owner_partition",
                return_value=(3, [2, 3]),
            ), mock.patch.object(
                controller.harness,
                "disarm",
            ) as disarm:
                response = instance.handle(payload)

            self.assertEqual("DISARMED", response["status"])
            disarm.assert_called_once()
            retained = json.loads(trigger.read_text(encoding="utf-8"))
            self.assertEqual(controller.CONTROL_PROTOCOL, retained["controlProtocol"])
            self.assertEqual(1, retained["originCountBeforeTrial"])
            self.assertEqual(3, retained["originCountAtTrigger"])
            self.assertEqual([2, 3], retained["originRequestIdsBeforeDisarm"])
            self.assertEqual("fetch-1:attempt-1", retained["attemptCorrelationId"])
            self.assertGreaterEqual(
                retained["faultDisarmedAtElapsedRealtimeNs"],
                retained["hostObservedAtElapsedRealtimeNs"],
            )


if __name__ == "__main__":
    unittest.main()
