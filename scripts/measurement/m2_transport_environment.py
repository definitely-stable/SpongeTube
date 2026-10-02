#!/usr/bin/env python3
"""Build/verify M2-G2 transport experiment environment evidence.

This artifact binds the already-frozen M2-E fault-engine fingerprint to the
actual Android runtime and the exact repository code used by G2. It is
descriptive evidence only: it does not rank or select a transport backend.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys
from typing import Any, Mapping

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCHEMA = ROOT / ".work" / "schemas" / "transport-experiment-environment-v1.schema.json"
sys.path.insert(0, str(ROOT / "scripts" / "measurement"))

from m2_contracts import scan_evidence_privacy  # noqa: E402
from schema_subset import validate_instance  # noqa: E402

CODE_PATHS = (
    ".github/workflows/m2-g2-network.yml",
    ".work/schemas/transport-experiment-environment-v1.schema.json",
    ".work/schemas/transport-phase-timings-v1.schema.json",
    "core/engine/src/androidTest/java/io/github/definitelystable/spongetube/core/engine/route/TransportPairNetworkAndroidTest.kt",
    "core/engine/src/main/java/io/github/definitelystable/spongetube/core/engine/FetchBroker.kt",
    "core/engine/src/main/java/io/github/definitelystable/spongetube/core/engine/HttpRangeFetchExecutor.kt",
    "core/engine/src/main/java/io/github/definitelystable/spongetube/core/engine/PlatformHttpRangeFetchExecutor.kt",
    "core/engine/src/main/java/io/github/definitelystable/spongetube/core/engine/TransportEvaluationSelector.kt",
    "core/engine/src/main/java/io/github/definitelystable/spongetube/core/engine/TransportPhaseObserver.kt",
    "core/engine/src/main/java/io/github/definitelystable/spongetube/core/engine/recovery/RecoveryCoordinator.kt",
    "scripts/ci/run-m2-g2-network.sh",
    "scripts/ci/verify-m2-g2-network.py",
    "scripts/faults/m2_network_environment.py",
    "scripts/faults/m2_network_harness.py",
    "scripts/faults/netem_control.sh",
    "scripts/measurement/m2_transport_environment.py",
    "scripts/measurement/m2_transport_evaluation_oracle.py",
    "scripts/measurement/m2_transport_pair_plan.py",
)


class TransportEnvironmentError(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise TransportEnvironmentError(message)


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
    files: list[dict[str, str]] = []
    for relative in CODE_PATHS:
        path = root / relative
        require(path.is_file(), f"experiment code path missing: {relative}")
        files.append({"path": relative, "sha256": file_sha256(path)})
    require(
        [row["path"] for row in files] == sorted(row["path"] for row in files),
        "CODE_PATHS must stay lexicographically sorted",
    )
    return {"sha256": canonical_sha256(files), "files": files}


def validate_fault_engine(fingerprint: Mapping[str, Any]) -> None:
    require(fingerprint.get("schemaVersion") == 1, "fault-engine fingerprint version drift")
    offloads = fingerprint.get("offloads")
    require(
        offloads == {"gro": False, "gso": False, "tso": False},
        "G2 packetization fidelity requires GRO/GSO/TSO disabled",
    )


def validate_link_state(link_state: Mapping[str, Any], fault_engine: Mapping[str, Any]) -> None:
    mtu = link_state.get("mtu")
    require(
        type(mtu) is int and 576 <= mtu <= 65_535,
        "media link MTU missing or outside accepted range",
    )
    offloads = link_state.get("offloads")
    require(
        offloads == {"gro": False, "gso": False, "tso": False},
        "live media link offloads are not canonical",
    )
    require(
        offloads == fault_engine.get("offloads"),
        "live media link offloads drifted from fault-engine fingerprint",
    )


def validate_device_runtime(runtime: Mapping[str, Any]) -> None:
    require(runtime.get("deviceClass") == "ANDROID_EMULATOR", "G2-C requires emulator runtime")
    require(runtime.get("androidApi") == 36, "G2-C requires Android API 36")
    require(runtime.get("abi") == "x86_64", "G2-C requires x86_64 emulator")
    for field in ("buildFingerprint", "buildId", "securityPatch", "kernelRelease"):
        value = runtime.get(field)
        require(isinstance(value, str) and value.strip(), f"Android runtime {field} missing")
    require(runtime.get("batteryPolicy") == "CI_POWERED", "G2-C requires CI_POWERED battery policy")
    require(runtime.get("acPowered") is True, "G2-C requires independent AC-powered readback")


def build(
    *,
    run_id: str,
    source_head_commit: str,
    checkout_commit: str,
    fault_engine: Mapping[str, Any],
    link_state: Mapping[str, Any],
    android_runtime: Mapping[str, Any],
    root: pathlib.Path = ROOT,
) -> dict[str, Any]:
    require(
        isinstance(run_id, str) and run_id and len(run_id) <= 96,
        "runId missing or too long",
    )
    for value, name in (
        (source_head_commit, "sourceHeadCommit"),
        (checkout_commit, "checkoutCommit"),
    ):
        require(
            isinstance(value, str)
            and len(value) == 40
            and all(ch in "0123456789abcdef" for ch in value),
            f"{name} must be a lowercase 40-hex commit",
        )
    validate_fault_engine(fault_engine)
    validate_link_state(link_state, fault_engine)
    validate_device_runtime(android_runtime)

    document = {
        "schemaVersion": 1,
        "runId": run_id,
        "sourceHeadCommit": source_head_commit,
        "checkoutCommit": checkout_commit,
        "faultEngineFingerprintSha256": canonical_sha256(fault_engine),
        "mediaLink": {
            "mtu": link_state["mtu"],
            "offloads": dict(link_state["offloads"]),
            "packetizationFidelity": "GRO_GSO_TSO_DISABLED",
        },
        "androidRuntime": {
            key: android_runtime[key]
            for key in (
                "deviceClass",
                "androidApi",
                "abi",
                "buildFingerprint",
                "buildId",
                "securityPatch",
                "kernelRelease",
                "batteryPolicy",
                "acPowered",
            )
        },
        "codeFingerprint": code_fingerprint(root),
        "limitations": [
            "GITHUB_HOSTED_RUNNER_AND_API36_EMULATOR_ENVIRONMENT_ONLY",
            "NO_PHYSICAL_DEVICE_PERFORMANCE_CLAIM",
            "NETEM_SEED_BINDS_CONFIGURATION_NOT_BIT_EXACT_EFFECT_REPLAY",
        ],
    }
    validate_instance(load(SCHEMA), document)
    scan_evidence_privacy(document)
    return document


def verify(
    document: Mapping[str, Any],
    *,
    fault_engine: Mapping[str, Any],
    link_state: Mapping[str, Any],
    android_runtime: Mapping[str, Any],
    root: pathlib.Path = ROOT,
) -> None:
    validate_instance(load(SCHEMA), document)
    scan_evidence_privacy(document)
    validate_fault_engine(fault_engine)
    validate_link_state(link_state, fault_engine)
    validate_device_runtime(android_runtime)
    require(
        document["faultEngineFingerprintSha256"] == canonical_sha256(fault_engine),
        "fault-engine fingerprint binding drift",
    )
    require(
        document["mediaLink"] == {
            "mtu": link_state["mtu"],
            "offloads": dict(link_state["offloads"]),
            "packetizationFidelity": "GRO_GSO_TSO_DISABLED",
        },
        "media link environment drift",
    )
    require(document["androidRuntime"] == {
        key: android_runtime[key]
        for key in (
            "deviceClass",
            "androidApi",
            "abi",
            "buildFingerprint",
            "buildId",
            "securityPatch",
            "kernelRelease",
            "batteryPolicy",
            "acPowered",
        )
    }, "Android runtime binding drift")
    require(document["codeFingerprint"] == code_fingerprint(root), "experiment code fingerprint drift")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    build_parser = sub.add_parser("build")
    build_parser.add_argument("--run-id", required=True)
    build_parser.add_argument("--source-head-commit", required=True)
    build_parser.add_argument("--checkout-commit", required=True)
    build_parser.add_argument("--fault-engine-fingerprint", required=True, type=pathlib.Path)
    build_parser.add_argument("--link-state", required=True, type=pathlib.Path)
    build_parser.add_argument("--android-runtime", required=True, type=pathlib.Path)
    build_parser.add_argument("--output", required=True, type=pathlib.Path)

    verify_parser = sub.add_parser("verify")
    verify_parser.add_argument("--environment", required=True, type=pathlib.Path)
    verify_parser.add_argument("--fault-engine-fingerprint", required=True, type=pathlib.Path)
    verify_parser.add_argument("--link-state", required=True, type=pathlib.Path)
    verify_parser.add_argument("--android-runtime", required=True, type=pathlib.Path)

    args = parser.parse_args()
    fault_engine = load(args.fault_engine_fingerprint)
    link_state = load(args.link_state)
    android_runtime = load(args.android_runtime)

    if args.command == "build":
        document = build(
            run_id=args.run_id,
            source_head_commit=args.source_head_commit,
            checkout_commit=args.checkout_commit,
            fault_engine=fault_engine,
            link_state=link_state,
            android_runtime=android_runtime,
        )
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(document, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    else:
        verify(
            load(args.environment),
            fault_engine=fault_engine,
            link_state=link_state,
            android_runtime=android_runtime,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
