from __future__ import annotations

import copy
import importlib.util
import json
import pathlib
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
MEASUREMENT = ROOT / "scripts" / "measurement"
spec = importlib.util.spec_from_file_location(
    "m2_transport_pair_plan", MEASUREMENT / "m2_transport_pair_plan.py",
)
assert spec and spec.loader
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)

TransportPairPlanError = module.TransportPairPlanError


def scenario(name: str) -> dict:
    return json.loads(
        (ROOT / "test-fixtures" / "network" / "m2" / name).read_text(
            encoding="utf-8"
        )
    )


def inputs() -> dict[str, dict]:
    return {
        "work": {
            "fixtureId": "F1",
            "trackId": "audio-main",
            "representationId": "f1-audio-1",
            "byteStart": 0,
            "byteEndExclusive": 81811,
            "expectedSha256": (
                "08ac93538dcb3f5eece5996b0abab1e4e7677afbc7b21cc3292a63c776ef4943"
            ),
        },
        "deviceState": {
            "androidApi": 36,
            "deviceClass": "ANDROID_EMULATOR",
            "abi": "x86_64",
            "batteryPolicy": "CI_POWERED",
        },
        "cacheState": {
            "extentStore": "EMPTY_FOR_TRIAL",
            "httpCache": "DISABLED",
        },
        "recoveryPolicy": {
            "policyId": "sponge-recovery-v1",
            "remoteAttemptLimit": 4,
        },
        "routePolicy": {
            "defaultRouteRequired": True,
            "ambientFallback": False,
            "processWideBinding": False,
        },
    }


def plan_for(
    scenario_doc: dict | None = None,
    *,
    seed: int = 20261001,
    blocks: int = 2,
    connection_state: str = "COLD",
) -> dict:
    return module.build_plan(
        run_id="m2-g2-n0",
        pair_id="pair-n0-cold",
        scenario=scenario_doc or scenario("n0-control.json"),
        inputs=inputs(),
        ordering_seed=seed,
        block_count=blocks,
        playback_mode="SPONGE",
        connection_state=connection_state,
    )


def trials_for(plan: dict) -> dict:
    rows = []
    for block in plan["blocks"]:
        for planned in block["trials"]:
            rows.append({
                "trialId": planned["trialId"],
                "orderingBlock": block["orderingBlock"],
                "positionInBlock": planned["positionInBlock"],
                "backendId": planned["backendId"],
                "backendVersion": "test-version",
                "implementationId": "test-implementation",
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
                    "internalRetryVisibility": "PROVEN_ZERO",
                    "internalRetryCount": 0,
                },
                "metrics": {
                    "firstByteUs": 1,
                    "completionUs": 2,
                    "cancellationLatencyUs": None,
                    "cpuTimeUs": 1,
                    "maxRssBytes": 1,
                    "bytesRequested": 81811,
                    "bytesReceived": 81811,
                    "bytesPublished": 81811,
                },
                "negotiatedProtocol": "UNKNOWN",
                "performanceSampleEligible": True,
                "limitations": [],
            })
    return {
        "schemaVersion": 1,
        "runId": plan["runId"],
        "pairId": plan["pairId"],
        "deviceClass": "ANDROID_EMULATOR",
        "androidApi": 36,
        "clockDomain": "ANDROID_MONOTONIC",
        "orderingProtocol": plan["orderingProtocol"],
        "orderingSeed": plan["orderingSeed"],
        "trials": rows,
        "limitations": ["test evidence"],
    }


class TransportPairPlanTest(unittest.TestCase):
    def test_control_plan_is_complete_counterbalanced_and_result_free(self):
        plan = plan_for()
        self.assertEqual(module.BACKENDS, tuple(plan["backends"]))
        self.assertEqual(2, len(plan["blocks"]))
        first = [block["trials"][0]["backendId"] for block in plan["blocks"]]
        self.assertEqual(set(module.BACKENDS), set(first))
        encoded = json.dumps(plan, sort_keys=True)
        for forbidden in ('"result"', '"metrics"', '"selectedBackend"'):
            self.assertNotIn(forbidden, encoded)
        module.validate_plan(plan, scenario=scenario("n0-control.json"), inputs=inputs())

    def test_order_is_deterministic_from_seed_and_scenario_not_pair_label(self):
        one = plan_for(seed=41)
        two = plan_for(seed=41)
        self.assertEqual(one["blocks"], two["blocks"])

        relabeled = module.build_plan(
            run_id="m2-g2-n0-relabeled",
            pair_id="different-pair-label",
            scenario=scenario("n0-control.json"),
            inputs=inputs(),
            ordering_seed=41,
        )
        self.assertEqual(
            [row["backendId"] for row in one["blocks"][0]["trials"]],
            [row["backendId"] for row in relabeled["blocks"][0]["trials"]],
        )

        first = one["blocks"][0]["trials"][0]["backendId"]
        flip_seed = next(
            candidate
            for candidate in range(42, 500)
            if plan_for(seed=candidate)["blocks"][0]["trials"][0]["backendId"] != first
        )
        self.assertNotEqual(
            first,
            plan_for(seed=flip_seed)["blocks"][0]["trials"][0]["backendId"],
        )

    def test_missing_or_duplicate_backend_in_block_fails(self):
        plan = plan_for()
        plan["blocks"][0]["trials"].pop()
        with self.assertRaises(TransportPairPlanError):
            module.validate_plan(plan, scenario=scenario("n0-control.json"), inputs=inputs())

        plan = plan_for()
        plan["blocks"][0]["trials"][1]["backendId"] = plan["blocks"][0]["trials"][0]["backendId"]
        with self.assertRaises(TransportPairPlanError):
            module.validate_plan(plan, scenario=scenario("n0-control.json"), inputs=inputs())

    def test_unbalanced_or_reordered_second_block_fails(self):
        plan = plan_for()
        plan["blocks"][1]["trials"].reverse()
        plan["blocks"][1]["trials"][0]["positionInBlock"] = 1
        plan["blocks"][1]["trials"][1]["positionInBlock"] = 2
        with self.assertRaises(TransportPairPlanError):
            module.validate_plan(plan, scenario=scenario("n0-control.json"), inputs=inputs())

    def test_scenario_drift_after_plan_freeze_fails(self):
        plan = plan_for()
        with self.assertRaises(TransportPairPlanError):
            module.validate_plan(
                plan,
                scenario=scenario("n2-high-rtt-jitter.json"),
                inputs=inputs(),
            )

    def test_n2_and_n5_require_the_exact_frozen_seed(self):
        for name in ("n2-high-rtt-jitter.json", "n5-burst-loss.json"):
            with self.subTest(name=name):
                broken = scenario(name)
                broken["randomSeed"] = 7
                with self.assertRaises(TransportPairPlanError):
                    plan_for(broken)

    def test_stochastic_harness_is_reset_before_every_backend_trial(self):
        plan = plan_for(scenario("n5-burst-loss.json"))
        self.assertTrue(plan["scenario"]["stochastic"])
        self.assertEqual(424242, plan["scenario"]["randomSeed"])
        self.assertTrue(plan["resetPolicy"]["faultHarnessResetPerTrial"])
        self.assertTrue(plan["resetPolicy"]["stochasticStateResetPerTrial"])

        control = plan_for()
        self.assertFalse(control["scenario"]["stochastic"])
        self.assertFalse(control["resetPolicy"]["stochasticStateResetPerTrial"])

    def test_v1_is_cold_only_and_resets_transport_session_per_trial(self):
        plan = plan_for()
        self.assertEqual("COLD", plan["comparison"]["connectionState"])
        self.assertTrue(plan["resetPolicy"]["transportSessionResetPerTrial"])

        plan["blocks"][1]["trials"][0]["connectionState"] = "WARM"
        with self.assertRaises(TransportPairPlanError):
            module.validate_plan(plan, scenario=scenario("n0-control.json"), inputs=inputs())

        with self.assertRaises(TransportPairPlanError):
            plan_for(connection_state="WARM")

    def test_comparison_input_change_invalidates_plan(self):
        plan = plan_for()
        changed = inputs()
        changed["cacheState"]["extentStore"] = "PREPOPULATED"
        with self.assertRaises(TransportPairPlanError):
            module.validate_plan(
                plan,
                scenario=scenario("n0-control.json"),
                inputs=changed,
            )

    def test_result_fields_are_forbidden_in_pre_execution_plan(self):
        plan = plan_for()
        plan["blocks"][0]["trials"][0]["result"] = "SUCCESS"
        with self.assertRaises(TransportPairPlanError):
            module.validate_plan(plan, scenario=scenario("n0-control.json"), inputs=inputs())

    def test_raw_locator_in_portable_plan_is_rejected(self):
        plan = plan_for()
        plan["limitations"].append("debug endpoint http://192.0.2.10/token")
        with self.assertRaises(TransportPairPlanError):
            module.validate_plan(plan, scenario=scenario("n0-control.json"), inputs=inputs())

    def test_observed_schedule_rejects_post_result_deletion_reordering_and_duplication(self):
        plan = plan_for()
        observed = module.planned_trial_schedule(plan)
        module.validate_observed_schedule(
            plan, observed, scenario=scenario("n0-control.json"), inputs=inputs()
        )

        missing = copy.deepcopy(observed)
        missing.pop(1)
        with self.assertRaises(TransportPairPlanError):
            module.validate_observed_schedule(
                plan, missing, scenario=scenario("n0-control.json"), inputs=inputs()
            )

        reordered = copy.deepcopy(observed)
        reordered[0], reordered[1] = reordered[1], reordered[0]
        with self.assertRaises(TransportPairPlanError):
            module.validate_observed_schedule(
                plan, reordered, scenario=scenario("n0-control.json"), inputs=inputs()
            )

        duplicated = copy.deepcopy(observed)
        duplicated[1] = copy.deepcopy(duplicated[0])
        with self.assertRaises(TransportPairPlanError):
            module.validate_observed_schedule(
                plan, duplicated, scenario=scenario("n0-control.json"), inputs=inputs()
            )

    def test_all_canonical_g2_scenarios_match_frozen_hashes(self):
        for name in (
            "n0-control.json",
            "n2-high-rtt-jitter.json",
            "n3-burst-packet-loss.json",
            "n5-burst-loss.json",
            "n6-transport-reset.json",
            "n6-default-route-loss-restore.json",
        ):
            with self.subTest(name=name):
                plan = plan_for(scenario(name))
                key = (plan["scenario"]["family"], plan["scenario"]["variant"])
                self.assertEqual(
                    module.CANONICAL_G2_SCENARIO_HASHES[key],
                    plan["scenario"]["hash"],
                )

    def test_incomplete_or_semantically_weak_fingerprint_inputs_are_rejected(self):
        cases = []
        empty = inputs()
        empty["work"] = {}
        cases.append(empty)

        bad_device = inputs()
        bad_device["deviceState"]["androidApi"] = 35
        cases.append(bad_device)

        dirty_cache = inputs()
        dirty_cache["cacheState"]["extentStore"] = "PREPOPULATED"
        cases.append(dirty_cache)

        weak_recovery = inputs()
        weak_recovery["recoveryPolicy"]["remoteAttemptLimit"] = 5
        cases.append(weak_recovery)

        ambient_route = inputs()
        ambient_route["routePolicy"]["ambientFallback"] = True
        cases.append(ambient_route)

        for values in cases:
            with self.subTest(values=values):
                with self.assertRaises(TransportPairPlanError):
                    module.build_plan(
                        run_id="m2-g2-n0",
                        pair_id="pair-n0-cold",
                        scenario=scenario("n0-control.json"),
                        inputs=values,
                        ordering_seed=1,
                    )

    def test_canonical_scenario_parameter_drift_is_rejected(self):
        mutations = [
            ("n2-high-rtt-jitter.json", ("networkFaults", 0, "parameters", "delayUs"), 100001),
            ("n3-burst-packet-loss.json", ("networkFaults", 0, "parameters", "durationMs"), 1499),
            ("n5-burst-loss.json", ("networkFaults", 0, "parameters", "lossPpm"), 20001),
            ("n6-transport-reset.json", ("transportFaults", 0, "parameters", "timeoutMs"), 1),
            (
                "n6-default-route-loss-restore.json",
                ("routeFaults", 0, "parameters", "replacementDefault"),
                "VPN",
            ),
        ]
        for name, path, value in mutations:
            with self.subTest(name=name):
                changed = scenario(name)
                target = changed
                for key in path[:-1]:
                    target = target[key]
                target[path[-1]] = value
                with self.assertRaises(TransportPairPlanError):
                    plan_for(changed)

    def test_retained_f2_route_regression_keeps_exact_identity(self):
        plan = plan_for(scenario("n6-default-route-loss-restore.json"))
        self.assertEqual("N6", plan["scenario"]["family"])
        self.assertEqual("DEFAULT_ROUTE_LOSS_RESTORE", plan["scenario"]["variant"])
        self.assertEqual(
            "05e0359cb33d864600e98547776b98b5a436f372c99ab325e7f0797c80d42c5a",
            plan["scenario"]["hash"],
        )

    def test_noncanonical_n6_variant_is_rejected(self):
        with self.assertRaises(TransportPairPlanError):
            plan_for(scenario("n6-truncated-stream.json"))

    def test_plan_ids_are_portable_and_bounded(self):
        for run_id, pair_id in (
            ("bad id with spaces", "pair-n0-cold"),
            ("m2-g2-n0", "https://not-portable"),
            ("x" * 97, "pair-n0-cold"),
        ):
            with self.subTest(run_id=run_id, pair_id=pair_id):
                with self.assertRaises(TransportPairPlanError):
                    module.build_plan(
                        run_id=run_id,
                        pair_id=pair_id,
                        scenario=scenario("n0-control.json"),
                        inputs=inputs(),
                        ordering_seed=1,
                    )

    def test_complete_g0_trials_bind_to_frozen_plan(self):
        plan = plan_for()
        module.validate_trials_against_plan(
            plan,
            trials_for(plan),
            scenario=scenario("n0-control.json"),
            inputs=inputs(),
        )

    def test_g0_results_cannot_delete_reorder_relabel_or_change_comparison(self):
        for mutation in ("delete", "reorder", "backend", "comparison"):
            with self.subTest(mutation=mutation):
                plan = plan_for()
                trials = trials_for(plan)
                if mutation == "delete":
                    trials["trials"].pop(1)
                elif mutation == "reorder":
                    trials["trials"][0], trials["trials"][1] = (
                        trials["trials"][1],
                        trials["trials"][0],
                    )
                elif mutation == "backend":
                    row = trials["trials"][0]
                    row["backendId"] = (
                        "PLATFORM_HTTP_ENGINE"
                        if row["backendId"] == "HTTP_URL_CONNECTION_ROUTE_BOUND"
                        else "HTTP_URL_CONNECTION_ROUTE_BOUND"
                    )
                else:
                    trials["trials"][0]["comparison"]["cacheStateFingerprint"] = "0" * 64
                with self.assertRaises(TransportPairPlanError):
                    module.validate_trials_against_plan(
                        plan,
                        trials,
                        scenario=scenario("n0-control.json"),
                        inputs=inputs(),
                    )

    def test_result_device_identity_must_match_fingerprint_source(self):
        plan = plan_for()
        for field, value in (
            ("androidApi", 34),
            ("deviceClass", "PHYSICAL_ANDROID"),
        ):
            with self.subTest(field=field):
                trials = trials_for(plan)
                trials[field] = value
                with self.assertRaises(TransportPairPlanError):
                    module.validate_trials_against_plan(
                        plan,
                        trials,
                        scenario=scenario("n0-control.json"),
                        inputs=inputs(),
                    )

    def test_schema_rejects_backend_superset_and_disabled_required_resets(self):
        plan = plan_for()
        plan["backends"].append(module.BACKENDS[0])
        with self.assertRaises(TransportPairPlanError):
            module.validate_plan(
                plan, scenario=scenario("n0-control.json"), inputs=inputs()
            )

        for field in ("faultHarnessResetPerTrial", "cacheStateResetPerTrial"):
            broken = plan_for()
            broken["resetPolicy"][field] = False
            with self.subTest(field=field):
                with self.assertRaises(TransportPairPlanError):
                    module.validate_plan(
                        broken,
                        scenario=scenario("n0-control.json"),
                        inputs=inputs(),
                    )

    def test_unknown_top_level_fingerprint_group_is_rejected_not_ignored(self):
        values = inputs()
        values["thermalState"] = {"status": "NOMINAL"}
        with self.assertRaises(TransportPairPlanError):
            module.build_plan(
                run_id="m2-g2-n0",
                pair_id="pair-n0-cold",
                scenario=scenario("n0-control.json"),
                inputs=values,
                ordering_seed=1,
            )

        private = inputs()
        private["debugState"] = {"origin": "https://private.invalid/secret"}
        with self.assertRaises(TransportPairPlanError):
            module.build_plan(
                run_id="m2-g2-n0",
                pair_id="pair-n0-cold",
                scenario=scenario("n0-control.json"),
                inputs=private,
                ordering_seed=1,
            )

    def test_private_locator_cannot_be_hidden_inside_fingerprint_input(self):
        values = inputs()
        values["routePolicy"]["debugOrigin"] = "https://origin.example.test/private/path"
        with self.assertRaises(TransportPairPlanError):
            module.build_plan(
                run_id="m2-g2-n0",
                pair_id="pair-n0-cold",
                scenario=scenario("n0-control.json"),
                inputs=values,
                ordering_seed=1,
            )

    def test_odd_block_count_is_rejected_before_execution(self):
        with self.assertRaises(TransportPairPlanError):
            plan_for(blocks=3)

    def test_fingerprint_is_key_order_independent_and_float_rejecting(self):
        self.assertEqual(
            module.fingerprint({"a": 1, "b": {"c": True}}),
            module.fingerprint({"b": {"c": True}, "a": 1}),
        )
        with self.assertRaises(TransportPairPlanError):
            module.fingerprint({"durationSeconds": 0.1})

    def test_plan_does_not_copy_raw_fingerprint_inputs(self):
        values = inputs()
        values["deviceState"]["privateMarker"] = "DO_NOT_COPY_THIS_VALUE"
        plan = module.build_plan(
            run_id="m2-g2-n0",
            pair_id="pair-n0-cold",
            scenario=scenario("n0-control.json"),
            inputs=values,
            ordering_seed=1,
        )
        self.assertNotIn("DO_NOT_COPY_THIS_VALUE", json.dumps(plan))


if __name__ == "__main__":
    unittest.main()
