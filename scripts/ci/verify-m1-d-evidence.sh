#!/usr/bin/env bash
set -euo pipefail

OUTPUT_ROOT="${1:-build/android-smoke/m1-d-evidence}"
ORIGIN_TRACE="${2:?origin trace path is required}"
ADDITIONAL_OUTPUT_ROOT="core/engine/build/outputs/connected_android_test_additional_output"

rm -rf "$OUTPUT_ROOT"
mkdir -p "$OUTPUT_ROOT/device" "$OUTPUT_ROOT/verified"

test -d "$ADDITIONAL_OUTPUT_ROOT"
test -s "$ORIGIN_TRACE"

mapfile -t EVENT_FILES < <(
  find "$ADDITIONAL_OUTPUT_ROOT"     -type f     -path '*/m1-d-evidence/fetch-events-v2.jsonl'     -print
)

if [[ "${#EVENT_FILES[@]}" -ne 1 ]]; then
  printf 'expected exactly one collected M1-D fetch event artifact, found %s\n'     "${#EVENT_FILES[@]}" >&2
  printf '%s\n' "${EVENT_FILES[@]}" >&2
  exit 1
fi

cp "${EVENT_FILES[0]}" "$OUTPUT_ROOT/device/fetch-events-v2.jsonl"
cp "$ORIGIN_TRACE" "$OUTPUT_ROOT/verified/origin-requests.jsonl"

python3 scripts/measurement/m1_fetch_evidence.py   --fetch-events "$OUTPUT_ROOT/device/fetch-events-v2.jsonl"   --origin-trace "$OUTPUT_ROOT/verified/origin-requests.jsonl"   --output "$OUTPUT_ROOT/verified/verification-summary.json"

python3 - "$OUTPUT_ROOT/verified/verification-summary.json" <<'PY'
import json
import pathlib
import sys
payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
if payload.get("status") != "PASS":
    raise SystemExit("M1-D verification did not pass")
print(
    "M1-D evidence verified: "
    f"fetchId={payload['fetchId']} "
    f"originRequestId={payload['originRequestId']}"
)
PY
