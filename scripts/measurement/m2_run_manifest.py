#!/usr/bin/env python3
"""Produce the frozen m2-run-manifest-v1 for one real M2-E run."""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
from datetime import datetime, timezone
from typing import Any

from m2_contracts import (
    scan_evidence_privacy,
    scenario_sha256,
    validate_run_manifest_semantics,
    validate_scenario_semantics,
)
from schema_subset import validate_instance

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCHEMA_PATH = ROOT / ".work" / "schemas" / "m2-run-manifest-v1.schema.json"


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_object(path: pathlib.Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"expected JSON object: {path}")
    return value


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def build(args: argparse.Namespace) -> dict[str, Any]:
    scenario = load_object(args.scenario)
    validate_scenario_semantics(scenario)
    plane = scenario.get("primaryPlane")
    if plane not in {"TRANSPORT", "NETWORK"}:
        raise ValueError("M2-E run manifest producer requires TRANSPORT or NETWORK primaryPlane")
    harness_binding = {
        "TRANSPORT": ("sponge-transport-harness", "transport"),
        "NETWORK": ("sponge-network-harness", "network"),
    }[plane]

    git_commit = args.git_commit.strip()
    if len(git_commit) != 40 or any(ch not in "0123456789abcdef" for ch in git_commit):
        raise ValueError("git commit must be a lowercase 40-character SHA")

    runtime = {
        "executor": "HttpRangeFetchExecutor",
        "media3": "1.11.1",
    }
    fingerprint = getattr(args, "fault_engine_fingerprint", None)
    if plane == "NETWORK":
        if fingerprint is None:
            raise ValueError("NETWORK run requires --fault-engine-fingerprint")
        runtime["faultEngineFingerprintSha256"] = sha256_file(fingerprint)

    manifest = {
        "schemaVersion": 1,
        "runId": args.run_id,
        "sessionId": args.session_id,
        "createdAtUtc": args.created_at_utc or utc_now(),
        "gitCommit": git_commit,
        "fixture": {
            "id": "F1",
            "manifestSha256": sha256_file(args.fixture_manifest),
        },
        "scenario": {
            "family": scenario["scenarioFamily"],
            "variant": scenario["variant"],
            "primaryPlane": scenario["primaryPlane"],
            "hash": scenario_sha256(scenario),
        },
        "playbackMode": "DIRECT",
        "mediaPath": "ANDROID_DEFAULT_NETWORK",
        "transport": {
            "backendId": "http-range-fetch-executor",
            "backendVersion": None,
        },
        "faultHarnesses": [
            {
                "plane": plane,
                "harnessId": harness_binding[0],
                "harnessVersion": "1",
            }
        ],
        "policies": [
            {
                "policyId": "sponge-recovery-v2",
                "version": "2",
            }
        ],
        "device": {
            "api": args.device_api,
            "kind": "ANDROID_EMULATOR",
        },
        "runtime": runtime,
        "clockDomains": [
            "ANDROID_MONOTONIC",
            "HOST_MEDIA_LAB_MONOTONIC",
            "HOST_FAULT_MONOTONIC",
        ],
        "limitations": [
            "API 36 emulator correctness and fault-attribution evidence; not representative "
            + harness_binding[1]
            + " performance."
        ],
    }

    schema = load_object(SCHEMA_PATH)
    validate_instance(schema, manifest)
    validate_run_manifest_semantics(manifest, scenario)
    scan_evidence_privacy(manifest)
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--session-id", required=True)
    parser.add_argument("--git-commit", required=True)
    parser.add_argument("--fixture-manifest", required=True, type=pathlib.Path)
    parser.add_argument("--device-api", required=True, type=int)
    parser.add_argument("--fault-engine-fingerprint", type=pathlib.Path)
    parser.add_argument("--created-at-utc")
    args = parser.parse_args()

    manifest = build(args)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
