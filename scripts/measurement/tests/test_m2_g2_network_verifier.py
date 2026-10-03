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


SCENARIOS = {
    "N2": load("test-fixtures/network/m2/n2-high-rtt-jitter.json"),
    "N3": load("test-fixtures/network/m2/n3-burst-packet-loss.json"),
    "N5": load("test-fixtures/network/m2/n5-burst-loss.json"),
    "N5GE": load("test-fixtures/network/m2/n5-ge-moment-match.json"),
}
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


def build_plan(profile: str) -> dict:
    slug = "n5-ge" if profile == "N5GE" else profile.lower()
    return pair_plan.build_plan(
        run_id=f"m2-g2-{slug}-api36",
        pair_id=f"m2-g2-{slug}-api36-cold",
        scenario=SCENARIOS[profile],
        inputs=INPUTS,
        ordering_seed=20261001,
        block_count=2,
        playback_mode="SPONGE",
        connection_state="COLD",
    )


def raw_case(profile: str, plan: dict, schedule_row: dict, request_id: int) -> dict:
    backend = schedule_row["backendId"]
    spec = verifier.SPECS[profile]
    family = spec["family"]
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
        "scenarioFamily": family,
        "scenarioVariant": spec["variant"],
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
            "recoveryFailures": [],
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


def control_row() -> dict:
    return {
        "schemaVersion": 2,
        "sessionId": "m2-g2-network",
        "requestId": 1,
        "plane": "control",
        "fixtureId": None,
        "resourceId": None,
        "profileId": "N0",
        "scenarioId": "N0",
        "scenarioHash": LAB_SCENARIO_HASH,
        "method": "GET",
        "path": "/__lab/config",
        "rangeHeader": None,
        "resolvedRangeStart": None,
        "resolvedRangeEndExclusive": None,
        "status": 200,
        "plannedResponseBytes": 1,
        "bodyBytesWritten": 1,
        "handlerStartedAtMonotonicNs": 1,
        "firstBodyWriteAtMonotonicNs": 2,
        "completedAtMonotonicNs": 3,
        "serverFirstBodyWriteDelayMs": 0,
        "handlerDurationMs": 1,
        "configuredRateBps": None,
        "noProgressWaitMs": 0,
        "outcome": "SUCCESS",
    }


def origin_row(request_id: int) -> dict:
    return {
        "schemaVersion": 1,
        "sessionId": "m2-g2-network",
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


def qdisc(profile: str, *, drops: int, packets: int = 100) -> list[dict]:
    if profile == "N2":
        options = {
            "delay": {"delay": 0.1, "jitter": 0.03, "correlation": 0.25},
            "seed": 424242,
        }
    elif profile == "N3":
        options = {"loss-random": {"loss": 1.0, "correlation": 0.0}}
    elif profile == "N5":
        options = {
            "loss-random": {"loss": 0.02, "correlation": 0.25},
            "seed": 424242,
        }
    else:
        options = {
            "loss-gemodel": {
                "p": 0.015,
                "r": 0.735,
                "1-h": 1.0,
                "1-k": 0.0,
            },
            "seed": 424242,
        }
    return [{
        "kind": "netem",
        "parent": "1:1",
        "packets": packets,
        "bytes": 900_000,
        "drops": drops,
        "options": options,
    }]


def write_trial_harness(root: pathlib.Path, profile: str, trial_id: str, plan: dict, before: int, after: int) -> None:
    trial = root / trial_id
    write_json(trial / "harness" / "netem-active-state.json", verifier.SPECS[profile]["active"])
    write_json(trial / "harness" / "netem-clean-state.json", {"filters": 0, "netem": 0, "prio": 0})
    base_ns = 1_000_000_000
    event_times = [
        base_ns,
        base_ns + 1_000_000,
        base_ns + 2_000_000,
        base_ns + (1_502_000_000 if profile == "N3" else 3_000_000),
        base_ns + (1_503_000_000 if profile == "N3" else 4_000_000),
    ]
    operations = (
        "HARNESS_STARTED",
        "FAULT_ARMED",
        "FAULT_APPLIED",
        "FAULT_REMOVED",
        "HARNESS_STOPPED",
    )
    events = []
    for index, (operation, elapsed_ns) in enumerate(zip(operations, event_times), start=1):
        parameters = {"durationMs": 1_500} if profile == "N3" and operation == "FAULT_APPLIED" else {}
        events.append({
            "sequence": index,
            "operation": operation,
            "elapsedRealtimeNs": elapsed_ns,
            "parameters": parameters,
        })
    write_json(
        trial / "harness" / "fault-harness-events.json",
        {
            "runId": plan["runId"],
            "scenarioHash": plan["scenario"]["hash"],
            "clockDomain": "HOST_FAULT_MONOTONIC",
            "events": events,
        },
    )
    if profile == "N3":
        write_json(trial / "harness" / "qdisc-applied.json", qdisc(profile, drops=1))
        write_json(trial / "harness" / "qdisc-final.json", qdisc(profile, drops=5))
    elif profile == "N5GE":
        write_json(trial / "harness" / "qdisc-applied.json", qdisc(profile, drops=0))
        write_json(trial / "harness" / "qdisc-final.json", qdisc(profile, drops=2))
    else:
        write_json(trial / "harness" / "qdisc-applied.json", qdisc(profile, drops=0))
        write_json(trial / "harness" / "qdisc-final.json", qdisc(profile, drops=0))
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
    (trial / "origin-before-count.txt").write_text(f"{before}\n", encoding="utf-8")
    (trial / "origin-after-count.txt").write_text(f"{after}\n", encoding="utf-8")


class NetworkCase:
    def __init__(self, profile: str):
        self.profile = profile
        self.plan = build_plan(profile)
        self.schedule = pair_plan.planned_trial_schedule(self.plan)
        self.temp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temp.name)
        self.raw_dir = self.root / "raw"
        self.trial_root = self.root / "trials"
        self.raw_dir.mkdir()
        self.origin = [control_row()]
        for index, schedule_row in enumerate(self.schedule, start=2):
            write_json(
                self.raw_dir / f"{schedule_row['trialId']}.json",
                raw_case(profile, self.plan, schedule_row, index),
            )
            self.origin.append(origin_row(index))
            write_trial_harness(
                self.trial_root,
                profile,
                schedule_row["trialId"],
                self.plan,
                index - 1,
                index,
            )
        self.link_state = copy.deepcopy(LINK_STATE)
        self.link_state["mtu"] = verifier.SPECS[profile]["mtu"]
        self.environment = envmod.build(
            run_id=self.plan["runId"],
            source_head_commit="a" * 40,
            checkout_commit="b" * 40,
            fault_engine=FAULT_ENGINE,
            link_state=self.link_state,
            android_runtime=ANDROID_RUNTIME,
        )

    def close(self):
        self.temp.cleanup()

    def verify(self):
        return verifier.verify_network(
            plan=self.plan,
            raw_dir=self.raw_dir,
            trial_root=self.trial_root,
            origin=self.origin,
            scenario=SCENARIOS[self.profile],
            inputs=INPUTS,
            environment=self.environment,
            fault_engine=FAULT_ENGINE,
            link_state=self.link_state,
            android_runtime=ANDROID_RUNTIME,
        )


class G2NetworkVerifierTest(unittest.TestCase):
    def test_network_profiles_emit_claims_at_their_actual_evidence_strength(self):
        for profile in ("N2", "N3", "N5", "N5GE"):
            case = NetworkCase(profile)
            try:
                trials, timings, result = case.verify()
                self.assertEqual(4, len(trials["trials"]))
                self.assertEqual(4, len(timings["rows"]))
                self.assertEqual("PASS", result["status"])
                self.assertEqual(2, result["pairedBlockCount"])
                self.assertIsNone(result["selectedBackend"])
                if profile == "N5":
                    self.assertEqual(
                        "CONFIGURATION_BOUND_STOCHASTIC_OBSERVATION",
                        result["claimScope"],
                    )
                    self.assertEqual(
                        "INCONCLUSIVE_STOCHASTIC_EFFECT",
                        result["resilienceClaim"],
                    )
                else:
                    self.assertEqual(
                        "OBSERVED_EFFECT_EQUIVALENT",
                        result["resilienceClaim"],
                    )
                for row in trials["trials"]:
                    self.assertEqual("OBSERVABLE", row["recovery"]["internalRetryVisibility"])
                    self.assertEqual(0, row["recovery"]["internalRetryCount"])
                    self.assertTrue(row["performanceSampleEligible"])
            finally:
                case.close()

    def test_legacy_n5_zero_effect_is_retained_but_ineligible(self):
        case = NetworkCase("N5")
        try:
            trials, _, result = case.verify()
            self.assertEqual(0, result["effectPositiveTrialCount"])
            self.assertEqual(2, result["pairedBlockCount"])
            self.assertTrue(
                all(row["performanceSampleEligible"] for row in trials["trials"])
            )
            self.assertTrue(
                all(
                    "LEGACY_NETEM_CORRELATED_RANDOM_STATE_IS_NOT_FULLY_SEED_REPRODUCIBLE"
                    in row["limitations"]
                    for row in trials["trials"]
                )
            )
        finally:
            case.close()

    def test_n5_ge_zero_effect_trial_is_rejected(self):
        case = NetworkCase("N5GE")
        try:
            trial_id = case.schedule[0]["trialId"]
            path = case.trial_root / trial_id / "harness" / "qdisc-final.json"
            write_json(path, qdisc("N5GE", drops=0))
            with self.assertRaisesRegex(verifier.NetworkEvidenceError, "N5GE.*zero scoped drops"):
                case.verify()
        finally:
            case.close()

    def test_n3_requires_first_drop_rendezvous(self):
        case = NetworkCase("N3")
        try:
            trial_id = case.schedule[0]["trialId"]
            path = case.trial_root / trial_id / "harness" / "qdisc-applied.json"
            write_json(path, qdisc("N3", drops=0))
            with self.assertRaisesRegex(verifier.NetworkEvidenceError, "first scoped drop"):
                case.verify()
        finally:
            case.close()

    def test_n3_accepts_drop_only_qdisc_counters(self):
        case = NetworkCase("N3")
        try:
            for row in case.schedule:
                trial_id = row["trialId"]
                final_path = case.trial_root / trial_id / "harness" / "qdisc-final.json"
                value = qdisc("N3", drops=5, packets=0)
                value[0]["bytes"] = 0
                write_json(final_path, value)
            trials, _, result = case.verify()
            self.assertEqual(4, len(trials["trials"]))
            self.assertEqual("PASS", result["status"])
        finally:
            case.close()

    def test_origin_prelude_must_be_exact_control_readiness_request(self):
        case = NetworkCase("N2")
        try:
            case.origin[0] = origin_row(1)
            with self.assertRaisesRegex(
                verifier.NetworkEvidenceError,
                "unexpected origin trace prelude",
            ):
                case.verify()
        finally:
            case.close()

    def test_n5_ge_requires_distinct_effect_packetization_profile(self):
        case = NetworkCase("N5GE")
        try:
            self.assertEqual(512, case.link_state["mtu"])
            self.assertEqual(
                "G2_EFFECT_MTU512_V1",
                case.environment["mediaLink"]["packetizationProfile"],
            )
            case.environment["mediaLink"]["mtu"] = 1500
            case.environment["mediaLink"]["packetizationProfile"] = "STANDARD_MTU1500_V1"
            case.link_state["mtu"] = 1500
            with self.assertRaisesRegex(
                verifier.NetworkEvidenceError,
                "media-link MTU drift",
            ):
                case.verify()
        finally:
            case.close()

    def test_origin_partition_detects_unassigned_replay(self):
        case = NetworkCase("N2")
        try:
            case.origin.append(origin_row(99))
            with self.assertRaisesRegex(verifier.NetworkEvidenceError, "unassigned origin trace rows"):
                case.verify()
        finally:
            case.close()

    def test_raw_row_cannot_self_certify_internal_retry_visibility(self):
        case = NetworkCase("N2")
        try:
            trial_id = case.schedule[0]["trialId"]
            path = case.raw_dir / f"{trial_id}.json"
            value = json.loads(path.read_text())
            value["trial"]["recovery"].update(
                {"internalRetryVisibility": "OBSERVABLE", "internalRetryCount": 0}
            )
            write_json(path, value)
            with self.assertRaisesRegex(verifier.NetworkEvidenceError, "must not self-certify"):
                case.verify()
        finally:
            case.close()

    def test_n3_shortened_blackout_is_rejected(self):
        case = NetworkCase("N3")
        try:
            trial_id = case.schedule[0]["trialId"]
            path = case.trial_root / trial_id / "harness" / "fault-harness-events.json"
            value = json.loads(path.read_text())
            applied_ns = value["events"][2]["elapsedRealtimeNs"]
            value["events"][3]["elapsedRealtimeNs"] = applied_ns + 100_000_000
            value["events"][4]["elapsedRealtimeNs"] = applied_ns + 101_000_000
            write_json(path, value)
            with self.assertRaisesRegex(
                verifier.NetworkEvidenceError,
                "ended before frozen 1500 ms",
            ):
                case.verify()
        finally:
            case.close()

    def test_first_broker_chunk_must_follow_transport_body_phase(self):
        case = NetworkCase("N2")
        try:
            trial_id = case.schedule[0]["trialId"]
            path = case.raw_dir / f"{trial_id}.json"
            value = json.loads(path.read_text())
            chain_start = value["proof"]["chainStartedElapsedRealtimeNs"]
            value["proof"]["firstBrokerProgressElapsedRealtimeNs"] = chain_start
            value["trial"]["metrics"]["firstByteUs"] = 0
            write_json(path, value)
            with self.assertRaisesRegex(
                verifier.NetworkEvidenceError,
                "first broker chunk is not causally bound",
            ):
                case.verify()
        finally:
            case.close()

    def test_pre_origin_failed_owner_is_not_counted_as_internal_http_replay(self):
        case = NetworkCase("N2")
        try:
            # Keep the paired recovery tuple equivalent while exercising a
            # physical owner that fails before HTTP response correlation. The
            # origin still sees exactly one GET per trial, so this must derive
            # zero transport-internal replay rather than subtracting all owners.
            for schedule_row in case.schedule:
                trial_id = schedule_row["trialId"]
                raw_path = case.raw_dir / f"{trial_id}.json"
                raw = json.loads(raw_path.read_text())
                proof = raw["proof"]
                trial = raw["trial"]
                original = proof["physicalAttempts"][0]
                chain_start = proof["chainStartedElapsedRealtimeNs"]
                failed_start = chain_start + 100_000
                failed_end = chain_start + 500_000
                success = copy.deepcopy(original)
                success["startElapsedRealtimeNs"] += 1_000_000
                success["endElapsedRealtimeNs"] += 1_000_000
                proof["physicalAttempts"] = [{
                    "fetchId": f"fetch-pre-origin-timeout-{trial_id}",
                    "startElapsedRealtimeNs": failed_start,
                    "endElapsedRealtimeNs": failed_end,
                    "terminal": "ATTEMPT_FAILED",
                    "transportCorrelationId": None,
                    "networkBytes": 0,
                }, success]
                for phase in proof["transportPhases"]:
                    phase["elapsedRealtimeNs"] += 1_000_000
                proof["firstBrokerProgressElapsedRealtimeNs"] += 1_000_000
                proof["chainTerminatedElapsedRealtimeNs"] += 1_000_000
                trial["metrics"]["firstByteUs"] = (
                    proof["firstBrokerProgressElapsedRealtimeNs"] - chain_start
                ) // 1_000
                trial["metrics"]["completionUs"] = (
                    proof["chainTerminatedElapsedRealtimeNs"] - chain_start
                ) // 1_000
                trial["recovery"]["ownerCount"] = 2
                proof["bindingTargetResolutionCount"] = 2
                proof["recoveryFailureCount"] = 1
                proof["recoveryFailures"] = [{
                    "fetchId": f"fetch-pre-origin-timeout-{trial_id}",
                    "attemptCorrelationId": None,
                    "observation": {
                        "plane": "TRANSPORT",
                        "type": "TRANSPORT_IO",
                        "kind": "CONNECT_TIMEOUT",
                    },
                    "classification": "TRANSIENT_TRANSPORT",
                    "decision": {"kind": "RETRY_AFTER_BACKOFF"},
                    "action": {"kind": "SCHEDULE_BACKOFF"},
                }]
                proof["recoveryBackoffs"] = [{
                    "retryOrdinal": 1,
                    "windowMs": 500,
                    "delayMs": 367,
                }]
                proof["recoveryJitterSampleCount"] = 1
                proof["recoveryJitterSamples"] = [367]
                write_json(raw_path, raw)

            trials, _, result = case.verify()
            self.assertTrue(result["recoveryEquivalent"])
            for finalized in trials["trials"]:
                self.assertEqual(1, finalized["recovery"]["originRequestCount"])
                self.assertEqual(0, finalized["recovery"]["internalRetryCount"])
        finally:
            case.close()

    def test_recovery_jitter_reference_vectors_are_frozen(self):
        windows = [500, 1000, 2000, 4000, 5000]
        self.assertEqual(
            [367, 411, 1655, 2005, 4312],
            [
                verifier.reference_recovery_jitter(window, index)
                for index, window in enumerate(windows)
            ],
        )

    def test_intermediate_origin_disconnect_is_valid_but_not_terminal_success(self):
        row = origin_row(7)
        row["bodyBytesWritten"] = 16_384
        row["outcome"] = "CLIENT_DISCONNECTED"
        verifier.validate_origin_row(row, trial_id="trial")
        with self.assertRaisesRegex(
            verifier.NetworkEvidenceError,
            "terminal successful owner origin did not succeed",
        ):
            verifier.validate_successful_origin_row(row, trial_id="trial")

    def test_internal_retry_visibility_stays_opaque_when_extra_get_is_ambiguous(self):
        visibility, count = verifier.derive_internal_retry_visibility(
            physical_owner_count=2,
            correlated_owner_count=1,
            origin_request_count=2,
        )
        self.assertEqual("OPAQUE", visibility)
        self.assertIsNone(count)

    def test_internal_retry_visibility_counts_extra_get_when_all_owners_correlate(self):
        visibility, count = verifier.derive_internal_retry_visibility(
            physical_owner_count=1,
            correlated_owner_count=1,
            origin_request_count=2,
        )
        self.assertEqual("OBSERVABLE", visibility)
        self.assertEqual(1, count)

    def test_recovery_failure_must_bind_the_failed_physical_owner(self):
        case = NetworkCase("N2")
        try:
            for schedule_row in case.schedule:
                trial_id = schedule_row["trialId"]
                path = case.raw_dir / f"{trial_id}.json"
                raw = json.loads(path.read_text())
                proof = raw["proof"]
                trial = raw["trial"]
                original = proof["physicalAttempts"][0]
                chain_start = proof["chainStartedElapsedRealtimeNs"]
                failed = {
                    "fetchId": f"fetch-failed-{trial_id}",
                    "startElapsedRealtimeNs": chain_start + 100_000,
                    "endElapsedRealtimeNs": chain_start + 500_000,
                    "terminal": "ATTEMPT_FAILED",
                    "transportCorrelationId": None,
                    "networkBytes": 0,
                }
                success = copy.deepcopy(original)
                success["startElapsedRealtimeNs"] += 1_000_000
                success["endElapsedRealtimeNs"] += 1_000_000
                proof["physicalAttempts"] = [failed, success]
                for phase in proof["transportPhases"]:
                    phase["elapsedRealtimeNs"] += 1_000_000
                proof["firstBrokerProgressElapsedRealtimeNs"] += 1_000_000
                proof["chainTerminatedElapsedRealtimeNs"] += 1_000_000
                trial["metrics"]["firstByteUs"] = (
                    proof["firstBrokerProgressElapsedRealtimeNs"] - chain_start
                ) // 1_000
                trial["metrics"]["completionUs"] = (
                    proof["chainTerminatedElapsedRealtimeNs"] - chain_start
                ) // 1_000
                trial["recovery"]["ownerCount"] = 2
                proof["bindingTargetResolutionCount"] = 2
                proof["recoveryFailureCount"] = 1
                proof["recoveryFailures"] = [{
                    "fetchId": "wrong-fetch-id",
                    "attemptCorrelationId": None,
                    "observation": {
                        "plane": "TRANSPORT",
                        "type": "TRANSPORT_IO",
                        "kind": "CONNECT_TIMEOUT",
                    },
                    "classification": "TRANSIENT_TRANSPORT",
                    "decision": {"kind": "RETRY_AFTER_BACKOFF"},
                    "action": {"kind": "SCHEDULE_BACKOFF"},
                }]
                proof["recoveryBackoffs"] = [{
                    "retryOrdinal": 1,
                    "windowMs": 500,
                    "delayMs": 367,
                }]
                proof["recoveryJitterSampleCount"] = 1
                proof["recoveryJitterSamples"] = [367]
                write_json(path, raw)
            with self.assertRaisesRegex(
                verifier.NetworkEvidenceError,
                "recovery failure fetchId drift",
            ):
                case.verify()
        finally:
            case.close()

    def test_provider_failure_cannot_masquerade_as_network_recovery(self):
        bad = [{
            "observation": {"plane": "PROVIDER", "type": "HTTP_RESPONSE"},
            "classification": "PROVIDER_TRANSIENT_RESPONSE",
            "decision": {"kind": "RETRY_AFTER_BACKOFF"},
            "action": {"kind": "SCHEDULE_BACKOFF"},
        }]
        with self.assertRaisesRegex(verifier.NetworkEvidenceError, "TRANSPORT observation plane"):
            verifier.validate_failure_lineage(bad, trial_id="trial")


if __name__ == "__main__":
    unittest.main()
