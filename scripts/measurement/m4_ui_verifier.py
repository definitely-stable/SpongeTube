#!/usr/bin/env python3
"""Independent M4-A1c debug UI evidence verifier.

MPD+fixture manifest, origin requests, Room SQLite and hashed published bytes
are independent of the Compose UI state. An inaccessible or unmeasured
CoverageIndex must never be represented as 0 or as a completed target.
"""

import argparse
import json
import pathlib
from typing import Any, Mapping

from m1_fetch_evidence import read_jsonl
from m4_reserve_verifier import expected_f1_units, verify_origin, verify_storage

SESSION = "m4-a1c-f1-debug"


def verify_ui_summary(summary: Mapping[str, Any], expected_end_us: int) -> None:
    expected = {
        "schemaVersion": 1,
        "sessionId": SESSION,
        "source": "CoverageIndex",
        "targetReserveUs": 8_000_000,
        "durableReserveUs": expected_end_us,
        "firstPassFetchKeys": 4,
        "secondPassFetchKeys": 0,
        "afterDisableUnmeasured": True,
        "reopenWithoutNetwork": True,
        "baselineSeparate": True,
    }
    if set(summary) != set(expected):
        raise ValueError("M4 UI summary fields do not match the contract")
    for field, value in expected.items():
        if type(summary[field]) is not type(value) or summary[field] != value:
            raise ValueError(f"M4 UI {field} disagrees with independent expectation")


def verify(
    summary: Mapping[str, Any],
    trace: list[Mapping[str, Any]],
    committed: Mapping[str, Any],
    files: Mapping[str, Any],
    oracle: Mapping[str, Any],
    manifest: Mapping[str, Any],
    mpd: bytes,
) -> dict[str, Any]:
    units, expected_end_us = expected_f1_units(mpd, manifest)
    if len(units) != 4 or expected_end_us != 9_941_333:
        raise ValueError("canonical F1 first audio/video horizon changed")

    verify_ui_summary(summary, expected_end_us)
    rows = verify_origin(trace, units)
    if len(rows) != 4:
        raise ValueError("debug UI opened unexpected physical origin requests")
    if any(
        row.get("sessionId") != SESSION or row.get("profileId") != "N0"
        for row in rows
    ):
        raise ValueError("debug UI N0 HTTP session/profile identity mismatch")
    verify_storage(committed, files, oracle, units, expected_end_us)
    return {
        "schemaVersion": 1,
        "status": "PASS",
        "scenario": "M4-A1c/DEBUG-F1",
        "originRequestCount": len(rows),
        "independentDurableReserveUs": expected_end_us,
        "repeatFetchUnits": 0,
        "requiredTracks": ["audio-main", "video-main"],
        "baselineIsolation": "debug-only-secondary-launcher",
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    for name in (
        "ui-summary", "origin-trace", "committed", "verified-files",
        "oracle", "manifest", "mpd", "output",
    ):
        parser.add_argument("--" + name, required=True, type=pathlib.Path)
    args = parser.parse_args()
    result = verify(
        summary=json.loads(args.ui_summary.read_text(encoding="utf-8")),
        trace=read_jsonl(args.origin_trace),
        committed=json.loads(args.committed.read_text(encoding="utf-8")),
        files=json.loads(args.verified_files.read_text(encoding="utf-8")),
        oracle=json.loads(args.oracle.read_text(encoding="utf-8")),
        manifest=json.loads(args.manifest.read_text(encoding="utf-8")),
        mpd=args.mpd.read_bytes(),
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    print(
        "M4-A1c debug UI PASS: "
        f"{result['originRequestCount']} physical F1 requests, "
        f"{result['independentDurableReserveUs']} us persisted reserve"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
