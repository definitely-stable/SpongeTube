#!/usr/bin/env python3
"""Independent semantic verifier for retained M1-B ACC-01/02/03 evidence."""

from __future__ import annotations

import argparse
import json
import pathlib
import sys
from typing import Any

from schema_subset import validate_instance, validate_schema_definition

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
EXTENT_SCHEMA_PATH = REPO_ROOT / ".work" / "schemas" / "extent-events-v1.schema.json"
TEN_SECONDS_US = 10_000_000

EXPECTED_CASES = {
    "acc-01": ("M1-ACC-01", TEN_SECONDS_US),
    "acc-02-after_receiving": ("M1-ACC-02", None),
    "acc-02-after_seal": ("M1-ACC-02", None),
    "acc-02-after_verify": ("M1-ACC-02", None),
    "acc-02-after_durable_before_publish": ("M1-ACC-02", None),
    "acc-02-after_metadata_commit_before_published_event": (
        "M1-ACC-02",
        TEN_SECONDS_US,
    ),
    "acc-02-after_publish": ("M1-ACC-02", TEN_SECONDS_US),
    "acc-03-missing": ("M1-ACC-03", None),
    "acc-03-corrupt": ("M1-ACC-03", None),
}

PRE_PUBLISH_CRASHES = {
    "AFTER_RECEIVING",
    "AFTER_SEAL",
    "AFTER_VERIFY",
    "AFTER_DURABLE_BEFORE_PUBLISH",
}


class StorageEvidenceError(ValueError):
    pass


def read_json(path: pathlib.Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise StorageEvidenceError(f"{path}: expected JSON object")
    return value


def read_jsonl(path: pathlib.Path) -> list[dict[str, Any]]:
    if not path.is_file():
        raise StorageEvidenceError(f"missing evidence file: {path}")
    rows: list[dict[str, Any]] = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        value = json.loads(line)
        if not isinstance(value, dict):
            raise StorageEvidenceError(f"{path}:{number}: expected object")
        rows.append(value)
    if not rows:
        raise StorageEvidenceError(f"{path}: empty evidence stream")
    return rows


def validate_extent_events(rows: list[dict[str, Any]]) -> None:
    schema = read_json(EXTENT_SCHEMA_PATH)
    validate_schema_definition(schema)
    for index, row in enumerate(rows):
        validate_instance(schema, row, root_schema=schema)
        if row["eventSequence"] != index:
            raise StorageEvidenceError("extent event sequence must be contiguous from zero")


def verify_oracle(case: dict[str, Any], oracle: dict[str, Any]) -> None:
    expected_end = case.get("expectedPlayableEndUs")
    intervals = oracle.get("playableIntervals")
    reserve = oracle.get("durableReserveUs")
    actual_end = oracle.get("durablePlayableEndUs")

    if expected_end is None:
        if intervals != [] or actual_end is not None or reserve != 0:
            raise StorageEvidenceError(
                f"{case['caseId']}: expected zero reconstructed coverage"
            )
        return

    expected_intervals = [{"startUs": 0, "endUs": expected_end}]
    if intervals != expected_intervals:
        raise StorageEvidenceError(
            f"{case['caseId']}: reconstructed playable intervals mismatch"
        )
    if actual_end != expected_end or reserve != expected_end:
        raise StorageEvidenceError(
            f"{case['caseId']}: reconstructed reserve/end mismatch"
        )


def verify_case(
    case_root: pathlib.Path,
    verified_root: pathlib.Path,
) -> dict[str, Any]:
    case = read_json(case_root / "case.json")
    case_id = str(case.get("caseId"))
    if case_id not in EXPECTED_CASES:
        raise StorageEvidenceError(f"unexpected storage evidence case {case_id!r}")

    expected_gate, expected_end = EXPECTED_CASES[case_id]
    if case.get("gateId") != expected_gate:
        raise StorageEvidenceError(f"{case_id}: gateId mismatch")
    if case.get("expectedPlayableEndUs") != expected_end:
        raise StorageEvidenceError(f"{case_id}: expected coverage contract mismatch")

    recovery = read_json(case_root / "recovery-report.json")
    rows = read_jsonl(case_root / "extent-events-v1.jsonl")
    validate_extent_events(rows)

    oracle = read_json(verified_root / case_id / "oracle-coverage.json")
    committed = read_json(verified_root / case_id / "committed-extents.json")
    files = read_json(verified_root / case_id / "verified-extent-files.json")
    verify_oracle(case, oracle)

    if committed.get("sessionId") != case.get("sessionId"):
        raise StorageEvidenceError(f"{case_id}: committed snapshot session mismatch")
    if files.get("sessionId") != case.get("sessionId"):
        raise StorageEvidenceError(f"{case_id}: file snapshot session mismatch")

    states = [str(row["state"]) for row in rows]
    if case_id == "acc-01":
        expected_states = ["RECEIVING", "SEALED", "VERIFIED", "DURABLE", "PUBLISHED"]
        if states != expected_states:
            raise StorageEvidenceError("ACC-01 lifecycle ordering mismatch")
        if recovery.get("verifiedPublishedExtents") != 1:
            raise StorageEvidenceError("ACC-01 published extent did not survive reopen")

    if expected_gate == "M1-ACC-02":
        fault = case.get("faultPoint")
        if fault in PRE_PUBLISH_CRASHES:
            if "PUBLISHED" in states:
                raise StorageEvidenceError(
                    f"{case_id}: pre-publish crash emitted PUBLISHED"
                )
            if recovery.get("verifiedPublishedExtents") != 0:
                raise StorageEvidenceError(
                    f"{case_id}: pre-publish crash recovered phantom published extent"
                )
        elif fault == "AFTER_METADATA_COMMIT_BEFORE_PUBLISHED_EVENT":
            if "PUBLISHED" in states:
                raise StorageEvidenceError(
                    "metadata-commit-before-event case unexpectedly emitted PUBLISHED"
                )
            if recovery.get("verifiedPublishedExtents") != 1:
                raise StorageEvidenceError(
                    "committed metadata did not survive missing diagnostic event"
                )
        elif fault == "AFTER_PUBLISH":
            if states[-1:] != ["PUBLISHED"]:
                raise StorageEvidenceError("AFTER_PUBLISH case lacks PUBLISHED event")
            if recovery.get("verifiedPublishedExtents") != 1:
                raise StorageEvidenceError("AFTER_PUBLISH did not survive recovery")
        else:
            raise StorageEvidenceError(f"{case_id}: unknown crash point {fault!r}")

    if expected_gate == "M1-ACC-03":
        if recovery.get("quarantinedExtents") != 1:
            raise StorageEvidenceError(f"{case_id}: recovery did not quarantine extent")
        if any(
            row.get("publicationState") == "PUBLISHED"
            and row.get("integrityState") == "VALID"
            for row in committed.get("extents", [])
        ):
            raise StorageEvidenceError(
                f"{case_id}: invalid published extent remained VALID"
            )

    return {
        "gateId": expected_gate,
        "caseId": case_id,
        "status": "PASS",
        "reconstructedPlayableEndUs": oracle.get("durablePlayableEndUs"),
        "extentEventCount": len(rows),
    }


def verify(cases_root: pathlib.Path, verified_root: pathlib.Path) -> dict[str, Any]:
    actual = {path.name for path in cases_root.iterdir() if path.is_dir()}
    expected = set(EXPECTED_CASES)
    if actual != expected:
        missing = sorted(expected - actual)
        extra = sorted(actual - expected)
        raise StorageEvidenceError(
            f"canonical storage case set mismatch; missing={missing}, extra={extra}"
        )

    results = [
        verify_case(cases_root / case_id, verified_root)
        for case_id in sorted(EXPECTED_CASES)
    ]
    gate_counts = {
        gate: sum(1 for row in results if row["gateId"] == gate)
        for gate in ("M1-ACC-01", "M1-ACC-02", "M1-ACC-03")
    }
    return {
        "schemaVersion": 1,
        "status": "PASS",
        "caseCount": len(results),
        "gateCounts": gate_counts,
        "cases": results,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cases-root", required=True, type=pathlib.Path)
    parser.add_argument("--verified-root", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args(argv)

    try:
        summary = verify(args.cases_root, args.verified_root)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(summary, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        print(
            "M1-B canonical storage evidence verified: "
            f"{summary['caseCount']} cases"
        )
        return 0
    except (OSError, json.JSONDecodeError, StorageEvidenceError, ValueError) as error:
        print(f"M1-B EVIDENCE FAILURE: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
