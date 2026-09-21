import argparse
import json
import pathlib
import tempfile
import unittest

import sys

SCRIPT_DIR = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

import artifacts


class ArtifactContractTest(unittest.TestCase):

    def test_manifest_binds_fixture_hash_and_clock_domains(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = pathlib.Path(temp_dir)
            fixture_manifest = root / "manifest.json"
            fixture_manifest.write_text('{"schemaVersion":1}\n', encoding="utf-8")

            args = argparse.Namespace(
                run_id="run-1",
                created_at_utc="2026-09-21T12:00:00Z",
                git_commit="a" * 40,
                fixture_id="F1",
                fixture_manifest=str(fixture_manifest),
                fixture_resource_sha256="b" * 64,
                scenario_id="N0",
                scenario_hash="c" * 64,
                mode="DIRECT",
                cache_state="NONE",
                requested_transport="RECOMMENDED_PLATFORM",
                effective_transport="HTTP_ENGINE",
                build_type="benchmark",
                debuggable=False,
                profileable=True,
                minify=False,
                compilation_mode="None",
                startup_mode="COLD",
                device_api=36,
                device_fingerprint="test/fingerprint",
                device_model="sdk_gphone64_x86_64",
                device_abi="x86_64",
                media3_version="1.11.1",
                benchmark_version="1.5.0",
                order_seed=1234,
            )

            manifest = artifacts.build_manifest(args)

            self.assertEqual(1, manifest["schemaVersion"])
            self.assertEqual(
                artifacts.file_sha256(fixture_manifest),
                manifest["fixture"]["manifestSha256"],
            )
            self.assertFalse(
                manifest["clockDomains"]["crossDomainSubtractionAllowed"]
            )
            self.assertEqual(
                "SYSTEM_CLOCK_ELAPSED_REALTIME_NS",
                manifest["clockDomains"]["androidMonotonic"],
            )

    def test_manifest_rejects_invalid_scenario_hash(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            fixture_manifest = pathlib.Path(temp_dir) / "manifest.json"
            fixture_manifest.write_text("{}\n", encoding="utf-8")

            args = argparse.Namespace(
                run_id="run-1",
                created_at_utc="2026-09-21T12:00:00Z",
                git_commit="a" * 40,
                fixture_id="F1",
                fixture_manifest=str(fixture_manifest),
                fixture_resource_sha256="b" * 64,
                scenario_id="N0",
                scenario_hash="not-a-hash",
                mode="DIRECT",
                cache_state="NONE",
                requested_transport="RECOMMENDED_PLATFORM",
                effective_transport="HTTP_ENGINE",
                build_type="benchmark",
                debuggable=False,
                profileable=True,
                minify=False,
                compilation_mode="None",
                startup_mode="COLD",
                device_api=36,
                device_fingerprint="test/fingerprint",
                device_model=None,
                device_abi=None,
                media3_version="1.11.1",
                benchmark_version="1.5.0",
                order_seed=1,
            )

            with self.assertRaises(ValueError):
                artifacts.build_manifest(args)

    def test_result_enforces_duplicate_byte_identity(self):
        args = argparse.Namespace(
            run_id="run-1",
            status="COMPLETE",
            ttff_ns=100,
            stall_count=1,
            stall_total_ns=20,
            seek_to_frame_ns=["30"],
            playback_error_code=[],
            request_count=4,
            network_bytes=1_000,
            unique_range_bytes=800,
            duplicate_range_bytes=200,
            http_error_count=0,
            limitation=[],
        )

        result = artifacts.build_result(args)

        self.assertEqual(200, result["network"]["duplicateRangeBytes"])
        self.assertEqual([30], result["playback"]["seekToFrameNs"])

    def test_partial_result_requires_limitation(self):
        args = argparse.Namespace(
            run_id="run-1",
            status="PARTIAL",
            ttff_ns=None,
            stall_count=0,
            stall_total_ns=0,
            seek_to_frame_ns=[],
            playback_error_code=[],
            request_count=0,
            network_bytes=0,
            unique_range_bytes=0,
            duplicate_range_bytes=0,
            http_error_count=0,
            limitation=[],
        )

        with self.assertRaises(ValueError):
            artifacts.build_result(args)

    def test_writer_emits_stable_sorted_json(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            path = pathlib.Path(temp_dir) / "result.json"
            payload = {"z": 1, "a": {"b": 2}}

            artifacts.write_json(path, payload)

            text = path.read_text(encoding="utf-8")
            parsed = json.loads(text)
            self.assertEqual(payload, parsed)
            self.assertLess(text.index('"a"'), text.index('"z"'))


if __name__ == "__main__":
    unittest.main()
