"""C2-D0 offline transport/classification/invariant falsification tests."""
import copy
import json
import pathlib
import sys
import tempfile
import types
import unittest

SCRIPT_DIR = pathlib.Path(__file__).resolve().parents[1]
REPO_ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from m3_batch import M3BatchError
from test_m3_batch import inputs
from m3_c2_contracts import C2ContractError
from m3_c2_d0 import (
    D0Error, PAIRS, build_plan, run_d0, typed_network_category,
    validate_plan, observe_cell,
)
from m3_matrix import build_plan as build_matrix_plan, M3MatrixError, load_registry
from m3_controls import build_control_plan
from test_m3_batch import synthetic_registry


class TransportError(Exception):
    pass


class HTTPError(TransportError):
    def __init__(self, status):
        self.status = status
        super().__init__("provider ID abc999; Authorization: bearer SECRET")


class SSLError(TransportError):
    pass


NETWORK_TYPES = types.SimpleNamespace(
    HTTPError=HTTPError, SSLError=SSLError, TransportError=TransportError,
)


class FakeOracle:
    network_calls = 0
    configured_clients = []
    modes = []

    class YoutubeDL:
        def __init__(self, params):
            FakeOracle.configured_clients.append(params["extractor_args"]["youtube"]["player_client"][0])
            FakeOracle.modes.append({
                "js": params["js_runtimes"], "remote": params["remote_components"],
                "skip": params["skip_download"], "cache": params["cachedir"],
            })

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, tb):
            return False

        def urlopen(self, req):
            FakeOracle.network_calls += 1
            raise HTTPError(403)

        def extract_info(self, url, download=False):
            # The fake never makes real network requests and only raises a
            # synthetic exception containing a secret that must not persist.
            assert download is False
            return self.urlopen(object())

    networking_exceptions = NETWORK_TYPES


class D0Tests(unittest.TestCase):
    def setUp(self):
        self.registry = synthetic_registry()
        self.c1 = build_control_plan(self.registry, build_matrix_plan(self.registry))
        self.plan = build_plan(self.registry, self.c1)
        self.inputs = inputs()
        self.ids = {
            "V0": self.inputs.v0_video_id,
            "V1": self.inputs.v1_video_id,
            "V2": self.inputs.v2_video_id,
        }
        FakeOracle.network_calls = 0
        FakeOracle.configured_clients = []
        FakeOracle.modes = []

    def loader(self):
        oracle = self.registry["referenceOracle"]
        return FakeOracle, oracle["version"], oracle["revision"]

    def test_committed_plan_matches_real_repository_state(self):
        folder = REPO_ROOT / ".work/m3-a2"
        registry = load_registry(folder / "case-registry-v1.json")
        c1 = json.loads((folder / "control-plan-v1.json").read_text(encoding="utf-8"))
        plan = json.loads((folder / "c2-d0-plan-v1.json").read_text(encoding="utf-8"))
        validate_plan(plan, registry, c1)

    def test_typed_ytdlp_http_403_and_429(self):
        self.assertEqual("HTTP_403", typed_network_category(HTTPError(403), NETWORK_TYPES))
        self.assertEqual("HTTP_429", typed_network_category(HTTPError(429), NETWORK_TYPES))
        self.assertEqual("HTTP_OTHER", typed_network_category(HTTPError(500), NETWORK_TYPES))
        self.assertEqual("TLS", typed_network_category(SSLError("TOKEN"), NETWORK_TYPES))
        self.assertEqual("UNCLASSIFIED", typed_network_category(TransportError("TOKEN"), NETWORK_TYPES))

    def test_arbitrary_status_attribute_does_not_fake_http(self):
        error = RuntimeError("hidden credentials")
        error.status = 403
        self.assertEqual("UNCLASSIFIED", typed_network_category(error, NETWORK_TYPES))

    def test_four_observations_are_bounded_and_redacted(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            result = run_d0(
                registry=self.registry, c1=self.c1, plan=self.plan,
                ids=self.ids, git_commit=self.inputs.git_commit,
                runner_image=self.inputs.runner_image,
                output=root, oracle_loader=self.loader,
            )
            self.assertEqual(4, FakeOracle.network_calls)
            self.assertEqual(4, result["totalMetadataRequests"])
            self.assertEqual(0, result["totalMediaProbes"])
            self.assertEqual("NOT_MADE_M3_A2", result["globalDecision"])
            self.assertEqual(PAIRS, tuple((x["caseClass"], x["lane"]) for x in result["observations"]))
            self.assertEqual(
                ["web_embedded", "web_embedded", "tv", "tv"],
                FakeOracle.configured_clients,
            )
            for mode in FakeOracle.modes:
                self.assertEqual({}, mode["js"])
                self.assertEqual(set(), mode["remote"])
                self.assertTrue(mode["skip"])
                self.assertFalse(mode["cache"])
            self.assertEqual(6, len(list(root.glob("*.json"))))
            for path in root.glob("*.json"):
                raw = path.read_text(encoding="utf-8")
                for forbidden in (*self.ids.values(), "Authorization", "bearer SECRET", "abc999", "https://www.youtube.com/watch?"):
                    self.assertNotIn(forbidden, raw)
            docs = [json.loads(path.read_text()) for path in root.glob("v*.json")]
            self.assertEqual(4, len(docs))
            for doc in docs:
                self.assertEqual([{"class": "HTTP_403", "ordinal": 1}], doc["networkFailures"])
                self.assertEqual("UNCLASSIFIED", doc["terminalClass"])

    def test_failure_class_api_rejects_untrusted_diagnosis(self):
        from m3_c2_contracts import DiagnosticCapture
        capture = DiagnosticCapture()
        capture.next_request()
        for category in ("JS_RUNTIME_UNAVAILABLE", "TOKEN_GATED", "HTTP_999", "Authorization: token"):
            with self.subTest(category=category), self.assertRaises(C2ContractError):
                capture.failed_request_class(1, category)
        capture.failed_request_class(1, "UNCLASSIFIED")
        with self.assertRaises(C2ContractError):
            capture.failed_request_class(1, "HTTP_403")

    def test_source_identity_rejected_before_network_calls(self):
        wrong = dict(self.ids)
        wrong["V1"] = "not-a-valid-id"
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises((M3MatrixError, M3BatchError)):
                run_d0(
                    registry=self.registry, c1=self.c1, plan=self.plan,
                    ids=wrong, git_commit=self.inputs.git_commit,
                    runner_image=self.inputs.runner_image,
                    output=pathlib.Path(tmp), oracle_loader=self.loader,
                )
        self.assertEqual(0, FakeOracle.network_calls)

    def test_frozen_plan_rejects_changed_lane_or_cap(self):
        for mutate in ("reverse", "budget", "runtime"):
            wrong = copy.deepcopy(self.plan)
            if mutate == "reverse":
                wrong["cells"].reverse()
            elif mutate == "budget":
                wrong["maxTotalMetadataRequests"] = 1_000_000
            else:
                wrong["mode"]["jsRuntime"] = "DENO"
            with self.subTest(mutate=mutate), self.assertRaises(D0Error):
                validate_plan(wrong, self.registry, self.c1)

    def test_bad_git_sha_or_runner_fails_before_network(self):
        cell = self.plan["cells"][0]
        for sha, runner in (("GARBAGE", self.inputs.runner_image), (self.inputs.git_commit, "bad/url")):
            with self.subTest(sha=sha, runner=runner), self.assertRaises(D0Error):
                observe_cell(
                    registry=self.registry, cell=cell, raw_id=self.ids["V0"],
                    git_commit=sha, runner_image=runner, oracle_loader=self.loader,
                )
        self.assertEqual(0, FakeOracle.network_calls)

    def test_wrong_oracle_revision_fails_before_network(self):
        def invalid_loader():
            oracle = self.registry["referenceOracle"]
            return FakeOracle, oracle["version"], "f" * 40
        cell = self.plan["cells"][0]
        with self.assertRaises(D0Error):
            observe_cell(
                registry=self.registry, cell=cell, raw_id=self.ids["V0"],
                git_commit=self.inputs.git_commit, runner_image=self.inputs.runner_image,
                oracle_loader=invalid_loader,
            )
        self.assertEqual(0, FakeOracle.network_calls)


if __name__ == "__main__":
    unittest.main()
