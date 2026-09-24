#!/usr/bin/env bash
set -euo pipefail

# M1-E PlaybackBridge evidence verification.
#  1. collects the per-case bridge/fetch evidence produced by
#     PlaybackBridgeAndroidTest (E1-E6);
#  2. verifies bridge-events-v1 + fetch-events-v3 against the Media Lab origin
#     trace (schema first, then fetch join, origin bijection, ACC-09/ACC-10
#     and per-case checks) with scripts/measurement/m1_bridge_evidence.py;
#  3. independently reconstructs post-run coverage from the copied Room
#     database + immutable files and compares it with the runtime snapshot
#     (scripts/measurement/m1_oracle.py, same kernel as M1-C).

OUTPUT_ROOT="${1:-build/android-smoke/m1-e-evidence}"
ORIGIN_TRACE="${2:?origin trace path is required}"
ADDITIONAL_OUTPUT_ROOT="playback/bridge/build/outputs/connected_android_test_additional_output"
CASES=(E1 E2 E3 E4 E5 E6)

rm -rf "$OUTPUT_ROOT"
mkdir -p "$OUTPUT_ROOT/device" "$OUTPUT_ROOT/verified"

test -d "$ADDITIONAL_OUTPUT_ROOT"
test -s "$ORIGIN_TRACE"

mapfile -t EVIDENCE_ROOTS < <(
  find "$ADDITIONAL_OUTPUT_ROOT" \
    -type d \
    -path '*/m1-e-evidence' \
    -print
)

if [[ "${#EVIDENCE_ROOTS[@]}" -ne 1 ]]; then
  printf 'expected exactly one collected M1-E evidence root, found %s\n' \
    "${#EVIDENCE_ROOTS[@]}" >&2
  printf '%s\n' "${EVIDENCE_ROOTS[@]}" >&2
  exit 1
fi
COLLECTED_ROOT="${EVIDENCE_ROOTS[0]}"

for case_id in "${CASES[@]}"; do
  source_root="$COLLECTED_ROOT/$case_id"
  case_root="$OUTPUT_ROOT/device/$case_id"
  test -s "$source_root/bridge-events-v1.jsonl"
  test -f "$source_root/fetch-events-v3.jsonl"
  test -s "$source_root/case.json"
  test -s "$source_root/runtime-coverage.json"
  test -d "$source_root/storage"
  mkdir -p "$case_root"
  cp -R "$source_root/." "$case_root/"
done

cp "$ORIGIN_TRACE" "$OUTPUT_ROOT/verified/origin-requests.jsonl"

python3 scripts/measurement/m1_bridge_evidence.py \
  --cases-root "$OUTPUT_ROOT/device" \
  --origin-trace "$OUTPUT_ROOT/verified/origin-requests.jsonl" \
  --output "$OUTPUT_ROOT/verified/verification-summary.json"

for case_id in "${CASES[@]}"; do
  case_root="$OUTPUT_ROOT/device/$case_id"
  verified_root="$OUTPUT_ROOT/verified/$case_id"
  mkdir -p "$verified_root"

  test -s "$case_root/storage/metadata/extents.db"

  python3 scripts/measurement/m1_oracle.py verify-run \
    --database "$case_root/storage/metadata/extents.db" \
    --storage-root "$case_root/storage" \
    --runtime "$case_root/runtime-coverage.json" \
    --snapshot-id "m1-e-$case_id" \
    --session-id "m1-e-$case_id" \
    --snapshot-kind LIVE_COMMITTED \
    --playhead-us 0 \
    --media-asset-id fixture:F1 \
    --required video-main=f1-video-0 \
    --required audio-main=f1-audio-1 \
    --committed-output "$verified_root/committed-extents.json" \
    --verified-output "$verified_root/verified-extent-files.json" \
    --oracle-output "$verified_root/oracle-coverage.json"
done

python3 - "$OUTPUT_ROOT/verified/verification-summary.json" <<'PY'
import json
import pathlib
import sys
payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
if payload.get("status") != "PASS":
    raise SystemExit("M1-E verification did not pass")
origin = payload["origin"]
print(
    "M1-E evidence verified: "
    f"originDataPlaneRequests={origin['originDataPlaneRequests']} "
    f"brokerCompletedAttempts={origin['brokerCompletedAttempts']} "
    f"cases={','.join(sorted(payload['cases']))}"
)
PY
