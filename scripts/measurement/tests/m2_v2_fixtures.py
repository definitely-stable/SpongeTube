"""Synthetic `failure-decision-events-v2` fixtures for the recovery oracle tests.

Two kinds of evidence are provided:

* an up-conversion of the committed M2-C v1 runtime example (failures, budget
  and `fetch-events-v4`) to `sponge-recovery-test-v2`; and
* hand-built minimal v2 runs covering the M2-D provider recovery decisions
  (provider wait, binding refresh, terminal classifications).

Every builder emits a structurally complete run: the RecoveryChain ledger, the
FetchBroker owner ledger and the failure rows share the same correlation
identities, so `m2_recovery_oracle.verify_recovery` re-derives the whole
lineage. Falsification tests mutate one aspect of a valid run.
"""

from __future__ import annotations

import json
import pathlib
from typing import Any, Mapping

SCRIPT_DIR = pathlib.Path(__file__).resolve().parents[1]
REPO_ROOT = SCRIPT_DIR.parents[1]
EXAMPLES = REPO_ROOT / ".work" / "schemas" / "examples" / "m2"

REMOTE_ATTEMPT = "REMOTE_ATTEMPT"
DELIVERY_BINDING_REFRESH = "DELIVERY_BINDING_REFRESH"
POLICY_ID = "sponge-recovery-test-v2"
LIMITS = {REMOTE_ATTEMPT: 4, DELIVERY_BINDING_REFRESH: 1}
SESSION_ID = "m2-d-host-recovery-v2"
RUN_ID = "m2-d-host-recovery-v2-run"
FETCH_KEY = "fixture:M2D/video/transient"
EXTENT_ID = "m2d:video:transient"

# Fixed virtual provider wall clock of the Media Lab provider simulator
# (2026-09-25T12:00:00Z).
PROVIDER_WALL_CLOCK_NOW = 1_790_337_600_000


# ---------------------------------------------------------------------------
# Observations
# ---------------------------------------------------------------------------


def retry_after_absent() -> dict[str, Any]:
    return {
        "rawKind": "ABSENT",
        "delaySeconds": None,
        "notBeforeUtcEpochMs": None,
        "clockDomain": None,
    }


def retry_after_delay(seconds: int) -> dict[str, Any]:
    return {
        "rawKind": "DELAY_SECONDS",
        "delaySeconds": seconds,
        "notBeforeUtcEpochMs": None,
        "clockDomain": None,
    }


def retry_after_date(not_before_utc_epoch_ms: int) -> dict[str, Any]:
    return {
        "rawKind": "HTTP_DATE",
        "delaySeconds": None,
        "notBeforeUtcEpochMs": not_before_utc_epoch_ms,
        "clockDomain": "PROVIDER_WALL_CLOCK",
    }


def retry_after_malformed() -> dict[str, Any]:
    return {
        "rawKind": "MALFORMED",
        "delaySeconds": None,
        "notBeforeUtcEpochMs": None,
        "clockDomain": None,
    }


def http_observation(
    status: int,
    *,
    retry_after: Mapping[str, Any] | None = None,
    provider_signal: str = "NONE",
    delivery_binding_revision: str | None = None,
) -> dict[str, Any]:
    return {
        "plane": "PROVIDER",
        "type": "HTTP_RESPONSE",
        "kind": "HTTP_STATUS",
        "httpStatus": status,
        "retryAfter": dict(retry_after) if retry_after is not None else retry_after_absent(),
        "providerSignal": provider_signal,
        "deliveryBindingRevision": delivery_binding_revision,
    }


def transport_observation(kind: str = "READ_TIMEOUT") -> dict[str, Any]:
    return {
        "plane": "TRANSPORT",
        "type": "TRANSPORT_IO",
        "kind": kind,
        "httpStatus": None,
        "retryAfter": None,
        "providerSignal": None,
        "deliveryBindingRevision": None,
    }


def provider_wait_for(
    retry_after: Mapping[str, Any],
    wall_clock_now: int | None = None,
) -> dict[str, Any]:
    """Executed ProviderWaitRecord for a valid Retry-After observation."""

    if retry_after["rawKind"] == "DELAY_SECONDS":
        return {
            "rawKind": "DELAY_SECONDS",
            "waitMs": retry_after["delaySeconds"] * 1000,
            "notBeforeUtcEpochMs": None,
            "wallClockNowUtcEpochMs": None,
            "wallClockDomain": None,
        }
    if retry_after["rawKind"] == "HTTP_DATE":
        assert wall_clock_now is not None
        return {
            "rawKind": "HTTP_DATE",
            "waitMs": max(0, retry_after["notBeforeUtcEpochMs"] - wall_clock_now),
            "notBeforeUtcEpochMs": retry_after["notBeforeUtcEpochMs"],
            "wallClockNowUtcEpochMs": wall_clock_now,
            "wallClockDomain": "PROVIDER_WALL_CLOCK",
        }
    raise AssertionError(f"no provider wait for {retry_after['rawKind']!r}")


def refreshed_binding(
    expected_revision: str,
    current_revision: str,
    refresh_correlation_id: str,
    *,
    charged: bool = True,
) -> dict[str, Any]:
    return {
        "expectedRevision": expected_revision,
        "result": "REFRESHED",
        "currentRevision": current_revision,
        "refreshCorrelationId": refresh_correlation_id,
        "charged": charged,
    }


def fetch_outcome(observation: Mapping[str, Any]) -> str:
    """M1 fetch-events projection of an observation (legacy owner outcome)."""

    kind_type = observation["type"]
    kind = observation["kind"]
    if kind_type == "TRANSPORT_IO":
        return (
            "TERMINAL_TRANSPORT_FAILURE"
            if kind == "TARGET_UNRESOLVED"
            else "RETRYABLE_TRANSPORT_FAILURE"
        )
    if kind_type == "HTTP_RESPONSE":
        status = observation["httpStatus"]
        return (
            "RETRYABLE_TRANSPORT_FAILURE"
            if status in (408, 429) or status >= 500
            else "TERMINAL_TRANSPORT_FAILURE"
        )
    return {
        "DELIVERY_DESCRIPTOR": "DESCRIPTOR_STALE",
        "RANGE_PROTOCOL": "RANGE_REJECTED",
        "CONTENT_INTEGRITY": "CONTENT_INTEGRITY_REJECTED",
        "INTERNAL": "INTERNAL_FAILURE",
    }.get(kind_type) or {
        ("STORAGE", "NO_SPACE"): "STORAGE_NO_SPACE",
        ("STORAGE", "IO"): "STORAGE_IO",
        ("STORAGE", "CONFLICT"): "STORAGE_CONFLICT",
        ("CANCELLATION", "NO_CONSUMERS"): "CANCELLED_NO_CONSUMERS",
        ("CANCELLATION", "SESSION_SHUTDOWN"): "CANCELLED_BROKER_SHUTDOWN",
    }[(kind_type, kind)]


def fetch_observation(observation: Mapping[str, Any]) -> dict[str, Any]:
    """fetch-events-v4 observation projection (typed fields only)."""

    return {
        "plane": observation["plane"],
        "type": observation["type"],
        "kind": observation["kind"],
        "httpStatus": observation["httpStatus"],
    }


# ---------------------------------------------------------------------------
# Run builder
# ---------------------------------------------------------------------------


class Run:
    """Builds one synthetic v2 run (failure rows, budget events, fetch rows)."""

    def __init__(
        self,
        *,
        policy_id: str = POLICY_ID,
        limits: Mapping[str, int] | None = None,
        session_id: str = SESSION_ID,
        run_id: str = RUN_ID,
        fetch_key: str = FETCH_KEY,
        extent_id: str = EXTENT_ID,
    ) -> None:
        self.policy_id = policy_id
        self.limits = dict(limits) if limits is not None else dict(LIMITS)
        self.session_id = session_id
        self.run_id = run_id
        self.fetch_key = fetch_key
        self.extent_id = extent_id
        self.events: list[dict[str, Any]] = []
        self.failures: list[dict[str, Any]] = []
        self.fetch: list[dict[str, Any]] = []
        self.chain_id = "recovery-1"
        self.spent = {dimension: 0 for dimension in self.limits}
        self.elapsed = 1_000_000
        self.owner_ordinal = 0
        self.fetch_ordinal = 0
        self.failure_ordinal = 0
        self.last_fetch_id: str | None = None

    # -- ledger ------------------------------------------------------------

    def tick(self, delta: int = 1_000) -> int:
        self.elapsed += delta
        return self.elapsed

    def _event(self, kind: str, **overrides: Any) -> dict[str, Any]:
        event = {
            "sequence": len(self.events) + 1,
            "elapsedRealtimeNs": self.elapsed,
            "sessionId": self.session_id,
            "recoveryChainId": self.chain_id,
            "kind": kind,
            "fetchKey": self.fetch_key,
            "extentId": self.extent_id,
            "policyId": self.policy_id,
            "limits": dict(self.limits),
            "spent": dict(self.spent),
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
        self.events.append(event)
        return event

    def chain_started(self) -> dict[str, Any]:
        return self._event("CHAIN_STARTED")

    def consumer_joined(self, consumer: str = "playback") -> dict[str, Any]:
        return self._event("CONSUMER_JOINED", consumerId=consumer, consumerKind="PLAYBACK")

    def begin_owner(self) -> str:
        """Emit the attempt gate, REMOTE_ATTEMPT charge and owner start."""

        self.tick()
        self.owner_ordinal += 1
        self.fetch_ordinal += 1
        fetch_id = f"fetch-{self.fetch_ordinal}"
        correlation = f"{fetch_id}:attempt-1"
        self._event("ATTEMPT_PERMIT_WAIT")
        self._event(
            "ATTEMPT_PERMIT_GRANTED",
            permit={"routeEpoch": None, "reason": "ALWAYS_PERMIT"},
        )
        before = self.spent[REMOTE_ATTEMPT]
        self.spent[REMOTE_ATTEMPT] = before + 1
        self._event(
            "CHARGE",
            charge={
                "dimension": REMOTE_ATTEMPT,
                "amount": 1,
                "spentBefore": before,
                "spentAfter": before + 1,
                "limit": self.limits[REMOTE_ATTEMPT],
            },
            ownerOrdinal=self.owner_ordinal,
            fetchId=fetch_id,
            attemptCorrelationId=correlation,
        )
        self._event(
            "OWNER_STARTED",
            ownerOrdinal=self.owner_ordinal,
            fetchId=fetch_id,
            attemptCorrelationId=correlation,
        )
        self._fetch_row("OWNER_REGISTERED", fetch_id)
        self._fetch_row(
            "ATTEMPT_STARTED",
            fetch_id,
            attempt=1,
            attemptCorrelationId=correlation,
        )
        self.last_fetch_id = fetch_id
        return fetch_id

    def end_owner(
        self,
        fetch_id: str,
        observation: Mapping[str, Any] | None = None,
    ) -> None:
        """Finish the owner as SUCCESS (no observation) or as its projection."""

        self.tick()
        correlation = f"{fetch_id}:attempt-1"
        if observation is None:
            outcome = "SUCCESS"
            self._event(
                "OWNER_FINISHED",
                ownerOrdinal=self.owner_ordinal,
                fetchId=fetch_id,
                attemptCorrelationId=correlation,
                ownerOutcome=outcome,
            )
            self._fetch_row(
                "ATTEMPT_COMPLETED",
                fetch_id,
                attempt=1,
                attemptCorrelationId=correlation,
                transportCorrelationId=f"lab-{fetch_id}",
                outcome=outcome,
            )
            self._fetch_row("OWNER_COMPLETED", fetch_id, outcome=outcome)
            return
        outcome = fetch_outcome(observation)
        projected = fetch_observation(observation)
        self._event(
            "OWNER_FINISHED",
            ownerOrdinal=self.owner_ordinal,
            fetchId=fetch_id,
            attemptCorrelationId=correlation,
            ownerOutcome=outcome,
        )
        self._fetch_row(
            "ATTEMPT_FAILED",
            fetch_id,
            attempt=1,
            attemptCorrelationId=correlation,
            outcome=outcome,
            observation=projected,
        )
        self._fetch_row("OWNER_FAILED", fetch_id, outcome=outcome, observation=projected)

    def failure(
        self,
        observation: Mapping[str, Any],
        classification: str,
        decision: str,
        reason: str,
        action: Mapping[str, Any],
    ) -> dict[str, Any]:
        """Append the failure row of the most recently finished owner."""

        self.tick()
        self.failure_ordinal += 1
        failure_id = f"{self.chain_id}:failure-{self.failure_ordinal}"
        fetch_id = self.last_fetch_id
        row = {
            "sequence": len(self.failures) + 1,
            "elapsedRealtimeNs": self.elapsed,
            "sessionId": self.session_id,
            "recoveryChainId": self.chain_id,
            "failureId": failure_id,
            "fetchKey": self.fetch_key,
            "fetchId": fetch_id,
            "attemptCorrelationId": f"{fetch_id}:attempt-1",
            "routeEpoch": None,
            "observation": dict(observation),
            "classification": classification,
            "decision": {"kind": decision, "reason": reason},
            "context": {
                "demandPresent": True,
                "sessionClosing": False,
                "remoteAttemptsRemaining": self.limits[REMOTE_ATTEMPT]
                - self.spent[REMOTE_ATTEMPT],
                "deliveryBindingRefreshesRemaining": self.limits.get(
                    DELIVERY_BINDING_REFRESH, 0
                )
                - self.spent.get(DELIVERY_BINDING_REFRESH, 0),
            },
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

    def refresh_charge(self, failure_id: str) -> dict[str, Any]:
        """Charge DELIVERY_BINDING_REFRESH for one executed provider operation."""

        self.tick()
        before = self.spent[DELIVERY_BINDING_REFRESH]
        self.spent[DELIVERY_BINDING_REFRESH] = before + 1
        return self._event(
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

    def backoff(
        self,
        failure_id: str,
        retry_ordinal: int,
        delay_ms: int,
        window_ms: int,
    ) -> None:
        backoff = {
            "retryOrdinal": retry_ordinal,
            "windowMs": window_ms,
            "delayMs": delay_ms,
        }
        self._event("BACKOFF_SCHEDULED", backoff=dict(backoff), failureId=failure_id)
        self._event("BACKOFF_COMPLETED", backoff=dict(backoff), failureId=failure_id)

    def terminate(self, reason: str, failure_id: str | None = None) -> dict[str, Any]:
        self.tick()
        return self._event("CHAIN_TERMINATED", terminalReason=reason, failureId=failure_id)

    # -- fetch ledger ------------------------------------------------------

    def _fetch_row(self, event: str, fetch_id: str, **overrides: Any) -> dict[str, Any]:
        row = {
            "schemaVersion": 4,
            "eventSequence": len(self.fetch),
            "eventElapsedRealtimeNs": self.elapsed,
            "sessionId": self.session_id,
            "fetchId": fetch_id,
            "fetchKey": self.fetch_key,
            "attempt": None,
            "attemptCorrelationId": None,
            "transportCorrelationId": None,
            "event": event,
            "consumerIds": [f"recovery:{self.chain_id}:{self.owner_ordinal}"],
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

    # -- documents ---------------------------------------------------------

    def documents(
        self,
    ) -> tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]]]:
        failures = {
            "schemaVersion": 2,
            "runId": self.run_id,
            "sessionId": self.session_id,
            "policyId": self.policy_id,
            "clockDomain": "ANDROID_MONOTONIC",
            "failures": self.failures,
        }
        budget = {
            "schemaVersion": 1,
            "runId": self.run_id,
            "sessionId": self.session_id,
            "policyId": self.policy_id,
            "clockDomain": "ANDROID_MONOTONIC",
            "events": self.events,
        }
        return failures, budget, self.fetch


# ---------------------------------------------------------------------------
# Minimal v2 runs
# ---------------------------------------------------------------------------


def wait_run(
    retry_after: Mapping[str, Any],
    wall_clock_now: int | None = None,
) -> tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]]]:
    """429 -> WAIT_UNTIL_PROVIDER -> WAIT_PROVIDER -> next owner SUCCESS."""

    run = Run()
    run.chain_started()
    run.consumer_joined()
    observation = http_observation(
        429, retry_after=retry_after, delivery_binding_revision="binding-1"
    )
    fetch_id = run.begin_owner()
    run.end_owner(fetch_id, observation)
    provider_wait = provider_wait_for(retry_after, wall_clock_now)
    run.failure(
        observation,
        "PROVIDER_RATE_LIMITED",
        "WAIT_UNTIL_PROVIDER",
        "PROVIDER_THROTTLED",
        {"kind": "WAIT_PROVIDER", "providerWait": provider_wait},
    )
    run.tick(provider_wait["waitMs"] * 1_000_000)
    second = run.begin_owner()
    run.end_owner(second)
    run.terminate("SUCCESS")
    return run.documents()


def refresh_run() -> tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]]]:
    """403 + stale signal on binding-1 -> REFRESHED -> next owner SUCCESS."""

    run = Run()
    run.chain_started()
    run.consumer_joined()
    observation = http_observation(
        403,
        provider_signal="BINDING_STALE_CONFIRMED",
        delivery_binding_revision="binding-1",
    )
    fetch_id = run.begin_owner()
    run.end_owner(fetch_id, observation)
    row = run.failure(
        observation,
        "DELIVERY_BINDING_STALE",
        "REFRESH_DELIVERY_BINDING",
        "STALE_BINDING_SIGNAL",
        {
            "kind": "REFRESH_DELIVERY_BINDING",
            "deliveryBinding": refreshed_binding("binding-1", "binding-2", "refresh-1"),
        },
    )
    run.refresh_charge(row["failureId"])
    second = run.begin_owner()
    run.end_owner(second)
    run.terminate("SUCCESS")
    return run.documents()


def refresh_then_exhausted_run() -> tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]]]:
    """403 stale -> REFRESHED -> 403 stale -> BUDGET_EXHAUSTED(refresh)."""

    run = Run()
    run.chain_started()
    run.consumer_joined()
    first = http_observation(
        403,
        provider_signal="BINDING_STALE_CONFIRMED",
        delivery_binding_revision="binding-1",
    )
    fetch_id = run.begin_owner()
    run.end_owner(fetch_id, first)
    refreshed = run.failure(
        first,
        "DELIVERY_BINDING_STALE",
        "REFRESH_DELIVERY_BINDING",
        "STALE_BINDING_SIGNAL",
        {
            "kind": "REFRESH_DELIVERY_BINDING",
            "deliveryBinding": refreshed_binding("binding-1", "binding-2", "refresh-1"),
        },
    )
    run.refresh_charge(refreshed["failureId"])
    second = http_observation(
        403,
        provider_signal="BINDING_STALE_CONFIRMED",
        delivery_binding_revision="binding-2",
    )
    fetch_id2 = run.begin_owner()
    run.end_owner(fetch_id2, second)
    exhausted = run.failure(
        second,
        "DELIVERY_BINDING_STALE",
        "REFRESH_DELIVERY_BINDING",
        "STALE_BINDING_SIGNAL",
        {
            "kind": "REFRESH_DELIVERY_BINDING",
            "exhaustedDimension": DELIVERY_BINDING_REFRESH,
            "deliveryBinding": {
                "expectedRevision": "binding-2",
                "result": "NOT_ADMITTED",
                "currentRevision": None,
                "refreshCorrelationId": None,
                "charged": False,
            },
        },
    )
    run.terminate("BUDGET_EXHAUSTED", exhausted["failureId"])
    return run.documents()


def remote_exhausted_run() -> tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]]]:
    """Four transient transport failures -> BUDGET_EXHAUSTED(REMOTE_ATTEMPT)."""

    run = Run()
    run.chain_started()
    run.consumer_joined()
    row = None
    for attempt in range(4):
        observation = transport_observation()
        fetch_id = run.begin_owner()
        run.end_owner(fetch_id, observation)
        if attempt < 3:
            delay = 100 * (2 ** attempt)
            row = run.failure(
                observation,
                "TRANSIENT_TRANSPORT",
                "RETRY_AFTER_BACKOFF",
                "TRANSIENT_FAILURE",
                {"kind": "SCHEDULE_BACKOFF", "delayMs": delay, "retryOrdinal": attempt + 1},
            )
            run.tick(delay * 1_000_000)
            run.backoff(
                row["failureId"],
                attempt + 1,
                delay,
                min(400, 100 * (2 ** attempt)),
            )
        else:
            row = run.failure(
                observation,
                "TRANSIENT_TRANSPORT",
                "RETRY_AFTER_BACKOFF",
                "TRANSIENT_FAILURE",
                {
                    "kind": "TERMINATE_BUDGET_EXHAUSTED",
                    "exhaustedDimension": REMOTE_ATTEMPT,
                },
            )
    run.terminate("BUDGET_EXHAUSTED", row["failureId"])
    return run.documents()


def abandoned_refresh_run() -> tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]]]:
    """403 stale -> refresh ABANDONED -> chain ends without a failureId."""

    run = Run()
    run.chain_started()
    run.consumer_joined()
    observation = http_observation(
        403,
        provider_signal="BINDING_STALE_CONFIRMED",
        delivery_binding_revision="binding-1",
    )
    fetch_id = run.begin_owner()
    run.end_owner(fetch_id, observation)
    run.failure(
        observation,
        "DELIVERY_BINDING_STALE",
        "REFRESH_DELIVERY_BINDING",
        "STALE_BINDING_SIGNAL",
        {
            "kind": "REFRESH_DELIVERY_BINDING",
            "deliveryBinding": {
                "expectedRevision": "binding-1",
                "result": "ABANDONED",
                "currentRevision": None,
                "refreshCorrelationId": None,
                "charged": False,
            },
        },
    )
    run.terminate("NO_REMAINING_DEMAND")
    return run.documents()


def fail_closed_stale_run() -> tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]]]:
    """Descriptor stale without a binding revision -> FAIL_CLOSED_ACTION_UNAVAILABLE."""

    run = Run()
    run.chain_started()
    run.consumer_joined()
    observation = {
        "plane": "PROVIDER",
        "type": "DELIVERY_DESCRIPTOR",
        "kind": "DESCRIPTOR_STALE",
        "httpStatus": None,
        "retryAfter": None,
        "providerSignal": None,
        "deliveryBindingRevision": None,
    }
    fetch_id = run.begin_owner()
    run.end_owner(fetch_id, observation)
    row = run.failure(
        observation,
        "DELIVERY_BINDING_STALE",
        "REFRESH_DELIVERY_BINDING",
        "STALE_BINDING_SIGNAL",
        {"kind": "FAIL_CLOSED_ACTION_UNAVAILABLE"},
    )
    run.terminate("TERMINAL_FAILURE", row["failureId"])
    return run.documents()


def _refresh_result_run(
    result: str,
    terminal: str,
) -> tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]]]:
    """403 stale -> refresh without a started operation -> terminal by result."""

    run = Run()
    run.chain_started()
    run.consumer_joined()
    observation = http_observation(
        403,
        provider_signal="BINDING_STALE_CONFIRMED",
        delivery_binding_revision="binding-1",
    )
    fetch_id = run.begin_owner()
    run.end_owner(fetch_id, observation)
    row = run.failure(
        observation,
        "DELIVERY_BINDING_STALE",
        "REFRESH_DELIVERY_BINDING",
        "STALE_BINDING_SIGNAL",
        {
            "kind": "REFRESH_DELIVERY_BINDING",
            "deliveryBinding": {
                "expectedRevision": "binding-1",
                "result": result,
                "currentRevision": None,
                "refreshCorrelationId": None,
                "charged": False,
            },
        },
    )
    run.terminate(terminal, row["failureId"])
    return run.documents()


def charged_terminal_refresh_run(
    result: str,
) -> tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]]]:
    """403 stale -> one charged refresh operation -> INCOMPATIBLE or FAILED
    -> TERMINAL_FAILURE with no further owner."""

    run = Run()
    run.chain_started()
    run.consumer_joined()
    observation = http_observation(
        403,
        provider_signal="BINDING_STALE_CONFIRMED",
        delivery_binding_revision="binding-1",
    )
    fetch_id = run.begin_owner()
    run.end_owner(fetch_id, observation)
    row = run.failure(
        observation,
        "DELIVERY_BINDING_STALE",
        "REFRESH_DELIVERY_BINDING",
        "STALE_BINDING_SIGNAL",
        {
            "kind": "REFRESH_DELIVERY_BINDING",
            "deliveryBinding": {
                "expectedRevision": "binding-1",
                "result": result,
                "currentRevision": None,
                "refreshCorrelationId": "refresh-1",
                "charged": True,
            },
        },
    )
    run.refresh_charge(row["failureId"])
    run.terminate("TERMINAL_FAILURE", row["failureId"])
    return run.documents()


def not_admitted_run() -> tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]]]:
    """A second stale owner reaches refresh admission after the chain already
    spent its one refresh charge -> NOT_ADMITTED / BUDGET_EXHAUSTED."""

    return refresh_then_exhausted_run()


def closed_run() -> tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]]]:
    """403 stale -> refresh CLOSED -> SESSION_TERMINATION, no charge."""

    return _refresh_result_run("CLOSED", "SESSION_TERMINATION")


def bare_403_run() -> tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]]]:
    """403 without a provider signal -> PROVIDER_REJECTED -> TERMINATE_FAILURE."""

    run = Run()
    run.chain_started()
    run.consumer_joined()
    observation = http_observation(
        403, provider_signal="NONE", delivery_binding_revision="binding-1"
    )
    fetch_id = run.begin_owner()
    run.end_owner(fetch_id, observation)
    row = run.failure(
        observation,
        "PROVIDER_REJECTED",
        "FAIL_TERMINAL",
        "NON_RETRYABLE_CLASSIFICATION",
        {"kind": "TERMINATE_FAILURE"},
    )
    run.terminate("TERMINAL_FAILURE", row["failureId"])
    return run.documents()


def rate_limited_absent_run() -> tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]]]:
    """429 without Retry-After -> FAIL_TERMINAL / RETRY_AFTER_ABSENT."""

    run = Run()
    run.chain_started()
    run.consumer_joined()
    observation = http_observation(
        429,
        retry_after=retry_after_absent(),
        delivery_binding_revision="binding-1",
    )
    fetch_id = run.begin_owner()
    run.end_owner(fetch_id, observation)
    row = run.failure(
        observation,
        "PROVIDER_RATE_LIMITED",
        "FAIL_TERMINAL",
        "RETRY_AFTER_ABSENT",
        {"kind": "TERMINATE_FAILURE"},
    )
    run.terminate("TERMINAL_FAILURE", row["failureId"])
    return run.documents()


# ---------------------------------------------------------------------------
# Committed v1 example up-conversion
# ---------------------------------------------------------------------------


def v1_example_as_v2() -> tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]]]:
    """Up-convert the committed M2-C v1 example to a table-v2 run."""

    failures = json.loads(
        (EXAMPLES / "failure-decision-v1.example.json").read_text(encoding="utf-8")
    )
    budget = json.loads(
        (EXAMPLES / "recovery-budget-v1.example.json").read_text(encoding="utf-8")
    )
    fetch = [
        json.loads(line)
        for line in (
            EXAMPLES / "recovery-fetch-events-v4.example.jsonl"
        ).read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]

    failures["schemaVersion"] = 2
    failures["policyId"] = POLICY_ID
    for row in failures["failures"]:
        observation = row["observation"]
        row["observation"] = {
            "plane": observation["plane"],
            "type": observation["type"],
            "kind": observation["kind"],
            "httpStatus": observation["httpStatus"],
            "retryAfter": None,
            "providerSignal": None,
            "deliveryBindingRevision": None,
        }
        row["context"]["deliveryBindingRefreshesRemaining"] = LIMITS[
            DELIVERY_BINDING_REFRESH
        ]
        row["action"]["exhaustedDimension"] = None
        row["action"]["providerWait"] = None
        row["action"]["deliveryBinding"] = None
    budget["policyId"] = POLICY_ID
    for event in budget["events"]:
        event["policyId"] = POLICY_ID
        event["limits"] = dict(LIMITS)
        event["spent"] = {
            REMOTE_ATTEMPT: event["spent"][REMOTE_ATTEMPT],
            DELIVERY_BINDING_REFRESH: 0,
        }
    return failures, budget, fetch


# ---------------------------------------------------------------------------
# Mutation helpers
# ---------------------------------------------------------------------------


def events(
    budget: Mapping[str, Any],
    kind: str,
    chain: str | None = None,
) -> list[dict[str, Any]]:
    return [
        event
        for event in budget["events"]
        if event["kind"] == kind
        and (chain is None or event["recoveryChainId"] == chain)
    ]


def resequence(budget: dict[str, Any]) -> None:
    for index, event in enumerate(budget["events"]):
        event["sequence"] = index + 1


def refresh_charge_event(budget: Mapping[str, Any]) -> dict[str, Any]:
    return next(
        event
        for event in events(budget, "CHARGE")
        if event["charge"]["dimension"] == DELIVERY_BINDING_REFRESH
    )


def remote_charges(budget: Mapping[str, Any]) -> list[dict[str, Any]]:
    return [
        event
        for event in events(budget, "CHARGE")
        if event["charge"]["dimension"] == REMOTE_ATTEMPT
    ]


def remove_refresh_charge(budget: dict[str, Any]) -> dict[str, Any]:
    """Drop the refresh charge and keep the ledger snapshots consistent."""

    charge = refresh_charge_event(budget)
    budget["events"].remove(charge)
    for event in budget["events"]:
        if event["spent"].get(DELIVERY_BINDING_REFRESH, 0) > 0:
            event["spent"][DELIVERY_BINDING_REFRESH] -= 1
    resequence(budget)
    return charge
