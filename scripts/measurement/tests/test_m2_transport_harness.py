#!/usr/bin/env python3
import argparse
import json
import pathlib
import sys
import tempfile
import unittest
from unittest import mock

ROOT = pathlib.Path(__file__).resolve().parents[3]
FAULTS = ROOT / "scripts" / "faults"
sys.path.insert(0, str(FAULTS))

import m2_transport_harness as harness
import toxiproxy_control as toxi

FIXTURES = ROOT / "test-fixtures" / "network" / "m2"


def scenario(name):
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


class M2TransportHarnessContractTest(unittest.TestCase):
    def test_canonical_variants_compile_to_transport_only_toxics(self):
        cases = {
            "n6-transport-read-timeout.json":
                ("timeout", "downstream", {"timeout": 0}),
            "n6-transport-reset.json":
                ("reset_peer", "downstream", {"timeout": 0}),
            "n6-truncated-stream.json":
                ("limit_data", "downstream", {"bytes": 16384}),
            "n6-slow-close.json":
                ("slow_close", "downstream", {"delay": 1000}),
        }
        for name, expected in cases.items():
            with self.subTest(name=name):
                self.assertEqual(expected, harness.compile_toxic(scenario(name)))

    def test_network_plane_is_rejected(self):
        broken = scenario("n6-transport-reset.json")
        broken["scenarioFamily"] = "N5"
        broken["variant"] = "BURST_LOSS"
        broken["primaryPlane"] = "NETWORK"
        broken["networkFaults"] = [{
            "faultId": "loss",
            "plane": "NETWORK",
            "kind": "BURST_LOSS",
            "stochastic": True,
            "parameters": {"lossPpm": 20000},
        }]
        broken["transportFaults"] = []
        broken["randomSeed"] = 424242
        with self.assertRaises(Exception):
            harness.compile_toxic(broken)

    def test_mixed_network_fault_is_rejected(self):
        broken = scenario("n6-transport-reset.json")
        broken["networkFaults"] = [{
            "faultId": "loss",
            "plane": "NETWORK",
            "kind": "BURST_LOSS",
            "stochastic": False,
            "parameters": {"lossPpm": 1000000},
        }]
        with self.assertRaises(Exception):
            harness.compile_toxic(broken)

    def test_invalid_limit_and_delay_fail_closed(self):
        broken = scenario("n6-truncated-stream.json")
        broken["transportFaults"][0]["parameters"]["limitBytes"] = 0
        with self.assertRaises(Exception):
            harness.compile_toxic(broken)
        broken = scenario("n6-slow-close.json")
        broken["transportFaults"][0]["parameters"]["delayMs"] = -1
        with self.assertRaises(Exception):
            harness.compile_toxic(broken)

    def test_variant_kind_direction_and_scope_must_match(self):
        broken = scenario("n6-transport-reset.json")
        broken["transportFaults"][0]["kind"] = "READ_TIMEOUT"
        with self.assertRaises(Exception):
            harness.compile_toxic(broken)

        broken = scenario("n6-transport-reset.json")
        broken["transportFaults"][0]["parameters"]["direction"] = "UPSTREAM"
        with self.assertRaises(Exception):
            harness.compile_toxic(broken)

        broken = scenario("n6-transport-reset.json")
        broken["transportFaults"][0]["parameters"]["scope"] = "CONTROL"
        with self.assertRaises(Exception):
            harness.compile_toxic(broken)

    def test_delivery_or_seeded_transport_mix_is_rejected(self):
        broken = scenario("n6-transport-reset.json")
        broken["deliveryFaults"] = [{
            "faultId": "delivery",
            "plane": "DELIVERY",
            "kind": "PACED",
            "stochastic": False,
            "parameters": {},
        }]
        with self.assertRaises(Exception):
            harness.compile_toxic(broken)

        broken = scenario("n6-transport-reset.json")
        broken["transportFaults"][0]["stochastic"] = True
        broken["randomSeed"] = 424242
        with self.assertRaises(Exception):
            harness.compile_toxic(broken)

    def test_normalized_proxy_state_excludes_addresses(self):
        raw = {
            "name": "m2e-media",
            "listen": "raw-address",
            "upstream": "raw-upstream",
            "enabled": True,
            "toxics": [{
                "name": "m2e-fault",
                "type": "reset_peer",
                "stream": "downstream",
                "toxicity": 1.0,
                "attributes": {"timeout": 0},
            }],
        }
        state = toxi.normalized_proxy_state(raw)
        encoded = json.dumps(state)
        self.assertNotIn("listen", encoded)
        self.assertNotIn("upstream", encoded)
        self.assertEqual(1_000_000, state["toxics"][0]["toxicityPpm"])

    def test_packet_loss_is_not_an_accepted_transport_toxic(self):
        raw = {
            "name": "m2e-media",
            "enabled": True,
            "toxics": [{
                "name": "wrong",
                "type": "packet_loss",
                "stream": "downstream",
                "toxicity": 1.0,
                "attributes": {"loss_rate": 0.1, "correlation": 0.0},
            }],
        }
        with self.assertRaises(toxi.ToxiproxyError):
            toxi.normalized_proxy_state(raw)


    def test_disarm_removes_toxic_but_preserves_proxy_and_records_event(self):
        fixture = scenario("n6-transport-reset.json")
        active = {
            "name": harness.PROXY_NAME,
            "enabled": True,
            "toxics": [{
                "name": harness.TOXIC_NAME,
                "type": "reset_peer",
                "stream": "downstream",
                "toxicityPpm": 1_000_000,
                "attributes": {"timeout": 0},
            }],
        }
        clean = {
            "name": harness.PROXY_NAME,
            "enabled": True,
            "toxics": [],
        }
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            scenario_path = root / "scenario.json"
            evidence_path = root / "events.json"
            state_path = root / "state.json"
            scenario_path.write_text(json.dumps(fixture), encoding="utf-8")
            doc = {
                "schemaVersion": 1,
                "runId": "run",
                "sessionId": "session",
                "scenarioHash": harness.scenario_sha256(fixture),
                "harness": {
                    "harnessId": harness.HARNESS_ID,
                    "harnessVersion": harness.HARNESS_VERSION,
                    "toolId": harness.TOOL_ID,
                    "toolVersion": harness.TOOL_VERSION,
                },
                "clockDomain": "HOST_FAULT_MONOTONIC",
                "events": [],
            }
            evidence_path.write_text(json.dumps(doc), encoding="utf-8")
            args = argparse.Namespace(
                scenario=str(scenario_path),
                api="http://control.invalid",
                evidence=str(evidence_path),
                state=str(state_path),
                run_id="run",
                session_id="session",
            )
            with mock.patch.object(
                harness.toxi,
                "get_proxy",
                side_effect=[active, clean],
            ), mock.patch.object(
                harness.toxi,
                "normalized_proxy_state",
                side_effect=lambda value: value,
            ), mock.patch.object(
                harness.toxi,
                "remove_toxic",
            ) as remove:
                harness.disarm(args)

            remove.assert_called_once_with(
                "http://control.invalid",
                harness.PROXY_NAME,
                harness.TOXIC_NAME,
            )
            retained = json.loads(evidence_path.read_text(encoding="utf-8"))
            self.assertEqual("FAULT_REMOVED", retained["events"][-1]["operation"])
            self.assertEqual("REMOVED", retained["events"][-1]["result"])
            self.assertEqual(clean, json.loads(state_path.read_text(encoding="utf-8")))

    def test_disarm_fails_closed_when_fault_was_already_removed(self):
        fixture = scenario("n6-transport-reset.json")
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            scenario_path = root / "scenario.json"
            evidence_path = root / "events.json"
            state_path = root / "state.json"
            scenario_path.write_text(json.dumps(fixture), encoding="utf-8")
            evidence_path.write_text(json.dumps({
                "schemaVersion": 1,
                "runId": "run",
                "sessionId": "session",
                "scenarioHash": harness.scenario_sha256(fixture),
                "harness": {
                    "harnessId": harness.HARNESS_ID,
                    "harnessVersion": harness.HARNESS_VERSION,
                    "toolId": harness.TOOL_ID,
                    "toolVersion": harness.TOOL_VERSION,
                },
                "clockDomain": "HOST_FAULT_MONOTONIC",
                "events": [{
                    "operation": "FAULT_REMOVED",
                }],
            }), encoding="utf-8")
            args = argparse.Namespace(
                scenario=str(scenario_path),
                api="http://control.invalid",
                evidence=str(evidence_path),
                state=str(state_path),
                run_id="run",
                session_id="session",
            )
            with self.assertRaisesRegex(Exception, "already removed"):
                harness.disarm(args)



if __name__ == "__main__":
    unittest.main()
