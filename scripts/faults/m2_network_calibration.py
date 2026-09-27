#!/usr/bin/env python3
"""Produce portable network-calibration-v1 from raw E3 harness observations."""
from __future__ import annotations

import argparse
import json
import pathlib
import sys
from typing import Any

SCRIPT_DIR = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

import m2_network_harness as harness  # noqa: E402

MEASUREMENT_DIR = SCRIPT_DIR.parent / "measurement"
sys.path.insert(0, str(MEASUREMENT_DIR))
from m2_contracts import scan_evidence_privacy, scenario_sha256  # noqa: E402
from schema_subset import validate_instance  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCHEMA = ROOT / ".work" / "schemas" / "network-calibration-v1.schema.json"


def load(path: pathlib.Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def marker(path: pathlib.Path, expected: str) -> bool:
    return path.read_text(encoding="utf-8").strip() == expected


def netem_entry(value: Any) -> dict[str, Any]:
    found = harness._find_kind(value, "netem")
    if found is None:
        raise ValueError("final qdisc readback has no netem")
    return found


def stat(entry: dict[str, Any], name: str) -> int:
    value = entry.get(name)
    if isinstance(value, int) and not isinstance(value, bool):
        return max(0, value)
    stats = entry.get("stats") or {}
    if isinstance(stats, dict):
        value = stats.get(name)
        if isinstance(value, int) and not isinstance(value, bool):
            return max(0, value)
    stats2 = entry.get("stats2") or {}
    if isinstance(stats2, dict):
        value = stats2.get(name)
        if isinstance(value, int) and not isinstance(value, bool):
            return max(0, value)
    return 0


def tool_config(config: dict[str, Any]) -> dict[str, Any]:
    value = {
        key: item
        for key, item in config.items()
        if key != "durationMs"
    }
    value["mediaPortScoped"] = True
    return value


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--scenario", required=True, type=pathlib.Path)
    parser.add_argument("--run-manifest", required=True, type=pathlib.Path)
    parser.add_argument("--harness", required=True, type=pathlib.Path)
    parser.add_argument("--qdisc-final", required=True, type=pathlib.Path)
    parser.add_argument("--filter-state", required=True, type=pathlib.Path)
    parser.add_argument("--network-result", required=True, type=pathlib.Path)
    parser.add_argument("--scope-before", required=True, type=pathlib.Path)
    parser.add_argument("--scope-after", required=True, type=pathlib.Path)
    parser.add_argument("--health-dir", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()

    scenario = load(args.scenario)
    manifest = load(args.run_manifest)
    harness_doc = load(args.harness)
    qdisc = load(args.qdisc_final)
    filters = load(args.filter_state)
    network_result = load(args.network_result)
    before = load(args.scope_before)
    after = load(args.scope_after)

    config = harness.compile_config(scenario)
    observed = harness.normalize_tc_state(qdisc, filters, scenario["variant"])
    expected_tool = tool_config(config)
    if observed != expected_tool:
        raise SystemExit(
            f"network calibration readback mismatch: expected={expected_tool!r} observed={observed!r}"
        )

    qdisc_entry = netem_entry(qdisc)
    duration_ns = int(network_result.get("acquisitionDurationNs", 0))
    failures = int(network_result.get("failureCount", 0))
    timeouts = int(network_result.get("timeoutCount", 0))
    success = network_result.get("terminalReason") == "SUCCESS"

    cleanup = {
        "qdiscRemoved": marker(args.health_dir / "qdisc-clean.txt", "ok"),
        "filtersRemoved": marker(args.health_dir / "filter-clean.txt", "ok"),
        "namespaceRemoved": marker(args.health_dir / "namespace-clean.txt", "ok"),
        "proxiesRemoved": marker(args.health_dir / "transport-tool-absent.txt", "ok"),
        "toxicsRemoved": marker(args.health_dir / "transport-tool-absent.txt", "ok"),
    }
    scope = {
        "mediaPacketsBefore": int(before["mediaPackets"]),
        "mediaPacketsAfter": int(after["mediaPackets"]),
        "mediaBytesBefore": int(before["mediaBytes"]),
        "mediaBytesAfter": int(after["mediaBytes"]),
        "controlPacketsBefore": int(before["controlPackets"]),
        "controlPacketsAfter": int(after["controlPackets"]),
        "adbHealthyBefore": marker(args.health_dir / "adb-before.txt", "device"),
        "adbHealthyDuring": marker(args.health_dir / "adb-during.txt", "device"),
        "adbHealthyAfter": marker(args.health_dir / "adb-after.txt", "device"),
    }
    calibration = {
        "schemaVersion": 1,
        "runId": manifest["runId"],
        "sessionId": manifest["sessionId"],
        "scenarioHash": scenario_sha256(scenario),
        "harnessId": "sponge-network-harness",
        "harnessVersion": "1",
        "clockDomain": "HOST_FAULT_MONOTONIC",
        "expectedConfig": expected_tool,
        "observedConfig": observed,
        "scopeProof": scope,
        "qdiscCounters": {
            "packets": stat(qdisc_entry, "packets"),
            "bytes": stat(qdisc_entry, "bytes"),
            "drops": stat(qdisc_entry, "drops"),
            "overlimits": stat(qdisc_entry, "overlimits"),
            "requeues": stat(qdisc_entry, "requeues"),
        },
        "probeSummary": {
            "sampleCount": 1,
            "successCount": 1 if success else 0,
            "failureCount": failures,
            "timeoutCount": timeouts,
            "minDurationNs": duration_ns,
            "maxDurationNs": duration_ns,
        },
        "cleanup": cleanup,
        "limitations": [
            "API 36 emulator correctness and packet-fault attribution evidence; not representative network performance."
        ],
    }

    schema = load(SCHEMA)
    validate_instance(schema, calibration)
    for document in (scenario, manifest, harness_doc, calibration):
        scan_evidence_privacy(document)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(calibration, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
