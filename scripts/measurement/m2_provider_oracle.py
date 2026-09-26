"""Independent host verifier for M2-D provider recovery evidence.

Normative sources: `.work/milestones/M2.md` (M2-D), `.work/VERIFICATION.md`
section 23, `.work/adr/0004-separate-immutable-work-from-delivery-binding.md`
and the M2-D design: `delivery-binding-events-v1` sections 2.1/2.2, the
`sponge-recovery-v2` decision table and executed actions (4.5-4.6), the
Retry-After contract (3), the deterministic provider simulator (6) and the
`m2_recovery_oracle` v2 rules (7).

Inputs of one run:

* `failure-decision-events-v2` - one row per failed FetchBroker owner with the
  normalized Retry-After observation, the provider signal, the delivery
  binding revision and the executed provider action;
* `recovery-budget-events-v1` - RecoveryChain lifecycle and ledger events;
* `delivery-binding-events-v1` - delivery binding selection and single-flight
  refresh lifecycle;
* `fetch-events-v4` (JSONL) - the FetchBroker physical owner ledger;
* the scenario `case.json`;
* optionally `provider-fault-events-v1` and the Media Lab origin trace.

The verifier never imports or trusts the production Kotlin coordinator. It
re-derives the provider decision table through `m2_recovery_oracle` (itself an
independent lineage verifier), replays the delivery binding revisions, the
single-flight refresh lifecycle and every provider observation with its own
tables, and joins every correlation identity exactly once. Absolute values are
never compared across clock domains: the only timing relations it checks are
ANDROID_MONOTONIC elapse inside failure/budget/delivery evidence,
PROVIDER_WALL_CLOCK inside authenticated provider waits and
HOST_MEDIA_LAB_MONOTONIC inside the origin trace. The origin trace carries raw
request paths; it is scanned for provider credentials and URL schemes only,
never interpreted as a locator.

Usage:

    python3 scripts/measurement/m2_provider_oracle.py verify \\
        --failures failure-decision-events.json \\
        --budget recovery-budget-events.json \\
        --delivery delivery-binding-events.json \\
        --fetch fetch-events.jsonl \\
        --case case.json \\
        --output provider-verification-summary.json
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
from collections import Counter, defaultdict
from typing import Any, Mapping

SCRIPT_DIR = pathlib.Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from m2_contracts import (  # noqa: E402
    M2ContractError,
    check_clock_relation,
    provider_wait_ms,
    scan_evidence_privacy,
)
from m2_recovery_oracle import (  # noqa: E402
    DELIVERY_BINDING_REFRESH,
    LIMITATIONS_V2,
    REMOTE_ATTEMPT,
    RecoveryOracleError,
    verify_recovery,
)
from schema_subset import SchemaContractError, validate_instance  # noqa: E402

SCHEMAS = REPO_ROOT / ".work" / "schemas"
FAILURE_SCHEMA = SCHEMAS / "failure-decision-events-v2.schema.json"
BUDGET_SCHEMA = SCHEMAS / "recovery-budget-events-v1.schema.json"
DELIVERY_SCHEMA = SCHEMAS / "delivery-binding-events-v1.schema.json"
FETCH_SCHEMA = SCHEMAS / "fetch-events-v4.schema.json"
FAULT_SCHEMA = SCHEMAS / "provider-fault-events-v1.schema.json"
SUMMARY_SCHEMA = SCHEMAS / "provider-verification-summary-v1.schema.json"

FAILURE_SCHEMA_VERSION = 2
FETCH_SCHEMA_VERSION = 4

GATE_IDS = ("M2-ACC-05", "M2-ACC-06", "M2-ACC-07", "M2-ACC-08")
PROVIDER_FAMILIES = ("N8", "N9", "N10")
EVIDENCE_SOURCES = ("HOST_SCRIPTED", "ANDROID_MEDIA_LAB")

_REVISION = re.compile(r"^binding-([1-9][0-9]*)$")
_PROVIDER_PATH_PREFIX = re.compile(r"^/provider/gen-[1-9][0-9]*")
_DATA_PLANE = "data"


class ProviderOracleError(ValueError):
    """A provider recovery contract violation (fails closed)."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ProviderOracleError(message)


# ---------------------------------------------------------------------------
# Provider-class privacy (DESIGN 2.2, M2.md section 15)
# ---------------------------------------------------------------------------

# Field names that may only ever carry opaque provider ids; a value under one
# of these names is a locator or a credential, never evidence.
PROVIDER_SECRET_KEYS = frozenset({
    "authorization",
    "cookie",
    "setcookie",
    "token",
    "accesstoken",
    "signature",
    "potoken",
    "visitordata",
    "url",
    "uri",
    "headers",
    "rawurl",
    "signedurl",
})

# String shapes a provider observation must never retain, even in free text.
# The patterns are credential/scheme shaped only: a plain request path such as
# `/provider/gen-1/fixtures/F1/segment-1-00001.m4s` deliberately does not match.
PROVIDER_SECRET_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"https?://", re.IGNORECASE), "URL scheme retained"),
    (re.compile(r"\bauthorization\b", re.IGNORECASE), "authorization field retained"),
    (re.compile(r"\bcookie\b", re.IGNORECASE), "cookie text retained"),
    (re.compile(r"\bbearer\s", re.IGNORECASE), "bearer credential retained"),
    (
        re.compile(r"[?&](?:sig|signature|token|pot|potoken)=", re.IGNORECASE),
        "query credential retained",
    ),
    (re.compile(r"\bpotoken\b", re.IGNORECASE), "PO token retained"),
    (re.compile(r"\bvisitor[_-]?(?:data|id)\b", re.IGNORECASE), "visitor identity retained"),
    (re.compile(r"\bsignature\b", re.IGNORECASE), "signature retained"),
)

_PROVIDER_KEY_NORMALIZER = re.compile(r"[^a-z0-9]")


def _normalized_key(key: str) -> str:
    return _PROVIDER_KEY_NORMALIZER.sub("", str(key).lower())


def scan_provider_privacy(document: Any, label: str, path: str = "$") -> None:
    """Provider-class secrets: forbidden field names and credential text.

    Works for unvalidated artifacts too (the scenario `case.json` and the
    origin trace). The origin trace is scanned for provider credentials and URL
    schemes only; its raw `path` values are legitimate fixture locators and are
    never interpreted as evidence.
    """

    if isinstance(document, Mapping):
        for key, value in document.items():
            normalized = _normalized_key(str(key))
            _require(
                normalized not in PROVIDER_SECRET_KEYS,
                f"{label} {path}.{key}: provider-class secret field must not be retained",
            )
            scan_provider_privacy(value, label, f"{path}.{key}")
    elif isinstance(document, list):
        for index, value in enumerate(document):
            scan_provider_privacy(value, label, f"{path}[{index}]")
    elif isinstance(document, str):
        for pattern, description in PROVIDER_SECRET_PATTERNS:
            _require(
                pattern.search(document) is None,
                f"{label} {path}: {description}",
            )


def _check_input_privacy(**inputs: Any) -> None:
    for label, document in inputs.items():
        if document is None:
            continue
        try:
            scan_evidence_privacy(document)
        except M2ContractError as error:
            raise ProviderOracleError(f"{label}: {error}") from error
        scan_provider_privacy(document, label)


# ---------------------------------------------------------------------------
# Clock domains (M2.md section 13)
# ---------------------------------------------------------------------------

# Every timing relation the oracle is allowed to compute. Same-domain only;
# cross-domain joins use correlation identities. Declared once and checked with
# the frozen `m2_contracts.check_clock_relation`.
DECLARED_CLOCK_RELATIONS: tuple[Mapping[str, str], ...] = (
    {"op": "ORDER", "leftDomain": "ANDROID_MONOTONIC", "rightDomain": "ANDROID_MONOTONIC"},
    {"op": "SUBTRACT", "leftDomain": "ANDROID_MONOTONIC", "rightDomain": "ANDROID_MONOTONIC"},
    {"op": "SUBTRACT", "leftDomain": "PROVIDER_WALL_CLOCK", "rightDomain": "PROVIDER_WALL_CLOCK"},
    {
        "op": "SUBTRACT",
        "leftDomain": "HOST_MEDIA_LAB_MONOTONIC",
        "rightDomain": "HOST_MEDIA_LAB_MONOTONIC",
    },
)


def _check_declared_clock_relations() -> None:
    for relation in DECLARED_CLOCK_RELATIONS:
        try:
            check_clock_relation(relation)
        except M2ContractError as error:
            raise ProviderOracleError(f"clock relation declaration: {error}") from error


def _revision_number(value: Any) -> int:
    match = _REVISION.match(str(value))
    _require(match is not None, f"invalid delivery binding revision {value!r}")
    return int(match.group(1))


# ---------------------------------------------------------------------------
# Loading and schema validation
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
        raise ProviderOracleError(f"{label} schema: {error}") from error


def _validate_documents(
    failures_doc: Mapping[str, Any],
    budget_doc: Mapping[str, Any],
    delivery_doc: Mapping[str, Any],
    fetch_rows: list[Mapping[str, Any]],
    provider_faults: Mapping[str, Any] | None,
) -> None:
    version = failures_doc.get("schemaVersion")
    _require(
        version == FAILURE_SCHEMA_VERSION,
        f"failure-decision-events-v{version!r} is not accepted by M2-D provider "
        "verification; v2 is required",
    )
    _validate(FAILURE_SCHEMA, failures_doc, "failure-decision-events-v2")
    _validate(BUDGET_SCHEMA, budget_doc, "recovery-budget-events-v1")
    _validate(DELIVERY_SCHEMA, delivery_doc, "delivery-binding-events-v1")
    _require(bool(fetch_rows), "fetch evidence is empty")
    for index, row in enumerate(fetch_rows):
        _require(
            row.get("schemaVersion") == FETCH_SCHEMA_VERSION,
            f"fetch[{index}]: M2-D provider verification requires fetch-events-v4",
        )
        _validate(FETCH_SCHEMA, row, f"fetch[{index}]")
    if provider_faults is not None:
        _validate(FAULT_SCHEMA, provider_faults, "provider-fault-events-v1")


def _check_case(case: Mapping[str, Any]) -> None:
    _require(isinstance(case, Mapping), "case.json must be an object")
    for field in (
        "schemaVersion",
        "caseId",
        "evidenceSource",
        "policyId",
        "scenarioFamily",
        "variant",
        "expectedPhysicalAttempts",
        "expectedTerminals",
        "expectedRefreshOperations",
        "expectedProviderWaits",
        "expectedBindingRevisions",
        "persistedExtentIdsBefore",
        "persistedExtentIdsAfter",
        "bindingsEnabled",
    ):
        _require(field in case, f"case.json: missing {field}")
    _require(case["schemaVersion"] == 1, "case.json: unsupported schemaVersion")
    _require(
        isinstance(case["caseId"], str) and case["caseId"],
        "case.json: caseId must be a non-empty string",
    )
    _require(
        case["evidenceSource"] in EVIDENCE_SOURCES,
        f"case.json: unknown evidenceSource {case['evidenceSource']!r}",
    )
    _require(
        isinstance(case["policyId"], str) and case["policyId"],
        "case.json: policyId must be a non-empty string",
    )
    _require(
        case["scenarioFamily"] in (*PROVIDER_FAMILIES, None),
        f"case.json: unknown scenarioFamily {case['scenarioFamily']!r}",
    )
    _require(
        case["variant"] is None
        or (isinstance(case["variant"], str) and re.match(r"^[A-Z][A-Z0-9_]*$", case["variant"])),
        f"case.json: invalid variant {case['variant']!r}",
    )
    for field in ("expectedPhysicalAttempts", "expectedRefreshOperations"):
        _require(
            isinstance(case[field], int) and not isinstance(case[field], bool) and case[field] >= 0,
            f"case.json: {field} must be a non-negative integer",
        )
    _require(
        isinstance(case["expectedTerminals"], Mapping),
        "case.json: expectedTerminals must be an object",
    )
    _require(
        isinstance(case["expectedProviderWaits"], list)
        and all(
            isinstance(value, int) and not isinstance(value, bool) and value >= 0
            for value in case["expectedProviderWaits"]
        ),
        "case.json: expectedProviderWaits must be a list of non-negative integers",
    )
    _require(
        isinstance(case["expectedBindingRevisions"], list)
        and all(_REVISION.match(str(value)) is not None for value in case["expectedBindingRevisions"]),
        "case.json: expectedBindingRevisions must be a list of binding-N ids",
    )
    for field in ("persistedExtentIdsBefore", "persistedExtentIdsAfter"):
        _require(
            isinstance(case[field], list)
            and all(isinstance(value, str) and value for value in case[field]),
            f"case.json: {field} must be a list of non-empty strings",
        )
    _require(
        isinstance(case["bindingsEnabled"], bool),
        "case.json: bindingsEnabled must be boolean",
    )


# ---------------------------------------------------------------------------
# Budget chain index
# ---------------------------------------------------------------------------


class _Chain:
    def __init__(self, event: Mapping[str, Any]) -> None:
        self.id = event["recoveryChainId"]
        self.fetch_key = event["fetchKey"]
        self.extent_id = event["extentId"]
        self.policy_id = event["policyId"]
        self.limits = dict(event["limits"])
        self.terminal: str | None = None
        self.terminal_failure_id: str | None = None
        self.terminal_elapsed_ns: int | None = None
        self.charges: list[Mapping[str, Any]] = []
        self.refresh_charges: list[Mapping[str, Any]] = []
        self.events: list[Mapping[str, Any]] = []


def _budget_chains(document: Mapping[str, Any]) -> dict[str, _Chain]:
    chains: dict[str, _Chain] = {}
    for index, event in enumerate(document["events"]):
        where = f"budget.events[{index}]"
        chain_id = event["recoveryChainId"]
        if event["kind"] == "CHAIN_STARTED":
            _require(chain_id not in chains, f"{where}: chain {chain_id} started twice")
            chains[chain_id] = _Chain(event)
        chain = chains.get(chain_id)
        _require(chain is not None, f"{where}: event for unstarted chain {chain_id}")
        chain.events.append(event)
        if event["kind"] == "CHARGE":
            charge = event["charge"]
            _require(charge is not None, f"{where}: CHARGE without charge")
            chain.charges.append(event)
            if charge["dimension"] == DELIVERY_BINDING_REFRESH:
                chain.refresh_charges.append(event)
        elif event["kind"] == "CHAIN_TERMINATED":
            chain.terminal = event["terminalReason"]
            chain.terminal_failure_id = event["failureId"]
            chain.terminal_elapsed_ns = event["elapsedRealtimeNs"]
    return chains


def _remote_charges(chains: Mapping[str, _Chain]) -> list[Mapping[str, Any]]:
    charges = [
        event
        for chain in chains.values()
        for event in chain.charges
        if event["charge"]["dimension"] == REMOTE_ATTEMPT
    ]
    charges.sort(key=lambda event: event["sequence"])
    return charges


def _refresh_charges(chains: Mapping[str, _Chain]) -> list[Mapping[str, Any]]:
    charges = [event for chain in chains.values() for event in chain.refresh_charges]
    charges.sort(key=lambda event: event["sequence"])
    return charges


# ---------------------------------------------------------------------------
# Delivery binding replay (DESIGN 2.1/2.2)
# ---------------------------------------------------------------------------

CHAIN_SCOPED_KINDS = frozenset({
    "BINDING_SELECTED_FOR_ATTEMPT",
    "REFRESH_REQUESTED",
    "REFRESH_STARTED",
    "REFRESH_JOINED",
    "REVISION_ALREADY_ADVANCED",
    "REFRESH_NOT_ADMITTED",
    "REFRESH_CLOSED",
})

COMPLETION_OUTCOMES = {
    "REFRESH_SUCCEEDED": "SUCCEEDED",
    "REFRESH_FAILED": "FAILED",
    "REFRESH_INCOMPATIBLE": "INCOMPATIBLE",
    "REFRESH_CANCELLED": "CANCELLED",
}


class _RefreshOperation:
    def __init__(self, event: Mapping[str, Any]) -> None:
        self.id = event["refreshCorrelationId"]
        self.initiator_chain = event["recoveryChainId"]
        self.failure_id = event["failureId"]
        self.expected = event["previousRevision"]
        self.installed_revision: str | None = None
        self.completion: str | None = None
        self.completion_sequence: int | None = None
        self.started_sequence = event["sequence"]
        self.joiners: list[str] = []
        self.participants: list[str] = [self.initiator_chain]


class _DeliveryReplay:
    def __init__(self) -> None:
        self.selections: dict[str, Mapping[str, Any]] = {}
        self.selections_by_chain: dict[str, list[Mapping[str, Any]]] = defaultdict(list)
        self.by_chain: dict[str, list[Mapping[str, Any]]] = defaultdict(list)
        self.operations: dict[str, _RefreshOperation] = {}
        self.started_count = 0
        self.joined_count = 0
        self.already_advanced_count = 0
        self.requests: list[Mapping[str, Any]] = []


def _replay_delivery(
    document: Mapping[str, Any],
    chains: Mapping[str, _Chain],
) -> _DeliveryReplay:
    """Replay the delivery binding revision state and refresh lifecycle."""

    events = document["events"]
    state = _DeliveryReplay()
    revision = 1
    in_flight: _RefreshOperation | None = None
    open_requests: dict[tuple[str, str | None], Mapping[str, Any]] = {}
    previous_elapsed = -1
    for index, event in enumerate(events):
        where = f"delivery.events[{index}]"
        _require(
            event["sequence"] == index + 1,
            f"{where}: sequence {event['sequence']} != {index + 1} (must be contiguous)",
        )
        _require(
            event["elapsedRealtimeNs"] >= previous_elapsed,
            f"{where}: elapsedRealtimeNs decreased (ANDROID_MONOTONIC)",
        )
        previous_elapsed = event["elapsedRealtimeNs"]
        chain_id = event["recoveryChainId"]
        chain = chains.get(chain_id)
        _require(
            chain is not None,
            f"{where}: delivery event for chain {chain_id} without budget evidence",
        )
        _require(
            (event["fetchKey"], event["extentId"]) == (chain.fetch_key, chain.extent_id),
            f"{where}: delivery work identity differs from chain {chain_id}"
            f" ({event['fetchKey']!r}/{event['extentId']!r} != "
            f"{chain.fetch_key!r}/{chain.extent_id!r})",
        )
        kind = event["kind"]
        previous = event["previousRevision"]
        current = event["currentRevision"]
        correlation = event["refreshCorrelationId"]
        failure_key = (chain_id, event["failureId"])
        state.by_chain[chain_id].append(event)

        if kind in CHAIN_SCOPED_KINDS and chain.terminal_elapsed_ns is not None:
            _require(
                event["elapsedRealtimeNs"] <= chain.terminal_elapsed_ns,
                f"{where}: {kind} after chain {chain_id} terminated",
            )

        if kind == "BINDING_SELECTED_FOR_ATTEMPT":
            _require(previous is None, f"{where}: selection carries a previousRevision")
            _require(current is not None, f"{where}: selection without a selected revision")
            _require(
                event["attemptCorrelationId"] is not None,
                f"{where}: selection without an attempt correlation",
            )
            _require(correlation is None, f"{where}: selection carries a refresh correlation")
            _require(event["outcome"] is None, f"{where}: selection carries an outcome")
            attempt = event["attemptCorrelationId"]
            _require(
                attempt not in state.selections,
                f"{where}: second BINDING_SELECTED_FOR_ATTEMPT for {attempt}",
            )
            state.selections[attempt] = event
            state.selections_by_chain[chain_id].append(event)
            continue

        if kind == "REFRESH_REQUESTED":
            _require(previous is not None, f"{where}: refresh request without an expected revision")
            _require(correlation is None, f"{where}: refresh request carries a correlation id")
            _require(event["outcome"] is None, f"{where}: refresh request carries an outcome")
            _require(
                current == f"binding-{revision}",
                f"{where}: refresh request currentRevision {current} is not the current "
                f"binding-{revision}",
            )
            _require(
                failure_key not in open_requests,
                f"{where}: second unresolved refresh request for {failure_key}",
            )
            open_requests[failure_key] = event
            state.requests.append(event)
            continue

        if kind in ("REFRESH_STARTED", "REFRESH_JOINED"):
            _require(correlation is not None, f"{where}: {kind} without a refresh correlation id")
            _require(
                previous == f"binding-{revision}",
                f"{where}: {kind} expected revision {previous} is not binding-{revision}",
            )
            _require(
                current == previous,
                f"{where}: {kind} currentRevision {current} != expected {previous}",
            )
            _require(
                failure_key in open_requests,
                f"{where}: {kind} without a matching REFRESH_REQUESTED from {failure_key}",
            )
            del open_requests[failure_key]
            if kind == "REFRESH_STARTED":
                _require(
                    in_flight is None,
                    f"{where}: a second refresh operation started while "
                    f"{in_flight.id if in_flight else None} was in flight",
                )
                _require(
                    correlation not in state.operations,
                    f"{where}: refresh correlation {correlation} reused",
                )
                in_flight = _RefreshOperation(event)
                state.operations[correlation] = in_flight
                state.started_count += 1
            else:
                _require(
                    in_flight is not None,
                    f"{where}: REFRESH_JOINED without an operation in flight",
                )
                _require(
                    in_flight.id == correlation,
                    f"{where}: REFRESH_JOINED for {correlation} but {in_flight.id} is in flight",
                )
                _require(
                    chain_id != in_flight.initiator_chain,
                    f"{where}: REFRESH_JOINED from the initiator chain {chain_id}",
                )
                in_flight.joiners.append(chain_id)
                in_flight.participants.append(chain_id)
                state.joined_count += 1
            continue

        if kind == "REVISION_ALREADY_ADVANCED":
            _require(
                previous is not None and current is not None and previous != current,
                f"{where}: REVISION_ALREADY_ADVANCED must show a revision change",
            )
            _require(
                current == f"binding-{revision}",
                f"{where}: REVISION_ALREADY_ADVANCED currentRevision {current} is not "
                f"binding-{revision}",
            )
            _require(
                correlation is None,
                f"{where}: REVISION_ALREADY_ADVANCED carries a refresh correlation id",
            )
            _require(
                failure_key in open_requests,
                f"{where}: REVISION_ALREADY_ADVANCED without a matching REFRESH_REQUESTED",
            )
            del open_requests[failure_key]
            state.already_advanced_count += 1
            continue

        if kind == "REFRESH_NOT_ADMITTED":
            _require(
                previous == f"binding-{revision}",
                f"{where}: REFRESH_NOT_ADMITTED expected revision {previous} is not "
                f"binding-{revision}",
            )
            _require(
                current == previous,
                f"{where}: REFRESH_NOT_ADMITTED currentRevision {current} != expected",
            )
            _require(correlation is not None, f"{where}: REFRESH_NOT_ADMITTED without an id")
            _require(
                failure_key in open_requests,
                f"{where}: REFRESH_NOT_ADMITTED without a matching REFRESH_REQUESTED",
            )
            del open_requests[failure_key]
            _require(in_flight is None, f"{where}: REFRESH_NOT_ADMITTED while an operation is in flight")
            continue

        if kind == "REFRESH_CLOSED":
            _require(previous is not None, f"{where}: REFRESH_CLOSED without an expected revision")
            _require(correlation is None, f"{where}: REFRESH_CLOSED carries a correlation id")
            _require(
                failure_key in open_requests,
                f"{where}: REFRESH_CLOSED without a matching REFRESH_REQUESTED",
            )
            del open_requests[failure_key]
            _require(in_flight is None, f"{where}: REFRESH_CLOSED while an operation is in flight")
            continue

        if kind in COMPLETION_OUTCOMES:
            _require(
                in_flight is not None,
                f"{where}: refresh completion {kind} without a started operation",
            )
            assert in_flight is not None
            _require(
                in_flight.id == correlation,
                f"{where}: completion {correlation} does not match in-flight {in_flight.id}",
            )
            _require(
                event["outcome"] == COMPLETION_OUTCOMES[kind],
                f"{where}: {kind} outcome {event['outcome']!r} is inconsistent",
            )
            _require(
                previous == f"binding-{revision}",
                f"{where}: {kind} expected revision {previous} is not binding-{revision}",
            )
            if kind == "REFRESH_SUCCEEDED":
                _require(current is not None, f"{where}: REFRESH_SUCCEEDED without a revision")
                _require(
                    current != previous,
                    f"{where}: a successful refresh must advance the revision, never {previous}",
                )
                _require(
                    _revision_number(current) == revision + 1,
                    f"{where}: a successful refresh must install binding-{revision + 1}, "
                    f"got {current}",
                )
                in_flight.installed_revision = current
                revision += 1
            else:
                _require(
                    current == previous,
                    f"{where}: a failed refresh must keep binding {previous}",
                )
            in_flight.completion = kind
            in_flight.completion_sequence = event["sequence"]
            in_flight = None
            continue

        _require(False, f"{where}: unknown delivery event kind {kind!r}")

    _require(in_flight is None, "delivery evidence ends with an operation still in flight")
    _require(not open_requests, f"unresolved REFRESH_REQUESTED for {sorted(open_requests)}")
    return state


# ---------------------------------------------------------------------------
# Provider semantics of every failure row (DESIGN 4.5, 3)
# ---------------------------------------------------------------------------


def _previous_failure_id(
    rows: list[Mapping[str, Any]],
    chain_id: str,
    before_ns: int,
) -> str | None:
    previous: str | None = None
    for row in rows:
        if row["recoveryChainId"] != chain_id:
            continue
        if row["elapsedRealtimeNs"] <= before_ns:
            previous = row["failureId"]
    return previous


def _next_charge_after(
    chain: _Chain,
    row: Mapping[str, Any],
) -> Mapping[str, Any] | None:
    candidates = [
        event
        for event in chain.charges
        if event["attemptCorrelationId"] != row["attemptCorrelationId"]
        and event["elapsedRealtimeNs"] >= row["elapsedRealtimeNs"]
    ]
    if not candidates:
        return None
    return min(candidates, key=lambda event: event["elapsedRealtimeNs"])


def _check_provider_semantics(
    rows: list[Mapping[str, Any]],
    chains: Mapping[str, _Chain],
    replay: _DeliveryReplay,
) -> None:
    for row in rows:
        observation = row["observation"]
        if observation["type"] != "HTTP_RESPONSE":
            continue
        chain = chains[row["recoveryChainId"]]
        status = observation["httpStatus"]
        if status == 403 and observation["providerSignal"] == "NONE":
            _require(
                row["classification"] == "PROVIDER_REJECTED",
                f"{row['failureId']}: a bare 403 is PROVIDER_REJECTED, not "
                f"{row['classification']}; only an explicit stale signal refines a rejection",
            )
            _require(
                not any(
                    event["kind"] == "REFRESH_REQUESTED"
                    and event["failureId"] == row["failureId"]
                    for event in replay.by_chain[chain.id]
                ),
                f"{row['failureId']}: a bare 403 must not request a delivery-binding refresh",
            )
        if status != 429:
            continue
        _require(
            observation["plane"] == "PROVIDER",
            f"{row['failureId']}: HTTP 429 observed outside the provider plane",
        )
        _require(
            row["classification"] == "PROVIDER_RATE_LIMITED",
            f"{row['failureId']}: HTTP 429 is PROVIDER_RATE_LIMITED, not "
            f"{row['classification']}",
        )
        retry_after = observation["retryAfter"]
        raw_kind = retry_after["rawKind"]
        if (
            raw_kind in ("ABSENT", "MALFORMED")
            and row["context"]["demandPresent"]
            and row["context"]["remoteAttemptsRemaining"] > 0
        ):
            _require(
                row["action"]["kind"] == "TERMINATE_FAILURE",
                f"{row['failureId']}: a {raw_kind} Retry-After must fail closed, not "
                f"{row['action']['kind']}",
            )
            later = [
                event
                for event in chain.charges
                if event["elapsedRealtimeNs"] > row["elapsedRealtimeNs"]
            ]
            _require(
                not later,
                f"{row['failureId']}: a {raw_kind} Retry-After was followed by another charge",
            )
        if row["action"]["kind"] != "WAIT_PROVIDER":
            continue
        wait = row["action"]["providerWait"]
        _require(wait is not None, f"{row['failureId']}: WAIT_PROVIDER without a wait record")
        domain = wait["wallClockDomain"]
        _require(
            domain is None or domain == "PROVIDER_WALL_CLOCK",
            f"{row['failureId']}: provider wait wall clock domain {domain!r} is not "
            "PROVIDER_WALL_CLOCK",
        )
        _require(
            wait["rawKind"] == raw_kind,
            f"{row['failureId']}: provider wait rawKind {wait['rawKind']} != "
            f"observation {raw_kind}",
        )
        normalized = {
            "rawKind": retry_after["rawKind"],
            "delaySeconds": retry_after["delaySeconds"],
            "notBeforeUtcEpochMs": retry_after["notBeforeUtcEpochMs"],
        }
        try:
            expected_wait_ms = provider_wait_ms(normalized, wait["wallClockNowUtcEpochMs"])
        except M2ContractError as error:
            raise ProviderOracleError(
                f"{row['failureId']}: provider wait is not derivable: {error}"
            ) from error
        _require(
            wait["waitMs"] == expected_wait_ms,
            f"{row['failureId']}: provider waitMs {wait['waitMs']} != oracle "
            f"{expected_wait_ms}",
        )
        if raw_kind == "HTTP_DATE":
            _require(
                domain == "PROVIDER_WALL_CLOCK",
                f"{row['failureId']}: an HTTP_DATE wait needs the PROVIDER_WALL_CLOCK domain",
            )
        else:
            _require(
                wait["wallClockNowUtcEpochMs"] is None,
                f"{row['failureId']}: a DELAY_SECONDS wait carries a wall clock instant",
            )
        next_charge = _next_charge_after(chain, row)
        if next_charge is not None:
            _require(
                next_charge["elapsedRealtimeNs"]
                >= row["elapsedRealtimeNs"] + wait["waitMs"] * 1_000_000,
                f"{row['failureId']}: next charge before the {wait['waitMs']} ms provider "
                "wait elapsed",
            )


# ---------------------------------------------------------------------------
# Delivery joins (DESIGN 2.2, 4.6, 5)
# ---------------------------------------------------------------------------


def _physical_attempts(
    fetch_rows: list[Mapping[str, Any]],
) -> dict[str, dict[str, Any]]:
    attempts: dict[str, dict[str, Any]] = {}
    for index, row in enumerate(fetch_rows):
        where = f"fetch[{index}]"
        if row["event"] == "ATTEMPT_STARTED":
            attempt = row["attemptCorrelationId"]
            _require(attempt is not None, f"{where}: attempt without a correlation id")
            _require(attempt not in attempts, f"{where}: attempt {attempt} started twice")
            attempts[attempt] = {
                "fetchId": row["fetchId"],
                "fetchKey": row["fetchKey"],
                "transportCorrelationId": None,
            }
        attempt = row["attemptCorrelationId"]
        transport = row["transportCorrelationId"]
        if attempt in attempts and transport is not None:
            recorded = attempts[attempt]["transportCorrelationId"]
            _require(
                recorded is None or recorded == transport,
                f"{where}: attempt {attempt} maps to several origin requests",
            )
            attempts[attempt]["transportCorrelationId"] = transport
    return attempts


def _check_joins(
    failures_doc: Mapping[str, Any],
    case: Mapping[str, Any],
    chains: Mapping[str, _Chain],
    replay: _DeliveryReplay,
) -> None:
    rows = failures_doc["failures"]
    remote_charges = _remote_charges(chains)
    refresh_charges = _refresh_charges(chains)

    if case["bindingsEnabled"]:
        _require(
            len(replay.selections) == len(remote_charges),
            f"{len(remote_charges)} REMOTE_ATTEMPT charges but "
            f"{len(replay.selections)} BINDING_SELECTED_FOR_ATTEMPT rows",
        )
        for charge in remote_charges:
            attempt = charge["attemptCorrelationId"]
            selection = replay.selections.get(attempt)
            _require(
                selection is not None,
                f"charge {attempt}: no BINDING_SELECTED_FOR_ATTEMPT for the attempt",
            )
            assert selection is not None
            _require(
                selection["recoveryChainId"] == charge["recoveryChainId"],
                f"charge {attempt}: selection belongs to {selection['recoveryChainId']}",
            )
            previous_failure = _previous_failure_id(
                rows, charge["recoveryChainId"], selection["elapsedRealtimeNs"]
            )
            _require(
                selection["failureId"] == previous_failure,
                f"charge {attempt}: selection failureId {selection['failureId']!r} != "
                f"previous failure row {previous_failure!r}",
            )

    for row in rows:
        revision = row["observation"]["deliveryBindingRevision"]
        if revision is None:
            continue
        attempt = row["attemptCorrelationId"]
        _require(
            attempt is not None,
            f"{row['failureId']}: binding revision without an attempt correlation",
        )
        selection = replay.selections.get(attempt)
        _require(
            selection is not None and selection["currentRevision"] == revision,
            f"{row['failureId']}: observation revision {revision} != selected revision "
            f"{selection['currentRevision'] if selection else None}",
        )

    # One DELIVERY_BINDING_REFRESH charge <-> one actual refresh operation.
    _require(
        len(refresh_charges) == len(replay.operations),
        f"{len(refresh_charges)} DELIVERY_BINDING_REFRESH charges != "
        f"{len(replay.operations)} refresh operations",
    )
    charges_by_key: dict[tuple[str, str | None], Mapping[str, Any]] = {}
    for charge in refresh_charges:
        key = (charge["recoveryChainId"], charge["failureId"])
        _require(key not in charges_by_key, f"two refresh charges for {key}")
        charges_by_key[key] = charge
    refresh_charge_keys = set(charges_by_key)
    for operation in replay.operations.values():
        key = (operation.initiator_chain, operation.failure_id)
        _require(
            key in charges_by_key,
            f"refresh operation {operation.id}: no DELIVERY_BINDING_REFRESH charge for {key}",
        )
        del charges_by_key[key]
    _require(
        not charges_by_key,
        f"refresh charge without a started operation: {sorted(charges_by_key)}",
    )

    # Failure-row refresh results agree with the delivery events.
    for row in rows:
        action = row["action"]
        if action["kind"] != "REFRESH_DELIVERY_BINDING":
            continue
        binding = action["deliveryBinding"]
        _require(
            binding is not None,
            f"{row['failureId']}: REFRESH_DELIVERY_BINDING without a deliveryBinding record",
        )
        assert binding is not None
        result = binding["result"]
        chain_id = row["recoveryChainId"]
        correlation = binding["refreshCorrelationId"]
        if result in ("REFRESHED", "JOINED_REFRESH", "INCOMPATIBLE", "FAILED"):
            _require(
                correlation is not None,
                f"{row['failureId']}: {result} without a refresh correlation id",
            )
            operation = replay.operations.get(correlation)
            _require(
                operation is not None,
                f"{row['failureId']}: {result} names unknown operation {correlation}",
            )
            assert operation is not None
            expected_completion = {
                "REFRESHED": "REFRESH_SUCCEEDED",
                "JOINED_REFRESH": "REFRESH_SUCCEEDED",
                "INCOMPATIBLE": "REFRESH_INCOMPATIBLE",
                "FAILED": "REFRESH_FAILED",
            }[result]
            _require(
                operation.completion == expected_completion,
                f"{row['failureId']}: {result} but operation {correlation} completed "
                f"{operation.completion}",
            )
            initiated = (
                operation.initiator_chain == chain_id
                and operation.failure_id == row["failureId"]
            )
            if result == "REFRESHED":
                _require(
                    initiated,
                    f"{row['failureId']}: REFRESHED but operation {correlation} was not "
                    "started by this row",
                )
                _require(
                    binding["charged"] is True,
                    f"{row['failureId']}: REFRESHED must charge this chain",
                )
                _require(
                    binding["currentRevision"] == operation.installed_revision,
                    f"{row['failureId']}: REFRESHED currentRevision "
                    f"{binding['currentRevision']} != installed "
                    f"{operation.installed_revision}",
                )
            elif result == "JOINED_REFRESH":
                _require(
                    chain_id in operation.joiners and not initiated,
                    f"{row['failureId']}: JOINED_REFRESH but chain {chain_id} never joined "
                    f"{correlation}",
                )
                _require(
                    binding["charged"] is False,
                    f"{row['failureId']}: JOINED_REFRESH must not charge this chain",
                )
                _require(
                    binding["currentRevision"] == operation.installed_revision,
                    f"{row['failureId']}: JOINED_REFRESH currentRevision "
                    f"{binding['currentRevision']} != installed "
                    f"{operation.installed_revision}",
                )
            else:
                _require(
                    binding["charged"] is initiated,
                    f"{row['failureId']}: {result} charged={binding['charged']} but this "
                    f"chain {'started' if initiated else 'did not start'} {correlation}",
                )
                _require(
                    binding["currentRevision"] is None,
                    f"{row['failureId']}: {result} must not advance the binding",
                )
        elif result == "ALREADY_ADVANCED":
            events = [
                event
                for event in replay.by_chain[chain_id]
                if event["kind"] == "REVISION_ALREADY_ADVANCED"
                and event["failureId"] == row["failureId"]
            ]
            _require(
                len(events) == 1,
                f"{row['failureId']}: ALREADY_ADVANCED without exactly one "
                "REVISION_ALREADY_ADVANCED event",
            )
            _require(
                binding["currentRevision"] == events[0]["currentRevision"],
                f"{row['failureId']}: ALREADY_ADVANCED currentRevision "
                f"{binding['currentRevision']} != {events[0]['currentRevision']}",
            )
        elif result in ("NOT_ADMITTED", "CLOSED"):
            event_kind = "REFRESH_NOT_ADMITTED" if result == "NOT_ADMITTED" else "REFRESH_CLOSED"
            events = [
                event
                for event in replay.by_chain[chain_id]
                if event["kind"] == event_kind and event["failureId"] == row["failureId"]
            ]
            _require(
                len(events) == 1,
                f"{row['failureId']}: {result} without exactly one {event_kind} event",
            )
            _require(
                binding["charged"] is False,
                f"{row['failureId']}: {result} must not charge this chain",
            )
        elif result == "ABANDONED":
            # DESIGN 4.6: the chain stopped waiting (session shutdown, or its
            # last consumer left) while the refresh was outstanding. The row
            # names the operation when the coordinator returned its cancelled
            # result; when the wait itself was abandoned first, the row carries
            # no correlation id because this chain never observed an operation.
            # A named operation must be the cancelled one and the chain must
            # have taken part in it.
            if correlation is None:
                _require(
                    binding["charged"] is False
                    or (chain_id, row["failureId"]) in refresh_charge_keys,
                    f"{row['failureId']}: ABANDONED claims a charge without a "
                    "DELIVERY_BINDING_REFRESH charge",
                )
            else:
                operation = replay.operations.get(correlation)
                _require(
                    operation is not None,
                    f"{row['failureId']}: ABANDONED names unknown operation {correlation}",
                )
                assert operation is not None
                _require(
                    operation.completion == "REFRESH_CANCELLED",
                    f"{row['failureId']}: ABANDONED but operation {correlation} "
                    f"completed {operation.completion}",
                )
                _require(
                    chain_id in operation.participants,
                    f"{row['failureId']}: ABANDONED but chain {chain_id} never took "
                    f"part in {correlation}",
                )
                initiated = (
                    operation.initiator_chain == chain_id
                    and operation.failure_id == row["failureId"]
                )
                _require(
                    binding["charged"] is initiated,
                    f"{row['failureId']}: ABANDONED charged={binding['charged']} but "
                    f"this chain {'started' if initiated else 'did not start'} "
                    f"{correlation}",
                )
        else:
            _require(False, f"{row['failureId']}: unknown refresh result {result!r}")

    # After a successful or already-advanced refresh the next owner uses the
    # new revision.
    for row in rows:
        binding = row["action"]["deliveryBinding"]
        if binding is None or row["action"]["kind"] != "REFRESH_DELIVERY_BINDING":
            continue
        result = binding["result"]
        if result not in ("REFRESHED", "JOINED_REFRESH", "ALREADY_ADVANCED"):
            continue
        chain_id = row["recoveryChainId"]
        current_revision = binding["currentRevision"]
        _require(
            current_revision is not None,
            f"{row['failureId']}: {result} without a current revision",
        )
        if result == "ALREADY_ADVANCED":
            event = next(
                event
                for event in replay.by_chain[chain_id]
                if event["kind"] == "REVISION_ALREADY_ADVANCED"
                and event["failureId"] == row["failureId"]
            )
            completion_sequence = event["sequence"]
        else:
            operation = replay.operations.get(binding["refreshCorrelationId"])
            _require(
                operation is not None,
                f"{row['failureId']}: {result} names unknown operation "
                f"{binding['refreshCorrelationId']}",
            )
            assert operation is not None
            completion_sequence = operation.completion_sequence
        later = [
            selection
            for selection in replay.selections_by_chain[chain_id]
            if selection["sequence"] > completion_sequence
        ]
        if later:
            next_revision = later[0]["currentRevision"]
            _require(
                _revision_number(next_revision) >= _revision_number(current_revision),
                f"{row['failureId']}: the next owner still used {next_revision} after "
                f"{result} advanced to {current_revision}",
            )

    # A failed-closed binding never starts another owner.
    for row in rows:
        binding = row["action"]["deliveryBinding"]
        if binding is None or row["action"]["kind"] != "REFRESH_DELIVERY_BINDING":
            continue
        if binding["result"] not in ("INCOMPATIBLE", "FAILED"):
            continue
        chain = chains[row["recoveryChainId"]]
        later_charges = [
            event
            for event in chain.charges
            if event["elapsedRealtimeNs"] > row["elapsedRealtimeNs"]
        ]
        _require(
            not later_charges,
            f"{row['failureId']}: a {binding['result']} refresh was followed by another "
            "charge (owner)",
        )
        later_selections = [
            selection
            for selection in replay.selections_by_chain[chain.id]
            if selection["elapsedRealtimeNs"] > row["elapsedRealtimeNs"]
        ]
        _require(
            not later_selections,
            f"{row['failureId']}: a {binding['result']} refresh was followed by an "
            "owner selection",
        )

    # A refresh never resets REMOTE_ATTEMPT: the context of a refresh row must
    # equal the ledger after its own owner charge.
    charge_by_attempt = {
        event["attemptCorrelationId"]: event for event in remote_charges
    }
    for row in rows:
        if row["action"]["kind"] != "REFRESH_DELIVERY_BINDING":
            continue
        charge = charge_by_attempt.get(row["attemptCorrelationId"])
        _require(
            charge is not None,
            f"{row['failureId']}: refresh row without a REMOTE_ATTEMPT charge",
        )
        assert charge is not None
        limit = chains[row["recoveryChainId"]].limits[REMOTE_ATTEMPT]
        remaining = limit - charge["charge"]["spentAfter"]
        _require(
            row["context"]["remoteAttemptsRemaining"] == remaining,
            f"{row['failureId']}: refresh context remoteAttemptsRemaining "
            f"{row['context']['remoteAttemptsRemaining']} != ledger {remaining}",
        )

    # The failure rows and the delivery evidence agree on the selection that
    # led to each refresh.
    for row in rows:
        binding = row["action"]["deliveryBinding"]
        if binding is None or row["action"]["kind"] != "REFRESH_DELIVERY_BINDING":
            continue
        observation_revision = row["observation"]["deliveryBindingRevision"]
        _require(
            binding["expectedRevision"] == observation_revision,
            f"{row['failureId']}: refresh expectedRevision {binding['expectedRevision']} "
            f"!= observation {observation_revision}",
        )
        if observation_revision is not None:
            selection = replay.selections.get(row["attemptCorrelationId"])
            _require(
                selection is not None
                and selection["currentRevision"] == observation_revision,
                f"{row['failureId']}: refresh row does not match the selected revision "
                f"{observation_revision}",
            )


# ---------------------------------------------------------------------------
# Provider faults (DESIGN 6)
# ---------------------------------------------------------------------------


def _check_provider_faults(
    faults: Mapping[str, Any],
    case: Mapping[str, Any],
    rows: list[Mapping[str, Any]],
    replay: _DeliveryReplay,
    attempts: Mapping[str, Mapping[str, Any]],
    transport_to_attempt: Mapping[str, str],
) -> None:
    _require(
        faults["scenarioFamily"] == case["scenarioFamily"],
        f"provider faults scenarioFamily {faults['scenarioFamily']} != "
        f"case.json {case['scenarioFamily']}",
    )
    _require(
        faults["variant"] == case["variant"],
        f"provider faults variant {faults['variant']} != case.json {case['variant']}",
    )

    rows_by_attempt = {
        row["attemptCorrelationId"]: row
        for row in rows
        if row["attemptCorrelationId"] is not None
    }
    events = faults["events"]
    media: list[Mapping[str, Any]] = []
    refresh: list[Mapping[str, Any]] = []
    seen_faults: set[str] = set()
    seen_requests: set[str] = set()
    previous_ns = -1
    for index, event in enumerate(events):
        where = f"provider-faults.events[{index}]"
        _require(
            event["sequence"] == index + 1,
            f"{where}: sequence {event['sequence']} != {index + 1} (must be contiguous)",
        )
        _require(
            event["hostMonotonicNs"] >= previous_ns,
            f"{where}: hostMonotonicNs decreased (HOST_MEDIA_LAB_MONOTONIC)",
        )
        previous_ns = event["hostMonotonicNs"]
        _require(event["faultId"] not in seen_faults, f"{where}: duplicate faultId")
        seen_faults.add(event["faultId"])
        _require(
            event["requestCorrelationId"] not in seen_requests,
            f"{where}: duplicate requestCorrelationId",
        )
        seen_requests.add(event["requestCorrelationId"])
        if event["requestKind"] == "REFRESH":
            refresh.append(event)
        else:
            media.append(event)

    revision_generations: dict[str, set[str]] = {}
    generation_revisions: dict[str, set[str]] = {}
    for event in media:
        attempt = transport_to_attempt.get(event["requestCorrelationId"])
        _require(
            attempt is not None,
            f"provider fault {event['faultId']}: request {event['requestCorrelationId']} "
            "matches no physical attempt transport correlation",
        )
        row = rows_by_attempt.get(attempt)
        _require(
            row is not None,
            f"provider fault {event['faultId']}: media fault for attempt {attempt} "
            "without a failure row",
        )
        assert row is not None
        observation = row["observation"]
        _require(
            observation["type"] == "HTTP_RESPONSE",
            f"provider fault {event['faultId']}: attempt {attempt} was not an HTTP observation",
        )
        _require(
            observation["httpStatus"] == event["statusCode"],
            f"provider fault {event['faultId']}: statusCode {event['statusCode']} != "
            f"observation {observation['httpStatus']}",
        )
        expected_retry_after = {
            "rawKind": "ABSENT",
            "delaySeconds": None,
            "notBeforeUtcEpochMs": None,
        }
        if event["retryAfter"] is not None:
            expected_retry_after = {
                key: event["retryAfter"][key]
                for key in ("rawKind", "delaySeconds", "notBeforeUtcEpochMs")
            }
        actual_retry_after = {
            key: observation["retryAfter"][key]
            for key in ("rawKind", "delaySeconds", "notBeforeUtcEpochMs")
        }
        _require(
            actual_retry_after == expected_retry_after,
            f"provider fault {event['faultId']}: normalized Retry-After "
            f"{actual_retry_after} != fault {expected_retry_after}",
        )
        stale = event["faultKind"] == "BINDING_STALE"
        _require(
            (event["providerSignal"] == "BINDING_STALE_CONFIRMED") == stale,
            f"provider fault {event['faultId']}: {event['faultKind']} carries provider "
            f"signal {event['providerSignal']}",
        )
        if not stale:
            _require(
                event["providerSignal"] == "NONE",
                f"provider fault {event['faultId']}: {event['faultKind']} carries provider "
                f"signal {event['providerSignal']}",
            )
        _require(
            observation["providerSignal"] == event["providerSignal"],
            f"provider fault {event['faultId']}: client provider signal "
            f"{observation['providerSignal']} != fault {event['providerSignal']}",
        )
        selection = replay.selections.get(attempt)
        selected_revision = selection["currentRevision"] if selection is not None else None
        _require(
            event["bindingRevision"] == selected_revision,
            f"provider fault {event['faultId']}: bindingRevision {event['bindingRevision']} "
            f"!= selected {selected_revision}",
        )
        revision = event["bindingRevision"]
        generation = event["providerBindingGeneration"]
        if revision is not None and generation is not None:
            revision_generations.setdefault(revision, set()).add(generation)
            generation_revisions.setdefault(generation, set()).add(revision)

    for revision, generations in revision_generations.items():
        _require(
            len(generations) == 1,
            f"local revision {revision} maps to several provider generations "
            f"{sorted(generations)}",
        )
    for generation, revisions in generation_revisions.items():
        _require(
            len(revisions) == 1,
            f"provider generation {generation} maps to several local revisions "
            f"{sorted(revisions)}",
        )

    _require(
        len(refresh) == replay.started_count,
        f"{len(refresh)} provider refresh events != {replay.started_count} refresh "
        "operations",
    )
    provider_kinds = Counter(event["faultKind"] for event in refresh)
    client_kinds = Counter(
        operation.completion for operation in replay.operations.values()
    )
    for provider_kind, client_kind in (
        ("REFRESH_SUCCEEDED", "REFRESH_SUCCEEDED"),
        ("REFRESH_INCOMPATIBLE", "REFRESH_INCOMPATIBLE"),
        ("REFRESH_FAILED", "REFRESH_FAILED"),
    ):
        _require(
            provider_kinds[provider_kind] == client_kinds[client_kind],
            f"{provider_kinds[provider_kind]} provider {provider_kind} events != "
            f"{client_kinds[client_kind]} client {client_kind} events",
        )


# ---------------------------------------------------------------------------
# Origin trace (DESIGN 6, M2.md 13)
# ---------------------------------------------------------------------------


def _origin_identity(path: Any) -> str:
    return _PROVIDER_PATH_PREFIX.sub("", str(path))


def _check_origin(
    origin: list[Mapping[str, Any]],
    rows: list[Mapping[str, Any]],
    replay: _DeliveryReplay,
    attempts: Mapping[str, Mapping[str, Any]],
) -> int:
    data = [row for row in origin if row.get("plane") == _DATA_PLANE]
    media = [row for row in data if "/fixtures/" in str(row.get("path", ""))]
    refresh = [row for row in data if str(row.get("path", "")) == "/provider/refresh"]
    request_ids = [str(row.get("requestId")) for row in data]
    _require(len(request_ids) == len(set(request_ids)), "origin request IDs must be unique")
    media_ids = [str(row["requestId"]) for row in media]
    attempt_transports = sorted(
        str(info["transportCorrelationId"])
        for info in attempts.values()
        if info["transportCorrelationId"] is not None
    )
    _require(
        len(media_ids) == len(attempts),
        f"origin media requests {len(media_ids)} != physical attempts {len(attempts)}",
    )
    _require(
        sorted(media_ids) == attempt_transports,
        "origin media request ids do not equal the attempts' transport correlations",
    )
    _require(
        len(refresh) == replay.started_count,
        f"origin refresh requests {len(refresh)} != refresh operations "
        f"{replay.started_count}",
    )

    by_request = {str(row["requestId"]): row for row in media}
    for row in rows:
        if row["action"]["kind"] != "WAIT_PROVIDER":
            continue
        attempt = row["attemptCorrelationId"]
        info = attempts.get(attempt) if attempt is not None else None
        transport = info["transportCorrelationId"] if info is not None else None
        flagged = by_request.get(str(transport)) if transport is not None else None
        _require(
            flagged is not None,
            f"{row['failureId']}: provider wait has no origin media request",
        )
        assert flagged is not None
        wait = row["action"]["providerWait"]
        assert wait is not None
        identity = _origin_identity(flagged.get("path"))
        candidates = [
            candidate
            for candidate in media
            if _origin_identity(candidate.get("path")) == identity
            and candidate["handlerStartedAtMonotonicNs"]
            > flagged["completedAtMonotonicNs"]
        ]
        if not candidates:
            continue
        following = min(candidates, key=lambda candidate: candidate["handlerStartedAtMonotonicNs"])
        # The client cannot receive the throttled response before its handler
        # started, while the handler's completion timestamp is taken after the
        # response was already on the wire. Measuring from the handler start
        # is therefore a sound lower bound in HOST_MEDIA_LAB_MONOTONIC.
        elapsed_ns = (
            following["handlerStartedAtMonotonicNs"] - flagged["handlerStartedAtMonotonicNs"]
        )
        _require(
            elapsed_ns >= wait["waitMs"] * 1_000_000,
            f"{row['failureId']}: the next media request started {elapsed_ns} ns after "
            f"the throttled request, before the {wait['waitMs']} ms provider wait "
            "elapsed (HOST_MEDIA_LAB_MONOTONIC)",
        )
    return len(media) + len(refresh)


# ---------------------------------------------------------------------------
# Case expectations (DESIGN 4.6, M2-D host case format)
# ---------------------------------------------------------------------------


def _binding_revisions(
    remote_charges: list[Mapping[str, Any]],
    replay: _DeliveryReplay,
) -> list[str]:
    revisions = []
    for charge in remote_charges:
        selection = replay.selections.get(charge["attemptCorrelationId"])
        if selection is not None:
            revisions.append(selection["currentRevision"])
    return revisions


def _check_case_expectations(
    case: Mapping[str, Any],
    failures_doc: Mapping[str, Any],
    chains: Mapping[str, _Chain],
    replay: _DeliveryReplay,
    remote_charges: list[Mapping[str, Any]],
    rows: list[Mapping[str, Any]],
) -> list[str]:
    _require(
        case["policyId"] == failures_doc["policyId"],
        f"case policyId {case['policyId']} != run policy {failures_doc['policyId']}",
    )
    _require(
        case["expectedPhysicalAttempts"] == len(remote_charges),
        f"expected {case['expectedPhysicalAttempts']} physical attempts, got "
        f"{len(remote_charges)}",
    )
    terminal_counts = Counter(chain.terminal for chain in chains.values())
    _require(
        dict(case["expectedTerminals"]) == dict(terminal_counts),
        f"expected terminals {case['expectedTerminals']} != {dict(terminal_counts)}",
    )
    _require(
        case["expectedRefreshOperations"] == replay.started_count,
        f"expected {case['expectedRefreshOperations']} refresh operations, got "
        f"{replay.started_count}",
    )
    waits = [
        row["action"]["providerWait"]["waitMs"]
        for row in rows
        if row["action"]["kind"] == "WAIT_PROVIDER"
    ]
    _require(
        list(case["expectedProviderWaits"]) == waits,
        f"expected provider waits {case['expectedProviderWaits']} != {waits}",
    )
    revisions = _binding_revisions(remote_charges, replay)
    _require(
        list(case["expectedBindingRevisions"]) == revisions,
        f"expected binding revisions {case['expectedBindingRevisions']} != {revisions}",
    )
    removed = sorted(
        set(case["persistedExtentIdsBefore"]) - set(case["persistedExtentIdsAfter"])
    )
    _require(
        not removed,
        f"persisted extent(s) removed: {removed}",
    )
    return revisions


# ---------------------------------------------------------------------------
# Gates and summary
# ---------------------------------------------------------------------------


def _gate(status: str, checks: list[str]) -> dict[str, Any]:
    _require(checks, "every gate needs at least one check string")
    return {"status": status, "checks": checks}


def _acc07_gate(
    rows: list[Mapping[str, Any]],
    replay: _DeliveryReplay,
) -> dict[str, Any]:
    progress: list[str] = []
    for chain_id, selections in replay.selections_by_chain.items():
        if len({selection["currentRevision"] for selection in selections}) < 2:
            continue
        for operation in replay.operations.values():
            if operation.completion != "REFRESH_SUCCEEDED":
                continue
            if chain_id not in operation.participants:
                continue
            before = [
                selection
                for selection in selections
                if selection["sequence"] < operation.started_sequence
                and selection["currentRevision"] == operation.expected
            ]
            after = [
                selection
                for selection in selections
                if operation.completion_sequence is not None
                and selection["sequence"] > operation.completion_sequence
                and operation.installed_revision is not None
                and _revision_number(selection["currentRevision"])
                >= _revision_number(operation.installed_revision)
            ]
            if before and after:
                progress.append(
                    f"chain {chain_id}: {operation.expected} -> "
                    f"{operation.installed_revision} on one immutable FetchKey/extent "
                    "identity"
                )
                break
    if progress:
        return _gate("PASS", [
            progress[0],
            "one actual refresh operation per charge; no revision reused or derived "
            "from material; REMOTE_ATTEMPT never reset by a refresh",
        ])
    incompatible = [
        row
        for row in rows
        if row["action"]["deliveryBinding"] is not None
        and row["action"]["kind"] == "REFRESH_DELIVERY_BINDING"
        and row["action"]["deliveryBinding"]["result"] == "INCOMPATIBLE"
    ]
    if incompatible:
        return _gate("PASS", [
            f"{incompatible[0]['failureId']}: an incompatible refresh failed closed "
            "with no later owner",
            "immutable FetchKey/extent identity and the recovery budget are unchanged",
        ])
    return _gate("NOT_EXERCISED", [
        "no chain replaced a delivery binding across two revisions",
        "no incompatible refresh failed closed",
    ])


def _acc08_gate(
    case: Mapping[str, Any],
    rows: list[Mapping[str, Any]],
    provider_faults: Mapping[str, Any] | None,
    origin: list[Mapping[str, Any]] | None,
) -> dict[str, Any]:
    provider_rows = [row for row in rows if row["observation"]["plane"] == "PROVIDER"]
    if case["scenarioFamily"] not in PROVIDER_FAMILIES or not provider_rows:
        return _gate("NOT_EXERCISED", [
            "no deterministic N8/N9/N10 provider scenario exercised",
        ])
    checks = [
        f"{case['scenarioFamily']}/{case['variant'] or '-'} deterministic provider "
        f"scenario with {len(provider_rows)} provider-plane failure rows"
    ]
    if provider_faults is not None:
        checks.append(
            "every provider media fault matches one physical attempt and every "
            "refresh event matches one actual refresh operation"
        )
    if origin is not None:
        checks.append(
            "origin media requests equal physical attempts and origin refresh "
            "requests equal refresh operations"
        )
    checks.append("Retry-After waits and provider signals independently re-derived")
    return _gate("PASS", checks)


def _summarize(
    case: Mapping[str, Any],
    failures_doc: Mapping[str, Any],
    chains: Mapping[str, _Chain],
    replay: _DeliveryReplay,
    rows: list[Mapping[str, Any]],
    remote_charges: list[Mapping[str, Any]],
    refresh_charges: list[Mapping[str, Any]],
    binding_revisions: list[str],
    recovery: Mapping[str, Any],
    origin_request_count: int | None,
    provider_faults: Mapping[str, Any] | None,
    origin: list[Mapping[str, Any]] | None,
) -> dict[str, Any]:
    provider_rows = [row for row in rows if row["observation"]["plane"] == "PROVIDER"]
    waits = [
        row["action"]["providerWait"]["waitMs"]
        for row in rows
        if row["action"]["kind"] == "WAIT_PROVIDER"
    ]
    acc05 = _gate(str(recovery["gates"]["M2-ACC-05"]["status"]), [
        *recovery["gates"]["M2-ACC-05"]["checks"],
        f"{len(provider_rows)} provider-plane failure rows: normalized Retry-After, "
        "provider signal and delivery binding revision re-derived",
    ])
    acc06 = _gate(str(recovery["gates"]["M2-ACC-06"]["status"]), [
        *recovery["gates"]["M2-ACC-06"]["checks"],
        f"{len(remote_charges)} REMOTE_ATTEMPT charges and "
        f"{len(refresh_charges)} refresh charges on {len(chains)} chain ledger(s); "
        "a refresh never reset REMOTE_ATTEMPT",
    ])
    summary = {
        "schemaVersion": 1,
        "caseId": case["caseId"],
        "runId": failures_doc["runId"],
        "sessionId": failures_doc["sessionId"],
        "policyId": failures_doc["policyId"],
        "status": "PASS",
        "evidenceSource": case["evidenceSource"],
        "scenarioFamily": case["scenarioFamily"],
        "variant": case["variant"],
        "chainCount": len(chains),
        "physicalAttemptCount": len(remote_charges),
        "remoteAttemptChargeCount": len(remote_charges),
        "originRequestCount": origin_request_count,
        "refreshOperationCount": replay.started_count,
        "refreshChargeCount": len(refresh_charges),
        "joinedRefreshCount": replay.joined_count,
        "alreadyAdvancedCount": replay.already_advanced_count,
        "providerWaitCount": len(waits),
        "bindingRevisions": binding_revisions,
        "gates": {
            "M2-ACC-05": acc05,
            "M2-ACC-06": acc06,
            "M2-ACC-07": _acc07_gate(rows, replay),
            "M2-ACC-08": _acc08_gate(case, rows, provider_faults, origin),
        },
        "limitations": list(LIMITATIONS_V2),
    }
    return summary


# ---------------------------------------------------------------------------
# Verification entry point
# ---------------------------------------------------------------------------


def verify_provider(
    failures_doc: Mapping[str, Any],
    budget_doc: Mapping[str, Any],
    delivery_doc: Mapping[str, Any],
    fetch_rows: list[Mapping[str, Any]],
    case: Mapping[str, Any],
    *,
    provider_faults: Mapping[str, Any] | None = None,
    origin: list[Mapping[str, Any]] | None = None,
) -> dict[str, Any]:
    """Verify one M2-D provider recovery run; raise ProviderOracleError on any
    contract violation and return the validated summary document."""

    _check_declared_clock_relations()
    fetch = [dict(row) for row in fetch_rows]
    _validate_documents(failures_doc, budget_doc, delivery_doc, fetch, provider_faults)
    _check_case(case)
    _require(
        failures_doc["runId"] == budget_doc["runId"] == delivery_doc["runId"],
        "failure, budget and delivery evidence belong to different runs",
    )
    _require(
        failures_doc["sessionId"] == budget_doc["sessionId"] == delivery_doc["sessionId"],
        "failure, budget and delivery evidence belong to different sessions",
    )
    _require(
        failures_doc["policyId"] == budget_doc["policyId"],
        "failure and budget evidence name different policies",
    )
    _check_input_privacy(
        failures=failures_doc,
        budget=budget_doc,
        delivery=delivery_doc,
        fetch=fetch,
        case=case,
        provider_faults=provider_faults,
        origin=origin,
    )

    try:
        recovery = verify_recovery(failures_doc, budget_doc, fetch, origin=None, case=None)
    except RecoveryOracleError as error:
        raise ProviderOracleError(f"recovery lineage: {error}") from error

    chains = _budget_chains(budget_doc)
    replay = _replay_delivery(delivery_doc, chains)
    rows = failures_doc["failures"]
    remote_charges = _remote_charges(chains)
    refresh_charges = _refresh_charges(chains)

    _check_provider_semantics(rows, chains, replay)
    _check_joins(failures_doc, case, chains, replay)

    attempts = _physical_attempts(fetch)
    transport_to_attempt: dict[str, str] = {}
    for attempt, info in attempts.items():
        transport = info["transportCorrelationId"]
        if transport is None:
            continue
        _require(
            transport not in transport_to_attempt,
            f"origin request {transport} is attributed to several physical attempts",
        )
        transport_to_attempt[transport] = attempt

    if provider_faults is not None:
        _check_provider_faults(
            provider_faults,
            case,
            rows,
            replay,
            attempts,
            transport_to_attempt,
        )
    origin_request_count = None
    if origin is not None:
        _require(not origin or any(row.get("plane") == _DATA_PLANE for row in origin),
                 "origin trace contains no data-plane request")
        origin_request_count = _check_origin(origin, rows, replay, attempts)

    binding_revisions = _check_case_expectations(
        case, failures_doc, chains, replay, remote_charges, rows
    )

    summary = _summarize(
        case,
        failures_doc,
        chains,
        replay,
        rows,
        remote_charges,
        refresh_charges,
        binding_revisions,
        recovery,
        origin_request_count,
        provider_faults,
        origin,
    )
    _validate(SUMMARY_SCHEMA, summary, "provider-verification-summary-v1")
    _check_input_privacy(**{"provider-verification-summary": summary})
    return summary


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def _read_json(path: pathlib.Path) -> Any:
    _require(path.is_file(), f"evidence file does not exist: {path}")
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise ProviderOracleError(f"{path}: invalid JSON") from error


def read_jsonl(path: pathlib.Path) -> list[dict[str, Any]]:
    _require(path.is_file(), f"evidence file does not exist: {path}")
    rows = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError as error:
            raise ProviderOracleError(f"{path}:{number}: invalid JSON") from error
        _require(isinstance(row, Mapping), f"{path}:{number}: row must be an object")
        rows.append(dict(row))
    return rows


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    verify = commands.add_parser(
        "verify", help="verify one run's M2-D provider recovery evidence"
    )
    verify.add_argument("--failures", required=True, type=pathlib.Path)
    verify.add_argument("--budget", required=True, type=pathlib.Path)
    verify.add_argument("--delivery", required=True, type=pathlib.Path)
    verify.add_argument("--fetch", required=True, type=pathlib.Path)
    verify.add_argument("--case", required=True, type=pathlib.Path)
    verify.add_argument(
        "--provider-faults", type=pathlib.Path, help="provider-fault-events-v1"
    )
    verify.add_argument("--origin", type=pathlib.Path, help="Media Lab requests.jsonl")
    verify.add_argument("--output", required=True, type=pathlib.Path)
    verify.add_argument(
        "--require-gate",
        action="append",
        default=[],
        choices=GATE_IDS,
        help="gate that must be PASS (repeatable)",
    )
    args = parser.parse_args(argv)

    try:
        summary = verify_provider(
            _read_json(args.failures),
            _read_json(args.budget),
            _read_json(args.delivery),
            read_jsonl(args.fetch),
            _read_json(args.case),
            provider_faults=_read_json(args.provider_faults) if args.provider_faults else None,
            origin=read_jsonl(args.origin) if args.origin else None,
        )
        for gate in args.require_gate:
            _require(
                summary["gates"][gate]["status"] == "PASS",
                f"{gate} was required but is {summary['gates'][gate]['status']}",
            )
    except ProviderOracleError as error:
        print(f"M2-D provider verification failed: {error}", file=sys.stderr)
        return 1
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    gates = " ".join(f"{gate}={summary['gates'][gate]['status']}" for gate in GATE_IDS)
    print(
        f"M2-D provider PASS: case={summary['caseId']} "
        f"attempts={summary['physicalAttemptCount']} "
        f"refreshOps={summary['refreshOperationCount']} {gates}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
