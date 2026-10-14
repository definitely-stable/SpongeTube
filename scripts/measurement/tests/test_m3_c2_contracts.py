"""Offline-only falsification of C2 metadata diagnostics."""
import copy
import json
import pathlib
import ssl
import socket
import sys
import unittest
import urllib.error

SCRIPT_DIR = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from m3_c2_contracts import (
    C2ContractError, DiagnosticCapture, MAX_METADATA_REQUESTS,
    classify_exception, validate_document,
)
from test_m3_batch import synthetic_registry

GIT_SHA = "a" * 40
RUNNER = "ubuntu24@20261004.327.1"


class C2ClassificationTest(unittest.TestCase):
    def test_typed_http_statuses(self):
        for status, outcome in ((403, "HTTP_403"), (429, "HTTP_429"), (503, "HTTP_OTHER")):
            with self.subTest(status=status):
                exc = urllib.error.HTTPError(
                    "https://example.invalid/private", status,
                    "sensitive bearer content", {}, None,
                )
                self.assertEqual(outcome, classify_exception(exc))

    def test_nested_transport_and_tls_categories(self):
        self.assertEqual("TIMEOUT", classify_exception(urllib.error.URLError(TimeoutError("private secret"))))
        self.assertEqual("CONNECT_OR_DNS", classify_exception(socket.gaierror("hidden hostname")))
        self.assertEqual("TLS", classify_exception(ssl.SSLError("token-containing-error")))

    def test_untrusted_message_cannot_claim_js_required(self):
        exc = RuntimeError("no supported javascript runtime for video SECRET_PROVIDER_ID")
        self.assertEqual("UNCLASSIFIED", classify_exception(exc))

    def test_spoofed_status_attributes_cannot_claim_http_response(self):
        for attribute, status in (("status", 403), ("code", 429)):
            with self.subTest(attribute=attribute):
                exc = RuntimeError("private data from an arbitrary layer")
                setattr(exc, attribute, status)
                self.assertEqual("UNCLASSIFIED", classify_exception(exc))

    def test_only_actual_http_exception_can_supply_http_status(self):
        cause = urllib.error.HTTPError(
            "https://example.invalid/private", 403, "confidential", {}, None,
        )
        outer = RuntimeError("do not inspect or retain this text")
        outer.__cause__ = cause
        self.assertEqual("HTTP_403", classify_exception(outer))
        self.assertEqual(
            "UNCLASSIFIED",
            classify_exception(urllib.error.HTTPError(
                "https://example.invalid/private", 302, "redirect", {}, None,
            )),
        )

    def test_nested_error_does_not_retain_exception_string(self):
        try:
            raise TimeoutError("signed URL https://example.invalid/private")
        except TimeoutError as cause:
            try:
                raise RuntimeError("Authorization: bearer A_SECRET") from cause
            except RuntimeError as outer:
                self.assertEqual("TIMEOUT", classify_exception(outer))


class C2DocumentTest(unittest.TestCase):
    def setUp(self):
        self.registry = synthetic_registry()
        self.capture = DiagnosticCapture()
        self.capture.next_request()
        self.capture.next_request()
        self.capture.failed_request(
            2, urllib.error.HTTPError("https://example.invalid/private", 403, "sensitive", {}, None)
        )
        self.doc = self.capture.finish(
            registry=self.registry,
            case_class="V0",
            lane="WEB_EMBEDDED",
            git_commit=GIT_SHA,
            runner_image=RUNNER,
            extraction_completed=False,
            terminal_error=RuntimeError("sensitive visitorData, YouTube video ID"),
        )

    def test_artifact_contains_only_typed_safe_metadata(self):
        validate_document(self.doc, self.registry)
        self.assertEqual("UNCLASSIFIED", self.doc["terminalClass"])
        self.assertEqual([{"ordinal": 2, "class": "HTTP_403"}], self.doc["networkFailures"])
        self.assertEqual(2, self.doc["metadataRequestsUsed"])
        rendered = json.dumps(self.doc, sort_keys=True)
        for secret in ("visitorData", "sensitive", "example.invalid", "bearer", "YouTube video ID"):
            self.assertNotIn(secret, rendered)
        self.assertEqual("DIAGNOSTIC_ONLY_NO_PROVIDER_INFERENCE", self.doc["decision"])

    def test_rejects_raw_exception_field_and_unsigned_context(self):
        bad = copy.deepcopy(self.doc)
        bad["exception"] = "video ID and signed URL"
        with self.assertRaises(C2ContractError):
            validate_document(bad, self.registry)
        bad = copy.deepcopy(self.doc)
        bad["networkFailures"][0]["message"] = "cookie"
        with self.assertRaises(C2ContractError):
            validate_document(bad, self.registry)

    def test_rejects_unapproved_runtime_and_token_provider(self):
        for key, value in (
            ("jsRuntime", "DENO"),
            ("remoteComponents", "AUTO"),
            ("poTokenProvider", "ENABLED"),
            ("credentialsUsed", True),
        ):
            bad = copy.deepcopy(self.doc)
            bad["mode"][key] = value
            with self.subTest(key=key), self.assertRaises(C2ContractError):
                validate_document(bad, self.registry)

    def test_rejects_forged_js_requirement_and_provider_decision(self):
        bad = copy.deepcopy(self.doc)
        bad["terminalClass"] = "JS_RUNTIME_UNAVAILABLE"
        with self.assertRaises(C2ContractError):
            validate_document(bad, self.registry)
        bad = copy.deepcopy(self.doc)
        bad["decision"] = "TOKEN_GATED"
        with self.assertRaises(C2ContractError):
            validate_document(bad, self.registry)

    def test_rejects_case_profile_and_producer_drift(self):
        changes = (
            ("case", "caseRevision", "bad-revision"),
            ("case", "providerContentIdSha256", "0" * 64),
            ("client", "profileId", "yt-dlp:web"),
            ("referenceOracle", "version", "unknown"),
        )
        for scope, field, value in changes:
            bad = copy.deepcopy(self.doc)
            bad[scope][field] = value
            with self.subTest(field=field), self.assertRaises(C2ContractError):
                validate_document(bad, self.registry)

    def test_metadata_budget_prevents_33rd_request(self):
        capture = DiagnosticCapture()
        for i in range(MAX_METADATA_REQUESTS):
            self.assertEqual(i + 1, capture.next_request())
        with self.assertRaises(C2ContractError):
            capture.next_request()

    def test_rejects_duplicate_ordinals_and_invalid_counts(self):
        with self.assertRaises(C2ContractError):
            self.capture.failed_request(2, RuntimeError("duplicate"))
        bad = copy.deepcopy(self.doc)
        bad["metadataRequestsUsed"] = True
        with self.assertRaises(C2ContractError):
            validate_document(bad, self.registry)
        bad = copy.deepcopy(self.doc)
        bad["networkFailures"][0]["class"] = ["HTTP_403"]
        with self.assertRaises(C2ContractError):
            validate_document(bad, self.registry)

    def test_no_terminal_success_and_failure_simultaneously(self):
        with self.assertRaises(C2ContractError):
            self.capture.finish(
                registry=self.registry,
                case_class="V0", lane="TV",
                git_commit=GIT_SHA, runner_image=RUNNER,
                extraction_completed=True, terminal_error=RuntimeError("should fail"),
            )
        good = DiagnosticCapture().finish(
            registry=self.registry, case_class="V2", lane="TV",
            git_commit=GIT_SHA, runner_image=RUNNER,
            extraction_completed=True,
        )
        self.assertEqual("NONE", good["terminalClass"])
        self.assertEqual(0, good["metadataRequestsUsed"])


if __name__ == "__main__":
    unittest.main()
