#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[2]
FIXTURE = REPO_ROOT / "test-fixtures" / "media" / "F0" / "progressive.mp4"
EXPECTED_STATUS = 206
EXPECTED_BYTES = 4096
FORBIDDEN_PORTABLE_TOKENS = (
    "http://",
    "https://",
    "192.0.2.",
    "198.51.100.",
    "203.0.113.",
)


class VerificationError(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise VerificationError(message)


def load_object(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise VerificationError(f"{path}: invalid JSON: {error}") from error
    if not isinstance(value, dict):
        raise VerificationError(f"{path}: expected JSON object")
    return value


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    try:
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip():
                continue
            value = json.loads(line)
            if not isinstance(value, dict):
                raise VerificationError(f"{path}:{number}: expected JSON object")
            rows.append(value)
    except (OSError, json.JSONDecodeError) as error:
        raise VerificationError(f"{path}: invalid JSONL: {error}") from error
    return rows


def canonical_range_hash() -> str:
    try:
        with FIXTURE.open("rb") as stream:
            data = stream.read(EXPECTED_BYTES)
    except OSError as error:
        raise VerificationError(f"cannot read canonical fixture: {error}") from error
    require(len(data) == EXPECTED_BYTES, "canonical fixture is shorter than the probe range")
    return hashlib.sha256(data).hexdigest()


def reject_private_endpoint_material(path: Path) -> None:
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as error:
        raise VerificationError(f"cannot privacy-scan {path}: {error}") from error
    for token in FORBIDDEN_PORTABLE_TOKENS:
        require(token not in text, f"{path}: portable evidence leaked {token!r}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("evidence_dir", type=Path)
    parser.add_argument("origin_trace", type=Path)
    args = parser.parse_args()

    feasibility_path = args.evidence_dir / "feasibility.json"
    route_events_path = args.evidence_dir / "route-events.json"
    feasibility = load_object(feasibility_path)
    route_events = load_object(route_events_path)
    origin_rows = load_jsonl(args.origin_trace)

    require(feasibility.get("schemaVersion") == 1, "feasibility schema mismatch")
    require(feasibility.get("phase") == "M2-F0", "wrong feasibility phase")
    require(feasibility.get("deviceApi") == 36, "F0 must run on API 36")
    require(feasibility.get("actualRouteLossObserved") is True, "route loss was not observed")
    require(feasibility.get("vpnObserved") is True, "VPN default route was not observed")
    require(feasibility.get("vpnMediaStatus") == EXPECTED_STATUS, "VPN media status mismatch")
    require(feasibility.get("vpnMediaBytes") == EXPECTED_BYTES, "VPN media byte count mismatch")
    require(
        feasibility.get("vpnMediaBodySha256") == canonical_range_hash(),
        "VPN media bytes do not match canonical F0 range",
    )
    require(feasibility.get("vpnTunReflectedPackets", 0) > 0, "no packets crossed the VPN TUN")
    require(feasibility.get("vpnTunReflectedBytes", 0) > 0, "no bytes crossed the VPN TUN")
    require(feasibility.get("mediaPathUsesAdbReverse") is False, "media path used adb reverse")
    require(feasibility.get("monitorStopped") is True, "route monitor did not stop cleanly")
    require(feasibility.get("status") == "PASS", "device feasibility did not pass")

    epochs = [
        feasibility.get("initialDirectEpoch"),
        feasibility.get("restoredDirectEpoch"),
        feasibility.get("vpnEpoch"),
        feasibility.get("postVpnDirectEpoch"),
    ]
    require(
        all(isinstance(value, int) and not isinstance(value, bool) and value > 0 for value in epochs),
        "route epochs are invalid",
    )
    require(
        epochs == sorted(epochs) and len(set(epochs)) == len(epochs),
        "route epochs are not strictly increasing",
    )

    require(route_events.get("schemaVersion") == 1, "route evidence schema mismatch")
    require(route_events.get("androidApi") == 36, "route evidence API mismatch")
    require(route_events.get("clockDomain") == "ANDROID_MONOTONIC", "route clock domain mismatch")
    events = route_events.get("events")
    require(isinstance(events, list) and events, "route evidence has no events")
    signals = [row.get("signal") for row in events if isinstance(row, dict)]
    require("LOST" in signals, "route evidence has no actual default-network LOST event")
    require(signals[-1] == "MONITOR_STOPPED", "route evidence did not retain MONITOR_STOPPED")

    available_states = [
        row.get("runtimeStateAfter")
        for row in events
        if isinstance(row, dict)
        and isinstance(row.get("runtimeStateAfter"), dict)
        and row["runtimeStateAfter"].get("state") == "AVAILABLE"
    ]
    require(
        any(state.get("vpn") == "TRUE" for state in available_states),
        "route evidence never observed VPN=true",
    )
    require(
        any(state.get("vpn") == "FALSE" for state in available_states),
        "route evidence never observed VPN=false",
    )

    data_rows = [
        row
        for row in origin_rows
        if row.get("plane") == "data" and row.get("method") == "GET"
    ]
    require(len(data_rows) == 1, f"expected exactly one origin data GET, got {len(data_rows)}")
    row = data_rows[0]
    require(row.get("path") == "/fixtures/F0/progressive.mp4", "origin path mismatch")
    require(row.get("rangeHeader") == "bytes=0-4095", "origin range request mismatch")
    require(row.get("resolvedRangeStart") == 0, "origin range start mismatch")
    require(row.get("resolvedRangeEndExclusive") == EXPECTED_BYTES, "origin range end mismatch")
    require(row.get("status") == EXPECTED_STATUS, "origin status mismatch")
    require(row.get("bodyBytesWritten") == EXPECTED_BYTES, "origin body byte count mismatch")

    reject_private_endpoint_material(feasibility_path)
    reject_private_endpoint_material(route_events_path)

    summary = {
        "schemaVersion": 1,
        "phase": "M2-F0",
        "status": "PASS",
        "checks": {
            "actualDefaultRouteLossRestore": True,
            "strictlyIncreasingRouteEpochs": True,
            "actualVpnRouteObserved": True,
            "vpnTunCarriedTraffic": True,
            "canonicalMediaBytesMatched": True,
            "realOriginRangeRequestObserved": True,
            "mediaAdbReverseAbsent": True,
            "monitorCleanupComplete": True,
            "portableEvidencePrivacyClean": True,
        },
        "limitations": [
            "API 36 emulator feasibility proof only; no RecoveryCoordinator integration or M2-F acceptance claim."
        ],
    }
    output = args.evidence_dir / "verification.json"
    output.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("M2-F0 route/VPN feasibility evidence verified")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except VerificationError as error:
        raise SystemExit(f"M2-F0 verification failed: {error}")
