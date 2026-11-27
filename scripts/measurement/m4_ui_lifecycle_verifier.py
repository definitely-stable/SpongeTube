#!/usr/bin/env python3
"""Verify the isolated M4 debug lifecycle N4R origin evidence."""
import sys
from pathlib import Path

from m1_fetch_evidence import read_jsonl


def verify(origin, gate):
    rows = [row for row in origin if row.get("plane") == "data"]
    if len(rows) != 1:
        raise ValueError("expected exactly one physical media request before onStop")
    row = rows[0]
    seq = [e.get("serverEventSequence") for e in gate]
    if any(type(n) is not int for n in seq) or seq != sorted(set(seq)):
        raise ValueError("gate event sequence is not strictly increasing")
    if any(type(e.get("schemaVersion")) is not int or e["schemaVersion"] != 1 for e in gate):
        raise ValueError("invalid gate schema version")
    if row.get("sessionId") != "m4-a1c-f1-debug" or row.get("profileId") != "N4R":
        raise ValueError("unexpected lifecycle origin identity")

    def exactly(event, command):
        matches = [e for e in gate if e.get("event") == event
                   and e.get("commandId") == command]
        if len(matches) != 1:
            raise ValueError(f"missing or duplicated {event}/{command}")
        return matches[0]

    close = exactly("GATE_CLOSE_ACCEPTED", "m4-ui-lifecycle-close")
    restore = exactly("GATE_OPEN_ACCEPTED", "m4-ui-lifecycle-restore")
    blocked = exactly("REQUEST_BLOCKED", "m4-ui-lifecycle-close")
    if sum(e.get("event") == "REQUEST_BLOCKED" for e in gate) != 1:
        raise ValueError("unexpected additional blocked request")
    if blocked.get("requestId") != row.get("requestId") or row.get("requestId") is None:
        raise ValueError("gate and origin request identity mismatch")
    if not (close["serverEventSequence"] < blocked["serverEventSequence"]
            < restore["serverEventSequence"]):
        raise ValueError("incorrect lifecycle gate event order")
    if (type(close.get("gateGeneration")) is not int or
            close["gateGeneration"] != restore.get("gateGeneration") or
            close["gateGeneration"] != blocked.get("gateGeneration")):
        raise ValueError("gate generation mismatch")
    first_write = row.get("firstBodyWriteAtMonotonicNs")
    if first_write is not None and first_write < restore["serverMonotonicNs"]:
        raise ValueError("body progressed before gate restore")


if __name__ == "__main__":
    verify(read_jsonl(Path(sys.argv[1])), read_jsonl(Path(sys.argv[2])))
    print("M4-A1c lifecycle origin PASS: one blocked request, no replacement")
