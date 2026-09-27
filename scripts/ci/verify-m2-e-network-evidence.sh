#!/usr/bin/env bash
# M2-E NETWORK evidence orchestration.
#
# Recovery lineage and external packet-fault fidelity remain independently
# verified. The NETWORK cause never becomes a synthetic Android packet-loss
# observation.
set -euo pipefail

ANDROID_DIR="${1:?android evidence directory required}"
SCENARIO="${2:?resolved scenario required}"
ORIGIN="${3:?origin trace required}"
HARNESS="${4:?fault harness evidence required}"
QDISC_STATE="${5:?final qdisc readback required}"
FILTER_STATE="${6:?flower filter readback required}"
CLEAN_STATE="${7:?clean netem state required}"
CALIBRATION="${8:?network calibration required}"
RUN_MANIFEST="${9:?m2-run-manifest-v1 required}"
ENGINE_FINGERPRINT="${10:?fault-engine-fingerprint-v1 required}"
HEALTH_DIR="${11:?health marker directory required}"
OUTPUT="${12:?output directory required}"

RECOVERY_ORACLE="scripts/measurement/m2_recovery_oracle.py"
FAULT_ORACLE="scripts/measurement/m2_fault_oracle.py"

mkdir -p "$OUTPUT"

for file in   "$ANDROID_DIR/failure-decision-events.json"   "$ANDROID_DIR/recovery-budget-events.json"   "$ANDROID_DIR/fetch-events.jsonl"   "$ANDROID_DIR/case.json"   "$ANDROID_DIR/network-result.json"   "$SCENARIO"   "$ORIGIN"   "$HARNESS"   "$QDISC_STATE"   "$FILTER_STATE"   "$CLEAN_STATE"   "$CALIBRATION"   "$RUN_MANIFEST"   "$ENGINE_FINGERPRINT"   "$HEALTH_DIR/adb-before.txt"   "$HEALTH_DIR/adb-during.txt"   "$HEALTH_DIR/adb-after.txt"   "$HEALTH_DIR/control-before.txt"   "$HEALTH_DIR/control-during.txt"   "$HEALTH_DIR/control-after.txt"   "$HEALTH_DIR/qdisc-clean.txt"   "$HEALTH_DIR/filter-clean.txt"   "$HEALTH_DIR/namespace-clean.txt"   "$HEALTH_DIR/transport-tool-absent.txt"   "$HEALTH_DIR/media-reverse-absent.txt"   "$HEALTH_DIR/artifact-collection.txt"; do
  test -s "$file"
done

cp "$SCENARIO" "$OUTPUT/resolved-scenario.json"
cp "$RUN_MANIFEST" "$OUTPUT/m2-run-manifest.json"
cp "$ENGINE_FINGERPRINT" "$OUTPUT/fault-engine-fingerprint.json"
cp "$HARNESS" "$OUTPUT/fault-harness-events.json"
cp "$CALIBRATION" "$OUTPUT/network-calibration.json"
cp "$CLEAN_STATE" "$OUTPUT/netem-clean-state.json"
cp "$ANDROID_DIR/network-result.json" "$OUTPUT/network-result.json"

if jq -e '(.failures // []) | length > 0'   "$ANDROID_DIR/failure-decision-events.json" >/dev/null; then
  python3 "$RECOVERY_ORACLE" verify     --failures "$ANDROID_DIR/failure-decision-events.json"     --budget "$ANDROID_DIR/recovery-budget-events.json"     --fetch "$ANDROID_DIR/fetch-events.jsonl"     --case "$ANDROID_DIR/case.json"     --output "$OUTPUT/recovery-verification-summary.json"     --forbid-rechain-after-failure     --require-gate M2-ACC-05     --require-gate M2-ACC-06
fi

python3 "$FAULT_ORACLE" verify-network   --scenario "$SCENARIO"   --run-manifest "$RUN_MANIFEST"   --harness "$HARNESS"   --qdisc-state "$QDISC_STATE"   --filter-state "$FILTER_STATE"   --clean-state "$CLEAN_STATE"   --calibration "$CALIBRATION"   --engine-fingerprint "$ENGINE_FINGERPRINT"   --failures "$ANDROID_DIR/failure-decision-events.json"   --budget "$ANDROID_DIR/recovery-budget-events.json"   --fetch "$ANDROID_DIR/fetch-events.jsonl"   --network-result "$ANDROID_DIR/network-result.json"   --origin "$ORIGIN"   --adb-before "$HEALTH_DIR/adb-before.txt"   --adb-during "$HEALTH_DIR/adb-during.txt"   --adb-after "$HEALTH_DIR/adb-after.txt"   --control-before "$HEALTH_DIR/control-before.txt"   --control-during "$HEALTH_DIR/control-during.txt"   --control-after "$HEALTH_DIR/control-after.txt"   --qdisc-clean "$HEALTH_DIR/qdisc-clean.txt"   --filter-clean "$HEALTH_DIR/filter-clean.txt"   --namespace-clean "$HEALTH_DIR/namespace-clean.txt"   --transport-tool-absent "$HEALTH_DIR/transport-tool-absent.txt"   --media-reverse-absent "$HEALTH_DIR/media-reverse-absent.txt"   --artifact-collection "$HEALTH_DIR/artifact-collection.txt"   --output "$OUTPUT/fault-verification-summary.json"

jq -e '
  .status == "PASS" and
  .primaryPlane == "NETWORK" and
  .gates["M2-ACC-01"] == true and
  .gates["M2-ACC-02"] == true and
  .gates["M2-ACC-09"] == true and
  ([.checks[]] | all)
' "$OUTPUT/fault-verification-summary.json" >/dev/null

case_id="$(jq -r '.caseId' "$ANDROID_DIR/case.json")"
variant="$(jq -r '.variant' "$SCENARIO")"
printf 'M2-E network evidence verified: %s (%s)\n' "$case_id" "$variant"
