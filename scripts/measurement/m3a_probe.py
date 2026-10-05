#!/usr/bin/env python3
"""Fail-closed semantic validator for M3-A bounded YouTube VOD probe artifacts.

M3-A is research infrastructure, not a production extractor. The retained
artifact intentionally records capabilities and outcomes while forbidding raw
provider credentials, tokens, signed media URLs and unbounded media transfer.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any, Iterable, Mapping

from schema_subset import SchemaContractError, validate_instance


class M3AProbeError(ValueError):
    pass


MAX_MEDIA_PROBE_BYTES = 1_048_576

_TOOL_LICENSE = {
    "YT_DLP": "UNLICENSE",
    "YOUTUBE_JS": "MIT",
    "BGUTILS": "MIT",
    "CUSTOM_REFERENCE": "UNKNOWN",
}

_VIDEO_CASE_CLASS = {
    "V0": "PUBLIC_EMBEDDABLE_LONG_FORM",
    "V1": "PUBLIC_SPLIT_AV_LONG_FORM",
    "V2": "PUBLIC_CAPABILITY_CONSTRAINING_NEGATIVE",
}

_FORBIDDEN_STRING_FRAGMENTS = (
    "http://",
    "https://",
    "googlevideo.com",
    "youtube.com/",
    "youtu.be/",
    "pot=",
    "poToken",
    "VISITOR_INFO1_LIVE",
    "SAPISID",
    "Authorization:",
    "Cookie:",
    "Bearer ",
)


def _walk_strings(value: Any, path: str = "$") -> Iterable[tuple[str, str]]:
    if isinstance(value, str):
        yield path, value
    elif isinstance(value, list):
        for index, item in enumerate(value):
            yield from _walk_strings(item, f"{path}[{index}]")
    elif isinstance(value, dict):
        for key, item in value.items():
            yield from _walk_strings(item, f"{path}.{key}")


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise M3AProbeError(message)


def validate_probe(
    document: Mapping[str, Any],
    *,
    schema: Mapping[str, Any],
) -> None:
    try:
        validate_instance(dict(schema), dict(document))
    except SchemaContractError as exc:
        raise M3AProbeError(str(exc)) from exc

    tool = document["tool"]
    expected_license = _TOOL_LICENSE[tool["name"]]
    _require(
        tool["license"] == expected_license,
        f"tool {tool['name']} must retain license {expected_license}",
    )

    video = document["video"]
    expected_class = _VIDEO_CASE_CLASS[video["caseId"]]
    _require(
        video["caseClass"] == expected_class,
        f"{video['caseId']}: expected caseClass {expected_class}",
    )

    protocols = document["resolver"]["protocols"]
    _require(
        len(protocols) == len(set(protocols)),
        "resolver.protocols must not contain duplicates",
    )
    _require(
        "NONE" not in protocols or protocols == ["NONE"],
        "resolver.protocols NONE is mutually exclusive",
    )

    outcome = document["resolver"]["outcome"]
    playability = document["resolver"]["playability"]
    if outcome == "SUCCESS":
        _require(playability == "PLAYABLE", "SUCCESS requires PLAYABLE")
        _require(protocols != ["NONE"], "SUCCESS requires at least one delivery protocol")
    if outcome == "BOT_CHECK":
        _require(playability == "BOT_CHECK", "BOT_CHECK outcome requires BOT_CHECK playability")
        _require(not document["delivery"]["attempted"], "BOT_CHECK must not attempt media delivery")
    if outcome in {"UNPLAYABLE", "BLOCKED"}:
        _require(playability != "PLAYABLE", f"{outcome} cannot retain PLAYABLE")

    delivery = document["delivery"]
    if delivery["attempted"]:
        _require(delivery["bytesRequested"] > 0, "attempted delivery requires bytesRequested > 0")
        _require(
            delivery["bytesRequested"] <= MAX_MEDIA_PROBE_BYTES,
            "media probe exceeds 1 MiB budget",
        )
        _require(delivery["httpStatus"] >= 100, "attempted delivery requires an HTTP status")
        _require(
            delivery["rangeResult"] != "NOT_ATTEMPTED",
            "attempted delivery cannot use NOT_ATTEMPTED rangeResult",
        )
    else:
        _require(delivery["bytesRequested"] == 0, "unattempted delivery must request zero bytes")
        _require(delivery["httpStatus"] == 0, "unattempted delivery must use httpStatus=0")
        _require(
            delivery["rangeResult"] == "NOT_ATTEMPTED",
            "unattempted delivery must use NOT_ATTEMPTED rangeResult",
        )

    descriptor = document["descriptor"]
    if descriptor["expiry"] == "PRESENT":
        _require(
            descriptor["expiryHorizonSeconds"] > 0,
            "PRESENT descriptor expiry requires positive horizon",
        )
    else:
        _require(
            descriptor["expiryHorizonSeconds"] == 0,
            "non-PRESENT descriptor expiry must not invent a horizon",
        )

    access = document["access"]
    if access["challengeExecution"] == "REFERENCE_TOOL_EXECUTED":
        _require(
            access["challengeRuntime"] in {"BOTGUARD", "DROIDGUARD", "IOSGUARD"},
            "executed challenge requires a concrete challenge runtime",
        )

    if tool["name"] == "BGUTILS":
        _require(
            not delivery["attempted"],
            "BgUtils is a token/challenge reference and must not own media delivery",
        )

    privacy = document["privacy"]
    _require(
        not any(privacy.values()),
        "M3-A retained privacy flags must all be false",
    )

    for path, value in _walk_strings(document):
        for fragment in _FORBIDDEN_STRING_FRAGMENTS:
            _require(
                fragment not in value,
                f"{path}: forbidden retained provider secret/URL fragment {fragment!r}",
            )


def load_and_validate(path: Path, schema_path: Path) -> dict[str, Any]:
    document = json.loads(path.read_text(encoding="utf-8"))
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    validate_probe(document, schema=schema)
    return document


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("artifact", type=Path)
    parser.add_argument(
        "--schema",
        type=Path,
        default=Path(".work/schemas/m3-a-probe-v1.schema.json"),
    )
    return parser.parse_args()


def main() -> int:
    args = _parse_args()
    load_and_validate(args.artifact, args.schema)
    print("M3-A probe verified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
