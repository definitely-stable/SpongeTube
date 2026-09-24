#!/usr/bin/env python3
"""Independent verifier for M1-ACC-15 HTTP range continuation evidence."""

from __future__ import annotations

import argparse
import json
import pathlib
import sys
from typing import Any

from schema_subset import validate_instance, validate_schema_definition

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
SCHEMA_PATH = REPO_ROOT / ".work" / "schemas" / "range-continuation-v1.schema.json"

EXPECTED = {
    "MATCHING_206",
    "FULL_200",
    "WRONG_START_206",
    "WRONG_TOTAL_206",
}


class RangeEvidenceError(ValueError):
    pass


def read_schema() -> dict[str, Any]:
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    validate_schema_definition(schema)
    return schema


def read_rows(path: pathlib.Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    schema = read_schema()
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        value = json.loads(line)
        if not isinstance(value, dict):
            raise RangeEvidenceError(f"{path}:{number}: expected object")
        validate_instance(schema, value, root_schema=schema)
        rows.append(value)
    if not rows:
        raise RangeEvidenceError("range continuation evidence is empty")
    return rows


def verify(rows: list[dict[str, Any]]) -> dict[str, Any]:
    by_case: dict[str, dict[str, Any]] = {}
    for row in rows:
        case_id = str(row["caseId"])
        if case_id in by_case:
            raise RangeEvidenceError(f"duplicate case {case_id}")
        by_case[case_id] = row

    if set(by_case) != EXPECTED:
        raise RangeEvidenceError(
            "canonical ACC-15 case set mismatch; "
            f"missing={sorted(EXPECTED - set(by_case))}, "
            f"extra={sorted(set(by_case) - EXPECTED)}"
        )

    correlations: set[str] = set()
    results: list[dict[str, Any]] = []
    for case_id in sorted(EXPECTED):
        row = by_case[case_id]
        if row["requestedByteStart"] != 100:
            raise RangeEvidenceError(f"{case_id}: requested start mismatch")
        if row["requestedByteEndExclusive"] != 1100:
            raise RangeEvidenceError(f"{case_id}: requested end mismatch")
        if row["resourceLength"] != 4096:
            raise RangeEvidenceError(f"{case_id}: resource length mismatch")
        if row["requestRange"] != "bytes=100-1099":
            raise RangeEvidenceError(f"{case_id}: actual Range header mismatch")

        correlation = str(row["transportCorrelationId"])
        if correlation in correlations:
            raise RangeEvidenceError(f"duplicate transport correlation {correlation}")
        correlations.add(correlation)

        if case_id == "MATCHING_206":
            if row["responseStatus"] != 206:
                raise RangeEvidenceError("matching case did not return 206")
            if row["responseContentRange"] != "bytes 100-1099/4096":
                raise RangeEvidenceError("matching case Content-Range mismatch")
            if row["outcome"] != "SUCCESS" or row["retryable"]:
                raise RangeEvidenceError("matching 206 was not accepted exactly once")
            if row["emittedBytes"] != 1000:
                raise RangeEvidenceError("matching 206 emitted byte count mismatch")
        else:
            if row["outcome"] != "RANGE_REJECTED" or row["retryable"]:
                raise RangeEvidenceError(f"{case_id}: incompatible response was not rejected")
            if row["emittedBytes"] != 0:
                raise RangeEvidenceError(
                    f"{case_id}: incompatible continuation emitted/appended bytes"
                )

            if case_id == "FULL_200":
                if row["responseStatus"] != 200 or row["responseContentRange"] is not None:
                    raise RangeEvidenceError("FULL_200 evidence mismatch")
            elif case_id == "WRONG_START_206":
                if row["responseStatus"] != 206:
                    raise RangeEvidenceError("WRONG_START_206 status mismatch")
                if row["responseContentRange"] != "bytes 101-1099/4096":
                    raise RangeEvidenceError("WRONG_START_206 Content-Range mismatch")
            elif case_id == "WRONG_TOTAL_206":
                if row["responseStatus"] != 206:
                    raise RangeEvidenceError("WRONG_TOTAL_206 status mismatch")
                if row["responseContentRange"] != "bytes 100-1099/4097":
                    raise RangeEvidenceError("WRONG_TOTAL_206 Content-Range mismatch")

        results.append(
            {
                "caseId": case_id,
                "status": "PASS",
                "outcome": row["outcome"],
                "emittedBytes": row["emittedBytes"],
            }
        )

    return {
        "schemaVersion": 1,
        "gateId": "M1-ACC-15",
        "status": "PASS",
        "caseCount": len(results),
        "cases": results,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args(argv)

    try:
        summary = verify(read_rows(args.evidence))
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(summary, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        print("M1-ACC-15 range continuation evidence verified: 4 cases")
        return 0
    except (OSError, json.JSONDecodeError, RangeEvidenceError, ValueError) as error:
        print(f"M1-ACC-15 EVIDENCE FAILURE: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
