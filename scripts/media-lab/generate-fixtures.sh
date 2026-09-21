#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUTPUT_ROOT="${1:-$REPO_ROOT/test-fixtures/media}"

command -v ffmpeg >/dev/null
command -v ffprobe >/dev/null
command -v python3 >/dev/null

rm -rf "$OUTPUT_ROOT/F0" "$OUTPUT_ROOT/F1"
mkdir -p "$OUTPUT_ROOT/F0" "$OUTPUT_ROOT/F1"

ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i 'testsrc2=size=640x360:rate=30' \
  -f lavfi -i 'sine=frequency=880:sample_rate=48000' \
  -t 10 \
  -map 0:v:0 -map 1:a:0 \
  -map_metadata -1 \
  -metadata creation_time=1970-01-01T00:00:00Z \
  -c:v libx264 -preset veryfast -profile:v main -pix_fmt yuv420p \
  -b:v 700k -maxrate 700k -bufsize 1400k \
  -g 60 -keyint_min 60 -sc_threshold 0 -threads 1 \
  -c:a aac -b:a 96k -ar 48000 -ac 2 \
  -movflags +faststart \
  "$OUTPUT_ROOT/F0/progressive.mp4"

(
  cd "$OUTPUT_ROOT/F1"
  ffmpeg -hide_banner -loglevel error -y \
    -f lavfi -i 'testsrc2=size=640x360:rate=30' \
    -f lavfi -i 'sine=frequency=440:sample_rate=48000' \
    -t 180 \
    -map 0:v:0 -map 1:a:0 \
    -map_metadata -1 \
    -metadata creation_time=1970-01-01T00:00:00Z \
    -c:v libx264 -preset veryfast -profile:v main -pix_fmt yuv420p \
    -b:v 550k -maxrate 550k -bufsize 1100k \
    -g 300 -keyint_min 300 -sc_threshold 0 -threads 1 \
    -c:a aac -b:a 64k -ar 48000 -ac 2 \
    -f dash -seg_duration 10 -use_template 1 -use_timeline 1 \
    -adaptation_sets 'id=0,streams=v id=1,streams=a' \
    -init_seg_name 'init-$RepresentationID$.m4s' \
    -media_seg_name 'segment-$RepresentationID$-$Number%05d$.m4s' \
    manifest.mpd
)

python3 "$REPO_ROOT/scripts/media-lab/build_fixture_metadata.py" "$OUTPUT_ROOT"

echo "Generated canonical SpongeTube media fixtures:"
du -sh "$OUTPUT_ROOT/F0" "$OUTPUT_ROOT/F1"
