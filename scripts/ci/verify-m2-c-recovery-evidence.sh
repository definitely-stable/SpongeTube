#!/usr/bin/env bash
# M2-C recovery evidence verification (.work/milestones/M2.md, M2-C;
# .work/adr/0003-centralize-recovery-ownership.md).
#
#   host   <output-root>
#       Re-runs RecoveryEvidenceHostTest (production RecoveryCoordinator,
#       FetchBroker, FailureClassifier and RecoveryPolicy over scripted
#       attempts) and verifies every case with the independent host oracle.
#       M2-ACC-05 and M2-ACC-06 must each PASS in at least one case.
#
#   device <output-root> <origin-trace>
#       After RecoveryOriginAndroidTest ran against Media Lab N4R: verifies the
#       exported artifacts against the Media Lab request trace (exact physical
#       request count) and requires both gates to PASS.
set -euo pipefail

MODE="${1:?usage: verify-m2-c-recovery-evidence.sh host|device <output-root> [origin-trace]}"
OUTPUT_ROOT="${2:?output root required}"
ORACLE="scripts/measurement/m2_recovery_oracle.py"

rm -rf "$OUTPUT_ROOT"
mkdir -p "$OUTPUT_ROOT"

verify_case() {
  local source="$1"
  local target="$2"
  shift 2
  mkdir -p "$target"
  for name in failure-decision-events.json recovery-budget-events.json fetch-events.jsonl case.json; do
    test -s "$source/$name"
    cp "$source/$name" "$target/$name"
  done
  python3 "$ORACLE" verify \
    --failures "$target/failure-decision-events.json" \
    --budget "$target/recovery-budget-events.json" \
    --fetch "$target/fetch-events.jsonl" \
    --case "$target/case.json" \
    --output "$target/recovery-verification-summary.json" \
    "$@"
}

case "$MODE" in
  host)
    SOURCE_ROOT="core/engine/build/m2-c-recovery"
    CASES=(
      transient-then-success
      budget-exhausted
      reserve-playback-join
      terminal-classifications
      cancellation-barrier
      attempt-gate-wait
      final-consumer-cancellation
    )
    rm -rf "$SOURCE_ROOT"

    ./gradlew --dependency-verification=strict \
      :core:engine:testDebugUnitTest \
      --tests io.github.definitelystable.spongetube.core.engine.recovery.RecoveryEvidenceHostTest \
      --rerun-tasks

    for case_id in "${CASES[@]}"; do
      verify_case "$SOURCE_ROOT/$case_id" "$OUTPUT_ROOT/$case_id"
    done
    # Terminal classifications must never be turned into a new chain.
    python3 "$ORACLE" verify \
      --failures "$OUTPUT_ROOT/terminal-classifications/failure-decision-events.json" \
      --budget "$OUTPUT_ROOT/terminal-classifications/recovery-budget-events.json" \
      --fetch "$OUTPUT_ROOT/terminal-classifications/fetch-events.jsonl" \
      --output "$OUTPUT_ROOT/terminal-classifications/no-rechain-summary.json" \
      --forbid-rechain-after-failure

    python3 - "$OUTPUT_ROOT" "${CASES[@]}" <<'PY'
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
passed = {"M2-ACC-05": [], "M2-ACC-06": []}
for case_id in sys.argv[2:]:
    summary = json.loads((root / case_id / "recovery-verification-summary.json").read_text())
    for gate in passed:
        if summary["gates"][gate]["status"] == "PASS":
            passed[gate].append(case_id)
for gate, cases in passed.items():
    if not cases:
        sys.exit(f"{gate} was not exercised by any host case")
    print(f"{gate} PASS in {len(cases)} host cases: {', '.join(cases)}")
PY
    printf 'M2-C host recovery evidence verified for %s cases\n' "${#CASES[@]}"
    ;;

  device)
    ORIGIN_TRACE="${3:?device mode requires the Media Lab origin trace}"
    ADDITIONAL_OUTPUT_ROOT="core/engine/build/outputs/connected_android_test_additional_output"
    test -d "$ADDITIONAL_OUTPUT_ROOT"
    test -s "$ORIGIN_TRACE"

    mapfile -t EVIDENCE < <(
      find "$ADDITIONAL_OUTPUT_ROOT" -type f -path '*/m2-c-recovery-evidence/recovery-budget-events.json' -print
    )
    if [[ "${#EVIDENCE[@]}" -ne 1 ]]; then
      printf 'expected exactly one M2-C recovery evidence set, found %s\n' "${#EVIDENCE[@]}" >&2
      printf '%s\n' "${EVIDENCE[@]}" >&2
      exit 1
    fi
    cp "$ORIGIN_TRACE" "$OUTPUT_ROOT/origin-requests.jsonl"
    verify_case "$(dirname "${EVIDENCE[0]}")" "$OUTPUT_ROOT" \
      --origin "$OUTPUT_ROOT/origin-requests.jsonl" \
      --require-gate M2-ACC-05 \
      --require-gate M2-ACC-06
    printf 'M2-C device recovery evidence verified\n'
    ;;

  *)
    printf 'unknown mode %s\n' "$MODE" >&2
    exit 2
    ;;
esac
