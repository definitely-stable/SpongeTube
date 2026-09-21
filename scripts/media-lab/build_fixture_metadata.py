#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import pathlib
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "test-fixtures/media").resolve()


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def ffmpeg_version() -> str:
    first = subprocess.check_output(["ffmpeg", "-version"], text=True).splitlines()[0]
    prefix = "ffmpeg version "
    return first[len(prefix):].split(" Copyright", 1)[0] if first.startswith(prefix) else first


def ffprobe_json(path: pathlib.Path) -> dict:
    output = subprocess.check_output(
        [
            "ffprobe",
            "-v",
            "error",
            "-show_entries",
            "format=duration,size,bit_rate",
            "-show_entries",
            "stream=index,codec_type,codec_name,profile,width,height,r_frame_rate,"
            "sample_rate,channels,bit_rate",
            "-of",
            "json",
            str(path),
        ],
        text=True,
    )
    return json.loads(output)


def resource(relative: str, role: str, content_type: str) -> dict:
    path = ROOT / relative
    return {
        "relativePath": relative,
        "role": role,
        "sizeBytes": path.stat().st_size,
        "sha256": sha256(path),
        "contentType": content_type,
    }


f0_resources = [
    resource("F0/progressive.mp4", "progressive-av", "video/mp4"),
]

f1_resources = [
    resource("F1/manifest.mpd", "dash-manifest", "application/dash+xml"),
]

for path in sorted((ROOT / "F1").glob("*.m4s")):
    name = path.name
    if name.startswith("init-0"):
        role, content_type = "video-init", "video/mp4"
    elif name.startswith("init-1"):
        role, content_type = "audio-init", "audio/mp4"
    elif name.startswith("segment-0-"):
        role, content_type = "video-segment", "video/iso.segment"
    elif name.startswith("segment-1-"):
        role, content_type = "audio-segment", "audio/iso.segment"
    else:
        raise SystemExit(f"Unexpected F1 resource name: {name}")

    f1_resources.append(resource(f"F1/{name}", role, content_type))

f0_payload = sum(item["sizeBytes"] for item in f0_resources)
f1_payload = sum(
    item["sizeBytes"]
    for item in f1_resources
    if item["role"] != "dash-manifest"
)
video_bytes = sum(
    item["sizeBytes"]
    for item in f1_resources
    if item["role"].startswith("video-")
)
audio_bytes = sum(
    item["sizeBytes"]
    for item in f1_resources
    if item["role"].startswith("audio-")
)

f0_duration_ms = 10_000
f1_duration_ms = 180_000
reference_bitrate = round((video_bytes + audio_bytes) * 8 * 1000 / f1_duration_ms)

version = ffmpeg_version()
manifest = {
    "schemaVersion": 1,
    "generator": {
        "name": "FFmpeg",
        "version": version,
        "canonicalBytesGeneratedAt": datetime.now(timezone.utc).date().isoformat(),
        "recommendedRegenerationBaseline": "9.0.2",
    },
    "fixtures": [
        {
            "fixtureId": "F0",
            "kind": "progressive-mp4",
            "durationMs": f0_duration_ms,
            "codecs": ["avc1-main", "mp4a.40.2"],
            "container": "MP4",
            "actualBytes": f0_payload,
            "mediaPayloadBytes": f0_payload,
            "actualAverageBitrateBps": round(f0_payload * 8 * 1000 / f0_duration_ms),
            "referencePlaybackBitrateBps": None,
            "resources": f0_resources,
        },
        {
            "fixtureId": "F1",
            "kind": "static-dash-fmp4",
            "durationMs": f1_duration_ms,
            "codecs": ["avc1.4d401e", "mp4a.40.2"],
            "container": "DASH/fMP4",
            "actualBytes": sum(item["sizeBytes"] for item in f1_resources),
            "mediaPayloadBytes": f1_payload,
            "actualAverageBitrateBps": reference_bitrate,
            "referencePlaybackBitrateBps": reference_bitrate,
            "videoPayloadBytes": video_bytes,
            "audioPayloadBytes": audio_bytes,
            "resources": f1_resources,
        },
    ],
}

(ROOT / "manifest.json").write_text(
    json.dumps(manifest, indent=2) + "\n",
    encoding="utf-8",
)

checksum_lines = []
for fixture in manifest["fixtures"]:
    for item in fixture["resources"]:
        checksum_lines.append(f'{item["sha256"]}  {item["relativePath"]}\n')

(ROOT / "checksums.sha256").write_text(
    "".join(sorted(checksum_lines)),
    encoding="utf-8",
)

with tempfile.TemporaryDirectory(prefix="spongetube-fixtures-") as temp_dir:
    temp = pathlib.Path(temp_dir)
    video = temp / "video-first.mp4"
    audio = temp / "audio-first.mp4"
    video.write_bytes(
        (ROOT / "F1/init-0.m4s").read_bytes()
        + (ROOT / "F1/segment-0-00001.m4s").read_bytes()
    )
    audio.write_bytes(
        (ROOT / "F1/init-1.m4s").read_bytes()
        + (ROOT / "F1/segment-1-00001.m4s").read_bytes()
    )

    dash_ns = {"d": "urn:mpeg:dash:schema:mpd:2011"}
    mpd = ET.parse(ROOT / "F1/manifest.mpd").getroot()
    representations = []
    for adaptation in mpd.findall(".//d:AdaptationSet", dash_ns):
        for representation in adaptation.findall("d:Representation", dash_ns):
            representations.append(
                {
                    "adaptationContentType": adaptation.attrib.get("contentType"),
                    **representation.attrib,
                }
            )

    structure = {
        "schemaVersion": 1,
        "ffprobeVersion": version,
        "F0": ffprobe_json(ROOT / "F0/progressive.mp4"),
        "F1": {
            "mpd": {
                "type": mpd.attrib.get("type"),
                "mediaPresentationDuration": mpd.attrib.get("mediaPresentationDuration"),
                "representations": representations,
            },
            "firstVideoSegment": ffprobe_json(video),
            "firstAudioSegment": ffprobe_json(audio),
        },
    }

(ROOT / "STRUCTURE.json").write_text(
    json.dumps(structure, indent=2) + "\n",
    encoding="utf-8",
)

print(json.dumps({
    "generator": version,
    "F0Bytes": f0_payload,
    "F1Bytes": sum(item["sizeBytes"] for item in f1_resources),
    "referencePlaybackBitrateBps": reference_bitrate,
}, indent=2))
