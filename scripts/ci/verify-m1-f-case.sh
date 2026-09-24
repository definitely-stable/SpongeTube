#!/usr/bin/env bash
set -euo pipefail

CASE_ROOT="$1"
OUTPUT_ROOT="$2"
ORIGIN_TRACE="${3:-}"
GATE_TRACE="${4:-}"

test -s "$CASE_ROOT/case.json"
test -s "$CASE_ROOT/coverage-after-v2.json"
test -s "$CASE_ROOT/storage/metadata/extents.db"

mkdir -p "$OUTPUT_ROOT"

session_id="$(jq -r '.sessionId' "$CASE_ROOT/case.json")"
scenario_id="$(jq -r '.scenarioId' "$CASE_ROOT/case.json")"
playhead_us="$(jq -r '.playheadUs' "$CASE_ROOT/coverage-after-v2.json")"

test -n "$session_id"
test "$session_id" != "null"
test "$playhead_us" -ge 0

snapshot_kind="LIVE_COMMITTED"
if [[ "$scenario_id" == "PROCESS_DEATH" ]]; then
  snapshot_kind="POST_RECOVERY"
  test -s "$CASE_ROOT/coverage-before-v2.json"
fi

python3 scripts/measurement/m1_oracle.py verify-run   --database "$CASE_ROOT/storage/metadata/extents.db"   --storage-root "$CASE_ROOT/storage"   --runtime "$CASE_ROOT/coverage-after-v2.json"   --snapshot-id "$session_id-post"   --session-id "$session_id"   --snapshot-kind "$snapshot_kind"   --playhead-us "$playhead_us"   --media-asset-id fixture:F1   --required video-main=f1-video-0   --required audio-main=f1-audio-1   --committed-output "$OUTPUT_ROOT/committed-extents.json"   --verified-output "$OUTPUT_ROOT/verified-extent-files.json"   --oracle-output "$OUTPUT_ROOT/oracle-coverage.json"

args=(
  --case "$CASE_ROOT/case.json"
  --coverage-after "$CASE_ROOT/coverage-after-v2.json"
  --oracle-coverage "$OUTPUT_ROOT/oracle-coverage.json"
  --output "$OUTPUT_ROOT/recovery-summary-v2.json"
)

if [[ -s "$CASE_ROOT/coverage-before-v2.json" ]]; then
  args+=(--coverage-before "$CASE_ROOT/coverage-before-v2.json")
fi
if [[ -s "$CASE_ROOT/recovery-timeline-v1.jsonl" ]]; then
  args+=(--timeline "$CASE_ROOT/recovery-timeline-v1.jsonl")
fi
if [[ -s "$CASE_ROOT/fetch-events-v3.jsonl" ]]; then
  args+=(--fetch-events "$CASE_ROOT/fetch-events-v3.jsonl")
fi
if [[ -n "$ORIGIN_TRACE" ]]; then
  test -s "$ORIGIN_TRACE"
  cp "$ORIGIN_TRACE" "$OUTPUT_ROOT/origin-requests.jsonl"
  args+=(--origin-trace "$ORIGIN_TRACE")
fi
if [[ -n "$GATE_TRACE" ]]; then
  test -s "$GATE_TRACE"
  cp "$GATE_TRACE" "$OUTPUT_ROOT/origin-gate-events-v1.jsonl"
  args+=(--gate-events "$GATE_TRACE")
fi

python3 scripts/measurement/m1_recovery_evidence.py "${args[@]}"

jq -e '
  .schemaVersion == 2 and
  .status == "PASS" and
  .coverage.exactMatch == true and
  .invariants.boundedAttempts == true and
  .invariants.noOverlappingOwners == true and
  .invariants.originBijection == true and
  .invariants.noValidExtentRefetch == true and
  .invariants.clockDomainsKeptSeparate == true
' "$OUTPUT_ROOT/recovery-summary-v2.json" >/dev/null

printf 'M1-F %s verified for session %s\n' "$scenario_id" "$session_id"
