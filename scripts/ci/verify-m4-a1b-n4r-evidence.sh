#!/usr/bin/env bash
set -euo pipefail

ROOT="${1:?verified output directory required}"
ORIGIN="${2:?Media Lab origin trace required}"
GATE="${3:?Media Lab physical gate trace required}"
ADDITIONAL="core/engine/build/outputs/connected_android_test_additional_output"

test -s "$ORIGIN"
test -s "$GATE"
test -d "$ADDITIONAL"
mapfile -t CASES < <(
  find "$ADDITIONAL" -type d -path '*/m4-a1b-n4r-evidence' -print
)
if (( ${#CASES[@]} != 1 )); then
  printf 'expected one N4R device artifact, found %s\n' "${#CASES[@]}" >&2
  exit 1
fi

rm -rf "$ROOT"
mkdir -p "$ROOT/device" "$ROOT/verified"
for name in runtime-coverage.json reserve-summary.json recovery-budget.json \
  failure-decision-events.json fetch-events.jsonl; do
  test -s "${CASES[0]}/$name"
  cp "${CASES[0]}/$name" "$ROOT/device/$name"
done
test -d "${CASES[0]}/storage"
cp -R "${CASES[0]}/storage" "$ROOT/device/storage"
test -s "$ROOT/device/storage/metadata/extents.db"

python3 scripts/measurement/m1_oracle.py verify-run \
  --database "$ROOT/device/storage/metadata/extents.db" \
  --storage-root "$ROOT/device/storage" \
  --runtime "$ROOT/device/runtime-coverage.json" \
  --snapshot-id m4-a1b-n4r \
  --session-id m4-a1b-n4r \
  --snapshot-kind LIVE_COMMITTED \
  --playhead-us 0 \
  --media-asset-id fixture:F1 \
  --required video-main=f1-video-0 \
  --required audio-main=f1-audio-1 \
  --committed-output "$ROOT/verified/committed-extents.json" \
  --verified-output "$ROOT/verified/verified-extent-files.json" \
  --oracle-output "$ROOT/verified/oracle-coverage.json"

cp "$ORIGIN" "$ROOT/verified/origin-requests.jsonl"
cp "$GATE" "$ROOT/verified/origin-gate.jsonl"

python3 scripts/measurement/m4_n4r_reserve_verifier.py \
  --summary "$ROOT/device/reserve-summary.json" \
  --fetch-events "$ROOT/device/fetch-events.jsonl" \
  --recovery-budget "$ROOT/device/recovery-budget.json" \
  --failure-events "$ROOT/device/failure-decision-events.json" \
  --origin-trace "$ROOT/verified/origin-requests.jsonl" \
  --gate-trace "$ROOT/verified/origin-gate.jsonl" \
  --committed "$ROOT/verified/committed-extents.json" \
  --verified-files "$ROOT/verified/verified-extent-files.json" \
  --oracle "$ROOT/verified/oracle-coverage.json" \
  --manifest test-fixtures/media/manifest.json \
  --mpd test-fixtures/media/F1/manifest.mpd \
  --output "$ROOT/verified/m4-a1b-n4r-result.json"

python3 - "$ROOT/verified/m4-a1b-n4r-result.json" <<'PY'
import json
import pathlib
import sys
evidence = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
assert evidence["status"] == "PASS"
assert evidence["originRequestCount"] == 6
assert evidence["logicalRecoveryChains"] == 4
assert evidence["independentDurableReserveUs"] == 9_941_333
assert evidence["recoveryGates"] == {
    "M2-ACC-05": "PASS",
    "M2-ACC-06": "PASS",
}
print("M4-A1b N4R exact-head Android physical body restore verified")
PY
