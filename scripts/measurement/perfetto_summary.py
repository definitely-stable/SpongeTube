#!/usr/bin/env python3
from __future__ import annotations

import argparse
import pathlib
import sys
from typing import Any

from artifacts import file_sha256, write_json

TRACE_STATUSES = {"COMPLETE", "PARTIAL"}

SECTION_NAMES = {
    "prepare": "SpongeTube:M0:prepare",
    "seek": "SpongeTube:M0:seek",
    "rebuffer": "SpongeTube:M0:rebuffer",
}


def non_negative_float(value: str) -> float:
    parsed = float(value)
    if parsed < 0:
        raise argparse.ArgumentTypeError("expected value >= 0")
    return parsed


def non_negative_int(value: str) -> int:
    parsed = int(value)
    if parsed < 0:
        raise argparse.ArgumentTypeError("expected value >= 0")
    return parsed


def section_summary(
    key: str,
    count: int | None,
    total_duration_ms: float | None,
) -> dict[str, Any]:
    return {
        "traceName": SECTION_NAMES[key],
        "count": count,
        "totalDurationMs": total_duration_ms,
    }


def build_summary(args: argparse.Namespace) -> dict[str, Any]:
    trace = pathlib.Path(args.trace)
    if not trace.is_file():
        raise ValueError(f"Perfetto trace not found: {trace}")

    trace_bytes = trace.stat().st_size
    if trace_bytes <= 0:
        raise ValueError("Perfetto trace must be non-empty")

    if args.status not in TRACE_STATUSES:
        raise ValueError(
            f"status={args.status!r} is invalid; "
            f"expected one of {sorted(TRACE_STATUSES)}"
        )

    limitations = list(args.limitation)
    if args.status == "PARTIAL" and not limitations:
        raise ValueError("PARTIAL trace summary requires a limitation")

    return {
        "schemaVersion": 1,
        "status": args.status,
        "rawTraceSha256": file_sha256(trace),
        "rawTraceBytes": trace_bytes,
        "process": {
            "cpuTimeMs": args.cpu_time_ms,
            "maxRssBytes": args.max_rss_bytes,
            "ioReadBytes": args.io_read_bytes,
            "ioWriteBytes": args.io_write_bytes,
            "gcTimeMs": args.gc_time_ms,
        },
        "customSections": {
            "prepare": section_summary(
                "prepare",
                args.prepare_count,
                args.prepare_total_ms,
            ),
            "seek": section_summary(
                "seek",
                args.seek_count,
                args.seek_total_ms,
            ),
            "rebuffer": section_summary(
                "rebuffer",
                args.rebuffer_count,
                args.rebuffer_total_ms,
            ),
        },
        "limitations": limitations,
    }


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(
        description="Compose SpongeTube Perfetto trace-summary v1."
    )
    result.add_argument("--trace", required=True, type=pathlib.Path)
    result.add_argument("--output", required=True, type=pathlib.Path)
    result.add_argument("--status", required=True)
    result.add_argument(
        "--limitation",
        action="append",
        default=[],
    )

    result.add_argument("--cpu-time-ms", type=non_negative_float)
    result.add_argument("--max-rss-bytes", type=non_negative_int)
    result.add_argument("--io-read-bytes", type=non_negative_int)
    result.add_argument("--io-write-bytes", type=non_negative_int)
    result.add_argument("--gc-time-ms", type=non_negative_float)

    result.add_argument("--prepare-count", type=non_negative_int)
    result.add_argument("--prepare-total-ms", type=non_negative_float)
    result.add_argument("--seek-count", type=non_negative_int)
    result.add_argument("--seek-total-ms", type=non_negative_float)
    result.add_argument("--rebuffer-count", type=non_negative_int)
    result.add_argument("--rebuffer-total-ms", type=non_negative_float)
    return result


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        summary = build_summary(args)
        write_json(args.output, summary)
    except (OSError, ValueError) as error:
        parser().error(str(error))
    return 0


if __name__ == "__main__":
    sys.exit(main())
