#!/usr/bin/env python3
import json
import pathlib
import sys
import unittest

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


if __name__ == "__main__":
    unittest.main()
