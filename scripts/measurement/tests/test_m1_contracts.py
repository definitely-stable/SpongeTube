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
    reconstruct_published_coverage,
)
from schema_subset import SchemaContractError, validate_instance


SCHEMAS = REPO_ROOT / ".work" / "schemas"
EXAMPLES = SCHEMAS / "examples" / "m1"

CONTRACTS = {
    "m1-run-manifest-v1.schema.json": "m1-run-manifest-v1.example.json",
    "seed-manifest-v1.schema.json": "seed-manifest-v1.example.json",
    "coverage-snapshot-v1.schema.json": "coverage-snapshot-v1.example.json",
    "extent-events-v1.schema.json": "extent-event-v1.example.json",
    "fetch-events-v1.schema.json": "fetch-event-v1.example.json",
    "recovery-summary-v1.schema.json": "recovery-summary-v1.example.json",
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
                    1,
                    schema["properties"]["schemaVersion"]["const"],
                )
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

    def test_oracle_rejects_non_playable_extent_states(self):
        events = [
            self.extent(1, "v-init", "video", "v1", None, None),
            self.extent(2, "a-init", "audio", "a1", None, None),
            self.extent(
                3, "v-good", "video", "v1", 0, 30, deps=["v-init"]
            ),
            self.extent(
                4, "a-good", "audio", "a1", 0, 30, deps=["a-init"]
            ),
            self.extent(
                5, "v-wrong", "video", "v2", 30, 60, deps=["v-init"]
            ),
            self.extent(
                6,
                "a-corrupt",
                "audio",
                "a1",
                30,
                60,
                integrity="CORRUPT",
            ),
            self.extent(
                7,
                "v-missing-dep",
                "video",
                "v1",
                30,
                60,
                deps=["never-published"],
            ),
            self.extent(
                8,
                "a-unpublished",
                "audio",
                "a1",
                30,
                60,
                state="DURABLE",
            ),
        ]

        verified = {
            "v-init",
            "a-init",
            "v-good",
            "a-good",
            "v-wrong",
            "a-corrupt",
            "v-missing-dep",
            "a-unpublished",
        }
        coverage = reconstruct_published_coverage(
            events,
            {"video": "v1", "audio": "a1"},
            verified,
        )
        self.assertEqual(
            (Interval(0, 30),),
            coverage["video"],
        )
        self.assertEqual(
            (Interval(0, 30),),
            coverage["audio"],
        )

    def test_latest_quarantine_removes_previous_publication(self):
        events = [
            self.extent(1, "v-init", "video", "v1", None, None),
            self.extent(2, "a-init", "audio", "a1", None, None),
            self.extent(
                3, "v-1", "video", "v1", 0, 30, deps=["v-init"]
            ),
            self.extent(
                4, "a-1", "audio", "a1", 0, 30, deps=["a-init"]
            ),
            self.extent(
                5,
                "v-1",
                "video",
                "v1",
                0,
                30,
                deps=["v-init"],
                state="QUARANTINED",
                integrity="CORRUPT",
            ),
        ]
        snapshot = oracle_snapshot(
            events,
            {"video": "v1", "audio": "a1"},
            {"v-init", "a-init", "v-1", "a-1"},
            0,
        )
        self.assertEqual([], snapshot["playableIntervals"])
        self.assertEqual(0, snapshot["durableReserveUs"])

    @staticmethod
    def extent(
        sequence,
        extent_id,
        track_id,
        representation_id,
        start,
        end,
        *,
        deps=None,
        state="PUBLISHED",
        integrity="VALID",
    ):
        return {
            "eventSequence": sequence,
            "extentId": extent_id,
            "state": state,
            "integrityState": integrity,
            "trackId": track_id,
            "representationId": representation_id,
            "mediaStartUs": start,
            "mediaEndUs": end,
            "dependencyExtentIds": deps or [],
        }


if __name__ == "__main__":
    unittest.main()
