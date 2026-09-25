"""Independent host verifier for M2-B `route-events-v1` evidence.

Normative source: `.work/milestones/M2.md` sections 8 and 16.

The verifier never imports or trusts the production Kotlin reducer. It
replays the recorded platform signals through its own state machine, derives
the expected route state, epoch and disposition of every event, replays the
session route guard through the M2 contract oracle (`m2_contracts`) and
compares both with what the runtime recorded. Every check fails closed by
raising `RouteOracleError`.

Usage:

    python3 scripts/measurement/m2_route_oracle.py verify \
        --input route-events.json --output route-verification-summary.json
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
from typing import Any, Mapping

SCRIPT_DIR = pathlib.Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from m2_contracts import (  # noqa: E402
    M2ContractError,
    ROUTE_CAPABILITIES,
    check_capability_api_floor,
    evaluate_external_fetch_route,
    scan_evidence_privacy,
)
from schema_subset import SchemaContractError, validate_instance  # noqa: E402

SCHEMAS = REPO_ROOT / ".work" / "schemas"
ROUTE_EVENTS_SCHEMA = SCHEMAS / "route-events-v1.schema.json"
SUMMARY_SCHEMA = SCHEMAS / "route-verification-summary-v1.schema.json"


class RouteOracleError(ValueError):
    pass


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise RouteOracleError(message)


OBSERVED_CAPABILITIES = (
    "internet",
    "validated",
    "vpn",
    "metered",
    "restricted",
    "suspended",
)

CALLBACK_SIGNALS = (
    "AVAILABLE",
    "CAPABILITIES_CHANGED",
    "LINK_PROPERTIES_CHANGED",
    "BLOCKED_CHANGED",
    "LOST",
)

SIGNAL_SOURCES = {
    "MONITOR_STARTED": "MONITOR_LIFECYCLE",
    "MONITOR_STOPPED": "MONITOR_LIFECYCLE",
    "LEGACY_SNAPSHOT": "API23_ACTIVE_NETWORK_SNAPSHOT",
    "BOOTSTRAP_SNAPSHOT": "BOOTSTRAP_ACTIVE_NETWORK",
    **{signal: "DEFAULT_NETWORK_CALLBACK" for signal in CALLBACK_SIGNALS},
}

# Sources a monitor may use per API (M2.md 8.1): the default-network callback
# does not exist on API 23; CONNECTIVITY_ACTION is forbidden from API 24.
LEGACY_API = 23

# Field names that would indicate platform identity or location-sensitive
# data leaked into evidence (M2.md 15; issue M2-B privacy list).
_FORBIDDEN_KEYS = {
    "network",
    "networkid",
    "netid",
    "networkhandle",
    "networktostring",
    "ip",
    "ipaddress",
    "address",
    "addresses",
    "linkaddress",
    "linkaddresses",
    "dns",
    "dnsservers",
    "privatedns",
    "interface",
    "interfacename",
    "iface",
    "proxy",
    "httpproxy",
    "routes",
    "linkproperties",
    "ssid",
    "bssid",
    "wifiinfo",
    "owneruid",
    "transportinfo",
}

LIMITATIONS = (
    "M2-B proves route observation, reduction and session privacy-policy "
    "semantics. It does not prove that a real media fetch is paused during an "
    "Android VPN/default-route transition; that end-to-end proof belongs to "
    "M2-F.",
    "Route evidence observes the emulator's actual default network; no VPN or "
    "default-route switch is induced in M2-B.",
)

CASES = (
    "SCHEMA",
    "STRICT_SEQUENCE",
    "MONOTONIC_ANDROID_CLOCK",
    "SOURCE_API_COMPATIBILITY",
    "MONITOR_LIFECYCLE",
    "EPOCH_TRANSITIONS",
    "STALE_EVENT_SUPPRESSION",
    "REPLACEMENT_CLEARS_CAPABILITIES",
    "CAPABILITY_API_FLOORS",
    "RUNTIME_STATE_MATCHES_ORACLE",
    "SESSION_GUARD",
    "POLICY_DECISIONS",
    "EVIDENCE_PRIVACY",
)


def _normalized_key(key: str) -> str:
    return re.sub(r"[^a-z0-9]", "", key.lower())


def _unknown_capabilities() -> dict[str, str]:
    return {name: "UNKNOWN" for name in ROUTE_CAPABILITIES}


class _Replay:
    """Oracle-owned default-route state machine."""

    def __init__(self) -> None:
        self.state = "INITIALIZING"
        self.current_ref: str | None = None
        self.last_epoch = 0
        self.epoch: int | None = None
        self.received = False
        self.capabilities = _unknown_capabilities()
        self.epochs_started = 0

    def snapshot(self) -> dict[str, Any]:
        return {
            "state": self.state,
            "routeEpoch": self.epoch,
            "capabilitiesReceived": self.received,
            **self.capabilities,
        }

    def _is_current(self, ref: str | None) -> bool:
        return self.state == "AVAILABLE" and ref is not None and ref == self.current_ref

    def _begin_epoch(self, ref: str, observed: Mapping[str, str] | None) -> None:
        self.last_epoch += 1
        self.epochs_started += 1
        self.state = "AVAILABLE"
        self.current_ref = ref
        self.epoch = self.last_epoch
        self.received = observed is not None
        self.capabilities = _unknown_capabilities()
        if observed is not None:
            self.capabilities.update({k: observed[k] for k in OBSERVED_CAPABILITIES})

    def _unavailable(self) -> None:
        self.state = "UNAVAILABLE"
        self.current_ref = None
        self.epoch = None
        self.received = False
        self.capabilities = _unknown_capabilities()

    def apply(self, event: Mapping[str, Any]) -> str:
        """Reduce one event; return the expected disposition."""

        signal = event["signal"]
        ref = event["platformRouteRef"]
        observed = event["observedCapabilities"]
        if signal in ("MONITOR_STARTED", "MONITOR_STOPPED"):
            return "APPLIED"
        if signal == "AVAILABLE":
            if self._is_current(ref):
                return "UNCHANGED"
            self._begin_epoch(ref, None)
            return "APPLIED"
        if signal == "CAPABILITIES_CHANGED":
            if not self._is_current(ref):
                return "STALE_IGNORED"
            self.received = True
            self.capabilities.update({k: observed[k] for k in OBSERVED_CAPABILITIES})
            return "APPLIED"
        if signal == "LINK_PROPERTIES_CHANGED":
            return "UNCHANGED" if self._is_current(ref) else "STALE_IGNORED"
        if signal == "BLOCKED_CHANGED":
            if not self._is_current(ref):
                return "STALE_IGNORED"
            self.capabilities["blocked"] = event["observedBlocked"]
            return "APPLIED"
        if signal == "LOST":
            if not self._is_current(ref):
                return "STALE_IGNORED"
            self._unavailable()
            return "APPLIED"
        if signal == "LEGACY_SNAPSHOT":
            if ref is None:
                self._unavailable()
            elif self._is_current(ref):
                self.received = observed is not None
                self.capabilities = _unknown_capabilities()
                if observed is not None:
                    self.capabilities.update(
                        {k: observed[k] for k in OBSERVED_CAPABILITIES}
                    )
            else:
                self._begin_epoch(ref, observed)
            return "APPLIED"
        if signal == "BOOTSTRAP_SNAPSHOT":
            if self.state != "INITIALIZING":
                return "UNCHANGED"
            if ref is None:
                self._unavailable()
            else:
                self._begin_epoch(ref, None)
            return "APPLIED"
        raise RouteOracleError(f"unknown signal {signal!r}")


def _check_privacy(document: Any, path: str = "$") -> None:
    if isinstance(document, Mapping):
        for key, value in document.items():
            _require(
                _normalized_key(str(key)) not in _FORBIDDEN_KEYS,
                f"{path}.{key}: platform/location identity must not be retained",
            )
            _check_privacy(value, f"{path}.{key}")
    elif isinstance(document, list):
        for index, value in enumerate(document):
            _check_privacy(value, f"{path}[{index}]")
    try:
        scan_evidence_privacy(document, path)
    except M2ContractError as error:
        raise RouteOracleError(str(error)) from error


def _check_event_shape(event: Mapping[str, Any], api: int, index: int) -> None:
    where = f"events[{index}]"
    signal = event["signal"]
    source = event["source"]
    _require(
        SIGNAL_SOURCES[signal] == source,
        f"{where}: signal {signal} cannot come from source {source}",
    )
    if api == LEGACY_API:
        _require(
            source in ("MONITOR_LIFECYCLE", "API23_ACTIVE_NETWORK_SNAPSHOT"),
            f"{where}: API 23 has no {source} source "
            "(registerDefaultNetworkCallback is API 24+)",
        )
    else:
        _require(
            source != "API23_ACTIVE_NETWORK_SNAPSHOT",
            f"{where}: API {api} must use the default-network callback, "
            "not CONNECTIVITY_ACTION",
        )

    ref = event["platformRouteRef"]
    if signal in CALLBACK_SIGNALS:
        _require(ref is not None, f"{where}: {signal} requires platformRouteRef")
    if signal in ("MONITOR_STARTED", "MONITOR_STOPPED"):
        _require(ref is None, f"{where}: lifecycle rows carry no platformRouteRef")

    observed = event["observedCapabilities"]
    if signal == "CAPABILITIES_CHANGED":
        _require(observed is not None, f"{where}: capabilities missing")
    elif signal == "LEGACY_SNAPSHOT":
        _require(
            ref is not None or observed is None,
            f"{where}: capabilities without an active network",
        )
    else:
        _require(observed is None, f"{where}: {signal} carries no capabilities")

    blocked = event["observedBlocked"]
    if signal == "BLOCKED_CHANGED":
        _require(blocked in ("TRUE", "FALSE"), f"{where}: blocked value missing")
    else:
        _require(blocked is None, f"{where}: {signal} carries no blocked value")


def _check_api_floors(event: Mapping[str, Any], api: int, index: int) -> None:
    where = f"events[{index}]"
    try:
        if event["signal"] == "BLOCKED_CHANGED":
            check_capability_api_floor(api, "blocked", event["observedBlocked"])
        observed = event["observedCapabilities"]
        if observed is not None:
            for name in OBSERVED_CAPABILITIES:
                check_capability_api_floor(api, name, observed[name])
        runtime = event["runtimeStateAfter"]
        for name in ROUTE_CAPABILITIES:
            check_capability_api_floor(api, name, runtime[name])
    except M2ContractError as error:
        raise RouteOracleError(f"{where}: {error}") from error


def verify_route_events(
    document: Mapping[str, Any],
    *,
    expected_api: int | None = None,
) -> dict[str, Any]:
    """Verify one route-events-v1 artifact and return its summary."""

    try:
        validate_instance(_load(ROUTE_EVENTS_SCHEMA), document)
    except SchemaContractError as error:
        raise RouteOracleError(f"schema: {error}") from error

    api = document["androidApi"]
    if expected_api is not None:
        _require(api == expected_api, f"androidApi {api} != expected {expected_api}")
    _check_privacy(document)

    events = document["events"]
    _require(events[0]["signal"] == "MONITOR_STARTED", "first event must be MONITOR_STARTED")
    _require(
        events[-1]["signal"] == "MONITOR_STOPPED",
        "complete evidence must end with MONITOR_STOPPED",
    )
    for signal in ("MONITOR_STARTED", "MONITOR_STOPPED"):
        _require(
            sum(1 for e in events if e["signal"] == signal) == 1,
            f"exactly one {signal} required",
        )

    replay = _Replay()
    states_by_sequence: dict[int, dict[str, Any]] = {}
    previous_ns = -1
    for index, event in enumerate(events):
        where = f"events[{index}]"
        _require(
            event["sequence"] == index + 1,
            f"{where}: sequence {event['sequence']} != {index + 1} "
            "(route events must be contiguous and ordered)",
        )
        _require(
            event["elapsedRealtimeNs"] >= previous_ns,
            f"{where}: ANDROID_MONOTONIC timestamp decreased",
        )
        previous_ns = event["elapsedRealtimeNs"]
        _check_event_shape(event, api, index)
        _check_api_floors(event, api, index)

        epoch_before = replay.epoch
        disposition = replay.apply(event)
        expected_state = replay.snapshot()

        _require(
            event["disposition"] == disposition,
            f"{where}: disposition {event['disposition']} != oracle {disposition}",
        )
        _require(
            event["routeEpochBefore"] == epoch_before,
            f"{where}: routeEpochBefore {event['routeEpochBefore']} != oracle "
            f"{epoch_before}",
        )
        _require(
            event["routeEpochAfter"] == replay.epoch,
            f"{where}: routeEpochAfter {event['routeEpochAfter']} != oracle "
            f"{replay.epoch}",
        )
        _require(
            event["runtimeStateAfter"] == expected_state,
            f"{where}: runtime state {event['runtimeStateAfter']} != oracle "
            f"{expected_state}",
        )
        states_by_sequence[event["sequence"]] = expected_state

    _verify_policy(document["policyEvaluations"], states_by_sequence)

    summary = {
        "schemaVersion": 1,
        "runId": document["runId"],
        "sessionId": document["sessionId"],
        "androidApi": api,
        "status": "PASS",
        "eventCount": len(events),
        "policyEvaluationCount": len(document["policyEvaluations"]),
        "epochsObserved": replay.epochs_started,
        "sourcesObserved": sorted({event["source"] for event in events}),
        "casesPassed": list(CASES),
        "limitations": list(LIMITATIONS),
    }
    validate_instance(_load(SUMMARY_SCHEMA), summary)
    return summary


def _verify_policy(
    evaluations: list[Mapping[str, Any]],
    states_by_sequence: Mapping[int, Mapping[str, Any]],
) -> None:
    guard = "UNRESOLVED"
    previous_watermark = 0
    for index, row in enumerate(evaluations):
        where = f"policyEvaluations[{index}]"
        _require(row["sequence"] == index + 1, f"{where}: policy sequence not contiguous")
        watermark = row["routeEventSequenceWatermark"]
        _require(
            watermark in states_by_sequence,
            f"{where}: watermark {watermark} names no route event",
        )
        _require(
            watermark >= previous_watermark,
            f"{where}: route watermark moved backwards",
        )
        previous_watermark = watermark
        route = states_by_sequence[watermark]
        _require(
            row["routeEpoch"] == route["routeEpoch"],
            f"{where}: routeEpoch {row['routeEpoch']} != oracle {route['routeEpoch']}",
        )
        _require(
            row["guardBefore"] == guard,
            f"{where}: guardBefore {row['guardBefore']} != oracle {guard}",
        )
        try:
            guard_after, decision, reason = evaluate_external_fetch_route(
                guard,
                route,
                explicit_direct_override=row["explicitDirectOverride"],
            )
        except M2ContractError as error:
            raise RouteOracleError(f"{where}: {error}") from error
        _require(
            row["guardAfter"] == guard_after,
            f"{where}: guardAfter {row['guardAfter']} != oracle {guard_after}",
        )
        _require(
            (row["decision"], row["reason"]) == (decision, reason),
            f"{where}: runtime decision {row['decision']}/{row['reason']} "
            f"!= oracle {decision}/{reason}",
        )
        guard = guard_after


_SCHEMA_CACHE: dict[pathlib.Path, dict[str, Any]] = {}


def _load(path: pathlib.Path) -> dict[str, Any]:
    if path not in _SCHEMA_CACHE:
        _SCHEMA_CACHE[path] = json.loads(path.read_text(encoding="utf-8"))
    return _SCHEMA_CACHE[path]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    verify = commands.add_parser("verify", help="verify one route-events-v1 artifact")
    verify.add_argument("--input", required=True, type=pathlib.Path)
    verify.add_argument("--output", required=True, type=pathlib.Path)
    verify.add_argument("--expected-api", type=int)
    verify.add_argument(
        "--require-source",
        action="append",
        default=[],
        help="source that must appear in the evidence (repeatable)",
    )
    args = parser.parse_args(argv)

    document = json.loads(args.input.read_text(encoding="utf-8"))
    try:
        summary = verify_route_events(document, expected_api=args.expected_api)
        for source in args.require_source:
            _require(
                source in summary["sourcesObserved"],
                f"required source {source} not observed",
            )
    except RouteOracleError as error:
        print(f"route-events-v1 verification failed: {error}", file=sys.stderr)
        return 1
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(
        f"route-events-v1 PASS: api={summary['androidApi']} "
        f"events={summary['eventCount']} "
        f"policyEvaluations={summary['policyEvaluationCount']} "
        f"epochs={summary['epochsObserved']}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
