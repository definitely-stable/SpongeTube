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

    def write_run_manifest(
        self,
        root: pathlib.Path,
        session_id: str,
        scenario_hash: str,
        run_id: str = "run-1",
    ) -> pathlib.Path:
        path = root / "run-manifest.json"
        path.write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "runId": run_id,
                    "sessionId": session_id,
                    "scenario": {
                        "scenarioId": "N0",
                        "scenarioHash": scenario_hash,
                    },
                }
            ),
            encoding="utf-8",
        )
        return path

    def playback_summary(
        self,
        session_id: str,
        status: str = "COMPLETE",
    ) -> dict:
        return {
            "schemaVersion": 1,
            "sessionId": session_id,
            "status": status,
            "ttffNs": 100,
            "stallCount": 0,
            "stallTotalNs": 0,
            "progressIntentNs": 1_000,
            "sessionWallNs": 1_200,
            "rebufferRatio": 0.0,
            "seekToFrame": [],
            "playbackErrorCodes": [],
            "issues": [],
        }

    def test_manifest_binds_fixture_hash_and_clock_domains(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = pathlib.Path(temp_dir)
            fixture_manifest = root / "manifest.json"
            fixture_manifest.write_text('{"schemaVersion":1}\n', encoding="utf-8")

            args = argparse.Namespace(
                run_id="run-1",
                session_id="session-1",
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
                session_id="session-1",
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

    def test_result_from_files_requires_shared_session_and_scenario(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = pathlib.Path(temp_dir)
            playback = root / "playback-summary.json"
            network = root / "network-summary.json"

            playback.write_text(
                json.dumps(
                    {
                        **self.playback_summary("session-1"),
                        "seekToFrame": [
                            {"operationId": 1, "durationNs": 50}
                        ],
                    }
                ),
                encoding="utf-8",
            )
            network.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "sessionId": "session-1",
                        "scenarioId": "N0",
                        "scenarioHash": "c" * 64,
                        "requestCount": 4,
                        "networkBytes": 1000,
                        "uniqueRangeBytes": 900,
                        "duplicateRangeBytes": 100,
                        "httpErrorCount": 0,
                    }
                ),
                encoding="utf-8",
            )

            run_manifest = self.write_run_manifest(
                root,
                "session-1",
                "c" * 64,
            )
            args = argparse.Namespace(
                run_id="run-1",
                session_id="session-1",
                scenario_hash="c" * 64,
                playback_summary=str(playback),
                network_summary=str(network),
                run_manifest=str(run_manifest),
                lab_calibration=None,
                limitation=[],
            )

            result = artifacts.build_result_from_files(args)

            self.assertEqual("COMPLETE", result["status"])
            self.assertEqual("session-1", result["sessionId"])
            self.assertEqual("c" * 64, result["scenarioHash"])
            self.assertEqual(
                artifacts.file_sha256(run_manifest),
                result["manifestSha256"],
            )
            self.assertEqual(0.0, result["playback"]["rebufferRatio"])
            self.assertEqual([50], result["playback"]["seekToFrameNs"])
            self.assertEqual(100, result["network"]["duplicateRangeBytes"])

    def test_result_from_files_rejects_session_mismatch(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = pathlib.Path(temp_dir)
            playback = root / "playback-summary.json"
            network = root / "network-summary.json"

            playback.write_text(
                json.dumps(
                    self.playback_summary("android-session")
                ),
                encoding="utf-8",
            )
            network.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "sessionId": "host-session",
                        "scenarioId": "N0",
                        "scenarioHash": "c" * 64,
                        "requestCount": 0,
                        "networkBytes": 0,
                        "uniqueRangeBytes": 0,
                        "duplicateRangeBytes": 0,
                        "httpErrorCount": 0,
                    }
                ),
                encoding="utf-8",
            )

            run_manifest = self.write_run_manifest(
                root,
                "android-session",
                "c" * 64,
            )
            args = argparse.Namespace(
                run_id="run-1",
                session_id="android-session",
                scenario_hash="c" * 64,
                playback_summary=str(playback),
                network_summary=str(network),
                run_manifest=str(run_manifest),
                lab_calibration=None,
                limitation=[],
            )

            with self.assertRaises(ValueError):
                artifacts.build_result_from_files(args)

    def test_result_from_files_includes_matching_lab_calibration(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = pathlib.Path(temp_dir)
            playback = root / "playback-summary.json"
            network = root / "network-summary.json"
            calibration = root / "calibration.json"

            playback.write_text(
                json.dumps(
                    self.playback_summary("session-1")
                ),
                encoding="utf-8",
            )
            network.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "sessionId": "session-1",
                        "scenarioId": "N1",
                        "scenarioHash": "c" * 64,
                        "requestCount": 4,
                        "networkBytes": 1000,
                        "uniqueRangeBytes": 900,
                        "duplicateRangeBytes": 100,
                        "httpErrorCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            calibration.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "scenarioId": "N1",
                        "scenarioHash": "c" * 64,
                        "observedRateBps": 310090,
                        "rateErrorPct": 0.5,
                        "observedFirstBodyDelayMs": 121,
                        "firstBodyDelayErrorMs": 1,
                        "observedNoProgressDurationMs": None,
                        "noProgressDurationErrorMs": None,
                        "maxSchedulerSlipMs": 2,
                    }
                ),
                encoding="utf-8",
            )

            run_manifest = self.write_run_manifest(
                root,
                "session-1",
                "c" * 64,
            )
            args = argparse.Namespace(
                run_id="run-1",
                session_id="session-1",
                scenario_hash="c" * 64,
                playback_summary=str(playback),
                network_summary=str(network),
                run_manifest=str(run_manifest),
                lab_calibration=str(calibration),
                limitation=[],
            )

            result = artifacts.build_result_from_files(args)

            self.assertEqual(310090, result["labAccuracy"]["observedRateBps"])
            self.assertEqual(1, result["labAccuracy"]["firstBodyDelayErrorMs"])
            self.assertEqual(2, result["labAccuracy"]["maxSchedulerSlipMs"])

    def test_result_from_files_includes_matching_cache_observations(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = pathlib.Path(temp_dir)
            playback = root / "playback-summary.json"
            network = root / "network-summary.json"
            cache = root / "baseline-observations.json"

            playback.write_text(
                json.dumps(self.playback_summary("session-1")),
                encoding="utf-8",
            )
            network.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "sessionId": "session-1",
                        "scenarioId": "N0",
                        "scenarioHash": "c" * 64,
                        "requestCount": 2,
                        "networkBytes": 100,
                        "uniqueRangeBytes": 100,
                        "duplicateRangeBytes": 0,
                        "httpErrorCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            cache.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "sessionId": "session-1",
                        "cacheBytesAtPreparation": 50,
                        "cacheBytesAtEnd": 80,
                        "cacheDeltaBytes": 30,
                    }
                ),
                encoding="utf-8",
            )
            run_manifest = self.write_run_manifest(
                root,
                "session-1",
                "c" * 64,
            )

            result = artifacts.build_result_from_files(
                argparse.Namespace(
                    run_id="run-1",
                    session_id="session-1",
                    scenario_hash="c" * 64,
                    playback_summary=str(playback),
                    network_summary=str(network),
                    run_manifest=str(run_manifest),
                    lab_calibration=None,
                    baseline_observations=str(cache),
                    limitation=[],
                )
            )

            self.assertEqual(
                50,
                result["cache"]["cacheBytesAtPreparation"],
            )
            self.assertEqual(80, result["cache"]["cacheBytesAtEnd"])
            self.assertEqual(30, result["cache"]["cacheDeltaBytes"])

    def test_result_from_files_rejects_cache_session_mismatch(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = pathlib.Path(temp_dir)
            playback = root / "playback-summary.json"
            network = root / "network-summary.json"
            cache = root / "baseline-observations.json"

            playback.write_text(
                json.dumps(self.playback_summary("session-1")),
                encoding="utf-8",
            )
            network.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "sessionId": "session-1",
                        "scenarioId": "N0",
                        "scenarioHash": "c" * 64,
                        "requestCount": 0,
                        "networkBytes": 0,
                        "uniqueRangeBytes": 0,
                        "duplicateRangeBytes": 0,
                        "httpErrorCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            cache.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "sessionId": "wrong-session",
                        "cacheBytesAtPreparation": 0,
                        "cacheBytesAtEnd": 0,
                        "cacheDeltaBytes": 0,
                    }
                ),
                encoding="utf-8",
            )
            run_manifest = self.write_run_manifest(
                root,
                "session-1",
                "c" * 64,
            )

            with self.assertRaises(ValueError):
                artifacts.build_result_from_files(
                    argparse.Namespace(
                        run_id="run-1",
                        session_id="session-1",
                        scenario_hash="c" * 64,
                        playback_summary=str(playback),
                        network_summary=str(network),
                        run_manifest=str(run_manifest),
                        lab_calibration=None,
                        baseline_observations=str(cache),
                        limitation=[],
                    )
                )

    def test_result_rejects_inconsistent_cache_delta(self):
        args = argparse.Namespace(
            run_id="run-1",
            session_id="session-1",
            scenario_hash="c" * 64,
            manifest_sha256="d" * 64,
            status="COMPLETE",
            ttff_ns=100,
            stall_count=0,
            stall_total_ns=0,
            progress_intent_ns=100,
            session_wall_ns=120,
            rebuffer_ratio=0.0,
            seek_to_frame_ns=[],
            playback_error_code=[],
            request_count=0,
            network_bytes=0,
            unique_range_bytes=0,
            duplicate_range_bytes=0,
            http_error_count=0,
            cache_bytes_at_preparation=10,
            cache_bytes_at_end=20,
            cache_delta_bytes=11,
            limitation=[],
        )

        with self.assertRaises(ValueError):
            artifacts.build_result(args)

    def test_result_from_files_rejects_calibration_scenario_mismatch(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = pathlib.Path(temp_dir)
            playback = root / "playback-summary.json"
            network = root / "network-summary.json"
            calibration = root / "calibration.json"

            playback.write_text(
                json.dumps(
                    self.playback_summary("session-1")
                ),
                encoding="utf-8",
            )
            network.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "sessionId": "session-1",
                        "scenarioId": "N0",
                        "scenarioHash": "c" * 64,
                        "requestCount": 0,
                        "networkBytes": 0,
                        "uniqueRangeBytes": 0,
                        "duplicateRangeBytes": 0,
                        "httpErrorCount": 0,
                    }
                ),
                encoding="utf-8",
            )
            calibration.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "scenarioId": "N0",
                        "scenarioHash": "d" * 64,
                    }
                ),
                encoding="utf-8",
            )

            run_manifest = self.write_run_manifest(
                root,
                "session-1",
                "c" * 64,
            )
            args = argparse.Namespace(
                run_id="run-1",
                session_id="session-1",
                scenario_hash="c" * 64,
                playback_summary=str(playback),
                network_summary=str(network),
                run_manifest=str(run_manifest),
                lab_calibration=str(calibration),
                limitation=[],
            )

            with self.assertRaises(ValueError):
                artifacts.build_result_from_files(args)

    def test_result_enforces_duplicate_byte_identity(self):
        args = argparse.Namespace(
            run_id="run-1",
            session_id="session-1",
            scenario_hash="c" * 64,
            manifest_sha256="d" * 64,
            status="COMPLETE",
            ttff_ns=100,
            stall_count=1,
            stall_total_ns=20,
            progress_intent_ns=100,
            session_wall_ns=200,
            rebuffer_ratio=0.2,
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
            session_id="session-1",
            scenario_hash="c" * 64,
            manifest_sha256="d" * 64,
            status="PARTIAL",
            ttff_ns=None,
            stall_count=0,
            stall_total_ns=0,
            progress_intent_ns=None,
            session_wall_ns=None,
            rebuffer_ratio=None,
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
