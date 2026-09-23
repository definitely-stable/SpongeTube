#!/usr/bin/env python3
"""Independent M1-D FetchBroker/origin evidence verifier."""

from __future__ import annotations

import argparse
import json
import pathlib
from typing import Any


RESOURCE_PATH = "/fixtures/F1/segment-1-00001.m4s"
RESOURCE_LENGTH = 81_811


def read_jsonl(path: pathlib.Path) -> list[dict[str, Any]]:
    if not path.is_file():
        raise ValueError(f"evidence file does not exist: {path}")
    rows: list[dict[str, Any]] = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ValueError(f"{path}:{number}: invalid JSON") from exc
        if not isinstance(value, dict):
            raise ValueError(f"{path}:{number}: row must be an object")
        rows.append(value)
    if not rows:
        raise ValueError(f"evidence file is empty: {path}")
    return rows


def one(rows: list[dict[str, Any]], event: str) -> dict[str, Any]:
    matches = [row for row in rows if row.get("event") == event]
    if len(matches) != 1:
        raise ValueError(f"expected exactly one {event}, got {len(matches)}")
    return matches[0]


def verify(
    fetch_events: list[dict[str, Any]],
    origin_trace: list[dict[str, Any]],
) -> dict[str, Any]:
    if any(row.get("schemaVersion") != 2 for row in fetch_events):
        raise ValueError("all fetch events must use schemaVersion=2")

    sequences = [row.get("eventSequence") for row in fetch_events]
    if sequences != sorted(sequences) or len(sequences) != len(set(sequences)):
        raise ValueError("fetch event sequence must be strictly ordered and unique")

    owner = one(fetch_events, "OWNER_REGISTERED")
    joined = one(fetch_events, "CONSUMER_JOINED")
    raised = one(fetch_events, "PRIORITY_RAISED")
    attempt_started = one(fetch_events, "ATTEMPT_STARTED")
    attempt_completed = one(fetch_events, "ATTEMPT_COMPLETED")
    completed = one(fetch_events, "OWNER_COMPLETED")

    fetch_id = owner.get("fetchId")
    fetch_key = owner.get("fetchKey")
    if not fetch_id or not fetch_key:
        raise ValueError("owner evidence is missing fetch identity")

    if any(
        row.get("fetchId") != fetch_id or row.get("fetchKey") != fetch_key
        for row in fetch_events
    ):
        raise ValueError("fetch evidence contains multiple owner identities")

    if not joined.get("singleFlightJoined"):
        raise ValueError("consumer join was not marked as single-flight")
    if raised.get("effectivePriority") != "PLAYBACK":
        raise ValueError("playback join did not raise effective priority")
    if attempt_started.get("attempt") != 1:
        raise ValueError("canonical M1-D case must start exactly attempt 1")
    if attempt_completed.get("attempt") != 1:
        raise ValueError("canonical M1-D case must complete exactly attempt 1")
    if attempt_completed.get("outcome") != "SUCCESS":
        raise ValueError("canonical M1-D attempt did not succeed")
    if completed.get("outcome") != "SUCCESS":
        raise ValueError("canonical M1-D owner did not succeed")

    expected_attempt_correlation = f"{fetch_id}:attempt-1"
    if attempt_started.get("attemptCorrelationId") != expected_attempt_correlation:
        raise ValueError("ATTEMPT_STARTED correlation does not match fetchId")
    if attempt_completed.get("attemptCorrelationId") != expected_attempt_correlation:
        raise ValueError("ATTEMPT_COMPLETED correlation does not match fetchId")

    transport_correlation = attempt_completed.get("transportCorrelationId")
    if not isinstance(transport_correlation, str) or not transport_correlation:
        raise ValueError("successful attempt is missing transport correlation")

    expected_bytes = {
        "networkBytes": RESOURCE_LENGTH,
        "uniqueRangeBytes": RESOURCE_LENGTH,
        "duplicateRangeBytes": 0,
        "rejectedOrUnmappedBytes": 0,
    }
    for key, expected in expected_bytes.items():
        if attempt_completed.get(key) != expected:
            raise ValueError(
                f"{key} mismatch: expected {expected}, "
                f"got {attempt_completed.get(key)}"
            )

    data_rows = [
        row
        for row in origin_trace
        if row.get("plane") == "data"
        and row.get("path") == RESOURCE_PATH
    ]
    if len(data_rows) != 1:
        raise ValueError(
            "expected exactly one physical origin request for canonical "
            f"FetchKey, got {len(data_rows)}"
        )
    origin = data_rows[0]

    if str(origin.get("requestId")) != transport_correlation:
        raise ValueError(
            "broker transport correlation does not match Media Lab requestId"
        )
    if origin.get("status") != 206:
        raise ValueError("canonical origin request must be HTTP 206")
    if origin.get("resolvedRangeStart") != 0:
        raise ValueError("canonical origin range must start at zero")
    if origin.get("resolvedRangeEndExclusive") != RESOURCE_LENGTH:
        raise ValueError("canonical origin range end is incorrect")
    if origin.get("bodyBytesWritten") != RESOURCE_LENGTH:
        raise ValueError("origin body byte count is incorrect")
    if origin.get("outcome") != "SUCCESS":
        raise ValueError("origin request did not complete successfully")

    return {
        "schemaVersion": 1,
        "status": "PASS",
        "fetchId": fetch_id,
        "fetchKey": fetch_key,
        "attemptCorrelationId": expected_attempt_correlation,
        "transportCorrelationId": transport_correlation,
        "originRequestId": origin["requestId"],
        "originRequestCount": 1,
        "networkBytes": RESOURCE_LENGTH,
        "uniqueRangeBytes": RESOURCE_LENGTH,
        "duplicateRangeBytes": 0,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fetch-events", required=True, type=pathlib.Path)
    parser.add_argument("--origin-trace", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()

    summary = verify(
        read_jsonl(args.fetch_events),
        read_jsonl(args.origin_trace),
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(summary, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
