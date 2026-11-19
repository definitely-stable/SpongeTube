"""Falsification tests for M4-A1c independent debug UI proof."""

import copy
import json
import pathlib
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "measurement"))

from m4_ui_verifier import SESSION, verify, verify_ui_summary
from m4_reserve_verifier import expected_f1_units


class M4UiVerifierTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = ROOT / "test-fixtures/media"
        cls.manifest = json.loads((data / "manifest.json").read_text())
        cls.mpd = (data / "F1/manifest.mpd").read_bytes()
        cls.units, cls.end = expected_f1_units(cls.mpd, cls.manifest)

    def summary(self):
        return {
            "schemaVersion": 1,
            "sessionId": SESSION,
            "source": "CoverageIndex",
            "targetReserveUs": 8_000_000,
            "durableReserveUs": self.end,
            "firstPassFetchKeys": 4,
            "secondPassFetchKeys": 0,
            "afterDisableUnmeasured": True,
            "reopenWithoutNetwork": True,
            "baselineSeparate": True,
        }

    def origins(self):
        return [
            {
                "plane": "data",
                "sessionId": SESSION,
                "profileId": "N0",
                "requestId": i + 1,
                "path": "/fixtures/" + unit.path,
                "status": 206,
                "outcome": "SUCCESS",
                "resolvedRangeStart": 0,
                "resolvedRangeEndExclusive": unit.length,
                "bodyBytesWritten": unit.length,
            }
            for i, unit in enumerate(self.units)
        ]

    def storage(self):
        committed = []
        files = []
        for u in self.units:
            kind = u.track_id.split("-")[0]
            rep_id = u.representation_id[-1]
            deps = [] if u.start_us is None else [f"f1:{kind}:{rep_id}:init"]
            storage_path = f"extents/{u.extent_id}"
            committed.append({
                "extentId": u.extent_id,
                "mediaAssetId": "fixture:F1",
                "state": "PUBLISHED",
                "integrityState": "VALID",
                "trackId": u.track_id,
                "representationId": u.representation_id,
                "mediaStartUs": u.start_us,
                "mediaEndUs": u.end_us,
                "dependencyExtentIds": deps,
                "length": u.length,
                "sha256": u.sha256,
                "storagePath": storage_path,
            })
            files.append({
                "extentId": u.extent_id,
                "exists": True,
                "length": u.length,
                "sha256": u.sha256,
                "storagePath": storage_path,
            })
        return (
            {"extents": committed},
            {"files": files},
            {"durableReserveUs": self.end, "durablePlayableEndUs": self.end},
        )

    def checked(self, summary=None, origins=None, storage=None):
        committed, files, oracle = (
            copy.deepcopy(storage) if storage is not None else self.storage()
        )
        return verify(
            summary=self.summary() if summary is None else summary,
            trace=self.origins() if origins is None else origins,
            committed=committed,
            files=files,
            oracle=oracle,
            manifest=self.manifest,
            mpd=self.mpd,
        )

    def test_accepts_consistent_ui_origin_sqlite_and_bytes(self):
        result = self.checked()
        self.assertEqual("PASS", result["status"])
        self.assertEqual(9_941_333, result["independentDurableReserveUs"])
        self.assertEqual(4, result["originRequestCount"])

    def test_rejects_fabricated_ui_reserve(self):
        summary = self.summary()
        summary["durableReserveUs"] += 1
        with self.assertRaisesRegex(ValueError, "durableReserveUs"):
            verify_ui_summary(summary, self.end)

    def test_rejects_hidden_automatic_download(self):
        trace = self.origins()
        trace.append(dict(trace[0], requestId=99))
        with self.assertRaisesRegex(ValueError, "request sequence"):
            self.checked(origins=trace)

    def test_rejects_ui_source_derived_from_media3(self):
        summary = self.summary()
        summary["source"] = "Player.bufferedPosition"
        with self.assertRaisesRegex(ValueError, "source"):
            self.checked(summary=summary)

    def test_rejects_fake_second_pass_fetch(self):
        summary = self.summary()
        summary["secondPassFetchKeys"] = 1
        with self.assertRaisesRegex(ValueError, "secondPassFetchKeys"):
            self.checked(summary=summary)

    def test_rejects_missing_unknown_state_after_disable(self):
        summary = self.summary()
        summary["afterDisableUnmeasured"] = False
        with self.assertRaisesRegex(ValueError, "afterDisableUnmeasured"):
            self.checked(summary=summary)

    def test_rejects_claimed_persistence_without_reopen(self):
        summary = self.summary()
        summary["reopenWithoutNetwork"] = False
        with self.assertRaisesRegex(ValueError, "reopenWithoutNetwork"):
            self.checked(summary=summary)

    def test_rejects_baseline_overlap(self):
        summary = self.summary()
        summary["baselineSeparate"] = False
        with self.assertRaisesRegex(ValueError, "baselineSeparate"):
            self.checked(summary=summary)

    def test_rejects_missing_published_required_audio(self):
        committed, files, oracle = self.storage()
        committed["extents"].pop(0)
        with self.assertRaisesRegex(ValueError, "committed extent identities"):
            self.checked(storage=(committed, files, oracle))

    def test_rejects_forged_sha_of_published_byte_file(self):
        committed, files, oracle = self.storage()
        files["files"][2]["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "real bytes"):
            self.checked(storage=(committed, files, oracle))

    def test_rejects_oracle_overestimating_audio_video_intersection(self):
        committed, files, oracle = self.storage()
        oracle["durableReserveUs"] = 10_000_000
        with self.assertRaisesRegex(ValueError, "independent playable reserve"):
            self.checked(storage=(committed, files, oracle))

    def test_rejects_wrong_media_lab_session(self):
        trace = self.origins()
        trace[0]["sessionId"] = "unrelated"
        with self.assertRaisesRegex(ValueError, "session/profile"):
            self.checked(origins=trace)


if __name__ == "__main__":
    unittest.main()
