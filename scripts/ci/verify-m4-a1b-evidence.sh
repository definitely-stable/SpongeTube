#!/usr/bin/env bash
set -euo pipefail

OUTPUT_ROOT="${1:-build/m4-a1b}"
ORIGIN_TRACE="${2:?Media Lab origin trace path is required}"
ADDITIONAL_OUTPUT="core/engine/build/outputs/connected_android_test_additional_output"

rm -rf "$OUTPUT_ROOT"
mkdir -p "$OUTPUT_ROOT/device" "$OUTPUT_ROOT/verified"

test -d "$ADDITIONAL_OUTPUT"
test -s "$ORIGIN_TRACE"
mapfile -t SOURCES < <(
  find "$ADDITIONAL_OUTPUT" -type d -path '*/m4-a1b-evidence/N0' -print
)
if [[ "${#SOURCES[@]}" -ne 1 ]]; then
  printf 'M4-A1b needs exactly one N0 evidence root, got %s\n' "${#SOURCES[@]}" >&2
  exit 1
fi

SOURCE="${SOURCES[0]}"
for item in runtime-coverage.json reserve-summary.json fetch-events.jsonl; do
  test -s "$SOURCE/$item"
  cp "$SOURCE/$item" "$OUTPUT_ROOT/device/$item"
done
test -d "$SOURCE/storage"
cp -R "$SOURCE/storage" "$OUTPUT_ROOT/device/storage"

DATABASE="$OUTPUT_ROOT/device/storage/metadata/extents.db"
test -s "$DATABASE"

python3 scripts/measurement/m1_oracle.py verify-run \
  --database "$DATABASE" \
  --storage-root "$OUTPUT_ROOT/device/storage" \
  --runtime "$OUTPUT_ROOT/device/runtime-coverage.json" \
  --snapshot-id m4-a1b-n0 \
  --session-id m4-a1b-n0 \
  --snapshot-kind LIVE_COMMITTED \
  --playhead-us 0 \
  --media-asset-id fixture:F1 \
  --required video-main=f1-video-0 \
  --required audio-main=f1-audio-1 \
  --committed-output "$OUTPUT_ROOT/verified/committed-extents.json" \
  --verified-output "$OUTPUT_ROOT/verified/verified-extent-files.json" \
  --oracle-output "$OUTPUT_ROOT/verified/oracle-coverage.json"

cp "$ORIGIN_TRACE" "$OUTPUT_ROOT/verified/origin-requests.jsonl"

python3 scripts/measurement/m4_reserve_verifier.py \
  --summary "$OUTPUT_ROOT/device/reserve-summary.json" \
  --fetch-events "$OUTPUT_ROOT/device/fetch-events.jsonl" \
  --origin-trace "$OUTPUT_ROOT/verified/origin-requests.jsonl" \
  --committed "$OUTPUT_ROOT/verified/committed-extents.json" \
  --verified-files "$OUTPUT_ROOT/verified/verified-extent-files.json" \
  --oracle "$OUTPUT_ROOT/verified/oracle-coverage.json" \
  --manifest test-fixtures/media/manifest.json \
  --mpd test-fixtures/media/F1/manifest.mpd \
  --output "$OUTPUT_ROOT/verified/m4-a1b-result.json"

python3 - "$OUTPUT_ROOT/verified/m4-a1b-result.json" <<'PY'
import json
import pathlib
import sys
data = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
if data.get("status") != "PASS":
    raise SystemExit("M4-A1b independent verification failed")
print("M4-A1b exact-head Android F1 N0 verified")
PY
