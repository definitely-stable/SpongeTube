"""Deterministic semantic seed planner for SpongeTube M1.

The planner derives media-time boundaries from the committed F1 DASH MPD. It never
assumes a fixed segment duration or selects seeds by byte count / segment count.
"""

from __future__ import annotations

from dataclasses import dataclass, replace
import json
from pathlib import Path
import xml.etree.ElementTree as ET

from m1_oracle import Interval, durable_reserve_us, intersect_required, normalize_intervals


ASSET_ID = "fixture:F1"
TRACK_IDS = {
    "video": "video-main",
    "audio": "audio-main",
}


@dataclass(frozen=True)
class SeedUnit:
    extent_id: str
    resource_path: str
    track_id: str
    representation_id: str
    media_start_us: int | None
    media_end_us: int | None
    dependency_extent_ids: tuple[str, ...]
    length: int
    sha256: str


@dataclass(frozen=True)
class RejectedAttempt:
    extent_id: str
    expected_length: int
    received_length: int
    reason: str


@dataclass(frozen=True)
class SeedPlan:
    seed_id: str
    media_asset_id: str
    required_representations: dict[str, str]
    target_playable_end_us: int
    units: tuple[SeedUnit, ...]
    negative_case: str | None = None
    rejected_attempts: tuple[RejectedAttempt, ...] = ()

    def coverage(self) -> tuple[dict[str, tuple[Interval, ...]], tuple[Interval, ...], int]:
        by_id = {unit.extent_id: unit for unit in self.units}
        ready: set[str] = set()
        changed = True
        while changed:
            changed = False
            for unit in self.units:
                if unit.extent_id in ready:
                    continue
                dependencies = [by_id.get(item) for item in unit.dependency_extent_ids]
                same_identity = all(
                    dependency is not None
                    and dependency.track_id == unit.track_id
                    and dependency.representation_id == unit.representation_id
                    for dependency in dependencies
                )
                if same_identity and set(unit.dependency_extent_ids) <= ready:
                    ready.add(unit.extent_id)
                    changed = True

        per_track: dict[str, list[Interval]] = {
            track_id: [] for track_id in self.required_representations
        }
        for unit in self.units:
            if unit.extent_id not in ready:
                continue
            if (
                self.required_representations.get(unit.track_id)
                != unit.representation_id
            ):
                continue
            if unit.media_start_us is None or unit.media_end_us is None:
                continue
            per_track[unit.track_id].append(
                Interval(unit.media_start_us, unit.media_end_us)
            )

        normalized = {
            track_id: normalize_intervals(intervals)
            for track_id, intervals in per_track.items()
        }
        playable = intersect_required(
            normalized,
            list(self.required_representations.keys()),
        )
        return normalized, playable, durable_reserve_us(0, playable)


def load_f1_catalog(repo_root: Path) -> tuple[SeedUnit, ...]:
    fixture_manifest = json.loads(
        (repo_root / "test-fixtures/media/manifest.json").read_text(
            encoding="utf-8"
        )
    )
    fixture = next(
        item for item in fixture_manifest["fixtures"] if item["fixtureId"] == "F1"
    )
    resources = {
        item["relativePath"]: item
        for item in fixture["resources"]
    }

    mpd_path = repo_root / "test-fixtures/media/F1/manifest.mpd"
    root = ET.fromstring(mpd_path.read_text(encoding="utf-8"))
    ns = {"d": "urn:mpeg:dash:schema:mpd:2011"}

    units: list[SeedUnit] = []
    for adaptation in root.findall(".//d:AdaptationSet", ns):
        kind = adaptation.attrib["contentType"]
        track_id = TRACK_IDS[kind]
        representation = adaptation.find("d:Representation", ns)
        if representation is None:
            raise ValueError(f"missing representation for {kind}")
        rep_id = representation.attrib["id"]
        representation_id = f"f1-{kind}-{rep_id}"
        template = representation.find("d:SegmentTemplate", ns)
        if template is None:
            raise ValueError(f"missing SegmentTemplate for {kind}")

        timescale = int(template.attrib["timescale"])
        init_path = "F1/" + template.attrib["initialization"].replace(
            "$RepresentationID$",
            rep_id,
        )
        init_resource = resources[init_path]
        init_extent_id = f"f1:{kind}:{rep_id}:init"
        units.append(
            SeedUnit(
                extent_id=init_extent_id,
                resource_path=init_path,
                track_id=track_id,
                representation_id=representation_id,
                media_start_us=None,
                media_end_us=None,
                dependency_extent_ids=(),
                length=int(init_resource["sizeBytes"]),
                sha256=str(init_resource["sha256"]),
            )
        )

        timeline = template.find("d:SegmentTimeline", ns)
        if timeline is None:
            raise ValueError(f"missing SegmentTimeline for {kind}")

        number = int(template.attrib.get("startNumber", "1"))
        current_ticks = 0
        media_template = template.attrib["media"]
        for node in timeline.findall("d:S", ns):
            if "t" in node.attrib:
                current_ticks = int(node.attrib["t"])
            duration = int(node.attrib["d"])
            repeat = int(node.attrib.get("r", "0"))
            if repeat < 0:
                raise ValueError("negative DASH repeats are not supported by F1")

            for _ in range(repeat + 1):
                start_ticks = current_ticks
                end_ticks = start_ticks + duration
                start_us = start_ticks * 1_000_000 // timescale
                end_us = end_ticks * 1_000_000 // timescale
                path = "F1/" + media_template.replace(
                    "$RepresentationID$",
                    rep_id,
                ).replace(
                    "$Number%05d$",
                    f"{number:05d}",
                )
                resource = resources[path]
                units.append(
                    SeedUnit(
                        extent_id=f"f1:{kind}:{rep_id}:{number}",
                        resource_path=path,
                        track_id=track_id,
                        representation_id=representation_id,
                        media_start_us=start_us,
                        media_end_us=end_us,
                        dependency_extent_ids=(init_extent_id,),
                        length=int(resource["sizeBytes"]),
                        sha256=str(resource["sha256"]),
                    )
                )
                current_ticks = end_ticks
                number += 1

    return tuple(units)


def build_seed(repo_root: Path, seed_id: str) -> SeedPlan:
    catalog = load_f1_catalog(repo_root)
    required = {
        "video-main": "f1-video-0",
        "audio-main": "f1-audio-1",
    }
    positive_targets = {
        "S0": 0,
        "S10": 10_000_000,
        "S30": 30_000_000,
        "S60": 60_000_000,
        "S120": 120_000_000,
    }

    if seed_id in positive_targets:
        target = positive_targets[seed_id]
        units = _positive_units(catalog, target)
        plan = SeedPlan(seed_id, ASSET_ID, required, target, units)
        _, _, reserve = plan.coverage()
        if reserve < target:
            raise AssertionError(
                f"{seed_id} produced {reserve}us reserve, target is {target}us"
            )
        return plan

    base = build_seed(repo_root, "S30")
    if seed_id == "S30_VIDEO_HOLE":
        units = tuple(
            unit
            for unit in base.units
            if unit.extent_id != "f1:video:0:2"
        )
        return replace(
            base,
            seed_id=seed_id,
            units=units,
            negative_case="VIDEO_HOLE",
        )

    if seed_id == "S30_AUDIO_HOLE":
        units = tuple(
            unit
            for unit in base.units
            if unit.extent_id != "f1:audio:1:2"
        )
        return replace(
            base,
            seed_id=seed_id,
            units=units,
            negative_case="AUDIO_HOLE",
        )

    if seed_id == "S30_MISSING_INIT":
        units = tuple(
            unit
            for unit in base.units
            if unit.extent_id != "f1:video:0:init"
        )
        return replace(
            base,
            seed_id=seed_id,
            units=units,
            negative_case="MISSING_INIT",
        )

    if seed_id == "S30_PARTIAL_TAIL":
        tail_id = "f1:video:0:3"
        tail = next(unit for unit in base.units if unit.extent_id == tail_id)
        units = tuple(unit for unit in base.units if unit.extent_id != tail_id)
        rejected = RejectedAttempt(
            extent_id=tail.extent_id,
            expected_length=tail.length,
            received_length=max(1, tail.length // 2),
            reason="TRUNCATED_BEFORE_PUBLICATION",
        )
        return replace(
            base,
            seed_id=seed_id,
            units=units,
            negative_case="PARTIAL_TAIL",
            rejected_attempts=(rejected,),
        )

    if seed_id == "S30_WRONG_REPRESENTATION":
        wrong_init_id = "f1:video:alt:init"
        original_init = next(
            unit for unit in base.units
            if unit.extent_id == "f1:video:0:init"
        )
        wrong_init = replace(
            original_init,
            extent_id=wrong_init_id,
            representation_id="f1-video-alt",
        )
        units: list[SeedUnit] = [wrong_init]
        for unit in base.units:
            if unit.extent_id in {"f1:video:0:2", "f1:video:0:3"}:
                units.append(
                    replace(
                        unit,
                        representation_id="f1-video-alt",
                        dependency_extent_ids=(wrong_init_id,),
                    )
                )
            else:
                units.append(unit)
        return replace(
            base,
            seed_id=seed_id,
            units=tuple(units),
            negative_case="WRONG_REPRESENTATION",
        )

    raise ValueError(f"unknown M1 seed: {seed_id}")


def _positive_units(
    catalog: tuple[SeedUnit, ...],
    target_us: int,
) -> tuple[SeedUnit, ...]:
    selected = [
        unit
        for unit in catalog
        if unit.media_start_us is None or unit.media_start_us < target_us
    ]
    return tuple(
        sorted(
            selected,
            key=lambda unit: (
                unit.track_id,
                -1 if unit.media_start_us is None else unit.media_start_us,
                unit.extent_id,
            ),
        )
    )
