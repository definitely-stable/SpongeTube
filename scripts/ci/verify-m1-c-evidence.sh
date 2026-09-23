#!/usr/bin/env bash
set -euo pipefail

OUTPUT_ROOT="${1:-build/m1-c-evidence}"
PULLED_ROOT="$OUTPUT_ROOT/device"

rm -rf "$OUTPUT_ROOT"
mkdir -p "$PULLED_ROOT"

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

ADDITIONAL_OUTPUT_ROOT="core/engine/build/outputs/connected_android_test_additional_output"
test -d "$ADDITIONAL_OUTPUT_ROOT"

mapfile -t EVIDENCE_ROOTS < <(
  find "$ADDITIONAL_OUTPUT_ROOT" \
    -type d \
    -path '*/m1-c-evidence' \
    -print
)

if [[ "${#EVIDENCE_ROOTS[@]}" -ne 1 ]]; then
  printf 'expected exactly one collected M1-C evidence root, found %s\n' \
    "${#EVIDENCE_ROOTS[@]}" >&2
  printf '%s\n' "${EVIDENCE_ROOTS[@]}" >&2
  exit 1
fi
COLLECTED_ROOT="${EVIDENCE_ROOTS[0]}"

for seed in "${SEEDS[@]}"; do
  source_root="$COLLECTED_ROOT/$seed"
  case_root="$PULLED_ROOT/$seed"
  test -d "$source_root/storage"
  test -s "$source_root/runtime-coverage.json"
  mkdir -p "$case_root"
  cp -R "$source_root/storage" "$case_root/storage"
  cp "$source_root/runtime-coverage.json" \
    "$case_root/runtime-coverage.json"
done

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
