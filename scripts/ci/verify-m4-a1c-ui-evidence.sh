#!/usr/bin/env bash
set -euo pipefail

OUTPUT="${1:?verified output root required}"
TRACE="${2:?Media Lab request trace required}"
ADDITIONAL="app/build/outputs/connected_android_test_additional_output"

test -s "$TRACE"
test -d "$ADDITIONAL"
mapfile -t SOURCES < <(
  find "$ADDITIONAL" -type d -path '*/m4-a1c-evidence' -print
)
if (( ${#SOURCES[@]} != 1 )); then
  printf 'M4-A1c requires one Android UI evidence root, found %s\n' "${#SOURCES[@]}" >&2
  exit 1
fi

rm -rf "$OUTPUT"
mkdir -p "$OUTPUT/device" "$OUTPUT/verified"
for name in ui-summary.json runtime-coverage.json; do
  test -s "${SOURCES[0]}/$name"
  cp "${SOURCES[0]}/$name" "$OUTPUT/device/$name"
done
test -d "${SOURCES[0]}/storage"
cp -R "${SOURCES[0]}/storage" "$OUTPUT/device/storage"
test -s "$OUTPUT/device/storage/metadata/extents.db"

python3 scripts/measurement/m1_oracle.py verify-run \
  --database "$OUTPUT/device/storage/metadata/extents.db" \
  --storage-root "$OUTPUT/device/storage" \
  --runtime "$OUTPUT/device/runtime-coverage.json" \
  --snapshot-id m4-a1c-debug-f1 \
  --session-id m4-a1c-f1-debug \
  --snapshot-kind LIVE_COMMITTED \
  --playhead-us 0 \
  --media-asset-id fixture:F1 \
  --required video-main=f1-video-0 \
  --required audio-main=f1-audio-1 \
  --committed-output "$OUTPUT/verified/committed-extents.json" \
  --verified-output "$OUTPUT/verified/verified-extent-files.json" \
  --oracle-output "$OUTPUT/verified/oracle-coverage.json"

cp "$TRACE" "$OUTPUT/verified/origin-requests.jsonl"

python3 scripts/measurement/m4_ui_verifier.py \
  --ui-summary "$OUTPUT/device/ui-summary.json" \
  --origin-trace "$OUTPUT/verified/origin-requests.jsonl" \
  --committed "$OUTPUT/verified/committed-extents.json" \
  --verified-files "$OUTPUT/verified/verified-extent-files.json" \
  --oracle "$OUTPUT/verified/oracle-coverage.json" \
  --manifest test-fixtures/media/manifest.json \
  --mpd test-fixtures/media/F1/manifest.mpd \
  --output "$OUTPUT/verified/m4-a1c-result.json"

python3 - "$OUTPUT/verified/m4-a1c-result.json" <<'PY'
import json
import pathlib
import sys
result = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
assert result["status"] == "PASS"
assert result["originRequestCount"] == 4
assert result["independentDurableReserveUs"] == 9_941_333
assert result["repeatFetchUnits"] == 0
print("M4-A1c exact-head Android debug UI independently verified")
PY
