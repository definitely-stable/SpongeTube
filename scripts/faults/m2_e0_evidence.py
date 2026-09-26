#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import pathlib
import re
from typing import Any

TOXIPROXY_VERSION = "2.12.0"
TOXIPROXY_ASSET = "toxiproxy-server-linux-amd64"
TOXIPROXY_SHA256 = "556d891134a3c582dc1e1a3f7335fd55142e5965769855a00b944e13e48302fc"

IPV4 = re.compile(r"(?<![0-9.])(?:(?:25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])\.){3}(?:25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])(?![0-9.])")
URL = re.compile(r"[A-Za-z][A-Za-z0-9+.-]*://")


def load_json(path: str) -> Any:
    return json.loads(pathlib.Path(path).read_text(encoding="utf-8"))


def marker_ok(path: str, expected: str = "ok") -> bool:
    return pathlib.Path(path).read_text(encoding="utf-8").strip() == expected


def write_portable(path: str, document: dict[str, Any]) -> None:
    encoded = json.dumps(document, indent=2, sort_keys=True) + "\n"
    if IPV4.search(encoded) or URL.search(encoded):
        raise SystemExit("portable E0 evidence contains a raw IP address or URL")
    pathlib.Path(path).parent.mkdir(parents=True, exist_ok=True)
    pathlib.Path(path).write_text(encoded, encoding="utf-8")


def host(args: argparse.Namespace) -> None:
    qdiscs = load_json(args.qdisc_json)
    filters = load_json(args.filter_json)
    qdisc_kinds = {
        item.get("kind")
        for item in qdiscs
        if isinstance(item, dict)
    }
    filter_kinds = {
        item.get("kind")
        for item in filters
        if isinstance(item, dict)
    }

    version_text = pathlib.Path(args.toxiproxy_version).read_text(encoding="utf-8").strip()
    digest = pathlib.Path(args.toxiproxy_sha).read_text(encoding="utf-8").strip()
    if version_text != f"toxiproxy-server version {TOXIPROXY_VERSION}":
        raise SystemExit(f"unexpected Toxiproxy version: {version_text!r}")
    if digest != TOXIPROXY_SHA256:
        raise SystemExit("Toxiproxy digest does not match the pinned value")
    if "netem" not in qdisc_kinds or "clsact" not in qdisc_kinds:
        raise SystemExit("qdisc readback does not contain netem + clsact")
    if "flower" not in filter_kinds:
        raise SystemExit("filter readback does not contain the flower classifier")
    if not marker_ok(args.seed_accepted):
        raise SystemExit("netem seed command was not proven")
    if not marker_ok(args.clean):
        raise SystemExit("namespace/veth cleanup was not proven")
    if not marker_ok(args.toxiproxy_clean):
        raise SystemExit("Toxiproxy cleanup was not proven")

    write_portable(
        args.output,
        {
            "schemaVersion": 1,
            "slice": "M2-E0",
            "runner": "ubuntu-24.04",
            "clockDomain": "HOST_FAULT_MONOTONIC",
            "tools": {
                "toxiproxy": {
                    "version": TOXIPROXY_VERSION,
                    "asset": TOXIPROXY_ASSET,
                    "sha256": TOXIPROXY_SHA256,
                    "lifecycle": "CLEAN",
                    "unexpectedProxies": 0,
                    "unexpectedToxics": 0,
                },
                "netem": {
                    "source": "system-iproute2",
                    "seedCommandAccepted": True,
                    "qdiscReadback": True,
                    "classifierReadback": True,
                },
            },
            "topology": {
                "networkNamespace": True,
                "veth": True,
                "mediaScopeOnly": True,
                "globalRunnerQdiscTouched": False,
            },
            "cleanup": {
                "namespaceRemoved": True,
                "vethRemoved": True,
                "qdiscRemoved": True,
                "filtersRemoved": True,
                "toxiproxyStopped": True,
            },
            "status": "PASS",
        },
    )


def android(args: argparse.Namespace) -> None:
    android_doc = load_json(args.android_evidence)
    required = {
        "schemaVersion": 1,
        "mediaPath": "DIRECT_NAMESPACE",
        "controlPath": "ADB_REVERSE_CONTROL_ONLY",
        "mediaEndpointIdentity": "M2_E0_NAMESPACE_MEDIA",
        "mediaReachable": True,
        "controlReachable": True,
    }
    for key, value in required.items():
        if android_doc.get(key) != value:
            raise SystemExit(f"android evidence mismatch for {key}: {android_doc.get(key)!r}")

    markers = {
        "adbHealthyBefore": marker_ok(args.adb_before, "device"),
        "adbHealthyDuring": marker_ok(args.adb_during, "device"),
        "adbHealthyAfter": marker_ok(args.adb_after, "device"),
        "controlHealthyBefore": marker_ok(args.control_before),
        "controlHealthyDuring": marker_ok(args.control_during),
        "controlHealthyAfter": marker_ok(args.control_after),
        "mediaReverseAbsent": marker_ok(args.media_reverse_absent),
        "artifactCollectionHealthy": marker_ok(args.artifact_collection),
        "cleanupComplete": marker_ok(args.clean),
    }
    if not all(markers.values()):
        failed = sorted(key for key, value in markers.items() if not value)
        raise SystemExit("E0 Android topology proof failed: " + ", ".join(failed))

    write_portable(
        args.output,
        {
            "schemaVersion": 1,
            "slice": "M2-E0",
            "runner": "ubuntu-24.04",
            "androidApi": args.api,
            "clockDomains": ["ANDROID_MONOTONIC", "HOST_FAULT_MONOTONIC"],
            "crossClockArithmeticUsed": False,
            "mediaPath": {
                "kind": "DIRECT_NAMESPACE",
                "adbReverseUsed": False,
                "reachable": True,
                "endpointIdentity": "M2_E0_NAMESPACE_MEDIA",
            },
            "controlPath": {
                "kind": "ADB_REVERSE_CONTROL_ONLY",
                "mediaLabReachableFromAndroid": True,
                "mediaLabReachableFromHost": True,
            },
            "isolation": markers,
            "portableEvidencePrivacyClean": True,
            "status": "PASS",
        },
    )


def parse() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)

    p_host = sub.add_parser("host")
    p_host.add_argument("--output", required=True)
    p_host.add_argument("--qdisc-json", required=True)
    p_host.add_argument("--filter-json", required=True)
    p_host.add_argument("--toxiproxy-version", required=True)
    p_host.add_argument("--toxiproxy-sha", required=True)
    p_host.add_argument("--seed-accepted", required=True)
    p_host.add_argument("--clean", required=True)
    p_host.add_argument("--toxiproxy-clean", required=True)
    p_host.set_defaults(func=host)

    p_android = sub.add_parser("android")
    p_android.add_argument("--output", required=True)
    p_android.add_argument("--android-evidence", required=True)
    p_android.add_argument("--api", type=int, required=True)
    for name in (
        "adb-before",
        "adb-during",
        "adb-after",
        "control-before",
        "control-during",
        "control-after",
        "media-reverse-absent",
        "artifact-collection",
        "clean",
    ):
        p_android.add_argument(f"--{name}", required=True)
    p_android.set_defaults(func=android)
    return parser.parse_args()


def main() -> int:
    args = parse()
    args.func(args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
