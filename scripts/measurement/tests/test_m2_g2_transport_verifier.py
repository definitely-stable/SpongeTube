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
                "hostObservationClockDomain": "HOST_FAULT_MONOTONIC",
                "hostObservedAtElapsedRealtimeNs": 40,
                "failureSignalsObservedAtTrigger": trigger_count,
            },
        )
        (trial / "android-fault-signal.log").write_text(
            f"ready trial={trial_id}\n"
            f"trial={trial_id} fetchId=fetch-1\n",
            encoding="utf-8",
        )
        return trial

    def test_harness_binds_disarm_to_first_android_failure_signal(self):
        with tempfile.TemporaryDirectory() as tmp:
            trial = self.make_trial(pathlib.Path(tmp))
            fetch_id, signal_ids = verifier.validate_harness(
                trial,
                plan=self.plan,
                trial_id=trial.name,
            )
            self.assertEqual("fetch-1", fetch_id)
            self.assertEqual(["fetch-1"], signal_ids)

    def test_harness_rejects_failure_before_readiness(self):
        with tempfile.TemporaryDirectory() as tmp:
            trial = self.make_trial(pathlib.Path(tmp))
            (trial / "android-fault-signal.log").write_text(
                f"trial={trial.name} fetchId=fetch-1\n"
                f"ready trial={trial.name}\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                verifier.TransportResetEvidenceError,
                "preceded Android readiness",
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
