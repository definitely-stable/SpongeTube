from __future__ import annotations

import copy
import unittest

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "measurement"))

import m2_transport_reset_environment as env  # noqa: E402


RUNTIME = {
    "deviceClass": "ANDROID_EMULATOR",
    "androidApi": 36,
    "abi": "x86_64",
    "buildFingerprint": "test/fingerprint",
    "buildId": "TEST",
    "securityPatch": "2026-09-05",
    "kernelRelease": "6.8.0-test",
    "batteryPolicy": "CI_POWERED",
    "acPowered": True,
}


class TransportResetEnvironmentTest(unittest.TestCase):
    def test_build_binds_pinned_tool_runtime_and_full_code_fingerprint(self):
        pinned = env.pinned_toxiproxy()
        doc = env.build(
            run_id="m2-g2-n6-api36",
            source_head_commit="1" * 40,
            checkout_commit="2" * 40,
            toxiproxy_sha256=pinned["sha256"],
            android_runtime=RUNTIME,
        )
        env.verify(doc, android_runtime=RUNTIME)
        self.assertEqual("2.12.0", doc["harness"]["toolVersion"])
        self.assertEqual(len(env.CODE_PATHS), len(doc["codeFingerprint"]["files"]))
        self.assertFalse(doc["transportPath"]["mediaAdbReverseUsed"])
        self.assertEqual(
            "ADB_REVERSE_LOOPBACK_HTTP",
            doc["transportPath"]["labControlPath"],
        )
        self.assertFalse(doc["transportPath"]["processWideNetworkBinding"])

    def test_verify_rejects_code_fingerprint_tampering(self):
        pinned = env.pinned_toxiproxy()
        doc = env.build(
            run_id="m2-g2-n6-api36",
            source_head_commit="1" * 40,
            checkout_commit="2" * 40,
            toxiproxy_sha256=pinned["sha256"],
            android_runtime=RUNTIME,
        )
        tampered = copy.deepcopy(doc)
        tampered["codeFingerprint"]["files"][0]["sha256"] = "0" * 64
        with self.assertRaisesRegex(
            env.TransportResetEnvironmentError,
            "code fingerprint drift",
        ):
            env.verify(tampered, android_runtime=RUNTIME)

    def test_build_rejects_unpinned_toxiproxy_binary(self):
        with self.assertRaisesRegex(
            env.TransportResetEnvironmentError,
            "does not match lock",
        ):
            env.build(
                run_id="m2-g2-n6-api36",
                source_head_commit="1" * 40,
                checkout_commit="2" * 40,
                toxiproxy_sha256="0" * 64,
                android_runtime=RUNTIME,
            )


if __name__ == "__main__":
    unittest.main()
