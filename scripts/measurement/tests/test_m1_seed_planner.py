import pathlib
import sys
import unittest

SCRIPT_DIR = pathlib.Path(__file__).resolve().parents[1]
REPO_ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from m1_seed_planner import build_seed, load_f1_catalog


class M1SeedPlannerTest(unittest.TestCase):
    def test_f1_catalog_uses_exact_dash_timeline(self):
        catalog = load_f1_catalog(REPO_ROOT)
        video = [
            item for item in catalog
            if item.track_id == "video-main"
            and item.media_start_us is not None
        ]
        audio = [
            item for item in catalog
            if item.track_id == "audio-main"
            and item.media_start_us is not None
        ]

        self.assertEqual(18, len(video))
        self.assertEqual(19, len(audio))
        self.assertEqual(10_000_000, video[0].media_end_us)
        self.assertEqual(9_941_333, audio[0].media_end_us)
        self.assertEqual(29_952_000, audio[2].media_end_us)

    def test_positive_seeds_reach_semantic_targets(self):
        expected_media_counts = {
            "S0": (0, 0),
            "S10": (1, 2),
            "S30": (3, 4),
            "S60": (6, 7),
            "S120": (12, 13),
        }

        for seed_id, counts in expected_media_counts.items():
            with self.subTest(seed=seed_id):
                plan = build_seed(REPO_ROOT, seed_id)
                video = [
                    item for item in plan.units
                    if item.track_id == "video-main"
                    and item.media_start_us is not None
                ]
                audio = [
                    item for item in plan.units
                    if item.track_id == "audio-main"
                    and item.media_start_us is not None
                ]
                self.assertEqual(counts, (len(video), len(audio)))
                _, _, reserve = plan.coverage()
                self.assertGreaterEqual(
                    reserve,
                    plan.target_playable_end_us,
                )

    def test_negative_seeds_are_conservative(self):
        expected_max_reserve = {
            "S30_VIDEO_HOLE": 10_000_000,
            "S30_AUDIO_HOLE": 9_941_333,
            "S30_MISSING_INIT": 0,
            "S30_PARTIAL_TAIL": 20_000_000,
            "S30_WRONG_REPRESENTATION": 10_000_000,
        }

        for seed_id, max_reserve in expected_max_reserve.items():
            with self.subTest(seed=seed_id):
                plan = build_seed(REPO_ROOT, seed_id)
                _, _, reserve = plan.coverage()
                self.assertLessEqual(reserve, max_reserve)
                self.assertIsNotNone(plan.negative_case)

        partial = build_seed(REPO_ROOT, "S30_PARTIAL_TAIL")
        self.assertEqual(1, len(partial.rejected_attempts))
        self.assertLess(
            partial.rejected_attempts[0].received_length,
            partial.rejected_attempts[0].expected_length,
        )


if __name__ == "__main__":
    unittest.main()
