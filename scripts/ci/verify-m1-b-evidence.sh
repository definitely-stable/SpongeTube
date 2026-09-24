#!/usr/bin/env bash
set -euo pipefail

OUTPUT_ROOT="${1:-build/android-smoke/m1-b-evidence}"
ADDITIONAL_OUTPUT_ROOT="core/storage/build/outputs/connected_android_test_additional_output"

rm -rf "$OUTPUT_ROOT"
mkdir -p "$OUTPUT_ROOT"/{device,verified}

mapfile -t EVIDENCE_ROOTS < <(
  find "$ADDITIONAL_OUTPUT_ROOT" \
    -type d \
    -path '*/m1-b-evidence' \
    -print
)

if [[ "${#EVIDENCE_ROOTS[@]}" -ne 1 ]]; then
  printf 'expected exactly one collected M1-B evidence root, found %s\n' \
    "${#EVIDENCE_ROOTS[@]}" >&2
  printf '%s\n' "${EVIDENCE_ROOTS[@]}" >&2
  exit 1
fi

cp -R "${EVIDENCE_ROOTS[0]}/." "$OUTPUT_ROOT/device/"

mapfile -t CASES < <(
  find "$OUTPUT_ROOT/device" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort
)

if [[ "${#CASES[@]}" -ne 9 ]]; then
  printf 'expected 9 canonical M1-B cases, found %s\n' "${#CASES[@]}" >&2
  exit 1
fi

for case_id in "${CASES[@]}"; do
  case_root="$OUTPUT_ROOT/device/$case_id"
  verified_root="$OUTPUT_ROOT/verified/$case_id"
  mkdir -p "$verified_root"

  test -s "$case_root/case.json"
  test -s "$case_root/recovery-report.json"
  test -s "$case_root/extent-events-v1.jsonl"
  test -s "$case_root/storage/metadata/extents.db"

  session_id="$(jq -er '.sessionId' "$case_root/case.json")"

  python3 scripts/measurement/m1_oracle.py export-db \
    --database "$case_root/storage/metadata/extents.db" \
    --snapshot-id "$case_id-committed" \
    --session-id "$session_id" \
    --snapshot-kind POST_RECOVERY \
    --output "$verified_root/committed-extents.json"

  python3 scripts/measurement/m1_oracle.py verify-files \
    --committed "$verified_root/committed-extents.json" \
    --storage-root "$case_root/storage" \
    --snapshot-id "$case_id-files" \
    --output "$verified_root/verified-extent-files.json"

  python3 scripts/measurement/m1_oracle.py reconstruct \
    --committed "$verified_root/committed-extents.json" \
    --verified "$verified_root/verified-extent-files.json" \
    --playhead-us 0 \
    --media-asset-id fixture:M1-B \
    --required video-main=m1-b-video \
    --output "$verified_root/oracle-coverage.json"
done

python3 scripts/measurement/m1_storage_evidence.py \
  --cases-root "$OUTPUT_ROOT/device" \
  --verified-root "$OUTPUT_ROOT/verified" \
  --output "$OUTPUT_ROOT/verification-summary.json"

jq -e '
  .status == "PASS" and
  .caseCount == 9 and
  .gateCounts["M1-ACC-01"] == 1 and
  .gateCounts["M1-ACC-02"] == 6 and
  .gateCounts["M1-ACC-03"] == 2
' "$OUTPUT_ROOT/verification-summary.json" >/dev/null

printf 'M1-B canonical evidence verified: ACC-01/02/03\n'
