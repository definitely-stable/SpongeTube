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

import m2_transport_environment as envmod
import m2_transport_pair_plan as pair_plan


def load_module():
    path = ROOT / "scripts" / "ci" / "verify-m2-g2-network.py"
    spec = importlib.util.spec_from_file_location("verify_m2_g2_network", path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


verifier = load_module()


def load(path: str) -> dict:
    return json.loads((ROOT / path).read_text(encoding="utf-8"))


SCENARIO = load("test-fixtures/network/m2/n2-high-rtt-jitter.json")
INPUTS = {
    "work": load("test-fixtures/network/m2/g2/work-f1-video-segment-1.json"),
    "deviceState": load("test-fixtures/network/m2/g2/device-api36-emulator.json"),
    "cacheState": load("test-fixtures/network/m2/g2/cache-empty-http-disabled.json"),
    "recoveryPolicy": load("test-fixtures/network/m2/g2/recovery-sponge-v2.json"),
    "routePolicy": load("test-fixtures/network/m2/g2/route-exact-default.json"),
}
FAULT_ENGINE = {
    "schemaVersion": 1,
    "offloads": {"gro": False, "gso": False, "tso": False},
}
LINK_STATE = {
    "mtu": 1500,
    "offloads": {"gro": False, "gso": False, "tso": False},
}
ANDROID_RUNTIME = {
    "deviceClass": "ANDROID_EMULATOR",
    "androidApi": 36,
    "abi": "x86_64",
    "buildFingerprint": "google/sdk_gphone64_x86_64/test:16/ABC/123:userdebug/test-keys",
    "buildId": "ABC",
    "securityPatch": "2026-09-05",
    "kernelRelease": "6.1.0-android-test",
    "batteryPolicy": "CI_POWERED",
    "acPowered": True,
}
LAB_SCENARIO_HASH = "7" * 64


def build_plan() -> dict:
    return pair_plan.build_plan(
        run_id="m2-g2-n2-api36",
        pair_id="m2-g2-n2-api36-cold",
        scenario=SCENARIO,
        inputs=INPUTS,
        ordering_seed=20261001,
        block_count=2,
        playback_mode="SPONGE",
        connection_state="COLD",
    )


def raw_case(plan: dict, schedule_row: dict, request_id: int) -> dict:
    backend = schedule_row["backendId"]
    chain_start = 1_000_000_000 + request_id * 10_000_000
    attempt_start = chain_start + 1_000_000
    headers = attempt_start + 100_000_000
    first_transport = headers + 10_000_000
    first_broker = first_transport + 1_000
    body_complete = first_transport + 50_000_000
    attempt_end = body_complete + 1_000_000
    chain_end = attempt_end + 2_000_000
    return {
        "schemaVersion": 1,
        "phase": "M2-G2-C-NETWORK",
        "scenarioFamily": "N2",
        "scenarioVariant": "HIGH_RTT_JITTER",
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
            "route": {"exactNetworkBound": True, "permitRouteEpoch": 1},
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
                "firstByteUs": (first_broker - chain_start) // 1_000,
                "completionUs": (chain_end - chain_start) // 1_000,
                "cancellationLatencyUs": None,
                "cpuTimeUs": 20_000,
                "maxRssBytes": 50_000_000,
                "bytesRequested": verifier.RESOURCE_LENGTH,
                "bytesReceived": verifier.RESOURCE_LENGTH,
                "bytesPublished": verifier.RESOURCE_LENGTH,
            },
            "negotiatedProtocol": "UNKNOWN" if backend == "HTTP_URL_CONNECTION_ROUTE_BOUND" else "HTTP_2",
            "performanceSampleEligible": False,
            "limitations": [
                "RAW_DEVICE_ROW_REQUIRES_HOST_RETRY_FINALIZATION",
                "API36_EMULATOR_DIRECTIONAL_ONLY",
                "MAX_RSS_IS_FRESH_PROCESS_HIGH_WATER",
                "FIRST_BYTE_IS_FIRST_ACCEPTED_FETCHBROKER_CHUNK_IN_RECOVERY_CHAIN",
                "COMPLETION_IS_RECOVERY_CHAIN_TERMINAL_AFTER_PUBLICATION",
                "TRANSPORT_PHASES_ARE_PHYSICAL_ATTEMPT_LEVEL",
            ],
        },
        "proof": {
            "correlatedOriginRequestIds": [request_id],
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
            "bindingTargetResolutionCount": 1,
            "chainStartedElapsedRealtimeNs": chain_start,
            "firstBrokerProgressElapsedRealtimeNs": first_broker,
            "chainTerminatedElapsedRealtimeNs": chain_end,
            "physicalAttempts": [{
                "fetchId": f"fetch-{request_id}",
                "startElapsedRealtimeNs": attempt_start,
                "endElapsedRealtimeNs": attempt_end,
                "terminal": "ATTEMPT_COMPLETED",
                "transportCorrelationId": str(request_id),
                "networkBytes": verifier.RESOURCE_LENGTH,
            }],
            "transportPhases": [
                {
                    "fetchKey": "fixture:F1/video-main/f1-video-0/test",
                    "attempt": 1,
                    "kind": "RESPONSE_HEADERS",
                    "elapsedRealtimeNs": headers,
                },
                {
                    "fetchKey": "fixture:F1/video-main/f1-video-0/test",
                    "attempt": 1,
                    "kind": "FIRST_BODY_BYTES",
                    "elapsedRealtimeNs": first_transport,
                },
                {
                    "fetchKey": "fixture:F1/video-main/f1-video-0/test",
                    "attempt": 1,
                    "kind": "RESPONSE_BODY_COMPLETE",
                    "elapsedRealtimeNs": body_complete,
                },
            ],
            "recoveryFailureCount": 0,
            "recoveryBackoffs": [],
            "recoveryJitterProtocol": verifier.RECOVERY_JITTER_PROTOCOL,
            "recoveryJitterSeed": verifier.RECOVERY_JITTER_SEED,
            "recoveryJitterSampleCount": 0,
            "recoveryJitterSamples": [],
            "firstResponseTimeoutMs": verifier.FIRST_RESPONSE_TIMEOUT_MS,
            "readTimeoutMs": verifier.READ_TIMEOUT_MS,
            "runtimeRecoveryPolicyId": INPUTS["recoveryPolicy"]["policyId"],
            "runtimeRemoteAttemptLimit": INPUTS["recoveryPolicy"]["remoteAttemptLimit"],
            "runtimeDeliveryBindingRefreshLimit": INPUTS["recoveryPolicy"]["deliveryBindingRefreshLimit"],
            "runtimeBackoffBaseMs": INPUTS["recoveryPolicy"]["backoffBaseMs"],
            "runtimeBackoffCapMs": INPUTS["recoveryPolicy"]["backoffCapMs"],
            "negotiatedProtocols": [],
        },
    }


def origin_row(plan: dict, request_id: int) -> dict:
    return {
        "schemaVersion": 1,
        "sessionId": "m2-g2-n2",
        "requestId": request_id,
        "plane": "data",
        "fixtureId": "F1",
        "resourceId": "segment-0-00001.m4s",
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


def write_json(path: pathlib.Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value), encoding="utf-8")


def write_trial_harness(root: pathlib.Path, trial_id: str, plan: dict) -> None:
    trial = root / trial_id
    write_json(trial / "harness" / "netem-active-state.json", verifier.N2_ACTIVE_STATE)
    write_json(trial / "harness" / "netem-clean-state.json", {"filters": 0, "netem": 0, "prio": 0})
    write_json(
        trial / "harness" / "fault-harness-events.json",
        {
            "runId": plan["runId"],
            "scenarioHash": plan["scenario"]["hash"],
            "events": [
                {"operation": name}
                for name in (
                    "HARNESS_STARTED",
                    "FAULT_ARMED",
                    "FAULT_APPLIED",
                    "FAULT_REMOVED",
                    "HARNESS_STOPPED",
                )
            ],
        },
    )
    write_json(
        trial / "harness" / "qdisc-final.json",
        [{
            "kind": "netem",
            "parent": "1:1",
            "packets": 100,
            "bytes": 900_000,
            "options": {
                "delay": {"delay": 0.1, "jitter": 0.03, "correlation": 0.25},
                "seed": 424242,
            },
        }],
    )
    write_json(
        trial / "harness" / "filter.json",
        [{
            "kind": "flower",
            "protocol": "ip",
            "options": {
                "classid": "1:1",
                "keys": {"eth_type": "ipv4", "ip_proto": "tcp", "src_port": 18081},
            },
        }],
    )
    write_json(trial / "scope-before.json", {"mediaPackets": 10, "mediaBytes": 100})
    write_json(trial / "scope-after.json", {"mediaPackets": 110, "mediaBytes": 900_100})


class G2NetworkVerifierTest(unittest.TestCase):
    def setUp(self):
        self.plan = build_plan()
        self.schedule = pair_plan.planned_trial_schedule(self.plan)
        self.temp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temp.name)
        self.raw_dir = self.root / "raw"
        self.trial_root = self.root / "trials"
        self.raw_dir.mkdir()
        self.origin = []
        for index, schedule_row in enumerate(self.schedule, start=1):
            write_json(
                self.raw_dir / f"{schedule_row['trialId']}.json",
                raw_case(self.plan, schedule_row, index),
            )
            write_trial_harness(self.trial_root, schedule_row["trialId"], self.plan)
            self.origin.append(origin_row(self.plan, index))
        self.environment = envmod.build(
            run_id=self.plan["runId"],
            source_head_commit="a" * 40,
            checkout_commit="b" * 40,
            fault_engine=FAULT_ENGINE,
            link_state=LINK_STATE,
            android_runtime=ANDROID_RUNTIME,
        )

    def tearDown(self):
        self.temp.cleanup()

    def verify(self):
        return verifier.verify_n2(
            plan=self.plan,
            raw_dir=self.raw_dir,
            trial_root=self.trial_root,
            origin=self.origin,
            scenario=SCENARIO,
            inputs=INPUTS,
            environment=self.environment,
            fault_engine=FAULT_ENGINE,
            link_state=LINK_STATE,
            android_runtime=ANDROID_RUNTIME,
        )

    def mutate_raw(self, trial_id: str, mutator):
        path = self.raw_dir / f"{trial_id}.json"
        value = json.loads(path.read_text())
        mutator(value)
        write_json(path, value)

    def test_valid_n2_pair_emits_g0_and_phase_timings(self):
        trials, timings, result = self.verify()
        self.assertEqual(4, len(trials["trials"]))
        self.assertEqual(4, len(timings["rows"]))
        self.assertEqual("PASS", result["status"])
        self.assertEqual(2, result["pairedBlockCount"])
        self.assertIsNone(result["selectedBackend"])
        for row in trials["trials"]:
            self.assertEqual("OBSERVABLE", row["recovery"]["internalRetryVisibility"])
            self.assertEqual(0, row["recovery"]["internalRetryCount"])
            self.assertTrue(row["performanceSampleEligible"])

    def test_extra_origin_replay_is_rejected(self):
        self.origin.append(origin_row(self.plan, 99))
        with self.assertRaisesRegex(verifier.NetworkEvidenceError, "exactly four origin-visible"):
            self.verify()

    def test_seed_drift_is_rejected(self):
        trial_id = self.schedule[0]["trialId"]
        path = self.trial_root / trial_id / "harness" / "netem-active-state.json"
        value = json.loads(path.read_text())
        value["randomSeed"] = 7
        write_json(path, value)
        with self.assertRaisesRegex(verifier.NetworkEvidenceError, "active netem readback drift"):
            self.verify()

    def test_zero_effect_path_is_rejected(self):
        trial_id = self.schedule[0]["trialId"]
        path = self.trial_root / trial_id / "harness" / "qdisc-final.json"
        value = json.loads(path.read_text())
        value[0]["packets"] = 0
        write_json(path, value)
        with self.assertRaisesRegex(verifier.NetworkEvidenceError, "saw no media traffic"):
            self.verify()

    def test_reused_process_identity_is_rejected(self):
        first, second = self.schedule[0]["trialId"], self.schedule[1]["trialId"]
        first_value = json.loads((self.raw_dir / f"{first}.json").read_text())
        pid = first_value["proof"]["processPid"]
        start = first_value["proof"]["processStartClockTicks"]
        self.mutate_raw(
            second,
            lambda raw: raw["proof"].update(
                {"processPid": pid, "processStartClockTicks": start}
            ),
        )
        with self.assertRaisesRegex(verifier.NetworkEvidenceError, "reused OS process"):
            self.verify()

    def test_first_byte_metric_must_recompute_from_raw_timestamp(self):
        trial_id = self.schedule[0]["trialId"]
        self.mutate_raw(
            trial_id,
            lambda raw: raw["trial"]["metrics"].__setitem__(
                "firstByteUs", raw["trial"]["metrics"]["firstByteUs"] + 1
            ),
        )
        with self.assertRaisesRegex(verifier.NetworkEvidenceError, "firstByteUs is not reproducible"):
            self.verify()

    def test_raw_row_cannot_self_certify_internal_retry_visibility(self):
        trial_id = self.schedule[0]["trialId"]
        self.mutate_raw(
            trial_id,
            lambda raw: raw["trial"]["recovery"].update(
                {"internalRetryVisibility": "OBSERVABLE", "internalRetryCount": 0}
            ),
        )
        with self.assertRaisesRegex(verifier.NetworkEvidenceError, "raw N2 recovery lineage drift"):
            self.verify()


if __name__ == "__main__":
    unittest.main()
