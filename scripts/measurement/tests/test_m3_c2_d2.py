"""C2-D2 deterministic privacy and provenance falsification; no live provider I/O."""
import copy
import json
import pathlib
import sys
import tempfile
import types
import unittest
from email.message import Message

HERE = pathlib.Path(__file__).resolve().parents[1]
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))

from test_m3_batch import inputs, synthetic_registry
from test_m3_c2_d1 import (
    FakeOracle, Response, HTTPError, ExtractorError, DownloadError,
)
from m3_controls import build_control_plan
from m3_matrix import build_plan as build_matrix_plan, load_registry
from m3_c2_d0 import build_plan as build_d0_plan
from m3_c2_d1 import build_plan as build_d1_plan
from m3_c2_d2 import (
    ResponseCaptureD2, build_plan, nested_cause_class, observe_cell,
    run_d2, safe_mime_family, validate_document_d2, validate_plan, verify_bundle,
)


def headers(value="text/html; charset=UTF-8"):
    msg = Message()
    if value is not None:
        msg["Content-Type"] = value
    msg["Set-Cookie"] = "SECRET_SESSION"
    return msg


class D2Oracle(FakeOracle):
    behavior = "WRAPPED"

    class YoutubeDL(FakeOracle.YoutubeDL):
        def urlopen(self, req):
            FakeOracle.network_calls += 1
            return Response(status=200, headers=headers())

        def extract_info(self, url, download=False):
            assert download is False
            self.urlopen(object())
            if D2Oracle.behavior == "SUCCESS":
                return {"title": "not recorded"}
            if D2Oracle.behavior == "NON_MAPPING":
                return None
            if D2Oracle.behavior == "WRAPPED":
                raise DownloadError("PRIVATE_SIGNED_URL") from ExtractorError("SECRET_CAUSE")
            raise RuntimeError("PRIVATE_SESSION")


class C2D2Tests(unittest.TestCase):
    def setUp(self):
        self.registry = synthetic_registry()
        self.c1 = build_control_plan(self.registry, build_matrix_plan(self.registry))
        self.d0 = build_d0_plan(self.registry, self.c1)
        self.d1 = build_d1_plan(self.registry, self.c1, self.d0)
        self.plan = build_plan(self.registry, self.c1, self.d0, self.d1)
        info = inputs()
        self.ids = {"V0": info.v0_video_id, "V1": info.v1_video_id, "V2": info.v2_video_id}
        self.sha, self.runner = info.git_commit, info.runner_image
        FakeOracle.network_calls = 0
        FakeOracle.configured_clients = []
        FakeOracle.configured_modes = []
        D2Oracle.behavior = "WRAPPED"

    def loader(self):
        ref = self.registry["referenceOracle"]
        return D2Oracle, ref["version"], ref["revision"]

    def observe(self):
        return observe_cell(
            registry=self.registry, cell=self.plan["cells"][0],
            raw_id=self.ids["V0"], git_commit=self.sha, runner_image=self.runner,
            oracle_loader=self.loader,
        )

    def test_frozen_committed_plan_and_budget(self):
        directory = ROOT / ".work" / "m3-a2"
        registry = load_registry(directory / "case-registry-v1.json")
        c1 = json.loads((directory / "control-plan-v1.json").read_text())
        d0 = json.loads((directory / "c2-d0-plan-v1.json").read_text())
        d1 = json.loads((directory / "c2-d1-plan-v1.json").read_text())
        d2 = json.loads((directory / "c2-d2-plan-v1.json").read_text())
        validate_plan(d2, registry, c1, d0, d1)
        self.assertEqual(4, len(d2["cells"]))
        self.assertEqual(32, d2["metadataRequestLimitPerCell"])
        self.assertEqual(128, d2["maxTotalMetadataRequests"])
        self.assertEqual("DISABLED", d2["mode"]["jsRuntime"])
        self.assertEqual("NOT_MADE_M3_A2", d2["globalDecision"])

    def test_no_hidden_plan_extensions_or_runtime_change(self):
        for mutation in ("js", "fifth", "budget", "oracle", "profile", "d1"):
            altered = copy.deepcopy(self.plan)
            if mutation == "js":
                altered["mode"]["jsRuntime"] = "DENO"
            elif mutation == "fifth":
                altered["cells"].append(altered["cells"][0])
            elif mutation == "budget":
                altered["maxTotalMetadataRequests"] = 129
            elif mutation == "oracle":
                altered["referenceOracle"]["version"] = "latest"
            elif mutation == "profile":
                altered["cells"][0]["profileId"] = "yt-dlp:other"
            else:
                altered["d1PlanId"] = "forged"
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                validate_plan(altered, self.registry, self.c1, self.d0, self.d1)

    def test_message_mime_safe_families(self):
        for header, expected in [
            ("application/json; charset=UTF-8", "JSON"),
            ("text/html; charset=UTF-8", "HTML"),
            ("video/mp4", "MEDIA"),
            ("text/plain", "TEXT_OTHER"),
            ("application/x-special", "OTHER"),
            ("", "UNKNOWN"),
            (None, "UNKNOWN"),
        ]:
            with self.subTest(header=header):
                self.assertEqual(expected, safe_mime_family(Response(headers=headers(header)), Response))

    def test_duplicate_header_and_invalid_header_type_unknown(self):
        duplicated = headers()
        duplicated["Content-Type"] = "application/json"
        self.assertEqual("UNKNOWN", safe_mime_family(Response(headers=duplicated), Response))
        self.assertEqual("UNKNOWN", safe_mime_family(Response(headers={"Content-Type": "text/html"}), Response))
        forged = types.SimpleNamespace(status=200, headers=headers())
        self.assertEqual("UNKNOWN", safe_mime_family(forged, Response))
        self.assertEqual("UNKNOWN", safe_mime_family(Response(headers=headers()), str))

    def test_header_accessor_exception_fails_unknown(self):
        class BrokenMessage(Message):
            def get_all(self, name, failobj=None):
                raise RuntimeError("SECRET signed URL")
        self.assertEqual("UNKNOWN", safe_mime_family(Response(headers=BrokenMessage()), Response))

    def test_trusted_exception_cause_and_exc_info(self):
        typeset = FakeOracle.networking_exceptions
        utils = FakeOracle.utils
        a = ExtractorError("PRIVATE")
        wrapped = DownloadError("SENSITIVE")
        wrapped.exc_info = (ExtractorError, a, None)
        self.assertEqual("YTDLP_EXTRACTOR_ERROR", nested_cause_class(wrapped, utils, typeset))
        http = HTTPError(403)
        another = DownloadError("secret")
        another.exc_info = (HTTPError, http, None)
        self.assertEqual("HTTP_403", nested_cause_class(another, utils, typeset))
        self.assertEqual("NONE", nested_cause_class(DownloadError("secret"), utils, typeset))
        self.assertEqual("NONE", nested_cause_class(None, utils, typeset))

    def test_untrusted_inner_errors_cannot_forge_http_or_js(self):
        suspicious = RuntimeError("JS_RUNTIME_REQUIRED token")
        suspicious.status = 403
        wrapped = DownloadError("secret")
        wrapped.__cause__ = suspicious
        self.assertEqual("UNKNOWN", nested_cause_class(wrapped, FakeOracle.utils, FakeOracle.networking_exceptions))
        self.assertEqual("UNKNOWN", nested_cause_class(suspicious, FakeOracle.utils, FakeOracle.networking_exceptions))

    def test_cyclic_nested_exception_is_bounded(self):
        a, b = DownloadError("A"), RuntimeError("B")
        a.__cause__ = b
        b.__cause__ = a
        self.assertEqual("UNKNOWN", nested_cause_class(a, FakeOracle.utils, FakeOracle.networking_exceptions))

    def test_capture_only_typed_response(self):
        capture = ResponseCaptureD2()
        number = capture.next_request()
        capture.returned(number, Response(headers=headers("application/json")), Response)
        self.assertEqual("JSON", capture.response_events[0]["contentTypeFamily"])
        with self.assertRaises(ValueError):
            capture.returned(number, Response(headers=headers()), Response)
        with self.assertRaises(ValueError):
            capture.next_request() if capture.request_attempts == 32 else capture.returned(33, Response(), Response)
        capture2 = ResponseCaptureD2()
        capture2.next_request()
        capture2.returned(1, types.SimpleNamespace(status=200, headers=headers()), Response)
        self.assertEqual("UNKNOWN", capture2.response_events[0]["httpClass"])
        self.assertEqual("UNKNOWN", capture2.response_events[0]["contentTypeFamily"])

    def test_integrated_fake_observation_preserves_no_js_contract(self):
        doc = self.observe()
        self.assertEqual("HTML", doc["responseEvents"][0]["contentTypeFamily"])
        self.assertEqual("HTTP_2XX", doc["responseEvents"][0]["httpClass"])
        self.assertEqual("YTDLP_DOWNLOAD_ERROR", doc["extractorErrorType"])
        self.assertEqual("YTDLP_EXTRACTOR_ERROR", doc["nestedCauseClass"])
        self.assertEqual("UNCLASSIFIED", doc["terminalClass"])
        self.assertEqual("DIAGNOSTIC_ONLY_NO_PROVIDER_INFERENCE", doc["decision"])
        validate_document_d2(doc, self.registry)
        self.assertEqual({}, FakeOracle.configured_modes[0]["js"])
        self.assertEqual(set(), FakeOracle.configured_modes[0]["remote"])
        self.assertTrue(FakeOracle.configured_modes[0]["skip"])

    def test_success_and_unknown_terminal_paths(self):
        D2Oracle.behavior = "SUCCESS"
        successful = self.observe()
        self.assertTrue(successful["extractionCompleted"])
        self.assertEqual("NONE", successful["nestedCauseClass"])
        D2Oracle.behavior = "UNKNOWN"
        unknown = self.observe()
        self.assertEqual("NONE", unknown["nestedCauseClass"])
        self.assertEqual("OTHER_EXCEPTION", unknown["extractorErrorType"])

    def test_invalid_identity_rejected_before_any_io(self):
        with tempfile.TemporaryDirectory() as td:
            corrupted = dict(self.ids)
            corrupted["V2"] = "invalid-ID"
            with self.assertRaises(ValueError):
                run_d2(registry=self.registry, c1=self.c1, d0=self.d0, d1_plan=self.d1,
                       plan=self.plan, ids=corrupted, git_commit=self.sha,
                       runner_image=self.runner, output=pathlib.Path(td), oracle_loader=self.loader)
        self.assertEqual(0, FakeOracle.network_calls)

    def test_bundle_verification_and_redaction(self):
        with tempfile.TemporaryDirectory() as td:
            root = pathlib.Path(td)
            summary = run_d2(registry=self.registry, c1=self.c1, d0=self.d0, d1_plan=self.d1,
                             plan=self.plan, ids=self.ids, git_commit=self.sha,
                             runner_image=self.runner, output=root, oracle_loader=self.loader)
            self.assertEqual(4, summary["controlCellsObserved"])
            self.assertEqual(4, summary["totalMetadataRequests"])
            self.assertEqual(0, summary["totalMediaProbes"])
            self.assertEqual("NOT_MADE_M3_A2", summary["globalDecision"])
            self.assertEqual(summary, verify_bundle(registry=self.registry, plan=self.plan,
                                                     ids=self.ids, root=root))
            for path in root.iterdir():
                content = path.read_text()
                for private in ("PRIVATE_SIGNED_URL", "SECRET_CAUSE", "SECRET_SESSION",
                                "www.youtube.com", *self.ids.values()):
                    self.assertNotIn(private, content)
            self.assertEqual(4, FakeOracle.network_calls)
            original = (root / "v0-tv.json").read_text()
            doc = json.loads(original)
            for key, value in [
                ("nestedCauseClass", "JS_RUNTIME_REQUIRED"),
                ("protocol", "m3-a2-c2-d1-response-stages-v1"),
                ("extractionCompleted", True),
            ]:
                changed = copy.deepcopy(doc)
                changed[key] = value
                with self.subTest(key=key), self.assertRaises(ValueError):
                    validate_document_d2(changed, self.registry)
            (root / "v0-tv.json").write_text(original + " ")
            with self.assertRaises(ValueError):
                verify_bundle(registry=self.registry, plan=self.plan, ids=self.ids, root=root)

    def test_runtime_failure_never_claims_provider_viability(self):
        D2Oracle.behavior = "WRAPPED"
        doc = self.observe()
        forged = dict(doc)
        forged["decision"] = "VIABLE_ANONYMOUS"
        with self.assertRaises(ValueError):
            validate_document_d2(forged, self.registry)


if __name__ == "__main__":
    unittest.main()
