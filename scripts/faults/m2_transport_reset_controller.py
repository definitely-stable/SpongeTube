#!/usr/bin/env python3
"""Synchronous lab-only control barrier for M2-G2-D TRANSPORT_RESET.

The Android instrumentation calls this controller after FetchBroker has emitted
the first ATTEMPT_FAILED event.  The HTTP response is deliberately withheld
until the host has:
  1. observed the first owner's durable Media Lab origin partition,
  2. removed the Toxiproxy reset toxic while preserving the proxy route, and
  3. persisted the causal trigger artifact.

Because the FetchEventListener is synchronous, RecoveryCoordinator cannot
advance to the next owner before the acknowledgement returns.  This makes the
origin split independently observable without comparing Android and host
clocks.  The ADB reverse endpoint is a lab control plane only; media continues
over the exact Android route through the namespace/veth path.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from typing import Any

SCRIPT_DIR = pathlib.Path(__file__).resolve().parent
MEASUREMENT_DIR = SCRIPT_DIR.parent / "measurement"
sys.path.insert(0, str(MEASUREMENT_DIR))

import m2_transport_harness as harness  # noqa: E402
from m2_contracts import scan_evidence_privacy  # noqa: E402

PORTABLE_ID = re.compile(r"^[a-z0-9][a-z0-9._:-]{0,255}$")
CONTROL_PROTOCOL = "ADB_REVERSE_LOOPBACK_HTTP_V1"
MAX_BODY_BYTES = 4096


class ResetControllerError(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ResetControllerError(message)


def complete_jsonl_rows(path: pathlib.Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    raw = path.read_text(encoding="utf-8")
    complete_end = raw.rfind("\n")
    if complete_end < 0:
        return []
    rows: list[dict[str, Any]] = []
    for line in raw[:complete_end].splitlines():
        if not line.strip():
            continue
        value = json.loads(line)
        require(isinstance(value, dict), "origin trace row must be an object")
        rows.append(value)
    return rows


def settle_first_owner_partition(
    trace_path: pathlib.Path,
    *,
    before_count: int,
    stable_seconds: float = 0.5,
    timeout_seconds: float = 5.0,
) -> tuple[int, list[int]]:
    """Freeze the origin boundary while the Android failure callback is blocked.

    At least one data request must have reached the origin for canonical N6.
    Once a complete-row signature remains stable for [stable_seconds], all rows
    since before_count are causally owned by the first physical application
    owner.  No later RecoveryCoordinator owner can exist until this function's
    caller acknowledges the Android control request.
    """
    require(before_count >= 0, "origin before-count must be non-negative")
    require(stable_seconds > 0.0, "stable_seconds must be positive")
    require(timeout_seconds > stable_seconds, "timeout must exceed settle window")

    deadline = time.monotonic() + timeout_seconds
    stable_signature: tuple[int, tuple[int, ...]] | None = None
    stable_since: float | None = None

    while time.monotonic() < deadline:
        rows = complete_jsonl_rows(trace_path)
        require(
            len(rows) >= before_count,
            "origin trace shrank below the frozen pre-trial boundary",
        )
        trial_rows = rows[before_count:]
        ids = tuple(
            row.get("requestId")
            for row in trial_rows
            if type(row.get("requestId")) is int
        )
        if len(ids) != len(trial_rows) or len(set(ids)) != len(ids):
            raise ResetControllerError("first-owner origin rows have invalid request ids")

        # Canonical reset_peer is required to reach the media origin.  This
        # excludes an uninteresting local connect failure from N6 evidence.
        enough = bool(trial_rows) and all(
            row.get("plane") == "data" and row.get("method") == "GET"
            for row in trial_rows
        )
        signature = (len(rows), ids)
        now = time.monotonic()
        if enough:
            if signature == stable_signature:
                if stable_since is not None and now - stable_since >= stable_seconds:
                    return len(rows), list(ids)
            else:
                stable_signature = signature
                stable_since = now
        else:
            stable_signature = None
            stable_since = None
        time.sleep(0.025)

    raise ResetControllerError(
        "first-owner origin partition did not settle before the control deadline"
    )


def validate_signal(payload: Any, *, trial_id: str) -> tuple[str, str]:
    require(isinstance(payload, dict), "control payload must be an object")
    require(
        set(payload) == {"signal", "trialId", "fetchId", "attemptCorrelationId"},
        "control payload fields drift",
    )
    require(payload.get("signal") == "ATTEMPT_FAILED", "unexpected control signal")
    require(payload.get("trialId") == trial_id, "control trialId drift")
    fetch_id = payload.get("fetchId")
    attempt_correlation = payload.get("attemptCorrelationId")
    require(
        isinstance(fetch_id, str) and PORTABLE_ID.fullmatch(fetch_id) is not None,
        "control fetchId is not portable",
    )
    require(
        attempt_correlation == f"{fetch_id}:attempt-1",
        "control application-attempt correlation drift",
    )
    return fetch_id, attempt_correlation


def write_json_atomic(path: pathlib.Path, value: dict[str, Any]) -> None:
    scan_evidence_privacy(value)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(value, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


class Controller:
    def __init__(
        self,
        *,
        trial_id: str,
        trace_path: pathlib.Path,
        before_count: int,
        trigger_path: pathlib.Path,
        scenario: pathlib.Path,
        api: str,
        evidence: pathlib.Path,
        disarmed_state: pathlib.Path,
        run_id: str,
        session_id: str,
    ) -> None:
        require(PORTABLE_ID.fullmatch(trial_id) is not None, "trialId is not portable")
        self.trial_id = trial_id
        self.trace_path = trace_path
        self.before_count = before_count
        self.trigger_path = trigger_path
        self.scenario = scenario
        self.api = api
        self.evidence = evidence
        self.disarmed_state = disarmed_state
        self.run_id = run_id
        self.session_id = session_id
        self.handled = False

    def handle(self, payload: Any) -> dict[str, Any]:
        require(not self.handled, "control barrier already consumed")
        fetch_id, attempt_correlation = validate_signal(
            payload,
            trial_id=self.trial_id,
        )
        observed_at = time.monotonic_ns()
        origin_count, origin_ids = settle_first_owner_partition(
            self.trace_path,
            before_count=self.before_count,
        )

        harness.disarm(
            argparse.Namespace(
                scenario=str(self.scenario),
                api=self.api,
                evidence=str(self.evidence),
                state=str(self.disarmed_state),
                run_id=self.run_id,
                session_id=self.session_id,
            )
        )
        disarmed_at = time.monotonic_ns()

        trigger = {
            "schemaVersion": 1,
            "trialId": self.trial_id,
            "signal": "ATTEMPT_FAILED",
            "fetchId": fetch_id,
            "attemptCorrelationId": attempt_correlation,
            "controlProtocol": CONTROL_PROTOCOL,
            "hostObservationClockDomain": "HOST_FAULT_MONOTONIC",
            "hostObservedAtElapsedRealtimeNs": observed_at,
            "faultDisarmedAtElapsedRealtimeNs": disarmed_at,
            "failureSignalsObservedAtTrigger": 1,
            "originCountBeforeTrial": self.before_count,
            "originCountAtTrigger": origin_count,
            "originRequestIdsBeforeDisarm": origin_ids,
        }
        write_json_atomic(self.trigger_path, trigger)
        self.handled = True
        return {
            "schemaVersion": 1,
            "status": "DISARMED",
            "trialId": self.trial_id,
        }


class ControllerServer(HTTPServer):
    controller: Controller
    error: str | None = None


class Handler(BaseHTTPRequestHandler):
    server_version = "SpongeG2DControl/1"

    def log_message(self, format: str, *args: Any) -> None:
        return

    def do_POST(self) -> None:  # noqa: N802
        server = self.server
        assert isinstance(server, ControllerServer)
        try:
            require(self.path == "/disarm", "unknown control path")
            raw_length = self.headers.get("Content-Length")
            require(raw_length is not None and raw_length.isdigit(), "Content-Length required")
            length = int(raw_length)
            require(0 < length <= MAX_BODY_BYTES, "control body size invalid")
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
            response = server.controller.handle(payload)
            encoded = json.dumps(response, sort_keys=True).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)
        except Exception as error:  # fail closed and make the host process fail
            server.error = f"{type(error).__name__}: {error}"
            encoded = json.dumps(
                {"schemaVersion": 1, "status": "ERROR"},
                sort_keys=True,
            ).encode("utf-8")
            self.send_response(409)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", required=True, type=int)
    parser.add_argument("--trial-id", required=True)
    parser.add_argument("--trace", required=True, type=pathlib.Path)
    parser.add_argument("--origin-before-count", required=True, type=int)
    parser.add_argument("--trigger", required=True, type=pathlib.Path)
    parser.add_argument("--ready", required=True, type=pathlib.Path)
    parser.add_argument("--scenario", required=True, type=pathlib.Path)
    parser.add_argument("--api", required=True)
    parser.add_argument("--evidence", required=True, type=pathlib.Path)
    parser.add_argument("--disarmed-state", required=True, type=pathlib.Path)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--session-id", required=True)
    args = parser.parse_args()

    require(1 <= args.port <= 65535, "control port out of range")
    controller = Controller(
        trial_id=args.trial_id,
        trace_path=args.trace,
        before_count=args.origin_before_count,
        trigger_path=args.trigger,
        scenario=args.scenario,
        api=args.api,
        evidence=args.evidence,
        disarmed_state=args.disarmed_state,
        run_id=args.run_id,
        session_id=args.session_id,
    )

    server = ControllerServer(("127.0.0.1", args.port), Handler)
    server.controller = controller
    server.timeout = 120.0
    write_json_atomic(
        args.ready,
        {
            "schemaVersion": 1,
            "state": "LISTENING",
            "controlProtocol": CONTROL_PROTOCOL,
            "trialId": args.trial_id,
        },
    )
    try:
        server.handle_request()
    finally:
        server.server_close()

    require(server.error is None, server.error or "controller failed")
    require(controller.handled, "controller timed out without a disarm request")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
