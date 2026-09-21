# SpongeTube canonical media fixtures

The committed payload bytes are the canonical test corpus. Normal CI verifies them and never regenerates them.

## Corpus

- **F0** — 10 s progressive MP4, H.264 Main + AAC-LC, fast-start; 1,054,544 bytes; 843,635 bit/s measured container average.
- **F1** — 180 s static DASH/fMP4, exactly one H.264 Main 640×360 video representation and one AAC-LC 48 kHz stereo audio representation; 13,956,166 bytes including MPD; 620,180 bit/s reference playback bitrate.

F1 has 18 exactly 10 s video media segments. AAC frame alignment produces 18 near-10 s audio segments plus one final 53.3 ms tail segment; the MPD SegmentTimeline still covers exactly 180 s. The normalized MPD declares `maxSegmentDuration=PT10.006S`, computed from the actual timelines rather than FFmpeg's requested 10 s target. This is part of the frozen B3 packaging shape.

F1 deliberately uses explicit segmented fMP4 rather than single-file/range-addressable DASH. M0-B needs a stable baseline independent of Media3 behavior; Range semantics are already isolated by the HTTP-origin contract and F0. A later diagnostic fixture may add a range-heavy DASH packaging shape if M0-C exposes a concrete question.

## Generator

Canonical B3 generation targets **FFmpeg 9.0.2**.

The generation workflow downloads the official release tarball, verifies the release signature and SHA-256, builds FFmpeg with system libx264, then executes:

    bash scripts/media-lab/generate-fixtures.sh

FFmpeg 9.0.2 source archive SHA-256:

    8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e

The exact build version used for the committed bytes is recorded in `manifest.json` and `STRUCTURE.json`.

Byte-identical output from another FFmpeg build is not assumed. Regenerating payloads is a reviewed corpus change: update bytes, manifest, checksums, structural evidence and DASH conformance evidence together.

## Determinism controls

The recipes use:

- synthetic `lavfi` sources only;
- fixed resolution/frame/audio rates;
- fixed H.264 GOPs aligned with intended segment boundaries;
- `-threads 1` for the encoder;
- fixed metadata/creation time;
- fixed DASH naming and segment duration;
- one video + one audio representation only.

## Verification levels

Ordinary `check` verifies:

1. exact payload set;
2. byte sizes;
3. SHA-256;
4. manifest/checksum agreement;
5. F0 size/fast-start contract;
6. F1 role counts/reference bitrate;
7. static MPD shape and exactly one video/audio representation.

`STRUCTURE.json` captures ffprobe evidence generated with the corpus.

DASH-IF Conformance 3.0.0 is a fixture-change evidence gate, not an every-PR dependency. It validates the MPD/segments using the official toolchain and MP4Box.

## Android bridge

Only the Media Lab **data port** is reversed:

    bash scripts/media-lab/adb-reverse.sh <host-data-port>

PowerShell:

    ./scripts/media-lab/adb-reverse.ps1 -HostDataPort <host-data-port>

The app uses:

    http://localhost:18080/

Debug builds use a source-set-only Network Security Config that permits cleartext for **localhost only**. The base debug policy remains cleartext-denied and release/main behavior is unchanged.

`adb reverse` is deterministic media plumbing only. It is not evidence for VPN/default-route behavior; those M2 scenarios must traverse Android's actual selected network.
