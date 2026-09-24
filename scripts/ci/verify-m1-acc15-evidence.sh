#!/usr/bin/env bash
set -euo pipefail

OUTPUT_ROOT="${1:-build/m1-acc15}"
SOURCE="core/engine/build/m1-acc15/range-continuation-v1.jsonl"

rm -rf "$OUTPUT_ROOT"
rm -f "$SOURCE"
mkdir -p "$OUTPUT_ROOT"

./gradlew --dependency-verification=strict \
  :core:engine:testDebugUnitTest \
  --tests io.github.definitelystable.spongetube.core.engine.HttpRangeFetchExecutorTest.canonicalContinuationVariantsProduceEvidence \
  --rerun-tasks

test -s "$SOURCE"
cp "$SOURCE" "$OUTPUT_ROOT/range-continuation-v1.jsonl"

python3 scripts/measurement/m1_range_continuation_evidence.py \
  --evidence "$OUTPUT_ROOT/range-continuation-v1.jsonl" \
  --output "$OUTPUT_ROOT/verification-summary.json"

jq -e '
  .gateId == "M1-ACC-15" and
  .status == "PASS" and
  .caseCount == 4 and
  ([.cases[] | select(.caseId != "MATCHING_206") | .emittedBytes] | all(. == 0))
' "$OUTPUT_ROOT/verification-summary.json" >/dev/null

printf 'M1-ACC-15 canonical range continuation evidence verified\n'
