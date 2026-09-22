"""Independent host-side coverage oracle for SpongeTube M1 evidence.

Runtime CoverageIndex output and lifecycle events are deliberately not authority inputs.
Canonical verification supplies:

1. rows read from the committed SQLite metadata snapshot; and
2. file facts independently reconstructed from immutable extent files.

An emitted PUBLISHED lifecycle event without a committed metadata row therefore cannot
create coverage in this oracle.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable, Mapping, Sequence


@dataclass(frozen=True, order=True)
class Interval:
    start_us: int
    end_us: int

    def __post_init__(self) -> None:
        if self.start_us < 0:
            raise ValueError("interval start must be >= 0")
        if self.end_us <= self.start_us:
            raise ValueError("interval end must be greater than start")


def _as_interval(value: Interval | Sequence[int]) -> Interval:
    if isinstance(value, Interval):
        return value
    if len(value) != 2:
        raise ValueError("interval sequence must contain exactly two values")
    return Interval(int(value[0]), int(value[1]))


def normalize_intervals(
    values: Iterable[Interval | Sequence[int]],
) -> tuple[Interval, ...]:
    ordered = sorted(_as_interval(value) for value in values)
    if not ordered:
        return ()

    merged: list[Interval] = [ordered[0]]
    for current in ordered[1:]:
        previous = merged[-1]
        if current.start_us <= previous.end_us:
            merged[-1] = Interval(
                previous.start_us,
                max(previous.end_us, current.end_us),
            )
        else:
            merged.append(current)
    return tuple(merged)


def intersect_two(
    left: Iterable[Interval | Sequence[int]],
    right: Iterable[Interval | Sequence[int]],
) -> tuple[Interval, ...]:
    a = normalize_intervals(left)
    b = normalize_intervals(right)
    out: list[Interval] = []
    i = 0
    j = 0

    while i < len(a) and j < len(b):
        start = max(a[i].start_us, b[j].start_us)
        end = min(a[i].end_us, b[j].end_us)
        if start < end:
            out.append(Interval(start, end))

        if a[i].end_us <= b[j].end_us:
            i += 1
        else:
            j += 1

    return tuple(out)


def intersect_required(
    per_track: Mapping[str, Iterable[Interval | Sequence[int]]],
    required_track_ids: Sequence[str],
) -> tuple[Interval, ...]:
    if not required_track_ids:
        raise ValueError("at least one required track is required")

    result = normalize_intervals(per_track.get(required_track_ids[0], ()))
    for track_id in required_track_ids[1:]:
        result = intersect_two(result, per_track.get(track_id, ()))
        if not result:
            break
    return result


def durable_reserve_us(
    playhead_us: int,
    playable_intervals: Iterable[Interval | Sequence[int]],
) -> int:
    if playhead_us < 0:
        raise ValueError("playhead must be >= 0")

    for interval in normalize_intervals(playable_intervals):
        if interval.start_us <= playhead_us < interval.end_us:
            return interval.end_us - playhead_us
        if playhead_us < interval.start_us:
            return 0
    return 0


def _file_matches_row(
    row: Mapping[str, object],
    fact: Mapping[str, object] | None,
) -> bool:
    if fact is None or fact.get("exists") is not True:
        return False

    return (
        fact.get("length") == row.get("length")
        and fact.get("sha256") == row.get("sha256")
        and fact.get("storagePath") == row.get("storagePath")
    )


def reconstruct_committed_coverage(
    committed_rows: Iterable[Mapping[str, object]],
    required_representations: Mapping[str, str],
    verified_files: Mapping[str, Mapping[str, object]],
) -> dict[str, tuple[Interval, ...]]:
    """Reconstruct conservative coverage from committed metadata + file facts.

    A row contributes only when:
    - the database snapshot contains it as PUBLISHED + VALID;
    - the independently inspected immutable file exists;
    - file length, SHA-256 and storage path match committed metadata;
    - the exact required track/representation identity matches; and
    - every declared dependency is itself eligible.

    Lifecycle event logs are intentionally not accepted as an input.
    """

    rows: dict[str, Mapping[str, object]] = {}
    for row in committed_rows:
        extent_id = str(row["extentId"])
        if extent_id in rows:
            raise ValueError(f"duplicate committed extent row: {extent_id}")
        rows[extent_id] = row

    eligible_candidates = {
        extent_id
        for extent_id, row in rows.items()
        if row.get("state") == "PUBLISHED"
        and row.get("integrityState") == "VALID"
        and _file_matches_row(row, verified_files.get(extent_id))
    }

    ready: set[str] = set()
    changed = True
    while changed:
        changed = False
        for extent_id in eligible_candidates - ready:
            row = rows[extent_id]
            dependencies = {
                str(item)
                for item in row.get("dependencyExtentIds", [])
            }
            same_identity = all(
                dep in rows
                and rows[dep].get("trackId") == row.get("trackId")
                and rows[dep].get("representationId")
                == row.get("representationId")
                for dep in dependencies
            )
            if same_identity and dependencies <= ready:
                ready.add(extent_id)
                changed = True

    per_track: dict[str, list[Interval]] = {
        track_id: [] for track_id in required_representations
    }

    for extent_id in ready:
        row = rows[extent_id]
        track_id = str(row["trackId"])
        required_representation = required_representations.get(track_id)
        if required_representation is None:
            continue
        if row.get("representationId") != required_representation:
            continue

        start = row.get("mediaStartUs")
        end = row.get("mediaEndUs")
        if start is None or end is None:
            # Initialization/index/dependency-only extent.
            continue

        per_track[track_id].append(Interval(int(start), int(end)))

    return {
        track_id: normalize_intervals(intervals)
        for track_id, intervals in per_track.items()
    }


def oracle_snapshot(
    committed_rows: Iterable[Mapping[str, object]],
    required_representations: Mapping[str, str],
    verified_files: Mapping[str, Mapping[str, object]],
    playhead_us: int,
) -> dict[str, object]:
    per_track = reconstruct_committed_coverage(
        committed_rows,
        required_representations,
        verified_files,
    )
    required_track_ids = list(required_representations.keys())
    playable = intersect_required(per_track, required_track_ids)
    reserve = durable_reserve_us(playhead_us, playable)

    playable_end = None
    for interval in playable:
        if interval.start_us <= playhead_us < interval.end_us:
            playable_end = interval.end_us
            break

    return {
        "perTrackPublishedIntervals": {
            track_id: [
                {"startUs": interval.start_us, "endUs": interval.end_us}
                for interval in intervals
            ]
            for track_id, intervals in per_track.items()
        },
        "playableIntervals": [
            {"startUs": interval.start_us, "endUs": interval.end_us}
            for interval in playable
        ],
        "durablePlayableEndUs": playable_end,
        "durableReserveUs": reserve,
    }
