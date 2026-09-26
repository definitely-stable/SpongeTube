"""M2-C/M2-D recovery oracle: positive runtime evidence and falsification suite.

Examples under `.work/schemas/examples/m2/` were produced by the Kotlin
RecoveryEvidenceHostTest (production RecoveryCoordinator). Each negative test
mutates one aspect of real evidence and requires the oracle to fail closed
(M2-C falsification list, items 1-24). The v2 suites use synthetic
`failure-decision-events-v2` runs built by `m2_v2_fixtures` (M2-D
falsification list).
"""

import copy
import json
import pathlib
import sys
import tempfile
import unittest

SCRIPT_DIR = pathlib.Path(__file__).resolve().parents[1]
TESTS_DIR = pathlib.Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(SCRIPT_DIR))
sys.path.insert(0, str(TESTS_DIR))

import m2_v2_fixtures as v2  # noqa: E402
from m2_recovery_oracle import (  # noqa: E402
    DELIVERY_BINDING_REFRESH,
    REMOTE_ATTEMPT,
    RecoveryOracleError,
    classify,
    decide,
    expected_action_v2,
    main,
    read_jsonl,
    verify_recovery,
)
from schema_subset import validate_instance, validate_schema_definition  # noqa: E402

SCHEMAS = REPO_ROOT / ".work" / "schemas"
EXAMPLES = SCHEMAS / "examples" / "m2"
RECOVERY = EXAMPLES / "recovery"


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


def run(name=None):
    """(failures, budget, fetch, case) of one runtime-produced example run."""

    if name is None:
        return (
            load(EXAMPLES / "failure-decision-v1.example.json"),
            load(EXAMPLES / "recovery-budget-v1.example.json"),
            read_jsonl(EXAMPLES / "recovery-fetch-events-v3.example.jsonl"),
            None,
        )
    root = RECOVERY / name
    return (
        load(root / "failure-decision-events.json"),
        load(root / "recovery-budget-events.json"),
        read_jsonl(root / "fetch-events.jsonl"),
        load(root / "case.json"),
    )


def resequence(budget):
    for index, event in enumerate(budget["events"]):
        event["sequence"] = index + 1


def resequence_fetch(fetch):
    for index, row in enumerate(fetch):
        row["eventSequence"] = index


def events(budget, kind, chain=None):
    return [
        event for event in budget["events"]
        if event["kind"] == kind and (chain is None or event["recoveryChainId"] == chain)
    ]


def failure_for(failures, *, http=None, kind=None):
    for row in failures["failures"]:
        observation = row["observation"]
        if http is not None and observation["httpStatus"] == http:
            return row
        if kind is not None and observation["kind"] == kind:
            return row
    raise AssertionError("no such failure row")


class RecoveryOracleSchemaTest(unittest.TestCase):
    def test_schemas_are_inside_the_fail_closed_subset(self):
        for name in (
            "failure-decision-events-v1.schema.json",
            "recovery-budget-events-v1.schema.json",
            "recovery-verification-summary-v1.schema.json",
        ):
            with self.subTest(schema=name):
                validate_schema_definition(load(SCHEMAS / name))

    def test_examples_validate(self):
        validate_instance(
            load(SCHEMAS / "failure-decision-events-v1.schema.json"),
            load(EXAMPLES / "failure-decision-v1.example.json"),
        )
        validate_instance(
            load(SCHEMAS / "recovery-budget-events-v1.schema.json"),
            load(EXAMPLES / "recovery-budget-v1.example.json"),
        )
        validate_instance(
            load(SCHEMAS / "recovery-verification-summary-v1.schema.json"),
            load(EXAMPLES / "recovery-verification-summary-v1.example.json"),
        )


class RecoveryOraclePositiveTest(unittest.TestCase):
    def test_full_failure_path_passes_both_gates(self):
        failures, budget, fetch, _ = run()

        summary = verify_recovery(failures, budget, fetch)

        self.assertEqual("PASS", summary["status"])
        self.assertEqual(1, summary["chainCount"])
        self.assertEqual(3, summary["physicalAttemptCount"])
        self.assertEqual(3, summary["chargedAttemptCount"])
        self.assertEqual({"SUCCESS": 1}, summary["terminalCounts"])
        self.assertEqual("PASS", summary["gates"]["M2-ACC-05"]["status"])
        self.assertEqual("PASS", summary["gates"]["M2-ACC-06"]["status"])
        self.assertIn("does not prove provider delivery-binding refresh", " ".join(summary["limitations"]))

    def test_every_runtime_case_passes_with_its_scenario(self):
        for name in ("budget-exhausted", "reserve-playback-join", "cancellation-barrier",
                     "terminal-classifications"):
            with self.subTest(case=name):
                failures, budget, fetch, case = run(name)
                summary = verify_recovery(failures, budget, fetch, case=case)
                self.assertEqual("PASS", summary["status"])

    def test_budget_exhaustion_is_exactly_four_attempts(self):
        failures, budget, fetch, case = run("budget-exhausted")

        summary = verify_recovery(failures, budget, fetch, case=case)

        self.assertEqual(4, summary["physicalAttemptCount"])
        self.assertEqual({"BUDGET_EXHAUSTED": 1}, summary["terminalCounts"])
        self.assertEqual(1, summary["actionCounts"]["TERMINATE_BUDGET_EXHAUSTED"])

    def test_origin_trace_count_equals_charged_attempts(self):
        failures, budget, fetch, _ = run()
        origin = self._correlate(fetch)

        summary = verify_recovery(failures, budget, fetch, origin=origin)

        self.assertEqual(3, summary["originRequestCount"])

    def test_cli_writes_summary_and_enforces_required_gates(self):
        with tempfile.TemporaryDirectory() as directory:
            output = pathlib.Path(directory) / "summary.json"
            args = [
                "verify",
                "--failures", str(EXAMPLES / "failure-decision-v1.example.json"),
                "--budget", str(EXAMPLES / "recovery-budget-v1.example.json"),
                "--fetch", str(EXAMPLES / "recovery-fetch-events-v3.example.jsonl"),
                "--output", str(output),
                "--require-gate", "M2-ACC-05",
                "--require-gate", "M2-ACC-06",
            ]
            self.assertEqual(0, main(args))
            self.assertEqual("PASS", load(output)["status"])

            root = RECOVERY / "terminal-classifications"
            single_owner = [
                "verify",
                "--failures", str(root / "failure-decision-events.json"),
                "--budget", str(root / "recovery-budget-events.json"),
                "--fetch", str(root / "fetch-events.jsonl"),
                "--output", str(output),
                "--require-gate", "M2-ACC-06",
            ]
            self.assertEqual(1, main(single_owner))

    def test_oracle_tables_match_frozen_anchors(self):
        self.assertEqual(
            "PROVIDER_RATE_LIMITED",
            classify({"type": "HTTP_RESPONSE", "kind": "HTTP_STATUS", "httpStatus": 429}),
        )
        self.assertEqual(
            "PROVIDER_REJECTED",
            classify({"type": "HTTP_RESPONSE", "kind": "HTTP_STATUS", "httpStatus": 403}),
        )
        self.assertEqual(
            ("FAIL_TERMINAL", "UNKNOWN_FAILS_CLOSED"),
            decide("UNKNOWN", {"type": "HTTP_RESPONSE", "kind": "HTTP_STATUS"},
                   {"demandPresent": True, "sessionClosing": False}),
        )

    @staticmethod
    def _correlate(fetch):
        origin = []
        for row in fetch:
            if row["attemptCorrelationId"] is not None:
                row["transportCorrelationId"] = "req-" + row["fetchId"]
            if row["event"] == "ATTEMPT_STARTED":
                origin.append({"requestId": row["transportCorrelationId"], "plane": "data",
                               "path": "/fixtures/F1/a"})
        return origin


class RecoveryOracleFalsificationTest(unittest.TestCase):
    def fails(self, pattern, failures, budget, fetch, **kwargs):
        with self.assertRaisesRegex(RecoveryOracleError, pattern):
            verify_recovery(failures, budget, fetch, **kwargs)

    # 1
    def test_physical_attempt_without_budget_charge(self):
        failures, budget, fetch, _ = run()
        template = next(row for row in fetch if row["event"] == "OWNER_REGISTERED")
        extra = [
            dict(template, fetchId="fetch-9", fetchKey="fixture:M2C/video/hidden",
                 consumerIds=["recovery:recovery-9:1"]),
            dict(template, fetchId="fetch-9", fetchKey="fixture:M2C/video/hidden",
                 event="ATTEMPT_STARTED", attempt=1, attemptCorrelationId="fetch-9:attempt-1",
                 consumerIds=["recovery:recovery-9:1"]),
            dict(template, fetchId="fetch-9", fetchKey="fixture:M2C/video/hidden",
                 event="OWNER_FAILED", outcome="RETRYABLE_TRANSPORT_FAILURE",
                 consumerIds=["recovery:recovery-9:1"]),
        ]
        fetch.extend(extra)
        resequence_fetch(fetch)
        self.fails("without budget charge", failures, budget, fetch)

    def test_broker_owner_bypassing_recovery(self):
        failures, budget, fetch, _ = run()
        for row in fetch:
            row["consumerIds"] = ["bridge:r1:t:v:2"]
        self.fails("only a RecoveryChain may own", failures, budget, fetch)

    # 2
    def test_charge_without_physical_attempt(self):
        failures, budget, fetch, _ = run()
        fetch[:] = [
            row for row in fetch
            if not (row["event"] == "ATTEMPT_STARTED" and row["fetchId"] == "fetch-2")
        ]
        resequence_fetch(fetch)
        self.fails("without physical attempt", failures, budget, fetch)

    # 3
    def test_fifth_attempt_at_limit_four(self):
        failures, budget, fetch, _ = run("budget-exhausted")
        terminal = events(budget, "CHAIN_TERMINATED")[0]
        position = budget["events"].index(terminal)
        grant = copy.deepcopy(events(budget, "ATTEMPT_PERMIT_GRANTED")[-1])
        grant["spent"] = {"REMOTE_ATTEMPT": 4}
        charge = copy.deepcopy(events(budget, "CHARGE")[-1])
        charge.update(ownerOrdinal=5, fetchId="fetch-5", attemptCorrelationId="fetch-5:attempt-1",
                      spent={"REMOTE_ATTEMPT": 5})
        charge["charge"].update(spentBefore=4, spentAfter=5)
        budget["events"][position:position] = [grant, charge]
        resequence(budget)
        self.fails("exceeds limit", failures, budget, fetch)

    # 4
    def test_new_fetch_id_resetting_spent(self):
        failures, budget, fetch, _ = run()
        owner = [e for e in events(budget, "OWNER_STARTED") if e["fetchId"] == "fetch-2"][0]
        owner["spent"] = {"REMOTE_ATTEMPT": 0}
        self.fails("implicit reset", failures, budget, fetch)

    # 5
    def test_priority_escalation_resetting_spent(self):
        failures, budget, fetch, _ = run("reserve-playback-join")
        events(budget, "PRIORITY_RAISED")[0]["spent"] = {"REMOTE_ATTEMPT": 0}
        self.fails("implicit reset", failures, budget, fetch)

    def test_priority_escalation_granting_budget(self):
        failures, budget, fetch, _ = run("reserve-playback-join")
        raised = events(budget, "PRIORITY_RAISED")[0]
        raised["limits"] = {"REMOTE_ATTEMPT": 8}
        self.fails("limits changed", failures, budget, fetch)

    # 6
    def test_backoff_transition_resetting_spent(self):
        failures, budget, fetch, _ = run()
        events(budget, "BACKOFF_COMPLETED")[0]["spent"] = {"REMOTE_ATTEMPT": 0}
        self.fails("implicit reset", failures, budget, fetch)

    # 7
    def test_route_wait_spending_remote_attempt(self):
        failures, budget, fetch, _ = run()
        waits = events(budget, "ATTEMPT_PERMIT_WAIT")
        waits[1]["spent"] = {"REMOTE_ATTEMPT": 2}
        self.fails("changed the ledger", failures, budget, fetch)

    # 8
    def test_two_active_chains_for_the_same_immutable_work(self):
        failures, budget, fetch, _ = run()
        second = copy.deepcopy(events(budget, "CHAIN_STARTED")[0])
        second["recoveryChainId"] = "recovery-2"
        budget["events"].insert(3, second)
        resequence(budget)
        self.fails("second open RecoveryChain", failures, budget, fetch)

    # 9
    def test_same_fetch_key_with_different_extent_joins(self):
        failures, budget, fetch, _ = run()
        events(budget, "CONSUMER_JOINED")[0]["extentId"] = "m2c:video:other"
        self.fails("immutable work identity changed", failures, budget, fetch)

    # 10
    def test_overlapping_physical_owners(self):
        failures, budget, fetch, _ = run()
        second = next(r for r in fetch if r["event"] == "OWNER_REGISTERED" and r["fetchId"] == "fetch-2")
        first_terminal = next(r for r in fetch if r["event"] == "OWNER_FAILED" and r["fetchId"] == "fetch-1")
        fetch.remove(second)
        fetch.insert(fetch.index(first_terminal), second)
        resequence_fetch(fetch)
        self.fails("overlapping physical owners", failures, budget, fetch)

    # 11
    def test_429_classified_as_transport(self):
        failures, budget, fetch, case = run("terminal-classifications")
        failure_for(failures, http=429)["classification"] = "TRANSIENT_TRANSPORT"
        self.fails("classification", failures, budget, fetch)

    def test_429_retried_as_transport(self):
        failures, budget, fetch, case = run("terminal-classifications")
        row = failure_for(failures, http=429)
        row["decision"] = {"kind": "RETRY_AFTER_BACKOFF", "reason": "TRANSIENT_FAILURE"}
        self.fails("decision", failures, budget, fetch)

    # 12
    def test_bare_403_classified_stale(self):
        failures, budget, fetch, case = run("terminal-classifications")
        failure_for(failures, http=403)["classification"] = "DELIVERY_BINDING_STALE"
        self.fails("classification", failures, budget, fetch)

    # 13
    def test_storage_failure_triggering_retry(self):
        failures, budget, fetch, case = run("terminal-classifications")
        row = failure_for(failures, kind="NO_SPACE")
        row["decision"] = {"kind": "RETRY_AFTER_BACKOFF", "reason": "TRANSIENT_FAILURE"}
        self.fails("decision", failures, budget, fetch)

    # 14
    def test_range_mismatch_triggering_generic_retry(self):
        failures, budget, fetch, case = run("terminal-classifications")
        row = failure_for(failures, kind="FULL_BODY_FOR_RANGE_REQUEST")
        row["action"] = {"kind": "SCHEDULE_BACKOFF", "delayMs": 100, "retryOrdinal": 1,
                         "reconciliation": None}
        self.fails("action", failures, budget, fetch)

    # 15
    def test_unknown_triggering_retry(self):
        failures, budget, fetch, case = run("terminal-classifications")
        row = failure_for(failures, http=302)
        row["decision"] = {"kind": "RETRY_AFTER_BACKOFF", "reason": "TRANSIENT_FAILURE"}
        self.fails("decision", failures, budget, fetch)

    def test_provider_action_executed_without_handler(self):
        failures, budget, fetch, case = run("terminal-classifications")
        row = failure_for(failures, kind="DESCRIPTOR_STALE")
        row["action"]["kind"] = "START_NEXT_OWNER"
        self.fails("action", failures, budget, fetch)

    # 16
    def test_media3_automatically_retrying_a_recovery_terminal_failure(self):
        failures, budget, fetch, case = run("terminal-classifications")
        forbidden = events(budget, "CHAIN_TERMINATED")[0]
        reopened = [
            copy.deepcopy(events(budget, "CHAIN_STARTED", forbidden["recoveryChainId"])[0]),
            copy.deepcopy(forbidden),
        ]
        for event in reopened:
            event["recoveryChainId"] = "recovery-99"
            event["failureId"] = None
        reopened[1]["terminalReason"] = "NO_REMAINING_DEMAND"
        reopened[1]["spent"] = {"REMOTE_ATTEMPT": 0}
        budget["events"].extend(reopened)
        resequence(budget)
        # Structurally a valid chain; forbidden once Media3 must not rechain.
        verify_recovery(failures, budget, fetch)
        self.fails("after terminal", failures, budget, fetch, forbid_rechain_after_failure=True)

    # 17
    def test_media3_error_count_used_as_recovery_budget(self):
        failures, budget, fetch, _ = run("budget-exhausted")
        # A per-load-task counter would report a fresh budget after a reopen.
        failures["failures"][2]["context"]["remoteAttemptsRemaining"] = 3
        self.fails("context remaining", failures, budget, fetch)

    # 18
    def test_final_consumer_cancellation_creating_a_new_attempt(self):
        failures, budget, fetch, case = run("cancellation-barrier")
        failures["failures"][0]["context"]["demandPresent"] = False
        self.fails("decision", failures, budget, fetch)

    # 19
    def test_event_after_chain_terminal(self):
        failures, budget, fetch, _ = run()
        late = copy.deepcopy(events(budget, "CONSUMER_JOINED")[0])
        late["kind"] = "CONSUMER_RELEASED"
        budget["events"].append(late)
        resequence(budget)
        self.fails("after chain", failures, budget, fetch)

    # 20
    def test_policy_id_changing_within_chain(self):
        failures, budget, fetch, _ = run()
        events(budget, "OWNER_STARTED")[1]["policyId"] = "sponge-recovery-v1"
        self.fails("policy", failures, budget, fetch)

    def test_unknown_policy_fails_closed(self):
        failures, budget, fetch, _ = run()
        failures["policyId"] = "made-up-v9"
        budget["policyId"] = "made-up-v9"
        for event in budget["events"]:
            event["policyId"] = "made-up-v9"
        self.fails("unknown policyId", failures, budget, fetch)

    # 21
    def test_dimension_disappearing_from_ledger(self):
        failures, budget, fetch, _ = run()
        events(budget, "BACKOFF_SCHEDULED")[0]["spent"] = {}
        self.fails("disappeared", failures, budget, fetch)

    # 22
    def test_spent_decreasing(self):
        failures, budget, fetch, _ = run()
        events(budget, "CHAIN_TERMINATED")[0]["spent"] = {"REMOTE_ATTEMPT": 2}
        self.fails("decreased", failures, budget, fetch)

    # 23
    def test_terminal_decision_followed_by_request(self):
        failures, budget, fetch, case = run("terminal-classifications")
        row = failure_for(failures, http=403)
        chain = row["recoveryChainId"]
        terminal = events(budget, "CHAIN_TERMINATED", chain)[0]
        position = budget["events"].index(terminal)
        grant = copy.deepcopy(events(budget, "ATTEMPT_PERMIT_GRANTED", chain)[0])
        grant["spent"] = {"REMOTE_ATTEMPT": 1}
        charge = copy.deepcopy(events(budget, "CHARGE", chain)[0])
        charge.update(ownerOrdinal=2, fetchId="fetch-90", attemptCorrelationId="fetch-90:attempt-1",
                      spent={"REMOTE_ATTEMPT": 2})
        charge["charge"].update(spentBefore=1, spentAfter=2)
        started = dict(copy.deepcopy(charge), kind="OWNER_STARTED", charge=None)
        finished = dict(copy.deepcopy(started), kind="OWNER_FINISHED", ownerOutcome="SUCCESS")
        for event in (grant, charge, started, finished):
            event["failureId"] = None
        terminal["spent"] = {"REMOTE_ATTEMPT": 2}
        budget["events"][position:position] = [grant, charge, started, finished]
        resequence(budget)
        self.fails("request after terminal decision", failures, budget, fetch)

    # 24
    def test_exception_text_in_evidence(self):
        failures, budget, fetch, _ = run()
        failures["failures"][0]["observation"]["message"] = "SocketTimeoutException: Read timed out"
        self.fails("schema", failures, budget, fetch)

    def test_url_in_evidence(self):
        failures, budget, fetch, _ = run()
        for event in budget["events"]:
            event["fetchKey"] = "https://media.example/v?sig=abc"
        for row in failures["failures"]:
            row["fetchKey"] = "https://media.example/v?sig=abc"
        self.fails("signed URL", failures, budget, fetch)

    def test_free_text_key_is_rejected_even_where_the_schema_is_open(self):
        failures, budget, fetch, _ = run()
        events(budget, "CHAIN_STARTED")[0]["limits"]["message"] = 4
        self.fails("free-text", failures, budget, fetch)

    # Exact physical request count (item 85).
    def test_origin_requests_beyond_charged_attempts(self):
        failures, budget, fetch, _ = run()
        origin = RecoveryOraclePositiveTest._correlate(fetch)
        origin += [
            {"requestId": f"hidden-{n}", "plane": "data", "path": "/fixtures/F1/a"}
            for n in range(5)
        ]
        self.fails("without a charged attempt", failures, budget, fetch, origin=origin)

    def test_backoff_outside_policy_window(self):
        failures, budget, fetch, _ = run()
        events(budget, "BACKOFF_SCHEDULED")[0]["backoff"]["delayMs"] = 101
        events(budget, "BACKOFF_COMPLETED")[0]["backoff"]["delayMs"] = 101
        self.fails("outside", failures, budget, fetch)

    def test_backoff_window_not_exponential(self):
        failures, budget, fetch, _ = run()
        for kind in ("BACKOFF_SCHEDULED", "BACKOFF_COMPLETED"):
            events(budget, kind)[1]["backoff"]["windowMs"] = 100
            events(budget, kind)[1]["backoff"]["delayMs"] = 100
        failures["failures"][1]["action"]["delayMs"] = 100
        self.fails("backoff window", failures, budget, fetch)

    def test_scenario_attempt_count_mismatch(self):
        failures, budget, fetch, case = run("budget-exhausted")
        case["expectedPhysicalAttempts"] = 8
        self.fails("physical attempts", failures, budget, fetch, case=case)

    def test_incomplete_evidence_without_terminal(self):
        failures, budget, fetch, _ = run()
        budget["events"] = [e for e in budget["events"] if e["kind"] != "CHAIN_TERMINATED"]
        self.fails("CHAIN_TERMINATED", failures, budget, fetch)


class RecoveryOracleV2VocabularyTest(unittest.TestCase):
    """v2 schema, committed example and oracle tables agree."""

    def test_v2_schema_is_inside_the_fail_closed_subset(self):
        validate_schema_definition(load(SCHEMAS / "failure-decision-events-v2.schema.json"))

    def test_committed_v2_example_validates(self):
        validate_instance(
            load(SCHEMAS / "failure-decision-events-v2.schema.json"),
            load(EXAMPLES / "failure-decision-v2.example.json"),
        )

    def test_committed_v2_example_rows_match_the_oracle_tables(self):
        document = load(EXAMPLES / "failure-decision-v2.example.json")
        limits = {REMOTE_ATTEMPT: 4, DELIVERY_BINDING_REFRESH: 1}
        for row in document["failures"]:
            with self.subTest(failure=row["failureId"]):
                observation = row["observation"]
                self.assertEqual(row["classification"], classify(observation))
                self.assertEqual(
                    (row["decision"]["kind"], row["decision"]["reason"]),
                    decide(row["classification"], observation, row["context"], "v2"),
                )
                self.assertEqual(
                    (row["action"]["kind"], row["action"]["exhaustedDimension"]),
                    expected_action_v2(
                        row["decision"]["kind"],
                        observation,
                        row["context"],
                        limits,
                        row["action"]["reconciliation"],
                    ),
                )

    def test_v1_decision_table_is_unchanged(self):
        observation = {"type": "HTTP_RESPONSE", "kind": "HTTP_STATUS", "httpStatus": 429}
        context = {
            "demandPresent": True,
            "sessionClosing": False,
            "remoteAttemptsRemaining": 3,
        }
        self.assertEqual(
            ("WAIT_UNTIL_PROVIDER", "PROVIDER_THROTTLED"),
            decide("PROVIDER_RATE_LIMITED", observation, context),
        )
        self.assertEqual(
            ("WAIT_UNTIL_PROVIDER", "PROVIDER_THROTTLED"),
            decide("PROVIDER_RATE_LIMITED", observation, context, "v1"),
        )


class RecoveryOracleV2PositiveTest(unittest.TestCase):
    def test_upconverted_v1_example_passes_as_v2(self):
        failures, budget, fetch = v2.v1_example_as_v2()

        summary = verify_recovery(failures, budget, fetch)

        self.assertEqual("PASS", summary["status"])
        self.assertEqual(2, summary["failureCount"])
        self.assertEqual(3, summary["physicalAttemptCount"])
        self.assertEqual(3, summary["chargedAttemptCount"])
        self.assertEqual({"SUCCESS": 1}, summary["terminalCounts"])
        self.assertEqual("PASS", summary["gates"]["M2-ACC-05"]["status"])
        self.assertIn(
            "M2-D proves provider-neutral delivery-binding replacement",
            " ".join(summary["limitations"]),
        )
        self.assertNotIn(
            "does not prove provider delivery-binding refresh",
            " ".join(summary["limitations"]),
        )

    def test_wait_provider_delay_seconds_then_success(self):
        failures, budget, fetch = v2.wait_run(v2.retry_after_delay(2))

        summary = verify_recovery(failures, budget, fetch)

        self.assertEqual("PASS", summary["status"])
        self.assertEqual({"SUCCESS": 1}, summary["terminalCounts"])
        self.assertEqual(1, summary["actionCounts"]["WAIT_PROVIDER"])
        self.assertEqual(2, summary["physicalAttemptCount"])
        row = failures["failures"][0]
        self.assertEqual("DELAY_SECONDS", row["action"]["providerWait"]["rawKind"])
        self.assertEqual(2000, row["action"]["providerWait"]["waitMs"])
        self.assertIsNone(row["action"]["providerWait"]["wallClockNowUtcEpochMs"])

    def test_wait_provider_http_date_uses_provider_wall_clock(self):
        not_before = v2.PROVIDER_WALL_CLOCK_NOW + 2_000
        failures, budget, fetch = v2.wait_run(
            v2.retry_after_date(not_before),
            wall_clock_now=v2.PROVIDER_WALL_CLOCK_NOW,
        )

        summary = verify_recovery(failures, budget, fetch)

        self.assertEqual("PASS", summary["status"])
        wait = failures["failures"][0]["action"]["providerWait"]
        self.assertEqual("HTTP_DATE", wait["rawKind"])
        self.assertEqual(2000, wait["waitMs"])
        self.assertEqual(not_before, wait["notBeforeUtcEpochMs"])
        self.assertEqual(v2.PROVIDER_WALL_CLOCK_NOW, wait["wallClockNowUtcEpochMs"])
        self.assertEqual("PROVIDER_WALL_CLOCK", wait["wallClockDomain"])

    def test_refresh_delivery_binding_then_success(self):
        failures, budget, fetch = v2.refresh_run()

        summary = verify_recovery(failures, budget, fetch)

        self.assertEqual("PASS", summary["status"])
        self.assertEqual({"SUCCESS": 1}, summary["terminalCounts"])
        self.assertEqual(1, summary["actionCounts"]["REFRESH_DELIVERY_BINDING"])
        self.assertEqual(2, summary["physicalAttemptCount"])
        binding = failures["failures"][0]["action"]["deliveryBinding"]
        self.assertEqual("REFRESHED", binding["result"])
        self.assertTrue(binding["charged"])
        self.assertEqual(2, summary["chargedAttemptCount"])

    def test_refresh_budget_exhaustion_is_dimension_specific(self):
        failures, budget, fetch = v2.refresh_then_exhausted_run()

        summary = verify_recovery(failures, budget, fetch)

        self.assertEqual("PASS", summary["status"])
        self.assertEqual({"BUDGET_EXHAUSTED": 1}, summary["terminalCounts"])
        self.assertEqual(1, summary["actionCounts"]["TERMINATE_BUDGET_EXHAUSTED"])
        self.assertEqual(
            DELIVERY_BINDING_REFRESH,
            failures["failures"][1]["action"]["exhaustedDimension"],
        )
        self.assertEqual(2, summary["chargedAttemptCount"])

    def test_remote_attempt_exhaustion_terminates_budget_exhausted(self):
        failures, budget, fetch = v2.remote_exhausted_run()

        summary = verify_recovery(failures, budget, fetch)

        self.assertEqual("PASS", summary["status"])
        self.assertEqual({"BUDGET_EXHAUSTED": 1}, summary["terminalCounts"])
        self.assertEqual(4, summary["chargedAttemptCount"])
        self.assertEqual(
            REMOTE_ATTEMPT, failures["failures"][3]["action"]["exhaustedDimension"]
        )

    def test_abandoned_refresh_is_not_terminal(self):
        failures, budget, fetch = v2.abandoned_refresh_run()

        summary = verify_recovery(failures, budget, fetch)

        self.assertEqual("PASS", summary["status"])
        self.assertEqual({"NO_REMAINING_DEMAND": 1}, summary["terminalCounts"])
        self.assertEqual(
            "ABANDONED", failures["failures"][0]["action"]["deliveryBinding"]["result"]
        )

    def test_stale_without_binding_revision_fails_closed(self):
        failures, budget, fetch = v2.fail_closed_stale_run()

        summary = verify_recovery(failures, budget, fetch)

        self.assertEqual("PASS", summary["status"])
        self.assertEqual({"TERMINAL_FAILURE": 1}, summary["terminalCounts"])
        self.assertEqual(1, summary["actionCounts"]["FAIL_CLOSED_ACTION_UNAVAILABLE"])
        self.assertIsNone(failures["failures"][0]["action"]["deliveryBinding"])

    def test_refresh_not_admitted_terminates_budget_exhausted(self):
        failures, budget, fetch = v2.not_admitted_run()

        summary = verify_recovery(failures, budget, fetch)

        self.assertEqual("PASS", summary["status"])
        self.assertEqual({"BUDGET_EXHAUSTED": 1}, summary["terminalCounts"])
        self.assertEqual(1, summary["chargedAttemptCount"])
        self.assertEqual([], v2.events(budget, "CHARGE")[1:])

    def test_charged_incompatible_or_failed_refresh_fails_closed(self):
        for result in ("INCOMPATIBLE", "FAILED"):
            with self.subTest(result=result):
                failures, budget, fetch = v2.charged_terminal_refresh_run(result)

                summary = verify_recovery(failures, budget, fetch)

                self.assertEqual("PASS", summary["status"])
                self.assertEqual({"TERMINAL_FAILURE": 1}, summary["terminalCounts"])
                self.assertEqual(1, summary["chargedAttemptCount"])
                self.assertEqual(
                    ["DELIVERY_BINDING_REFRESH"],
                    [e["charge"]["dimension"] for e in v2.events(budget, "CHARGE")[1:]],
                )

    def test_charged_incompatible_refresh_followed_by_a_request(self):
        failures, budget, fetch = v2.charged_terminal_refresh_run("INCOMPATIBLE")
        refresh = v2.refresh_charge_event(budget)
        extra = dict(refresh)
        extra["failureId"] = None
        extra["charge"] = dict(refresh["charge"], dimension="REMOTE_ATTEMPT")
        budget["events"].insert(budget["events"].index(refresh) + 1, extra)
        v2.resequence(budget)
        with self.assertRaises(RecoveryOracleError):
            verify_recovery(failures, budget, fetch)

    def test_refresh_closed_terminates_session(self):
        failures, budget, fetch = v2.closed_run()

        summary = verify_recovery(failures, budget, fetch)

        self.assertEqual("PASS", summary["status"])
        self.assertEqual({"SESSION_TERMINATION": 1}, summary["terminalCounts"])
        self.assertEqual(0, len(v2.events(budget, "CHARGE")[1:]))

    def test_bare_403_fails_terminal(self):
        failures, budget, fetch = v2.bare_403_run()

        summary = verify_recovery(failures, budget, fetch)

        self.assertEqual("PASS", summary["status"])
        self.assertEqual({"TERMINAL_FAILURE": 1}, summary["terminalCounts"])
        self.assertEqual("PROVIDER_REJECTED", failures["failures"][0]["classification"])

    def test_rate_limited_without_retry_after_fails_closed(self):
        failures, budget, fetch = v2.rate_limited_absent_run()

        summary = verify_recovery(failures, budget, fetch)

        self.assertEqual("PASS", summary["status"])
        self.assertEqual({"TERMINAL_FAILURE": 1}, summary["terminalCounts"])
        self.assertEqual("FAIL_TERMINAL", failures["failures"][0]["decision"]["kind"])
        self.assertEqual("RETRY_AFTER_ABSENT", failures["failures"][0]["decision"]["reason"])


class RecoveryOracleV2FalsificationTest(unittest.TestCase):
    def fails(self, pattern, failures, budget, fetch, **kwargs):
        with self.assertRaisesRegex(RecoveryOracleError, pattern):
            verify_recovery(failures, budget, fetch, **kwargs)

    # Policy table identity.
    def test_v2_document_requires_a_table_v2_policy(self):
        failures, budget, fetch = v2.v1_example_as_v2()
        for document in (failures, budget):
            document["policyId"] = "sponge-recovery-test-v1"
        for event in budget["events"]:
            event["policyId"] = "sponge-recovery-test-v1"
            event["limits"] = {REMOTE_ATTEMPT: 4}
            event["spent"] = {REMOTE_ATTEMPT: event["spent"][REMOTE_ATTEMPT]}
        self.fails("requires a v2 policy", failures, budget, fetch)

    # Classification.
    def test_bare_403_classified_stale(self):
        failures, budget, fetch = v2.bare_403_run()
        failures["failures"][0]["classification"] = "DELIVERY_BINDING_STALE"
        self.fails("classification", failures, budget, fetch)

    def test_403_with_none_signal_deciding_refresh(self):
        failures, budget, fetch = v2.bare_403_run()
        failures["failures"][0]["decision"] = {
            "kind": "REFRESH_DELIVERY_BINDING",
            "reason": "STALE_BINDING_SIGNAL",
        }
        self.fails("decision", failures, budget, fetch)

    def test_429_classified_as_transport(self):
        failures, budget, fetch = v2.wait_run(v2.retry_after_delay(2))
        failures["failures"][0]["classification"] = "TRANSIENT_TRANSPORT"
        self.fails("classification", failures, budget, fetch)

    # Retry-After.
    def test_malformed_retry_after_deciding_wait(self):
        failures, budget, fetch = v2.wait_run(v2.retry_after_delay(2))
        failures["failures"][0]["observation"]["retryAfter"] = v2.retry_after_malformed()
        self.fails("decision", failures, budget, fetch)

    def test_malformed_retry_after_executed_as_retry(self):
        for action_kind in ("SCHEDULE_BACKOFF", "START_NEXT_OWNER"):
            with self.subTest(action=action_kind):
                failures, budget, fetch = v2.rate_limited_absent_run()
                row = failures["failures"][0]
                row["observation"]["retryAfter"] = v2.retry_after_malformed()
                row["decision"] = {
                    "kind": "FAIL_TERMINAL",
                    "reason": "RETRY_AFTER_MALFORMED",
                }
                row["action"]["kind"] = action_kind
                if action_kind == "SCHEDULE_BACKOFF":
                    row["action"]["delayMs"] = 100
                    row["action"]["retryOrdinal"] = 1
                self.fails("action", failures, budget, fetch)

    # Provider wait.
    def test_provider_wait_charge_without_permit(self):
        failures, budget, fetch = v2.wait_run(v2.retry_after_delay(2))
        row = failures["failures"][0]
        wait = next(
            event
            for event in v2.events(budget, "ATTEMPT_PERMIT_WAIT")
            if event["elapsedRealtimeNs"] > row["elapsedRealtimeNs"]
        )
        inserted = copy.deepcopy(v2.remote_charges(budget)[1])
        inserted.update(
            elapsedRealtimeNs=row["elapsedRealtimeNs"] + 1_000,
            spent={REMOTE_ATTEMPT: 2, DELIVERY_BINDING_REFRESH: 0},
        )
        inserted["charge"] = {
            "dimension": REMOTE_ATTEMPT,
            "amount": 1,
            "spentBefore": 1,
            "spentAfter": 2,
            "limit": 4,
        }
        budget["events"].insert(budget["events"].index(wait), inserted)
        v2.resequence(budget)
        self.fails("attempt permit", failures, budget, fetch)

    def test_provider_wait_spent_change_without_charge(self):
        failures, budget, fetch = v2.wait_run(v2.retry_after_delay(2))
        row = failures["failures"][0]
        wait = next(
            event
            for event in v2.events(budget, "ATTEMPT_PERMIT_WAIT")
            if event["elapsedRealtimeNs"] > row["elapsedRealtimeNs"]
        )
        wait["spent"] = {REMOTE_ATTEMPT: 2, DELIVERY_BINDING_REFRESH: 0}
        self.fails("changed the ledger", failures, budget, fetch)

    def test_next_charge_before_provider_wait_elapsed(self):
        failures, budget, fetch = v2.wait_run(v2.retry_after_delay(2))
        row = failures["failures"][0]
        v2.remote_charges(budget)[1]["elapsedRealtimeNs"] = (
            row["elapsedRealtimeNs"] + 2_000_000_000 - 1
        )
        self.fails("provider wait elapsed", failures, budget, fetch)

    def test_wait_ms_not_matching_retry_after(self):
        failures, budget, fetch = v2.wait_run(v2.retry_after_delay(2))
        failures["failures"][0]["action"]["providerWait"]["waitMs"] = 1500
        self.fails("waitMs", failures, budget, fetch)

    def test_http_date_wait_without_wall_clock(self):
        failures, budget, fetch = v2.wait_run(
            v2.retry_after_date(v2.PROVIDER_WALL_CLOCK_NOW + 2_000),
            wall_clock_now=v2.PROVIDER_WALL_CLOCK_NOW,
        )
        failures["failures"][0]["action"]["providerWait"]["wallClockNowUtcEpochMs"] = None
        self.fails("wall clock", failures, budget, fetch)

    # Delivery-binding refresh.
    def test_refresh_charged_as_remote_attempt(self):
        failures, budget, fetch = v2.refresh_run()
        charge = v2.refresh_charge_event(budget)
        charge["charge"] = {
            "dimension": REMOTE_ATTEMPT,
            "amount": 1,
            "spentBefore": 1,
            "spentAfter": 2,
            "limit": 4,
        }
        charge["spent"] = {REMOTE_ATTEMPT: 2, DELIVERY_BINDING_REFRESH: 0}
        self.fails("attempt permit", failures, budget, fetch)

    def test_refresh_charge_without_failure_row(self):
        failures, budget, fetch = v2.refresh_run()
        v2.refresh_charge_event(budget)["failureId"] = None
        self.fails("failureId", failures, budget, fetch)

    def test_charged_refresh_row_without_charge(self):
        failures, budget, fetch = v2.refresh_run()
        v2.remove_refresh_charge(budget)
        self.fails("refresh charge", failures, budget, fetch)

    def test_refreshed_revision_not_advanced(self):
        failures, budget, fetch = v2.refresh_run()
        failures["failures"][0]["action"]["deliveryBinding"]["currentRevision"] = "binding-1"
        self.fails("REFRESHED", failures, budget, fetch)

    def test_already_advanced_with_charge(self):
        failures, budget, fetch = v2.refresh_run()
        binding = failures["failures"][0]["action"]["deliveryBinding"]
        binding.update(
            result="ALREADY_ADVANCED",
            currentRevision="binding-2",
            refreshCorrelationId=None,
            charged=True,
        )
        self.fails("ALREADY_ADVANCED", failures, budget, fetch)

    def test_second_refresh_charge_at_limit_one(self):
        failures, budget, fetch = v2.refresh_then_exhausted_run()
        second = copy.deepcopy(v2.refresh_charge_event(budget))
        second["failureId"] = failures["failures"][1]["failureId"]
        second["spent"] = {REMOTE_ATTEMPT: 2, DELIVERY_BINDING_REFRESH: 2}
        second["charge"] = {
            "dimension": DELIVERY_BINDING_REFRESH,
            "amount": 1,
            "spentBefore": 1,
            "spentAfter": 2,
            "limit": 1,
        }
        terminal = v2.events(budget, "CHAIN_TERMINATED")[0]
        budget["events"].insert(budget["events"].index(terminal), second)
        v2.resequence(budget)
        self.fails("exceeds limit", failures, budget, fetch)

    def test_refresh_resetting_remote_attempt(self):
        failures, budget, fetch = v2.refresh_run()
        after = next(
            event
            for event in v2.events(budget, "ATTEMPT_PERMIT_WAIT")
            if event["spent"][DELIVERY_BINDING_REFRESH] == 1
        )
        after["spent"] = {REMOTE_ATTEMPT: 0, DELIVERY_BINDING_REFRESH: 1}
        self.fails("decreased", failures, budget, fetch)

    # Terminals.
    def test_budget_exhausted_terminal_without_exhausted_dimension(self):
        failures, budget, fetch = v2.rate_limited_absent_run()
        v2.events(budget, "CHAIN_TERMINATED")[0]["terminalReason"] = "BUDGET_EXHAUSTED"
        self.fails("exhausted dimension", failures, budget, fetch)

    def test_terminate_budget_exhausted_without_exhausted_dimension(self):
        failures, budget, fetch = v2.refresh_then_exhausted_run()
        failures["failures"][1]["action"]["exhaustedDimension"] = None
        self.fails("exhaustedDimension", failures, budget, fetch)

    def test_incompatible_refresh_followed_by_another_charge(self):
        failures, budget, fetch = v2.refresh_run()
        row = failures["failures"][0]
        row["action"]["deliveryBinding"].update(
            result="INCOMPATIBLE",
            currentRevision=None,
            refreshCorrelationId="refresh-1",
            charged=True,
        )
        terminal = v2.events(budget, "CHAIN_TERMINATED")[0]
        terminal["terminalReason"] = "TERMINAL_FAILURE"
        terminal["failureId"] = row["failureId"]
        self.fails("request after terminal decision", failures, budget, fetch)

    # Demand and budget.
    def test_no_demand_chain_deciding_wait(self):
        failures, budget, fetch = v2.wait_run(v2.retry_after_delay(2))
        failures["failures"][0]["context"]["demandPresent"] = False
        self.fails("decision", failures, budget, fetch)

    def test_no_demand_chain_deciding_refresh(self):
        failures, budget, fetch = v2.refresh_run()
        failures["failures"][0]["context"]["demandPresent"] = False
        self.fails("decision", failures, budget, fetch)

    def test_remote_attempt_exhausted_but_waiting(self):
        failures, budget, fetch = v2.wait_run(v2.retry_after_delay(2))
        failures["failures"][0]["context"]["remoteAttemptsRemaining"] = 0
        self.fails("action", failures, budget, fetch)

    # v2 observation shape.
    def test_v2_non_http_observation_with_provider_signal(self):
        failures, budget, fetch = v2.v1_example_as_v2()
        failures["failures"][0]["observation"]["providerSignal"] = "NONE"
        self.fails("provider transport fields", failures, budget, fetch)

    def test_v2_http_observation_without_retry_after(self):
        failures, budget, fetch = v2.wait_run(v2.retry_after_delay(2))
        failures["failures"][0]["observation"]["retryAfter"] = None
        self.fails("lacks Retry-After", failures, budget, fetch)

    # Privacy.
    def test_url_inside_a_v2_row(self):
        failures, budget, fetch = v2.wait_run(v2.retry_after_delay(2))
        failures["failures"][0]["fetchKey"] = "https://media.example/v?sig=abc"
        self.fails("signed URL", failures, budget, fetch)


if __name__ == "__main__":
    unittest.main()
