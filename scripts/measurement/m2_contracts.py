"""Host-side reference oracle for the FROZEN M2 resilience contracts.

Normative source: `.work/milestones/M2.md`.

This module checks only contract semantics that M2-A freezes. It is not a
simulation of the production route monitor, FailureClassifier,
RecoveryBudget or DeliveryBinding runtime; owning slices (M2-B..M2-H) provide
those producers and their own verifiers. Every check fails closed by raising
`M2ContractError`.
"""

from __future__ import annotations

import datetime as _datetime
import hashlib
import json
import re
from typing import Any, Iterable, Mapping


class M2ContractError(ValueError):
    pass


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise M2ContractError(message)


# ---------------------------------------------------------------------------
# Fault planes and scenario identity (M2.md sections 6-7)
# ---------------------------------------------------------------------------

PLANES = ("DELIVERY", "TRANSPORT", "NETWORK", "PROVIDER", "ROUTE", "STORAGE")

PLANE_FAULT_FIELDS = {plane: f"{plane.lower()}Faults" for plane in PLANES}

# Family shorthand -> permitted primary planes (M2.md section 6.1).
FAMILY_PLANES = {
    "N0": {"DELIVERY"},
    "N1": {"DELIVERY"},
    "N2": {"NETWORK"},
    "N3": {"DELIVERY", "NETWORK"},
    "N4": {"DELIVERY"},
    "N5": {"NETWORK"},
    "N6": {"TRANSPORT", "ROUTE"},
    "N7": {"ROUTE"},
    "N8": {"PROVIDER"},
    "N9": {"PROVIDER"},
    "N10": {"PROVIDER"},
    "N11": {"STORAGE"},
}

CONTROL_VARIANT = "CONTROL"


def _reject_non_integer_numbers(value: Any, path: str) -> None:
    if isinstance(value, float):
        raise M2ContractError(
            f"{path}: non-integer number {value!r} is not canonical; "
            "use integer units"
        )
    if isinstance(value, dict):
        for key, item in value.items():
            _require(isinstance(key, str), f"{path}: object keys must be strings")
            _reject_non_integer_numbers(item, f"{path}.{key}")
    elif isinstance(value, list):
        for index, item in enumerate(value):
            _reject_non_integer_numbers(item, f"{path}[{index}]")


def _canonical_form(scenario: Mapping[str, Any]) -> dict[str, Any]:
    _require(isinstance(scenario, Mapping), "scenario must be an object")
    _reject_non_integer_numbers(scenario, "$")
    form = json.loads(json.dumps(scenario))
    for field in PLANE_FAULT_FIELDS.values():
        faults = form.get(field)
        if isinstance(faults, list):
            _require(
                all(isinstance(f, dict) and isinstance(f.get("faultId"), str)
                    for f in faults),
                f"{field}: every fault needs a string faultId",
            )
            form[field] = sorted(faults, key=lambda f: f["faultId"])
    return form


def canonicalize_scenario(scenario: Mapping[str, Any]) -> bytes:
    """Deterministic canonical bytes of a resolved scenario.

    UTF-8 JSON, sorted keys, no insignificant whitespace, per-plane fault lists
    ordered by faultId, non-integer numbers rejected.
    """

    return json.dumps(
        _canonical_form(scenario),
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
        allow_nan=False,
    ).encode("utf-8")


def scenario_sha256(scenario: Mapping[str, Any]) -> str:
    return hashlib.sha256(canonicalize_scenario(scenario)).hexdigest()


def iter_scenario_faults(scenario: Mapping[str, Any]):
    for plane, field in PLANE_FAULT_FIELDS.items():
        for fault in scenario.get(field) or []:
            yield plane, fault


def scenario_claims_route_evidence(scenario: Mapping[str, Any]) -> bool:
    return scenario.get("primaryPlane") == "ROUTE" or bool(
        scenario.get(PLANE_FAULT_FIELDS["ROUTE"])
    )


def validate_scenario_semantics(scenario: Mapping[str, Any]) -> None:
    """Cross-field rules of m2-scenario-v1 that JSON Schema cannot express."""

    _require(scenario.get("schemaVersion") == 1, "unsupported scenario version")
    _canonical_form(scenario)

    family = scenario.get("scenarioFamily")
    primary = scenario.get("primaryPlane")
    variant = scenario.get("variant")
    _require(family in FAMILY_PLANES, f"unknown scenario family {family!r}")
    _require(primary in PLANES, f"unknown primaryPlane {primary!r}")
    _require(isinstance(variant, str) and variant != "", "variant is required")
    _require(
        primary in FAMILY_PLANES[family],
        f"{family} does not permit primaryPlane {primary}; permitted: "
        f"{sorted(FAMILY_PLANES[family])}",
    )

    owners: dict[str, str] = {}
    stochastic = False
    for plane, field in PLANE_FAULT_FIELDS.items():
        faults = scenario.get(field)
        _require(isinstance(faults, list), f"{field} must be an array")
        for fault in faults:
            fault_id = fault.get("faultId")
            declared = fault.get("plane")
            _require(
                declared == plane,
                f"fault {fault_id!r} in {field} declares plane {declared!r}",
            )
            _require(
                fault_id not in owners,
                f"fault {fault_id!r} claims two primary planes: "
                f"{owners.get(fault_id)} and {plane}",
            )
            owners[fault_id] = plane
            stochastic = stochastic or fault.get("stochastic") is True

    if variant == CONTROL_VARIANT:
        _require(not owners, "CONTROL scenario must not inject faults")
    else:
        _require(
            bool(scenario.get(PLANE_FAULT_FIELDS[primary])),
            f"primaryPlane {primary} has no fault in "
            f"{PLANE_FAULT_FIELDS[primary]}",
        )

    seed = scenario.get("randomSeed")
    if stochastic:
        _require(
            isinstance(seed, int) and not isinstance(seed, bool) and seed >= 0,
            "stochastic fault requires a persisted integer randomSeed",
        )

    if scenario_claims_route_evidence(scenario):
        _require(
            scenario.get("requiresActualDefaultNetwork") is True,
            "route/VPN evidence requires requiresActualDefaultNetwork=true",
        )


def validate_run_manifest_semantics(
    manifest: Mapping[str, Any],
    scenario: Mapping[str, Any],
) -> None:
    """Bind an m2-run-manifest-v1 to its resolved m2-scenario-v1."""

    validate_scenario_semantics(scenario)
    bound = manifest.get("scenario") or {}
    _require(
        bound.get("hash") == scenario_sha256(scenario),
        "run manifest scenario.hash does not match canonical scenario",
    )
    _require(bound.get("family") == scenario["scenarioFamily"], "family mismatch")
    _require(bound.get("variant") == scenario["variant"], "variant mismatch")
    _require(
        bound.get("primaryPlane") == scenario["primaryPlane"],
        "primaryPlane mismatch",
    )

    if scenario.get("requiresActualDefaultNetwork"):
        _require(
            manifest.get("mediaPath") == "ANDROID_DEFAULT_NETWORK",
            "scenario requires Android's actual default network; "
            f"mediaPath={manifest.get('mediaPath')!r} cannot prove route behavior",
        )

    harness_planes: list[str] = [
        harness.get("plane") for harness in manifest.get("faultHarnesses") or []
    ]
    _require(
        len(harness_planes) == len(set(harness_planes)),
        "each fault plane has exactly one owning harness per run",
    )
    faulted = {plane for plane, _ in iter_scenario_faults(scenario)}
    missing = sorted(faulted - set(harness_planes))
    _require(not missing, f"faulted plane(s) without owning harness: {missing}")

    domains = manifest.get("clockDomains") or []
    _require(len(domains) == len(set(domains)), "duplicate clock domain")
    scan_evidence_privacy(manifest)
    scan_evidence_privacy(scenario)


# ---------------------------------------------------------------------------
# Route observation and privacy (M2.md section 8)
# ---------------------------------------------------------------------------

TRI_STATE = ("TRUE", "FALSE", "UNKNOWN")

# DefaultRouteState (M2.md section 8.2). LOST is an event, not a state.
ROUTE_STATES = ("INITIALIZING", "UNAVAILABLE", "AVAILABLE")

ROUTE_CAPABILITIES = (
    "internet",
    "validated",
    "vpn",
    "metered",
    "restricted",
    "blocked",
    "suspended",
)

# Lowest Android API on which the platform can report each field (M2.md 8.1).
# Below the floor the value is UNKNOWN; absence of a callback is never FALSE.
CAPABILITY_API_FLOORS = {
    "internet": 21,
    "metered": 21,
    "vpn": 21,
    "restricted": 21,
    "validated": 23,
    "suspended": 28,
    "blocked": 29,
}

SESSION_ROUTE_GUARDS = (
    "UNRESOLVED",
    "SYSTEM_DEFAULT_ALLOWED",
    "VPN_CONTINUITY_REQUIRED",
)

EXTERNAL_FETCH_DECISIONS = ("ALLOW", "PAUSE")

ALLOW_REASONS = ("ROUTE_READY", "EXPLICIT_DIRECT_OVERRIDE")
PAUSE_REASONS = (
    "INITIALIZING",
    "NO_USABLE_DEFAULT",
    "CAPABILITIES_PENDING",
    "SESSION_ROUTE_UNRESOLVED",
    "VPN_CONTINUITY_REQUIRED",
    "NETWORK_BLOCKED",
    "NETWORK_SUSPENDED",
    "NETWORK_RESTRICTED",
    "NO_INTERNET_CAPABILITY",
)


def _tri(value: Any, name: str) -> str:
    _require(value in TRI_STATE, f"{name} must be TRUE/FALSE/UNKNOWN, got {value!r}")
    return value


def _route_state(route: Mapping[str, Any]) -> str:
    state = route.get("state")
    _require(state in ROUTE_STATES, f"unknown route state {state!r}")
    return state


def _capability(route: Mapping[str, Any], capability: str) -> str:
    _require(
        capability in ROUTE_CAPABILITIES,
        f"unknown route capability {capability!r}",
    )
    return _tri(route.get(capability, "UNKNOWN"), capability)


def known_true(route: Mapping[str, Any], capability: str) -> bool:
    """True only for an explicitly observed TRUE capability; UNKNOWN is not TRUE."""

    state = _route_state(route)
    value = _capability(route, capability)
    return state == "AVAILABLE" and value == "TRUE"


def check_route_capability_claim(
    route: Mapping[str, Any],
    capability: str,
    claimed: bool,
) -> None:
    """Reject an interpretation that claims a capability not actually observed."""

    if claimed:
        _require(
            known_true(route, capability),
            f"{capability}={route.get(capability)} in state {route.get('state')} "
            "must not be interpreted as TRUE",
        )


def check_capability_api_floor(
    android_api: int,
    capability: str,
    value: Any,
) -> None:
    """A capability below its platform API floor can only be UNKNOWN."""

    _require(
        isinstance(android_api, int) and not isinstance(android_api, bool)
        and android_api >= 23,
        f"androidApi must be an integer >= 23, got {android_api!r}",
    )
    _require(
        capability in CAPABILITY_API_FLOORS,
        f"unknown route capability {capability!r}",
    )
    _tri(value, capability)
    if android_api < CAPABILITY_API_FLOORS[capability]:
        _require(
            value == "UNKNOWN",
            f"API {android_api} cannot observe {capability} "
            f"(floor API {CAPABILITY_API_FLOORS[capability]}); got {value}",
        )


def _vpn_known(route: Mapping[str, Any]) -> str:
    """Observed vpn value usable for guard resolution, else UNKNOWN."""

    if _route_state(route) != "AVAILABLE" or route.get("capabilitiesReceived") is not True:
        return "UNKNOWN"
    return _capability(route, "vpn")


def resolve_session_route_guard(guard: str, route: Mapping[str, Any]) -> str:
    """SessionRouteGuard resolution (M2.md section 8.6).

    Only UNRESOLVED can change, and only from an observation with known vpn.
    A resolved guard is sticky: SYSTEM_DEFAULT_ALLOWED never escalates because a
    VPN appeared later; VPN_CONTINUITY_REQUIRED never clears on its own.
    """

    _require(guard in SESSION_ROUTE_GUARDS, f"unknown session route guard {guard!r}")
    if guard != "UNRESOLVED":
        return guard
    vpn = _vpn_known(route)
    if vpn == "TRUE":
        return "VPN_CONTINUITY_REQUIRED"
    if vpn == "FALSE":
        return "SYSTEM_DEFAULT_ALLOWED"
    return "UNRESOLVED"


def evaluate_external_fetch_route(
    guard: str,
    route: Mapping[str, Any],
    *,
    explicit_direct_override: bool = False,
) -> tuple[str, str, str]:
    """Reference ExternalFetchRouteDecision: (guardAfter, decision, reason).

    Evaluation order is normative (M2.md 8.6). The explicit direct override
    lifts only the VPN-continuity requirement. `metered` and `validated` never
    pause by themselves.
    """

    _require(
        isinstance(explicit_direct_override, bool),
        "explicitDirectOverride must be boolean",
    )
    state = _route_state(route)
    guard_after = resolve_session_route_guard(guard, route)
    if state == "INITIALIZING":
        return guard_after, "PAUSE", "INITIALIZING"
    if state == "UNAVAILABLE":
        return guard_after, "PAUSE", "NO_USABLE_DEFAULT"
    if route.get("capabilitiesReceived") is not True:
        return guard_after, "PAUSE", "CAPABILITIES_PENDING"
    if guard_after == "UNRESOLVED":
        return guard_after, "PAUSE", "SESSION_ROUTE_UNRESOLVED"
    override_used = False
    if guard_after == "VPN_CONTINUITY_REQUIRED" and _capability(route, "vpn") != "TRUE":
        if not explicit_direct_override:
            return guard_after, "PAUSE", "VPN_CONTINUITY_REQUIRED"
        override_used = True
    if _capability(route, "blocked") == "TRUE":
        return guard_after, "PAUSE", "NETWORK_BLOCKED"
    if _capability(route, "suspended") == "TRUE":
        return guard_after, "PAUSE", "NETWORK_SUSPENDED"
    if _capability(route, "restricted") == "TRUE":
        return guard_after, "PAUSE", "NETWORK_RESTRICTED"
    if _capability(route, "internet") == "FALSE":
        return guard_after, "PAUSE", "NO_INTERNET_CAPABILITY"
    return (
        guard_after,
        "ALLOW",
        "EXPLICIT_DIRECT_OVERRIDE" if override_used else "ROUTE_READY",
    )


def validate_route_privacy_transition(
    *,
    guard: str,
    new_route: Mapping[str, Any],
    external_fetch_decision: str,
    explicit_direct_override: bool = False,
) -> str:
    """Pure oracle for the FROZEN VPN privacy invariant (PRODUCT, M2.md 8/F-03).

    `guard` is the session route guard before this evaluation. Returns
    `ELIGIBLE` when an ALLOW decision is consistent with the contract, or
    `PAUSED` when external fetching is (correctly) paused. Pausing never
    violates privacy. Raises when an ALLOW would violate the contract.
    Persisted/local playback is outside this check: it always continues.
    """

    _require(
        external_fetch_decision in EXTERNAL_FETCH_DECISIONS,
        f"unknown external fetch decision {external_fetch_decision!r}",
    )
    _, expected, reason = evaluate_external_fetch_route(
        guard,
        new_route,
        explicit_direct_override=explicit_direct_override,
    )
    if external_fetch_decision == "PAUSE":
        return "PAUSED"
    _require(
        expected == "ALLOW",
        f"external fetch must pause ({reason}) for guard {guard} on route "
        f"state={new_route.get('state')} vpn={new_route.get('vpn')}",
    )
    return "ELIGIBLE"


def _extent_identity_map(
    extents: Iterable[Mapping[str, Any]],
) -> dict[str, str]:
    identities: dict[str, str] = {}
    for extent in extents:
        extent_id = extent.get("extentId")
        _require(isinstance(extent_id, str) and extent_id, "extentId required")
        _require(extent_id not in identities, f"duplicate extent {extent_id}")
        identities[extent_id] = json.dumps(extent, sort_keys=True)
    return identities


PERSISTED_IDENTITY_TRANSITIONS = (
    "ROUTE_EPOCH_CHANGED",
    "ROUTE_CAPABILITY_CHANGED",
    "VPN_LOST",
    "VPN_APPEARED",
    "DELIVERY_REBOUND",
    "TRANSPORT_BACKEND_CHANGED",
    "PROVIDER_REJECTED",
)


def validate_persisted_identity_invariance(
    before: Iterable[Mapping[str, Any]],
    after: Iterable[Mapping[str, Any]],
    transition: str,
) -> None:
    """M2-I02: a route/binding/provider transition never mutates or removes
    valid persisted extent identity. New publications may be added."""

    _require(
        transition in PERSISTED_IDENTITY_TRANSITIONS,
        f"unknown transition {transition!r}",
    )
    old = _extent_identity_map(before)
    new = _extent_identity_map(after)
    for extent_id, identity in old.items():
        _require(
            extent_id in new,
            f"{transition} removed persisted extent {extent_id}",
        )
        _require(
            new[extent_id] == identity,
            f"{transition} mutated persisted extent identity {extent_id}",
        )


# ---------------------------------------------------------------------------
# Observation / classification / decision (M2.md sections 9-10)
# ---------------------------------------------------------------------------

OBSERVATION_PLANES = {
    "SOCKET_TIMEOUT": "TRANSPORT",
    "CONNECTION_RESET": "TRANSPORT",
    "EOF_BEFORE_EXPECTED_RANGE": "TRANSPORT",
    "HTTP_STATUS": "PROVIDER",
    "ROUTE_EPOCH_CHANGED": "ROUTE",
    "VALIDATED_CAPABILITY_LOST": "ROUTE",
    "DEFAULT_ROUTE_UNAVAILABLE": "ROUTE",
    "STORAGE_ENOSPC": "STORAGE",
    "STORAGE_IO": "STORAGE",
}

# PROVISIONAL vocabulary (M2-C owns the final enum).
CLASSIFICATIONS = (
    "TRANSIENT_TRANSPORT",
    "PROVIDER_RATE_LIMITED",
    "PROVIDER_REJECTED",
    "DELIVERY_BINDING_STALE",
    "ROUTE_UNAVAILABLE",
    "ROUTE_POLICY_BLOCKED",
    "RANGE_REJECTED",
    "STORAGE_FAILURE",
    "UNKNOWN",
)

DECISIONS = (
    "RETRY",
    "WAIT",
    "WAIT_FOR_ROUTE",
    "REFRESH_DELIVERY_BINDING",
    "RERESOLVE",
    "FAIL",
)

_PROVIDER_CLASSES = {
    "PROVIDER_RATE_LIMITED",
    "PROVIDER_REJECTED",
    "DELIVERY_BINDING_STALE",
}

_BINDING_DECISIONS = {"REFRESH_DELIVERY_BINDING", "RERESOLVE"}

_DELAY_SECONDS = re.compile(r"^[0-9]+$")
_DAYS = ("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
_LONG_DAYS = (
    "Monday",
    "Tuesday",
    "Wednesday",
    "Thursday",
    "Friday",
    "Saturday",
    "Sunday",
)
_MONTHS = (
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)
_IMF_FIXDATE = re.compile(
    r"^(Mon|Tue|Wed|Thu|Fri|Sat|Sun), ([0-9]{2}) ([A-Z][a-z]{2}) ([0-9]{4}) "
    r"([0-9]{2}):([0-9]{2}):([0-9]{2}) GMT$"
)
_RFC850 = re.compile(
    r"^(Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday), "
    r"([0-9]{2})-([A-Z][a-z]{2})-([0-9]{2}) "
    r"([0-9]{2}):([0-9]{2}):([0-9]{2}) GMT$"
)
_ASCTIME = re.compile(
    r"^(Mon|Tue|Wed|Thu|Fri|Sat|Sun) ([A-Z][a-z]{2}) ([ 0-9][0-9]) "
    r"([0-9]{2}):([0-9]{2}):([0-9]{2}) ([0-9]{4})$"
)


def _http_date(
    weekday: str,
    day: str,
    month: str,
    year: int,
    hour: str,
    minute: str,
    second: str,
) -> _datetime.datetime | None:
    if month not in _MONTHS:
        return None
    try:
        parsed = _datetime.datetime(
            year,
            _MONTHS.index(month) + 1,
            int(day),
            int(hour),
            int(minute),
            int(second),
            tzinfo=_datetime.timezone.utc,
        )
    except ValueError:
        return None
    if weekday in _DAYS:
        expected = _DAYS[parsed.weekday()]
    else:
        expected = _LONG_DAYS[parsed.weekday()]
    return parsed if weekday == expected else None


def _rfc850_year(two_digits: int, reference_year: int) -> int:
    # RFC 9110 5.6.7: a two-digit year that appears to be more than 50 years in
    # the future is interpreted as the most recent past year with those digits.
    century = reference_year - reference_year % 100
    year = century + two_digits
    if year > reference_year + 50:
        year -= 100
    return year


def parse_retry_after(
    raw: str | None,
    *,
    reference_year: int = 2026,
) -> dict[str, Any]:
    """Normalize a Retry-After observation (RFC 9110 10.2.3).

    delay-seconds is a duration with no clock domain; HTTP-date is a
    PROVIDER_WALL_CLOCK instant. A malformed value is recorded, never
    reinterpreted into another plane or classification.
    """

    if raw is None:
        return {"source": "HTTP_HEADER", "rawKind": "ABSENT"}
    value = raw.strip()
    if _DELAY_SECONDS.match(value):
        return {
            "source": "HTTP_HEADER",
            "rawKind": "DELAY_SECONDS",
            "delaySeconds": int(value),
        }

    parsed = None
    match = _IMF_FIXDATE.match(value)
    if match:
        weekday, day, month, year, hour, minute, second = match.groups()
        parsed = _http_date(weekday, day, month, int(year), hour, minute, second)
    match = None if parsed else _RFC850.match(value)
    if match:
        weekday, day, month, year, hour, minute, second = match.groups()
        parsed = _http_date(
            weekday, day, month,
            _rfc850_year(int(year), reference_year),
            hour, minute, second,
        )
    match = None if parsed else _ASCTIME.match(value)
    if match:
        weekday, month, day, hour, minute, second, year = match.groups()
        parsed = _http_date(
            weekday, day.strip().zfill(2), month, int(year), hour, minute, second
        )

    if parsed is not None:
        return {
            "source": "HTTP_HEADER",
            "rawKind": "HTTP_DATE",
            "notBeforeUtc": parsed.strftime("%Y-%m-%dT%H:%M:%SZ"),
            "clockDomain": "PROVIDER_WALL_CLOCK",
        }
    return {"source": "HTTP_HEADER", "rawKind": "MALFORMED"}


def classify_http_contract_case(
    status: int,
    *,
    retry_after: str | None = None,
    provider_evidence: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    """Frozen HTTP semantic anchors only (M2.md section 10).

    This is not the production classifier. Only 403 and 429 are anchored.
    """

    if status == 403:
        return {
            "plane": "PROVIDER",
            "classification": "PROVIDER_REJECTED",
            # M2-A never auto-selects stale binding; with explicit provider
            # evidence a later M2-D policy MAY classify it so.
            "staleBindingPermittedByProviderPolicy": provider_evidence is not None,
        }
    if status == 429:
        return {
            "plane": "PROVIDER",
            "classification": "PROVIDER_RATE_LIMITED",
            "retryAfter": parse_retry_after(retry_after),
        }
    raise M2ContractError(f"HTTP {status} is outside the frozen M2-A anchors")


def validate_failure_record(record: Mapping[str, Any]) -> None:
    """Observation, classification and decision are separate and consistent
    with the frozen interpretation anchors (M2-ACC-05)."""

    for layer in ("observation", "classification", "decision"):
        _require(isinstance(record.get(layer), Mapping), f"missing {layer} layer")
    failure_id = record.get("failureId")
    _require(isinstance(failure_id, str) and failure_id, "failureId required")

    observation = record["observation"]
    classification = record["classification"]
    decision = record["decision"]

    kind = observation.get("kind")
    _require(kind in OBSERVATION_PLANES, f"unknown observation kind {kind!r}")
    plane = OBSERVATION_PLANES[kind]
    _require(
        observation.get("plane", plane) == plane,
        f"observation {kind} belongs to plane {plane}, "
        f"not {observation.get('plane')}",
    )
    for forbidden in ("class", "action"):
        _require(forbidden not in observation, f"observation carries {forbidden}")
    _require("action" not in classification, "classification carries an action")

    klass = classification.get("class")
    action = decision.get("action")
    _require(klass in CLASSIFICATIONS, f"unknown classification {klass!r}")
    _require(action in DECISIONS, f"unknown decision {action!r}")

    provider_evidence = record.get("providerEvidence")

    if plane == "STORAGE":
        _require(
            klass == "STORAGE_FAILURE",
            f"storage observation {kind} classified as {klass}",
        )
        _require(
            action not in _BINDING_DECISIONS,
            f"storage failure must not trigger {action}",
        )
    else:
        _require(
            klass != "STORAGE_FAILURE",
            f"{plane} observation {kind} classified as storage failure",
        )

    if plane in ("TRANSPORT", "ROUTE"):
        _require(
            klass not in _PROVIDER_CLASSES,
            f"{plane} observation {kind} classified as provider {klass}",
        )

    if kind == "HTTP_STATUS":
        status = observation.get("status")
        if status == 429:
            _require(
                klass == "PROVIDER_RATE_LIMITED",
                f"HTTP 429 must classify as PROVIDER_RATE_LIMITED, not {klass}",
            )
        if status == 403 and provider_evidence is None:
            _require(
                klass == "PROVIDER_REJECTED",
                f"bare HTTP 403 must classify as PROVIDER_REJECTED, not {klass}",
            )
            _require(
                action not in _BINDING_DECISIONS,
                f"bare HTTP 403 must not automatically trigger {action}",
            )
        _require(
            klass not in ("TRANSIENT_TRANSPORT", "ROUTE_UNAVAILABLE",
                          "ROUTE_POLICY_BLOCKED"),
            f"received HTTP {status} classified as {klass}",
        )

    _require(
        not decision.get("invalidatesPersistedCoverage", False),
        "no recovery decision may invalidate valid persisted coverage",
    )


# ---------------------------------------------------------------------------
# RecoveryChain budget lineage (M2.md section 11)
# ---------------------------------------------------------------------------

LEDGER_EVENT_KINDS = ("CHAIN_STARTED", "CHARGE", "LAYER_TRANSITION", "TERMINAL")

LAYER_TRANSITIONS = (
    "SHARED_FETCH_REPLACED",
    "MEDIA3_REOPEN",
    "ROUTE_EPOCH_CHANGED",
    "ROUTE_PAUSED",
    "ROUTE_RESUMED",
    "DELIVERY_REBOUND",
    "TRANSPORT_RECONNECT",
)

CHAIN_TERMINALS = (
    "SUCCESS",
    "TERMINAL_FAILURE",
    "NO_REMAINING_DEMAND",
    "SESSION_TERMINATION",
)


def _as_vector(value: Any, path: str) -> dict[str, int]:
    """A budget may be scalar or vector-shaped (PROVISIONAL); normalize."""

    if isinstance(value, int) and not isinstance(value, bool):
        vector = {"": value}
    elif isinstance(value, Mapping):
        vector = dict(value)
    else:
        raise M2ContractError(f"{path}: spent must be an integer or object")
    for dimension, amount in vector.items():
        _require(
            isinstance(amount, int) and not isinstance(amount, bool) and amount >= 0,
            f"{path}.{dimension}: spent must be a non-negative integer",
        )
    return vector


def validate_recovery_ledger(events: Iterable[Mapping[str, Any]]) -> dict[str, str]:
    """Generic RecoveryBudget lineage verifier.

    It does not know the bucket structure. Returns chainId -> terminal (or
    `OPEN`). Rejects: sequence regressions, work identity changes inside a
    chain, policy changes inside a chain, non-monotonic or disappearing spend
    dimensions, charges whose delta differs from the charged amount, any spend
    change on a layer transition, and any event after a terminal.
    """

    chains: dict[str, dict[str, Any]] = {}
    last_sequence = None
    for index, event in enumerate(events):
        path = f"events[{index}]"
        sequence = event.get("sequence")
        _require(
            isinstance(sequence, int) and not isinstance(sequence, bool),
            f"{path}: integer sequence required",
        )
        _require(
            last_sequence is None or sequence > last_sequence,
            f"{path}: sequence must strictly increase",
        )
        last_sequence = sequence

        chain_id = event.get("recoveryChainId")
        _require(isinstance(chain_id, str) and chain_id, f"{path}: chain id required")
        kind = event.get("kind")
        _require(kind in LEDGER_EVENT_KINDS, f"{path}: unknown kind {kind!r}")
        spent = _as_vector(event.get("spent"), f"{path}.spent")

        if kind == "CHAIN_STARTED":
            _require(chain_id not in chains, f"{path}: chain {chain_id} restarted")
            _require(
                all(amount == 0 for amount in spent.values()),
                f"{path}: chain must start with zero spend",
            )
            _require(
                isinstance(event.get("workIdentity"), str)
                and event.get("workIdentity"),
                f"{path}: chain must bind immutable work identity",
            )
            _require(
                isinstance(event.get("policyId"), str) and event.get("policyId"),
                f"{path}: chain must bind a versioned policyId",
            )
            chains[chain_id] = {
                "work": event["workIdentity"],
                "policy": event["policyId"],
                "spent": spent,
                "terminal": None,
            }
            continue

        _require(chain_id in chains, f"{path}: event for unstarted chain {chain_id}")
        chain = chains[chain_id]
        _require(
            chain["terminal"] is None,
            f"{path}: chain {chain_id} continues after {chain['terminal']}",
        )
        if "workIdentity" in event:
            _require(
                event["workIdentity"] == chain["work"],
                f"{path}: immutable work identity changed inside chain {chain_id}",
            )
        if "policyId" in event:
            _require(
                event["policyId"] == chain["policy"],
                f"{path}: policy changed inside chain {chain_id}",
            )

        before = chain["spent"]
        for dimension, amount in before.items():
            _require(
                dimension in spent,
                f"{path}: spend dimension {dimension!r} disappeared (reset)",
            )
            _require(
                spent[dimension] >= amount,
                f"{path}: spend {dimension!r} decreased {amount} -> "
                f"{spent[dimension]} (implicit reset)",
            )

        if kind == "CHARGE":
            charge = _as_vector(event.get("charge"), f"{path}.charge")
            _require(
                any(amount > 0 for amount in charge.values()),
                f"{path}: a charge must be positive",
            )
            expected = dict(before)
            for dimension, amount in charge.items():
                expected[dimension] = expected.get(dimension, 0) + amount
            _require(
                spent == expected,
                f"{path}: spent {spent} != previous {before} + charge {charge}",
            )
        elif kind == "LAYER_TRANSITION":
            transition = event.get("transition")
            _require(
                transition in LAYER_TRANSITIONS,
                f"{path}: unknown layer transition {transition!r}",
            )
            _require(
                spent == before,
                f"{path}: {transition} changed spend {before} -> {spent}",
            )
        else:
            terminal = event.get("terminal")
            _require(terminal in CHAIN_TERMINALS, f"{path}: unknown terminal")
            _require(spent == before, f"{path}: terminal changed spend")
            chain["terminal"] = terminal

        chain["spent"] = spent

    return {
        chain_id: chain["terminal"] or "OPEN"
        for chain_id, chain in chains.items()
    }


# ---------------------------------------------------------------------------
# Stable identity vs mutable delivery binding (M2.md section 12)
# ---------------------------------------------------------------------------

REBIND_OUTCOMES = ("REBOUND", "FAIL_CLOSED", "RERESOLVE")


def validate_delivery_rebinding(event: Mapping[str, Any]) -> None:
    """A rebind may change only mutable delivery material."""

    stable = event.get("stableWork")
    offered = event.get("offeredWork")
    outcome = event.get("outcome")
    _require(isinstance(stable, Mapping) and stable, "stableWork required")
    _require(isinstance(offered, Mapping) and offered, "offeredWork required")
    _require(outcome in REBIND_OUTCOMES, f"unknown rebind outcome {outcome!r}")

    compatible = dict(stable) == dict(offered)
    if outcome == "REBOUND":
        _require(
            compatible,
            "rebinding changed immutable work identity; must FAIL_CLOSED or "
            "RERESOLVE instead",
        )
        before = event.get("bindingRevisionBefore")
        after = event.get("bindingRevisionAfter")
        _require(
            isinstance(before, str) and isinstance(after, str) and before != after,
            "REBOUND requires a new DeliveryBindingRevision",
        )
    _require(
        event.get("resultingWork", stable) == stable,
        "the existing ExtentSpec/work identity must never be mutated",
    )


# ---------------------------------------------------------------------------
# Clock domains (M2.md section 13)
# ---------------------------------------------------------------------------

CLOCK_DOMAINS = (
    "ANDROID_MONOTONIC",
    "HOST_MEDIA_LAB_MONOTONIC",
    "HOST_FAULT_MONOTONIC",
    "PROVIDER_WALL_CLOCK",
)

CLOCK_RELATIONS = ("SUBTRACT", "ORDER")


def check_clock_relation(relation: Mapping[str, Any]) -> None:
    """Reject a correctness relation that compares absolute values across
    clock domains. Cross-domain joins must use correlation identities."""

    op = relation.get("op")
    left = relation.get("leftDomain")
    right = relation.get("rightDomain")
    _require(op in CLOCK_RELATIONS, f"unknown clock relation {op!r}")
    _require(left in CLOCK_DOMAINS, f"unknown clock domain {left!r}")
    _require(right in CLOCK_DOMAINS, f"unknown clock domain {right!r}")
    _require(
        left == right,
        f"{op} across clock domains {left} and {right} is forbidden",
    )


# ---------------------------------------------------------------------------
# Evidence privacy (M2.md section 15)
# ---------------------------------------------------------------------------

_SECRET_KEYS = {
    "authorization",
    "proxyauthorization",
    "cookie",
    "setcookie",
    "potoken",
    "visitordata",
    "visitorid",
    "sessionsecret",
    "password",
    "passphrase",
    "secret",
    "apikey",
    "accesstoken",
    "refreshtoken",
    "idtoken",
    "bearertoken",
    "signedurl",
    "rawurl",
    "ssid",
    "bssid",
    "vpncredential",
    "vpnpresharedkey",
    "privatekey",
}

_URL_WITH_QUERY_OR_USERINFO = re.compile(
    r"[a-zA-Z][a-zA-Z0-9+.-]*://(?:[^/\s?#]*@|[^\s?#]*\?)"
)
_BEARER = re.compile(r"\b(?:Bearer|Basic)\s+[A-Za-z0-9._~+/=-]{8,}")
_IPV4 = re.compile(
    r"(?<![0-9.])(?:(?:25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])\.){3}"
    r"(?:25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])(?![0-9.])"
)
# Full eight-group form, or a compressed form containing "::" next to a hex
# group. Clock times such as "04:00:00" are deliberately not matched.
_IPV6 = re.compile(
    r"(?<![\w:])(?:"
    r"(?:[0-9A-Fa-f]{1,4}:){7}[0-9A-Fa-f]{1,4}"
    r"|(?:[0-9A-Fa-f]{1,4}:){1,7}:(?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4})*)?"
    r"|::[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4})*"
    r")(?![\w:])"
)


def _normalized_key(key: str) -> str:
    return re.sub(r"[^a-z0-9]", "", key.lower())


def scan_evidence_privacy(document: Any, path: str = "$") -> None:
    """Fail closed when portable evidence would retain a secret-class value."""

    if isinstance(document, Mapping):
        for key, value in document.items():
            _require(
                _normalized_key(str(key)) not in _SECRET_KEYS,
                f"{path}.{key}: secret-class field must not be retained",
            )
            scan_evidence_privacy(value, f"{path}.{key}")
    elif isinstance(document, list):
        for index, value in enumerate(document):
            scan_evidence_privacy(value, f"{path}[{index}]")
    elif isinstance(document, str):
        _require(
            not _URL_WITH_QUERY_OR_USERINFO.search(document),
            f"{path}: URL with query/userinfo (possible signed URL) retained",
        )
        _require(not _BEARER.search(document), f"{path}: credential retained")
        _require(not _IPV4.search(document), f"{path}: raw IPv4 address retained")
        _require(not _IPV6.search(document), f"{path}: raw IPv6 address retained")


# ---------------------------------------------------------------------------
# Historical M0/M1 contracts are immutable (falsification item 18)
# ---------------------------------------------------------------------------

# Subsystem schemas added by M2 owning slices (M2.md section 16). They are M2
# contracts, not historical ones; every other non-`m2-` schema is historical.
M2_SLICE_SCHEMAS = frozenset({
    "route-events-v1.schema.json",
    "route-verification-summary-v1.schema.json",
})

# SHA-256 of every pre-M2 (M0/M1) schema at M2-A. M2 work must add a new
# versioned artifact rather than rewrite these.
HISTORICAL_SCHEMA_SHA256 = {
    "baseline-observations-v1.schema.json":
        "867cadacbfed1e87c95aae728871c89d2cd37916d01872dd17d0980a260cf5ef",
    "bridge-events-v1.schema.json":
        "1a5404eddac7d3accd5d690d9cb6e22faf0d33b22b4077c4ca4e728015971752",
    "committed-extents-v1.schema.json":
        "c83ed7985d404578ad79b3cd9cea2089c04077e80834b90c957029ae6f72f40f",
    "committed-extents-v2.schema.json":
        "32b9803b3e7a67ee265ec8c0c29c7ac177abf7ce30cbccc4e897f54b9b174214",
    "coverage-snapshot-v1.schema.json":
        "c37f2704f0837535ca2523542e8b10063e0ba6ccf2c40facbefe0fc82ca50c84",
    "coverage-snapshot-v2.schema.json":
        "51dc9448a582ac0487ac723f329e4681866086655c8fa6aae3db958f24fd70a5",
    "extent-events-v1.schema.json":
        "d286970e6544d00b500c2722530ae6b86c9671fb89282eefac8f23e56a52200e",
    "fetch-events-v1.schema.json":
        "11de506eb012f5da1f68671977f3c7a23639061d29a56fe26e46cce926923c05",
    "fetch-events-v2.schema.json":
        "e717db27f96437abd26bc39d285bdf3898da6bfba9494c02260e4238d03821a6",
    "fetch-events-v3.schema.json":
        "4999a7ac39c6bfc7579df54cd9f71e9aed7eb20410b12bbf91c394d51a15d69e",
    "m1-acceptance-index-v1.schema.json":
        "9e315ba6b87a15691defc8e014ae403baafb2eaab0ef1d452bbc0feb3d757f47",
    "m1-run-manifest-v1.schema.json":
        "b5d10cdfd692c72535ed2aaf50ef32652bb0246759383ecdb045c7f3447a138a",
    "origin-gate-events-v1.schema.json":
        "2234cc9dd52f5cb242030ca40ecf1b2fee1bde6a57f223f293202c4e852bc35f",
    "perfetto-trace-summary-v1.schema.json":
        "687f565b14e406af30254f5f8c97ddcc72339ca0a5ef397a329b4d2f7394a77a",
    "range-continuation-v1.schema.json":
        "1326cf0ceafa64d7da92ac29f67ea863c8b3916429a70984806a64633c91d44f",
    "recovery-summary-v1.schema.json":
        "783dba097de516ebd84b743e04842644f9c439808a500428fc4ee4ddf6280d61",
    "recovery-summary-v2.schema.json":
        "a43a982290ef66d546c03e1129b0cb2398199cf3b9509c740e28d01c6dece229",
    "recovery-timeline-v1.schema.json":
        "64d36f86fef60510bd4e9fda68a96b7148c03f44ccc1797550fec79d518b9afc",
    "result-v1.schema.json":
        "6a4d8e0199960c862a0af4e631620f16e94bc4b1de617e124b5bdaa8ad1d956e",
    "run-manifest-v1.schema.json":
        "efbe2a8738375827e3eb9b76dc070d899120f17fd94153dce173cefe24af8707",
    "seed-manifest-v1.schema.json":
        "463a330818ee16d717ac8207b7c4121b147e1ef36318cd308cd8fc557a13a17d",
    "seed-manifest-v2.schema.json":
        "e38b93d8918d2e13c599c0578d8f14c4b41cfd757815ca6c725f4d3fe00fa57b",
    "verified-extent-files-v1.schema.json":
        "4ad246c5289f6620212319fe7b7762183bf195673ac60c30102309bba254048e",
}
