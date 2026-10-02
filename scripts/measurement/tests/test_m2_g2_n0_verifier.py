from __future__ import annotations

import copy
import importlib.util
import json
import pathlib
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
MEASUREMENT = ROOT / "scripts" / "measurement"

import sys
sys.path.insert(0, str(MEASUREMENT))

import m2_transport_pair_plan as pair_plan


def load_module():
    path = ROOT / "scripts" / "ci" / "verify-m2-g2-n0.py"
    spec = importlib.util.spec_from_file_location("verify_m2_g2_n0", path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


verifier = load_module()


def load(path: str) -> dict:
    return json.loads((ROOT / path).read_text(encoding="utf-8"))


SCENARIO = load("test-fixtures/network/m2/n0-control.json")
LAB_SCENARIO_HASH = "7" * 64
RUNTIME_DEVICE = {
    "deviceClass": "ANDROID_EMULATOR",
    "androidApi": 36,
    "abi": "x86_64",
    "batteryPolicy": "CI_POWERED",
    "acPowered": True,
}
INPUTS = {
    "work": load("test-fixtures/network/m2/g2/work-f1-audio-segment-1.json"),
    "deviceState": load("test-fixtures/network/m2/g2/device-api36-emulator.json"),
    "cacheState": load("test-fixtures/network/m2/g2/cache-empty-http-disabled.json"),
    "recoveryPolicy": load("test-fixtures/network/m2/g2/recovery-sponge-v2.json"),
    "routePolicy": load("test-fixtures/network/m2/g2/route-exact-default.json"),
}


def build_plan() -> dict:
    return pair_plan.build_plan(
        run_id="m2-g2-n0-api36",
        pair_id="m2-g2-n0-api36-cold",
        scenario=SCENARIO,
        inputs=INPUTS,
        ordering_seed=20261001,
        block_count=2,
        playback_mode="SPONGE",
        connection_state="COLD",
    )


def raw_case(plan: dict, schedule_row: dict, request_id: int) -> dict:
    backend = schedule_row["backendId"]
    return {
        "schemaVersion": 1,
        "phase": "M2-G2-B-N0",
        "runId": plan["runId"],
        "pairId": plan["pairId"],
        "trial": {
            **schedule_row,
            "backendVersion": (
                "android-http-url-connection"
                if backend == "HTTP_URL_CONNECTION_ROUTE_BOUND"
                else "133.0.6876.3"
            ),
            "implementationId": (
                "android-platform-url-connection"
                if backend == "HTTP_URL_CONNECTION_ROUTE_BOUND"
                else "android-platform-http-engine"
            ),
            "eligibility": "ELIGIBLE",
            "comparison": copy.deepcopy(plan["comparison"]),
            "route": {
                "exactNetworkBound": True,
                "permitRouteEpoch": 1,
            },
            "result": "SUCCESS",
            "requestCorrectness": {
                "range": "PASS",
                "contentRange": "PASS",
                "responseBounds": "PASS",
                "publishedBytes": "PASS",
            },
            "recovery": {
                "recoveryChainCount": 1,
                "ownerCount": 1,
                "originRequestCount": 1,
                "internalRetryVisibility": "OPAQUE",
                "internalRetryCount": None,
            },
            "metrics": {
                "firstByteUs": 10_000,
                "completionUs": 100_000,
                "cancellationLatencyUs": None,
                "cpuTimeUs": 20_000,
                "maxRssBytes": 50_000_000,
                "bytesRequested": verifier.RESOURCE_LENGTH,
                "bytesReceived": verifier.RESOURCE_LENGTH,
                "bytesPublished": verifier.RESOURCE_LENGTH,
            },
            "negotiatedProtocol": (
                "UNKNOWN"
                if backend == "HTTP_URL_CONNECTION_ROUTE_BOUND"
                else "HTTP_2"
            ),
            "performanceSampleEligible": False,
            "limitations": [
                "RAW_DEVICE_ROW_REQUIRES_HOST_RETRY_FINALIZATION",
                "API36_EMULATOR_DIRECTIONAL_ONLY",
                "MAX_RSS_IS_FRESH_PROCESS_HIGH_WATER",
                "FIRST_BYTE_IS_FIRST_ACCEPTED_16K_CHUNK",
                "CANCELLATION_NOT_EXERCISED_IN_N0",
            ],
        },
        "proof": {
            "originRequestId": request_id,
            "committedSha256": verifier.RESOURCE_SHA256,
            "committedBytes": verifier.RESOURCE_LENGTH,
            "processInstanceId": f"process-{request_id}",
            "processPid": 10_000 + request_id,
            "processStartClockTicks": 1_000_000 + request_id,
            "androidApi": 36,
            "primaryAbi": "x86_64",
            "extentStoreInitiallyEmpty": True,
            "transportSessionFresh": True,
            "routeEpochBefore": 1,
            "routeEpochAfter": 1,
            "bindingRevision": "binding-1",
            "bindingTargetResolved": True,
            "attemptStartedCount": 1,
            "attemptCompletedCount": 1,
            "attemptProgressCount": 3,
            "attemptCorrelationCount": 1,
            "remoteAttemptChargeCount": 1,
            "recoveryJitterProtocol": verifier.RECOVERY_JITTER_PROTOCOL,
            "recoveryJitterSeed": verifier.RECOVERY_JITTER_SEED,
            "recoveryJitterSampleCount": 0,
            "recoveryJitterSamples": [],
            "firstResponseTimeoutMs": verifier.FIRST_RESPONSE_TIMEOUT_MS,
            "readTimeoutMs": verifier.READ_TIMEOUT_MS,
            "runtimeRecoveryPolicyId": INPUTS["recoveryPolicy"]["policyId"],
            "runtimeRemoteAttemptLimit": INPUTS["recoveryPolicy"]["remoteAttemptLimit"],
            "runtimeDeliveryBindingRefreshLimit": (
                INPUTS["recoveryPolicy"]["deliveryBindingRefreshLimit"]
            ),
            "runtimeBackoffBaseMs": INPUTS["recoveryPolicy"]["backoffBaseMs"],
            "runtimeBackoffCapMs": INPUTS["recoveryPolicy"]["backoffCapMs"],
        },
    }


def origin_row(plan: dict, request_id: int) -> dict:
    return {
        "schemaVersion": 1,
        "sessionId": "m2-g2-n0",
        "requestId": request_id,
        "plane": "data",
        "fixtureId": "F1",
        "resourceId": "segment-1-00001.m4s",
        "profileId": "N0",
        "scenarioId": "N0",
        "scenarioHash": LAB_SCENARIO_HASH,
        "method": "GET",
        "path": verifier.RESOURCE_PATH,
        "rangeHeader": f"bytes=0-{verifier.RESOURCE_LENGTH - 1}",
        "resolvedRangeStart": 0,
        "resolvedRangeEndExclusive": verifier.RESOURCE_LENGTH,
        "status": 206,
        "plannedResponseBytes": verifier.RESOURCE_LENGTH,
        "bodyBytesWritten": verifier.RESOURCE_LENGTH,
        "handlerStartedAtMonotonicNs": request_id * 100,
        "firstBodyWriteAtMonotonicNs": request_id * 100 + 1,
        "completedAtMonotonicNs": request_id * 100 + 2,
        "serverFirstBodyWriteDelayMs": 0,
        "handlerDurationMs": 1,
        "configuredRateBps": None,
        "noProgressWaitMs": 0,
        "outcome": "SUCCESS",
    }


class G2N0VerifierTest(unittest.TestCase):
    def setUp(self):
        self.plan = build_plan()
        self.schedule = pair_plan.planned_trial_schedule(self.plan)
        self.temp = tempfile.TemporaryDirectory()
        self.raw_dir = pathlib.Path(self.temp.name)
        self.origin = []
        for index, schedule_row in enumerate(self.schedule, start=1):
            raw = raw_case(self.plan, schedule_row, index)
            (self.raw_dir / f"{schedule_row['trialId']}.json").write_text(
                json.dumps(raw), encoding="utf-8"
            )
            self.origin.append(origin_row(self.plan, index))

    def tearDown(self):
        self.temp.cleanup()

    def verify(self):
        return verifier.verify(
            plan=self.plan,
            raw_dir=self.raw_dir,
            origin=self.origin,
            scenario=SCENARIO,
            inputs={**INPUTS, "_runtimeDevice": copy.deepcopy(RUNTIME_DEVICE)},
        )

    def mutate_raw(self, trial_id: str, mutator):
        path = self.raw_dir / f"{trial_id}.json"
        value = json.loads(path.read_text(encoding="utf-8"))
        mutator(value)
        path.write_text(json.dumps(value), encoding="utf-8")

    def test_valid_n0_pair_is_directional_only_and_selects_nothing(self):
        trials, result = self.verify()
        self.assertEqual(4, len(trials["trials"]))
        self.assertEqual("PASS", result["status"])
        self.assertEqual("API36_EMULATOR_DIRECTIONAL_ONLY", result["claimScope"])
        self.assertEqual(2, result["pairedBlockCount"])
        self.assertIsNone(result["selectedBackend"])
        for row in trials["trials"]:
            self.assertEqual("OBSERVABLE", row["recovery"]["internalRetryVisibility"])
            self.assertEqual(0, row["recovery"]["internalRetryCount"])
            self.assertTrue(row["performanceSampleEligible"])
            self.assertIn(
                "INTERNAL_HTTP_REPLAY_ZERO_OBSERVED_FROM_EXACT_ORIGIN_GET_BIJECTION",
                row["limitations"],
            )
            self.assertNotIn(
                "RAW_DEVICE_ROW_REQUIRES_HOST_RETRY_FINALIZATION",
                row["limitations"],
            )

    def test_missing_or_extra_trial_is_rejected(self):
        missing = self.raw_dir / f"{self.schedule[-1]['trialId']}.json"
        missing.unlink()
        with self.assertRaises(ValueError):
            self.verify()

        missing.write_text(
            json.dumps(raw_case(self.plan, self.schedule[-1], 4)),
            encoding="utf-8",
        )
        (self.raw_dir / "result-dependent-extra.json").write_text("{}", encoding="utf-8")
        with self.assertRaises(ValueError):
            self.verify()

    def test_reused_process_fails_cold_reset_proof(self):
        first = self.schedule[0]["trialId"]
        second = self.schedule[1]["trialId"]
        first_value = json.loads((self.raw_dir / f"{first}.json").read_text())
        process_id = first_value["proof"]["processInstanceId"]
        self.mutate_raw(second, lambda raw: raw["proof"].__setitem__("processInstanceId", process_id))
        with self.assertRaisesRegex(ValueError, "fresh instrumentation process"):
            self.verify()

    def test_runtime_device_readback_must_match_frozen_device_state(self):
        original = RUNTIME_DEVICE["abi"]
        RUNTIME_DEVICE["abi"] = "arm64-v8a"
        try:
            with self.assertRaisesRegex(ValueError, "host ABI"):
                self.verify()
        finally:
            RUNTIME_DEVICE["abi"] = original

        trial_id = self.schedule[0]["trialId"]
        self.mutate_raw(
            trial_id,
            lambda raw: raw["proof"].__setitem__("androidApi", 35),
        )
        with self.assertRaisesRegex(ValueError, "device Android API"):
            self.verify()

    def test_reused_os_process_identity_fails_cold_reset_proof(self):
        first = self.schedule[0]["trialId"]
        second = self.schedule[1]["trialId"]
        first_value = json.loads((self.raw_dir / f"{first}.json").read_text())
        pid = first_value["proof"]["processPid"]
        start = first_value["proof"]["processStartClockTicks"]
        self.mutate_raw(
            second,
            lambda raw: raw["proof"].update(
                {"processPid": pid, "processStartClockTicks": start}
            ),
        )
        with self.assertRaisesRegex(ValueError, "distinct OS process"):
            self.verify()

    def test_runtime_recovery_policy_must_match_frozen_input(self):
        trial_id = self.schedule[0]["trialId"]
        self.mutate_raw(
            trial_id,
            lambda raw: raw["proof"].__setitem__("runtimeRemoteAttemptLimit", 3),
        )
        with self.assertRaisesRegex(ValueError, "RecoveryPolicy.DEFAULT drifted"):
            self.verify()

    def test_transport_timeout_policy_drift_is_rejected(self):
        trial_id = self.schedule[0]["trialId"]
        self.mutate_raw(
            trial_id,
            lambda raw: raw["proof"].__setitem__("firstResponseTimeoutMs", 20_000),
        )
        with self.assertRaisesRegex(ValueError, "timeout policy drift"):
            self.verify()

    def test_first_byte_boundary_must_be_disclosed(self):
        trial_id = self.schedule[0]["trialId"]
        self.mutate_raw(
            trial_id,
            lambda raw: raw["trial"]["limitations"].remove(
                "FIRST_BYTE_IS_FIRST_ACCEPTED_16K_CHUNK"
            ),
        )
        with self.assertRaisesRegex(ValueError, "firstByteUs measurement boundary"):
            self.verify()

    def test_result_reordering_or_origin_relabeling_is_rejected(self):
        first = self.schedule[0]["trialId"]
        second = self.schedule[1]["trialId"]
        self.mutate_raw(first, lambda raw: raw["proof"].__setitem__("originRequestId", 2))
        self.mutate_raw(second, lambda raw: raw["proof"].__setitem__("originRequestId", 1))
        with self.assertRaises(ValueError):
            self.verify()

    def test_route_epoch_change_is_rejected(self):
        trial_id = self.schedule[0]["trialId"]
        self.mutate_raw(trial_id, lambda raw: raw["proof"].__setitem__("routeEpochAfter", 2))
        with self.assertRaisesRegex(ValueError, "route epoch changed"):
            self.verify()

    def test_byte_or_timing_corruption_is_rejected(self):
        trial_id = self.schedule[0]["trialId"]
        self.mutate_raw(
            trial_id,
            lambda raw: raw["trial"]["metrics"].__setitem__(
                "bytesReceived", verifier.RESOURCE_LENGTH - 1
            ),
        )
        with self.assertRaisesRegex(ValueError, "received bytes"):
            self.verify()

        self.setUp_fresh()
        trial_id = self.schedule[0]["trialId"]
        self.mutate_raw(
            trial_id,
            lambda raw: raw["trial"]["metrics"].update(
                {"firstByteUs": 100_001, "completionUs": 100_000}
            ),
        )
        with self.assertRaisesRegex(ValueError, "timing order"):
            self.verify()

    def setUp_fresh(self):
        self.temp.cleanup()
        self.temp = tempfile.TemporaryDirectory()
        self.raw_dir = pathlib.Path(self.temp.name)
        self.origin = []
        for index, schedule_row in enumerate(self.schedule, start=1):
            (self.raw_dir / f"{schedule_row['trialId']}.json").write_text(
                json.dumps(raw_case(self.plan, schedule_row, index)),
                encoding="utf-8",
            )
            self.origin.append(origin_row(self.plan, index))

    def test_origin_range_body_or_scenario_drift_is_rejected(self):
        cases = (
            ("rangeHeader", "bytes=1-81810"),
            ("bodyBytesWritten", verifier.RESOURCE_LENGTH - 1),
            ("scenarioHash", "0" * 64),
        )
        for field, value in cases:
            with self.subTest(field=field):
                original = self.origin[0][field]
                self.origin[0][field] = value
                with self.assertRaises(ValueError):
                    self.verify()
                self.origin[0][field] = original

    def test_device_row_cannot_self_claim_internal_retry_visibility(self):
        trial_id = self.schedule[0]["trialId"]
        self.mutate_raw(
            trial_id,
            lambda raw: (
                raw["trial"]["recovery"].update(
                    {"internalRetryVisibility": "OBSERVABLE", "internalRetryCount": 0}
                ),
                raw["trial"].__setitem__("performanceSampleEligible", True),
            ),
        )
        with self.assertRaisesRegex(ValueError, "must not self-certify internal retries"):
            self.verify()

    def test_any_extra_origin_get_breaks_proven_zero(self):
        extra = origin_row(self.plan, 99)
        self.origin.append(extra)
        with self.assertRaisesRegex(ValueError, "exactly four measured GETs"):
            self.verify()

    def test_retry_or_jitter_activity_in_n0_is_rejected(self):
        trial_id = self.schedule[0]["trialId"]
        self.mutate_raw(
            trial_id,
            lambda raw: (
                raw["trial"]["recovery"].update(
                    {"internalRetryVisibility": "OBSERVABLE", "internalRetryCount": 1}
                ),
                raw["proof"].update(
                    {"recoveryJitterSampleCount": 1, "recoveryJitterSamples": [367]}
                ),
            ),
        )
        with self.assertRaises(ValueError):
            self.verify()

    def test_backend_version_drift_between_repetitions_is_rejected(self):
        candidate = [
            row for row in self.schedule
            if row["backendId"] == "PLATFORM_HTTP_ENGINE"
        ]
        self.assertEqual(2, len(candidate))
        self.mutate_raw(
            candidate[1]["trialId"],
            lambda raw: raw["trial"].__setitem__("backendVersion", "different-platform-version"),
        )
        with self.assertRaises(Exception):
            self.verify()


if __name__ == "__main__":
    unittest.main()
