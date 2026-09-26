"""M2-D provider oracle: positive evidence and falsification suite.

Positive suites cover the four committed M2-D schema examples, the 14
synthetic host provider cases built by `m2_provider_fixtures` and (when the
`ProviderRecoveryEvidenceHostTest` producer has run) every real host case.
Each falsification test mutates one aspect of a valid run and requires
`m2_provider_oracle.verify_provider` to fail closed.
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

import m2_provider_fixtures as pf  # noqa: E402
from m2_provider_oracle import (  # noqa: E402
    ProviderOracleError,
    main,
    verify_provider,
)
from schema_subset import validate_instance, validate_schema_definition  # noqa: E402

SCHEMAS = REPO_ROOT / ".work" / "schemas"
EXAMPLES = SCHEMAS / "examples" / "m2"
HOST_ROOT = REPO_ROOT / "core" / "engine" / "build" / "m2-d-provider"

PROVIDER_SCHEMAS = (
    "failure-decision-events-v2.schema.json",
    "delivery-binding-events-v1.schema.json",
    "provider-fault-events-v1.schema.json",
    "provider-verification-summary-v1.schema.json",
)

PROVIDER_EXAMPLES = (
    ("failure-decision-events-v2.schema.json", "failure-decision-v2.example.json"),
    ("delivery-binding-events-v1.schema.json", "delivery-binding-events-v1.example.json"),
    ("provider-fault-events-v1.schema.json", "provider-fault-events-v1.example.json"),
    ("provider-verification-summary-v1.schema.json", "provider-verification-summary-v1.example.json"),
)


def load(path: pathlib.Path):
    return json.loads(path.read_text(encoding="utf-8"))


def write_fixture(root: pathlib.Path, fixture: pf.Fixture) -> None:
    (root / "failure-decision-events.json").write_text(
        json.dumps(fixture.failures, indent=2), encoding="utf-8"
    )
    (root / "recovery-budget-events.json").write_text(
        json.dumps(fixture.budget, indent=2), encoding="utf-8"
    )
    (root / "delivery-binding-events.json").write_text(
        json.dumps(fixture.delivery, indent=2), encoding="utf-8"
    )
    (root / "case.json").write_text(json.dumps(fixture.case, indent=2), encoding="utf-8")
    (root / "fetch-events.jsonl").write_text(
        "\n".join(json.dumps(row) for row in fixture.fetch) + "\n", encoding="utf-8"
    )


def verify(fixture: pf.Fixture, **overrides):
    return verify_provider(
        fixture.failures,
        fixture.budget,
        fixture.delivery,
        fixture.fetch,
        fixture.case,
        provider_faults=fixture.faults,
        origin=fixture.origin,
        **overrides,
    )


def delivery_event(fixture: pf.Fixture, kind: str, *, index: int = 0):
    return pf.delivery_events(fixture, kind)[index]


def insert_delivery(fixture: pf.Fixture, position: int, event: dict) -> None:
    fixture.delivery["events"].insert(position, event)
    pf.resequence_delivery(fixture)


def inserted_delivery_event(
    fixture: pf.Fixture,
    kind: str,
    *,
    elapsed_ns: int,
    previous: str | None,
    current: str | None,
    failure_id: str | None,
    refresh: str | None = None,
    attempt: str | None = None,
    outcome: str | None = None,
) -> dict:
    template = fixture.delivery["events"][0]
    return {
        "sequence": 0,
        "elapsedRealtimeNs": elapsed_ns,
        "recoveryChainId": template["recoveryChainId"],
        "failureId": failure_id,
        "fetchKey": template["fetchKey"],
        "extentId": template["extentId"],
        "kind": kind,
        "previousRevision": previous,
        "currentRevision": current,
        "attemptCorrelationId": attempt,
        "refreshCorrelationId": refresh,
        "outcome": outcome,
    }


class ProviderOracleSchemaTest(unittest.TestCase):
    def test_schemas_are_inside_the_fail_closed_subset(self):
        for name in PROVIDER_SCHEMAS:
            with self.subTest(schema=name):
                validate_schema_definition(load(SCHEMAS / name))

    def test_committed_examples_validate(self):
        for schema_name, example_name in PROVIDER_EXAMPLES:
            with self.subTest(example=example_name):
                validate_instance(
                    load(SCHEMAS / schema_name),
                    load(EXAMPLES / example_name),
                )


class ProviderOraclePositiveTest(unittest.TestCase):
    def test_every_synthetic_case_passes(self):
        for name, fixture in pf.all_fixtures().items():
            with self.subTest(case=name):
                summary = verify(fixture)
                self.assertEqual("PASS", summary["status"])
                self.assertEqual(name, summary["caseId"])

    def test_n10_binding_expired_refresh_summary(self):
        fixture = pf.n10_binding_expired_refresh()

        summary = verify(fixture)

        self.assertEqual(2, summary["physicalAttemptCount"])
        self.assertEqual(2, summary["remoteAttemptChargeCount"])
        self.assertEqual(1, summary["refreshOperationCount"])
        self.assertEqual(1, summary["refreshChargeCount"])
        self.assertEqual(0, summary["joinedRefreshCount"])
        self.assertEqual(0, summary["alreadyAdvancedCount"])
        self.assertEqual(0, summary["providerWaitCount"])
        self.assertEqual(["binding-1", "binding-2"], summary["bindingRevisions"])
        self.assertEqual(3, summary["originRequestCount"])
        self.assertEqual("HOST_SCRIPTED", summary["evidenceSource"])
        for gate in ("M2-ACC-05", "M2-ACC-06", "M2-ACC-07", "M2-ACC-08"):
            self.assertEqual("PASS", summary["gates"][gate]["status"], gate)
            self.assertTrue(summary["gates"][gate]["checks"], gate)
        self.assertIn("does not by itself prove that a live YouTube HTTP 403", " ".join(summary["limitations"]))
        validate_instance(load(SCHEMAS / "provider-verification-summary-v1.schema.json"), summary)

    def test_n8_bare_403_never_attempts_a_stale_refresh(self):
        fixture = pf.n8_bare_403()

        summary = verify(fixture)

        self.assertEqual(1, summary["physicalAttemptCount"])
        self.assertEqual(0, summary["refreshOperationCount"])
        self.assertEqual(["binding-1"], summary["bindingRevisions"])
        self.assertEqual("PASS", summary["gates"]["M2-ACC-08"]["status"])
        self.assertEqual("NOT_EXERCISED", summary["gates"]["M2-ACC-07"]["status"])

    def test_provider_waits_are_reported_in_failure_row_order(self):
        delay_fixture = pf.n9_delay_seconds()
        delay = verify(delay_fixture)
        http_date = verify(pf.n9_http_date())
        cancelled = verify(pf.provider_wait_cancelled())
        exhausted = verify(pf.rate_limit_budget_exhausted())

        self.assertEqual(
            [2000],
            [
                row["action"]["providerWait"]["waitMs"]
                for row in pf.failure_rows(delay_fixture)
                if row["action"]["kind"] == "WAIT_PROVIDER"
            ],
        )
        self.assertEqual(1, delay["providerWaitCount"])
        self.assertEqual(["binding-1", "binding-1"], delay["bindingRevisions"])
        self.assertEqual(1, http_date["providerWaitCount"])
        self.assertEqual(1, cancelled["providerWaitCount"])
        self.assertEqual(3, exhausted["providerWaitCount"])

    def test_http_date_wait_uses_the_provider_wall_clock(self):
        row = pf.failure_rows(pf.n9_http_date())[0]
        wait = row["action"]["providerWait"]
        self.assertEqual("HTTP_DATE", wait["rawKind"])
        self.assertEqual(2000, wait["waitMs"])
        self.assertEqual(pf.PROVIDER_WALL_CLOCK_NOW + 2000, wait["notBeforeUtcEpochMs"])
        self.assertEqual(pf.PROVIDER_WALL_CLOCK_NOW, wait["wallClockNowUtcEpochMs"])
        self.assertEqual("PROVIDER_WALL_CLOCK", wait["wallClockDomain"])

    def test_single_flight_and_already_advanced_gates(self):
        single_flight = verify(pf.n10_concurrent_single_flight())
        already_advanced = verify(pf.n10_already_advanced())

        self.assertEqual(1, single_flight["refreshOperationCount"])
        self.assertEqual(1, single_flight["joinedRefreshCount"])
        self.assertEqual("PASS", single_flight["gates"]["M2-ACC-07"]["status"])
        self.assertEqual(1, already_advanced["refreshOperationCount"])
        self.assertEqual(1, already_advanced["alreadyAdvancedCount"])
        self.assertEqual("PASS", already_advanced["gates"]["M2-ACC-07"]["status"])
        self.assertEqual(4, already_advanced["physicalAttemptCount"])

    def test_incompatible_refresh_passes_the_binding_gate(self):
        summary = verify(pf.n10_refresh_incompatible())

        self.assertEqual("PASS", summary["gates"]["M2-ACC-07"]["status"])
        self.assertEqual("PASS", summary["gates"]["M2-ACC-08"]["status"])
        self.assertEqual(1, summary["refreshOperationCount"])
        self.assertEqual(["binding-1"], summary["bindingRevisions"])

    def test_abandoned_refresh_is_accepted_with_and_without_a_correlation(self):
        before_operation = verify(pf.refresh_abandoned_before_operation())

        self.assertEqual("PASS", before_operation["status"])
        self.assertEqual(1, before_operation["physicalAttemptCount"])
        self.assertEqual(0, before_operation["refreshOperationCount"])

        fixture = pf.shutdown_during_refresh()
        binding = fixture.failures["failures"][0]["action"]["deliveryBinding"]
        self.assertIsNone(binding["refreshCorrelationId"])
        self.assertEqual("PASS", verify(fixture)["status"])
        binding["refreshCorrelationId"] = "refresh-1"
        self.assertEqual("PASS", verify(fixture)["status"])

    def test_legit_provider_vocabulary_passes_the_privacy_scan(self):
        fixture = pf.n10_binding_expired_refresh()
        fixture.case["persistedExtentIdsBefore"] = [
            "m2d:f1:audio:1:1",
            "fixture:F1/audio-main/f1-audio-1/segment-1-00001",
        ]
        fixture.case["persistedExtentIdsAfter"] = list(fixture.case["persistedExtentIdsBefore"])

        summary = verify(fixture)

        self.assertEqual("PASS", summary["status"])
        self.assertEqual("BINDING_EXPIRY_REFRESH", summary["variant"])

    def test_cli_writes_summary_and_enforces_required_gates(self):
        fixture = pf.n10_binding_expired_refresh()
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            write_fixture(root, fixture)
            output = root / "provider-verification-summary.json"
            args = [
                "verify",
                "--failures", str(root / "failure-decision-events.json"),
                "--budget", str(root / "recovery-budget-events.json"),
                "--delivery", str(root / "delivery-binding-events.json"),
                "--fetch", str(root / "fetch-events.jsonl"),
                "--case", str(root / "case.json"),
                "--output", str(output),
                "--require-gate", "M2-ACC-07",
                "--require-gate", "M2-ACC-08",
            ]
            self.assertEqual(0, main(args))
            summary = load(output)
            self.assertEqual("PASS", summary["status"])
            self.assertEqual("n10-binding-expired-refresh", summary["caseId"])

        none_exercised = pf.n8_bare_403()
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            write_fixture(root, none_exercised)
            output = root / "provider-verification-summary.json"
            args = [
                "verify",
                "--failures", str(root / "failure-decision-events.json"),
                "--budget", str(root / "recovery-budget-events.json"),
                "--delivery", str(root / "delivery-binding-events.json"),
                "--fetch", str(root / "fetch-events.jsonl"),
                "--case", str(root / "case.json"),
                "--output", str(output),
                "--require-gate", "M2-ACC-07",
            ]
            self.assertEqual(1, main(args))

    def test_v1_failure_evidence_is_rejected(self):
        fixture = pf.n8_bare_403()
        fixture.failures["schemaVersion"] = 1
        with self.assertRaisesRegex(ProviderOracleError, "v1"):
            verify(fixture)

    def test_v3_fetch_evidence_is_rejected(self):
        fixture = pf.n8_bare_403()
        fixture.fetch[0]["schemaVersion"] = 3
        with self.assertRaisesRegex(ProviderOracleError, "fetch-events-v4"):
            verify(fixture)


@unittest.skipUnless(
    HOST_ROOT.is_dir() and any(HOST_ROOT.iterdir()),
    "host provider evidence not built (ProviderRecoveryEvidenceHostTest)",
)
class ProviderOracleHostEvidenceTest(unittest.TestCase):
    def test_every_real_host_case_passes_with_its_case_json(self):
        cases = sorted(
            path for path in HOST_ROOT.iterdir() if (path / "case.json").is_file()
        )
        self.assertTrue(cases, "no host provider cases found")
        for case_dir in cases:
            with self.subTest(case=case_dir.name):
                fixture = pf.load_host_case(case_dir)
                summary = verify_provider(
                    fixture.failures,
                    fixture.budget,
                    fixture.delivery,
                    fixture.fetch,
                    fixture.case,
                )
                self.assertEqual("PASS", summary["status"])


class ProviderOracleFalsificationTest(unittest.TestCase):
    def fails(self, fixture, *, message=None):
        with self.assertRaises(ProviderOracleError) as raised:
            verify(fixture)
        if message is not None:
            self.assertIn(message, str(raised.exception))

    # 1. A 403 without an explicit provider signal never becomes a stale
    # binding.
    def test_bare_403_without_explicit_signal_is_rejected(self):
        fixture = pf.n8_bare_403()
        fixture.failures["failures"][0]["classification"] = "DELIVERY_BINDING_STALE"
        self.fails(fixture, message="PROVIDER_REJECTED")

    # 2. A bare 403 never requests a delivery-binding refresh.
    def test_bare_403_with_a_refresh_requested_is_rejected(self):
        fixture = pf.n8_bare_403()
        row = fixture.failures["failures"][0]
        terminal = pf.budget_events(fixture, "CHAIN_TERMINATED")[0]
        step = max(1, (terminal["elapsedRealtimeNs"] - row["elapsedRealtimeNs"]) // 4)
        insert_delivery(fixture, 1, inserted_delivery_event(
            fixture, "REFRESH_REQUESTED",
            elapsed_ns=row["elapsedRealtimeNs"] + step,
            previous="binding-1", current="binding-1", failure_id=row["failureId"],
        ))
        insert_delivery(fixture, 2, inserted_delivery_event(
            fixture, "REFRESH_STARTED",
            elapsed_ns=row["elapsedRealtimeNs"] + 2 * step,
            previous="binding-1", current="binding-1", failure_id=row["failureId"],
            refresh="refresh-1",
        ))
        insert_delivery(fixture, 3, inserted_delivery_event(
            fixture, "REFRESH_SUCCEEDED",
            elapsed_ns=row["elapsedRealtimeNs"] + 3 * step,
            previous="binding-1", current="binding-2", failure_id=row["failureId"],
            refresh="refresh-1", outcome="SUCCEEDED",
        ))
        self.fails(fixture, message="bare 403")

    # 3. 429 stays a provider-plane throttling observation.
    def test_429_classified_as_transport_is_rejected(self):
        fixture = pf.n9_delay_seconds()
        fixture.failures["failures"][0]["classification"] = "TRANSIENT_TRANSPORT"
        self.fails(fixture, message="PROVIDER_RATE_LIMITED")

    # 4. A malformed Retry-After never continues the chain.
    def test_malformed_retry_after_followed_by_a_charge_is_rejected(self):
        fixture = pf.n9_delay_seconds()
        row = fixture.failures["failures"][0]
        row["observation"]["retryAfter"] = pf.retry_after_malformed()
        row["decision"] = {"kind": "FAIL_TERMINAL", "reason": "RETRY_AFTER_MALFORMED"}
        row["action"]["kind"] = "TERMINATE_FAILURE"
        row["action"]["providerWait"] = None
        terminal = pf.budget_events(fixture, "CHAIN_TERMINATED")[0]
        terminal["terminalReason"] = "TERMINAL_FAILURE"
        terminal["failureId"] = row["failureId"]
        self.fails(fixture, message="request after terminal decision")

    # 5. A provider wait never consumes REMOTE_ATTEMPT.
    def test_provider_wait_consuming_remote_attempt_is_rejected(self):
        fixture = pf.n9_delay_seconds()
        original = pf.remote_charges(fixture)[0]
        second_wait = [
            index for index, event in enumerate(fixture.budget["events"])
            if event["kind"] == "ATTEMPT_PERMIT_WAIT"
        ][1]
        extra = copy.deepcopy(original)
        extra["spent"] = dict(fixture.budget["events"][second_wait]["spent"])
        fixture.budget["events"].insert(second_wait, extra)
        pf.resequence_budget(fixture)
        self.fails(fixture, message="inconsistent charge")

    # 6. The next attempt respects the provider-directed wait.
    def test_next_charge_before_the_wait_elapsed_is_rejected(self):
        fixture = pf.n9_delay_seconds()
        row = fixture.failures["failures"][0]
        wait_ms = row["action"]["providerWait"]["waitMs"]
        charges = pf.remote_charges(fixture)
        charges[1]["elapsedRealtimeNs"] = (
            row["elapsedRealtimeNs"] + wait_ms * 1_000_000 - 1
        )
        self.fails(fixture, message="before the provider wait elapsed")

    # 7. A refresh charges the refresh dimension, never REMOTE_ATTEMPT.
    def test_refresh_charged_as_remote_attempt_is_rejected(self):
        fixture = pf.n10_binding_expired_refresh()
        pf.refresh_charges(fixture)[0]["charge"]["dimension"] = pf.REMOTE_ATTEMPT
        self.fails(fixture, message="inconsistent charge")

    # 8. A refresh never resets the ledger.
    def test_refresh_resetting_the_ledger_is_rejected(self):
        fixture = pf.n10_binding_expired_refresh()
        charge = pf.refresh_charges(fixture)[0]
        position = fixture.budget["events"].index(charge)
        for event in fixture.budget["events"][position:]:
            if event["spent"].get(pf.DELIVERY_BINDING_REFRESH, 0) > 0:
                event["spent"][pf.DELIVERY_BINDING_REFRESH] -= 1
        self.fails(fixture, message="+ charge")

    # 9. A refresh stays on the same chain for the same immutable work.
    def test_refresh_creating_a_new_chain_is_rejected(self):
        fixture = pf.n10_binding_expired_refresh()
        charge = pf.refresh_charges(fixture)[0]
        position = fixture.budget["events"].index(charge)
        new_chain = copy.deepcopy(fixture.budget["events"][0])
        new_chain["recoveryChainId"] = "recovery-2"
        fixture.budget["events"].insert(position, new_chain)
        pf.resequence_budget(fixture)
        self.fails(fixture, message="second open RecoveryChain")

    # 10/11. Immutable work identity never changes across revisions.
    def test_fetch_key_changing_across_revisions_is_rejected(self):
        fixture = pf.n10_binding_expired_refresh()
        selection = pf.delivery_events(fixture, "BINDING_SELECTED_FOR_ATTEMPT")[1]
        selection["fetchKey"] = "fixture:other"
        self.fails(fixture, message="identity")

    def test_extent_id_changing_is_rejected(self):
        fixture = pf.n10_binding_expired_refresh()
        selection = pf.delivery_events(fixture, "BINDING_SELECTED_FOR_ATTEMPT")[1]
        selection["extentId"] = "m2d:other"
        self.fails(fixture, message="identity")

    # 12. One actual refresh is exactly one charge.
    def test_refresh_started_without_charge_is_rejected(self):
        fixture = pf.n10_binding_expired_refresh()
        pf.drop_refresh_charge(fixture)
        self.fails(fixture, message="charged refresh must have exactly one refresh charge")

    # 13. No charge exists without an actual refresh.
    def test_charge_without_refresh_started_is_rejected(self):
        fixture = pf.n10_binding_expired_refresh()
        fixture.delivery["events"] = [
            event
            for event in fixture.delivery["events"]
            if event["kind"] != "REFRESH_STARTED"
        ]
        pf.resequence_delivery(fixture)
        self.fails(fixture, message="without a started operation")

    # 14. The refresh budget is a hard limit.
    def test_second_refresh_operation_with_limit_one_is_rejected(self):
        fixture = pf.n10_binding_expired_refresh()
        charge = pf.refresh_charges(fixture)[0]
        duplicate = copy.deepcopy(charge)
        duplicate["charge"]["spentBefore"] = 1
        duplicate["charge"]["spentAfter"] = 2
        duplicate["spent"][pf.DELIVERY_BINDING_REFRESH] = 2
        position = fixture.budget["events"].index(charge) + 1
        fixture.budget["events"].insert(position, duplicate)
        pf.resequence_budget(fixture)
        self.fails(fixture, message="limit")

    # 15. Single-flight: one revision, one operation.
    def test_two_refresh_started_for_one_revision_is_rejected(self):
        fixture = pf.n10_binding_expired_refresh()
        started = pf.delivery_events(fixture, "REFRESH_STARTED")[0]
        position = fixture.delivery["events"].index(started) + 1
        row = fixture.failures["failures"][0]
        insert_delivery(fixture, position, inserted_delivery_event(
            fixture, "REFRESH_REQUESTED",
            elapsed_ns=started["elapsedRealtimeNs"],
            previous="binding-1", current="binding-1", failure_id=row["failureId"],
        ))
        insert_delivery(fixture, position + 1, inserted_delivery_event(
            fixture, "REFRESH_STARTED",
            elapsed_ns=started["elapsedRealtimeNs"],
            previous="binding-1", current="binding-1", failure_id=row["failureId"],
            refresh="refresh-2",
        ))
        self.fails(fixture, message="in flight")

    # 16. ALREADY_ADVANCED is free.
    def test_already_advanced_with_a_charge_is_rejected(self):
        fixture = pf.n10_already_advanced()
        row = fixture.failures["failures"][1]
        terminal = [
            index for index, event in enumerate(fixture.budget["events"])
            if event["kind"] == "CHAIN_TERMINATED"
            and event["recoveryChainId"] == row["recoveryChainId"]
        ][0]
        charge = copy.deepcopy(pf.refresh_charges(fixture)[0])
        charge["charge"]["spentBefore"] = 0
        charge["charge"]["spentAfter"] = 1
        charge["spent"] = dict(fixture.budget["events"][terminal]["spent"])
        charge["spent"][pf.DELIVERY_BINDING_REFRESH] = 1
        charge["failureId"] = row["failureId"]
        charge["recoveryChainId"] = row["recoveryChainId"]
        charge["fetchKey"] = row["fetchKey"]
        charge["extentId"] = pf.budget_events(
            fixture, "CHAIN_STARTED", row["recoveryChainId"]
        )[0]["extentId"]
        fixture.budget["events"].insert(terminal, charge)
        for event in fixture.budget["events"][terminal + 1:]:
            event["spent"][pf.DELIVERY_BINDING_REFRESH] = 1
        pf.resequence_budget(fixture)
        self.fails(fixture, message="refresh charge without a charged refresh row")

    # 17. A successful refresh advances the revision, never R -> R.
    def test_refresh_succeeded_r1_to_r1_is_rejected(self):
        fixture = pf.n10_binding_expired_refresh()
        delivery_event(fixture, "REFRESH_SUCCEEDED")["currentRevision"] = "binding-1"
        self.fails(fixture, message="advance")

    # 18. A successful refresh never skips a revision.
    def test_revision_skipping_is_rejected(self):
        fixture = pf.n10_binding_expired_refresh()
        delivery_event(fixture, "REFRESH_SUCCEEDED")["currentRevision"] = "binding-3"
        self.fails(fixture, message="binding-2")

    # 19. The next owner uses the refreshed revision.
    def test_next_owner_still_using_the_old_revision_is_rejected(self):
        fixture = pf.n10_binding_expired_refresh()
        selection = pf.delivery_events(fixture, "BINDING_SELECTED_FOR_ATTEMPT")[1]
        selection["currentRevision"] = "binding-1"
        self.fails(fixture, message="next owner")

    # 20. An incompatible binding starts no owner.
    def test_incompatible_refresh_followed_by_an_owner_is_rejected(self):
        fixture = pf.n10_binding_expired_refresh()
        binding = fixture.failures["failures"][0]["action"]["deliveryBinding"]
        binding["result"] = "INCOMPATIBLE"
        binding["currentRevision"] = None
        self.fails(fixture, message="TERMINAL_FAILURE")

    # 21. A no-demand chain does not continue provider work.
    def test_no_demand_chain_continuing_provider_work_is_rejected(self):
        fixture = pf.n9_delay_seconds()
        fixture.failures["failures"][0]["context"]["demandPresent"] = False
        self.fails(fixture, message="COMPLETE_NO_DEMAND")

    # 22. No chain-scoped event follows the chain terminal.
    def test_refresh_started_after_the_chain_terminated_is_rejected(self):
        fixture = pf.n10_binding_expired_refresh()
        terminal = pf.budget_events(fixture, "CHAIN_TERMINATED")[0]
        row = fixture.failures["failures"][0]
        fixture.delivery["events"].append(inserted_delivery_event(
            fixture, "REFRESH_REQUESTED",
            elapsed_ns=terminal["elapsedRealtimeNs"] + 1_000,
            previous="binding-2", current="binding-2", failure_id=row["failureId"],
        ))
        pf.resequence_delivery(fixture)
        self.fails(fixture, message="terminated")

    # 23. No signed URL (or any scheme) is retained.
    def test_signed_url_in_evidence_is_rejected(self):
        fixture = pf.n10_binding_expired_refresh()
        fixture.failures["failures"][0]["fetchKey"] = "https://x.test/a?sig=1"
        self.fails(fixture, message="retained")

    # 24. No cookie-class field is retained.
    def test_cookie_key_is_rejected(self):
        fixture = pf.n10_binding_expired_refresh()
        fixture.case["Cookie"] = "session=1"
        self.fails(fixture, message="secret-class field")

    # 25. A provider wall clock is never ordered with ANDROID_MONOTONIC.
    def test_provider_wall_clock_domain_mismatch_is_rejected(self):
        fixture = pf.n9_http_date()
        wait = fixture.failures["failures"][0]["action"]["providerWait"]
        wait["wallClockDomain"] = "ANDROID_MONOTONIC"
        self.fails(fixture, message="wallClockDomain")

    # 26/27. The origin trace accounts for every media and refresh request.
    def test_origin_media_count_mismatch_is_rejected(self):
        fixture = pf.n10_binding_expired_refresh()
        fixture.origin = [
            row
            for row in fixture.origin
            if not (row["plane"] == "data" and "/fixtures/" in row["path"] and row["status"] == 403)
        ]
        self.fails(fixture, message="media requests")

    def test_origin_refresh_count_mismatch_is_rejected(self):
        fixture = pf.n10_binding_expired_refresh()
        fixture.origin = [
            row for row in fixture.origin if row["path"] != "/provider/refresh"
        ]
        self.fails(fixture, message="refresh requests")

    def test_origin_media_request_inside_the_provider_wait_is_rejected(self):
        fixture = pf.n9_delay_seconds()
        media = [
            row for row in fixture.origin
            if row["plane"] == "data" and "/fixtures/" in row["path"]
        ]
        throttled, following = media[0], media[1]
        # The retried request reaches the lab 100 ms after the throttled one,
        # although the provider demanded a 2000 ms wait.
        following["handlerStartedAtMonotonicNs"] = (
            throttled["handlerStartedAtMonotonicNs"] + 100_000_000
        )
        following["completedAtMonotonicNs"] = following["handlerStartedAtMonotonicNs"] + 1_000_000
        self.fails(fixture, message="HOST_MEDIA_LAB_MONOTONIC")

    # 28. Provider fault attribution matches the client observation.
    def test_provider_fault_status_mismatch_is_rejected(self):
        fixture = pf.n8_bare_403()
        fixture.faults["events"][0]["statusCode"] = 429
        self.fails(fixture, message="statusCode")

    # 29. A stale signal requires explicit client confirmation.
    def test_provider_stale_signal_without_client_confirmation_is_rejected(self):
        fixture = pf.n10_binding_expired_refresh()
        fixture.faults["events"][0]["providerSignal"] = "NONE"
        self.fails(fixture, message="carries provider signal NONE")

    # 30. The local revision <-> provider generation mapping is a bijection.
    def test_local_revision_generation_mapping_is_not_a_bijection(self):
        fixture = pf.refresh_budget_exhausted()
        media = [
            event for event in fixture.faults["events"] if event["requestKind"] == "MEDIA"
        ]
        self.assertEqual(2, len(media))
        media[1]["providerBindingGeneration"] = "gen-1"
        self.fails(fixture, message="generation")

    # 31. No valid persisted extent is ever removed.
    def test_persisted_extent_removed_is_rejected(self):
        fixture = pf.n10_binding_expired_refresh()
        fixture.case["persistedExtentIdsAfter"] = []
        self.fails(fixture, message="persisted extent")

    # 32. The terminal refresh charge keeps its ledger bookkeeping.
    def test_terminal_refresh_charge_bookkeeping_is_checked(self):
        fixture = pf.n10_refresh_failed()
        charge = pf.refresh_charges(fixture)[0]
        charge["charge"]["spentAfter"] = 2
        charge["spent"][pf.DELIVERY_BINDING_REFRESH] = 2
        for event in fixture.budget["events"][
            fixture.budget["events"].index(charge) + 1:
        ]:
            event["spent"][pf.DELIVERY_BINDING_REFRESH] = 2
        self.fails(fixture, message="exceeds limit")

    # 33. An abandoned refresh names only a cancelled operation it took part in.
    def test_abandoned_with_an_unknown_operation_is_rejected(self):
        fixture = pf.shutdown_during_refresh()
        binding = fixture.failures["failures"][0]["action"]["deliveryBinding"]
        binding["refreshCorrelationId"] = "refresh-9"
        self.fails(fixture, message="unknown operation")

    def test_abandoned_naming_a_finished_operation_is_rejected(self):
        fixture = pf.n10_refresh_failed()
        binding = fixture.failures["failures"][0]["action"]["deliveryBinding"]
        binding["result"] = "ABANDONED"
        terminal = pf.budget_events(fixture, "CHAIN_TERMINATED")[0]
        terminal["terminalReason"] = "NO_REMAINING_DEMAND"
        terminal["failureId"] = None
        self.fails(fixture, message="completed REFRESH_FAILED")

    def test_abandoned_joiner_is_accepted_and_the_initiator_charge_is_not(self):
        fixture = pf.shutdown_during_refresh_with_joiner()

        summary = verify(fixture)
        self.assertEqual("PASS", summary["status"])
        self.assertEqual(2, summary["physicalAttemptCount"])
        self.assertEqual(1, summary["refreshOperationCount"])

        binding = fixture.failures["failures"][1]["action"]["deliveryBinding"]
        self.assertIs(binding["charged"], False)
        binding["charged"] = True
        self.fails(fixture, message="must have exactly one refresh charge")

    # 34. The run matches its case expectations.
    def test_case_expectation_mismatch_is_rejected(self):
        fixture = pf.n10_binding_expired_refresh()
        fixture.case["expectedPhysicalAttempts"] = 3
        self.fails(fixture, message="physical attempts")

    def test_case_terminal_mismatch_is_rejected(self):
        fixture = pf.n10_binding_expired_refresh()
        fixture.case["expectedTerminals"] = {"SUCCESS": 2}
        self.fails(fixture, message="terminals")

    def test_case_refresh_expectation_mismatch_is_rejected(self):
        fixture = pf.n10_binding_expired_refresh()
        fixture.case["expectedRefreshOperations"] = 0
        self.fails(fixture, message="refresh operations")

    def test_case_binding_revision_mismatch_is_rejected(self):
        fixture = pf.n10_binding_expired_refresh()
        fixture.case["expectedBindingRevisions"] = ["binding-1"]
        self.fails(fixture, message="binding revisions")

    def test_case_provider_wait_mismatch_is_rejected(self):
        fixture = pf.n9_delay_seconds()
        fixture.case["expectedProviderWaits"] = [500]
        self.fails(fixture, message="provider waits")
