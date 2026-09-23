import copy
import hashlib
import json
import pathlib
import sqlite3
import sys
import tempfile
import unittest

SCRIPT_DIR = pathlib.Path(__file__).resolve().parents[1]
REPO_ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from m1_oracle import (
    build_canonical_oracle_snapshot,
    compare_coverage_semantics,
    export_committed_snapshot,
    main,
    verify_extent_files,
)
from schema_subset import validate_instance


SCHEMAS = REPO_ROOT / ".work" / "schemas"


class M1EvidenceKernelTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temp.name)
        self.storage_root = self.root / "sponge"
        self.storage_root.mkdir()
        self.database = self.storage_root / "metadata" / "extents.db"
        self.database.parent.mkdir()

        self.init_bytes = b"init-bytes"
        self.media_bytes = b"0123456789"
        self.init_path = self._canonical_storage_path("v-init")
        self.media_path = self._canonical_storage_path("v-0")
        self._write_extent(self.init_path, self.init_bytes)
        self._write_extent(self.media_path, self.media_bytes)
        self._create_database()

    def tearDown(self):
        self.temp.cleanup()

    @staticmethod
    def _canonical_storage_path(extent_id):
        key = hashlib.sha256(extent_id.encode("utf-8")).hexdigest()
        return f"extents/{key[:2]}/{key}.extent"

    def _write_extent(self, relative_path, data):
        path = self.storage_root / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def _create_database(self):
        with sqlite3.connect(self.database) as connection:
            connection.execute("PRAGMA user_version = 1")
            connection.execute(
                """
                CREATE TABLE extents (
                    extent_id TEXT PRIMARY KEY NOT NULL,
                    track_id TEXT NOT NULL,
                    representation_id TEXT NOT NULL,
                    media_start_us INTEGER,
                    media_end_us INTEGER,
                    byte_start INTEGER,
                    byte_end_exclusive INTEGER,
                    length INTEGER NOT NULL,
                    sha256 TEXT NOT NULL,
                    storage_path TEXT NOT NULL,
                    publication_state TEXT NOT NULL,
                    integrity_state TEXT NOT NULL,
                    quarantine_reason TEXT,
                    published_at_epoch_ms INTEGER NOT NULL
                )
                """
            )
            connection.execute(
                """
                CREATE TABLE extent_dependencies (
                    extent_id TEXT NOT NULL,
                    dependency_extent_id TEXT NOT NULL,
                    PRIMARY KEY(extent_id, dependency_extent_id)
                )
                """
            )
            self._insert_row(
                connection,
                "v-init",
                None,
                None,
                self.init_bytes,
                self.init_path,
            )
            self._insert_row(
                connection,
                "v-0",
                0,
                10_000_000,
                self.media_bytes,
                self.media_path,
            )
            connection.execute(
                """
                INSERT INTO extent_dependencies(extent_id, dependency_extent_id)
                VALUES (?, ?)
                """,
                ("v-0", "v-init"),
            )

    def _insert_row(
        self,
        connection,
        extent_id,
        media_start_us,
        media_end_us,
        data,
        storage_path,
        *,
        representation_id="v1",
        state="PUBLISHED",
        integrity="VALID",
        sha256=None,
        length=None,
    ):
        connection.execute(
            """
            INSERT INTO extents(
                extent_id,
                track_id,
                representation_id,
                media_start_us,
                media_end_us,
                byte_start,
                byte_end_exclusive,
                length,
                sha256,
                storage_path,
                publication_state,
                integrity_state,
                quarantine_reason,
                published_at_epoch_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                extent_id,
                "video",
                representation_id,
                media_start_us,
                media_end_us,
                None,
                None,
                len(data) if length is None else length,
                hashlib.sha256(data).hexdigest() if sha256 is None else sha256,
                storage_path,
                state,
                integrity,
                None,
                1,
            ),
        )

    def _committed(self):
        return export_committed_snapshot(
            self.database,
            snapshot_id="committed-1",
            session_id="session-1",
            snapshot_kind="POST_RECOVERY",
        )

    def _verified(self, committed=None):
        return verify_extent_files(
            self._committed() if committed is None else committed,
            self.storage_root,
            snapshot_id="files-1",
        )

    def _oracle(self):
        committed = self._committed()
        return build_canonical_oracle_snapshot(
            committed,
            self._verified(committed),
            {"video": "v1"},
            5_000_000,
        )

    def test_end_to_end_reads_sqlite_and_hashes_real_files(self):
        committed = self._committed()
        self.assertEqual(1, committed["databaseSchemaVersion"])
        self.assertEqual(["v-0", "v-init"], [
            row["extentId"] for row in committed["extents"]
        ])

        verified = self._verified(committed)
        facts = {item["extentId"]: item for item in verified["files"]}
        self.assertTrue(facts["v-init"]["exists"])
        self.assertEqual(len(self.init_bytes), facts["v-init"]["length"])
        self.assertEqual(
            hashlib.sha256(self.init_bytes).hexdigest(),
            facts["v-init"]["sha256"],
        )
        self.assertEqual(
            hashlib.sha256(self.media_bytes).hexdigest(),
            facts["v-0"]["sha256"],
        )

        oracle = build_canonical_oracle_snapshot(
            committed,
            verified,
            {"video": "v1"},
            5_000_000,
        )
        validate_instance(
            json.loads(
                (SCHEMAS / "coverage-snapshot-v1.schema.json").read_text(
                    encoding="utf-8"
                )
            ),
            oracle,
        )
        self.assertEqual(
            [{"startUs": 0, "endUs": 10_000_000}],
            oracle["playableIntervals"],
        )
        self.assertEqual(10_000_000, oracle["durablePlayableEndUs"])
        self.assertEqual(5_000_000, oracle["durableReserveUs"])

    def test_missing_file_fails_closed(self):
        (self.storage_root / self.media_path).unlink()
        committed = self._committed()
        verified = self._verified(committed)
        facts = {item["extentId"]: item for item in verified["files"]}

        self.assertFalse(facts["v-0"]["exists"])
        self.assertIsNone(facts["v-0"]["length"])
        self.assertIsNone(facts["v-0"]["sha256"])

        oracle = build_canonical_oracle_snapshot(
            committed,
            verified,
            {"video": "v1"},
            0,
        )
        self.assertEqual([], oracle["playableIntervals"])
        self.assertEqual(0, oracle["durableReserveUs"])

    def test_truncated_file_fails_closed(self):
        (self.storage_root / self.media_path).write_bytes(b"short")
        committed = self._committed()
        verified = self._verified(committed)
        facts = {item["extentId"]: item for item in verified["files"]}

        self.assertEqual(5, facts["v-0"]["length"])
        self.assertNotEqual(
            next(
                row["sha256"]
                for row in committed["extents"]
                if row["extentId"] == "v-0"
            ),
            facts["v-0"]["sha256"],
        )

        oracle = build_canonical_oracle_snapshot(
            committed,
            verified,
            {"video": "v1"},
            0,
        )
        self.assertEqual([], oracle["playableIntervals"])

    def test_database_digest_claim_is_not_treated_as_file_truth(self):
        with sqlite3.connect(self.database) as connection:
            connection.execute(
                "UPDATE extents SET sha256 = ? WHERE extent_id = ?",
                ("f" * 64, "v-0"),
            )

        committed = self._committed()
        verified = self._verified(committed)
        fact = next(
            item for item in verified["files"] if item["extentId"] == "v-0"
        )
        self.assertEqual(
            hashlib.sha256(self.media_bytes).hexdigest(),
            fact["sha256"],
        )
        self.assertNotEqual("f" * 64, fact["sha256"])

        oracle = build_canonical_oracle_snapshot(
            committed,
            verified,
            {"video": "v1"},
            0,
        )
        self.assertEqual([], oracle["playableIntervals"])

    def test_wrong_representation_does_not_contribute(self):
        with sqlite3.connect(self.database) as connection:
            connection.execute(
                """
                UPDATE extents
                SET representation_id = ?
                WHERE extent_id IN (?, ?)
                """,
                ("v2", "v-init", "v-0"),
            )

        committed = self._committed()
        oracle = build_canonical_oracle_snapshot(
            committed,
            self._verified(committed),
            {"video": "v1"},
            0,
        )
        self.assertEqual([], oracle["playableIntervals"])

    def test_missing_dependency_does_not_contribute(self):
        with sqlite3.connect(self.database) as connection:
            connection.execute(
                """
                UPDATE extent_dependencies
                SET dependency_extent_id = ?
                WHERE extent_id = ?
                """,
                ("never-published", "v-0"),
            )

        committed = self._committed()
        oracle = build_canonical_oracle_snapshot(
            committed,
            self._verified(committed),
            {"video": "v1"},
            0,
        )
        self.assertEqual([], oracle["playableIntervals"])

    def test_storage_path_escape_is_rejected(self):
        with sqlite3.connect(self.database) as connection:
            connection.execute(
                "UPDATE extents SET storage_path = ? WHERE extent_id = ?",
                ("../outside.extent", "v-0"),
            )

        with self.assertRaisesRegex(ValueError, "unsafe storagePath"):
            self._verified(self._committed())

    def test_unsupported_database_schema_version_fails_closed(self):
        with sqlite3.connect(self.database) as connection:
            connection.execute("PRAGMA user_version = 2")

        with self.assertRaisesRegex(
            ValueError,
            "unsupported ExtentStore database schema version",
        ):
            self._committed()

    def test_safe_but_noncanonical_storage_path_is_rejected(self):
        noncanonical = "extents/aa/not-the-layout.extent"
        self._write_extent(noncanonical, self.media_bytes)
        with sqlite3.connect(self.database) as connection:
            connection.execute(
                "UPDATE extents SET storage_path = ? WHERE extent_id = ?",
                (noncanonical, "v-0"),
            )

        with self.assertRaisesRegex(
            ValueError,
            "storagePath does not match canonical layout",
        ):
            self._verified(self._committed())

    def test_committed_and_verified_session_mismatch_is_rejected(self):
        committed = self._committed()
        verified = self._verified(committed)
        verified["sessionId"] = "different-session"

        with self.assertRaisesRegex(ValueError, "sessionId mismatch"):
            build_canonical_oracle_snapshot(
                committed,
                verified,
                {"video": "v1"},
                0,
            )

    def test_missing_verified_extent_fact_is_rejected(self):
        committed = self._committed()
        verified = self._verified(committed)
        verified["files"] = [
            item
            for item in verified["files"]
            if item["extentId"] != "v-0"
        ]

        with self.assertRaisesRegex(ValueError, "extent-id set mismatch"):
            build_canonical_oracle_snapshot(
                committed,
                verified,
                {"video": "v1"},
                0,
            )

    def test_extra_verified_extent_fact_is_rejected(self):
        committed = self._committed()
        verified = self._verified(committed)
        verified["files"].append(
            {
                "extentId": "not-committed",
                "exists": False,
                "length": None,
                "sha256": None,
                "storagePath": self._canonical_storage_path("not-committed"),
            }
        )

        with self.assertRaisesRegex(ValueError, "extent-id set mismatch"):
            build_canonical_oracle_snapshot(
                committed,
                verified,
                {"video": "v1"},
                0,
            )

    def test_exact_comparator_rejects_inflated_runtime_coverage(self):
        oracle = self._oracle()
        runtime = copy.deepcopy(oracle)
        runtime["eventSequence"] = 42
        runtime["eventElapsedRealtimeNs"] = 99
        runtime["playerBufferedAheadUs"] = 123
        self.assertEqual([], compare_coverage_semantics(runtime, oracle))

        runtime["playableIntervals"][0]["endUs"] = 11_000_000
        mismatches = compare_coverage_semantics(runtime, oracle)
        self.assertTrue(
            any("playableIntervals mismatch" in item for item in mismatches)
        )

    def test_exact_comparator_rejects_interval_crossing_hole(self):
        oracle = self._oracle()
        runtime = copy.deepcopy(oracle)
        runtime["perTrackPublishedIntervals"]["video"] = [
            {"startUs": 0, "endUs": 4_000_000},
            {"startUs": 6_000_000, "endUs": 10_000_000},
        ]
        runtime["playableIntervals"] = [
            {"startUs": 0, "endUs": 10_000_000}
        ]
        runtime["durablePlayableEndUs"] = 10_000_000
        runtime["durableReserveUs"] = 10_000_000

        mismatches = compare_coverage_semantics(runtime, oracle)
        self.assertTrue(mismatches)

    def test_cli_verify_run_writes_all_artifacts_and_compares(self):
        oracle = self._oracle()
        runtime_path = self.root / "runtime.json"
        runtime_path.write_text(
            json.dumps(oracle),
            encoding="utf-8",
        )
        committed_output = self.root / "out" / "committed.json"
        verified_output = self.root / "out" / "files.json"
        oracle_output = self.root / "out" / "oracle.json"

        status = main(
            [
                "verify-run",
                "--database",
                str(self.database),
                "--storage-root",
                str(self.storage_root),
                "--runtime",
                str(runtime_path),
                "--snapshot-id",
                "run-1",
                "--session-id",
                "session-1",
                "--snapshot-kind",
                "POST_RECOVERY",
                "--playhead-us",
                "5000000",
                "--required",
                "video=v1",
                "--committed-output",
                str(committed_output),
                "--verified-output",
                str(verified_output),
                "--oracle-output",
                str(oracle_output),
            ]
        )

        self.assertEqual(0, status)
        self.assertTrue(committed_output.is_file())
        self.assertTrue(verified_output.is_file())
        self.assertTrue(oracle_output.is_file())


if __name__ == "__main__":
    unittest.main()
