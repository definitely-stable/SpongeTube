#!/usr/bin/env python3
"""Build and verify M2-G2-D transport-reset environment evidence."""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys
from typing import Any, Mapping

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCHEMA = ROOT / ".work" / "schemas" / "transport-reset-environment-v1.schema.json"
TOOL_LOCK = ROOT / "tools" / "fault-harness" / "toolchain.lock.json"
sys.path.insert(0, str(ROOT / "scripts" / "measurement"))

from m2_contracts import scan_evidence_privacy  # noqa: E402
from schema_subset import validate_instance  # noqa: E402

CODE_PATHS = (
    ".github/workflows/m2-g2-transport.yml",
    ".work/schemas/transport-evaluation-trials-v1.schema.json",
    ".work/schemas/transport-phase-timings-v1.schema.json",
    ".work/schemas/transport-reset-environment-v1.schema.json",
    "core/engine/src/androidTest/java/io/github/definitelystable/spongetube/core/engine/route/TransportPairNetworkAndroidTest.kt",
    "core/engine/src/main/java/io/github/definitelystable/spongetube/core/engine/FetchBroker.kt",
    "core/engine/src/main/java/io/github/definitelystable/spongetube/core/engine/FetchEvidence.kt",
    "core/engine/src/main/java/io/github/definitelystable/spongetube/core/engine/HttpRangeFetchExecutor.kt",
    "core/engine/src/main/java/io/github/definitelystable/spongetube/core/engine/PlatformHttpRangeFetchExecutor.kt",
    "core/engine/src/main/java/io/github/definitelystable/spongetube/core/engine/TransportEvaluationSelector.kt",
    "core/engine/src/main/java/io/github/definitelystable/spongetube/core/engine/TransportPhaseObserver.kt",
    "core/engine/src/main/java/io/github/definitelystable/spongetube/core/engine/recovery/RecoveryCoordinator.kt",
    "scripts/ci/run-m2-g2-transport.sh",
    "scripts/ci/verify-m2-g2-network.py",
    "scripts/ci/verify-m2-g2-transport.py",
    "scripts/faults/install_toxiproxy.sh",
    "scripts/faults/m2_e0_netns.sh",
    "scripts/faults/m2_transport_harness.py",
    "scripts/faults/toxiproxy_control.py",
    "scripts/measurement/m2_contracts.py",
    "scripts/measurement/m2_transport_evaluation_oracle.py",
    "scripts/measurement/m2_transport_pair_plan.py",
    "scripts/measurement/m2_transport_reset_environment.py",
    "tools/fault-harness/toolchain.lock.json",
)


class TransportResetEnvironmentError(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise TransportResetEnvironmentError(message)


def load(path: pathlib.Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    require(isinstance(value, dict), f"{path}: expected JSON object")
    return value


def canonical_sha256(value: Any) -> str:
    encoded = json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=False
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def file_sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def code_fingerprint(root: pathlib.Path = ROOT) -> dict[str, Any]:
    require(
        list(CODE_PATHS) == sorted(CODE_PATHS),
        "CODE_PATHS must stay lexicographically sorted",
    )
    rows: list[dict[str, str]] = []
    for relative in CODE_PATHS:
        path = root / relative
        require(path.is_file(), f"experiment code path missing: {relative}")
        rows.append({"path": relative, "sha256": file_sha256(path)})
    return {"sha256": canonical_sha256(rows), "files": rows}


def pinned_toxiproxy(root: pathlib.Path = ROOT) -> Mapping[str, Any]:
    lock = load(root / "tools" / "fault-harness" / "toolchain.lock.json")
    tool = lock.get("toxiproxy")
    require(isinstance(tool, Mapping), "toxiproxy lock missing")
    require(tool.get("version") == "2.12.0", "toxiproxy version drift")
    require(
        tool.get("asset") == "toxiproxy-server-linux-amd64",
        "toxiproxy asset drift",
    )
    sha = tool.get("sha256")
    require(
        isinstance(sha, str)
        and len(sha) == 64
        and all(ch in "0123456789abcdef" for ch in sha),
        "toxiproxy lock SHA-256 invalid",
    )
    return tool


def validate_android_runtime(runtime: Mapping[str, Any]) -> None:
    require(runtime.get("deviceClass") == "ANDROID_EMULATOR", "G2-D requires emulator")
    require(runtime.get("androidApi") == 36, "G2-D requires API 36")
    require(runtime.get("abi") == "x86_64", "G2-D requires x86_64")
    for field in ("buildFingerprint", "buildId", "securityPatch", "kernelRelease"):
        value = runtime.get(field)
        require(isinstance(value, str) and value.strip(), f"runtime {field} missing")
    require(runtime.get("batteryPolicy") == "CI_POWERED", "battery policy drift")
    require(runtime.get("acPowered") is True, "AC-powered readback required")


def build(
    *,
    run_id: str,
    source_head_commit: str,
    checkout_commit: str,
    toxiproxy_sha256: str,
    android_runtime: Mapping[str, Any],
    root: pathlib.Path = ROOT,
) -> dict[str, Any]:
    require(
        isinstance(run_id, str) and run_id and len(run_id) <= 96,
        "runId missing or too long",
    )
    for value, label in (
        (source_head_commit, "sourceHeadCommit"),
        (checkout_commit, "checkoutCommit"),
    ):
        require(
            isinstance(value, str)
            and len(value) == 40
            and all(ch in "0123456789abcdef" for ch in value),
            f"{label} must be lowercase 40-hex",
        )
    validate_android_runtime(android_runtime)
    pinned = pinned_toxiproxy(root)
    require(
        toxiproxy_sha256 == pinned["sha256"],
        "executed Toxiproxy binary does not match lock",
    )
    doc = {
        "schemaVersion": 1,
        "runId": run_id,
        "sourceHeadCommit": source_head_commit,
        "checkoutCommit": checkout_commit,
        "harness": {
            "harnessId": "sponge-transport-harness",
            "harnessVersion": "1",
            "toolId": "toxiproxy",
            "toolVersion": pinned["version"],
            "asset": pinned["asset"],
            "binarySha256": toxiproxy_sha256,
        },
        "transportPath": {
            "mediaPath": "SCOPED_NAMESPACE_VETH",
            "proxyLifecycle": "FRESH_PER_TRIAL",
            "adbReverseUsed": False,
            "processWideNetworkBinding": False,
        },
        "androidRuntime": dict(android_runtime),
        "codeFingerprint": code_fingerprint(root),
        "limitations": [
            "GITHUB_HOSTED_RUNNER_AND_API36_EMULATOR_ENVIRONMENT_ONLY",
            "NO_PHYSICAL_DEVICE_PERFORMANCE_CLAIM",
            "TOXIPROXY_RESET_EFFECT_REQUIRES_PER_TRIAL_CAUSAL_EVIDENCE",
        ],
    }
    validate_instance(load(root / ".work" / "schemas" / "transport-reset-environment-v1.schema.json"), doc)
    scan_evidence_privacy(doc)
    return doc


def verify(
    document: Mapping[str, Any],
    *,
    android_runtime: Mapping[str, Any],
    root: pathlib.Path = ROOT,
) -> None:
    schema = load(root / ".work" / "schemas" / "transport-reset-environment-v1.schema.json")
    validate_instance(schema, document)
    scan_evidence_privacy(document)
    validate_android_runtime(android_runtime)
    pinned = pinned_toxiproxy(root)
    require(
        document["harness"]["binarySha256"] == pinned["sha256"],
        "environment Toxiproxy SHA does not match lock",
    )
    require(
        document["harness"]["toolVersion"] == pinned["version"]
        and document["harness"]["asset"] == pinned["asset"],
        "environment Toxiproxy identity drift",
    )
    require(document["androidRuntime"] == dict(android_runtime), "Android runtime binding drift")
    require(
        document["transportPath"] == {
            "mediaPath": "SCOPED_NAMESPACE_VETH",
            "proxyLifecycle": "FRESH_PER_TRIAL",
            "adbReverseUsed": False,
            "processWideNetworkBinding": False,
        },
        "transport path provenance drift",
    )
    require(
        document["codeFingerprint"] == code_fingerprint(root),
        "experiment code fingerprint drift",
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    build_parser = sub.add_parser("build")
    build_parser.add_argument("--run-id", required=True)
    build_parser.add_argument("--source-head-commit", required=True)
    build_parser.add_argument("--checkout-commit", required=True)
    build_parser.add_argument("--toxiproxy-sha256", required=True)
    build_parser.add_argument("--android-runtime", required=True, type=pathlib.Path)
    build_parser.add_argument("--output", required=True, type=pathlib.Path)

    verify_parser = sub.add_parser("verify")
    verify_parser.add_argument("--environment", required=True, type=pathlib.Path)
    verify_parser.add_argument("--android-runtime", required=True, type=pathlib.Path)

    args = parser.parse_args()
    runtime = load(args.android_runtime)
    if args.command == "build":
        doc = build(
            run_id=args.run_id,
            source_head_commit=args.source_head_commit,
            checkout_commit=args.checkout_commit,
            toxiproxy_sha256=args.toxiproxy_sha256,
            android_runtime=runtime,
        )
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(doc, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    else:
        verify(
            load(args.environment),
            android_runtime=runtime,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
