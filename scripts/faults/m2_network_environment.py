#!/usr/bin/env python3
"""Produce the canonical M2-E NETWORK fault-engine environment fingerprint."""
from __future__ import annotations

import argparse
import json
import pathlib
import platform
import subprocess
import sys
from typing import Any

SCRIPT_DIR = pathlib.Path(__file__).resolve().parent
MEASUREMENT_DIR = SCRIPT_DIR.parent / "measurement"
sys.path.insert(0, str(MEASUREMENT_DIR))

from m2_contracts import scan_evidence_privacy  # noqa: E402
from schema_subset import validate_instance  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCHEMA = ROOT / ".work" / "schemas" / "fault-engine-fingerprint-v1.schema.json"
LOCK = ROOT / "tools" / "fault-harness" / "toolchain.lock.json"


def load(path: pathlib.Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def command(*args: str) -> str:
    result = subprocess.run(
        args,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if result.returncode != 0:
        raise RuntimeError(
            f"command failed ({result.returncode}): "
            f"{result.stderr.strip() or result.stdout.strip()}"
        )
    return result.stdout.strip()


def os_release() -> dict[str, str]:
    values: dict[str, str] = {}
    for line in pathlib.Path("/etc/os-release").read_text(encoding="utf-8").splitlines():
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key] = value.strip().strip('"')
    return values


def build(args: argparse.Namespace) -> dict[str, Any]:
    locked = load(LOCK)["netem"]["labTc"]
    actual_source_sha = args.tc_source_sha.read_text(encoding="utf-8").strip()
    if actual_source_sha != locked["sha256"]:
        raise RuntimeError("fault-engine tc source SHA does not match toolchain lock")

    tc_version = command(str(args.tc_bin), "-V")
    if not tc_version.startswith(f"tc utility, iproute2-{locked['version']}"):
        raise RuntimeError(f"unexpected canonical tc version: {tc_version}")

    offloads = json.loads(command(str(SCRIPT_DIR / "netem_control.sh"), "inspect-offloads"))
    if offloads != {"gro": False, "gso": False, "tso": False}:
        raise RuntimeError(f"canonical packetization offloads are not disabled: {offloads}")

    release = os_release()
    document = {
        "schemaVersion": 1,
        "runner": {
            "imageOs": args.runner_image_os,
            "imageVersion": args.runner_image_version,
            "osId": release.get("ID", "unknown"),
            "osVersionId": release.get("VERSION_ID", "unknown"),
            "kernelRelease": platform.release(),
            "kernelMachine": platform.machine(),
        },
        "toolchain": {
            "tcVersion": tc_version,
            "tcSourceSha256": actual_source_sha,
            "ethtoolVersion": command("ethtool", "--version"),
        },
        "offloads": offloads,
        "limitations": [
            "GitHub-hosted runner fault-engine fingerprint; not a physical-network performance claim."
        ],
    }
    validate_instance(load(SCHEMA), document)
    scan_evidence_privacy(document)
    return document


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tc-bin", required=True, type=pathlib.Path)
    parser.add_argument("--tc-source-sha", required=True, type=pathlib.Path)
    parser.add_argument("--runner-image-os", required=True)
    parser.add_argument("--runner-image-version", required=True)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()

    document = build(args)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(document, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
