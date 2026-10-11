"""M3-A1 bounded probe producer tests.

No live-provider access and no yt-dlp installation are required by Verify.
"""

import json
import pathlib
import sys
import types
import unittest
from unittest import mock

SCRIPT_DIR = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

import m3_probe
from m3_probe import (
    ExtractionResult,
    LANE_TO_YTDLP_CLIENT,
    MAX_MEDIA_PROBE_BYTES,
    MAX_METADATA_REQUESTS,
    MediaProbeResult,
    OracleSignals,
    _bounded_http_probe,
    _build_artifact,
    _extract_with_yt_dlp,
    _run_media_probes,
    _summary_from_extraction,
)


GIT_SHA = "a" * 40
YTDLP_REVISION = "b" * 40


def extraction(
    formats=None,
    *,
    completed=True,
    signals=None,
    requests=3,
    exhausted=False,
):
    return ExtractionResult(
        info={"formats": formats or []} if completed else None,
        metadata_requests_used=requests,
        metadata_budget_exhausted=exhausted,
        signals=signals or OracleSignals(),
        oracle_version=m3_probe.EXPECTED_YTDLP_VERSION,
        oracle_revision=YTDLP_REVISION,
        extraction_completed=completed,
    )


def direct_muxed(*, missing_pot=False, drm=False):
    return {
        "format_id": "18",
        "protocol": "https",
        "url": "https://media.invalid/signed?opaque=1",
        "vcodec": "avc1",
        "acodec": "mp4a",
        "has_drm": drm,
        "format_note": "MISSING POT" if missing_pot else "",
        "filesize": 4 * 1024 * 1024,
        "http_headers": {
            "User-Agent": "fixture-agent",
            "Cookie": "must-not-forward",
            "Authorization": "must-not-forward",
        },
    }


def direct_video():
    return {
        "format_id": "137",
        "protocol": "https",
        "url": "https://video.invalid/signed?opaque=1",
        "vcodec": "avc1",
        "acodec": "none",
        "has_drm": False,
        "format_note": "",
        "filesize": 8 * 1024 * 1024,
    }


def direct_audio():
    return {
        "format_id": "140",
        "protocol": "https",
        "url": "https://audio.invalid/signed?opaque=1",
        "vcodec": "none",
        "acodec": "mp4a",
        "has_drm": False,
        "format_note": "",
        "filesize": 2 * 1024 * 1024,
    }


def successful_media(purpose="STARTUP", probe_id="p0"):
    return MediaProbeResult(
        purpose=purpose,
        probe_id=probe_id,
        byte_limit=64 * 1024,
        bytes_read=64 * 1024,
        status=206,
        range_honored=True,
    )


class M3A1LaneContractTest(unittest.TestCase):
    def test_frozen_lane_mapping_is_explicit(self):
        self.assertEqual(
            {
                "WEB_EMBEDDED": "web_embedded",
                "TV": "tv",
                "ANDROID_VR": "android_vr",
                "MWEB": "mweb",
                "WEB": "web",
                "WEB_SAFARI": "web_safari",
                "ANDROID": "android",
                "IOS": "ios",
            },
            LANE_TO_YTDLP_CLIENT,
        )


class M3A1MetadataBudgetTest(unittest.TestCase):
    def test_reference_oracle_is_aborted_before_request_33(self):
        class FakeYoutubeDL:
            def __init__(self, params):
                self.params = params

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def urlopen(self, req):
                return object()

            def extract_info(self, url, download=False):
                for _ in range(MAX_METADATA_REQUESTS + 1):
                    self.urlopen(object())
                return {"formats": []}

        fake_module = types.SimpleNamespace(YoutubeDL=FakeYoutubeDL)
        with mock.patch.object(
            m3_probe,
            "_load_yt_dlp",
            return_value=(
                fake_module,
                m3_probe.EXPECTED_YTDLP_VERSION,
                YTDLP_REVISION,
            ),
        ):
            result = _extract_with_yt_dlp("public-id", "WEB_EMBEDDED")

        self.assertEqual(MAX_METADATA_REQUESTS, result.metadata_requests_used)
        self.assertTrue(result.metadata_budget_exhausted)
        self.assertFalse(result.extraction_completed)


class _FakeHeaders(dict):
    pass


class _FakeResponse:
    def __init__(self, *, status=206, payload=b"x" * (64 * 1024), headers=None):
        self.status = status
        self.payload = payload
        self.headers = _FakeHeaders(headers or {"Content-Range": "bytes 0-65535/999999"})
        self.read_sizes = []

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def read(self, size=-1):
        self.read_sizes.append(size)
        if size < 0:
            return self.payload
        return self.payload[:size]


class M3A1MediaBudgetTest(unittest.TestCase):
    def test_media_reader_never_requests_more_than_declared_limit(self):
        response = _FakeResponse(payload=b"x" * (MAX_MEDIA_PROBE_BYTES + 1024))
        fmt = direct_muxed()
        with mock.patch("urllib.request.urlopen", return_value=response):
            result = _bounded_http_probe(
                fmt,
                purpose="STARTUP",
                probe_id="p0",
                start=0,
                byte_limit=MAX_MEDIA_PROBE_BYTES,
            )

        self.assertEqual([MAX_MEDIA_PROBE_BYTES], response.read_sizes)
        self.assertEqual(MAX_MEDIA_PROBE_BYTES, result.bytes_read)
        self.assertTrue(result.range_honored)

    def test_secret_headers_are_not_forwarded_to_media_probe(self):
        captured = {}

        def fake_open(request, timeout):
            captured["headers"] = {k.lower(): v for k, v in request.header_items()}
            return _FakeResponse()

        with mock.patch("urllib.request.urlopen", side_effect=fake_open):
            _bounded_http_probe(
                direct_muxed(),
                purpose="STARTUP",
                probe_id="p0",
                start=0,
                byte_limit=64 * 1024,
            )

        self.assertNotIn("cookie", captured["headers"])
        self.assertNotIn("authorization", captured["headers"])
        self.assertIn("user-agent", captured["headers"])
        self.assertIn("range", captured["headers"])

    def test_range_set_is_small_and_each_read_is_bounded(self):
        formats = [direct_muxed(), direct_video(), direct_audio()]

        def fake_probe(fmt, *, purpose, probe_id, start, byte_limit, timeout_seconds=20.0):
            self.assertLessEqual(byte_limit, MAX_MEDIA_PROBE_BYTES)
            return successful_media(purpose, probe_id)

        with mock.patch.object(m3_probe, "_bounded_http_probe", side_effect=fake_probe):
            results = _run_media_probes(formats, mode="range-set")

        self.assertLessEqual(len(results), m3_probe.MAX_MEDIA_PROBES)
        self.assertEqual(
            ["STARTUP", "VIDEO", "AUDIO", "CONTINUATION", "SEEK"],
            [item.purpose for item in results],
        )


class M3A1ClassificationTest(unittest.TestCase):
    def test_successful_no_token_no_runtime_path_is_viable_anonymous(self):
        ext = extraction([direct_muxed(), direct_video(), direct_audio()])
        media = [
            successful_media("STARTUP", "p0"),
            successful_media("CONTINUATION", "p1"),
            successful_media("SEEK", "p2"),
        ]

        summary = _summary_from_extraction(ext, media)

        self.assertEqual("PRESENT", summary.capabilities["https"])
        self.assertEqual("NOT_REQUIRED", summary.requirements["gvsPoToken"])
        self.assertEqual("NOT_REQUIRED", summary.requirements["playerPoToken"])
        self.assertEqual("NOT_REQUIRED", summary.requirements["challengeRuntime"])
        self.assertEqual("PRESENT", summary.delivery["range"])
        self.assertEqual("PRESENT", summary.delivery["continuation"])
        self.assertEqual("PRESENT", summary.delivery["seek"])
        self.assertEqual("VIABLE_ANONYMOUS", summary.outcome["classification"])
        self.assertEqual("MULTIPLE", summary.delivery["mediaLayout"])

    def test_successful_selected_path_wins_over_other_missing_pot_formats(self):
        ext = extraction([direct_muxed(), {**direct_video(), "format_note": "MISSING POT"}])
        summary = _summary_from_extraction(ext, [successful_media()])

        self.assertEqual("NOT_REQUIRED", summary.requirements["gvsPoToken"])
        self.assertEqual("VIABLE_ANONYMOUS", summary.outcome["classification"])

    def test_non_startup_success_cannot_manufacture_viability(self):
        ext = extraction([direct_muxed(), direct_video()])
        video = successful_media("VIDEO", "p1")
        summary = _summary_from_extraction(ext, [video])

        self.assertEqual("UNKNOWN", summary.delivery["startup"])
        self.assertEqual("UNKNOWN", summary.requirements["gvsPoToken"])
        self.assertEqual("INCONCLUSIVE", summary.outcome["classification"])

    def test_sibling_rejection_does_not_erase_working_anonymous_path(self):
        ext = extraction([direct_muxed(), direct_video()])
        good = successful_media("STARTUP", "p0")
        rejected = MediaProbeResult(
            purpose="VIDEO",
            probe_id="p1",
            byte_limit=64 * 1024,
            status=403,
            rejection=True,
        )
        summary = _summary_from_extraction(ext, [good, rejected])

        self.assertEqual("PRESENT", summary.provider_signals["rejection"])
        self.assertEqual("VIABLE_ANONYMOUS", summary.outcome["classification"])

    def test_missing_pot_without_working_path_is_token_gated(self):
        ext = extraction([direct_muxed(missing_pot=True)])
        summary = _summary_from_extraction(ext, [])

        self.assertEqual("REQUIRED", summary.requirements["gvsPoToken"])
        self.assertEqual("TOKEN_GATED", summary.outcome["classification"])

    def test_sabr_signal_is_observation_not_automatic_sabr_only_claim(self):
        signals = OracleSignals(saw_sabr=True)
        summary = _summary_from_extraction(extraction([], signals=signals), [])

        self.assertEqual("PRESENT", summary.capabilities["sabr"])
        self.assertEqual("UNKNOWN", summary.capabilities["dash"])
        self.assertEqual("UNKNOWN", summary.capabilities["hls"])
        self.assertEqual("INCONCLUSIVE", summary.outcome["classification"])

    def test_no_runtime_failure_can_be_challenge_gated(self):
        signals = OracleSignals(saw_js_runtime_requirement=True)
        summary = _summary_from_extraction(
            extraction([], completed=False, signals=signals),
            [],
        )
        self.assertEqual("REQUIRED", summary.requirements["challengeRuntime"])
        self.assertEqual("CHALLENGE_GATED", summary.outcome["classification"])

    def test_429_media_observation_is_rate_limited(self):
        media = MediaProbeResult(
            purpose="STARTUP",
            probe_id="p0",
            byte_limit=64 * 1024,
            status=429,
            throttling=True,
            retry_after_present=True,
        )
        summary = _summary_from_extraction(extraction([direct_muxed()]), [media])
        self.assertEqual("PRESENT", summary.provider_signals["throttling"])
        self.assertEqual("PRESENT", summary.provider_signals["retryAfter"])
        self.assertEqual("PROVIDER_RATE_LIMITED", summary.outcome["classification"])


class M3A1ArtifactPrivacyTest(unittest.TestCase):
    def test_artifact_hashes_provider_id_and_never_retains_it_or_urls(self):
        raw_id = "public-provider-id"
        ext = extraction([direct_muxed()])
        summary = _summary_from_extraction(ext, [successful_media()])
        artifact = _build_artifact(
            video_id=raw_id,
            case_class="V0",
            case_revision="v0-r1",
            lane="WEB_EMBEDDED",
            git_commit=GIT_SHA,
            runner_image="ubuntu-24.04",
            extraction=ext,
            summary=summary,
        )

        encoded = json.dumps(artifact, sort_keys=True)
        self.assertNotIn(raw_id, encoded)
        self.assertNotIn("media.invalid", encoded)
        self.assertNotIn("https://", encoded)
        self.assertEqual(
            m3_probe._sha256_text(raw_id),
            artifact["videoCase"]["providerContentIdSha256"],
        )

    def test_artifact_records_actual_bounded_usage(self):
        ext = extraction([direct_muxed()], requests=7)
        summary = _summary_from_extraction(ext, [successful_media()])
        artifact = _build_artifact(
            video_id="id",
            case_class="V0",
            case_revision="r1",
            lane="WEB_EMBEDDED",
            git_commit=GIT_SHA,
            runner_image="ubuntu-24.04",
            extraction=ext,
            summary=summary,
        )
        self.assertEqual(7, artifact["budget"]["metadataRequestsUsed"])
        self.assertEqual(64 * 1024, artifact["budget"]["mediaProbes"][0]["bytesRead"])


if __name__ == "__main__":
    unittest.main()
