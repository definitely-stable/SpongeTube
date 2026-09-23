#!/usr/bin/env bash
set -euo pipefail

OUTPUT_ROOT="${1:-build/m1-c-evidence}"
DEVICE_ROOT="/data/local/tmp/spongetube-m1-c"
PULLED_ROOT="$OUTPUT_ROOT/device"

rm -rf "$OUTPUT_ROOT"
mkdir -p "$OUTPUT_ROOT"

adb pull "$DEVICE_ROOT" "$PULLED_ROOT"
test -d "$PULLED_ROOT"

SEEDS=(
  S0
  S10
  S30
  S60
  S120
  S30_VIDEO_HOLE
  S30_AUDIO_HOLE
  S30_MISSING_INIT
  S30_PARTIAL_TAIL
  S30_WRONG_REPRESENTATION
)

for seed in "${SEEDS[@]}"; do
  case_root="$PULLED_ROOT/$seed"
  verified_root="$OUTPUT_ROOT/verified/$seed"
  mkdir -p "$verified_root"

  database="$case_root/storage/metadata/extents.db"
  runtime="$case_root/runtime-coverage.json"
  test -s "$database"
  test -s "$runtime"

  python3 scripts/measurement/m1_oracle.py verify-run \
    --database "$database" \
    --storage-root "$case_root/storage" \
    --runtime "$runtime" \
    --snapshot-id "m1-c-$seed" \
    --session-id "m1-c-$seed" \
    --snapshot-kind LIVE_COMMITTED \
    --playhead-us 0 \
    --media-asset-id fixture:F1 \
    --required video-main=f1-video-0 \
    --required audio-main=f1-audio-1 \
    --committed-output "$verified_root/committed-extents.json" \
    --verified-output "$verified_root/verified-extent-files.json" \
    --oracle-output "$verified_root/oracle-coverage.json"

  python3 scripts/measurement/m1_seed_planner.py \
    --seed-id "$seed" \
    --output "$verified_root/seed-manifest.json" \
    --verify-committed "$verified_root/committed-extents.json"
done

printf 'M1-C evidence verified for %s canonical seeds\n' "${#SEEDS[@]}"
