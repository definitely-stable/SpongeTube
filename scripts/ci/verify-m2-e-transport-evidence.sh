#!/usr/bin/env bash
# M2-E TRANSPORT evidence orchestration.
#
# Recovery correctness and external fault fidelity are deliberately verified by
# different independent oracles. Media Lab request IDs are downstream
# observations and are not treated as RecoveryChain/FetchBroker attempt IDs
# through Toxiproxy.
set -euo pipefail

ANDROID_DIR="${1:?android evidence directory required}"
SCENARIO="${2:?resolved scenario required}"
ORIGIN="${3:?origin trace required}"
HARNESS="${4:?fault harness evidence required}"
ACTIVE_STATE="${5:?active Toxiproxy normalized state required}"
CLEAN_STATE="${6:?clean Toxiproxy normalized state required}"
RUN_MANIFEST="${7:?m2-run-manifest-v1 required}"
PACKETS_BEFORE="${8:?media packet counter before required}"
PACKETS_AFTER="${9:?media packet counter after required}"
HEALTH_DIR="${10:?health marker directory required}"
OUTPUT="${11:?output directory required}"

RECOVERY_ORACLE="scripts/measurement/m2_recovery_oracle.py"
FAULT_ORACLE="scripts/measurement/m2_fault_oracle.py"

mkdir -p "$OUTPUT"

for file in   "$ANDROID_DIR/failure-decision-events.json"   "$ANDROID_DIR/recovery-budget-events.json"   "$ANDROID_DIR/fetch-events.jsonl"   "$ANDROID_DIR/case.json"   "$ANDROID_DIR/transport-result.json"   "$SCENARIO"   "$ORIGIN"   "$HARNESS"   "$ACTIVE_STATE"   "$CLEAN_STATE"   "$RUN_MANIFEST"   "$PACKETS_BEFORE"   "$PACKETS_AFTER"   "$HEALTH_DIR/adb-before.txt"   "$HEALTH_DIR/adb-during.txt"   "$HEALTH_DIR/adb-after.txt"   "$HEALTH_DIR/control-before.txt"   "$HEALTH_DIR/control-during.txt"   "$HEALTH_DIR/control-after.txt"   "$HEALTH_DIR/media-reverse-absent.txt"   "$HEALTH_DIR/artifact-collection.txt"   "$HEALTH_DIR/cleanup.txt"; do
  test -s "$file"
done

# Portable verified artifacts contain normalized evidence only. The raw origin
# trace remains in the run's diagnostic directory and is consumed by the
# oracle, but is not copied into this portable verified set.
cp "$SCENARIO" "$OUTPUT/resolved-scenario.json"
cp "$RUN_MANIFEST" "$OUTPUT/m2-run-manifest.json"
cp "$HARNESS" "$OUTPUT/fault-harness-events.json"
cp "$ACTIVE_STATE" "$OUTPUT/toxiproxy-active-state.json"
cp "$CLEAN_STATE" "$OUTPUT/toxiproxy-clean-state.json"
cp "$ANDROID_DIR/transport-result.json" "$OUTPUT/transport-result.json"

variant="$(jq -r '.variant' "$SCENARIO")"

# M2-C lineage is independent of Media Lab request IDs once an external proxy
# sits between FetchBroker and origin. FetchBroker attempt evidence is the
# authoritative physical-attempt join for RecoveryChain accounting.
if [[ "$variant" != "SLOW_CLOSE" ]]; then
  python3 "$RECOVERY_ORACLE" verify     --failures "$ANDROID_DIR/failure-decision-events.json"     --budget "$ANDROID_DIR/recovery-budget-events.json"     --fetch "$ANDROID_DIR/fetch-events.jsonl"     --case "$ANDROID_DIR/case.json"     --output "$OUTPUT/recovery-verification-summary.json"     --forbid-rechain-after-failure     --require-gate M2-ACC-05     --require-gate M2-ACC-06
fi

python3 "$FAULT_ORACLE" verify-transport   --scenario "$SCENARIO"   --run-manifest "$RUN_MANIFEST"   --harness "$HARNESS"   --active-state "$ACTIVE_STATE"   --clean-state "$CLEAN_STATE"   --failures "$ANDROID_DIR/failure-decision-events.json"   --budget "$ANDROID_DIR/recovery-budget-events.json"   --fetch "$ANDROID_DIR/fetch-events.jsonl"   --transport-result "$ANDROID_DIR/transport-result.json"   --origin "$ORIGIN"   --media-packets-before "$PACKETS_BEFORE"   --media-packets-after "$PACKETS_AFTER"   --adb-before "$HEALTH_DIR/adb-before.txt"   --adb-during "$HEALTH_DIR/adb-during.txt"   --adb-after "$HEALTH_DIR/adb-after.txt"   --control-before "$HEALTH_DIR/control-before.txt"   --control-during "$HEALTH_DIR/control-during.txt"   --control-after "$HEALTH_DIR/control-after.txt"   --media-reverse-absent "$HEALTH_DIR/media-reverse-absent.txt"   --artifact-collection "$HEALTH_DIR/artifact-collection.txt"   --cleanup "$HEALTH_DIR/cleanup.txt"   --output "$OUTPUT/fault-verification-summary.json"

jq -e '
  .status == "PASS" and
  .primaryPlane == "TRANSPORT" and
  .gates["M2-ACC-01"] == true and
  .gates["M2-ACC-02"] == true and
  .gates["M2-ACC-09"] == true and
  ([.checks[]] | all)
' "$OUTPUT/fault-verification-summary.json" >/dev/null

case_id="$(jq -r '.caseId' "$ANDROID_DIR/case.json")"
printf 'M2-E transport evidence verified: %s (%s)\n' "$case_id" "$variant"
