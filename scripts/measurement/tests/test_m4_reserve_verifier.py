"""Falsification tests for the independent M4-A1b Android F1 verifier."""

import copy
import json
import pathlib
import sys
import unittest

SCRIPT_DIR = pathlib.Path(__file__).resolve().parents[1]
REPO_ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from m4_reserve_verifier import expected_f1_units, verify_origin, verify_storage


class M4ReserveVerifierTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        fixture_root = REPO_ROOT / "test-fixtures" / "media"
        manifest = json.loads(
            (fixture_root / "manifest.json").read_text(encoding="utf-8")
        )
        cls.units, cls.playable_end = expected_f1_units(
            (fixture_root / "F1" / "manifest.mpd").read_bytes(),
            manifest,
        )

    def origin_rows(self):
        return [
            {
                "plane": "data",
                "path": "/fixtures/" + unit.path,
                "requestId": idx + 1,
                "status": 206,
                "outcome": "SUCCESS",
                "resolvedRangeStart": 0,
                "resolvedRangeEndExclusive": unit.length,
                "bodyBytesWritten": unit.length,
            }
            for idx, unit in enumerate(self.units)
        ]

    def storage_rows(self):
        committed = []
        verified = []
        for unit in self.units:
            kind = unit.track_id.split("-")[0]
            rep_id = unit.representation_id[-1]
            dependencies = (
                [] if unit.start_us is None
                else [f"f1:{kind}:{rep_id}:init"]
            )
            committed.append({
                "extentId": unit.extent_id,
                "mediaAssetId": "fixture:F1",
                "state": "PUBLISHED",
                "integrityState": "VALID",
                "trackId": unit.track_id,
                "representationId": unit.representation_id,
                "mediaStartUs": unit.start_us,
                "mediaEndUs": unit.end_us,
                "dependencyExtentIds": dependencies,
                "length": unit.length,
                "sha256": unit.sha256,
                "storagePath": f"extents/{unit.extent_id}",
            })
            verified.append({
                "extentId": unit.extent_id,
                "exists": True,
                "length": unit.length,
                "sha256": unit.sha256,
                "storagePath": f"extents/{unit.extent_id}",
            })
        return (
            {"extents": committed},
            {"files": verified},
            {"durableReserveUs": self.playable_end,
             "durablePlayableEndUs": self.playable_end},
        )

    def test_canonical_first_audio_video_units_from_mpd(self):
        self.assertEqual(4, len(self.units))
        self.assertEqual(9_941_333, self.playable_end)
        self.assertEqual(
            [
                "F1/init-1.m4s",
                "F1/segment-1-00001.m4s",
                "F1/init-0.m4s",
                "F1/segment-0-00001.m4s",
            ],
            [u.path for u in self.units],
        )

    def test_accepts_one_physical_request_per_expected_extent(self):
        self.assertEqual(
            4, len(verify_origin(self.origin_rows(), self.units)),
        )

    def test_duplicate_physical_request_fails(self):
        rows = self.origin_rows()
        rows.insert(1, dict(rows[0], requestId=99))
        with self.assertRaisesRegex(ValueError, "origin request sequence"):
            verify_origin(rows, self.units)

    def test_unrelated_extra_media_request_fails(self):
        rows = self.origin_rows()
        rows.append(dict(rows[0], path="/fixtures/F1/segment-1-00002.m4s"))
        with self.assertRaisesRegex(ValueError, "origin request sequence"):
            verify_origin(rows, self.units)

    def test_missing_audio_media_request_fails(self):
        rows = self.origin_rows()
        del rows[1]
        with self.assertRaisesRegex(ValueError, "origin request sequence"):
            verify_origin(rows, self.units)

    def test_forged_origin_byte_count_fails(self):
        rows = self.origin_rows()
        rows[0]["bodyBytesWritten"] -= 1
        with self.assertRaisesRegex(ValueError, "body count"):
            verify_origin(rows, self.units)

    def test_forged_http_success_with_wrong_status_fails(self):
        rows = self.origin_rows()
        rows[0]["status"] = 200
        with self.assertRaisesRegex(ValueError, "unsuccessful"):
            verify_origin(rows, self.units)

    def test_duplicate_request_id_fails(self):
        rows = self.origin_rows()
        rows[1]["requestId"] = rows[0]["requestId"]
        with self.assertRaisesRegex(ValueError, "duplicate Media Lab"):
            verify_origin(rows, self.units)

    def test_valid_independent_committed_file_and_reserve_facts(self):
        committed, files, oracle = self.storage_rows()
        verify_storage(
            committed, files, oracle, self.units, self.playable_end,
        )

    def test_wrong_sha256_on_real_file_fails(self):
        committed, files, oracle = self.storage_rows()
        files["files"][1]["sha256"] = "f" * 64
        with self.assertRaisesRegex(ValueError, "real bytes"):
            verify_storage(
                committed, files, oracle, self.units, self.playable_end,
            )

    def test_missing_audio_extent_fails_even_if_video_is_present(self):
        committed, files, oracle = self.storage_rows()
        committed["extents"].pop(1)
        with self.assertRaisesRegex(ValueError, "committed extent identities"):
            verify_storage(
                committed, files, oracle, self.units, self.playable_end,
            )

    def test_incorrect_dependency_fails(self):
        committed, files, oracle = self.storage_rows()
        committed["extents"][1]["dependencyExtentIds"] = []
        with self.assertRaisesRegex(ValueError, "wrong init dependency"):
            verify_storage(
                committed, files, oracle, self.units, self.playable_end,
            )

    def test_oracle_reserve_cannot_exceed_required_audio_boundary(self):
        committed, files, oracle = self.storage_rows()
        oracle["durableReserveUs"] = 10_000_000
        with self.assertRaisesRegex(ValueError, "independent playable reserve"):
            verify_storage(
                committed, files, oracle, self.units, self.playable_end,
            )


if __name__ == "__main__":
    unittest.main()
