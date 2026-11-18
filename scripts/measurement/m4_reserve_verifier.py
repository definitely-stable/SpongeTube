#!/usr/bin/env python3
"""Independent M4-A1b F1 Android/Media Lab reserve evidence verifier.

Authority: original F1 MPD + manifest; HTTP-origin trace; independently
reconstructed SQLite/immutable-file oracle. Runtime reserve numbers cannot
self-certify correct coverage or physical request counts.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from typing import Any, Mapping

from m1_fetch_evidence import read_jsonl, validate_fetch_events


@dataclass(frozen=True)
class ExpectedUnit:
    path: str
    extent_id: str
    fetch_key: str
    track_id: str
    representation_id: str
    length: int
    sha256: str
    start_us: int | None
    end_us: int | None


def expected_f1_units(
    mpd: bytes,
    manifest: Mapping[str, Any],
) -> tuple[list[ExpectedUnit], int]:
    fixtures = [x for x in manifest["fixtures"] if x["fixtureId"] == "F1"]
    if len(fixtures) != 1:
        raise ValueError("expected exactly one F1 fixture")
    facts = {
        r["relativePath"]: r for r in fixtures[0]["resources"]
    }
    root = ET.fromstring(mpd)
    by_kind: dict[str, tuple[ExpectedUnit, ExpectedUnit]] = {}
    for adaptation in root.findall(".//{*}AdaptationSet"):
        kind = adaptation.attrib.get("contentType")
        if kind not in {"video", "audio"}:
            continue
        if kind in by_kind:
            raise ValueError(f"duplicate adaptation {kind}")
        reps = adaptation.findall("{*}Representation")
        if len(reps) != 1:
            raise ValueError(f"{kind}: expected one representation")
        rep = reps[0]
        rep_id = rep.attrib["id"]
        track = f"{kind}-main"
        representation = f"f1-{kind}-{rep_id}"
        template = rep.find(".//{*}SegmentTemplate")
        if template is None:
            raise ValueError(f"{kind}: missing SegmentTemplate")
        timescale = int(template.attrib["timescale"])
        if timescale <= 0:
            raise ValueError("nonpositive timescale")
        first_segment = template.find(".//{*}SegmentTimeline/{*}S")
        if first_segment is None:
            raise ValueError(f"{kind}: missing SegmentTimeline")
        if int(first_segment.attrib.get("t", "0")) != 0:
            raise ValueError(f"{kind}: first segment must start at zero")
        duration = int(first_segment.attrib["d"])
        if duration <= 0:
            raise ValueError("invalid segment duration")
        end_us = duration * 1_000_000 // timescale
        start_number = int(template.attrib.get("startNumber", "1"))
        init_path = "F1/" + template.attrib["initialization"].replace(
            "$RepresentationID$", rep_id
        )
        media_path = "F1/" + template.attrib["media"].replace(
            "$RepresentationID$", rep_id
        ).replace("$Number%05d$", f"{start_number:05d}")
        def make(path: str, suffix: str, start: int | None, end: int | None):
            fact = facts.get(path)
            if fact is None:
                raise ValueError(f"manifest missing canonical resource {path}")
            return ExpectedUnit(
                path=path,
                extent_id=f"f1:{kind}:{rep_id}:{suffix}",
                fetch_key=(
                    f"fixture:F1/{track}/{representation}/"
                    + pathlib.PurePosixPath(path).stem
                ),
                track_id=track,
                representation_id=representation,
                length=int(fact["sizeBytes"]),
                sha256=str(fact["sha256"]),
                start_us=start,
                end_us=end,
            )
        by_kind[kind] = (
            make(init_path, "init", None, None),
            make(media_path, str(start_number), 0, end_us),
        )
    if set(by_kind) != {"audio", "video"}:
        raise ValueError("F1 must provide both audio and video")
    audio_init, audio_first = by_kind["audio"]
    video_init, video_first = by_kind["video"]
    if audio_first.end_us == video_first.end_us:
        # The fixture deliberately has different track segment boundaries.
        raise ValueError("F1 segment boundaries unexpectedly identical")
    # First timed candidate sorts by endUs, then trackId.
    if not (audio_first.end_us < video_first.end_us):
        raise ValueError("F1 ordering assumption changed")
    return [
        audio_init, audio_first, video_init, video_first
    ], min(audio_first.end_us, video_first.end_us)


def verify_origin(
    trace: list[Mapping[str, Any]],
    units: list[ExpectedUnit],
) -> list[Mapping[str, Any]]:
    data_rows = [r for r in trace if r.get("plane") == "data"]
    expected_paths = ["/fixtures/" + u.path for u in units]
    actual_paths = [r.get("path") for r in data_rows]
    if actual_paths != expected_paths:
        raise ValueError(
            f"origin request sequence mismatch: {actual_paths!r} "
            f"expected {expected_paths!r}"
        )
    ids = [str(r.get("requestId")) for r in data_rows]
    if len(ids) != len(set(ids)):
        raise ValueError("duplicate Media Lab requestId")
    for row, unit in zip(data_rows, units):
        if row.get("status") != 206 or row.get("outcome") != "SUCCESS":
            raise ValueError(f"physical HTTP request unsuccessful: {unit.path}")
        if row.get("resolvedRangeStart") != 0:
            raise ValueError(f"unexpected HTTP range start for {unit.path}")
        if row.get("resolvedRangeEndExclusive") != unit.length:
            raise ValueError(f"wrong HTTP Content-Range for {unit.path}")
        if row.get("bodyBytesWritten") != unit.length:
            raise ValueError(f"wrong origin body count for {unit.path}")
    return data_rows


def verify_storage(
    committed: Mapping[str, Any],
    verified: Mapping[str, Any],
    oracle: Mapping[str, Any],
    units: list[ExpectedUnit],
    expected_end_us: int,
) -> None:
    rows = committed.get("extents", [])
    files = verified.get("files", [])
    by_id = {r["extentId"]: r for r in rows}
    file_by_id = {r["extentId"]: r for r in files}
    expected_ids = {u.extent_id for u in units}
    if len(rows) != len(by_id) or set(by_id) != expected_ids:
        raise ValueError("committed extent identities differ from F1 first A/V units")
    if len(files) != len(file_by_id) or set(file_by_id) != expected_ids:
        raise ValueError("verified filesystem identities differ from F1 first A/V units")
    for unit in units:
        row = by_id[unit.extent_id]
        actual = file_by_id[unit.extent_id]
        expected_fields = {
            "state": "PUBLISHED",
            "integrityState": "VALID",
            "trackId": unit.track_id,
            "representationId": unit.representation_id,
            "mediaStartUs": unit.start_us,
            "mediaEndUs": unit.end_us,
            "length": unit.length,
            "sha256": unit.sha256,
        }
        for key, expected in expected_fields.items():
            if row.get(key) != expected:
                raise ValueError(f"{unit.extent_id}: {key} differs from MPD/manifest")
        if row.get("mediaAssetId") != "fixture:F1":
            raise ValueError(f"{unit.extent_id}: wrong asset")
        if row.get("dependencyExtentIds") != (
            [] if unit.start_us is None else
            [f"f1:{unit.track_id.split('-')[0]}:{unit.representation_id[-1]}:init"]
        ):
            raise ValueError(f"{unit.extent_id}: wrong init dependency")
        if (
            actual.get("exists") is not True or
            actual.get("sha256") != unit.sha256 or
            actual.get("length") != unit.length or
            actual.get("storagePath") != row.get("storagePath")
        ):
            raise ValueError(f"{unit.extent_id}: real bytes are not verified")
    if oracle.get("durableReserveUs") != expected_end_us:
        raise ValueError("independent playable reserve disagrees with MPD")
    if oracle.get("durablePlayableEndUs") != expected_end_us:
        raise ValueError("independent playable end disagrees with MPD")


def verify_events(
    events: list[dict[str, Any]],
    origins: list[Mapping[str, Any]],
    units: list[ExpectedUnit],
) -> None:
    validate_fetch_events(events)
    sequence = [r["eventSequence"] for r in events]
    if sequence != sorted(set(sequence)):
        raise ValueError("fetch event order not strictly monotonic")
    expected_keys = [u.fetch_key for u in units]
    registered = [r for r in events if r.get("event") == "OWNER_REGISTERED"]
    started = [r for r in events if r.get("event") == "ATTEMPT_STARTED"]
    completed = [r for r in events if r.get("event") == "ATTEMPT_COMPLETED"]
    owner_completed = [r for r in events if r.get("event") == "OWNER_COMPLETED"]
    for name, rows in [
        ("owner", registered), ("attempt", started),
        ("completed attempt", completed), ("terminal", owner_completed),
    ]:
        if [r.get("fetchKey") for r in rows] != expected_keys:
            raise ValueError(f"{name} order/owner count not equal to F1 demand")
    owner_ids = [r.get("fetchId") for r in registered]
    if len(set(owner_ids)) != len(units):
        raise ValueError("a physical owner was reused for another immutable key")
    for start, done, end, origin, unit, owner in zip(
        started, completed, owner_completed, origins, units, registered
    ):
        if not (start.get("fetchId") == done.get("fetchId") ==
                end.get("fetchId") == owner.get("fetchId")):
            raise ValueError(f"owner identity changed for {unit.path}")
        if start.get("attempt") != 1 or done.get("attempt") != 1:
            raise ValueError("unplanned additional physical attempt")
        correlation = f"{owner['fetchId']}:attempt-1"
        if start.get("attemptCorrelationId") != correlation:
            raise ValueError("attempt correlation mismatch")
        if done.get("attemptCorrelationId") != correlation:
            raise ValueError("completed attempt correlation mismatch")
        if str(done.get("transportCorrelationId")) != str(origin["requestId"]):
            raise ValueError("origin request is not correlated with broker")
        if done.get("outcome") != "SUCCESS" or end.get("outcome") != "SUCCESS":
            raise ValueError("broker owner did not succeed")
        if done.get("networkBytes") != unit.length:
            raise ValueError("broker network bytes differ from manifest")
        if done.get("uniqueRangeBytes") != unit.length:
            raise ValueError("broker unique bytes differ from manifest")
        if done.get("duplicateRangeBytes") != 0:
            raise ValueError("broker recorded duplicate bytes")
    joined = [
        r for r in events
        if r.get("event") == "CONSUMER_JOINED"
        and r.get("fetchKey") == units[0].fetch_key
    ]
    raised = [
        r for r in events
        if r.get("event") == "PRIORITY_RAISED"
        and r.get("fetchKey") == units[0].fetch_key
    ]
    if len(joined) != 1 or joined[0].get("singleFlightJoined") is not True:
        raise ValueError("playback did not join reserve owner")
    if len(raised) != 1 or raised[0].get("effectivePriority") != "PLAYBACK":
        raise ValueError("playback join did not escalate existing owner")
    if any(r.get("event") == "CONSUMER_JOINED" for r in events
           if r.get("fetchKey") != units[0].fetch_key):
        raise ValueError("unexpected additional broker consumer join")


def verify(
    summary: Mapping[str, Any],
    fetch_events: list[dict[str, Any]],
    origin_trace: list[Mapping[str, Any]],
    committed: Mapping[str, Any],
    verified: Mapping[str, Any],
    oracle: Mapping[str, Any],
    manifest: Mapping[str, Any],
    mpd: bytes,
) -> dict[str, Any]:
    units, expected_end_us = expected_f1_units(mpd, manifest)
    expected_keys = [u.fetch_key for u in units]
    fields = {
        "schemaVersion": 1,
        "sessionId": "m4-a1b-n0",
        "mediaAssetId": "fixture:F1",
        "playheadUs": 0,
        "targetReserveUs": 8_000_000,
        "initialReserveUs": 0,
        "finalReserveUs": expected_end_us,
        "expectedPlayableEndUs": expected_end_us,
        "requestedFetchKeys": expected_keys,
        "replayRequestedFetchKeys": [],
        "playbackJoinedFetchKey": expected_keys[0],
        "committedExtentCount": len(units),
    }
    if set(summary) != set(fields):
        raise ValueError("reserve-summary fields differ from declared contract")
    for field, expected in fields.items():
        if summary[field] != expected:
            raise ValueError(f"reserve-summary {field} != independent expectation")
    origins = verify_origin(origin_trace, units)
    verify_storage(committed, verified, oracle, units, expected_end_us)
    verify_events(fetch_events, origins, units)
    return {
        "schemaVersion": 1,
        "status": "PASS",
        "sessionId": "m4-a1b-n0",
        "originRequestCount": len(units),
        "distinctFetchOwners": len(units),
        "playbackJoined": True,
        "independentDurableReserveUs": expected_end_us,
        "totalVerifiedBytes": sum(u.length for u in units),
        "fetchKeys": expected_keys,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    for key in (
        "summary", "fetch-events", "origin-trace",
        "committed", "verified-files", "oracle", "manifest", "mpd", "output",
    ):
        parser.add_argument("--" + key, required=True, type=pathlib.Path)
    args = parser.parse_args()
    result = verify(
        summary=json.loads(args.summary.read_text(encoding="utf-8")),
        fetch_events=read_jsonl(args.fetch_events),
        origin_trace=read_jsonl(args.origin_trace),
        committed=json.loads(args.committed.read_text(encoding="utf-8")),
        verified=json.loads(args.verified_files.read_text(encoding="utf-8")),
        oracle=json.loads(args.oracle.read_text(encoding="utf-8")),
        manifest=json.loads(args.manifest.read_text(encoding="utf-8")),
        mpd=args.mpd.read_bytes(),
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    print(f"M4-A1b F1 verified: {result['originRequestCount']} unique origin requests, "
          f"durable reserve {result['independentDurableReserveUs']} us")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
