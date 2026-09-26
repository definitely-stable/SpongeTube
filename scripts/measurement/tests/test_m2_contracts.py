"""M2-A contract and falsification suite (.work/milestones/M2.md section 18.3).

Each negative test names the falsification checklist item it covers.
"""

import copy
import hashlib
import json
import pathlib
import sys
import unittest

SCRIPT_DIR = pathlib.Path(__file__).resolve().parents[1]
REPO_ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from m2_contracts import (
    ACCEPTED_M2_SCHEMA_SHA256,
    HISTORICAL_SCHEMA_SHA256,
    M2_SLICE_SCHEMAS,
    MAX_RETRY_AFTER_DELAY_SECONDS,
    PLANE_FAULT_FIELDS,
    M2ContractError,
    canonicalize_scenario,
    ROUTE_CAPABILITIES,
    check_capability_api_floor,
    check_clock_relation,
    check_route_capability_claim,
    classify_http_contract_case,
    evaluate_external_fetch_route,
    known_true,
    parse_retry_after,
    provider_wait_ms,
    resolve_session_route_guard,
    scan_evidence_privacy,
    scenario_sha256,
    validate_delivery_rebinding,
    validate_failure_record,
    validate_persisted_identity_invariance,
    validate_recovery_ledger,
    validate_route_privacy_transition,
    validate_run_manifest_semantics,
    validate_scenario_semantics,
)
from schema_subset import (
    SchemaContractError,
    validate_instance,
    validate_schema_definition,
)


SCHEMAS = REPO_ROOT / ".work" / "schemas"
EXAMPLES = SCHEMAS / "examples" / "m2"

SCENARIO_SCHEMA = "m2-scenario-v1.schema.json"
MANIFEST_SCHEMA = "m2-run-manifest-v1.schema.json"

CONTRACTS = {
    SCENARIO_SCHEMA: [
        "m2-scenario-n5-burst-loss-v1.example.json",
        "m2-scenario-n7-vpn-route-loss-v1.example.json",
    ],
    MANIFEST_SCHEMA: ["m2-run-manifest-v1.example.json"],
    "failure-decision-events-v2.schema.json": [
        "failure-decision-v2.example.json",
    ],
    "delivery-binding-events-v1.schema.json": [
        "delivery-binding-events-v1.example.json",
    ],
    "provider-fault-events-v1.schema.json": [
        "provider-fault-events-v1.example.json",
    ],
    "provider-verification-summary-v1.schema.json": [
        "provider-verification-summary-v1.example.json",
    ],
}


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


def schema(name):
    return load(SCHEMAS / name)


def example(name):
    return load(EXAMPLES / name)


def n5():
    return example("m2-scenario-n5-burst-loss-v1.example.json")


def n7():
    return example("m2-scenario-n7-vpn-route-loss-v1.example.json")


def manifest():
    return example("m2-run-manifest-v1.example.json")


def empty_scenario(family, variant, plane):
    scenario = {
        "schemaVersion": 1,
        "scenarioFamily": family,
        "variant": variant,
        "primaryPlane": plane,
        "randomSeed": None,
        "requiresActualDefaultNetwork": False,
    }
    for field in PLANE_FAULT_FIELDS.values():
        scenario[field] = []
    return scenario


def fault(fault_id, plane, kind, *, stochastic=False, **parameters):
    return {
        "faultId": fault_id,
        "plane": plane,
        "kind": kind,
        "stochastic": stochastic,
        "parameters": parameters,
    }


def route(state="AVAILABLE", *, vpn="FALSE", validated="TRUE", received=True,
          **capabilities):
    available = state == "AVAILABLE"
    value = {
        "state": state,
        "routeEpoch": 2 if available else None,
        "capabilitiesReceived": available and received,
        "internet": "TRUE",
        "validated": validated,
        "vpn": vpn,
        "metered": "UNKNOWN",
        "restricted": "FALSE",
        "blocked": "UNKNOWN",
        "suspended": "UNKNOWN",
    }
    if not value["capabilitiesReceived"]:
        for name in ROUTE_CAPABILITIES:
            value[name] = "UNKNOWN"
    value.update(capabilities)
    return value


class M2SchemaContractTest(unittest.TestCase):
    def test_schemas_parse_and_examples_validate(self):
        for schema_name, example_names in CONTRACTS.items():
            loaded = schema(schema_name)
            validate_schema_definition(loaded)
            self.assertEqual(
                "https://json-schema.org/draft/2020-12/schema",
                loaded["$schema"],
            )
            for example_name in example_names:
                with self.subTest(schema=schema_name, example=example_name):
                    document = example(example_name)
                    self.assertEqual(
                        loaded["properties"]["schemaVersion"]["const"],
                        document["schemaVersion"],
                    )
                    validate_instance(loaded, document)
                    scan_evidence_privacy(document)

    def test_examples_are_semantically_valid_and_bound(self):
        validate_scenario_semantics(n5())
        validate_scenario_semantics(n7())
        validate_run_manifest_semantics(manifest(), n7())

    def test_unsupported_schema_keyword_fails(self):
        broken = schema(SCENARIO_SCHEMA)
        broken["properties"]["variant"]["oneOf"] = [{"type": "string"}]
        with self.assertRaises(SchemaContractError):
            validate_schema_definition(broken)

    def test_missing_required_field_fails(self):
        for field in ("randomSeed", "primaryPlane", "routeFaults"):
            with self.subTest(field=field):
                broken = n5()
                del broken[field]
                with self.assertRaises(SchemaContractError):
                    validate_instance(schema(SCENARIO_SCHEMA), broken)
        broken = manifest()
        del broken["limitations"]
        with self.assertRaises(SchemaContractError):
            validate_instance(schema(MANIFEST_SCHEMA), broken)

    def test_unknown_additional_field_fails(self):
        broken = n5()
        broken["label"] = "anything"
        with self.assertRaises(SchemaContractError):
            validate_instance(schema(SCENARIO_SCHEMA), broken)
        broken = n5()
        broken["networkFaults"][0]["ownerPlane"] = "TRANSPORT"
        with self.assertRaises(SchemaContractError):
            validate_instance(schema(SCENARIO_SCHEMA), broken)
        broken = manifest()
        broken["transport"]["url"] = "opaque"
        with self.assertRaises(SchemaContractError):
            validate_instance(schema(MANIFEST_SCHEMA), broken)

    def test_invalid_hashes_fail(self):
        for bad in ("abc", "C" * 64, "g" * 64):
            with self.subTest(bad=bad):
                broken = manifest()
                broken["scenario"]["hash"] = bad
                with self.assertRaises(SchemaContractError):
                    validate_instance(schema(MANIFEST_SCHEMA), broken)
        broken = manifest()
        broken["gitCommit"] = "not-a-commit"
        with self.assertRaises(SchemaContractError):
            validate_instance(schema(MANIFEST_SCHEMA), broken)

    def test_fault_in_wrong_plane_list_fails_schema(self):
        # checklist 3: a fault's declared plane must equal its owning list.
        broken = n5()
        broken["networkFaults"][0]["plane"] = "TRANSPORT"
        with self.assertRaises(SchemaContractError):
            validate_instance(schema(SCENARIO_SCHEMA), broken)

    def test_transport_backend_is_opaque_versioned_not_closed_enum(self):
        document = manifest()
        for backend in ("platform.httpengine", "okhttp", "cronet.embedded"):
            document["transport"] = {"backendId": backend, "backendVersion": "1"}
            validate_instance(schema(MANIFEST_SCHEMA), document)
        document["transport"] = {"backendId": "HTTP ENGINE", "backendVersion": None}
        with self.assertRaises(SchemaContractError):
            validate_instance(schema(MANIFEST_SCHEMA), document)

    def test_m1_run_manifest_is_not_an_m2_manifest(self):
        # checklist 18: M2 does not reinterpret an M1 artifact.
        m1 = load(SCHEMAS / "examples" / "m1" / "m1-run-manifest-v1.example.json")
        with self.assertRaises(SchemaContractError):
            validate_instance(schema(MANIFEST_SCHEMA), m1)


class M2HistoricalContractTest(unittest.TestCase):
    def test_pre_m2_schemas_are_not_rewritten(self):
        # checklist 18: historical M0/M1 schemas keep their exact bytes.
        present = {
            path.name
            for path in SCHEMAS.glob("*.schema.json")
            if not path.name.startswith("m2-")
            and path.name not in M2_SLICE_SCHEMAS
            and path.name not in ACCEPTED_M2_SCHEMA_SHA256
        }
        self.assertEqual(set(HISTORICAL_SCHEMA_SHA256), present)
        self.assertFalse(M2_SLICE_SCHEMAS & set(HISTORICAL_SCHEMA_SHA256))
        for name in M2_SLICE_SCHEMAS:
            self.assertTrue((SCHEMAS / name).is_file(), name)
        for name, digest in HISTORICAL_SCHEMA_SHA256.items():
            with self.subTest(schema=name):
                actual = hashlib.sha256((SCHEMAS / name).read_bytes()).hexdigest()
                self.assertEqual(digest, actual)

    def test_accepted_m2a_and_m2b_schemas_are_not_rewritten(self):
        # M2-C adds failure-decision/recovery-budget schemas; M2-A/M2-B
        # contracts keep their exact bytes.
        for name, digest in ACCEPTED_M2_SCHEMA_SHA256.items():
            with self.subTest(schema=name):
                actual = hashlib.sha256((SCHEMAS / name).read_bytes()).hexdigest()
                self.assertEqual(digest, actual)
        registered = set(ACCEPTED_M2_SCHEMA_SHA256) | M2_SLICE_SCHEMAS
        m2_present = {
            path.name
            for path in SCHEMAS.glob("*.schema.json")
            if path.name not in HISTORICAL_SCHEMA_SHA256
        }
        self.assertEqual(registered, m2_present)

    def test_m2_contract_suite_does_not_alter_m1_examples(self):
        for path in sorted((SCHEMAS / "examples" / "m1").glob("*.json")):
            with self.subTest(example=path.name):
                self.assertNotIn(
                    "primaryPlane", path.read_text(encoding="utf-8")
                )


class M2ScenarioIdentityTest(unittest.TestCase):
    def test_same_canonical_scenario_same_hash(self):
        self.assertEqual(scenario_sha256(n5()), scenario_sha256(n5()))

    def test_key_ordering_does_not_change_hash(self):
        original = n5()
        reordered = dict(reversed(list(original.items())))
        reordered["networkFaults"] = [
            dict(reversed(list(item.items())))
            for item in original["networkFaults"]
        ]
        self.assertEqual(scenario_sha256(original), scenario_sha256(reordered))
        self.assertEqual(
            canonicalize_scenario(original), canonicalize_scenario(reordered)
        )

    def test_fault_list_order_does_not_change_hash(self):
        first = n7()
        second = n7()
        second["routeFaults"].append(
            fault("a-second-route-fault", "ROUTE", "VALIDATION_LOSS")
        )
        first["routeFaults"].insert(
            0, fault("a-second-route-fault", "ROUTE", "VALIDATION_LOSS")
        )
        self.assertEqual(scenario_sha256(first), scenario_sha256(second))

    def test_seed_change_changes_hash(self):
        # checklist 2
        changed = n5()
        changed["randomSeed"] += 1
        self.assertNotEqual(scenario_sha256(n5()), scenario_sha256(changed))

    def test_fault_parameter_change_changes_hash(self):
        changed = n5()
        changed["networkFaults"][0]["parameters"]["lossPpm"] += 1
        self.assertNotEqual(scenario_sha256(n5()), scenario_sha256(changed))

    def test_plane_and_variant_change_hash(self):
        base = empty_scenario("N3", "BURST_DELIVERY_BLACKOUT", "DELIVERY")
        base["deliveryFaults"] = [fault("burst", "DELIVERY", "BURST_BLACKOUT")]
        base["networkFaults"] = [fault("loss", "NETWORK", "BURST_LOSS")]
        other_plane = copy.deepcopy(base)
        other_plane["primaryPlane"] = "NETWORK"
        other_variant = copy.deepcopy(base)
        other_variant["variant"] = "BURST_DELIVERY_BLACKOUT_LONG"
        for candidate in (other_plane, other_variant):
            validate_scenario_semantics(candidate)
            self.assertNotEqual(scenario_sha256(base), scenario_sha256(candidate))

    def test_non_integer_numbers_are_not_canonical(self):
        broken = n5()
        broken["networkFaults"][0]["parameters"]["lossPpm"] = 0.5
        with self.assertRaises(M2ContractError):
            scenario_sha256(broken)

    def test_stochastic_network_fault_without_seed_rejected(self):
        # checklist 1
        broken = n5()
        broken["randomSeed"] = None
        with self.assertRaises(M2ContractError):
            validate_scenario_semantics(broken)

    def test_one_fault_two_primary_planes_rejected(self):
        # checklist 3
        broken = n5()
        broken["transportFaults"] = [
            dict(broken["networkFaults"][0], plane="TRANSPORT")
        ]
        with self.assertRaises(M2ContractError):
            validate_scenario_semantics(broken)
        mislabelled = n5()
        mislabelled["networkFaults"][0]["plane"] = "DELIVERY"
        with self.assertRaises(M2ContractError):
            validate_scenario_semantics(mislabelled)

    def test_n3_and_n6_need_plane_and_variant(self):
        for family, variant, plane, kind in (
            ("N3", "BURST_DELIVERY_BLACKOUT", "DELIVERY", "BURST_BLACKOUT"),
            ("N3", "BURST_PACKET_LOSS", "NETWORK", "BURST_LOSS"),
            ("N6", "TRANSPORT_RESET", "TRANSPORT", "CONNECTION_RESET"),
            ("N6", "DEFAULT_ROUTE_REPLACEMENT", "ROUTE", "DEFAULT_REPLACEMENT"),
        ):
            with self.subTest(family=family, variant=variant):
                scenario = empty_scenario(family, variant, plane)
                scenario[PLANE_FAULT_FIELDS[plane]] = [
                    fault("f", plane, kind)
                ]
                scenario["requiresActualDefaultNetwork"] = plane == "ROUTE"
                validate_scenario_semantics(scenario)

        transport_reset = empty_scenario("N6", "TRANSPORT_RESET", "TRANSPORT")
        transport_reset["transportFaults"] = [
            fault("f", "TRANSPORT", "CONNECTION_RESET")
        ]
        route_replacement = empty_scenario(
            "N6", "DEFAULT_ROUTE_REPLACEMENT", "ROUTE"
        )
        route_replacement["routeFaults"] = [
            fault("f", "ROUTE", "DEFAULT_REPLACEMENT")
        ]
        route_replacement["requiresActualDefaultNetwork"] = True
        self.assertNotEqual(
            scenario_sha256(transport_reset), scenario_sha256(route_replacement)
        )

    def test_family_plane_incompatibility_rejected(self):
        for family, plane in (("N6", "NETWORK"), ("N3", "TRANSPORT"),
                              ("N7", "TRANSPORT"), ("N11", "PROVIDER")):
            with self.subTest(family=family, plane=plane):
                scenario = empty_scenario(family, "X", plane)
                scenario[PLANE_FAULT_FIELDS[plane]] = [fault("f", plane, "X")]
                with self.assertRaises(M2ContractError):
                    validate_scenario_semantics(scenario)

    def test_primary_plane_without_fault_rejected(self):
        scenario = empty_scenario("N6", "TRANSPORT_RESET", "TRANSPORT")
        with self.assertRaises(M2ContractError):
            validate_scenario_semantics(scenario)

    def test_route_proof_requires_actual_default_network(self):
        # N7-like: route/VPN claim with requiresActualDefaultNetwork=false.
        broken = n7()
        broken["requiresActualDefaultNetwork"] = False
        with self.assertRaises(M2ContractError):
            validate_scenario_semantics(broken)
        hidden_route = n5()
        hidden_route["routeFaults"] = [fault("r", "ROUTE", "VPN_LOSS")]
        with self.assertRaises(M2ContractError):
            validate_scenario_semantics(hidden_route)

    def test_route_run_on_non_default_network_path_rejected(self):
        # checklist 4
        for path in ("ADB_REVERSE", "HOST_ONLY"):
            with self.subTest(path=path):
                broken = manifest()
                broken["mediaPath"] = path
                validate_instance(schema(MANIFEST_SCHEMA), broken)
                with self.assertRaises(M2ContractError):
                    validate_run_manifest_semantics(broken, n7())

    def test_manifest_must_bind_exact_scenario_hash(self):
        changed = n7()
        changed["routeFaults"][0]["parameters"]["restoreAfterMs"] += 1
        with self.assertRaises(M2ContractError):
            validate_run_manifest_semantics(manifest(), changed)

    def test_faulted_plane_requires_single_owning_harness(self):
        missing = manifest()
        missing["faultHarnesses"] = missing["faultHarnesses"][:1]
        with self.assertRaises(M2ContractError):
            validate_run_manifest_semantics(missing, n7())
        duplicate = manifest()
        duplicate["faultHarnesses"].append(
            dict(duplicate["faultHarnesses"][1], harnessId="another")
        )
        with self.assertRaises(M2ContractError):
            validate_run_manifest_semantics(duplicate, n7())


class M2RoutePrivacyTest(unittest.TestCase):
    def decide(self, guard, target, override=False):
        return evaluate_external_fetch_route(
            guard, target, explicit_direct_override=override
        )

    def test_guard_resolves_from_observed_vpn_only(self):
        self.assertEqual(
            "VPN_CONTINUITY_REQUIRED",
            resolve_session_route_guard("UNRESOLVED", route(vpn="TRUE")),
        )
        self.assertEqual(
            "SYSTEM_DEFAULT_ALLOWED",
            resolve_session_route_guard("UNRESOLVED", route(vpn="FALSE")),
        )
        for unresolved in (
            route("INITIALIZING"),
            route("UNAVAILABLE"),
            route(received=False),
            route(vpn="UNKNOWN"),
        ):
            with self.subTest(route=unresolved):
                self.assertEqual(
                    "UNRESOLVED",
                    resolve_session_route_guard("UNRESOLVED", unresolved),
                )

    def test_unresolved_guard_pauses(self):
        # checklist 23
        guard, decision, reason = self.decide("UNRESOLVED", route(vpn="UNKNOWN"))
        self.assertEqual(("UNRESOLVED", "PAUSE"), (guard, decision))
        self.assertEqual("SESSION_ROUTE_UNRESOLVED", reason)
        with self.assertRaises(M2ContractError):
            validate_route_privacy_transition(
                guard="UNRESOLVED",
                new_route=route(vpn="UNKNOWN"),
                external_fetch_decision="ALLOW",
            )

    def test_initializing_is_not_unavailable(self):
        # checklist 21
        self.assertEqual(
            "INITIALIZING", self.decide("UNRESOLVED", route("INITIALIZING"))[2]
        )
        self.assertEqual(
            "NO_USABLE_DEFAULT", self.decide("UNRESOLVED", route("UNAVAILABLE"))[2]
        )
        with self.assertRaises(M2ContractError):
            known_true({"state": "NO_DEFAULT", "vpn": "UNKNOWN"}, "vpn")

    def test_vpn_to_same_or_other_vpn_remains_policy_eligible(self):
        for epoch in (1, 2):
            with self.subTest(routeEpoch=epoch):
                target = route(vpn="TRUE")
                target["routeEpoch"] = epoch
                self.assertEqual(
                    "ELIGIBLE",
                    validate_route_privacy_transition(
                        guard="VPN_CONTINUITY_REQUIRED",
                        new_route=target,
                        external_fetch_decision="ALLOW",
                    ),
                )

    def test_vpn_to_no_default_pauses(self):
        for state in ("INITIALIZING", "UNAVAILABLE"):
            with self.subTest(state=state):
                gone = route(state)
                self.assertEqual(
                    "PAUSED",
                    validate_route_privacy_transition(
                        guard="VPN_CONTINUITY_REQUIRED",
                        new_route=gone,
                        external_fetch_decision="PAUSE",
                    ),
                )
                with self.assertRaises(M2ContractError):
                    validate_route_privacy_transition(
                        guard="VPN_CONTINUITY_REQUIRED",
                        new_route=gone,
                        external_fetch_decision="ALLOW",
                    )

    def test_vpn_to_direct_auto_allow_forbidden(self):
        # checklist 6
        with self.assertRaises(M2ContractError):
            validate_route_privacy_transition(
                guard="VPN_CONTINUITY_REQUIRED",
                new_route=route(vpn="FALSE"),
                external_fetch_decision="ALLOW",
            )
        self.assertEqual(
            "PAUSED",
            validate_route_privacy_transition(
                guard="VPN_CONTINUITY_REQUIRED",
                new_route=route(vpn="FALSE"),
                external_fetch_decision="PAUSE",
            ),
        )
        self.assertEqual(
            ("VPN_CONTINUITY_REQUIRED", "PAUSE", "VPN_CONTINUITY_REQUIRED"),
            self.decide("VPN_CONTINUITY_REQUIRED", route(vpn="FALSE")),
        )

    def test_vpn_to_pending_capabilities_is_not_assumed_vpn(self):
        with self.assertRaises(M2ContractError):
            validate_route_privacy_transition(
                guard="VPN_CONTINUITY_REQUIRED",
                new_route=route(received=False),
                external_fetch_decision="ALLOW",
            )

    def test_explicit_override_lifts_only_vpn_continuity(self):
        # checklist 20
        self.assertEqual(
            "ELIGIBLE",
            validate_route_privacy_transition(
                guard="VPN_CONTINUITY_REQUIRED",
                new_route=route(vpn="FALSE"),
                external_fetch_decision="ALLOW",
                explicit_direct_override=True,
            ),
        )
        self.assertEqual(
            "EXPLICIT_DIRECT_OVERRIDE",
            self.decide("VPN_CONTINUITY_REQUIRED", route(vpn="FALSE"), True)[2],
        )
        with self.assertRaises(M2ContractError):
            validate_route_privacy_transition(
                guard="VPN_CONTINUITY_REQUIRED",
                new_route=route(vpn="FALSE"),
                external_fetch_decision="ALLOW",
            )
        for target, reason in (
            (route("UNAVAILABLE"), "NO_USABLE_DEFAULT"),
            (route(received=False), "CAPABILITIES_PENDING"),
            (route(vpn="FALSE", blocked="TRUE"), "NETWORK_BLOCKED"),
            (route(vpn="FALSE", suspended="TRUE"), "NETWORK_SUSPENDED"),
            (route(vpn="FALSE", restricted="TRUE"), "NETWORK_RESTRICTED"),
            (route(vpn="FALSE", internet="FALSE"), "NO_INTERNET_CAPABILITY"),
        ):
            with self.subTest(reason=reason):
                self.assertEqual(
                    ("PAUSE", reason),
                    self.decide("VPN_CONTINUITY_REQUIRED", target, True)[1:],
                )
                with self.assertRaises(M2ContractError):
                    validate_route_privacy_transition(
                        guard="VPN_CONTINUITY_REQUIRED",
                        new_route=target,
                        external_fetch_decision="ALLOW",
                        explicit_direct_override=True,
                    )

    def test_direct_start_is_not_escalated_by_transient_vpn(self):
        # checklist 19: direct -> VPN -> direct must not become VPN-required.
        guard = "UNRESOLVED"
        for target in (route(vpn="FALSE"), route(vpn="TRUE"), route(vpn="FALSE")):
            guard, decision, reason = self.decide(guard, target)
            self.assertEqual("SYSTEM_DEFAULT_ALLOWED", guard)
            self.assertEqual(("ALLOW", "ROUTE_READY"), (decision, reason))
        self.assertEqual(
            "ELIGIBLE",
            validate_route_privacy_transition(
                guard=guard,
                new_route=route(vpn="FALSE"),
                external_fetch_decision="ALLOW",
            ),
        )

    def test_metered_and_unvalidated_do_not_pause(self):
        for target in (
            route(metered="TRUE"),
            route(validated="FALSE"),
            route(validated="UNKNOWN"),
        ):
            with self.subTest(route=target):
                self.assertEqual(
                    "ALLOW", self.decide("SYSTEM_DEFAULT_ALLOWED", target)[1]
                )

    def test_non_vpn_to_non_vpn_not_privacy_blocked(self):
        self.assertEqual(
            "ELIGIBLE",
            validate_route_privacy_transition(
                guard="SYSTEM_DEFAULT_ALLOWED",
                new_route=route(vpn="FALSE"),
                external_fetch_decision="ALLOW",
            ),
        )

    def test_capability_api_floors(self):
        # checklist 22
        check_capability_api_floor(23, "validated", "TRUE")
        check_capability_api_floor(23, "suspended", "UNKNOWN")
        check_capability_api_floor(28, "suspended", "FALSE")
        check_capability_api_floor(29, "blocked", "FALSE")
        for api, capability, value in (
            (23, "suspended", "FALSE"),
            (27, "suspended", "TRUE"),
            (23, "blocked", "FALSE"),
            (28, "blocked", "FALSE"),
        ):
            with self.subTest(api=api, capability=capability):
                with self.assertRaises(M2ContractError):
                    check_capability_api_floor(api, capability, value)

    def test_unknown_validated_is_not_true(self):
        # checklist 5
        pending = route(received=False)
        self.assertFalse(known_true(pending, "validated"))
        with self.assertRaises(M2ContractError):
            check_route_capability_claim(pending, "validated", True)
        observed_unknown = route(validated="UNKNOWN")
        self.assertFalse(known_true(observed_unknown, "validated"))
        with self.assertRaises(M2ContractError):
            check_route_capability_claim(observed_unknown, "validated", True)
        check_route_capability_claim(route(validated="TRUE"), "validated", True)

    def test_route_transition_does_not_mutate_persisted_identity(self):
        # checklist 7
        before = [
            {"extentId": "e1", "mediaAssetId": "a", "sha256": "1" * 64,
             "length": 10},
            {"extentId": "e2", "mediaAssetId": "a", "sha256": "2" * 64,
             "length": 20},
        ]
        grown = before + [
            {"extentId": "e3", "mediaAssetId": "a", "sha256": "3" * 64,
             "length": 30}
        ]
        for transition in ("ROUTE_EPOCH_CHANGED", "VPN_LOST", "DELIVERY_REBOUND"):
            validate_persisted_identity_invariance(before, before, transition)
            validate_persisted_identity_invariance(before, grown, transition)
        mutated = copy.deepcopy(before)
        mutated[0]["sha256"] = "f" * 64
        with self.assertRaises(M2ContractError):
            validate_persisted_identity_invariance(
                before, mutated, "ROUTE_EPOCH_CHANGED"
            )
        with self.assertRaises(M2ContractError):
            validate_persisted_identity_invariance(before, before[1:], "VPN_LOST")


class M2HttpAnchorTest(unittest.TestCase):
    def test_bare_403_is_not_stale_binding(self):
        # checklist 8
        result = classify_http_contract_case(403)
        self.assertEqual("PROVIDER", result["plane"])
        self.assertEqual("PROVIDER_REJECTED", result["classification"])
        self.assertFalse(result["staleBindingPermittedByProviderPolicy"])
        with self.assertRaises(M2ContractError):
            validate_failure_record(self._record(
                {"kind": "HTTP_STATUS", "status": 403},
                "DELIVERY_BINDING_STALE",
                "REFRESH_DELIVERY_BINDING",
            ))
        with self.assertRaises(M2ContractError):
            validate_failure_record(self._record(
                {"kind": "HTTP_STATUS", "status": 403},
                "PROVIDER_REJECTED",
                "REFRESH_DELIVERY_BINDING",
            ))

    def test_403_with_provider_evidence_is_left_to_provider_policy(self):
        result = classify_http_contract_case(
            403, provider_evidence={"signal": "provider-specific"}
        )
        self.assertEqual("PROVIDER_REJECTED", result["classification"])
        self.assertTrue(result["staleBindingPermittedByProviderPolicy"])
        record = self._record(
            {"kind": "HTTP_STATUS", "status": 403},
            "DELIVERY_BINDING_STALE",
            "REFRESH_DELIVERY_BINDING",
        )
        record["providerEvidence"] = {"signal": "provider-specific"}
        validate_failure_record(record)

    def test_429_is_rate_limiting(self):
        for header in (None, "120", "Wed, 21 Oct 2026 07:28:00 GMT", "soon"):
            with self.subTest(header=header):
                result = classify_http_contract_case(429, retry_after=header)
                self.assertEqual("PROVIDER", result["plane"])
                self.assertEqual("PROVIDER_RATE_LIMITED", result["classification"])

    def test_429_as_socket_failure_rejected(self):
        # checklist 9
        for klass in ("TRANSIENT_TRANSPORT", "PROVIDER_REJECTED", "UNKNOWN"):
            with self.subTest(classification=klass):
                with self.assertRaises(M2ContractError):
                    validate_failure_record(self._record(
                        {"kind": "HTTP_STATUS", "status": 429}, klass, "WAIT"
                    ))
        with self.assertRaises(M2ContractError):
            validate_failure_record(self._record(
                {"kind": "HTTP_STATUS", "status": 429, "plane": "TRANSPORT"},
                "PROVIDER_RATE_LIMITED",
                "WAIT",
            ))
        validate_failure_record(self._record(
            {"kind": "HTTP_STATUS", "status": 429}, "PROVIDER_RATE_LIMITED", "WAIT"
        ))

    def test_retry_after_forms(self):
        self.assertEqual(
            {"source": "HTTP_HEADER", "rawKind": "DELAY_SECONDS",
             "delaySeconds": 120},
            parse_retry_after("120"),
        )
        for raw in (
            "Wed, 21 Oct 2026 07:28:00 GMT",
            "Wednesday, 21-Oct-26 07:28:00 GMT",
            "Wed Oct 21 07:28:00 2026",
        ):
            with self.subTest(raw=raw):
                parsed = parse_retry_after(raw)
                self.assertEqual("HTTP_DATE", parsed["rawKind"])
                self.assertEqual("2026-10-21T07:28:00Z", parsed["notBeforeUtc"])
                self.assertEqual("PROVIDER_WALL_CLOCK", parsed["clockDomain"])
                self.assertNotIn("delaySeconds", parsed)
        self.assertEqual("ABSENT", parse_retry_after(None)["rawKind"])

    def test_malformed_retry_after_stays_advisory(self):
        for raw in ("-1", "1.5", "tomorrow", "Thu, 21 Oct 2026 07:28:00 GMT",
                    "Wed, 21 Oct 2026 07:28:00 UTC", ""):
            with self.subTest(raw=raw):
                self.assertEqual("MALFORMED", parse_retry_after(raw)["rawKind"])
                result = classify_http_contract_case(429, retry_after=raw)
                self.assertEqual("PROVIDER", result["plane"])
                self.assertEqual("PROVIDER_RATE_LIMITED", result["classification"])

    def test_statuses_outside_anchors_are_not_classified(self):
        with self.assertRaises(M2ContractError):
            classify_http_contract_case(500)

    @staticmethod
    def _record(observation, klass, action):
        return {
            "failureId": "failure-1",
            "observation": observation,
            "classification": {"class": klass},
            "decision": {"action": action},
        }


class M2RetryAfterNormalizationTest(unittest.TestCase):
    """M2-D normalized Retry-After shape and provider wait (M2.md 10.2)."""

    def test_delay_seconds_vectors(self):
        for raw, seconds in (
            ("0", 0),
            ("120", 120),
            ("007", 7),
            ("9223372036854775", MAX_RETRY_AFTER_DELAY_SECONDS),
        ):
            with self.subTest(raw=raw):
                self.assertEqual(
                    {"source": "HTTP_HEADER", "rawKind": "DELAY_SECONDS",
                     "delaySeconds": seconds},
                    parse_retry_after(raw),
                )

    def test_only_sp_and_htab_are_trimmed(self):
        expected = {
            " 120\t": 120,
            "\t120 ": 120,
            " \t0\t ": 0,
        }
        for raw, seconds in expected.items():
            with self.subTest(raw=raw):
                self.assertEqual(
                    {"source": "HTTP_HEADER", "rawKind": "DELAY_SECONDS",
                     "delaySeconds": seconds},
                    parse_retry_after(raw),
                )

    def test_delay_seconds_overflow_is_malformed_not_clamped(self):
        for raw in ("9223372036854776", "99999999999999999999999", "9" * 400):
            with self.subTest(raw=raw):
                self.assertEqual("MALFORMED", parse_retry_after(raw)["rawKind"])

    def test_http_date_forms_carry_utc_epoch_ms(self):
        expected_epoch_ms = 784111777000
        for raw in (
            "Sun, 06 Nov 1994 08:49:37 GMT",
            "Sunday, 06-Nov-94 08:49:37 GMT",
            "Sun Nov  6 08:49:37 1994",
        ):
            with self.subTest(raw=raw):
                parsed = parse_retry_after(raw)
                self.assertEqual("HTTP_DATE", parsed["rawKind"])
                self.assertEqual("1994-11-06T08:49:37Z", parsed["notBeforeUtc"])
                self.assertEqual(expected_epoch_ms, parsed["notBeforeUtcEpochMs"])
                self.assertEqual("PROVIDER_WALL_CLOCK", parsed["clockDomain"])
                self.assertNotIn("delaySeconds", parsed)

    def test_rfc850_two_digit_year_rule(self):
        # Reference 2026: century 2000 + yy; above 2076 rolls back 100 years.
        self.assertEqual(
            3155760000000,
            parse_retry_after(
                "Wednesday, 01-Jan-70 00:00:00 GMT"
            )["notBeforeUtcEpochMs"],
        )  # 2070-01-01
        self.assertEqual(
            220924800000,
            parse_retry_after(
                "Saturday, 01-Jan-77 00:00:00 GMT"
            )["notBeforeUtcEpochMs"],
        )  # 1977-01-01: 2077 > 2076 rolls back
        self.assertEqual(
            "MALFORMED",
            parse_retry_after("Thursday, 01-Jan-76 00:00:00 GMT")["rawKind"],
        )  # 01-Jan-76 resolves to Wednesday 2076-01-01, not Thursday 1976

    def test_reference_utc_epoch_ms_resolves_rfc850_year(self):
        # 2107-01-01 reference: 2100 + 94 = 2194 > 2157, so the year is 2094.
        parsed = parse_retry_after(
            "Saturday, 06-Nov-94 08:49:37 GMT",
            reference_utc_epoch_ms=4323283200000,
        )
        self.assertEqual(3939871777000, parsed["notBeforeUtcEpochMs"])
        self.assertEqual(
            parsed,
            parse_retry_after(
                "Saturday, 06-Nov-94 08:49:37 GMT", reference_year=2107
            ),
        )
        # 2026-09-26T00:00:00Z reference resolves the same digits to 1994.
        self.assertEqual(
            784111777000,
            parse_retry_after(
                "Sunday, 06-Nov-94 08:49:37 GMT",
                reference_utc_epoch_ms=1790380800000,
            )["notBeforeUtcEpochMs"],
        )
        self.assertEqual(
            1792567680000,
            parse_retry_after(
                "Wednesday, 21-Oct-26 07:28:00 GMT",
                reference_utc_epoch_ms=1790380800000,
            )["notBeforeUtcEpochMs"],
        )

    def test_malformed_values_stay_malformed(self):
        for raw in (
            "-1",
            "+10",
            "1.5",
            "abc",
            "",
            " \t ",
            "1 0",
            "\u0661\u0662",
            "Sun, 31 Feb 2026 00:00:00 GMT",
            "Mon, 06 Nov 1994 08:49:37 GMT",
            "Sun, 06 Nov 1994 24:00:00 GMT",
            "Sun, 06 Nov 1994 08:49:60 GMT",
            "Sun, 06 Nov 1994 08:49:37 gmt",
            "Sun, 06 Nov 1994 08:49:37 UTC",
            "Sun, 6 Nov 1994 08:49:37 GMT",
            "sun, 06 Nov 1994 08:49:37 GMT",
            "Sun, 06 nov 1994 08:49:37 GMT",
            "Sat, 29 Feb 2025 00:00:00 GMT",
        ):
            with self.subTest(raw=raw):
                self.assertEqual("MALFORMED", parse_retry_after(raw)["rawKind"])
        self.assertEqual("ABSENT", parse_retry_after(None)["rawKind"])

    def test_provider_wait_ms_is_provider_wall_clock_only(self):
        date = parse_retry_after("Sun, 06 Nov 1994 08:49:37 GMT")
        not_before = date["notBeforeUtcEpochMs"]
        self.assertEqual(2000, provider_wait_ms(parse_retry_after("2"), None))
        self.assertEqual(0, provider_wait_ms(date, not_before + 5000))
        self.assertEqual(0, provider_wait_ms(date, not_before))
        self.assertEqual(2000, provider_wait_ms(date, not_before - 2000))
        self.assertIsNone(provider_wait_ms(parse_retry_after(None), None))
        self.assertIsNone(provider_wait_ms(parse_retry_after("soon"), None))
        with self.assertRaises(M2ContractError):
            provider_wait_ms(date, None)

    def test_retry_after_parse_is_status_independent(self):
        # parse_retry_after has no status input; a 503 carrying a valid header
        # must not turn it into a different normalized shape.
        expected = {
            "source": "HTTP_HEADER",
            "rawKind": "DELAY_SECONDS",
            "delaySeconds": 120,
        }
        self.assertEqual(expected, parse_retry_after("120"))
        try:
            result = classify_http_contract_case(503, retry_after="120")
        except M2ContractError:
            self.skipTest("classify_http_contract_case has no HTTP 503 anchor")
        self.assertEqual(expected, result["retryAfter"])


class M2FailureSeparationTest(unittest.TestCase):
    record = staticmethod(M2HttpAnchorTest._record)

    def test_layers_must_be_separate(self):
        record = self.record(
            {"kind": "CONNECTION_RESET"}, "TRANSIENT_TRANSPORT", "RETRY"
        )
        validate_failure_record(record)
        for layer in ("observation", "classification", "decision"):
            with self.subTest(missing=layer):
                broken = copy.deepcopy(record)
                del broken[layer]
                with self.assertRaises(M2ContractError):
                    validate_failure_record(broken)
        merged = copy.deepcopy(record)
        merged["classification"]["action"] = "RETRY"
        with self.assertRaises(M2ContractError):
            validate_failure_record(merged)

    def test_enospc_as_provider_rejection_rejected(self):
        # checklist 15
        with self.assertRaises(M2ContractError):
            validate_failure_record(self.record(
                {"kind": "STORAGE_ENOSPC"}, "PROVIDER_REJECTED", "FAIL"
            ))
        with self.assertRaises(M2ContractError):
            validate_failure_record(self.record(
                {"kind": "STORAGE_ENOSPC"}, "TRANSIENT_TRANSPORT", "RETRY"
            ))

    def test_storage_failure_never_refreshes_binding(self):
        for action in ("REFRESH_DELIVERY_BINDING", "RERESOLVE"):
            with self.subTest(action=action):
                with self.assertRaises(M2ContractError):
                    validate_failure_record(self.record(
                        {"kind": "STORAGE_IO"}, "STORAGE_FAILURE", action
                    ))
        validate_failure_record(self.record(
            {"kind": "STORAGE_ENOSPC"}, "STORAGE_FAILURE", "FAIL"
        ))

    def test_route_and_provider_failures_are_not_conflated(self):
        with self.assertRaises(M2ContractError):
            validate_failure_record(self.record(
                {"kind": "VALIDATED_CAPABILITY_LOST"}, "PROVIDER_REJECTED", "WAIT"
            ))
        with self.assertRaises(M2ContractError):
            validate_failure_record(self.record(
                {"kind": "HTTP_STATUS", "status": 403}, "ROUTE_UNAVAILABLE",
                "WAIT_FOR_ROUTE",
            ))
        validate_failure_record(self.record(
            {"kind": "VALIDATED_CAPABILITY_LOST"}, "ROUTE_UNAVAILABLE",
            "WAIT_FOR_ROUTE",
        ))

    def test_no_decision_invalidates_persisted_coverage(self):
        record = self.record(
            {"kind": "HTTP_STATUS", "status": 403}, "PROVIDER_REJECTED", "FAIL"
        )
        record["decision"]["invalidatesPersistedCoverage"] = True
        with self.assertRaises(M2ContractError):
            validate_failure_record(record)


def chain_start(chain="r1", sequence=1, spent=0, work="fetch-key-x"):
    return {
        "sequence": sequence,
        "recoveryChainId": chain,
        "kind": "CHAIN_STARTED",
        "workIdentity": work,
        "policyId": "recovery-policy@example",
        "spent": spent,
    }


def charge(sequence, spent, amount, chain="r1"):
    return {
        "sequence": sequence,
        "recoveryChainId": chain,
        "kind": "CHARGE",
        "charge": amount,
        "spent": spent,
    }


def transition(sequence, name, spent, chain="r1"):
    return {
        "sequence": sequence,
        "recoveryChainId": chain,
        "kind": "LAYER_TRANSITION",
        "transition": name,
        "spent": spent,
    }


def terminal(sequence, name, spent, chain="r1"):
    return {
        "sequence": sequence,
        "recoveryChainId": chain,
        "kind": "TERMINAL",
        "terminal": name,
        "spent": spent,
    }


class M2RecoveryLedgerTest(unittest.TestCase):
    def full_chain(self):
        return [
            chain_start(),
            charge(2, 1, 1),                                # owner #1 attempt 1
            charge(3, 2, 1),                                # owner #1 attempt 2
            transition(4, "MEDIA3_REOPEN", 2),
            transition(5, "SHARED_FETCH_REPLACED", 2),      # owner #2
            charge(6, 3, 1),
            transition(7, "ROUTE_EPOCH_CHANGED", 3),
            transition(8, "ROUTE_PAUSED", 3),
            transition(9, "ROUTE_RESUMED", 3),
            transition(10, "DELIVERY_REBOUND", 3),
            transition(11, "TRANSPORT_RECONNECT", 3),
            charge(12, 4, 1),
            terminal(13, "SUCCESS", 4),
        ]

    def test_chain_retains_charges_across_layers(self):
        self.assertEqual({"r1": "SUCCESS"}, validate_recovery_ledger(self.full_chain()))

    def test_vector_budget_is_supported_without_known_buckets(self):
        events = [
            chain_start(spent={}),
            charge(2, {"physical": 1}, {"physical": 1}),
            transition(3, "DELIVERY_REBOUND", {"physical": 1}),
            charge(4, {"physical": 1, "refresh": 1}, {"refresh": 1}),
            terminal(5, "NO_REMAINING_DEMAND", {"physical": 1, "refresh": 1}),
        ]
        self.assertEqual(
            {"r1": "NO_REMAINING_DEMAND"}, validate_recovery_ledger(events)
        )
        dropped = events[:3] + [
            charge(4, {"refresh": 1}, {"refresh": 1}),
        ]
        with self.assertRaises(M2ContractError):
            validate_recovery_ledger(dropped)

    def test_reset_after_any_layer_transition_rejected(self):
        # checklist 10-13 (+ transport reconnect)
        for name in ("SHARED_FETCH_REPLACED", "MEDIA3_REOPEN",
                     "ROUTE_EPOCH_CHANGED", "DELIVERY_REBOUND",
                     "TRANSPORT_RECONNECT"):
            with self.subTest(transition=name):
                events = [
                    chain_start(),
                    charge(2, 1, 1),
                    charge(3, 2, 1),
                    transition(4, name, 0),
                ]
                with self.assertRaises(M2ContractError):
                    validate_recovery_ledger(events)

    def test_reset_to_zero_on_next_event_rejected(self):
        events = [chain_start(), charge(2, 1, 1), charge(3, 1, 1)]
        with self.assertRaises(M2ContractError):
            validate_recovery_ledger(events)
        events = [chain_start(), charge(2, 2, 2), charge(3, 1, 1)]
        with self.assertRaises(M2ContractError):
            validate_recovery_ledger(events)

    def test_restarting_same_chain_rejected(self):
        events = [chain_start(), charge(2, 1, 1), chain_start(sequence=3)]
        with self.assertRaises(M2ContractError):
            validate_recovery_ledger(events)

    def test_transition_cannot_spend_silently(self):
        events = [chain_start(), charge(2, 1, 1), transition(3, "MEDIA3_REOPEN", 2)]
        with self.assertRaises(M2ContractError):
            validate_recovery_ledger(events)

    def test_terminal_chain_cannot_spend(self):
        events = [chain_start(), charge(2, 1, 1), terminal(3, "SUCCESS", 1),
                  charge(4, 2, 1)]
        with self.assertRaises(M2ContractError):
            validate_recovery_ledger(events)

    def test_changed_work_identity_is_a_different_chain(self):
        events = [chain_start(), charge(2, 1, 1)]
        moved = charge(3, 2, 1)
        moved["workIdentity"] = "fetch-key-y"
        with self.assertRaises(M2ContractError):
            validate_recovery_ledger(events + [moved])
        separate = events + [
            chain_start("r2", 3, work="fetch-key-y"),
            charge(4, 1, 1, chain="r2"),
            terminal(5, "TERMINAL_FAILURE", 1, chain="r2"),
        ]
        self.assertEqual(
            {"r1": "OPEN", "r2": "TERMINAL_FAILURE"},
            validate_recovery_ledger(separate),
        )

    def test_policy_identity_is_constant_within_chain(self):
        event = charge(3, 2, 1)
        event["policyId"] = "recovery-policy@other"
        with self.assertRaises(M2ContractError):
            validate_recovery_ledger([chain_start(), charge(2, 1, 1), event])


class M2DeliveryBindingTest(unittest.TestCase):
    stable = {
        "mediaAssetId": "asset-a",
        "fetchKey": "fetch-key-x",
        "extentSpec": {"extentId": "e1", "expectedLength": 1000,
                       "sha256": "1" * 64},
    }

    def event(self, outcome="REBOUND", offered=None):
        return {
            "stableWork": copy.deepcopy(self.stable),
            "offeredWork": copy.deepcopy(offered or self.stable),
            "bindingRevisionBefore": "binding-rev-1",
            "bindingRevisionAfter": "binding-rev-2",
            "outcome": outcome,
        }

    def test_rebind_changes_only_binding(self):
        validate_delivery_rebinding(self.event())
        same_revision = self.event()
        same_revision["bindingRevisionAfter"] = "binding-rev-1"
        with self.assertRaises(M2ContractError):
            validate_delivery_rebinding(same_revision)

    def test_rebind_changing_extent_spec_rejected(self):
        # checklist 14
        offered = copy.deepcopy(self.stable)
        offered["extentSpec"]["expectedLength"] = 999
        with self.assertRaises(M2ContractError):
            validate_delivery_rebinding(self.event("REBOUND", offered))
        mutated = self.event("FAIL_CLOSED", offered)
        mutated["resultingWork"] = offered
        with self.assertRaises(M2ContractError):
            validate_delivery_rebinding(mutated)

    def test_incompatible_binding_fails_closed_or_reresolves(self):
        offered = copy.deepcopy(self.stable)
        offered["extentSpec"]["sha256"] = "2" * 64
        for outcome in ("FAIL_CLOSED", "RERESOLVE"):
            with self.subTest(outcome=outcome):
                validate_delivery_rebinding(self.event(outcome, offered))


class M2ClockDomainTest(unittest.TestCase):
    def test_cross_domain_comparison_rejected(self):
        # checklist 16
        for left, right in (
            ("ANDROID_MONOTONIC", "HOST_FAULT_MONOTONIC"),
            ("ANDROID_MONOTONIC", "HOST_MEDIA_LAB_MONOTONIC"),
            ("PROVIDER_WALL_CLOCK", "ANDROID_MONOTONIC"),
        ):
            for op in ("SUBTRACT", "ORDER"):
                with self.subTest(left=left, right=right, op=op):
                    with self.assertRaises(M2ContractError):
                        check_clock_relation(
                            {"op": op, "leftDomain": left, "rightDomain": right}
                        )

    def test_same_domain_relation_allowed(self):
        check_clock_relation({
            "op": "SUBTRACT",
            "leftDomain": "ANDROID_MONOTONIC",
            "rightDomain": "ANDROID_MONOTONIC",
        })

    def test_manifest_rejects_unknown_clock_domain(self):
        broken = manifest()
        broken["clockDomains"] = ["WALL_CLOCK_SYNCED"]
        with self.assertRaises(SchemaContractError):
            validate_instance(schema(MANIFEST_SCHEMA), broken)


class M2EvidencePrivacyTest(unittest.TestCase):
    def test_secret_bearing_evidence_rejected(self):
        # checklist 17
        cases = [
            {"locator": "https://media.example.invalid/v?sig=abc&expire=1"},
            {"headers": {"Authorization": "x"}},
            {"cookie": "a=b"},
            {"poToken": "opaque"},
            {"visitorData": "opaque"},
            {"note": "Bearer abcdefghijklmnop"},
            {"route": {"ssid": "home"}},
            {"route": {"BSSID": "00:11:22:33:44:55"}},
            {"peer": "203.0.113.7"},
            {"peer": "2001:db8::7"},
            {"proxy": "socks5://user:pass@vpn.example.invalid"},
            {"harness": [{"vpn_credential": "x"}]},
        ]
        for case in cases:
            with self.subTest(case=case):
                with self.assertRaises(M2ContractError):
                    scan_evidence_privacy(case)

    def test_manifest_with_secret_rejected_by_semantic_binding(self):
        broken = manifest()
        broken["runtime"]["signedUrl"] = "opaque"
        with self.assertRaises(M2ContractError):
            validate_run_manifest_semantics(broken, n7())

    def test_opaque_identities_are_allowed(self):
        scan_evidence_privacy({
            "backendId": "platform.httpengine",
            "createdAtUtc": "2026-09-25T04:00:00Z",
            "media3": "1.11.1",
            "schema": "https://spongetube.invalid/schema/m2-scenario-v1.schema.json",
            "deliveryBindingRevision": "binding-rev-2",
        })


if __name__ == "__main__":
    unittest.main()
