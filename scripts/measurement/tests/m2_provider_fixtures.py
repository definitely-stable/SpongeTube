"""Synthetic M2-D provider-recovery runs for the provider oracle tests.

The builders mirror the 14 host cases of `ProviderRecoveryEvidenceHostTest`
(`.m2d/tasks/C2-provider-host-evidence.md`) and the deterministic Media Lab
provider scenarios N8/N9/N10 (`.work/milestones/M2.md`, M2-D): a real
`failure-decision-events-v2` run, a `recovery-budget-events-v1` ledger, a
`delivery-binding-events-v1` lifecycle, `fetch-events-v4` rows and the
`case.json` expectations, plus optional `provider-fault-events-v1` and Media
Lab origin trace rows.

Every builder emits a structurally complete run: the RecoveryChain ledger, the
FetchBroker owner ledger, the failure rows and the delivery binding events
share the same correlation identities, so `m2_provider_oracle.verify_provider`
re-derives the whole lineage. Falsification tests mutate one aspect of a valid
run.
"""

from __future__ import annotations

import json
import pathlib
from collections import Counter
from dataclasses import dataclass
from typing import Any, Mapping

from m2_v2_fixtures import (  # shared pure projections of the M2-C fixtures
    fetch_observation,
    fetch_outcome,
    http_observation,
    provider_wait_for,
    retry_after_absent,
    retry_after_date,
    retry_after_delay,
    retry_after_malformed,
)

SCRIPT_DIR = pathlib.Path(__file__).resolve().parents[1]
REPO_ROOT = SCRIPT_DIR.parents[1]

REMOTE_ATTEMPT = "REMOTE_ATTEMPT"
DELIVERY_BINDING_REFRESH = "DELIVERY_BINDING_REFRESH"
POLICY_ID = "sponge-recovery-test-v2"
LIMITS = {REMOTE_ATTEMPT: 4, DELIVERY_BINDING_REFRESH: 1}
RUN_ID = "m2-d-provider-host-run"
SESSION_ID = "m2-d-provider-host"
SCENARIO_HASH = "7f1c8a5d3e9b2a4c6d0f8e1b3a5c7e9d0f2a4c6e8b0d1f3a5c7e9b0d2f4a6c8e"

# Fixed virtual provider wall clock of the Media Lab provider simulator
# (2026-09-25T12:00:00Z).
PROVIDER_WALL_CLOCK_NOW = 1_790_337_600_000

FETCH_KEY = "fixture:F1/audio-main/f1-audio-1/segment-1-00001"
EXTENT_ID = "m2d:f1:audio:1:1"
SECOND_FETCH_KEY = "fixture:F1/audio-main/f1-audio-1/segment-1-00002"
SECOND_EXTENT_ID = "m2d:f1:audio:1:2"
MEDIA_RESOURCE = "F1/audio-main/f1-audio-1/segment-1-00001.m4s"


@dataclass
class Owner:
    chain: str
    fetch_id: str
    attempt: str
    ordinal: int
    revision: str | None
    request_id: str
    generation: int
    lab_start_ns: int
    observation: Mapping[str, Any] | None = None


@dataclass
class Failure:
    chain: str
    id: str
    owner: Owner
    observation: Mapping[str, Any]
    classification: str
    decision: str
    reason: str
    context: dict[str, Any]


@dataclass
class RefreshOp:
    kind: str
    chain: str
    failure_id: str
    expected: str
    current: str
    refresh_id: str | None
    installed: str | None = None
    joined_op: "RefreshOp | None" = None


@dataclass
class Fixture:
    name: str
    failures: dict[str, Any]
    budget: dict[str, Any]
    delivery: dict[str, Any]
    fetch: list[dict[str, Any]]
    case: dict[str, Any]
    faults: dict[str, Any] | None = None
    origin: list[dict[str, Any]] | None = None


_DEFAULT = object()


def binding_record(
    expected: str,
    result: str,
    *,
    current: str | None = None,
    correlation: str | None = None,
    charged: bool = False,
) -> dict[str, Any]:
    return {
        "expectedRevision": expected,
        "result": result,
        "currentRevision": current,
        "refreshCorrelationId": correlation,
        "charged": charged,
    }


class ProviderRun:
    """Builds one complete M2-D provider run."""

    def __init__(
        self,
        case_id: str,
        *,
        scenario_family: str | None = None,
        variant: str | None = None,
        evidence_source: str = "HOST_SCRIPTED",
        policy_id: str = POLICY_ID,
        limits: Mapping[str, int] | None = None,
        bindings_enabled: bool = True,
        persisted_before: tuple[str, ...] = ("m2d:seed:persisted",),
        persisted_after: tuple[str, ...] | None = None,
    ) -> None:
        self.case_id = case_id
        self.scenario_family = scenario_family
        self.variant = variant
        self.evidence_source = evidence_source
        self.policy_id = policy_id
        self.limits = dict(limits) if limits is not None else dict(LIMITS)
        self.bindings_enabled = bindings_enabled
        self.persisted_before = list(persisted_before)
        self.persisted_after = (
            list(persisted_after) if persisted_after is not None else list(persisted_before)
        )
        self.elapsed_ns = 1_000_000
        self.lab_ns = 5_000_000
        self.budget: list[dict[str, Any]] = []
        self.failures: list[dict[str, Any]] = []
        self.fetch: list[dict[str, Any]] = []
        self.delivery: list[dict[str, Any]] = []
        self.faults: list[dict[str, Any]] = []
        self.origin: list[dict[str, Any]] = []
        self.chains: dict[str, dict[str, Any]] = {}
        self.current_revision = 1
        self.client_generation = 1
        self.refresh_counter = 0
        self.started_operations = 0
        self.in_flight: RefreshOp | None = None
        self.fetch_counter = 0
        self.fault_counter = 0
        self.request_counter = 0
        self.attempts: list[str] = []
        self.attempt_revisions: dict[str, str] = {}

    # -- clocks ------------------------------------------------------------

    def tick(self, delta_ns: int = 1_000) -> int:
        self.elapsed_ns += delta_ns
        return self.elapsed_ns

    def wait_ms(self, wait_ms: int) -> None:
        self.elapsed_ns += wait_ms * 1_000_000

    def lab_advance_ms(self, wait_ms: int) -> None:
        self.lab_ns += wait_ms * 1_000_000

    # -- budget evidence ---------------------------------------------------

    def _budget(self, chain_id: str, kind: str, **overrides: Any) -> dict[str, Any]:
        chain = self.chains[chain_id]
        event = {
            "sequence": len(self.budget) + 1,
            "elapsedRealtimeNs": self.elapsed_ns,
            "sessionId": SESSION_ID,
            "recoveryChainId": chain_id,
            "kind": kind,
            "fetchKey": chain["fetch_key"],
            "extentId": chain["extent_id"],
            "policyId": self.policy_id,
            "limits": dict(self.limits),
            "spent": dict(chain["spent"]),
            "effectivePriority": "PLAYBACK",
            "consumerId": None,
            "consumerKind": None,
            "priorityBefore": None,
            "charge": None,
            "ownerOrdinal": None,
            "fetchId": None,
            "attemptCorrelationId": None,
            "ownerOutcome": None,
            "permit": None,
            "backoff": None,
            "failureId": None,
            "terminalReason": None,
        }
        event.update(overrides)
        self.budget.append(event)
        return event

    def start_chain(
        self,
        chain_id: str,
        *,
        fetch_key: str | None = None,
        extent_id: str | None = None,
    ) -> None:
        self.chains[chain_id] = {
            "fetch_key": fetch_key if fetch_key is not None else FETCH_KEY,
            "extent_id": extent_id if extent_id is not None else EXTENT_ID,
            "spent": {dimension: 0 for dimension in self.limits},
            "owner_ordinal": 0,
            "failure_ordinal": 0,
            "last_failure_id": None,
            "terminal": None,
        }
        self._budget(chain_id, "CHAIN_STARTED")
        self._budget(chain_id, "CONSUMER_JOINED", consumerId="playback", consumerKind="PLAYBACK")

    def terminate(self, chain_id: str, reason: str, failure_id: str | None = None) -> None:
        self.tick()
        self.chains[chain_id]["terminal"] = reason
        self._budget(chain_id, "CHAIN_TERMINATED", terminalReason=reason, failureId=failure_id)

    def _charge_refresh(self, chain_id: str, failure_id: str) -> None:
        self.tick()
        chain = self.chains[chain_id]
        before = chain["spent"][DELIVERY_BINDING_REFRESH]
        chain["spent"][DELIVERY_BINDING_REFRESH] = before + 1
        self._budget(
            chain_id,
            "CHARGE",
            charge={
                "dimension": DELIVERY_BINDING_REFRESH,
                "amount": 1,
                "spentBefore": before,
                "spentAfter": before + 1,
                "limit": self.limits[DELIVERY_BINDING_REFRESH],
            },
            failureId=failure_id,
        )

    # -- delivery evidence -------------------------------------------------

    def _delivery(
        self,
        chain_id: str,
        kind: str,
        *,
        previous: str | None = None,
        current: str | None = None,
        attempt: str | None = None,
        refresh: str | None = None,
        outcome: str | None = None,
        failure_id: Any = _DEFAULT,
    ) -> dict[str, Any]:
        chain = self.chains[chain_id]
        resolved_failure = chain["last_failure_id"] if failure_id is _DEFAULT else failure_id
        event = {
            "sequence": len(self.delivery) + 1,
            "elapsedRealtimeNs": self.elapsed_ns,
            "recoveryChainId": chain_id,
            "failureId": resolved_failure,
            "fetchKey": chain["fetch_key"],
            "extentId": chain["extent_id"],
            "kind": kind,
            "previousRevision": previous,
            "currentRevision": current,
            "attemptCorrelationId": attempt,
            "refreshCorrelationId": refresh,
            "outcome": outcome,
        }
        self.delivery.append(event)
        return event

    # -- fetch evidence ----------------------------------------------------

    def _fetch_row(
        self,
        chain_id: str,
        event: str,
        fetch_id: str,
        **overrides: Any,
    ) -> dict[str, Any]:
        chain = self.chains[chain_id]
        row = {
            "schemaVersion": 4,
            "eventSequence": len(self.fetch),
            "eventElapsedRealtimeNs": self.elapsed_ns,
            "sessionId": SESSION_ID,
            "fetchId": fetch_id,
            "fetchKey": chain["fetch_key"],
            "attempt": None,
            "attemptCorrelationId": None,
            "transportCorrelationId": None,
            "event": event,
            "consumerIds": [f"recovery:{chain_id}:{chain['owner_ordinal']}"],
            "effectivePriority": "PLAYBACK",
            "requestedByteStart": 0,
            "requestedByteEndExclusive": 8,
            "chunkByteStart": None,
            "chunkByteEndExclusive": None,
            "networkBytes": 0,
            "uniqueRangeBytes": 0,
            "duplicateRangeBytes": 0,
            "rejectedOrUnmappedBytes": 0,
            "singleFlightJoined": False,
            "outcome": None,
            "observation": None,
        }
        row.update(overrides)
        self.fetch.append(row)
        return row

    # -- provider simulator evidence ---------------------------------------

    def _fault(
        self,
        request_id: str,
        *,
        request_kind: str,
        fault_kind: str,
        status: int | None,
        retry_after: Mapping[str, Any] | None,
        signal: str,
        revision: str | None,
        generation: str | None,
    ) -> None:
        self.fault_counter += 1
        self.faults.append({
            "sequence": len(self.faults) + 1,
            "hostMonotonicNs": self.lab_ns,
            "faultId": f"fault-{self.fault_counter}",
            "requestCorrelationId": str(request_id),
            "requestKind": request_kind,
            "faultKind": fault_kind,
            "statusCode": status,
            "retryAfter": dict(retry_after) if retry_after is not None else None,
            "providerSignal": signal,
            "bindingRevision": revision,
            "providerBindingGeneration": generation,
            "providerWallClockUtcEpochMs": PROVIDER_WALL_CLOCK_NOW,
        })

    def media_fault(
        self,
        owner: Owner,
        *,
        kind: str,
        status: int,
        retry_after: Mapping[str, Any] | None = None,
        signal: str = "NONE",
        generation: int | None = None,
    ) -> None:
        normalized = None
        if retry_after is not None:
            # The provider artifact never carries the client clock domain.
            normalized = {
                key: retry_after[key]
                for key in ("rawKind", "delaySeconds", "notBeforeUtcEpochMs")
            }
        self._fault(
            owner.request_id,
            request_kind="MEDIA",
            fault_kind=kind,
            status=status,
            retry_after=normalized,
            signal=signal,
            revision=owner.revision,
            generation=f"gen-{owner.generation if generation is None else generation}",
        )

    def provider_refresh(
        self,
        kind: str,
        status: int,
        *,
        new_generation: int | None = None,
    ) -> None:
        self.request_counter += 1
        request_id = str(self.request_counter)
        self.lab_ns += 3_000_000
        start = self.lab_ns
        self.lab_ns += 2_000_000
        completed = self.lab_ns
        self.origin.append({
            "schemaVersion": 2,
            "sessionId": SESSION_ID,
            "requestId": int(request_id),
            "plane": "data",
            "fixtureId": "F1",
            "resourceId": None,
            "profileId": self.scenario_family or "N0",
            "scenarioId": self._scenario_id(),
            "scenarioHash": SCENARIO_HASH,
            "method": "POST",
            "path": "/provider/refresh",
            "rangeHeader": None,
            "resolvedRangeStart": None,
            "resolvedRangeEndExclusive": None,
            "status": status,
            "plannedResponseBytes": 0,
            "bodyBytesWritten": 0,
            "handlerStartedAtMonotonicNs": start,
            "firstBodyWriteAtMonotonicNs": None,
            "completedAtMonotonicNs": completed,
            "serverFirstBodyWriteDelayMs": None,
            "handlerDurationMs": max(1, (completed - start) // 1_000_000),
            "configuredRateBps": None,
            "noProgressWaitMs": 0,
            "outcome": "SUCCESS" if status < 400 else "PROVIDER_FAULT",
        })
        self._fault(
            request_id,
            request_kind="REFRESH",
            fault_kind=kind,
            status=status,
            retry_after=None,
            signal="NONE",
            revision=None,
            generation=f"gen-{new_generation}" if new_generation is not None else None,
        )
        if kind in ("REFRESH_SUCCEEDED", "REFRESH_INCOMPATIBLE") and new_generation is not None:
            self.client_generation = new_generation

    def _scenario_id(self) -> str:
        if self.scenario_family is None:
            return "N0-CONTROL"
        return f"{self.scenario_family}-{self.variant}" if self.variant else self.scenario_family

    def _media_path(self, generation: int) -> str:
        return f"/provider/gen-{generation}/fixtures/{MEDIA_RESOURCE}"

    def _media_origin_row(self, owner: Owner, status: int) -> None:
        self.lab_ns += 1_000_000
        completed = self.lab_ns
        self.origin.append({
            "schemaVersion": 2,
            "sessionId": SESSION_ID,
            "requestId": int(owner.request_id),
            "plane": "data",
            "fixtureId": "F1",
            "resourceId": MEDIA_RESOURCE,
            "profileId": self.scenario_family or "N0",
            "scenarioId": self._scenario_id(),
            "scenarioHash": SCENARIO_HASH,
            "method": "GET",
            "path": self._media_path(owner.generation),
            "rangeHeader": "bytes=0-",
            "resolvedRangeStart": 0,
            "resolvedRangeEndExclusive": 8,
            "status": status,
            "plannedResponseBytes": 8,
            "bodyBytesWritten": 8 if status < 400 else 0,
            "handlerStartedAtMonotonicNs": owner.lab_start_ns,
            "firstBodyWriteAtMonotonicNs": owner.lab_start_ns + 1_000_000,
            "completedAtMonotonicNs": completed,
            "serverFirstBodyWriteDelayMs": 1,
            "handlerDurationMs": max(1, (completed - owner.lab_start_ns) // 1_000_000),
            "configuredRateBps": None,
            "noProgressWaitMs": 0,
            "outcome": "SUCCESS" if status < 400 else "PROVIDER_FAULT",
        })

    # -- run lifecycle -----------------------------------------------------

    def begin_owner(
        self,
        chain_id: str,
        *,
        revision: str | None = None,
        generation: int | None = None,
    ) -> Owner:
        self.tick()
        chain = self.chains[chain_id]
        chain["owner_ordinal"] += 1
        ordinal = chain["owner_ordinal"]
        self.fetch_counter += 1
        self.request_counter += 1
        request_id = str(self.request_counter)
        fetch_id = f"fetch-{self.fetch_counter}"
        attempt = f"{fetch_id}:attempt-1"
        self._budget(chain_id, "ATTEMPT_PERMIT_WAIT")
        self._budget(
            chain_id,
            "ATTEMPT_PERMIT_GRANTED",
            permit={"routeEpoch": None, "reason": "ALWAYS_PERMIT"},
        )
        before = chain["spent"][REMOTE_ATTEMPT]
        chain["spent"][REMOTE_ATTEMPT] = before + 1
        self._budget(
            chain_id,
            "CHARGE",
            charge={
                "dimension": REMOTE_ATTEMPT,
                "amount": 1,
                "spentBefore": before,
                "spentAfter": before + 1,
                "limit": self.limits[REMOTE_ATTEMPT],
            },
            ownerOrdinal=ordinal,
            fetchId=fetch_id,
            attemptCorrelationId=attempt,
        )
        self._budget(
            chain_id,
            "OWNER_STARTED",
            ownerOrdinal=ordinal,
            fetchId=fetch_id,
            attemptCorrelationId=attempt,
        )
        self._fetch_row(chain_id, "OWNER_REGISTERED", fetch_id)
        self._fetch_row(chain_id, "ATTEMPT_STARTED", fetch_id, attempt=1, attemptCorrelationId=attempt)
        self.attempts.append(attempt)
        resolved = revision
        if resolved is None and self.bindings_enabled:
            resolved = f"binding-{self.current_revision}"
        if self.bindings_enabled:
            assert resolved is not None
            self._delivery(
                chain_id,
                "BINDING_SELECTED_FOR_ATTEMPT",
                current=resolved,
                attempt=attempt,
            )
            self.attempt_revisions[attempt] = resolved
        self.lab_ns += 5_000_000
        return Owner(
            chain=chain_id,
            fetch_id=fetch_id,
            attempt=attempt,
            ordinal=ordinal,
            revision=resolved,
            request_id=request_id,
            generation=self.client_generation if generation is None else generation,
            lab_start_ns=self.lab_ns,
        )

    def finish_owner(
        self,
        owner: Owner,
        observation: Mapping[str, Any] | None = None,
    ) -> None:
        self.tick()
        owner.observation = observation
        chain_id = owner.chain
        if observation is None:
            self._budget(
                chain_id,
                "OWNER_FINISHED",
                ownerOrdinal=owner.ordinal,
                fetchId=owner.fetch_id,
                attemptCorrelationId=owner.attempt,
                ownerOutcome="SUCCESS",
            )
            self._fetch_row(
                chain_id,
                "ATTEMPT_COMPLETED",
                owner.fetch_id,
                attempt=1,
                attemptCorrelationId=owner.attempt,
                transportCorrelationId=owner.request_id,
                outcome="SUCCESS",
            )
            self._fetch_row(chain_id, "OWNER_COMPLETED", owner.fetch_id, outcome="SUCCESS")
            self._media_origin_row(owner, 206)
            return
        outcome = fetch_outcome(observation)
        projected = fetch_observation(observation)
        self._budget(
            chain_id,
            "OWNER_FINISHED",
            ownerOrdinal=owner.ordinal,
            fetchId=owner.fetch_id,
            attemptCorrelationId=owner.attempt,
            ownerOutcome=outcome,
        )
        self._fetch_row(
            chain_id,
            "ATTEMPT_FAILED",
            owner.fetch_id,
            attempt=1,
            attemptCorrelationId=owner.attempt,
            transportCorrelationId=owner.request_id,
            outcome=outcome,
            observation=projected,
        )
        self._fetch_row(
            chain_id,
            "OWNER_FAILED",
            owner.fetch_id,
            outcome=outcome,
            observation=projected,
        )
        status = observation["httpStatus"]
        self._media_origin_row(owner, status if status is not None else 500)

    def fail_owner(
        self,
        owner: Owner,
        classification: str,
        decision: str,
        reason: str,
    ) -> Failure:
        observation = owner.observation
        if observation is None:
            raise AssertionError("the owner must finish with an observation first")
        chain = self.chains[owner.chain]
        chain["failure_ordinal"] += 1
        failure_id = f"{owner.chain}:failure-{chain['failure_ordinal']}"
        chain["last_failure_id"] = failure_id
        context = {
            "demandPresent": True,
            "sessionClosing": False,
            "remoteAttemptsRemaining": self.limits[REMOTE_ATTEMPT]
            - chain["spent"][REMOTE_ATTEMPT],
            "deliveryBindingRefreshesRemaining": self.limits.get(DELIVERY_BINDING_REFRESH, 0)
            - chain["spent"].get(DELIVERY_BINDING_REFRESH, 0),
        }
        return Failure(
            chain=owner.chain,
            id=failure_id,
            owner=owner,
            observation=observation,
            classification=classification,
            decision=decision,
            reason=reason,
            context=context,
        )

    def record_failure(
        self,
        failure: Failure,
        action: Mapping[str, Any],
    ) -> dict[str, Any]:
        self.tick()
        owner = failure.owner
        row = {
            "sequence": len(self.failures) + 1,
            "elapsedRealtimeNs": self.elapsed_ns,
            "sessionId": SESSION_ID,
            "recoveryChainId": failure.chain,
            "failureId": failure.id,
            "fetchKey": self.chains[failure.chain]["fetch_key"],
            "fetchId": owner.fetch_id,
            "attemptCorrelationId": owner.attempt,
            "routeEpoch": None,
            "observation": dict(failure.observation),
            "classification": failure.classification,
            "decision": {"kind": failure.decision, "reason": failure.reason},
            "context": dict(failure.context),
            "action": {
                "kind": action["kind"],
                "delayMs": action.get("delayMs"),
                "retryOrdinal": action.get("retryOrdinal"),
                "reconciliation": action.get("reconciliation"),
                "exhaustedDimension": action.get("exhaustedDimension"),
                "providerWait": action.get("providerWait"),
                "deliveryBinding": action.get("deliveryBinding"),
            },
        }
        self.failures.append(row)
        return row

    # -- refresh lifecycle -------------------------------------------------

    def open_refresh(
        self,
        failure: Failure,
        *,
        expected: str | None = None,
        charge: bool = True,
        admitted: bool = True,
        closed: bool = False,
    ) -> RefreshOp:
        chain_id = failure.chain
        resolved = expected if expected is not None else failure.owner.revision
        assert resolved is not None
        state = f"binding-{self.current_revision}"
        self._delivery(
            chain_id,
            "REFRESH_REQUESTED",
            previous=resolved,
            current=state,
            failure_id=failure.id,
        )
        if closed:
            self._delivery(
                chain_id,
                "REFRESH_CLOSED",
                previous=resolved,
                current=state,
                failure_id=failure.id,
            )
            return RefreshOp("CLOSED", chain_id, failure.id, resolved, state, None)
        if resolved != state:
            self._delivery(
                chain_id,
                "REVISION_ALREADY_ADVANCED",
                previous=resolved,
                current=state,
                failure_id=failure.id,
            )
            return RefreshOp("ALREADY_ADVANCED", chain_id, failure.id, resolved, state, None)
        if self.in_flight is not None:
            operation = self.in_flight
            self._delivery(
                chain_id,
                "REFRESH_JOINED",
                previous=resolved,
                current=state,
                refresh=operation.refresh_id,
                failure_id=failure.id,
            )
            return RefreshOp(
                "JOINED",
                chain_id,
                failure.id,
                resolved,
                state,
                operation.refresh_id,
                joined_op=operation,
            )
        self.refresh_counter += 1
        refresh_id = f"refresh-{self.refresh_counter}"
        if not admitted:
            self._delivery(
                chain_id,
                "REFRESH_NOT_ADMITTED",
                previous=resolved,
                current=state,
                refresh=refresh_id,
                failure_id=failure.id,
            )
            return RefreshOp("NOT_ADMITTED", chain_id, failure.id, resolved, state, refresh_id)
        if charge:
            self._charge_refresh(chain_id, failure.id)
        self._delivery(
            chain_id,
            "REFRESH_STARTED",
            previous=resolved,
            current=state,
            refresh=refresh_id,
            failure_id=failure.id,
        )
        operation = RefreshOp("STARTED", chain_id, failure.id, resolved, state, refresh_id)
        self.in_flight = operation
        self.started_operations += 1
        return operation

    def close_refresh(self, operation: RefreshOp, outcome: str = "SUCCEEDED") -> None:
        if operation.kind != "STARTED" or self.in_flight is not operation:
            raise AssertionError("only the in-flight started operation can complete")
        kind = {
            "SUCCEEDED": "REFRESH_SUCCEEDED",
            "FAILED": "REFRESH_FAILED",
            "INCOMPATIBLE": "REFRESH_INCOMPATIBLE",
            "CANCELLED": "REFRESH_CANCELLED",
        }[outcome]
        installed = None
        if outcome == "SUCCEEDED":
            self.current_revision += 1
            installed = f"binding-{self.current_revision}"
            operation.installed = installed
        self._delivery(
            operation.chain,
            kind,
            previous=operation.expected,
            current=installed or operation.expected,
            refresh=operation.refresh_id,
            outcome=outcome,
            failure_id=operation.failure_id,
        )
        self.in_flight = None

    def refresh(
        self,
        failure: Failure,
        *,
        expected: str | None = None,
        outcome: str = "SUCCEEDED",
        charged: bool = True,
    ) -> RefreshOp:
        operation = self.open_refresh(failure, expected=expected, charge=charged)
        self.close_refresh(operation, outcome)
        return operation

    # -- documents ---------------------------------------------------------

    def expected_binding_revisions(self) -> list[str]:
        charges = sorted(
            (
                event
                for event in self.budget
                if event["kind"] == "CHARGE"
                and event["charge"]["dimension"] == REMOTE_ATTEMPT
            ),
            key=lambda event: event["sequence"],
        )
        return [
            self.attempt_revisions[event["attemptCorrelationId"]]
            for event in charges
            if event["attemptCorrelationId"] in self.attempt_revisions
        ]

    def fixture(self, *, with_provider: bool = False) -> Fixture:
        terminals = Counter(chain["terminal"] for chain in self.chains.values())
        case = {
            "schemaVersion": 1,
            "caseId": self.case_id,
            "evidenceSource": self.evidence_source,
            "policyId": self.policy_id,
            "scenarioFamily": self.scenario_family,
            "variant": self.variant,
            "expectedPhysicalAttempts": len(self.attempts),
            "expectedTerminals": dict(terminals),
            "expectedRefreshOperations": self.started_operations,
            "expectedProviderWaits": [
                row["action"]["providerWait"]["waitMs"]
                for row in self.failures
                if row["action"]["kind"] == "WAIT_PROVIDER"
            ],
            "expectedBindingRevisions": self.expected_binding_revisions(),
            "persistedExtentIdsBefore": list(self.persisted_before),
            "persistedExtentIdsAfter": list(self.persisted_after),
            "bindingsEnabled": self.bindings_enabled,
        }
        failures = {
            "schemaVersion": 2,
            "runId": RUN_ID,
            "sessionId": SESSION_ID,
            "policyId": self.policy_id,
            "clockDomain": "ANDROID_MONOTONIC",
            "failures": self.failures,
        }
        budget = {
            "schemaVersion": 1,
            "runId": RUN_ID,
            "sessionId": SESSION_ID,
            "policyId": self.policy_id,
            "clockDomain": "ANDROID_MONOTONIC",
            "events": self.budget,
        }
        delivery = {
            "schemaVersion": 1,
            "runId": RUN_ID,
            "sessionId": SESSION_ID,
            "clockDomain": "ANDROID_MONOTONIC",
            "events": self.delivery,
        }
        faults = None
        origin = None
        if with_provider:
            faults = {
                "schemaVersion": 1,
                "runId": RUN_ID,
                "sessionId": SESSION_ID,
                "scenarioFamily": self.scenario_family,
                "variant": self.variant,
                "primaryPlane": "PROVIDER",
                "scenarioHash": SCENARIO_HASH,
                "clockDomain": "HOST_MEDIA_LAB_MONOTONIC",
                "providerWallClockDomain": "PROVIDER_WALL_CLOCK",
                "providerWallClockEpochMs": PROVIDER_WALL_CLOCK_NOW,
                "events": self.faults,
            }
            origin = list(self.origin)
        return Fixture(
            name=self.case_id,
            failures=failures,
            budget=budget,
            delivery=delivery,
            fetch=self.fetch,
            case=case,
            faults=faults,
            origin=origin,
        )


# ---------------------------------------------------------------------------
# The 14 host provider cases (C2) and the deterministic provider scenarios
# ---------------------------------------------------------------------------


def n8_bare_403() -> Fixture:
    run = ProviderRun("n8-bare-403", scenario_family="N8", variant="HTTP_403_BARE")
    run.start_chain("recovery-1")
    owner = run.begin_owner("recovery-1", generation=1)
    observation = http_observation(
        403, provider_signal="NONE", delivery_binding_revision="binding-1"
    )
    run.finish_owner(owner, observation)
    run.media_fault(owner, kind="HTTP_403_BARE", status=403, signal="NONE", generation=1)
    failure = run.fail_owner(
        owner, "PROVIDER_REJECTED", "FAIL_TERMINAL", "NON_RETRYABLE_CLASSIFICATION"
    )
    row = run.record_failure(failure, {"kind": "TERMINATE_FAILURE"})
    run.terminate("recovery-1", "TERMINAL_FAILURE", row["failureId"])
    return run.fixture(with_provider=True)


def _rate_limited_wait_run(
    case_id: str,
    variant: str,
    retry_after: Mapping[str, Any],
    *,
    wall_clock_now: int | None = None,
) -> Fixture:
    run = ProviderRun(case_id, scenario_family="N9", variant=variant)
    run.start_chain("recovery-1")
    owner = run.begin_owner("recovery-1", generation=1)
    observation = http_observation(429, retry_after=retry_after, delivery_binding_revision="binding-1")
    run.finish_owner(owner, observation)
    run.media_fault(owner, kind="HTTP_429", status=429, retry_after=retry_after, generation=1)
    failure = run.fail_owner(
        owner, "PROVIDER_RATE_LIMITED", "WAIT_UNTIL_PROVIDER", "PROVIDER_THROTTLED"
    )
    wait = provider_wait_for(retry_after, wall_clock_now)
    run.record_failure(failure, {"kind": "WAIT_PROVIDER", "providerWait": wait})
    run.wait_ms(wait["waitMs"])
    run.lab_advance_ms(wait["waitMs"])
    second = run.begin_owner("recovery-1", generation=1)
    run.finish_owner(second)
    run.terminate("recovery-1", "SUCCESS")
    return run.fixture(with_provider=True)


def n9_delay_seconds() -> Fixture:
    return _rate_limited_wait_run(
        "n9-delay-seconds",
        "HTTP_429_RETRY_AFTER_DELAY_SECONDS",
        retry_after_delay(2),
    )


def n9_http_date() -> Fixture:
    return _rate_limited_wait_run(
        "n9-http-date",
        "HTTP_429_RETRY_AFTER_HTTP_DATE",
        retry_after_date(PROVIDER_WALL_CLOCK_NOW + 2000),
        wall_clock_now=PROVIDER_WALL_CLOCK_NOW,
    )


def _rate_limited_terminal_run(
    case_id: str,
    variant: str,
    retry_after: Mapping[str, Any],
    reason: str,
) -> Fixture:
    run = ProviderRun(case_id, scenario_family="N9", variant=variant)
    run.start_chain("recovery-1")
    owner = run.begin_owner("recovery-1", generation=1)
    observation = http_observation(429, retry_after=retry_after, delivery_binding_revision="binding-1")
    run.finish_owner(owner, observation)
    run.media_fault(owner, kind="HTTP_429", status=429, retry_after=retry_after, generation=1)
    failure = run.fail_owner(owner, "PROVIDER_RATE_LIMITED", "FAIL_TERMINAL", reason)
    row = run.record_failure(failure, {"kind": "TERMINATE_FAILURE"})
    run.terminate("recovery-1", "TERMINAL_FAILURE", row["failureId"])
    return run.fixture(with_provider=True)


def n9_retry_after_absent() -> Fixture:
    return _rate_limited_terminal_run(
        "n9-retry-after-absent",
        "HTTP_429_RETRY_AFTER_ABSENT",
        retry_after_absent(),
        "RETRY_AFTER_ABSENT",
    )


def n9_retry_after_malformed() -> Fixture:
    return _rate_limited_terminal_run(
        "n9-retry-after-malformed",
        "HTTP_429_RETRY_AFTER_MALFORMED",
        retry_after_malformed(),
        "RETRY_AFTER_MALFORMED",
    )


def _stale_observation(revision: str) -> dict[str, Any]:
    return http_observation(
        403, provider_signal="BINDING_STALE_CONFIRMED", delivery_binding_revision=revision
    )


def _stale_owner(run: ProviderRun, chain_id: str, revision: str, generation: int):
    owner = run.begin_owner(chain_id, revision=revision, generation=generation)
    observation = _stale_observation(revision)
    run.finish_owner(owner, observation)
    run.media_fault(
        owner,
        kind="BINDING_STALE",
        status=403,
        signal="BINDING_STALE_CONFIRMED",
        generation=generation,
    )
    return owner, observation


def _stale_failure(run: ProviderRun, owner, observation) -> Failure:
    return run.fail_owner(
        owner, "DELIVERY_BINDING_STALE", "REFRESH_DELIVERY_BINDING", "STALE_BINDING_SIGNAL"
    )


def n10_binding_expired_refresh() -> Fixture:
    run = ProviderRun(
        "n10-binding-expired-refresh", scenario_family="N10", variant="BINDING_EXPIRY_REFRESH"
    )
    run.start_chain("recovery-1")
    owner, observation = _stale_owner(run, "recovery-1", "binding-1", 1)
    failure = _stale_failure(run, owner, observation)
    operation = run.open_refresh(failure)
    run.provider_refresh("REFRESH_SUCCEEDED", 200, new_generation=2)
    run.close_refresh(operation, "SUCCEEDED")
    run.record_failure(failure, {
        "kind": "REFRESH_DELIVERY_BINDING",
        "deliveryBinding": binding_record(
            "binding-1", "REFRESHED", current="binding-2",
            correlation=operation.refresh_id, charged=True,
        ),
    })
    second = run.begin_owner("recovery-1", generation=2)
    run.finish_owner(second)
    run.terminate("recovery-1", "SUCCESS")
    return run.fixture(with_provider=True)


def n10_refresh_incompatible() -> Fixture:
    run = ProviderRun(
        "n10-refresh-incompatible", scenario_family="N10", variant="BINDING_REFRESH_INCOMPATIBLE"
    )
    run.start_chain("recovery-1")
    owner, observation = _stale_owner(run, "recovery-1", "binding-1", 1)
    failure = _stale_failure(run, owner, observation)
    operation = run.open_refresh(failure)
    run.provider_refresh("REFRESH_INCOMPATIBLE", 200, new_generation=2)
    run.close_refresh(operation, "INCOMPATIBLE")
    row = run.record_failure(failure, {
        "kind": "REFRESH_DELIVERY_BINDING",
        "deliveryBinding": binding_record(
            "binding-1", "INCOMPATIBLE", correlation=operation.refresh_id, charged=True
        ),
    })
    run.terminate("recovery-1", "TERMINAL_FAILURE", row["failureId"])
    return run.fixture(with_provider=True)


def n10_refresh_failed() -> Fixture:
    run = ProviderRun(
        "n10-refresh-failed", scenario_family="N10", variant="BINDING_REFRESH_FAILED"
    )
    run.start_chain("recovery-1")
    owner, observation = _stale_owner(run, "recovery-1", "binding-1", 1)
    failure = _stale_failure(run, owner, observation)
    operation = run.open_refresh(failure)
    run.provider_refresh("REFRESH_FAILED", 503)
    run.close_refresh(operation, "FAILED")
    row = run.record_failure(failure, {
        "kind": "REFRESH_DELIVERY_BINDING",
        "deliveryBinding": binding_record(
            "binding-1", "FAILED", correlation=operation.refresh_id, charged=True
        ),
    })
    run.terminate("recovery-1", "TERMINAL_FAILURE", row["failureId"])
    return run.fixture(with_provider=True)


def n10_already_advanced() -> Fixture:
    run = ProviderRun("n10-already-advanced", scenario_family="N10", variant=None)
    run.start_chain("recovery-1", fetch_key=FETCH_KEY, extent_id=EXTENT_ID)
    run.start_chain("recovery-2", fetch_key=SECOND_FETCH_KEY, extent_id=SECOND_EXTENT_ID)
    owner_a, observation_a = _stale_owner(run, "recovery-1", "binding-1", 1)
    failure_a = _stale_failure(run, owner_a, observation_a)
    owner_b, observation_b = _stale_owner(run, "recovery-2", "binding-1", 1)
    failure_b = _stale_failure(run, owner_b, observation_b)
    operation = run.open_refresh(failure_a)
    run.provider_refresh("REFRESH_SUCCEEDED", 200, new_generation=2)
    run.close_refresh(operation, "SUCCEEDED")
    run.record_failure(failure_a, {
        "kind": "REFRESH_DELIVERY_BINDING",
        "deliveryBinding": binding_record(
            "binding-1", "REFRESHED", current="binding-2",
            correlation=operation.refresh_id, charged=True,
        ),
    })
    run.open_refresh(failure_b)
    run.record_failure(failure_b, {
        "kind": "REFRESH_DELIVERY_BINDING",
        "deliveryBinding": binding_record(
            "binding-1", "ALREADY_ADVANCED", current="binding-2", charged=False
        ),
    })
    second_a = run.begin_owner("recovery-1", generation=2)
    run.finish_owner(second_a)
    second_b = run.begin_owner("recovery-2", generation=2)
    run.finish_owner(second_b)
    run.terminate("recovery-1", "SUCCESS")
    run.terminate("recovery-2", "SUCCESS")
    return run.fixture()


def n10_concurrent_single_flight() -> Fixture:
    run = ProviderRun("n10-concurrent-single-flight", scenario_family="N10", variant=None)
    run.start_chain("recovery-1", fetch_key=FETCH_KEY, extent_id=EXTENT_ID)
    run.start_chain("recovery-2", fetch_key=SECOND_FETCH_KEY, extent_id=SECOND_EXTENT_ID)
    owner_a, observation_a = _stale_owner(run, "recovery-1", "binding-1", 1)
    failure_a = _stale_failure(run, owner_a, observation_a)
    owner_b, observation_b = _stale_owner(run, "recovery-2", "binding-1", 1)
    failure_b = _stale_failure(run, owner_b, observation_b)
    operation = run.open_refresh(failure_a)
    run.open_refresh(failure_b)
    run.provider_refresh("REFRESH_SUCCEEDED", 200, new_generation=2)
    run.close_refresh(operation, "SUCCEEDED")
    run.record_failure(failure_a, {
        "kind": "REFRESH_DELIVERY_BINDING",
        "deliveryBinding": binding_record(
            "binding-1", "REFRESHED", current="binding-2",
            correlation=operation.refresh_id, charged=True,
        ),
    })
    run.record_failure(failure_b, {
        "kind": "REFRESH_DELIVERY_BINDING",
        "deliveryBinding": binding_record(
            "binding-1", "JOINED_REFRESH", current="binding-2",
            correlation=operation.refresh_id, charged=False,
        ),
    })
    second_a = run.begin_owner("recovery-1", generation=2)
    run.finish_owner(second_a)
    second_b = run.begin_owner("recovery-2", generation=2)
    run.finish_owner(second_b)
    run.terminate("recovery-1", "SUCCESS")
    run.terminate("recovery-2", "SUCCESS")
    return run.fixture()


def refresh_budget_exhausted() -> Fixture:
    run = ProviderRun(
        "refresh-budget-exhausted",
        scenario_family="N10",
        variant="BINDING_EXPIRY_REFRESH",
    )
    run.start_chain("recovery-1")
    owner, observation = _stale_owner(run, "recovery-1", "binding-1", 1)
    failure = _stale_failure(run, owner, observation)
    operation = run.open_refresh(failure)
    run.provider_refresh("REFRESH_SUCCEEDED", 200, new_generation=2)
    run.close_refresh(operation, "SUCCEEDED")
    run.record_failure(failure, {
        "kind": "REFRESH_DELIVERY_BINDING",
        "deliveryBinding": binding_record(
            "binding-1", "REFRESHED", current="binding-2",
            correlation=operation.refresh_id, charged=True,
        ),
    })
    second_owner, second_observation = _stale_owner(run, "recovery-1", "binding-2", 2)
    second_failure = _stale_failure(run, second_owner, second_observation)
    run.open_refresh(second_failure, admitted=False)
    row = run.record_failure(second_failure, {
        "kind": "REFRESH_DELIVERY_BINDING",
        "exhaustedDimension": DELIVERY_BINDING_REFRESH,
        "deliveryBinding": binding_record(
            "binding-2", "NOT_ADMITTED", charged=False,
        ),
    })
    run.terminate("recovery-1", "BUDGET_EXHAUSTED", row["failureId"])
    return run.fixture(with_provider=True)


def provider_wait_cancelled() -> Fixture:
    run = ProviderRun(
        "provider-wait-cancelled",
        scenario_family="N9",
        variant="HTTP_429_RETRY_AFTER_DELAY_SECONDS",
    )
    run.start_chain("recovery-1")
    owner = run.begin_owner("recovery-1", generation=1)
    retry_after = retry_after_delay(30)
    observation = http_observation(429, retry_after=retry_after, delivery_binding_revision="binding-1")
    run.finish_owner(owner, observation)
    run.media_fault(owner, kind="HTTP_429", status=429, retry_after=retry_after, generation=1)
    failure = run.fail_owner(
        owner, "PROVIDER_RATE_LIMITED", "WAIT_UNTIL_PROVIDER", "PROVIDER_THROTTLED"
    )
    wait = provider_wait_for(retry_after)
    run.record_failure(failure, {"kind": "WAIT_PROVIDER", "providerWait": wait})
    run.terminate("recovery-1", "NO_REMAINING_DEMAND")
    return run.fixture(with_provider=True)


def shutdown_during_refresh() -> Fixture:
    run = ProviderRun("shutdown-during-refresh", scenario_family="N10", variant=None)
    run.start_chain("recovery-1")
    owner, observation = _stale_owner(run, "recovery-1", "binding-1", 1)
    failure = _stale_failure(run, owner, observation)
    operation = run.open_refresh(failure)
    run.close_refresh(operation, "CANCELLED")
    run.record_failure(failure, {
        "kind": "REFRESH_DELIVERY_BINDING",
        "deliveryBinding": binding_record("binding-1", "ABANDONED", charged=True),
    })
    run.terminate("recovery-1", "SESSION_TERMINATION")
    return run.fixture()


def refresh_abandoned_before_operation() -> Fixture:
    """The chain stops waiting before any refresh operation id reaches it.

    DESIGN 4.6 records such a wait as ABANDONED with no correlation id and no
    charge: the coordinator never returned a result for this chain.
    """

    run = ProviderRun("refresh-abandoned-before-operation", scenario_family="N10", variant=None)
    run.start_chain("recovery-1")
    owner, observation = _stale_owner(run, "recovery-1", "binding-1", 1)
    failure = _stale_failure(run, owner, observation)
    run.record_failure(failure, {
        "kind": "REFRESH_DELIVERY_BINDING",
        "deliveryBinding": binding_record("binding-1", "ABANDONED", charged=False),
    })
    run.terminate("recovery-1", "NO_REMAINING_DEMAND")
    return run.fixture()


def shutdown_during_refresh_with_joiner() -> Fixture:
    """Session shutdown cancels one shared refresh both chains waited on.

    The initiator paid the refresh charge; the joiner is abandoned for free.
    """

    run = ProviderRun(
        "shutdown-during-refresh-with-joiner", scenario_family="N10", variant=None
    )
    run.start_chain("recovery-1", fetch_key=FETCH_KEY, extent_id=EXTENT_ID)
    run.start_chain("recovery-2", fetch_key=SECOND_FETCH_KEY, extent_id=SECOND_EXTENT_ID)
    owner_a, observation_a = _stale_owner(run, "recovery-1", "binding-1", 1)
    failure_a = _stale_failure(run, owner_a, observation_a)
    owner_b, observation_b = _stale_owner(run, "recovery-2", "binding-1", 1)
    failure_b = _stale_failure(run, owner_b, observation_b)
    operation = run.open_refresh(failure_a)
    run.open_refresh(failure_b)
    run.close_refresh(operation, "CANCELLED")
    run.record_failure(failure_a, {
        "kind": "REFRESH_DELIVERY_BINDING",
        "deliveryBinding": binding_record(
            "binding-1", "ABANDONED", correlation=operation.refresh_id, charged=True
        ),
    })
    run.record_failure(failure_b, {
        "kind": "REFRESH_DELIVERY_BINDING",
        "deliveryBinding": binding_record(
            "binding-1", "ABANDONED", correlation=operation.refresh_id, charged=False
        ),
    })
    run.terminate("recovery-1", "SESSION_TERMINATION")
    run.terminate("recovery-2", "SESSION_TERMINATION")
    return run.fixture()


def rate_limit_budget_exhausted() -> Fixture:
    run = ProviderRun(
        "rate-limit-budget-exhausted",
        scenario_family="N9",
        variant="HTTP_429_RETRY_AFTER_DELAY_SECONDS",
    )
    run.start_chain("recovery-1")
    retry_after = retry_after_delay(0)
    for attempt in range(4):
        owner = run.begin_owner("recovery-1", generation=1)
        observation = http_observation(429, retry_after=retry_after, delivery_binding_revision="binding-1")
        run.finish_owner(owner, observation)
        run.media_fault(owner, kind="HTTP_429", status=429, retry_after=retry_after, generation=1)
        failure = run.fail_owner(
            owner, "PROVIDER_RATE_LIMITED", "WAIT_UNTIL_PROVIDER", "PROVIDER_THROTTLED"
        )
        if attempt < 3:
            wait = provider_wait_for(retry_after)
            run.record_failure(failure, {"kind": "WAIT_PROVIDER", "providerWait": wait})
            run.wait_ms(wait["waitMs"])
            run.lab_advance_ms(wait["waitMs"])
        else:
            row = run.record_failure(failure, {
                "kind": "TERMINATE_BUDGET_EXHAUSTED",
                "exhaustedDimension": REMOTE_ATTEMPT,
            })
    run.terminate("recovery-1", "BUDGET_EXHAUSTED", row["failureId"])
    return run.fixture(with_provider=True)


SCENARIOS = {
    "n8-bare-403": n8_bare_403,
    "n9-delay-seconds": n9_delay_seconds,
    "n9-http-date": n9_http_date,
    "n9-retry-after-absent": n9_retry_after_absent,
    "n9-retry-after-malformed": n9_retry_after_malformed,
    "n10-binding-expired-refresh": n10_binding_expired_refresh,
    "n10-refresh-incompatible": n10_refresh_incompatible,
    "n10-refresh-failed": n10_refresh_failed,
    "n10-already-advanced": n10_already_advanced,
    "n10-concurrent-single-flight": n10_concurrent_single_flight,
    "refresh-budget-exhausted": refresh_budget_exhausted,
    "provider-wait-cancelled": provider_wait_cancelled,
    "shutdown-during-refresh": shutdown_during_refresh,
    "rate-limit-budget-exhausted": rate_limit_budget_exhausted,
}

CASE_IDS = tuple(SCENARIOS)


def all_fixtures() -> dict[str, Fixture]:
    return {name: builder() for name, builder in SCENARIOS.items()}


# ---------------------------------------------------------------------------
# Mutation helpers
# ---------------------------------------------------------------------------


def budget_events(fixture: Fixture, kind: str, chain: str | None = None) -> list[dict[str, Any]]:
    return [
        event
        for event in fixture.budget["events"]
        if event["kind"] == kind
        and (chain is None or event["recoveryChainId"] == chain)
    ]


def remote_charges(fixture: Fixture) -> list[dict[str, Any]]:
    return [
        event
        for event in budget_events(fixture, "CHARGE")
        if event["charge"]["dimension"] == REMOTE_ATTEMPT
    ]


def refresh_charges(fixture: Fixture) -> list[dict[str, Any]]:
    return [
        event
        for event in budget_events(fixture, "CHARGE")
        if event["charge"]["dimension"] == DELIVERY_BINDING_REFRESH
    ]


def delivery_events(fixture: Fixture, kind: str) -> list[dict[str, Any]]:
    return [event for event in fixture.delivery["events"] if event["kind"] == kind]


def failure_rows(fixture: Fixture) -> list[dict[str, Any]]:
    return fixture.failures["failures"]


def resequence_budget(fixture: Fixture) -> None:
    for index, event in enumerate(fixture.budget["events"]):
        event["sequence"] = index + 1


def resequence_delivery(fixture: Fixture) -> None:
    for index, event in enumerate(fixture.delivery["events"]):
        event["sequence"] = index + 1


def resequence_fetch(fixture: Fixture) -> None:
    for index, row in enumerate(fixture.fetch):
        row["eventSequence"] = index


def drop_refresh_charge(fixture: Fixture) -> dict[str, Any]:
    """Drop the refresh charge and keep every later ledger snapshot consistent."""

    charge = refresh_charges(fixture)[0]
    fixture.budget["events"].remove(charge)
    for event in fixture.budget["events"]:
        if event["spent"].get(DELIVERY_BINDING_REFRESH, 0) > 0:
            event["spent"][DELIVERY_BINDING_REFRESH] -= 1
    resequence_budget(fixture)
    return charge


def load_host_case(case_dir: pathlib.Path) -> Fixture:
    """Load one real `ProviderRecoveryEvidenceHostTest` case directory."""

    def load(name: str) -> Any:
        return json.loads((case_dir / name).read_text(encoding="utf-8"))

    fetch = [
        json.loads(line)
        for line in (case_dir / "fetch-events.jsonl").read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    return Fixture(
        name=case_dir.name,
        failures=load("failure-decision-events.json"),
        budget=load("recovery-budget-events.json"),
        delivery=load("delivery-binding-events.json"),
        fetch=fetch,
        case=load("case.json"),
    )


def host_cases_root() -> pathlib.Path:
    return REPO_ROOT / "core" / "engine" / "build" / "m2-d-provider"
