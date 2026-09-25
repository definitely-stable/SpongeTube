"""Independent host verifier for M2-C recovery evidence.

Normative source: `.work/milestones/M2.md` (sections 9, 11 and M2-C) and
`.work/adr/0003-centralize-recovery-ownership.md`.

Inputs of one run:

* `failure-decision-events-v1` - observation / classification / decision /
  action rows of every failed physical owner;
* `recovery-budget-events-v1` - RecoveryChain lifecycle and ledger events;
* `fetch-events-v3` (JSONL) - the FetchBroker physical owner ledger;
* optionally the Media Lab origin request trace and a scenario `case.json`.

The verifier never imports or trusts the production Kotlin
RecoveryCoordinator. It re-derives every classification, decision, executed
action, backoff window and ledger transition with its own tables, replays the
chain lifecycle, and joins budget charges to FetchBroker attempts and origin
requests by correlation identities only (no clock comparison). Every check
fails closed by raising `RecoveryOracleError`.

Usage:

    python3 scripts/measurement/m2_recovery_oracle.py verify \\
        --failures failure-decision-events.json \\
        --budget recovery-budget-events.json \\
        --fetch fetch-events.jsonl \\
        --output recovery-verification-summary.json
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
from collections import Counter, defaultdict
from typing import Any, Iterable, Mapping

SCRIPT_DIR = pathlib.Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from m2_contracts import (  # noqa: E402
    M2ContractError,
    scan_evidence_privacy,
    validate_failure_record,
)
from schema_subset import SchemaContractError, validate_instance  # noqa: E402

SCHEMAS = REPO_ROOT / ".work" / "schemas"
FAILURE_SCHEMA = SCHEMAS / "failure-decision-events-v1.schema.json"
BUDGET_SCHEMA = SCHEMAS / "recovery-budget-events-v1.schema.json"
FETCH_SCHEMAS = {
    3: SCHEMAS / "fetch-events-v3.schema.json",
    4: SCHEMAS / "fetch-events-v4.schema.json",
}
SUMMARY_SCHEMA = SCHEMAS / "recovery-verification-summary-v1.schema.json"


class RecoveryOracleError(ValueError):
    pass


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise RecoveryOracleError(message)


REMOTE_ATTEMPT = "REMOTE_ATTEMPT"
DIMENSION = re.compile(r"^[A-Z][A-Z0-9_]*$")

# Versioned policies the oracle knows. An unknown policyId fails closed: the
# verifier cannot re-derive decisions for a policy it has not reviewed.
KNOWN_POLICIES: dict[str, dict[str, Any]] = {
    "sponge-recovery-v1": {
        "limits": {REMOTE_ATTEMPT: 4},
        "backoffBaseMs": 500,
        "backoffCapMs": 5_000,
    },
    "sponge-recovery-test-v1": {
        "limits": {REMOTE_ATTEMPT: 4},
        "backoffBaseMs": 100,
        "backoffCapMs": 400,
    },
}

LIMITATIONS = (
    "M2-C proves provider-independent failure classification, single-owner "
    "recovery lineage and bounded remote-attempt accounting.",
    "It does not prove provider delivery-binding refresh, Retry-After behavior, "
    "real VPN/default-route fetch suppression, packet/network fault attribution, "
    "or transport superiority.",
)

# ---------------------------------------------------------------------------
# Independent vocabulary (M2-C)
# ---------------------------------------------------------------------------

# type -> (plane, permitted kinds)
OBSERVATION_TYPES: dict[str, tuple[str, frozenset[str]]] = {
    "TRANSPORT_IO": (
        "TRANSPORT",
        frozenset({
            "CONNECT_TIMEOUT", "READ_TIMEOUT", "CONNECTION_RESET",
            "PREMATURE_EOF", "IO", "TARGET_UNRESOLVED",
        }),
    ),
    "HTTP_RESPONSE": ("PROVIDER", frozenset({"HTTP_STATUS"})),
    "DELIVERY_DESCRIPTOR": ("PROVIDER", frozenset({"DESCRIPTOR_STALE"})),
    "RANGE_PROTOCOL": (
        "RESPONSE_CONTRACT",
        frozenset({
            "FULL_BODY_FOR_RANGE_REQUEST", "CONTENT_RANGE_MISSING",
            "CONTENT_RANGE_MISMATCH", "RESPONSE_LENGTH_MISMATCH",
            "RESPONSE_BYTES_OUTSIDE_RANGE", "REQUEST_OUTSIDE_RESOURCE",
        }),
    ),
    "CONTENT_INTEGRITY": ("RESPONSE_CONTRACT", frozenset({"DIGEST_OR_LENGTH_MISMATCH"})),
    "STORAGE": ("STORAGE", frozenset({"NO_SPACE", "IO", "CONFLICT"})),
    "CANCELLATION": ("LOCAL", frozenset({"NO_CONSUMERS", "SESSION_SHUTDOWN"})),
    "INTERNAL": ("LOCAL", frozenset({"INTERNAL_FAILURE"})),
}

OBSERVATION_KEYS = frozenset({"plane", "type", "kind", "httpStatus"})

# Keys that would carry free text or raw locators; never allowed anywhere.
FORBIDDEN_KEYS = frozenset({
    "message", "exceptionmessage", "exception", "stacktrace", "cause",
    "url", "uri", "headers", "header", "requesturl", "responseheaders",
})


def classify(observation: Mapping[str, Any]) -> str:
    """M2-C frozen mappings, re-derived independently."""

    kind_type = observation["type"]
    kind = observation["kind"]
    if kind_type == "TRANSPORT_IO":
        return "TERMINAL_TRANSPORT" if kind == "TARGET_UNRESOLVED" else "TRANSIENT_TRANSPORT"
    if kind_type == "HTTP_RESPONSE":
        status = observation["httpStatus"]
        if status == 429:
            return "PROVIDER_RATE_LIMITED"
        if status == 408 or 500 <= status <= 599:
            return "PROVIDER_TRANSIENT_RESPONSE"
        if 400 <= status <= 499:
            return "PROVIDER_REJECTED"
        return "UNKNOWN"
    if kind_type == "DELIVERY_DESCRIPTOR":
        return "DELIVERY_BINDING_STALE"
    if kind_type == "RANGE_PROTOCOL":
        return "RANGE_REJECTED"
    if kind_type == "CONTENT_INTEGRITY":
        return "CONTENT_INTEGRITY"
    if kind_type == "STORAGE":
        return "PUBLICATION_CONFLICT" if kind == "CONFLICT" else "STORAGE_FAILURE"
    if kind_type == "CANCELLATION":
        return "CANCELLED"
    if kind_type == "INTERNAL":
        return "INTERNAL"
    return "UNKNOWN"


def legacy_outcome(observation: Mapping[str, Any]) -> str:
    """M1 fetch-events-v3 projection of an observation (compatibility)."""

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


def decide(
    classification: str,
    observation: Mapping[str, Any],
    context: Mapping[str, Any],
) -> tuple[str, str]:
    """`sponge-recovery-v1` decision table, re-derived independently."""

    if context["sessionClosing"] or (
        observation["type"] == "CANCELLATION" and observation["kind"] == "SESSION_SHUTDOWN"
    ):
        return "COMPLETE_SESSION", "SESSION_SHUTDOWN"
    demand = context["demandPresent"]
    if classification in ("TRANSIENT_TRANSPORT", "PROVIDER_TRANSIENT_RESPONSE"):
        if not demand:
            return "COMPLETE_NO_DEMAND", "DEMAND_RELEASED"
        return (
            "RETRY_AFTER_BACKOFF",
            "TRANSIENT_FAILURE"
            if classification == "TRANSIENT_TRANSPORT"
            else "PROVIDER_TRANSIENT_STATUS",
        )
    if classification == "PROVIDER_RATE_LIMITED":
        return "WAIT_UNTIL_PROVIDER", "PROVIDER_THROTTLED"
    if classification == "DELIVERY_BINDING_STALE":
        return "REFRESH_DELIVERY_BINDING", "STALE_BINDING_SIGNAL"
    if classification == "PUBLICATION_CONFLICT":
        return "RECONCILE_LOCAL_COVERAGE", "LOCAL_PUBLICATION_CONFLICT"
    if classification == "CANCELLED":
        if demand:
            return "CONTINUE_FOR_DEMAND", "DEMAND_PRESENT_AFTER_CANCELLATION"
        return "COMPLETE_NO_DEMAND", "DEMAND_RELEASED"
    if classification == "UNKNOWN":
        return "FAIL_TERMINAL", "UNKNOWN_FAILS_CLOSED"
    return "FAIL_TERMINAL", "NON_RETRYABLE_CLASSIFICATION"


# Decisions the M2-C runtime has no handler for: they must fail closed.
UNHANDLED_DECISIONS = frozenset({
    "WAIT_UNTIL_PROVIDER",
    "REFRESH_DELIVERY_BINDING",
    "RERESOLVE_PROVIDER",
})

TERMINAL_OF_ACTION = {
    "LOCAL_COVERAGE_READY": "SUCCESS",
    "TERMINATE_BUDGET_EXHAUSTED": "BUDGET_EXHAUSTED",
    "TERMINATE_NO_DEMAND": "NO_REMAINING_DEMAND",
    "TERMINATE_SESSION": "SESSION_TERMINATION",
    "TERMINATE_FAILURE": "TERMINAL_FAILURE",
    "FAIL_CLOSED_ACTION_UNAVAILABLE": "TERMINAL_FAILURE",
    "FAIL_CLOSED_IDENTITY_CONFLICT": "TERMINAL_FAILURE",
}


def expected_action_kind(
    decision: str,
    remaining: int,
    reconciliation: str | None,
) -> str:
    if decision == "RETRY_AFTER_BACKOFF":
        return "SCHEDULE_BACKOFF" if remaining > 0 else "TERMINATE_BUDGET_EXHAUSTED"
    if decision in ("CONTINUE_FOR_DEMAND", "WAIT_FOR_ROUTE"):
        return "START_NEXT_OWNER" if remaining > 0 else "TERMINATE_BUDGET_EXHAUSTED"
    if decision in UNHANDLED_DECISIONS:
        return "FAIL_CLOSED_ACTION_UNAVAILABLE"
    if decision == "FAIL_TERMINAL":
        return "TERMINATE_FAILURE"
    if decision == "COMPLETE_NO_DEMAND":
        return "TERMINATE_NO_DEMAND"
    if decision == "COMPLETE_SESSION":
        return "TERMINATE_SESSION"
    if decision == "RECONCILE_LOCAL_COVERAGE":
        return {
            "COVERAGE_PRESENT": "LOCAL_COVERAGE_READY",
            "IDENTITY_CONFLICT": "FAIL_CLOSED_IDENTITY_CONFLICT",
            "ABSENT": "TERMINATE_FAILURE",
            "FAILED": "TERMINATE_FAILURE",
        }.get(reconciliation or "", "<missing reconciliation>")
    raise RecoveryOracleError(f"unknown decision {decision!r}")


def backoff_window_ms(policy: Mapping[str, Any], retry_ordinal: int) -> int:
    window = policy["backoffBaseMs"] * (2 ** (retry_ordinal - 1))
    return min(policy["backoffCapMs"], window)


# Frozen M2-A anchor vocabulary for the mappable subset of rows.
_CONTRACT_KIND = {
    ("TRANSPORT_IO", "CONNECT_TIMEOUT"): "SOCKET_TIMEOUT",
    ("TRANSPORT_IO", "READ_TIMEOUT"): "SOCKET_TIMEOUT",
    ("TRANSPORT_IO", "CONNECTION_RESET"): "CONNECTION_RESET",
    ("TRANSPORT_IO", "PREMATURE_EOF"): "EOF_BEFORE_EXPECTED_RANGE",
    ("STORAGE", "NO_SPACE"): "STORAGE_ENOSPC",
    ("STORAGE", "IO"): "STORAGE_IO",
}
_CONTRACT_DECISION = {
    "RETRY_AFTER_BACKOFF": "RETRY",
    "WAIT_FOR_ROUTE": "WAIT_FOR_ROUTE",
    "WAIT_UNTIL_PROVIDER": "WAIT",
    "REFRESH_DELIVERY_BINDING": "REFRESH_DELIVERY_BINDING",
    "RERESOLVE_PROVIDER": "RERESOLVE",
    "FAIL_TERMINAL": "FAIL",
}


def _check_frozen_anchors(row: Mapping[str, Any], where: str) -> None:
    """Re-check rows against the M2-A frozen anchors (m2_contracts)."""

    observation = row["observation"]
    decision = row["decision"]["kind"]
    if decision not in _CONTRACT_DECISION:
        return
    if observation["type"] == "HTTP_RESPONSE":
        kind = "HTTP_STATUS"
    else:
        kind = _CONTRACT_KIND.get((observation["type"], observation["kind"]))
    if kind is None:
        return
    record = {
        "failureId": row["failureId"],
        "observation": {"kind": kind, "status": observation["httpStatus"]},
        "classification": {"class": row["classification"]},
        "decision": {"action": _CONTRACT_DECISION[decision]},
    }
    try:
        validate_failure_record(record)
    except M2ContractError as error:
        raise RecoveryOracleError(f"{where}: frozen M2 anchor violated: {error}") from error


# ---------------------------------------------------------------------------
# Verification
# ---------------------------------------------------------------------------

_SCHEMA_CACHE: dict[pathlib.Path, dict[str, Any]] = {}


def _schema(path: pathlib.Path) -> dict[str, Any]:
    if path not in _SCHEMA_CACHE:
        _SCHEMA_CACHE[path] = json.loads(path.read_text(encoding="utf-8"))
    return _SCHEMA_CACHE[path]


def _validate(path: pathlib.Path, document: Any, label: str) -> None:
    try:
        validate_instance(_schema(path), document)
    except SchemaContractError as error:
        raise RecoveryOracleError(f"{label} schema: {error}") from error


def _normalized(key: str) -> str:
    return re.sub(r"[^a-z]", "", key.lower())


def _check_privacy(document: Any, label: str, path: str = "$") -> None:
    if isinstance(document, Mapping):
        for key, value in document.items():
            _require(
                _normalized(str(key)) not in FORBIDDEN_KEYS,
                f"{label} {path}.{key}: free-text / locator field must not be retained",
            )
            _check_privacy(value, label, f"{path}.{key}")
    elif isinstance(document, list):
        for index, value in enumerate(document):
            _check_privacy(value, label, f"{path}[{index}]")
    if path == "$":
        try:
            scan_evidence_privacy(document)
        except M2ContractError as error:
            raise RecoveryOracleError(f"{label}: {error}") from error


class _Chain:
    def __init__(self, event: Mapping[str, Any], policy: Mapping[str, Any]) -> None:
        self.chain_id = event["recoveryChainId"]
        self.fetch_key = event["fetchKey"]
        self.extent_id = event["extentId"]
        self.policy_id = event["policyId"]
        self.limits = dict(event["limits"])
        self.policy = policy
        self.spent = dict(event["spent"])
        self.terminal: str | None = None
        self.terminal_failure_id: str | None = None
        self.priority = event["effectivePriority"]
        self.consumers: set[str] = set()
        # owner lifecycle
        self.owner_ordinal = 0
        self.open_owner: dict[str, Any] | None = None
        self.pending_charge: dict[str, Any] | None = None
        self.permit_granted = False
        self.backoff_open: dict[str, Any] | None = None
        self.charges: list[dict[str, Any]] = []
        self.owners: list[dict[str, Any]] = []
        self.finished: dict[str, dict[str, Any]] = {}
        self.retry_ordinal = 0
        self.last_terminal_decision_failure: str | None = None


def _replay_budget(
    document: Mapping[str, Any],
) -> tuple[dict[str, _Chain], list[Mapping[str, Any]]]:
    events = document["events"]
    chains: dict[str, _Chain] = {}
    open_by_key: dict[str, str] = {}
    for index, event in enumerate(events):
        where = f"budget.events[{index}]"
        _require(
            event["sequence"] == index + 1,
            f"{where}: sequence {event['sequence']} != {index + 1} (must be contiguous)",
        )
        _require(event["sessionId"] == document["sessionId"], f"{where}: foreign session")
        _require(
            event["policyId"] == document["policyId"],
            f"{where}: policyId {event['policyId']} != run policy {document['policyId']}",
        )
        for field in ("limits", "spent"):
            for dimension in event[field]:
                _require(
                    DIMENSION.match(dimension) is not None,
                    f"{where}: invalid budget dimension {dimension!r}",
                )

        kind = event["kind"]
        chain_id = event["recoveryChainId"]
        if kind == "CHAIN_STARTED":
            _require(chain_id not in chains, f"{where}: chain {chain_id} started twice")
            policy = KNOWN_POLICIES.get(event["policyId"])
            _require(policy is not None, f"{where}: unknown policyId {event['policyId']!r}")
            _require(
                event["limits"] == policy["limits"],
                f"{where}: limits {event['limits']} != policy {policy['limits']}",
            )
            _require(
                set(event["spent"]) == set(event["limits"])
                and all(value == 0 for value in event["spent"].values()),
                f"{where}: chain must start with every declared dimension at zero",
            )
            _require(
                event["fetchKey"] not in open_by_key,
                f"{where}: second open RecoveryChain for immutable work "
                f"{event['fetchKey']} (open: {open_by_key.get(event['fetchKey'])})",
            )
            chains[chain_id] = _Chain(event, policy)
            open_by_key[event["fetchKey"]] = chain_id
            continue

        _require(chain_id in chains, f"{where}: event for unstarted chain {chain_id}")
        chain = chains[chain_id]
        _require(
            chain.terminal is None,
            f"{where}: {kind} after chain {chain_id} terminated ({chain.terminal})",
        )
        _require(
            (event["fetchKey"], event["extentId"]) == (chain.fetch_key, chain.extent_id),
            f"{where}: immutable work identity changed inside chain {chain_id}",
        )
        _require(event["policyId"] == chain.policy_id, f"{where}: policy changed inside chain")
        _require(event["limits"] == chain.limits, f"{where}: limits changed inside chain")

        before = chain.spent
        after = event["spent"]
        for dimension, amount in before.items():
            _require(
                dimension in after,
                f"{where}: budget dimension {dimension} disappeared (implicit reset)",
            )
            _require(
                after[dimension] >= amount,
                f"{where}: {dimension} spent decreased {amount} -> {after[dimension]} "
                "(implicit reset)",
            )
        _require(
            set(after) == set(chain.limits),
            f"{where}: ledger dimensions {sorted(after)} != declared {sorted(chain.limits)}",
        )
        for dimension, amount in after.items():
            _require(
                amount <= chain.limits[dimension],
                f"{where}: {dimension} spent {amount} exceeds limit {chain.limits[dimension]}",
            )

        if kind == "CHARGE":
            charge = event["charge"]
            _require(charge is not None, f"{where}: CHARGE without charge")
            dimension = charge["dimension"]
            _require(dimension in chain.limits, f"{where}: charge of undeclared {dimension}")
            _require(
                charge["spentBefore"] == before[dimension]
                and charge["spentAfter"] == before[dimension] + charge["amount"]
                and charge["limit"] == chain.limits[dimension],
                f"{where}: inconsistent charge {charge} for ledger {before}",
            )
            expected = dict(before)
            expected[dimension] += charge["amount"]
            _require(after == expected, f"{where}: ledger {after} != {before} + charge")
            _require(
                dimension == REMOTE_ATTEMPT and charge["amount"] == 1,
                f"{where}: M2-C charges exactly one REMOTE_ATTEMPT per physical attempt",
            )
            _require(chain.permit_granted, f"{where}: charge without attempt permit")
            _require(chain.open_owner is None, f"{where}: charge while an owner is open")
            _require(chain.backoff_open is None, f"{where}: charge during backoff")
            _require(
                chain.pending_charge is None,
                f"{where}: charge without a physical owner for the previous charge",
            )
            _require(
                event["ownerOrdinal"] == chain.owner_ordinal + 1,
                f"{where}: owner ordinal {event['ownerOrdinal']} != {chain.owner_ordinal + 1}",
            )
            _require(
                event["fetchId"] is not None and event["attemptCorrelationId"] is not None,
                f"{where}: charge must correlate fetchId and attemptCorrelationId",
            )
            _require(
                event["attemptCorrelationId"] == f"{event['fetchId']}:attempt-1",
                f"{where}: attemptCorrelationId does not belong to fetchId",
            )
            chain.pending_charge = dict(event)
            chain.charges.append(dict(event))
            chain.permit_granted = False
        else:
            _require(after == before, f"{where}: {kind} changed the ledger {before} -> {after}")
            _require(event["charge"] is None, f"{where}: {kind} carries a charge")

        if kind == "OWNER_STARTED":
            pending = chain.pending_charge
            _require(pending is not None, f"{where}: physical owner without a budget charge")
            for field in ("ownerOrdinal", "fetchId", "attemptCorrelationId"):
                _require(
                    event[field] == pending[field],
                    f"{where}: OWNER_STARTED {field} {event[field]} != charge {pending[field]}",
                )
            chain.pending_charge = None
            chain.open_owner = dict(event)
            chain.owner_ordinal = event["ownerOrdinal"]
            chain.owners.append(dict(event))
        elif kind == "OWNER_FINISHED":
            _require(chain.pending_charge is None, f"{where}: charge without physical owner")
            if event["attemptCorrelationId"] is None:
                # Owner cancelled before admission: no charge, no attempt.
                _require(chain.open_owner is None, f"{where}: unadmitted owner overlaps")
                _require(
                    event["ownerOrdinal"] == chain.owner_ordinal + 1,
                    f"{where}: unadmitted owner ordinal out of order",
                )
                chain.owner_ordinal = event["ownerOrdinal"]
            else:
                owner = chain.open_owner
                _require(owner is not None, f"{where}: OWNER_FINISHED without OWNER_STARTED")
                for field in ("ownerOrdinal", "fetchId", "attemptCorrelationId"):
                    _require(
                        event[field] == owner[field],
                        f"{where}: OWNER_FINISHED {field} {event[field]} != {owner[field]}",
                    )
                chain.open_owner = None
            _require(event["ownerOutcome"] is not None, f"{where}: owner without outcome")
            chain.finished[event["fetchId"]] = dict(event)
            chain.permit_granted = False
        elif kind == "ATTEMPT_PERMIT_WAIT":
            _require(chain.open_owner is None, f"{where}: permit wait while owner open")
            _require(chain.backoff_open is None, f"{where}: permit wait during backoff")
        elif kind == "ATTEMPT_PERMIT_GRANTED":
            _require(event["permit"] is not None, f"{where}: grant without permit")
            chain.permit_granted = True
        elif kind == "BACKOFF_SCHEDULED":
            backoff = event["backoff"]
            _require(backoff is not None and event["failureId"], f"{where}: incomplete backoff")
            chain.retry_ordinal += 1
            _require(
                backoff["retryOrdinal"] == chain.retry_ordinal,
                f"{where}: retryOrdinal {backoff['retryOrdinal']} != {chain.retry_ordinal}",
            )
            window = backoff_window_ms(chain.policy, backoff["retryOrdinal"])
            _require(
                backoff["windowMs"] == window,
                f"{where}: backoff window {backoff['windowMs']} != policy {window}",
            )
            _require(
                0 <= backoff["delayMs"] <= window,
                f"{where}: jittered delay {backoff['delayMs']} outside 0..{window}",
            )
            chain.backoff_open = dict(event)
        elif kind == "BACKOFF_COMPLETED":
            _require(
                chain.backoff_open is not None
                and event["backoff"] == chain.backoff_open["backoff"]
                and event["failureId"] == chain.backoff_open["failureId"],
                f"{where}: BACKOFF_COMPLETED does not match the scheduled backoff",
            )
            chain.backoff_open = None
        elif kind == "CONSUMER_JOINED":
            consumer = event["consumerId"]
            _require(consumer and consumer not in chain.consumers, f"{where}: bad join")
            chain.consumers.add(consumer)
        elif kind == "CONSUMER_RELEASED":
            consumer = event["consumerId"]
            _require(consumer in chain.consumers, f"{where}: release of unknown consumer")
            chain.consumers.remove(consumer)
        elif kind == "PRIORITY_RAISED":
            _require(
                (event["priorityBefore"], event["effectivePriority"]) == (chain.priority, "PLAYBACK")
                and chain.priority == "RESERVE",
                f"{where}: priority may only rise RESERVE -> PLAYBACK",
            )
        elif kind == "CHAIN_TERMINATED":
            reason = event["terminalReason"]
            _require(reason is not None, f"{where}: terminal without reason")
            _require(chain.open_owner is None, f"{where}: chain terminal before its owner")
            _require(chain.pending_charge is None, f"{where}: terminal with unused charge")
            if reason == "BUDGET_EXHAUSTED":
                _require(
                    after[REMOTE_ATTEMPT] == chain.limits[REMOTE_ATTEMPT],
                    f"{where}: BUDGET_EXHAUSTED with REMOTE_ATTEMPT {after[REMOTE_ATTEMPT]} "
                    f"< limit {chain.limits[REMOTE_ATTEMPT]}",
                )
            chain.terminal = reason
            chain.terminal_failure_id = event["failureId"]
            chain.backoff_open = None
            if open_by_key.get(chain.fetch_key) == chain_id:
                del open_by_key[chain.fetch_key]

        if kind != "PRIORITY_RAISED":
            _require(
                event["effectivePriority"] == chain.priority
                or kind == "CONSUMER_JOINED",
                f"{where}: effective priority changed outside PRIORITY_RAISED",
            )
        chain.priority = event["effectivePriority"]
        chain.spent = dict(after)

    for chain in chains.values():
        _require(
            chain.terminal is not None,
            f"chain {chain.chain_id} has no CHAIN_TERMINATED (incomplete evidence)",
        )
    return chains, events


def _verify_failures(
    document: Mapping[str, Any],
    chains: Mapping[str, _Chain],
) -> list[Mapping[str, Any]]:
    rows = document["failures"]
    seen_ids: set[str] = set()
    by_chain: dict[str, list[Mapping[str, Any]]] = defaultdict(list)
    for index, row in enumerate(rows):
        where = f"failures[{index}]"
        _require(row["sequence"] == index + 1, f"{where}: sequence not contiguous")
        _require(row["sessionId"] == document["sessionId"], f"{where}: foreign session")
        _require(row["failureId"] not in seen_ids, f"{where}: duplicate failureId")
        seen_ids.add(row["failureId"])
        chain = chains.get(row["recoveryChainId"])
        _require(chain is not None, f"{where}: failure for unknown chain")
        _require(
            row["failureId"].startswith(row["recoveryChainId"] + ":"),
            f"{where}: failureId does not belong to its chain",
        )
        _require(row["fetchKey"] == chain.fetch_key, f"{where}: failure names foreign work")

        observation = row["observation"]
        _require(
            set(observation) == OBSERVATION_KEYS,
            f"{where}: observation layer carries {sorted(observation)} (layers conflated)",
        )
        plane, kinds = OBSERVATION_TYPES[observation["type"]]
        _require(
            observation["plane"] == plane,
            f"{where}: {observation['type']} observed on plane {observation['plane']}, not {plane}",
        )
        _require(observation["kind"] in kinds, f"{where}: kind/type mismatch")
        _require(
            (observation["type"] == "HTTP_RESPONSE") == (observation["httpStatus"] is not None),
            f"{where}: httpStatus only and always for HTTP_RESPONSE",
        )

        classification = classify(observation)
        _require(
            row["classification"] == classification,
            f"{where}: classification {row['classification']} != oracle {classification}",
        )
        decision, reason = decide(classification, observation, row["context"])
        _require(
            (row["decision"]["kind"], row["decision"]["reason"]) == (decision, reason),
            f"{where}: decision {row['decision']} != oracle {decision}/{reason}",
        )
        action = row["action"]
        expected = expected_action_kind(
            decision,
            row["context"]["remoteAttemptsRemaining"],
            action["reconciliation"],
        )
        _require(
            action["kind"] == expected,
            f"{where}: action {action['kind']} != oracle {expected} for {decision}",
        )
        _require(
            (action["reconciliation"] is not None) == (decision == "RECONCILE_LOCAL_COVERAGE"),
            f"{where}: reconciliation recorded only for RECONCILE_LOCAL_COVERAGE",
        )
        if action["kind"] == "SCHEDULE_BACKOFF":
            _require(
                action["delayMs"] is not None and action["retryOrdinal"] is not None,
                f"{where}: backoff action without delay/ordinal",
            )
            window = backoff_window_ms(chain.policy, action["retryOrdinal"])
            _require(
                0 <= action["delayMs"] <= window,
                f"{where}: delay {action['delayMs']} outside 0..{window}",
            )
        else:
            _require(
                action["delayMs"] is None and action["retryOrdinal"] is None,
                f"{where}: {action['kind']} carries a delay",
            )
        _check_frozen_anchors(row, where)
        by_chain[row["recoveryChainId"]].append(row)
    return rows


def _verify_failure_lineage(
    events: list[Mapping[str, Any]],
    rows: list[Mapping[str, Any]],
    chains: Mapping[str, _Chain],
) -> None:
    """Join failure rows to the budget timeline of their chain."""

    failure_by_id = {row["failureId"]: row for row in rows}
    rows_by_fetch = defaultdict(list)
    for row in rows:
        if row["fetchId"] is not None:
            rows_by_fetch[(row["recoveryChainId"], row["fetchId"])].append(row)

    spent_at_finish: dict[tuple[str, str], int] = {}
    for event in events:
        if event["kind"] == "OWNER_FINISHED":
            key = (event["recoveryChainId"], event["fetchId"])
            spent_at_finish[key] = event["spent"][REMOTE_ATTEMPT]
            linked = rows_by_fetch.get(key, [])
            if event["ownerOutcome"] == "SUCCESS":
                _require(not linked, f"successful owner {key} has a failure row")
            else:
                _require(
                    len(linked) == 1,
                    f"failed owner {key} must have exactly one failure row, has {len(linked)}",
                )
                row = linked[0]
                _require(
                    legacy_outcome(row["observation"]) == event["ownerOutcome"],
                    f"{row['failureId']}: observation does not project to the owner outcome "
                    f"{event['ownerOutcome']}",
                )
                _require(
                    row["attemptCorrelationId"] == event["attemptCorrelationId"],
                    f"{row['failureId']}: attempt correlation differs from its owner",
                )

    for row in rows:
        chain = chains[row["recoveryChainId"]]
        if row["fetchId"] is not None:
            key = (row["recoveryChainId"], row["fetchId"])
            _require(key in spent_at_finish, f"{row['failureId']}: names no finished owner")
            remaining = chain.limits[REMOTE_ATTEMPT] - spent_at_finish[key]
            _require(
                row["context"]["remoteAttemptsRemaining"] == remaining,
                f"{row['failureId']}: context remaining "
                f"{row['context']['remoteAttemptsRemaining']} != ledger {remaining}",
            )

    # Terminal decisions end the chain; non-terminal ones continue it.
    by_chain_events: dict[str, list[Mapping[str, Any]]] = defaultdict(list)
    for event in events:
        by_chain_events[event["recoveryChainId"]].append(event)
    for chain_id, chain_events in by_chain_events.items():
        chain = chains[chain_id]
        for position, event in enumerate(chain_events):
            if event["kind"] != "OWNER_FINISHED" or event["ownerOutcome"] == "SUCCESS":
                continue
            row = rows_by_fetch[(chain_id, event["fetchId"])][0]
            action = row["action"]["kind"]
            after = chain_events[position + 1:]
            later_charges = [e for e in after if e["kind"] == "CHARGE"]
            if action in TERMINAL_OF_ACTION:
                _require(
                    chain.terminal == TERMINAL_OF_ACTION[action]
                    and chain.terminal_failure_id == row["failureId"],
                    f"{row['failureId']}: {action} must end the chain as "
                    f"{TERMINAL_OF_ACTION[action]} (got {chain.terminal}/"
                    f"{chain.terminal_failure_id})",
                )
                _require(
                    not later_charges,
                    f"{row['failureId']}: request after terminal decision {action}",
                )
            elif action == "SCHEDULE_BACKOFF":
                scheduled = [
                    e for e in after
                    if e["kind"] == "BACKOFF_SCHEDULED" and e["failureId"] == row["failureId"]
                ]
                _require(len(scheduled) == 1, f"{row['failureId']}: backoff not scheduled")
                _require(
                    scheduled[0]["backoff"]["delayMs"] == row["action"]["delayMs"]
                    and scheduled[0]["backoff"]["retryOrdinal"] == row["action"]["retryOrdinal"],
                    f"{row['failureId']}: executed backoff differs from the decided action",
                )
    for chain in chains.values():
        if chain.terminal_failure_id is not None:
            row = failure_by_id.get(chain.terminal_failure_id)
            _require(row is not None, f"chain {chain.chain_id}: terminal names unknown failure")
            _require(
                TERMINAL_OF_ACTION.get(row["action"]["kind"]) == chain.terminal,
                f"chain {chain.chain_id}: terminal {chain.terminal} not the action outcome",
            )


def _load_fetch(rows: Iterable[Mapping[str, Any]]) -> list[Mapping[str, Any]]:
    loaded = list(rows)
    versions = {row.get("schemaVersion") for row in loaded}
    _require(len(versions) <= 1, "fetch evidence mixes schema versions")
    if loaded:
        schema_path = FETCH_SCHEMAS.get(next(iter(versions)))
        _require(schema_path is not None, "unsupported fetch event schema version")
        for index, row in enumerate(loaded):
            _validate(schema_path, row, f"fetch[{index}]")
    for index, row in enumerate(loaded):
        _require(row["eventSequence"] == index, f"fetch[{index}]: eventSequence not contiguous")
    return loaded


TERMINAL_FETCH_EVENTS = ("OWNER_COMPLETED", "OWNER_FAILED", "OWNER_CANCELLED")


def _verify_fetch(
    fetch: list[Mapping[str, Any]],
    chains: Mapping[str, _Chain],
    session_id: str,
) -> dict[str, Any]:
    owners: dict[str, dict[str, Any]] = {}
    active_by_key: dict[str, str] = {}
    attempts: dict[str, list[Mapping[str, Any]]] = defaultdict(list)
    transport: dict[str, set[str]] = defaultdict(set)
    for index, row in enumerate(fetch):
        where = f"fetch[{index}]"
        _require(row["sessionId"] == session_id, f"{where}: foreign session")
        fetch_id, key, event = row["fetchId"], row["fetchKey"], row["event"]
        if event == "OWNER_REGISTERED":
            _require(fetch_id not in owners, f"{where}: {fetch_id} registered twice")
            _require(
                key not in active_by_key,
                f"{where}: overlapping physical owners for {key} "
                f"({active_by_key.get(key)} still active)",
            )
            consumers = row["consumerIds"]
            _require(
                len(consumers) == 1 and str(consumers[0]).startswith("recovery:"),
                f"{where}: broker owner {fetch_id} has consumers {consumers}; only a "
                "RecoveryChain may own a physical fetch",
            )
            owners[fetch_id] = {"key": key, "consumer": consumers[0], "terminal": None}
            active_by_key[key] = fetch_id
            continue
        _require(fetch_id in owners, f"{where}: event for unregistered owner {fetch_id}")
        owner = owners[fetch_id]
        _require(owner["terminal"] is None, f"{where}: event after owner terminal")
        if event == "ATTEMPT_STARTED":
            _require(row["attempt"] == 1, f"{where}: one owner makes exactly one attempt")
            attempts[fetch_id].append(row)
            _require(len(attempts[fetch_id]) == 1, f"{where}: {fetch_id} made a second attempt")
        if row.get("transportCorrelationId") and row.get("attemptCorrelationId"):
            transport[row["attemptCorrelationId"]].add(str(row["transportCorrelationId"]))
        if event in TERMINAL_FETCH_EVENTS:
            owner["terminal"] = row["outcome"]
            if active_by_key.get(key) == fetch_id:
                del active_by_key[key]
    _require(not active_by_key, f"non-terminal broker owners remain: {sorted(active_by_key)}")

    charged: dict[str, Mapping[str, Any]] = {}
    finished: dict[str, Mapping[str, Any]] = {}
    for chain in chains.values():
        for charge in chain.charges:
            charged[charge["attemptCorrelationId"]] = charge
        for fetch_id, event in chain.finished.items():
            finished[fetch_id] = event
            owner = owners.get(fetch_id)
            _require(owner is not None, f"{chain.chain_id}: owner {fetch_id} not in fetch evidence")
            _require(
                owner["key"] == chain.fetch_key
                and owner["consumer"] == f"recovery:{chain.chain_id}:{event['ownerOrdinal']}",
                f"{chain.chain_id}: owner {fetch_id} is not this chain's owner "
                f"#{event['ownerOrdinal']}",
            )
            _require(
                owner["terminal"] == event["ownerOutcome"],
                f"{chain.chain_id}: owner {fetch_id} outcome {owner['terminal']} != "
                f"budget {event['ownerOutcome']}",
            )

    physical = {
        row["attemptCorrelationId"]: fetch_id
        for fetch_id, rows in attempts.items()
        for row in rows
    }
    for correlation in physical:
        _require(correlation in charged, f"physical attempt {correlation} without budget charge")
    for correlation in charged:
        _require(correlation in physical, f"budget charge {correlation} without physical attempt")
    for fetch_id in owners:
        _require(fetch_id in finished, f"broker owner {fetch_id} unknown to every chain")
    for correlation, values in transport.items():
        _require(len(values) <= 1, f"{correlation} maps to several origin requests")
    return {
        "physicalAttemptCount": len(physical),
        "transport": {key: next(iter(value)) for key, value in transport.items() if value},
    }


def _verify_origin(
    origin: list[Mapping[str, Any]],
    fetch_result: Mapping[str, Any],
) -> int:
    data = [
        row for row in origin
        if row.get("plane") == "data" and str(row.get("path", "")).startswith("/fixtures/")
    ]
    request_ids = [str(row.get("requestId")) for row in data]
    _require(len(request_ids) == len(set(request_ids)), "origin request IDs must be unique")
    correlated = list(fetch_result["transport"].values())
    _require(
        len(correlated) == len(set(correlated)),
        "one origin request is attributed to several physical attempts",
    )
    hidden = sorted(set(request_ids) - set(correlated))
    _require(not hidden, f"origin requests without a charged attempt: {hidden}")
    foreign = sorted(set(correlated) - set(request_ids))
    _require(not foreign, f"attempt correlations absent from the origin trace: {foreign}")
    _require(
        len(request_ids) == fetch_result["physicalAttemptCount"],
        f"origin requests {len(request_ids)} != physical attempts "
        f"{fetch_result['physicalAttemptCount']}",
    )
    return len(request_ids)


def verify_recovery(
    failures_doc: Mapping[str, Any],
    budget_doc: Mapping[str, Any],
    fetch_rows: Iterable[Mapping[str, Any]],
    *,
    origin: list[Mapping[str, Any]] | None = None,
    case: Mapping[str, Any] | None = None,
    forbid_rechain_after_failure: bool = False,
) -> dict[str, Any]:
    _validate(FAILURE_SCHEMA, failures_doc, "failure-decision-events-v1")
    _validate(BUDGET_SCHEMA, budget_doc, "recovery-budget-events-v1")
    _check_privacy(failures_doc, "failure-decision-events-v1")
    _check_privacy(budget_doc, "recovery-budget-events-v1")
    _require(
        failures_doc["sessionId"] == budget_doc["sessionId"]
        and failures_doc["runId"] == budget_doc["runId"],
        "failure and budget evidence belong to different runs",
    )
    _require(
        failures_doc["policyId"] == budget_doc["policyId"],
        "failure and budget evidence name different policies",
    )
    fetch = _load_fetch(fetch_rows)

    chains, events = _replay_budget(budget_doc)
    rows = _verify_failures(failures_doc, chains)
    _verify_failure_lineage(events, rows, chains)
    fetch_result = _verify_fetch(fetch, chains, budget_doc["sessionId"])

    charged = sum(len(chain.charges) for chain in chains.values())
    _require(
        charged == fetch_result["physicalAttemptCount"],
        f"charged attempts {charged} != physical attempts {fetch_result['physicalAttemptCount']}",
    )
    origin_count = None
    if origin is not None:
        origin_count = _verify_origin(origin, fetch_result)

    if forbid_rechain_after_failure:
        # Media3 must not turn a terminal chain into a new one (M2-C 78).
        ended: dict[str, str] = {}
        for event in events:
            chain = chains[event["recoveryChainId"]]
            if event["kind"] == "CHAIN_STARTED":
                _require(
                    chain.fetch_key not in ended,
                    f"new RecoveryChain {chain.chain_id} for {chain.fetch_key} after "
                    f"terminal {ended.get(chain.fetch_key)}",
                )
            if event["kind"] == "CHAIN_TERMINATED" and event["terminalReason"] in (
                "TERMINAL_FAILURE", "BUDGET_EXHAUSTED",
            ):
                ended[chain.fetch_key] = event["terminalReason"]

    terminal_counts = Counter(chain.terminal for chain in chains.values())
    if case is not None:
        _require(case.get("policyId") == budget_doc["policyId"], "case policyId mismatch")
        _require(
            case.get("expectedPhysicalAttempts") == charged,
            f"expected {case.get('expectedPhysicalAttempts')} physical attempts, got {charged}",
        )
        _require(
            dict(case.get("expectedTerminals") or {}) == dict(terminal_counts),
            f"expected terminals {case.get('expectedTerminals')} != {dict(terminal_counts)}",
        )

    acc05_checks = [
        f"{len(rows)} failure rows with independently re-derived classification, "
        "decision and executed action",
        "observation layer carries only typed fields; no free text, URL or header",
        "every failed owner has exactly one failure row whose observation projects "
        "to its FetchBroker outcome",
    ]
    multi_owner = [chain for chain in chains.values() if len(chain.owners) >= 2]
    acc06_checks = [
        f"{len(multi_owner)} chains spanning several owner lifetimes on one ledger",
        f"{charged} charges == {fetch_result['physicalAttemptCount']} physical attempts",
        "ledger monotonic, dimensions persistent, spent <= limit, no reset on new "
        "fetchId, priority, permit or backoff",
    ]
    if origin_count is not None:
        acc06_checks.append(f"{origin_count} origin requests == physical attempts")

    summary = {
        "schemaVersion": 1,
        "runId": budget_doc["runId"],
        "sessionId": budget_doc["sessionId"],
        "policyId": budget_doc["policyId"],
        "status": "PASS",
        "chainCount": len(chains),
        "physicalAttemptCount": fetch_result["physicalAttemptCount"],
        "chargedAttemptCount": charged,
        "failureCount": len(rows),
        "originRequestCount": origin_count,
        "terminalCounts": dict(sorted(terminal_counts.items())),
        "classificationCounts": dict(sorted(Counter(r["classification"] for r in rows).items())),
        "decisionCounts": dict(sorted(Counter(r["decision"]["kind"] for r in rows).items())),
        "actionCounts": dict(sorted(Counter(r["action"]["kind"] for r in rows).items())),
        "gates": {
            "M2-ACC-05": {
                "status": "PASS" if rows else "NOT_EXERCISED",
                "checks": acc05_checks,
            },
            "M2-ACC-06": {
                "status": "PASS" if multi_owner else "NOT_EXERCISED",
                "checks": acc06_checks,
            },
        },
        "limitations": list(LIMITATIONS),
    }
    _validate(SUMMARY_SCHEMA, summary, "recovery-verification-summary-v1")
    return summary


def read_jsonl(path: pathlib.Path) -> list[dict[str, Any]]:
    rows = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if line.strip():
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError as error:
                raise RecoveryOracleError(f"{path}:{number}: invalid JSON") from error
    return rows


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    verify = commands.add_parser("verify", help="verify one run's M2-C recovery evidence")
    verify.add_argument("--failures", required=True, type=pathlib.Path)
    verify.add_argument("--budget", required=True, type=pathlib.Path)
    verify.add_argument("--fetch", required=True, type=pathlib.Path, help="fetch-events-v3 JSONL")
    verify.add_argument("--origin", type=pathlib.Path, help="Media Lab requests.jsonl")
    verify.add_argument("--case", type=pathlib.Path, help="scenario case.json")
    verify.add_argument("--output", required=True, type=pathlib.Path)
    verify.add_argument("--forbid-rechain-after-failure", action="store_true")
    verify.add_argument(
        "--require-gate",
        action="append",
        default=[],
        choices=("M2-ACC-05", "M2-ACC-06"),
        help="gate that must be PASS (repeatable)",
    )
    args = parser.parse_args(argv)

    try:
        summary = verify_recovery(
            json.loads(args.failures.read_text(encoding="utf-8")),
            json.loads(args.budget.read_text(encoding="utf-8")),
            read_jsonl(args.fetch),
            origin=read_jsonl(args.origin) if args.origin else None,
            case=json.loads(args.case.read_text(encoding="utf-8")) if args.case else None,
            forbid_rechain_after_failure=args.forbid_rechain_after_failure,
        )
        for gate in args.require_gate:
            _require(
                summary["gates"][gate]["status"] == "PASS",
                f"{gate} was required but is {summary['gates'][gate]['status']}",
            )
    except RecoveryOracleError as error:
        print(f"M2-C recovery verification failed: {error}", file=sys.stderr)
        return 1
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(
        f"M2-C recovery PASS: chains={summary['chainCount']} "
        f"physicalAttempts={summary['physicalAttemptCount']} "
        f"charges={summary['chargedAttemptCount']} failures={summary['failureCount']} "
        f"ACC-05={summary['gates']['M2-ACC-05']['status']} "
        f"ACC-06={summary['gates']['M2-ACC-06']['status']}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
