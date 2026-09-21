#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import sys
from datetime import datetime, timezone
from typing import Any

SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
GIT_SHA_RE = re.compile(r"^[0-9a-f]{40}$")

MODES = {"DIRECT", "STANDARD_CACHE", "SPONGE"}
CACHE_STATES = {"NONE", "COLD", "WARM"}
REQUESTED_TRANSPORTS = {"RECOMMENDED_PLATFORM", "DEFAULT_HTTP"}
EFFECTIVE_TRANSPORTS = {"HTTP_ENGINE", "DEFAULT_HTTP"}
STARTUP_MODES = {"COLD", "WARM", "HOT", "NOT_APPLICABLE"}
RESULT_STATUSES = {"COMPLETE", "PARTIAL", "INVALID"}


def file_sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def require_sha256(name: str, value: str) -> str:
    if not SHA256_RE.fullmatch(value):
        raise ValueError(f"{name} must be a lowercase SHA-256 hex digest")
    return value


def require_git_sha(value: str) -> str:
    if not GIT_SHA_RE.fullmatch(value):
        raise ValueError("gitCommit must be a 40-character lowercase Git SHA")
    return value


def require_enum(name: str, value: str, allowed: set[str]) -> str:
    if value not in allowed:
        raise ValueError(
            f"{name}={value!r} is invalid; expected one of {sorted(allowed)}"
        )
    return value


def parse_bool(value: str) -> bool:
    normalized = value.strip().lower()
    if normalized == "true":
        return True
    if normalized == "false":
        return False
    raise argparse.ArgumentTypeError("expected true or false")


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def write_json(path: pathlib.Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(payload, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def build_manifest(args: argparse.Namespace) -> dict[str, Any]:
    fixture_manifest = pathlib.Path(args.fixture_manifest)
    if not fixture_manifest.is_file():
        raise ValueError(f"fixture manifest not found: {fixture_manifest}")

    manifest_sha = file_sha256(fixture_manifest)

    return {
        "schemaVersion": 1,
        "runId": args.run_id,
        "createdAtUtc": args.created_at_utc or utc_now(),
        "gitCommit": require_git_sha(args.git_commit),
        "fixture": {
            "fixtureId": args.fixture_id,
            "manifestSha256": manifest_sha,
            "resourceSha256": require_sha256(
                "resourceSha256",
                args.fixture_resource_sha256,
            ),
        },
        "scenario": {
            "scenarioId": args.scenario_id,
            "scenarioHash": require_sha256(
                "scenarioHash",
                args.scenario_hash,
            ),
        },
        "playback": {
            "mode": require_enum("mode", args.mode, MODES),
            "cacheState": require_enum(
                "cacheState",
                args.cache_state,
                CACHE_STATES,
            ),
            "requestedTransport": require_enum(
                "requestedTransport",
                args.requested_transport,
                REQUESTED_TRANSPORTS,
            ),
            "effectiveTransport": require_enum(
                "effectiveTransport",
                args.effective_transport,
                EFFECTIVE_TRANSPORTS,
            ),
        },
        "build": {
            "buildType": args.build_type,
            "debuggable": args.debuggable,
            "profileable": args.profileable,
            "minify": args.minify,
            "compilationMode": args.compilation_mode,
            "startupMode": require_enum(
                "startupMode",
                args.startup_mode,
                STARTUP_MODES,
            ),
        },
        "device": {
            "apiLevel": args.device_api,
            "fingerprint": args.device_fingerprint,
            "model": args.device_model,
            "abi": args.device_abi,
        },
        "toolchain": {
            "media3Version": args.media3_version,
            "benchmarkVersion": args.benchmark_version,
        },
        "orderSeed": args.order_seed,
        "clockDomains": {
            "hostMonotonic": "MEDIA_LAB_MONOTONIC_NS",
            "androidMonotonic": "SYSTEM_CLOCK_ELAPSED_REALTIME_NS",
            "crossDomainSubtractionAllowed": False,
        },
    }


def build_result(args: argparse.Namespace) -> dict[str, Any]:
    status = require_enum("status", args.status, RESULT_STATUSES)
    seek_samples = [
        int(value)
        for value in args.seek_to_frame_ns
    ]
    for value in seek_samples:
        if value < 0:
            raise ValueError("seekToFrameNs values must be >= 0")

    values = {
        "stallCount": args.stall_count,
        "stallTotalNs": args.stall_total_ns,
        "requestCount": args.request_count,
        "networkBytes": args.network_bytes,
        "uniqueRangeBytes": args.unique_range_bytes,
        "duplicateRangeBytes": args.duplicate_range_bytes,
        "httpErrorCount": args.http_error_count,
    }
    for name, value in values.items():
        if value < 0:
            raise ValueError(f"{name} must be >= 0")

    if args.ttff_ns is not None and args.ttff_ns < 0:
        raise ValueError("ttffNs must be >= 0")

    if args.unique_range_bytes > args.network_bytes:
        raise ValueError("uniqueRangeBytes cannot exceed networkBytes")
    if (
        args.network_bytes - args.unique_range_bytes
        != args.duplicate_range_bytes
    ):
        raise ValueError(
            "duplicateRangeBytes must equal "
            "networkBytes - uniqueRangeBytes"
        )

    limitations = list(args.limitation)
    if status != "COMPLETE" and not limitations:
        raise ValueError(
            "PARTIAL/INVALID results require at least one limitation"
        )

    return {
        "schemaVersion": 1,
        "runId": args.run_id,
        "status": status,
        "playback": {
            "ttffNs": args.ttff_ns,
            "stallCount": args.stall_count,
            "stallTotalNs": args.stall_total_ns,
            "seekToFrameNs": seek_samples,
            "playbackErrorCodes": list(args.playback_error_code),
        },
        "network": {
            "requestCount": args.request_count,
            "networkBytes": args.network_bytes,
            "uniqueRangeBytes": args.unique_range_bytes,
            "duplicateRangeBytes": args.duplicate_range_bytes,
            "httpErrorCount": args.http_error_count,
        },
        "labAccuracy": None,
        "limitations": limitations,
    }


def add_common_output(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--output", required=True, type=pathlib.Path)


def manifest_parser(subparsers: Any) -> None:
    parser = subparsers.add_parser("manifest")
    add_common_output(parser)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--created-at-utc")
    parser.add_argument("--git-commit", required=True)
    parser.add_argument("--fixture-id", required=True)
    parser.add_argument("--fixture-manifest", required=True)
    parser.add_argument("--fixture-resource-sha256", required=True)
    parser.add_argument("--scenario-id", required=True)
    parser.add_argument("--scenario-hash", required=True)
    parser.add_argument("--mode", required=True)
    parser.add_argument("--cache-state", required=True)
    parser.add_argument("--requested-transport", required=True)
    parser.add_argument("--effective-transport", required=True)
    parser.add_argument("--build-type", required=True)
    parser.add_argument("--debuggable", required=True, type=parse_bool)
    parser.add_argument("--profileable", required=True, type=parse_bool)
    parser.add_argument("--minify", required=True, type=parse_bool)
    parser.add_argument("--compilation-mode", required=True)
    parser.add_argument("--startup-mode", required=True)
    parser.add_argument("--device-api", required=True, type=int)
    parser.add_argument("--device-fingerprint", required=True)
    parser.add_argument("--device-model")
    parser.add_argument("--device-abi")
    parser.add_argument("--media3-version", required=True)
    parser.add_argument("--benchmark-version", required=True)
    parser.add_argument("--order-seed", required=True, type=int)
    parser.set_defaults(builder=build_manifest)


def result_parser(subparsers: Any) -> None:
    parser = subparsers.add_parser("result")
    add_common_output(parser)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--status", required=True)
    parser.add_argument("--ttff-ns", type=int)
    parser.add_argument("--stall-count", required=True, type=int)
    parser.add_argument("--stall-total-ns", required=True, type=int)
    parser.add_argument(
        "--seek-to-frame-ns",
        action="append",
        default=[],
    )
    parser.add_argument(
        "--playback-error-code",
        action="append",
        default=[],
    )
    parser.add_argument("--request-count", required=True, type=int)
    parser.add_argument("--network-bytes", required=True, type=int)
    parser.add_argument("--unique-range-bytes", required=True, type=int)
    parser.add_argument("--duplicate-range-bytes", required=True, type=int)
    parser.add_argument("--http-error-count", required=True, type=int)
    parser.add_argument(
        "--limitation",
        action="append",
        default=[],
    )
    parser.set_defaults(builder=build_result)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Compose SpongeTube M0 measurement artifacts."
    )
    subparsers = parser.add_subparsers(dest="command", required=True)
    manifest_parser(subparsers)
    result_parser(subparsers)

    args = parser.parse_args(argv)

    try:
        payload = args.builder(args)
        write_json(args.output, payload)
    except (OSError, ValueError) as error:
        parser.error(str(error))

    return 0


if __name__ == "__main__":
    sys.exit(main())
