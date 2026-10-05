from __future__ import annotations

import copy
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "scripts" / "measurement" / "m3a_probe.py"
SCHEMA = ROOT / ".work" / "schemas" / "m3-a-probe-v1.schema.json"

spec = importlib.util.spec_from_file_location("m3a_probe", SCRIPT)
assert spec and spec.loader
m3a_probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m3a_probe)


def valid_probe() -> dict:
    return {
        "schemaVersion": 1,
        "probeId": "m3a-v0-web-embedded-001",
        "capturedAtUtc": "2026-10-05T18:30:00Z",
        "sourceRevision": "a" * 40,
        "tool": {
            "name": "YT_DLP",
            "version": "2026.10.01",
            "revision": "0123456789abcdef",
            "license": "UNLICENSE",
            "role": "REFERENCE_ORACLE",
        },
        "environment": {
            "runner": "GITHUB_HOSTED_UBUNTU_24_04",
            "networkScope": "GITHUB_HOSTED_CLOUD_EGRESS",
            "authenticated": False,
            "cookiesProvided": False,
            "proxyOrIpRotation": False,
        },
        "video": {
            "caseId": "V0",
            "videoId": "aqz-KE-bpKQ",
            "caseClass": "PUBLIC_EMBEDDABLE_LONG_FORM",
        },
        "clientProfile": "WEB_EMBEDDED",
        "resolver": {
            "outcome": "SUCCESS",
            "playability": "PLAYABLE",
            "protocols": ["HTTPS", "DASH"],
            "muxing": "BOTH",
            "drm": "ABSENT",
        },
        "access": {
            "playerPoToken": "NOT_REQUIRED",
            "gvsPoToken": "NOT_REQUIRED",
            "subsPoToken": "NOT_APPLICABLE",
            "challengeRuntime": "NONE",
            "challengeExecution": "NOT_REQUIRED",
        },
        "descriptor": {
            "signedUrlObserved": True,
            "rawUrlRetained": False,
            "expiry": "PRESENT",
            "expiryHorizonSeconds": 18000,
            "rangeAdvertised": "YES",
        },
        "delivery": {
            "attempted": True,
            "bytesRequested": 1048576,
            "httpStatus": 206,
            "rangeResult": "PASS",
            "continuationIdentity": "STABLE",
        },
        "privacy": {
            "retainsSignedUrl": False,
            "retainsCookie": False,
            "retainsAuthorization": False,
            "retainsPoToken": False,
            "retainsVisitorOrSessionSecret": False,
            "retainsRawIp": False,
        },
        "limitations": [
            "Cloud-egress observation only; capability may vary by session or region."
        ],
    }


class M3AProbeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.schema = json.loads(SCHEMA.read_text(encoding="utf-8"))

    def validate(self, probe: dict) -> None:
        m3a_probe.validate_probe(probe, schema=self.schema)

    def test_valid_bounded_probe_passes(self):
        self.validate(valid_probe())

    def test_rejects_tool_license_drift(self):
        probe = valid_probe()
        probe["tool"]["license"] = "MIT"
        with self.assertRaisesRegex(m3a_probe.M3AProbeError, "must retain license"):
            self.validate(probe)

    def test_rejects_case_class_drift(self):
        probe = valid_probe()
        probe["video"]["caseClass"] = "PUBLIC_SPLIT_AV_LONG_FORM"
        with self.assertRaisesRegex(m3a_probe.M3AProbeError, "expected caseClass"):
            self.validate(probe)

    def test_rejects_duplicate_protocols(self):
        probe = valid_probe()
        probe["resolver"]["protocols"] = ["HTTPS", "HTTPS"]
        with self.assertRaisesRegex(m3a_probe.M3AProbeError, "must not contain duplicates"):
            self.validate(probe)

    def test_rejects_none_mixed_with_protocol(self):
        probe = valid_probe()
        probe["resolver"]["protocols"] = ["NONE", "SABR"]
        with self.assertRaisesRegex(m3a_probe.M3AProbeError, "mutually exclusive"):
            self.validate(probe)

    def test_rejects_success_without_playable(self):
        probe = valid_probe()
        probe["resolver"]["playability"] = "UNKNOWN"
        with self.assertRaisesRegex(m3a_probe.M3AProbeError, "SUCCESS requires PLAYABLE"):
            self.validate(probe)

    def test_rejects_unattempted_delivery_with_bytes(self):
        probe = valid_probe()
        probe["delivery"] = {
            "attempted": False,
            "bytesRequested": 1,
            "httpStatus": 0,
            "rangeResult": "NOT_ATTEMPTED",
            "continuationIdentity": "NOT_APPLICABLE",
        }
        with self.assertRaisesRegex(m3a_probe.M3AProbeError, "must request zero bytes"):
            self.validate(probe)

    def test_schema_rejects_media_probe_over_one_mib(self):
        probe = valid_probe()
        probe["delivery"]["bytesRequested"] = 1048577
        with self.assertRaisesRegex(m3a_probe.M3AProbeError, "maximum"):
            self.validate(probe)

    def test_rejects_invented_expiry_horizon(self):
        probe = valid_probe()
        probe["descriptor"]["expiry"] = "UNKNOWN"
        probe["descriptor"]["expiryHorizonSeconds"] = 3600
        with self.assertRaisesRegex(m3a_probe.M3AProbeError, "must not invent a horizon"):
            self.validate(probe)

    def test_rejects_raw_signed_url_in_any_string(self):
        probe = valid_probe()
        probe["limitations"] = [
            "Observed https://rr1---sn.example.googlevideo.com/videoplayback?x=1"
        ]
        with self.assertRaisesRegex(m3a_probe.M3AProbeError, "forbidden retained"):
            self.validate(probe)

    def test_rejects_po_token_value_in_any_string(self):
        probe = valid_probe()
        probe["limitations"] = ["debug query contained pot=secret-value"]
        with self.assertRaisesRegex(m3a_probe.M3AProbeError, "forbidden retained"):
            self.validate(probe)

    def test_rejects_privacy_retention_flag(self):
        probe = valid_probe()
        probe["privacy"]["retainsPoToken"] = True
        with self.assertRaises(m3a_probe.M3AProbeError):
            self.validate(probe)

    def test_bot_check_cannot_attempt_media(self):
        probe = valid_probe()
        probe["resolver"]["outcome"] = "BOT_CHECK"
        probe["resolver"]["playability"] = "BOT_CHECK"
        probe["resolver"]["protocols"] = ["NONE"]
        with self.assertRaisesRegex(m3a_probe.M3AProbeError, "must not attempt media"):
            self.validate(probe)

    def test_bgutils_cannot_own_delivery(self):
        probe = valid_probe()
        probe["tool"] = {
            "name": "BGUTILS",
            "version": "4.0.3",
            "revision": "f39a041",
            "license": "MIT",
            "role": "TOKEN_REQUIREMENT_REFERENCE",
        }
        probe["clientProfile"] = "WEB"
        probe["access"]["challengeRuntime"] = "BOTGUARD"
        probe["access"]["challengeExecution"] = "REFERENCE_TOOL_EXECUTED"
        with self.assertRaisesRegex(m3a_probe.M3AProbeError, "must not own media delivery"):
            self.validate(probe)

    def test_executed_challenge_requires_concrete_runtime(self):
        probe = valid_probe()
        probe["access"]["challengeExecution"] = "REFERENCE_TOOL_EXECUTED"
        with self.assertRaisesRegex(m3a_probe.M3AProbeError, "concrete challenge runtime"):
            self.validate(probe)


if __name__ == "__main__":
    unittest.main()
