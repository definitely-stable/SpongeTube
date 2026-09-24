#!/usr/bin/env bash
set -euo pipefail

OUTPUT_ROOT="${1:-build/m1-acc07}"
SOURCE_ROOT="core/engine/build/m1-acc07"

rm -rf "$OUTPUT_ROOT" "$SOURCE_ROOT"
mkdir -p "$OUTPUT_ROOT"

./gradlew --dependency-verification=strict \
  :core:engine:testDebugUnitTest \
  --tests io.github.definitelystable.spongetube.core.engine.FetchBrokerCancellationEvidenceTest.canonicalCancellationCasesProduceEvidence \
  --rerun-tasks

test -d "$SOURCE_ROOT"
cp -R "$SOURCE_ROOT/." "$OUTPUT_ROOT/cases/"

python3 scripts/measurement/m1_fetch_cancellation_evidence.py \
  --cases-root "$OUTPUT_ROOT/cases" \
  --output "$OUTPUT_ROOT/verification-summary.json"

jq -e '
  .gateId == "M1-ACC-07" and
  .status == "PASS" and
  .caseCount == 4
' "$OUTPUT_ROOT/verification-summary.json" >/dev/null

printf 'M1-ACC-07 canonical FetchBroker cancellation evidence verified\n'
