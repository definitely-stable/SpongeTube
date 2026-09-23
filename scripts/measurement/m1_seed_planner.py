"""Deterministic M1 semantic seed construction producer.

This module is intentionally NOT a coverage oracle. It derives which immutable F1
resources must be attempted/published for each canonical seed from the committed DASH
SegmentTimeline and fixture manifest. Runtime CoverageIndex plus m1_oracle.py prove the
resulting coverage independently.
"""

from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass, replace
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


ASSET_ID = "fixture:F1"
TRACK_IDS = {
    "video": "video-main",
    "audio": "audio-main",
}
POSITIVE_TARGETS_US = {
    "S0": 0,
    "S10": 10_000_000,
    "S30": 30_000_000,
    "S60": 60_000_000,
    "S120": 120_000_000,
}


@dataclass(frozen=True)
class SeedUnit:
    extent_id: str
    fixture_resource_path: str
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
    fixture_resource_path: str
    expected_length: int
    received_length: int
    expected_sha256: str
    reason: str


@dataclass(frozen=True)
class SeedPlan:
    seed_id: str
    media_asset_id: str
    fixture_id: str
    timeline_resource_path: str
    timeline_resource_sha256: str
    required_representations: dict[str, str]
    playhead_us: int
    target_playable_end_us: int
    units: tuple[SeedUnit, ...]
    negative_case: str | None = None
    rejected_attempts: tuple[RejectedAttempt, ...] = ()

    @property
    def seed_class(self) -> str:
        return "NEGATIVE" if self.negative_case is not None else "POSITIVE"

    def to_artifact(self) -> dict[str, object]:
        required_track_ids = sorted(self.required_representations)
        return {
            "schemaVersion": 2,
            "seedId": self.seed_id,
            "playheadUs": self.playhead_us,
            "seedClass": self.seed_class,
            "mediaAssetId": self.media_asset_id,
            "fixtureId": self.fixture_id,
            "timelineResourcePath": self.timeline_resource_path,
            "timelineResourceSha256": self.timeline_resource_sha256,
            "requiredTrackIds": required_track_ids,
            "requiredRepresentations": {
                track_id: self.required_representations[track_id]
                for track_id in required_track_ids
            },
            "targetPlayableEndUs": self.target_playable_end_us,
            "extents": [
                {
                    "extentId": unit.extent_id,
                    "fixtureResourcePath": unit.fixture_resource_path,
                    "trackId": unit.track_id,
                    "representationId": unit.representation_id,
                    "mediaStartUs": unit.media_start_us,
                    "mediaEndUs": unit.media_end_us,
                    "dependencyExtentIds": list(unit.dependency_extent_ids),
                    "length": unit.length,
                    "sha256": unit.sha256,
                }
                for unit in self.units
            ],
            "negativeCase": self.negative_case,
            "rejectedAttempts": [
                {
                    "extentId": attempt.extent_id,
                    "fixtureResourcePath": attempt.fixture_resource_path,
                    "expectedLength": attempt.expected_length,
                    "receivedLength": attempt.received_length,
                    "expectedSha256": attempt.expected_sha256,
                    "reason": attempt.reason,
                }
                for attempt in self.rejected_attempts
            ],
        }


def load_f1_catalog(repo_root: Path) -> tuple[tuple[SeedUnit, ...], str]:
    fixture_manifest_path = repo_root / "test-fixtures/media/manifest.json"
    fixture_manifest = json.loads(
        fixture_manifest_path.read_text(encoding="utf-8")
    )
    fixture = next(
        item
        for item in fixture_manifest["fixtures"]
        if item["fixtureId"] == "F1"
    )
    resources = {
        str(item["relativePath"]): item
        for item in fixture["resources"]
    }

    timeline_path = "F1/manifest.mpd"
    timeline_resource = resources.get(timeline_path)
    if timeline_resource is None:
        raise ValueError("F1 fixture manifest does not contain F1/manifest.mpd")

    mpd_path = repo_root / "test-fixtures/media" / timeline_path
    root = ET.fromstring(mpd_path.read_text(encoding="utf-8"))
    ns = {"d": "urn:mpeg:dash:schema:mpd:2011"}

    units: list[SeedUnit] = []
    required: dict[str, str] = {}

    for adaptation in root.findall(".//d:AdaptationSet", ns):
        kind = adaptation.attrib.get("contentType")
        if kind not in TRACK_IDS:
            continue

        track_id = TRACK_IDS[kind]
        representations = adaptation.findall("d:Representation", ns)
        if len(representations) != 1:
            raise ValueError(
                f"F1 requires exactly one {kind} representation, "
                f"found {len(representations)}"
            )
        representation = representations[0]
        rep_id = representation.attrib["id"]
        representation_id = f"f1-{kind}-{rep_id}"
        required[track_id] = representation_id

        template = representation.find("d:SegmentTemplate", ns)
        if template is None:
            raise ValueError(f"missing SegmentTemplate for {kind}")

        timescale = int(template.attrib["timescale"])
        if timescale <= 0:
            raise ValueError(f"invalid SegmentTemplate timescale for {kind}")

        init_path = "F1/" + template.attrib["initialization"].replace(
            "$RepresentationID$",
            rep_id,
        )
        init_resource = _resource(resources, init_path)
        init_extent_id = f"f1:{kind}:{rep_id}:init"
        units.append(
            SeedUnit(
                extent_id=init_extent_id,
                fixture_resource_path=init_path,
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
        previous_end_us: int | None = None

        for node in timeline.findall("d:S", ns):
            if "t" in node.attrib:
                current_ticks = int(node.attrib["t"])
            duration = int(node.attrib["d"])
            repeat = int(node.attrib.get("r", "0"))
            if duration <= 0:
                raise ValueError(f"non-positive DASH duration for {kind}")
            if repeat < 0:
                raise ValueError("negative DASH repeats are not supported by F1")

            for _ in range(repeat + 1):
                start_ticks = current_ticks
                end_ticks = start_ticks + duration
                start_us = start_ticks * 1_000_000 // timescale
                end_us = end_ticks * 1_000_000 // timescale
                if previous_end_us is not None and start_us != previous_end_us:
                    raise ValueError(
                        f"F1 {kind} timeline is not contiguous: "
                        f"{previous_end_us} -> {start_us}"
                    )

                relative_path = media_template.replace(
                    "$RepresentationID$",
                    rep_id,
                ).replace(
                    "$Number%05d$",
                    f"{number:05d}",
                )
                path = "F1/" + relative_path
                resource = _resource(resources, path)

                units.append(
                    SeedUnit(
                        extent_id=f"f1:{kind}:{rep_id}:{number}",
                        fixture_resource_path=path,
                        track_id=track_id,
                        representation_id=representation_id,
                        media_start_us=start_us,
                        media_end_us=end_us,
                        dependency_extent_ids=(init_extent_id,),
                        length=int(resource["sizeBytes"]),
                        sha256=str(resource["sha256"]),
                    )
                )
                previous_end_us = end_us
                current_ticks = end_ticks
                number += 1

    if set(required) != {"video-main", "audio-main"}:
        raise ValueError(f"unexpected F1 playback requirements: {required!r}")

    return tuple(units), str(timeline_resource["sha256"])


def build_seed(repo_root: Path, seed_id: str) -> SeedPlan:
    catalog, timeline_sha256 = load_f1_catalog(repo_root)
    required = _required_representations(catalog)

    if seed_id in POSITIVE_TARGETS_US:
        target = POSITIVE_TARGETS_US[seed_id]
        units = _positive_units(catalog, target)
        _assert_selected_reaches_target(units, required, target)
        return SeedPlan(
            seed_id=seed_id,
            media_asset_id=ASSET_ID,
            fixture_id="F1",
            timeline_resource_path="F1/manifest.mpd",
            timeline_resource_sha256=timeline_sha256,
            required_representations=required,
            playhead_us=0,
            target_playable_end_us=target,
            units=units,
        )

    base = build_seed(repo_root, "S30")

    if seed_id == "S30_VIDEO_HOLE":
        return replace(
            base,
            seed_id=seed_id,
            units=_without(base.units, "f1:video:0:2"),
            negative_case="VIDEO_HOLE",
        )

    if seed_id == "S30_AUDIO_HOLE":
        return replace(
            base,
            seed_id=seed_id,
            units=_without(base.units, "f1:audio:1:2"),
            negative_case="AUDIO_HOLE",
        )

    if seed_id == "S30_MISSING_INIT":
        return replace(
            base,
            seed_id=seed_id,
            units=_without(base.units, "f1:video:0:init"),
            negative_case="MISSING_INIT",
        )

    if seed_id == "S30_PARTIAL_TAIL":
        tail_id = "f1:video:0:3"
        tail = next(unit for unit in base.units if unit.extent_id == tail_id)
        return replace(
            base,
            seed_id=seed_id,
            units=_without(base.units, tail_id),
            negative_case="PARTIAL_TAIL",
            rejected_attempts=(
                RejectedAttempt(
                    extent_id=tail.extent_id,
                    fixture_resource_path=tail.fixture_resource_path,
                    expected_length=tail.length,
                    received_length=max(1, tail.length // 2),
                    expected_sha256=tail.sha256,
                    reason="TRUNCATED_BEFORE_PUBLICATION",
                ),
            ),
        )

    if seed_id == "S30_WRONG_REPRESENTATION":
        wrong_init_id = "f1:video:alt:init"
        original_init = next(
            unit
            for unit in base.units
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
                number = unit.extent_id.rsplit(":", 1)[1]
                units.append(
                    replace(
                        unit,
                        extent_id=f"f1:video:alt:{number}",
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


def _required_representations(
    catalog: tuple[SeedUnit, ...],
) -> dict[str, str]:
    result: dict[str, str] = {}
    for unit in catalog:
        existing = result.setdefault(
            unit.track_id,
            unit.representation_id,
        )
        if existing != unit.representation_id:
            raise ValueError(
                f"multiple canonical representations for {unit.track_id}"
            )
    return result


def _positive_units(
    catalog: tuple[SeedUnit, ...],
    target_us: int,
) -> tuple[SeedUnit, ...]:
    return tuple(
        sorted(
            (
                unit
                for unit in catalog
                if unit.media_start_us is None
                or unit.media_start_us < target_us
            ),
            key=lambda unit: (
                unit.track_id,
                -1 if unit.media_start_us is None else unit.media_start_us,
                unit.extent_id,
            ),
        )
    )


def _assert_selected_reaches_target(
    units: tuple[SeedUnit, ...],
    required: dict[str, str],
    target_us: int,
) -> None:
    if target_us == 0:
        if any(unit.media_start_us is not None for unit in units):
            raise AssertionError("S0 must contain dependency/init units only")
        return

    for track_id, representation_id in required.items():
        media = [
            unit
            for unit in units
            if unit.track_id == track_id
            and unit.representation_id == representation_id
            and unit.media_start_us is not None
        ]
        if not media:
            raise AssertionError(
                f"seed has no media units for required track {track_id}"
            )
        if media[0].media_start_us != 0:
            raise AssertionError(
                f"seed does not start at zero for required track {track_id}"
            )
        if int(media[-1].media_end_us) < target_us:
            raise AssertionError(
                f"seed ends before target for required track {track_id}: "
                f"{media[-1].media_end_us} < {target_us}"
            )


def _without(
    units: tuple[SeedUnit, ...],
    extent_id: str,
) -> tuple[SeedUnit, ...]:
    result = tuple(unit for unit in units if unit.extent_id != extent_id)
    if len(result) == len(units):
        raise ValueError(f"seed extent does not exist: {extent_id}")
    return result


def _resource(
    resources: dict[str, dict[str, object]],
    path: str,
) -> dict[str, object]:
    resource = resources.get(path)
    if resource is None:
        raise ValueError(f"F1 resource missing from fixture manifest: {path}")
    length = int(resource["sizeBytes"])
    digest = str(resource["sha256"])
    if length <= 0:
        raise ValueError(f"F1 resource has invalid length: {path}")
    if len(digest) != 64 or any(ch not in "0123456789abcdef" for ch in digest):
        raise ValueError(f"F1 resource has invalid sha256: {path}")
    return resource


def write_seed_manifest(
    repo_root: Path,
    seed_id: str,
    output: Path,
) -> None:
    payload = build_seed(repo_root, seed_id).to_artifact()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(payload, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Produce deterministic SpongeTube M1 seed construction."
    )
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=Path(__file__).resolve().parents[2],
    )
    parser.add_argument("--seed-id", required=True)
    parser.add_argument("--output", required=True, type=Path)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _build_parser().parse_args(argv)
    try:
        write_seed_manifest(
            repo_root=args.repo_root,
            seed_id=args.seed_id,
            output=args.output,
        )
    except (OSError, ValueError, AssertionError, KeyError, ET.ParseError) as error:
        print(f"seed planner error: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
