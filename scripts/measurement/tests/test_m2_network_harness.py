#!/usr/bin/env python3
import json
import pathlib
import sys
import unittest
from unittest import mock

ROOT = pathlib.Path(__file__).resolve().parents[3]
FAULTS = ROOT / "scripts" / "faults"
MEASUREMENT = ROOT / "scripts" / "measurement"
sys.path.insert(0, str(FAULTS))
sys.path.insert(0, str(MEASUREMENT))

import m2_fault_oracle as oracle
import m2_network_harness as harness

FIXTURES = ROOT / "test-fixtures" / "network" / "m2"


def scenario(name):
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


class M2NetworkHarnessContractTest(unittest.TestCase):
    def test_canonical_network_profiles_compile_exactly(self):
        cases = {
            "n2-high-rtt-jitter.json": {
                "direction": "DOWNSTREAM",
                "ipFamily": "IPV4",
                "l4Protocol": "TCP",
                "scope": "MEDIA_DATA_ONLY",
                "delayUs": 100000,
                "jitterUs": 30000,
                "delayCorrelationPpm": 250000,
                "randomSeed": 424242,
            },
            "n3-burst-packet-loss.json": {
                "direction": "DOWNSTREAM",
                "ipFamily": "IPV4",
                "l4Protocol": "TCP",
                "scope": "MEDIA_DATA_ONLY",
                "lossPpm": 1000000,
                "durationMs": 1500,
            },
            "n5-burst-loss.json": {
                "direction": "DOWNSTREAM",
                "ipFamily": "IPV4",
                "l4Protocol": "TCP",
                "scope": "MEDIA_DATA_ONLY",
                "lossPpm": 20000,
                "burstCorrelationPpm": 250000,
                "randomSeed": 424242,
            },
        }
        for name, expected in cases.items():
            with self.subTest(name=name):
                value = scenario(name)
                self.assertEqual(expected, harness.compile_config(value))
                _, oracle_config = oracle.expected_network_fault(value)
                self.assertEqual(expected, oracle_config)

    def test_stochastic_network_profile_requires_exact_seed(self):
        broken = scenario("n5-burst-loss.json")
        broken["randomSeed"] = 7
        with self.assertRaises(Exception):
            harness.compile_config(broken)
        with self.assertRaises(Exception):
            oracle.expected_network_fault(broken)

        broken = scenario("n2-high-rtt-jitter.json")
        broken["randomSeed"] = None
        with self.assertRaises(Exception):
            harness.compile_config(broken)

    def test_deterministic_blackout_rejects_seed(self):
        broken = scenario("n3-burst-packet-loss.json")
        broken["networkFaults"][0]["stochastic"] = True
        broken["randomSeed"] = 424242
        with self.assertRaises(Exception):
            harness.compile_config(broken)

    def test_non_network_or_mixed_fault_fails_closed(self):
        broken = scenario("n5-burst-loss.json")
        broken["transportFaults"] = [{
            "faultId": "transport",
            "plane": "TRANSPORT",
            "kind": "RESET_PEER",
            "stochastic": False,
            "parameters": {"scope": "MEDIA_DATA_ONLY", "direction": "DOWNSTREAM", "timeoutMs": 0},
        }]
        with self.assertRaises(Exception):
            harness.compile_config(broken)

        broken = scenario("n5-burst-loss.json")
        broken["primaryPlane"] = "TRANSPORT"
        with self.assertRaises(Exception):
            harness.compile_config(broken)

    def test_scenario_scope_and_frozen_parameters_fail_closed(self):
        broken = scenario("n5-burst-loss.json")
        broken["networkFaults"][0]["parameters"]["scope"] = "CONTROL"
        with self.assertRaises(Exception):
            harness.compile_config(broken)

        broken = scenario("n2-high-rtt-jitter.json")
        broken["networkFaults"][0]["parameters"]["delayUs"] = 99999
        with self.assertRaises(Exception):
            harness.compile_config(broken)

        broken = scenario("n3-burst-packet-loss.json")
        broken["networkFaults"][0]["parameters"]["durationMs"] = 1499
        with self.assertRaises(Exception):
            harness.compile_config(broken)

        for key, bad in (("direction", "UPSTREAM"), ("ipFamily", "IPV6"), ("l4Protocol", "UDP")):
            broken = scenario("n5-burst-loss.json")
            broken["networkFaults"][0]["parameters"][key] = bad
            with self.subTest(key=key), self.assertRaises(Exception):
                harness.compile_config(broken)
            with self.subTest(key=key + "-oracle"), self.assertRaises(Exception):
                oracle.expected_network_fault(broken)

    def test_blackout_rendezvous_waits_for_first_drop(self):
        qdisc_before = [{"kind": "netem", "drops": 0}]
        qdisc_after = [{"kind": "netem", "drops": 1}]
        with mock.patch.object(harness, "inspect", side_effect=[(qdisc_before, []), (qdisc_after, [])]), mock.patch.object(harness.time, "sleep"):
            observed, _ = harness.wait_for_first_effect(0, timeout_ms=1000)
        self.assertEqual(1, harness._qdisc_stat(harness._find_kind(observed, "netem"), "drops"))

    def test_blackout_effect_accepts_drop_only_qdisc_stats(self):
        oracle.validate_network_effect("BURST_PACKET_LOSS", {"packets": 0, "drops": 1})
        with self.assertRaises(Exception):
            oracle.validate_network_effect("BURST_PACKET_LOSS", {"packets": 0, "drops": 0})

    def test_independent_tc_normalization_derives_integer_units(self):
        qdisc = [
            {"kind": "prio", "handle": "1:"},
            {
                "kind": "netem",
                "handle": "10:",
                "parent": "1:1",
                "options": {
                    "delay": {
                        "delay": 0.1,
                        "jitter": 0.03,
                        "correlation": 0.25,
                    },
                    "seed": 424242,
                },
            },
        ]
        filters = [{
            "kind": "flower",
            "protocol": "ip",
            "options": {
                "keys": {"ip_proto": "tcp", "src_port": 18081},
                "classid": "1:1",
            },
        }]
        self.assertEqual(
            {
                "direction": "DOWNSTREAM",
                "ipFamily": "IPV4",
                "l4Protocol": "TCP",
                "scope": "MEDIA_DATA_ONLY",
                "mediaPortScoped": True,
                "delayUs": 100000,
                "jitterUs": 30000,
                "delayCorrelationPpm": 250000,
                "randomSeed": 424242,
            },
            oracle.normalize_network_tc_state(qdisc, filters, "HIGH_RTT_JITTER"),
        )

    def test_raw_addresses_never_enter_network_config(self):
        encoded = json.dumps(harness.compile_config(scenario("n5-burst-loss.json")))
        self.assertNotIn("192.", encoded)
        self.assertNotIn("http://", encoded)


if __name__ == "__main__":
    unittest.main()
