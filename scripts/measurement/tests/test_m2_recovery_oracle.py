"""M2-C recovery oracle: positive runtime evidence and falsification suite.

Examples under `.work/schemas/examples/m2/` were produced by the Kotlin
RecoveryEvidenceHostTest (production RecoveryCoordinator). Each negative test
mutates one aspect of real evidence and requires the oracle to fail closed
(M2-C falsification list, items 1-24).
"""

import copy
import json
import pathlib
import sys
import tempfile
import unittest

SCRIPT_DIR = pathlib.Path(__file__).resolve().parents[1]
REPO_ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from m2_recovery_oracle import (  # noqa: E402
    RecoveryOracleError,
    classify,
    decide,
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


if __name__ == "__main__":
    unittest.main()
