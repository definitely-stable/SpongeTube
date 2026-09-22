import copy
import json
import pathlib
import sys
import unittest

SCRIPT_DIR = pathlib.Path(__file__).resolve().parents[1]
REPO_ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from m1_oracle import (
    Interval,
    durable_reserve_us,
    intersect_required,
    normalize_intervals,
    oracle_snapshot,
    reconstruct_committed_coverage,
)
from schema_subset import SchemaContractError, validate_instance


SCHEMAS = REPO_ROOT / ".work" / "schemas"
EXAMPLES = SCHEMAS / "examples" / "m1"

CONTRACTS = {
    "m1-run-manifest-v1.schema.json": "m1-run-manifest-v1.example.json",
    "seed-manifest-v1.schema.json": "seed-manifest-v1.example.json",
    "seed-manifest-v2.schema.json": "seed-manifest-v2.example.json",
    "coverage-snapshot-v1.schema.json": "coverage-snapshot-v1.example.json",
    "coverage-snapshot-v2.schema.json": "coverage-snapshot-v2.example.json",
    "extent-events-v1.schema.json": "extent-event-v1.example.json",
    "fetch-events-v1.schema.json": "fetch-event-v1.example.json",
    "recovery-summary-v1.schema.json": "recovery-summary-v1.example.json",
    "committed-extents-v1.schema.json": "committed-extents-v1.example.json",
    "committed-extents-v2.schema.json": "committed-extents-v2.example.json",
    "verified-extent-files-v1.schema.json": "verified-extent-files-v1.example.json",
}


class M1SchemaContractTest(unittest.TestCase):
    def test_all_m1_schemas_parse_and_examples_validate(self):
        for schema_name, example_name in CONTRACTS.items():
            with self.subTest(schema=schema_name):
                schema = json.loads(
                    (SCHEMAS / schema_name).read_text(encoding="utf-8")
                )
                example = json.loads(
                    (EXAMPLES / example_name).read_text(encoding="utf-8")
                )
                self.assertEqual(
                    "https://json-schema.org/draft/2020-12/schema",
                    schema["$schema"],
                )
                self.assertEqual(
                    example["schemaVersion"],
                    schema["properties"]["schemaVersion"]["const"],
                )
                self.assertIn(example["schemaVersion"], (1, 2))
                validate_instance(schema, example)

    def test_validator_rejects_missing_required_property(self):
        schema = json.loads(
            (SCHEMAS / "coverage-snapshot-v1.schema.json").read_text(
                encoding="utf-8"
            )
        )
        example = json.loads(
            (EXAMPLES / "coverage-snapshot-v1.example.json").read_text(
                encoding="utf-8"
            )
        )
        broken = copy.deepcopy(example)
        del broken["durableReserveUs"]

        with self.assertRaises(SchemaContractError):
            validate_instance(schema, broken)

    def test_validator_rejects_invalid_hash(self):
        schema = json.loads(
            (SCHEMAS / "seed-manifest-v1.schema.json").read_text(
                encoding="utf-8"
            )
        )
        example = json.loads(
            (EXAMPLES / "seed-manifest-v1.example.json").read_text(
                encoding="utf-8"
            )
        )
        broken = copy.deepcopy(example)
        broken["fixtureSha256"] = "not-a-sha256"

        with self.assertRaises(SchemaContractError):
            validate_instance(schema, broken)

    def test_asset_scoped_v2_requires_media_asset_id(self):
        schema = json.loads(
            (SCHEMAS / "coverage-snapshot-v2.schema.json").read_text(
                encoding="utf-8"
            )
        )
        example = json.loads(
            (EXAMPLES / "coverage-snapshot-v2.example.json").read_text(
                encoding="utf-8"
            )
        )
        broken = copy.deepcopy(example)
        del broken["mediaAssetId"]

        with self.assertRaises(SchemaContractError):
            validate_instance(schema, broken)

    def test_validator_rejects_boolean_for_integer_const(self):
        schema = json.loads(
            (SCHEMAS / "m1-run-manifest-v1.schema.json").read_text(
                encoding="utf-8"
            )
        )
        example = json.loads(
            (EXAMPLES / "m1-run-manifest-v1.example.json").read_text(
                encoding="utf-8"
            )
        )
        broken = copy.deepcopy(example)
        broken["schemaVersion"] = True

        with self.assertRaises(SchemaContractError):
            validate_instance(schema, broken)


class M1CoverageOracleTest(unittest.TestCase):
    def test_normalize_merges_overlap_and_adjacency(self):
        self.assertEqual(
            (Interval(0, 20), Interval(30, 50)),
            normalize_intervals(
                [(0, 10), (10, 20), (30, 40), (35, 50)]
            ),
        )

    def test_required_track_intersection_is_conservative(self):
        playable = intersect_required(
            {
                "video": [(0, 60_000_000)],
                "audio": [(0, 70_000_000)],
            },
            ["video", "audio"],
        )
        self.assertEqual(
            (Interval(0, 60_000_000),),
            playable,
        )

    def test_reserve_stops_at_first_hole(self):
        playable = [
            (0, 30_000_000),
            (40_000_000, 90_000_000),
        ]
        self.assertEqual(
            20_000_000,
            durable_reserve_us(10_000_000, playable),
        )
        self.assertEqual(
            0,
            durable_reserve_us(35_000_000, playable),
        )
        self.assertEqual(
            45_000_000,
            durable_reserve_us(45_000_000, playable),
        )

    def test_oracle_rejects_non_playable_committed_rows(self):
        rows = [
            self.row("v-init", "video", "v1", None, None),
            self.row("a-init", "audio", "a1", None, None),
            self.row("v-good", "video", "v1", 0, 30, deps=["v-init"]),
            self.row("a-good", "audio", "a1", 0, 30, deps=["a-init"]),
            self.row("v-wrong", "video", "v2", 30, 60, deps=["v-init"]),
            self.row(
                "a-corrupt",
                "audio",
                "a1",
                30,
                60,
                integrity="CORRUPT",
            ),
            self.row(
                "v-missing-dep",
                "video",
                "v1",
                30,
                60,
                deps=["never-published"],
            ),
            self.row(
                "a-unpublished",
                "audio",
                "a1",
                30,
                60,
                state="QUARANTINED",
            ),
        ]
        files = {
            row["extentId"]: self.file_fact(row)
            for row in rows
        }

        coverage = reconstruct_committed_coverage(
            rows,
            "asset-f1",
            {"video": "v1", "audio": "a1"},
            files,
        )
        self.assertEqual(
            (Interval(0, 30),),
            coverage["video"],
        )
        self.assertEqual(
            (Interval(0, 30),),
            coverage["audio"],
        )

    def test_oracle_does_not_merge_different_media_assets(self):
        rows = [
            self.row("v-init", "video", "v1", None, None),
            self.row("a-init", "audio", "a1", None, None),
            self.row("v-good", "video", "v1", 0, 10, deps=["v-init"]),
            self.row("a-good", "audio", "a1", 0, 20, deps=["a-init"]),
            self.row(
                "v-other",
                "video",
                "v1",
                10,
                20,
                asset="asset-other",
            ),
        ]
        files = {
            row["extentId"]: self.file_fact(row)
            for row in rows
        }

        snapshot = oracle_snapshot(
            rows,
            "asset-f1",
            {"video": "v1", "audio": "a1"},
            files,
            0,
        )

        self.assertEqual(
            [{"startUs": 0, "endUs": 10}],
            snapshot["playableIntervals"],
        )
        self.assertEqual(10, snapshot["durableReserveUs"])

    def test_published_event_without_committed_row_is_not_coverage(self):
        # A lifecycle event may have been emitted immediately before a crash.
        phantom_published_event = {
            "extentId": "v-phantom",
            "state": "PUBLISHED",
            "integrityState": "VALID",
        }
        self.assertEqual("PUBLISHED", phantom_published_event["state"])

        row = self.row("v-phantom", "video", "v1", 0, 30)
        snapshot = oracle_snapshot(
            committed_rows=[],
            media_asset_id="asset-f1",
            required_representations={"video": "v1"},
            verified_files={"v-phantom": self.file_fact(row)},
            playhead_us=0,
        )

        self.assertEqual([], snapshot["playableIntervals"])
        self.assertEqual(0, snapshot["durableReserveUs"])

    def test_committed_row_requires_matching_verified_file(self):
        rows = [
            self.row("v-init", "video", "v1", None, None),
            self.row("v-1", "video", "v1", 0, 30, deps=["v-init"]),
        ]
        files = {
            row["extentId"]: self.file_fact(row)
            for row in rows
        }
        files["v-1"] = {
            **files["v-1"],
            "sha256": "f" * 64,
        }

        coverage = reconstruct_committed_coverage(
            rows,
            "asset-f1",
            {"video": "v1"},
            files,
        )
        self.assertEqual((), coverage["video"])

    def test_quarantined_committed_row_is_not_coverage(self):
        rows = [
            self.row("v-init", "video", "v1", None, None),
            self.row("a-init", "audio", "a1", None, None),
            self.row(
                "v-1",
                "video",
                "v1",
                0,
                30,
                deps=["v-init"],
                state="QUARANTINED",
                integrity="CORRUPT",
            ),
            self.row(
                "a-1",
                "audio",
                "a1",
                0,
                30,
                deps=["a-init"],
            ),
        ]
        files = {
            row["extentId"]: self.file_fact(row)
            for row in rows
        }

        snapshot = oracle_snapshot(
            rows,
            "asset-f1",
            {"video": "v1", "audio": "a1"},
            files,
            0,
        )
        self.assertEqual([], snapshot["playableIntervals"])
        self.assertEqual(0, snapshot["durableReserveUs"])

    @staticmethod
    def row(
        extent_id,
        track_id,
        representation_id,
        start,
        end,
        *,
        deps=None,
        state="PUBLISHED",
        integrity="VALID",
        asset="asset-f1",
    ):
        sha256 = (extent_id.encode("utf-8").hex() + "0" * 64)[:64]
        return {
            "extentId": extent_id,
            "mediaAssetId": asset,
            "state": state,
            "integrityState": integrity,
            "trackId": track_id,
            "representationId": representation_id,
            "mediaStartUs": start,
            "mediaEndUs": end,
            "dependencyExtentIds": deps or [],
            "length": 1024,
            "sha256": sha256,
            "storagePath": f"filesDir/sponge/extents/{extent_id}",
        }

    @staticmethod
    def file_fact(row):
        return {
            "extentId": row["extentId"],
            "exists": True,
            "length": row["length"],
            "sha256": row["sha256"],
            "storagePath": row["storagePath"],
        }


if __name__ == "__main__":
    unittest.main()
