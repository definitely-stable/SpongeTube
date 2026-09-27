#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[2]
LOCK_PATH = REPO_ROOT / "tools" / "fault-harness" / "toolchain.lock.json"
EXPECTED_SEED = 424242
EXPECTED_MEDIA_SHA256 = hashlib.sha256(b"SPONGETUBE_M2_E0_MEDIA_V1\n").hexdigest()
EXPECTED_CONTROL_SHA256 = hashlib.sha256(b"SPONGETUBE_M2_E0_CONTROL_V1\n").hexdigest()


def fail(message: str) -> None:
    raise SystemExit(f"M2-E0 verification failed: {message}")


def read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"cannot read valid JSON {path}: {exc}")


def read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8").strip()
    except OSError as exc:
        fail(f"cannot read {path}: {exc}")


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def find_kind(value: Any, kind: str) -> dict[str, Any] | None:
    if isinstance(value, dict):
        if value.get("kind") == kind:
            return value
        for child in value.values():
            found = find_kind(child, kind)
            if found is not None:
                return found
    elif isinstance(value, list):
        for child in value:
            found = find_kind(child, kind)
            if found is not None:
                return found
    return None


def packet_count(value: dict[str, Any]) -> int:
    packets = value.get("packets")
    if isinstance(packets, int):
        return packets
    stats = value.get("stats")
    if isinstance(stats, dict) and isinstance(stats.get("packets"), int):
        return int(stats["packets"])
    stats2 = value.get("stats2")
    if isinstance(stats2, dict):
        basic = stats2.get("basic")
        if isinstance(basic, dict) and isinstance(basic.get("packets"), int):
            return int(basic["packets"])
    for child in value.values():
        if isinstance(child, dict):
            result = packet_count(child)
            if result >= 0:
                return result
    return -1


def version_tuple(value: str) -> tuple[int, int]:
    match = re.match(r"^(\d+)\.(\d+)", value)
    if not match:
        fail(f"cannot parse version: {value!r}")
    return int(match.group(1)), int(match.group(2))


def verify_toolchain(root: Path) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    lock = read_json(LOCK_PATH)
    tc = read_json(root / "tc.json")
    toxiproxy = read_json(root / "toxiproxy.json") if (root / "toxiproxy.json").exists() else {}

    tc_lock = lock["iproute2Tc"]
    require(tc.get("tool") == "tc", "harness tc evidence has wrong tool")
    require(tc.get("iproute2Version") == tc_lock["version"], "harness tc version drift")
    require(tc.get("tag") == f"v{tc_lock['version']}", "harness tc tag drift")
    require(tc.get("gitCommit") == tc_lock["gitCommit"], "harness tc commit drift")
    require(tc.get("systemPackageReplacement") is False, "harness tc replaced the system package")
    require(
        isinstance(tc.get("binarySha256"), str) and len(tc["binarySha256"]) == 64,
        "harness tc binary hash missing",
    )

    if toxiproxy:
        tox_lock = lock["toxiproxy"]
        require(toxiproxy.get("version") == tox_lock["version"], "wrong Toxiproxy version")
        require(toxiproxy.get("sha256") == tox_lock["sha256"], "wrong Toxiproxy SHA-256")
        require(toxiproxy.get("sha256Matches") is True, "Toxiproxy checksum did not match")
        require(toxiproxy.get("apiHealthy") is True, "Toxiproxy API was not healthy")
        require(toxiproxy.get("proxiesEmpty") is True, "Toxiproxy retained an unexpected proxy")
        require(toxiproxy.get("processStopped") is True, "Toxiproxy process did not stop")

    return lock, tc, toxiproxy


def verify_host(root: Path) -> None:
    lock, tc, toxiproxy = verify_toolchain(root)
    netem = read_json(root / "netem-seed.json")
    filter_state = read_json(root / "filter.json")
    kernel_release = read_text(root / "kernel-release.txt")
    system_tc = read_text(root / "system-tc-version.txt")

    kernel = version_tuple(kernel_release)
    minimum_kernel = version_tuple(lock["runner"]["requiredKernelMinimum"])
    require(kernel >= minimum_kernel, f"kernel {kernel_release} is below required {minimum_kernel}")
    require("iproute2-6.1.0" in system_tc, "unexpected Noble system tc baseline")

    netem_qdisc = find_kind(netem, "netem")
    require(netem_qdisc is not None, "netem qdisc is absent from seed readback")
    require(int(netem_qdisc.get("seed", -1)) == EXPECTED_SEED, "netem seed was not read back")

    flower = find_kind(filter_state, "flower")
    require(flower is not None, "flower classifier was not read back")
    require(bool(toxiproxy), "Toxiproxy evidence is missing")

    summary = {
        "schemaVersion": 1,
        "phase": "E0_HOST_CAPABILITY",
        "runner": lock["runner"]["label"],
        "kernelRelease": kernel_release,
        "systemTcVersion": system_tc,
        "harnessTcVersion": tc["iproute2Version"],
        "harnessTcCommit": tc["gitCommit"],
        "sudoAvailable": True,
        "netnsVethAvailable": True,
        "tcJsonAvailable": True,
        "tcFlowerAvailable": True,
        "netemSeedSupported": True,
        "seed": EXPECTED_SEED,
        "toxiproxyVersion": toxiproxy["version"],
        "toxiproxySha256Matches": True,
        "status": "PASS",
    }
    (root / "host-summary.json").write_text(
        json.dumps(summary, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    try:
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                rows.append(json.loads(line))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"cannot read valid JSONL {path}: {exc}")
    return rows


def verify_android(root: Path) -> None:
    _, tc, _ = verify_toolchain(root)
    android = read_json(root / "android" / "evidence.json")
    host = read_json(root / "host-state.json")
    cleanup = read_json(root / "cleanup-state.json")
    before = read_json(root / "qdisc-before.json")
    after = read_json(root / "qdisc-after.json")
    requests = read_jsonl(root / "media-requests.jsonl")

    require(android.get("schemaVersion") == 1, "Android evidence schema mismatch")
    require(android.get("mediaPathMode") == "GUEST_OUTBOUND_ROUTED_VETH_NAMESPACE", "wrong media path mode")
    require(android.get("mediaUsesAdbReverse") is False, "media path claims adb reverse")
    require(android.get("controlUsesAdbReverse") is True, "control path did not use the isolated reverse")
    require(android.get("mediaOk") is True, "Android media probe failed")
    require(android.get("controlOk") is True, "Android control probe failed")
    require(android.get("mediaBodySha256") == EXPECTED_MEDIA_SHA256, "Android media body mismatch")
    require(android.get("controlBodySha256") == EXPECTED_CONTROL_SHA256, "Android control body mismatch")

    for key in ("adbBefore", "adbDuring", "adbAfter", "mediaLabBefore", "mediaLabDuring", "mediaLabAfter"):
        require(host.get(key) is True, f"host health proof missing: {key}")
    require(host.get("controlReversePresent") is True, "control reverse was not present")
    require(host.get("mediaReverseAbsent") is True, "media port appeared in adb reverse")

    before_netem = find_kind(before, "netem")
    after_netem = find_kind(after, "netem")
    require(before_netem is not None and after_netem is not None, "netem readback missing around Android probe")
    require(int(before_netem.get("seed", -1)) == EXPECTED_SEED, "pre-probe netem seed mismatch")
    require(int(after_netem.get("seed", -1)) == EXPECTED_SEED, "post-probe netem seed mismatch")
    before_packets = packet_count(before_netem)
    after_packets = packet_count(after_netem)
    require(before_packets >= 0 and after_packets > before_packets, "netem packet counter did not increase")

    media_rows = [row for row in requests if row.get("role") == "media" and row.get("path") == "/probe"]
    require(len(media_rows) >= 2, "namespace media server did not observe readiness + Android requests")

    require(cleanup.get("namespacePresent") is False, "namespace survived teardown")
    require(cleanup.get("hostVethPresent") is False, "host veth survived teardown")
    require(cleanup.get("netemPresent") is False, "netem survived teardown")
    require(cleanup.get("reverseClean") is True, "adb reverse entry survived teardown")
    require(cleanup.get("childProcessesStopped") is True, "harness child process survived teardown")

    device_api = int(android.get("deviceApi", -1))
    require(device_api == 36, "canonical E0 topology proof must run on API 36")

    summary = {
        "schemaVersion": 1,
        "phase": "E0_ANDROID_TOPOLOGY",
        "deviceApi": device_api,
        "topology": "GUEST_OUTBOUND_ROUTED_VETH_NAMESPACE",
        "mediaPathUsesAdbReverse": False,
        "controlPathUsesAdbReverse": True,
        "harnessTcVersion": tc["iproute2Version"],
        "harnessTcCommit": tc["gitCommit"],
        "seed": EXPECTED_SEED,
        "qdiscPacketsBefore": before_packets,
        "qdiscPacketsAfter": after_packets,
        "adbHealthy": True,
        "mediaLabControlHealthy": True,
        "cleanupClean": True,
        "status": "PASS",
    }
    (root / "topology-summary.json").write_text(
        json.dumps(summary, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("host", "android"))
    parser.add_argument("root", type=Path)
    args = parser.parse_args()

    if args.mode == "host":
        verify_host(args.root)
    else:
        verify_android(args.root)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
