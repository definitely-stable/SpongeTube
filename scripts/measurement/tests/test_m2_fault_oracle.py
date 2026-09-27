#!/usr/bin/env python3
import argparse
import json
import pathlib
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
MEASUREMENT = ROOT / "scripts" / "measurement"
sys.path.insert(0, str(MEASUREMENT))

import m2_fault_oracle as oracle
import m2_run_manifest as run_manifest

FIXTURES = ROOT / "test-fixtures" / "network" / "m2"


def scenario(name: str):
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


class M2FaultOracleContractTest(unittest.TestCase):
    def test_transport_variants_are_derived_independently(self):
        cases = {
            "n6-transport-read-timeout.json":
                ("timeout", "DOWNSTREAM", {"timeout": 0}),
            "n6-transport-reset.json":
                ("reset_peer", "DOWNSTREAM", {"timeout": 0}),
            "n6-truncated-stream.json":
                ("limit_data", "DOWNSTREAM", {"bytes": 16384}),
            "n6-slow-close.json":
                ("slow_close", "DOWNSTREAM", {"delay": 1000}),
        }
        for name, expected in cases.items():
            with self.subTest(name=name):
                _, toxic, direction, attributes = oracle.expected_transport_fault(
                    scenario(name)
                )
                self.assertEqual(expected, (toxic, direction, attributes))

    def test_variant_kind_mismatch_fails_closed(self):
        broken = scenario("n6-transport-reset.json")
        broken["transportFaults"][0]["kind"] = "READ_TIMEOUT"
        with self.assertRaises(Exception):
            oracle.expected_transport_fault(broken)

    def test_non_transport_mix_fails_closed(self):
        broken = scenario("n6-transport-reset.json")
        broken["deliveryFaults"] = [{
            "faultId": "delivery",
            "plane": "DELIVERY",
            "kind": "PACED",
            "stochastic": False,
            "parameters": {},
        }]
        with self.assertRaises(Exception):
            oracle.expected_transport_fault(broken)

    def test_truncation_must_actually_truncate_fixture(self):
        broken = scenario("n6-truncated-stream.json")
        broken["transportFaults"][0]["parameters"]["limitBytes"] = oracle.RESOURCE_LENGTH
        with self.assertRaises(Exception):
            oracle.expected_transport_fault(broken)

    def test_deterministic_transport_rejects_seed(self):
        broken = scenario("n6-transport-reset.json")
        broken["transportFaults"][0]["stochastic"] = True
        broken["randomSeed"] = 424242
        with self.assertRaises(Exception):
            oracle.expected_transport_fault(broken)

    def test_real_run_manifest_binds_scenario_and_harness(self):
        args = argparse.Namespace(
            scenario=FIXTURES / "n6-transport-reset.json",
            run_id="m2-e-transport-reset",
            session_id="m2-e-transport-reset-session",
            git_commit="a" * 40,
            fixture_manifest=ROOT / "test-fixtures" / "media" / "manifest.json",
            device_api=36,
            created_at_utc="2026-09-27T15:00:00Z",
        )
        manifest = run_manifest.build(args)
        self.assertEqual("TRANSPORT", manifest["scenario"]["primaryPlane"])
        self.assertEqual("ANDROID_DEFAULT_NETWORK", manifest["mediaPath"])
        self.assertEqual(
            [{
                "plane": "TRANSPORT",
                "harnessId": "sponge-transport-harness",
                "harnessVersion": "1",
            }],
            manifest["faultHarnesses"],
        )
        self.assertIn("HOST_FAULT_MONOTONIC", manifest["clockDomains"])


if __name__ == "__main__":
    unittest.main()
