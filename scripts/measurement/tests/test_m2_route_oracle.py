"""M2-B route-events-v1 verifier and falsification suite.

The examples under `.work/schemas/examples/m2/route-events-v1-*.example.json`
are artifacts produced by the production Kotlin reducer
(`RouteEvidenceHostTest`). Each negative test mutates one of them and names the
M2-B falsification item it covers.
"""

import copy
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

SCRIPT_DIR = pathlib.Path(__file__).resolve().parents[1]
REPO_ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from m2_route_oracle import RouteOracleError, verify_route_events  # noqa: E402
from schema_subset import validate_instance, validate_schema_definition  # noqa: E402

SCHEMAS = REPO_ROOT / ".work" / "schemas"
EXAMPLES = SCHEMAS / "examples" / "m2"
APIS = (23, 28, 34, 36)


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


def artifact(api):
    return load(EXAMPLES / f"route-events-v1-api{api}.example.json")


def event(document, signal, ref=None, disposition=None):
    for item in document["events"]:
        if item["signal"] != signal:
            continue
        if ref is not None and item["platformRouteRef"] != ref:
            continue
        if disposition is not None and item["disposition"] != disposition:
            continue
        return item
    raise AssertionError(f"no {signal}/{ref}/{disposition} event in example")


def policy(document, reason):
    for row in document["policyEvaluations"]:
        if row["reason"] == reason:
            return row
    raise AssertionError(f"no {reason} policy row in example")


class RouteSchemaTest(unittest.TestCase):
    def test_schemas_parse_and_examples_validate(self):
        events_schema = load(SCHEMAS / "route-events-v1.schema.json")
        summary_schema = load(SCHEMAS / "route-verification-summary-v1.schema.json")
        for schema in (events_schema, summary_schema):
            validate_schema_definition(schema)
            self.assertEqual(
                "https://json-schema.org/draft/2020-12/schema", schema["$schema"]
            )
        for api in APIS:
            with self.subTest(api=api):
                validate_instance(events_schema, artifact(api))
        validate_instance(
            summary_schema,
            load(EXAMPLES / "route-verification-summary-v1.example.json"),
        )

    def test_committed_summary_matches_verifier_output(self):
        self.assertEqual(
            load(EXAMPLES / "route-verification-summary-v1.example.json"),
            verify_route_events(artifact(34)),
        )


class RouteOracleAcceptanceTest(unittest.TestCase):
    def test_runtime_examples_replay_exactly(self):
        for api in APIS:
            with self.subTest(api=api):
                summary = verify_route_events(artifact(api), expected_api=api)
                self.assertEqual("PASS", summary["status"])
                self.assertIn("MONITOR_LIFECYCLE", summary["sourcesObserved"])

    def test_sources_follow_api(self):
        self.assertEqual(
            ["API23_ACTIVE_NETWORK_SNAPSHOT", "MONITOR_LIFECYCLE"],
            verify_route_events(artifact(23))["sourcesObserved"],
        )
        self.assertIn(
            "DEFAULT_NETWORK_CALLBACK",
            verify_route_events(artifact(34))["sourcesObserved"],
        )

    def test_expected_api_is_enforced(self):
        with self.assertRaises(RouteOracleError):
            verify_route_events(artifact(34), expected_api=36)

    def test_summary_states_the_m2f_limitation(self):
        limitations = " ".join(verify_route_events(artifact(36))["limitations"])
        self.assertIn("does not prove that a real media fetch is paused", limitations)
        self.assertIn("M2-F", limitations)

    def test_cli_writes_summary_and_fails_closed(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            good = root / "good.json"
            good.write_text(json.dumps(artifact(34)), encoding="utf-8")
            command = [
                sys.executable,
                str(SCRIPT_DIR / "m2_route_oracle.py"),
                "verify",
                "--input",
                str(good),
                "--output",
                str(root / "summary.json"),
                "--expected-api",
                "34",
                "--require-source",
                "DEFAULT_NETWORK_CALLBACK",
            ]
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("PASS", load(root / "summary.json")["status"])

            bad = artifact(34)
            bad["events"].pop()
            (root / "bad.json").write_text(json.dumps(bad), encoding="utf-8")
            command[4] = str(root / "bad.json")
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(1, result.returncode)

            command[4] = str(good)
            command[-1] = "API23_ACTIVE_NETWORK_SNAPSHOT"
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(1, result.returncode)


class RouteFalsificationTest(unittest.TestCase):
    def assertRejected(self, document, fragment=None):
        with self.assertRaises(RouteOracleError) as caught:
            verify_route_events(document)
        if fragment is not None:
            self.assertIn(fragment, str(caught.exception))

    def test_01_initializing_replaced_by_unavailable(self):
        document = artifact(34)
        document["events"][0]["runtimeStateAfter"]["state"] = "UNAVAILABLE"
        self.assertRejected(document, "oracle")
        document = artifact(34)
        first = document["policyEvaluations"][0]
        first["reason"] = "NO_USABLE_DEFAULT"
        self.assertRejected(document, "INITIALIZING")

    def test_02_api23_artifact_claims_default_callback(self):
        document = artifact(34)
        document["androidApi"] = 23
        self.assertRejected(document, "API 23")
        legacy = artifact(23)
        snapshot = event(legacy, "LEGACY_SNAPSHOT")
        snapshot["source"] = "DEFAULT_NETWORK_CALLBACK"
        self.assertRejected(legacy)
        modern = artifact(34)
        modern["events"][1]["source"] = "API23_ACTIVE_NETWORK_SNAPSHOT"
        modern["events"][1]["signal"] = "LEGACY_SNAPSHOT"
        self.assertRejected(modern, "CONNECTIVITY_ACTION")

    def test_03_api27_claims_suspended(self):
        document = artifact(34)
        document["androidApi"] = 27
        self.assertRejected(document, "suspended")

    def test_04_api28_claims_blocked(self):
        document = artifact(34)
        document["androidApi"] = 28
        self.assertRejected(document, "blocked")
        runtime_only = artifact(28)
        runtime_only["events"][-1]["runtimeStateAfter"]["blocked"] = "FALSE"
        self.assertRejected(runtime_only, "blocked")

    def test_05_new_route_inherits_old_capabilities(self):
        document = artifact(34)
        replacement = event(document, "AVAILABLE", "p2")
        replacement["runtimeStateAfter"].update(vpn="TRUE", validated="TRUE")
        self.assertRejected(document, "runtime state")

    def test_06_capability_change_increments_epoch(self):
        document = artifact(34)
        change = event(document, "CAPABILITIES_CHANGED", "p1", "APPLIED")
        change["routeEpochAfter"] = 2
        change["runtimeStateAfter"]["routeEpoch"] = 2
        self.assertRejected(document, "routeEpochAfter")

    def test_07_replacement_keeps_epoch(self):
        document = artifact(34)
        replacement = event(document, "AVAILABLE", "p2")
        replacement["routeEpochAfter"] = 1
        replacement["runtimeStateAfter"]["routeEpoch"] = 1
        self.assertRejected(document, "routeEpochAfter")

    def test_08_stale_old_route_capability_mutates_state(self):
        document = artifact(34)
        stale = event(document, "CAPABILITIES_CHANGED", "p1", "STALE_IGNORED")
        stale["disposition"] = "APPLIED"
        stale["runtimeStateAfter"].update(
            capabilitiesReceived=True,
            **stale["observedCapabilities"],
        )
        self.assertRejected(document, "disposition")
        document = artifact(34)
        stale = event(document, "CAPABILITIES_CHANGED", "p1", "STALE_IGNORED")
        stale["runtimeStateAfter"]["vpn"] = "TRUE"
        self.assertRejected(document, "runtime state")

    def test_09_unknown_vpn_treated_as_direct(self):
        document = artifact(28)
        row = policy(document, "SESSION_ROUTE_UNRESOLVED")
        row.update(
            guardAfter="SYSTEM_DEFAULT_ALLOWED",
            decision="ALLOW",
            reason="ROUTE_READY",
        )
        self.assertRejected(document, "guardAfter")

    def test_10_vpn_required_session_allows_direct(self):
        document = artifact(34)
        row = policy(document, "VPN_CONTINUITY_REQUIRED")
        row.update(decision="ALLOW", reason="ROUTE_READY")
        self.assertRejected(document, "runtime decision")

    def test_11_direct_start_escalated_after_transient_vpn(self):
        document = artifact(36)
        rows = document["policyEvaluations"]
        rows[1]["guardAfter"] = "VPN_CONTINUITY_REQUIRED"
        rows[2].update(
            guardBefore="VPN_CONTINUITY_REQUIRED",
            guardAfter="VPN_CONTINUITY_REQUIRED",
            decision="PAUSE",
            reason="VPN_CONTINUITY_REQUIRED",
        )
        self.assertRejected(document, "guardAfter")

    def test_12_explicit_override_allows_blocked_route(self):
        document = artifact(34)
        row = policy(document, "NETWORK_BLOCKED")
        row.update(decision="ALLOW", reason="EXPLICIT_DIRECT_OVERRIDE")
        self.assertRejected(document, "runtime decision")
        document = artifact(34)
        row = policy(document, "NO_USABLE_DEFAULT")
        row.update(decision="ALLOW", reason="EXPLICIT_DIRECT_OVERRIDE")
        self.assertRejected(document, "runtime decision")

    def test_13_runtime_policy_disagrees_with_oracle(self):
        document = artifact(34)
        policy(document, "CAPABILITIES_PENDING")["reason"] = "INITIALIZING"
        self.assertRejected(document, "runtime decision")
        document = artifact(34)
        document["policyEvaluations"][3]["routeEventSequenceWatermark"] = 6
        self.assertRejected(document)
        document = artifact(34)
        document["policyEvaluations"][4]["explicitDirectOverride"] = False
        self.assertRejected(document, "runtime decision")

    def test_14_evidence_contains_platform_or_location_identity(self):
        for key, value in (
            ("network", "100"),
            ("netId", 100),
            ("ipAddress", "10.0.2.15"),
            ("ssid", "home"),
            ("bssid", "02:00:00:00:00:00"),
            ("interfaceName", "wlan0"),
        ):
            with self.subTest(key=key):
                document = artifact(34)
                document["events"][3][key] = value
                self.assertRejected(document)
        document = artifact(34)
        event(document, "AVAILABLE", "p2")["platformRouteRef"] = "100"
        self.assertRejected(document, "schema")
        document = artifact(34)
        document["runId"] = "10.0.2.15"
        self.assertRejected(document, "IPv4")

    def test_15_route_events_out_of_order(self):
        document = artifact(34)
        events = document["events"]
        events[6], events[7] = events[7], events[6]
        self.assertRejected(document, "sequence")
        document = artifact(34)
        document["events"][4]["sequence"] = 99
        self.assertRejected(document, "sequence")

    def test_16_android_monotonic_timestamp_decreases(self):
        document = artifact(34)
        events = document["events"]
        events[5]["elapsedRealtimeNs"] = events[4]["elapsedRealtimeNs"] - 1
        self.assertRejected(document, "ANDROID_MONOTONIC")
        document = artifact(34)
        document["clockDomain"] = "HOST_MEDIA_LAB_MONOTONIC"
        self.assertRejected(document, "schema")

    def test_17_complete_run_without_shutdown_event(self):
        document = artifact(34)
        document["events"].pop()
        self.assertRejected(document, "MONITOR_STOPPED")
        document = artifact(34)
        document["events"].pop(0)
        self.assertRejected(document, "MONITOR_STARTED")

    def test_lost_event_for_old_route_does_not_end_current_epoch(self):
        document = artifact(34)
        stale = event(document, "LOST", "p1")
        stale.update(disposition="APPLIED", routeEpochAfter=None)
        stale["runtimeStateAfter"] = {
            "state": "UNAVAILABLE",
            "routeEpoch": None,
            "capabilitiesReceived": False,
            **{k: "UNKNOWN" for k in (
                "internet", "validated", "vpn", "metered", "restricted",
                "blocked", "suspended",
            )},
        }
        self.assertRejected(document, "disposition")

    def test_link_properties_values_are_not_representable(self):
        document = artifact(34)
        event(document, "LINK_PROPERTIES_CHANGED")["observedCapabilities"] = copy.deepcopy(
            event(document, "CAPABILITIES_CHANGED")["observedCapabilities"]
        )
        self.assertRejected(document, "carries no capabilities")
        document = artifact(34)
        event(document, "LINK_PROPERTIES_CHANGED")["linkProperties"] = {"dns": []}
        self.assertRejected(document)


if __name__ == "__main__":
    unittest.main()
