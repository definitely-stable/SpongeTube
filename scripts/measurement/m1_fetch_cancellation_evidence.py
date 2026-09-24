#!/usr/bin/env python3
"""Independent semantic verifier for retained M1-ACC-07 FetchBroker evidence."""

from __future__ import annotations

import argparse
import json
import pathlib
import sys
from typing import Any

from m1_fetch_evidence import read_jsonl, validate_fetch_events

EXPECTED_CASES = {
    "JOINED_CONSUMER_RELEASE",
    "CANCELLING_BARRIER_RESTART",
    "CANCELLING_BARRIER_LATE_SUCCESS",
}


class FetchCancellationEvidenceError(ValueError):
    pass


def read_json(path: pathlib.Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise FetchCancellationEvidenceError(f"{path}: expected JSON object")
    return value


def events_of(rows: list[dict[str, Any]], event: str) -> list[dict[str, Any]]:
    return [row for row in rows if row.get("event") == event]


def one(rows: list[dict[str, Any]], event: str) -> dict[str, Any]:
    matches = events_of(rows, event)
    if len(matches) != 1:
        raise FetchCancellationEvidenceError(
            f"expected exactly one {event}, got {len(matches)}"
        )
    return matches[0]


def sequence_index(rows: list[dict[str, Any]], predicate) -> int:
    for index, row in enumerate(rows):
        if predicate(row):
            return index
    return -1


def verify_joined_release(
    rows: list[dict[str, Any]],
    case: dict[str, Any],
) -> dict[str, Any]:
    if case.get("executions") != 1:
        raise FetchCancellationEvidenceError("joined release refetched physical work")
    if case.get("terminalOutcome") != "SUCCESS":
        raise FetchCancellationEvidenceError("remaining joined consumer did not succeed")

    one(rows, "OWNER_REGISTERED")
    one(rows, "ATTEMPT_STARTED")
    one(rows, "CONSUMER_JOINED")
    release = one(rows, "CONSUMER_RELEASED")
    if events_of(rows, "OWNER_CANCELLED"):
        raise FetchCancellationEvidenceError(
            "releasing one joined consumer cancelled the shared owner"
        )
    completed = one(rows, "OWNER_COMPLETED")
    if release.get("consumerIds") != ["two"]:
        raise FetchCancellationEvidenceError(
            "released-consumer evidence does not retain the remaining consumer"
        )
    if completed.get("outcome") != "SUCCESS":
        raise FetchCancellationEvidenceError("shared owner did not complete SUCCESS")

    return {
        "caseId": "JOINED_CONSUMER_RELEASE",
        "status": "PASS",
        "fetchId": completed.get("fetchId"),
    }


def verify_barrier_restart(
    rows: list[dict[str, Any]],
    case: dict[str, Any],
) -> dict[str, Any]:
    if case.get("waitingBeforeTerminal") is not True:
        raise FetchCancellationEvidenceError(
            "late demand did not demonstrably wait behind CANCELLING"
        )
    if case.get("replacementDisposition") != "NEW_OWNER":
        raise FetchCancellationEvidenceError(
            "post-cancellation replacement did not start as a new owner"
        )
    if case.get("executions") != 2:
        raise FetchCancellationEvidenceError(
            "restart case did not execute exactly one old and one new owner"
        )

    owners = events_of(rows, "OWNER_REGISTERED")
    cancelled = events_of(rows, "OWNER_CANCELLED")
    if len(owners) != 2 or len(cancelled) != 1:
        raise FetchCancellationEvidenceError(
            "restart case must have two owners and one cancelled owner"
        )
    if owners[0].get("fetchId") == owners[1].get("fetchId"):
        raise FetchCancellationEvidenceError("fresh owner reused cancelled fetchId")
    if cancelled[0].get("fetchId") != owners[0].get("fetchId"):
        raise FetchCancellationEvidenceError("wrong owner was cancelled")
    if cancelled[0].get("outcome") != "CANCELLED_NO_CONSUMERS":
        raise FetchCancellationEvidenceError("final-consumer cancellation outcome mismatch")

    first_terminal = sequence_index(
        rows,
        lambda row: row.get("event") == "OWNER_CANCELLED",
    )
    second_owner = sequence_index(
        rows,
        lambda row: row.get("event") == "OWNER_REGISTERED"
        and row.get("fetchId") == owners[1].get("fetchId"),
    )
    if first_terminal < 0 or second_owner <= first_terminal:
        raise FetchCancellationEvidenceError(
            "replacement owner appeared before cancelling owner was terminal"
        )

    return {
        "caseId": "CANCELLING_BARRIER_RESTART",
        "status": "PASS",
        "cancelledFetchId": owners[0].get("fetchId"),
        "replacementFetchId": owners[1].get("fetchId"),
    }


def verify_late_success(
    rows: list[dict[str, Any]],
    case: dict[str, Any],
) -> dict[str, Any]:
    if case.get("waitingBeforeTerminal") is not True:
        raise FetchCancellationEvidenceError(
            "late-success demand did not wait behind CANCELLING"
        )
    if case.get("replacementDisposition") != "WAITED_CANCELLING":
        raise FetchCancellationEvidenceError(
            "late success was not handed through the cancellation barrier"
        )
    if case.get("sameFetchId") is not True:
        raise FetchCancellationEvidenceError(
            "late success did not preserve the original fetchId"
        )
    if case.get("executions") != 1:
        raise FetchCancellationEvidenceError(
            "late success caused hidden refetch/request-budget reset"
        )

    one(rows, "OWNER_REGISTERED")
    one(rows, "ATTEMPT_STARTED")
    one(rows, "CONSUMER_RELEASED")
    completed = one(rows, "OWNER_COMPLETED")
    if events_of(rows, "OWNER_CANCELLED"):
        raise FetchCancellationEvidenceError(
            "late-success owner was incorrectly finalized as cancelled"
        )
    if completed.get("outcome") != "SUCCESS":
        raise FetchCancellationEvidenceError("late-success owner did not finish SUCCESS")

    return {
        "caseId": "CANCELLING_BARRIER_LATE_SUCCESS",
        "status": "PASS",
        "fetchId": completed.get("fetchId"),
    }


def verify_case(case_root: pathlib.Path) -> dict[str, Any]:
    case = read_json(case_root / "case.json")
    case_id = str(case.get("caseId"))
    if case_id not in EXPECTED_CASES:
        raise FetchCancellationEvidenceError(f"unexpected ACC-07 case {case_id!r}")

    rows = read_jsonl(case_root / "fetch-events-v3.jsonl")
    validate_fetch_events(rows)
    sequences = [row.get("eventSequence") for row in rows]
    if sequences != sorted(sequences) or len(sequences) != len(set(sequences)):
        raise FetchCancellationEvidenceError(
            f"{case_id}: fetch event sequence is not strictly ordered"
        )

    if case_id == "JOINED_CONSUMER_RELEASE":
        return verify_joined_release(rows, case)
    if case_id == "CANCELLING_BARRIER_RESTART":
        return verify_barrier_restart(rows, case)
    return verify_late_success(rows, case)


def verify(cases_root: pathlib.Path) -> dict[str, Any]:
    actual = {path.name for path in cases_root.iterdir() if path.is_dir()}
    if actual != EXPECTED_CASES:
        raise FetchCancellationEvidenceError(
            "canonical ACC-07 case set mismatch; "
            f"missing={sorted(EXPECTED_CASES - actual)}, "
            f"extra={sorted(actual - EXPECTED_CASES)}"
        )

    cases = [
        verify_case(cases_root / case_id)
        for case_id in sorted(EXPECTED_CASES)
    ]
    return {
        "schemaVersion": 1,
        "gateId": "M1-ACC-07",
        "status": "PASS",
        "caseCount": len(cases),
        "cases": cases,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cases-root", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args(argv)

    try:
        summary = verify(args.cases_root)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(summary, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        print("M1-ACC-07 FetchBroker cancellation evidence verified: 3 cases")
        return 0
    except (
        OSError,
        json.JSONDecodeError,
        FetchCancellationEvidenceError,
        ValueError,
    ) as error:
        print(f"M1-ACC-07 EVIDENCE FAILURE: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
