from dataclasses import replace
import json
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPT_DIR = pathlib.Path(__file__).resolve().parents[1]
REPO_ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from m1_seed_planner import (
    build_seed,
    load_f1_catalog,
    validate_seed_plan,
    verify_seed_state,
)


class M1SeedPlannerTest(unittest.TestCase):
    def test_catalog_uses_exact_f1_dash_timeline_and_fixture_hashes(self):
        catalog, timeline_sha = load_f1_catalog(REPO_ROOT)

        video = [
            item
            for item in catalog
            if item.track_id == "video-main"
            and item.media_start_us is not None
        ]
        audio = [
            item
            for item in catalog
            if item.track_id == "audio-main"
            and item.media_start_us is not None
        ]

        self.assertEqual(18, len(video))
        self.assertEqual(19, len(audio))
        self.assertEqual(10_000_000, video[0].media_end_us)
        self.assertEqual(9_941_333, audio[0].media_end_us)
        self.assertEqual(29_952_000, audio[2].media_end_us)
        self.assertEqual(
            "e0e05820165ae7c93c81ee8a71a4a3c1412bf6262416dcb1709c769caee07a56",
            timeline_sha,
        )
        self.assertEqual(
            "f3e8a844487d57a05c69975389566bde3bcb38d9afa1d53538be18c959d77fa3",
            video[0].sha256,
        )

    def test_positive_seeds_select_exact_units_without_duration_assumption(self):
        expected_media_counts = {
            "S0": (0, 0),
            "S10": (1, 2),
            "S30": (3, 4),
            "S60": (6, 7),
            "S120": (12, 13),
        }

        for seed_id, expected in expected_media_counts.items():
            with self.subTest(seed=seed_id):
                plan = build_seed(REPO_ROOT, seed_id)
                video = [
                    item
                    for item in plan.units
                    if item.track_id == "video-main"
                    and item.media_start_us is not None
                ]
                audio = [
                    item
                    for item in plan.units
                    if item.track_id == "audio-main"
                    and item.media_start_us is not None
                ]
                self.assertEqual(expected, (len(video), len(audio)))
                self.assertEqual("fixture:F1", plan.media_asset_id)

    def test_negative_seed_construction_is_explicit(self):
        video_hole = build_seed(REPO_ROOT, "S30_VIDEO_HOLE")
        self.assertNotIn(
            "f1:video:0:2",
            {unit.extent_id for unit in video_hole.units},
        )
        self.assertEqual("VIDEO_HOLE", video_hole.negative_case)

        audio_hole = build_seed(REPO_ROOT, "S30_AUDIO_HOLE")
        self.assertNotIn(
            "f1:audio:1:2",
            {unit.extent_id for unit in audio_hole.units},
        )

        missing_init = build_seed(REPO_ROOT, "S30_MISSING_INIT")
        self.assertNotIn(
            "f1:video:0:init",
            {unit.extent_id for unit in missing_init.units},
        )

        wrong = build_seed(REPO_ROOT, "S30_WRONG_REPRESENTATION")
        wrong_units = [
            unit
            for unit in wrong.units
            if unit.representation_id == "f1-video-alt"
        ]
        self.assertEqual(3, len(wrong_units))

        partial = build_seed(REPO_ROOT, "S30_PARTIAL_TAIL")
        self.assertEqual(1, len(partial.rejected_attempts))
        rejected = partial.rejected_attempts[0]
        self.assertEqual("f1:video:0:3", rejected.extent_id)
        self.assertLess(rejected.received_length, rejected.expected_length)
        self.assertNotIn(
            rejected.extent_id,
            {unit.extent_id for unit in partial.units},
        )

    def test_seed_manifest_contains_construction_not_coverage_oracle_fields(self):
        artifact = build_seed(REPO_ROOT, "S10").to_artifact()
        self.assertEqual(2, artifact["schemaVersion"])
        self.assertEqual("F1/manifest.mpd", artifact["timelineResourcePath"])
        self.assertNotIn("playableCoverage", artifact)
        self.assertNotIn("perTrackCoverage", artifact)
        self.assertNotIn("actualReserveUs", artifact)

    def test_fixture_bytes_are_hashed_not_only_manifest_claims(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            media = root / "test-fixtures" / "media"
            media.mkdir(parents=True)
            shutil.copy2(
                REPO_ROOT / "test-fixtures/media/manifest.json",
                media / "manifest.json",
            )
            shutil.copytree(
                REPO_ROOT / "test-fixtures/media/F1",
                media / "F1",
            )
            corrupted = media / "F1" / "video-00001.m4s"
            blob = bytearray(corrupted.read_bytes())
            blob[0] ^= 0x01
            corrupted.write_bytes(blob)

            with self.assertRaisesRegex(
                ValueError,
                "sha256 mismatch",
            ):
                load_f1_catalog(root)

    def test_seed_semantics_fail_closed_for_invalid_partial_tail(self):
        plan = build_seed(REPO_ROOT, "S30_PARTIAL_TAIL")
        attempt = plan.rejected_attempts[0]
        broken = replace(
            plan,
            rejected_attempts=(
                replace(
                    attempt,
                    received_length=attempt.expected_length,
                ),
            ),
        )

        with self.assertRaisesRegex(ValueError, "strictly truncated"):
            validate_seed_plan(broken)

    def test_committed_state_must_match_exact_seed_construction(self):
        plan = build_seed(REPO_ROOT, "S30_WRONG_REPRESENTATION")
        committed = self._committed_from_plan(plan)

        verify_seed_state(plan, committed)

        committed["extents"][0]["mediaAssetId"] = "other-asset"
        with self.assertRaisesRegex(ValueError, "mediaAssetId mismatch"):
            verify_seed_state(plan, committed)

    def test_cli_can_verify_committed_seed_state(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            output = root / "seed.json"
            committed_path = root / "committed.json"
            plan = build_seed(REPO_ROOT, "S30_AUDIO_HOLE")
            committed_path.write_text(
                json.dumps(self._committed_from_plan(plan)),
                encoding="utf-8",
            )

            result = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT_DIR / "m1_seed_planner.py"),
                    "--repo-root",
                    str(REPO_ROOT),
                    "--seed-id",
                    plan.seed_id,
                    "--output",
                    str(output),
                    "--verify-committed",
                    str(committed_path),
                ],
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertEqual(0, result.returncode, result.stderr)

    @staticmethod
    def _committed_from_plan(plan):
        return {
            "schemaVersion": 2,
            "snapshotId": "seed-state",
            "sessionId": "seed-session",
            "snapshotKind": "LIVE_COMMITTED",
            "databaseSchemaVersion": 2,
            "extents": [
                {
                    "extentId": unit.extent_id,
                    "mediaAssetId": plan.media_asset_id,
                    "state": "PUBLISHED",
                    "integrityState": "VALID",
                    "trackId": unit.track_id,
                    "representationId": unit.representation_id,
                    "mediaStartUs": unit.media_start_us,
                    "mediaEndUs": unit.media_end_us,
                    "dependencyExtentIds": list(unit.dependency_extent_ids),
                    "length": unit.length,
                    "sha256": unit.sha256,
                    "storagePath": "ignored-by-seed-state-verifier",
                }
                for unit in plan.units
            ],
        }

    def test_cli_writes_deterministic_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            output = pathlib.Path(directory) / "seed.json"
            result = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT_DIR / "m1_seed_planner.py"),
                    "--repo-root",
                    str(REPO_ROOT),
                    "--seed-id",
                    "S30_PARTIAL_TAIL",
                    "--output",
                    str(output),
                ],
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            payload = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual("PARTIAL_TAIL", payload["negativeCase"])
            self.assertEqual(1, len(payload["rejectedAttempts"]))


if __name__ == "__main__":
    unittest.main()
