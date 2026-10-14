"""C2-D1 offline falsification: status provenance, stage attribution, redaction."""
import copy
import json
import pathlib
import sys
import tempfile
import types
import unittest

HERE = pathlib.Path(__file__).resolve().parents[1]
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))

from test_m3_batch import inputs, synthetic_registry
from m3_controls import build_control_plan
from m3_matrix import build_plan as build_matrix_plan, load_registry
from m3_c2_d0 import build_plan as build_d0_plan
from m3_c2_d1 import (
    D1Error, PLAN_ID, ResponseCapture, build_plan, content_type_family,
    extractor_exception_type, http_class, observe_cell, run_d1,
    validate_document_d1, validate_plan, verify_bundle,
)


class HTTPError(Exception):
    def __init__(self, status):
        self.status = status
        super().__init__("SECRET SIGNED URL and bearer token")


class SSLError(Exception):
    pass


class TransportError(Exception):
    pass


class ExtractorError(Exception):
    pass


class DownloadError(Exception):
    pass


class Response:
    def __init__(self, status=200, headers=None):
        self.status = status
        self.headers = headers if headers is not None else {"Content-Type": "application/json; charset=utf-8"}


class FakeOracle:
    network_calls = 0
    configured_clients = []
    configured_modes = []
    behavior = "EXTRACTOR_ERROR"

    networking_exceptions = types.SimpleNamespace(
        HTTPError=HTTPError, SSLError=SSLError, TransportError=TransportError,
    )
    networking_response_type = Response
    utils = types.SimpleNamespace(ExtractorError=ExtractorError, DownloadError=DownloadError)

    class YoutubeDL:
        def __init__(self, params):
            FakeOracle.configured_clients.append(params["extractor_args"]["youtube"]["player_client"][0])
            FakeOracle.configured_modes.append({
                "js": params["js_runtimes"], "remote": params["remote_components"],
                "skip": params["skip_download"], "cache": params["cachedir"],
            })

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, tb):
            return False

        def urlopen(self, req):
            FakeOracle.network_calls += 1
            if FakeOracle.behavior == "HTTP_ERROR":
                raise HTTPError(403)
            if FakeOracle.behavior == "FAKE_RESPONSE":
                return types.SimpleNamespace(status=200, headers={"Content-Type": "application/json"})
            return Response(200, {"Content-Type": "text/html; charset=UTF-8", "Set-Cookie": "supersecret"})

        def extract_info(self, url, download=False):
            assert not download
            self.urlopen(object())
            if FakeOracle.behavior == "SUCCESS":
                return {"id": "IN MEMORY ONLY"}
            if FakeOracle.behavior == "NON_MAPPING":
                return None
            if FakeOracle.behavior == "HTTP_ERROR":
                raise DownloadError("raw HTTPS and sensitive token")
            raise ExtractorError("provider ID and signed URL SECRET")


class C2D1Tests(unittest.TestCase):
    def setUp(self):
        self.registry = synthetic_registry()
        self.c1 = build_control_plan(self.registry, build_matrix_plan(self.registry))
        self.d0 = build_d0_plan(self.registry, self.c1)
        self.plan = build_plan(self.registry, self.c1, self.d0)
        a = inputs()
        self.ids = {"V0": a.v0_video_id, "V1": a.v1_video_id, "V2": a.v2_video_id}
        self.sha, self.runner = a.git_commit, a.runner_image
        FakeOracle.behavior = "EXTRACTOR_ERROR"
        FakeOracle.network_calls = 0
        FakeOracle.configured_clients = []
        FakeOracle.configured_modes = []

    def loader(self):
        oracle = self.registry["referenceOracle"]
        return FakeOracle, oracle["version"], oracle["revision"]

    def test_committed_d1_plan_matches_frozen_d0(self):
        root = ROOT / ".work" / "m3-a2"
        registry = load_registry(root / "case-registry-v1.json")
        c1 = json.loads((root / "control-plan-v1.json").read_text())
        d0 = json.loads((root / "c2-d0-plan-v1.json").read_text())
        committed = json.loads((root / "c2-d1-plan-v1.json").read_text())
        validate_plan(committed, registry, c1, d0)
        self.assertEqual(4, len(committed["cells"]))
        self.assertEqual(128, committed["maxTotalMetadataRequests"])
        self.assertEqual("NOT_MADE_M3_A2", committed["globalDecision"])

    def test_closed_http_status_and_mime_families(self):
        self.assertEqual(["UNKNOWN", "HTTP_2XX", "HTTP_3XX", "HTTP_4XX", "HTTP_5XX"],
                         [http_class(v) for v in (True, 200, 302, 403, 503)])
        self.assertEqual("UNKNOWN", http_class("200"))
        self.assertEqual("UNKNOWN", http_class(999))
        self.assertEqual("JSON", content_type_family("application/json; charset=utf8"))
        self.assertEqual("HTML", content_type_family("text/html; TOKEN SECRET"))
        self.assertEqual("MEDIA", content_type_family("video/mp4"))
        self.assertEqual("OTHER", content_type_family("application/vnd.unknown"))
        self.assertEqual("UNKNOWN", content_type_family(None))

    def test_response_spoof_cannot_prove_http_200(self):
        capture = ResponseCapture()
        capture.next_request()
        forged = types.SimpleNamespace(status=200, headers={"Content-Type": "application/json"})
        capture.returned(1, forged, Response)
        self.assertEqual([{
            "ordinal": 1, "kind": "RETURNED",
            "httpClass": "UNKNOWN", "contentTypeFamily": "UNKNOWN",
        }], capture.response_events)

    def test_request_budget_and_unique_completion(self):
        capture = ResponseCapture()
        ordinal = capture.next_request()
        capture.returned(ordinal, Response(), Response)
        with self.assertRaises(D1Error):
            capture.returned(ordinal, Response(), Response)
        with self.assertRaises(D1Error):
            capture.failed(ordinal, HTTPError(403), FakeOracle.networking_exceptions)

    def test_extractor_error_requires_exact_exception_type(self):
        self.assertEqual("YTDLP_EXTRACTOR_ERROR",
                         extractor_exception_type(ExtractorError("secret"), FakeOracle.utils))
        self.assertEqual("YTDLP_DOWNLOAD_ERROR",
                         extractor_exception_type(DownloadError("secret"), FakeOracle.utils))
        self.assertEqual("OTHER_EXCEPTION",
                         extractor_exception_type(RuntimeError("ExtractorError: secret"), FakeOracle.utils))

    def test_actual_response_and_extractor_boundary_are_observed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            summary = run_d1(
                registry=self.registry, c1=self.c1, d0=self.d0, plan=self.plan,
                ids=self.ids, git_commit=self.sha, runner_image=self.runner,
                output=root, oracle_loader=self.loader,
            )
            self.assertEqual(4, summary["controlCellsObserved"])
            self.assertEqual(4, summary["totalMetadataRequests"])
            self.assertEqual(4, FakeOracle.network_calls)
            self.assertEqual(0, summary["totalMediaProbes"])
            self.assertEqual("NOT_MADE_M3_A2", summary["globalDecision"])
            for name in ("v0-web_embedded.json", "v2-web_embedded.json", "v0-tv.json", "v2-tv.json"):
                doc = json.loads((root / name).read_text())
                self.assertEqual([{
                    "ordinal": 1, "kind": "RETURNED", "httpClass": "HTTP_2XX",
                    "contentTypeFamily": "HTML",
                }], doc["responseEvents"])
                self.assertEqual("EXTRACT_INFO_RAISED", doc["extractorStage"])
                self.assertEqual("YTDLP_EXTRACTOR_ERROR", doc["extractorErrorType"])
                self.assertEqual("UNCLASSIFIED", doc["terminalClass"])
                validate_document_d1(doc, self.registry)
            independently = verify_bundle(registry=self.registry, plan=self.plan, ids=self.ids, root=root)
            self.assertEqual(summary, independently)
            for path in root.iterdir():
                text = path.read_text()
                for forbidden in ("SECRET", "Set-Cookie", "supersecret", "bearer",
                                  *self.ids.values(), "www.youtube.com", "signed URL"):
                    self.assertNotIn(forbidden, text)
            self.assertEqual(["web_embedded", "web_embedded", "tv", "tv"], FakeOracle.configured_clients)
            for mode in FakeOracle.configured_modes:
                self.assertEqual({}, mode["js"])
                self.assertEqual(set(), mode["remote"])
                self.assertTrue(mode["skip"])
                self.assertFalse(mode["cache"])

    def test_negative_and_non_mapping_response_paths(self):
        for behavior, kind, stage, extractor in (
            ("HTTP_ERROR", "EXCEPTION", "EXTRACT_INFO_RAISED", "YTDLP_DOWNLOAD_ERROR"),
            ("NON_MAPPING", "RETURNED", "NON_MAPPING_RETURNED", "NONE"),
            ("SUCCESS", "RETURNED", "MAPPING_RETURNED", "NONE"),
            ("FAKE_RESPONSE", "RETURNED", "EXTRACT_INFO_RAISED", "YTDLP_EXTRACTOR_ERROR"),
        ):
            with self.subTest(behavior=behavior):
                FakeOracle.behavior = behavior
                cell = self.plan["cells"][0]
                doc = observe_cell(
                    registry=self.registry, cell=cell, raw_id=self.ids["V0"],
                    git_commit=self.sha, runner_image=self.runner, oracle_loader=self.loader,
                )
                self.assertEqual(kind, doc["responseEvents"][0]["kind"])
                self.assertEqual(stage, doc["extractorStage"])
                self.assertEqual(extractor, doc["extractorErrorType"])
                if behavior == "HTTP_ERROR":
                    self.assertEqual("HTTP_403", doc["responseEvents"][0]["class"])
                if behavior == "FAKE_RESPONSE":
                    self.assertEqual("UNKNOWN", doc["responseEvents"][0]["httpClass"])

    def test_forged_or_dropped_typed_events_fail_closed(self):
        cell = self.plan["cells"][0]
        doc = observe_cell(
            registry=self.registry, cell=cell, raw_id=self.ids["V0"],
            git_commit=self.sha, runner_image=self.runner, oracle_loader=self.loader,
        )
        for mutation in ("addHeader", "inventStatus", "forgeStage", "dropEvent", "wrongOrdinal",
                         "badMime", "forgeJS", "falseComplete", "adoption"):
            fake = copy.deepcopy(doc)
            if mutation == "addHeader":
                fake["responseEvents"][0]["headers"] = {"Cookie": "SECRET"}
            elif mutation == "inventStatus":
                fake["responseEvents"][0]["httpClass"] = "HTTP_200"
            elif mutation == "forgeStage":
                fake["extractorErrorType"] = "JS_RUNTIME_REQUIRED"
            elif mutation == "dropEvent":
                fake["responseEvents"].clear()
            elif mutation == "wrongOrdinal":
                fake["responseEvents"][0]["ordinal"] = 5
            elif mutation == "badMime":
                fake["responseEvents"][0]["contentTypeFamily"] = "text/html; SECRET"
            elif mutation == "forgeJS":
                fake["mode"]["jsRuntime"] = "DENO"
            elif mutation == "falseComplete":
                fake["extractionCompleted"] = True
            elif mutation == "adoption":
                fake["decision"] = "VIABLE_ANONYMOUS"
            with self.subTest(mutation=mutation), self.assertRaises((D1Error, ValueError)):
                validate_document_d1(fake, self.registry)

    def test_provider_identity_fails_before_network(self):
        invalid = dict(self.ids)
        invalid["V2"] = "INVALIDRAWID"
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                run_d1(
                    registry=self.registry, c1=self.c1, d0=self.d0, plan=self.plan,
                    ids=invalid, git_commit=self.sha, runner_image=self.runner,
                    output=pathlib.Path(tmp), oracle_loader=self.loader,
                )
        self.assertEqual(0, FakeOracle.network_calls)

    def test_plan_cannot_expand_requests_or_use_runtime(self):
        for mutation in ("moreRequests", "extraCell", "enableJS"):
            fake = copy.deepcopy(self.plan)
            if mutation == "moreRequests":
                fake["maxTotalMetadataRequests"] = 129
            elif mutation == "extraCell":
                fake["cells"].append(dict(fake["cells"][0]))
            else:
                fake["mode"]["jsRuntime"] = "DENO"
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                validate_plan(fake, self.registry, self.c1, self.d0)


if __name__ == "__main__":
    unittest.main()
