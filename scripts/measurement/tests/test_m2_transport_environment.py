from __future__ import annotations

import copy
import importlib.util
import json
import pathlib
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]


def load_module():
    path = ROOT / "scripts" / "measurement" / "m2_transport_environment.py"
    spec = importlib.util.spec_from_file_location("m2_transport_environment", path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


envmod = load_module()


FAULT_ENGINE = {
    "schemaVersion": 1,
    "runner": {
        "imageOs": "ubuntu24",
        "imageVersion": "20261001.1",
        "osId": "ubuntu",
        "osVersionId": "24.04",
        "kernelRelease": "6.11.0-test",
        "kernelMachine": "x86_64",
    },
    "toolchain": {
        "tcVersion": "tc utility, iproute2-6.6.0",
        "tcSourceSha256": "1" * 64,
        "ethtoolVersion": "ethtool version 6.10",
    },
    "offloads": {"gro": False, "gso": False, "tso": False},
    "limitations": ["test fingerprint"],
}

LINK_STATE = {
    "mtu": 1500,
    "offloads": {"gro": False, "gso": False, "tso": False},
}

DEVICE = {
    "deviceClass": "ANDROID_EMULATOR",
    "androidApi": 36,
    "abi": "x86_64",
    "buildFingerprint": "google/sdk_gphone64_x86_64/test:16/ABC/123:userdebug/test-keys",
    "buildId": "ABC",
    "securityPatch": "2026-09-05",
    "kernelRelease": "6.1.0-android-test",
    "batteryPolicy": "CI_POWERED",
    "acPowered": True,
}


class TransportEnvironmentTest(unittest.TestCase):
    def build(self, **overrides):
        values = {
            "run_id": "m2-g2-c-n2",
            "source_head_commit": "a" * 40,
            "checkout_commit": "b" * 40,
            "fault_engine": copy.deepcopy(FAULT_ENGINE),
            "link_state": copy.deepcopy(LINK_STATE),
            "android_runtime": copy.deepcopy(DEVICE),
        }
        values.update(overrides)
        return envmod.build(**values)

    def test_build_and_verify_round_trip(self):
        document = self.build()
        self.assertEqual(1, document["schemaVersion"])
        self.assertEqual(1500, document["mediaLink"]["mtu"])
        self.assertEqual("STANDARD_MTU1500_V1", document["mediaLink"]["packetizationProfile"])
        self.assertEqual(
            {"gro": False, "gso": False, "tso": False},
            document["mediaLink"]["offloads"],
        )
        self.assertEqual("ANDROID_EMULATOR", document["androidRuntime"]["deviceClass"])
        self.assertEqual(
            [row["path"] for row in document["codeFingerprint"]["files"]],
            sorted(row["path"] for row in document["codeFingerprint"]["files"]),
        )
        envmod.verify(
            document,
            fault_engine=FAULT_ENGINE,
            link_state=LINK_STATE,
            android_runtime=DEVICE,
        )

    def test_fault_engine_offload_drift_is_rejected(self):
        fault = copy.deepcopy(FAULT_ENGINE)
        fault["offloads"]["gro"] = True
        with self.assertRaisesRegex(envmod.TransportEnvironmentError, "GRO/GSO/TSO"):
            self.build(fault_engine=fault)

    def test_live_link_offload_drift_is_rejected(self):
        link = copy.deepcopy(LINK_STATE)
        link["offloads"]["tso"] = True
        with self.assertRaisesRegex(envmod.TransportEnvironmentError, "live media link offloads"):
            self.build(link_state=link)

    def test_mtu_drift_is_rejected_on_verify(self):
        document = self.build()
        link = copy.deepcopy(LINK_STATE)
        link["mtu"] = 512
        with self.assertRaisesRegex(envmod.TransportEnvironmentError, "media link environment drift"):
            envmod.verify(
                document,
                fault_engine=FAULT_ENGINE,
                link_state=link,
                android_runtime=DEVICE,
            )

    def test_g2_effect_mtu512_profile_is_explicit(self):
        link = copy.deepcopy(LINK_STATE)
        link["mtu"] = 512
        document = self.build(link_state=link)
        self.assertEqual(512, document["mediaLink"]["mtu"])
        self.assertEqual(
            "G2_EFFECT_MTU512_V1",
            document["mediaLink"]["packetizationProfile"],
        )
        envmod.verify(
            document,
            fault_engine=FAULT_ENGINE,
            link_state=link,
            android_runtime=DEVICE,
        )

    def test_unfrozen_intermediate_mtu_is_rejected(self):
        link = copy.deepcopy(LINK_STATE)
        link["mtu"] = 576
        with self.assertRaisesRegex(envmod.TransportEnvironmentError, "frozen G2 packetization profile"):
            self.build(link_state=link)

    def test_android_runtime_drift_is_rejected(self):
        document = self.build()
        device = copy.deepcopy(DEVICE)
        device["buildId"] = "DIFFERENT"
        with self.assertRaisesRegex(envmod.TransportEnvironmentError, "Android runtime binding drift"):
            envmod.verify(
                document,
                fault_engine=FAULT_ENGINE,
                link_state=LINK_STATE,
                android_runtime=device,
            )

    def test_non_api36_or_non_x86_runtime_is_rejected(self):
        for field, value, message in (
            ("androidApi", 35, "API 36"),
            ("abi", "arm64-v8a", "x86_64"),
            ("deviceClass", "PHYSICAL_ANDROID", "emulator"),
        ):
            with self.subTest(field=field):
                device = copy.deepcopy(DEVICE)
                device[field] = value
                with self.assertRaisesRegex(envmod.TransportEnvironmentError, message):
                    self.build(android_runtime=device)

    def test_non_powered_runtime_is_rejected(self):
        device = copy.deepcopy(DEVICE)
        device["acPowered"] = False
        with self.assertRaisesRegex(envmod.TransportEnvironmentError, "AC-powered"):
            self.build(android_runtime=device)

    def test_code_fingerprint_detects_experiment_source_mutation(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            for relative in envmod.CODE_PATHS:
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(relative + "\n", encoding="utf-8")
            before = envmod.code_fingerprint(root)
            first = root / envmod.CODE_PATHS[0]
            first.write_text("mutated\n", encoding="utf-8")
            after = envmod.code_fingerprint(root)
            self.assertNotEqual(before["sha256"], after["sha256"])

    def test_source_and_checkout_commits_are_exact_lowercase_sha1(self):
        for field in ("source_head_commit", "checkout_commit"):
            for value in ("A" * 40, "a" * 39, "z" * 40):
                with self.subTest(field=field, value=value):
                    with self.assertRaisesRegex(envmod.TransportEnvironmentError, "40-hex"):
                        self.build(**{field: value})

    def test_source_and_checkout_commits_are_retained_separately(self):
        document = self.build(
            source_head_commit="1" * 40,
            checkout_commit="2" * 40,
        )
        self.assertEqual("1" * 40, document["sourceHeadCommit"])
        self.assertEqual("2" * 40, document["checkoutCommit"])


if __name__ == "__main__":
    unittest.main()
