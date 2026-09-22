"""Independent host-side coverage oracle for SpongeTube M1 evidence.

Runtime CoverageIndex output is deliberately not an input. Canonical verification
supplies ordered extent events plus a separately file-verified extent-id set.
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


def final_extent_events(
    events: Iterable[Mapping[str, object]],
) -> dict[str, Mapping[str, object]]:
    final: dict[str, Mapping[str, object]] = {}
    for event in events:
        extent_id = str(event["extentId"])
        sequence = int(event["eventSequence"])
        previous = final.get(extent_id)
        if previous is None or sequence > int(previous["eventSequence"]):
            final[extent_id] = event
    return final


def reconstruct_published_coverage(
    events: Iterable[Mapping[str, object]],
    required_representations: Mapping[str, str],
    file_verified_extent_ids: set[str],
) -> dict[str, tuple[Interval, ...]]:
    final = final_extent_events(events)

    published_valid = {
        extent_id
        for extent_id, event in final.items()
        if event.get("state") == "PUBLISHED"
        and event.get("integrityState") == "VALID"
        and extent_id in file_verified_extent_ids
    }

    ready: set[str] = set()
    changed = True
    while changed:
        changed = False
        for extent_id in published_valid - ready:
            event = final[extent_id]
            dependencies = {
                str(item)
                for item in event.get("dependencyExtentIds", [])
            }
            same_identity = all(
                dep in final
                and final[dep].get("trackId") == event.get("trackId")
                and final[dep].get("representationId")
                == event.get("representationId")
                for dep in dependencies
            )
            if same_identity and dependencies <= ready:
                ready.add(extent_id)
                changed = True

    per_track: dict[str, list[Interval]] = {
        track_id: [] for track_id in required_representations
    }

    for extent_id in ready:
        event = final[extent_id]
        track_id = str(event["trackId"])
        required_representation = required_representations.get(track_id)
        if required_representation is None:
            continue
        if event.get("representationId") != required_representation:
            continue

        start = event.get("mediaStartUs")
        end = event.get("mediaEndUs")
        if start is None or end is None:
            continue

        per_track[track_id].append(Interval(int(start), int(end)))

    return {
        track_id: normalize_intervals(intervals)
        for track_id, intervals in per_track.items()
    }


def oracle_snapshot(
    events: Iterable[Mapping[str, object]],
    required_representations: Mapping[str, str],
    file_verified_extent_ids: set[str],
    playhead_us: int,
) -> dict[str, object]:
    per_track = reconstruct_published_coverage(
        events,
        required_representations,
        file_verified_extent_ids,
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
