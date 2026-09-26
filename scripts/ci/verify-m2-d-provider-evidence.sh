#!/usr/bin/env bash
# M2-D provider recovery evidence verification (.work/milestones/M2.md, M2-D;
# .work/VERIFICATION.md section 23.2; .work/adr/0004-separate-immutable-work-
# from-delivery-binding.md).
#
#   host   <output-root>
#       Re-runs ProviderRecoveryEvidenceHostTest (production RecoveryCoordinator,
#       DeliveryBindingCoordinator, FetchBroker and scripted provider over
#       scripted attempts) and verifies every case with the independent provider
#       oracle. M2-ACC-07 and M2-ACC-08 must each PASS in at least one case;
#       n10-binding-expired-refresh must PASS all four M2-D gates and
#       n8-bare-403 must PASS M2-ACC-08.
#
#   device <output-root> <scenario> <origin-trace> <provider-faults> [api]
#       After the Android provider scenario ran against Media Lab: requires the
#       ProviderRecoveryAndroidTest instrumentation case to have passed, then
#       verifies the exported artifacts against the Media Lab request trace and
#       the provider-fault-events-v1 artifact. M2-ACC-05 and M2-ACC-08 are
#       always required, M2-ACC-07 for N10_BINDING_EXPIRED_REFRESH and
#       M2-ACC-06 for every scenario except N8_BARE_403. On API < 34 the legacy
#       test-output path cannot stage exported evidence, so the passed
#       instrumentation case and the provider-side scenario expectations are
#       the proof instead.
#       M2D_ANDROID_TEST_RESULTS_ROOT overrides the Android test-results root
#       (default core/engine/build/outputs/androidTest-results).
set -euo pipefail

MODE="${1:?usage: verify-m2-d-provider-evidence.sh host <output-root> | device <output-root> <scenario> <origin-trace> <provider-faults> [api]}"
OUTPUT_ROOT="${2:?output root required}"
ORACLE="scripts/measurement/m2_provider_oracle.py"

rm -rf "$OUTPUT_ROOT"
mkdir -p "$OUTPUT_ROOT"

verify_case() {
  local source="$1"
  local target="$2"
  shift 2
  mkdir -p "$target"
  for name in failure-decision-events.json recovery-budget-events.json delivery-binding-events.json fetch-events.jsonl case.json; do
    test -s "$source/$name"
    cp "$source/$name" "$target/$name"
  done
  python3 "$ORACLE" verify \
    --failures "$target/failure-decision-events.json" \
    --budget "$target/recovery-budget-events.json" \
    --delivery "$target/delivery-binding-events.json" \
    --fetch "$target/fetch-events.jsonl" \
    --case "$target/case.json" \
    --output "$target/provider-verification-summary.json" \
    "$@"
}

case "$MODE" in
  host)
    SOURCE_ROOT="core/engine/build/m2-d-provider"
    CASES=(
      n8-bare-403
      n9-delay-seconds
      n9-http-date
      n9-retry-after-absent
      n9-retry-after-malformed
      n10-binding-expired-refresh
      n10-refresh-incompatible
      n10-refresh-failed
      n10-already-advanced
      n10-concurrent-single-flight
      refresh-budget-exhausted
      provider-wait-cancelled
      shutdown-during-refresh
      rate-limit-budget-exhausted
    )
    rm -rf "$SOURCE_ROOT"

    ./gradlew --dependency-verification=strict \
      :core:engine:testDebugUnitTest \
      --tests io.github.definitelystable.spongetube.core.engine.recovery.ProviderRecoveryEvidenceHostTest \
      --rerun-tasks

    for case_id in "${CASES[@]}"; do
      verify_case "$SOURCE_ROOT/$case_id" "$OUTPUT_ROOT/$case_id"
    done

    python3 - "$OUTPUT_ROOT" "${CASES[@]}" <<'PY'
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
cases = sys.argv[2:]
progress = []
exercised = []
for case_id in cases:
    summary = json.loads(
        (root / case_id / "provider-verification-summary.json").read_text()
    )
    if summary["gates"]["M2-ACC-07"]["status"] == "PASS":
        progress.append(case_id)
    if summary["gates"]["M2-ACC-08"]["status"] == "PASS":
        exercised.append(case_id)
if not progress:
    sys.exit("M2-ACC-07 was not exercised by any host case")
if not exercised:
    sys.exit("M2-ACC-08 was not exercised by any host case")
required = {
    "n10-binding-expired-refresh": ("M2-ACC-05", "M2-ACC-06", "M2-ACC-07", "M2-ACC-08"),
    "n8-bare-403": ("M2-ACC-08",),
}
for case_id, gates in required.items():
    summary = json.loads(
        (root / case_id / "provider-verification-summary.json").read_text()
    )
    for gate in gates:
        if summary["gates"][gate]["status"] != "PASS":
            sys.exit(
                f"{case_id} did not PASS {gate}: "
                f"{summary['gates'][gate]['status']}"
            )
print(f"M2-ACC-07 PASS in {len(progress)} host cases: {', '.join(progress)}")
print(f"M2-ACC-08 PASS in {len(exercised)} host cases: {', '.join(exercised)}")
PY
    printf 'M2-D host provider evidence verified for %s cases\n' "${#CASES[@]}"
    ;;

  device)
    SCENARIO="${3:?device mode requires the Media Lab scenario id}"
    ORIGIN_TRACE="${4:?device mode requires the Media Lab origin trace}"
    PROVIDER_FAULTS="${5:?device mode requires the provider-fault-events-v1 artifact}"
    API="${6:-36}"
    RESULTS_ROOT="${M2D_ANDROID_TEST_RESULTS_ROOT:-core/engine/build/outputs/androidTest-results}"
    test -d "$RESULTS_ROOT"

    python3 - "$RESULTS_ROOT" <<'PY'
import pathlib
import sys
import xml.etree.ElementTree as ET

CLASS = "io.github.definitelystable.spongetube.core.engine.ProviderRecoveryAndroidTest"
CASE = "providerScenarioRecoversDeterministically"
passed = False
for path in pathlib.Path(sys.argv[1]).rglob("TEST-*.xml"):
    for case in ET.parse(path).getroot().iter("testcase"):
        if case.get("classname") != CLASS or case.get("name") != CASE:
            continue
        if any(child.tag in ("failure", "error", "skipped") for child in case):
            sys.exit(f"{CLASS}.{CASE} did not pass in {path}")
        passed = True
if not passed:
    sys.exit(f"M2-D instrumentation case missing: {CLASS}.{CASE}")
print(f"M2-D instrumentation: {CLASS}.{CASE} passed")
PY

    test -s "$ORIGIN_TRACE"
    test -s "$PROVIDER_FAULTS"

    if [[ "$API" -lt 34 ]]; then
      FAULT_SCHEMA=".work/schemas/provider-fault-events-v1.schema.json"
      test -s "$FAULT_SCHEMA"
      python3 - "$SCENARIO" "$ORIGIN_TRACE" "$PROVIDER_FAULTS" "$FAULT_SCHEMA" <<'PY'
import json
import pathlib
import re
import sys

sys.path.insert(0, "scripts/measurement")
from schema_subset import SchemaContractError, validate_instance

SCENARIOS = {
    "N8_BARE_403": ("N8", "HTTP_403_BARE"),
    "N9_429_DELAY_SECONDS": ("N9", "HTTP_429_RETRY_AFTER_DELAY_SECONDS"),
    "N9_429_HTTP_DATE": ("N9", "HTTP_429_RETRY_AFTER_HTTP_DATE"),
    "N10_BINDING_EXPIRED_REFRESH": ("N10", "BINDING_EXPIRY_REFRESH"),
}
MEDIA_PATH = re.compile(r"^/provider/gen-[1-9][0-9]*/fixtures/")


def fail(message):
    sys.exit(f"M2-D provider-side proof: {message}")


def single_fault(events, request_id, where):
    matches = [
        event for event in events if event["requestCorrelationId"] == str(request_id)
    ]
    if len(matches) != 1:
        fail(f"{where}: expected exactly one provider fault, found {len(matches)}")
    return matches[0]


scenario = sys.argv[1]
origin_trace = pathlib.Path(sys.argv[2])
provider_faults_path = pathlib.Path(sys.argv[3])
schema_path = pathlib.Path(sys.argv[4])

expected = SCENARIOS.get(scenario)
if expected is None:
    fail(f"unknown scenario {scenario}")
family, variant = expected

faults = json.loads(provider_faults_path.read_text(encoding="utf-8"))
try:
    validate_instance(json.loads(schema_path.read_text(encoding="utf-8")), faults)
except SchemaContractError as error:
    fail(f"provider-fault-events-v1 schema: {error}")
if (faults["scenarioFamily"], faults["variant"]) != (family, variant):
    fail(
        f"provider fault document is {faults['scenarioFamily']}/{faults['variant']}, "
        f"not {family}/{variant}"
    )

rows = [
    json.loads(line)
    for line in origin_trace.read_text(encoding="utf-8").splitlines()
    if line.strip()
]
media = sorted(
    (row for row in rows if MEDIA_PATH.match(str(row.get("path", "")))),
    key=lambda row: row["handlerStartedAtMonotonicNs"],
)
refresh = [row for row in rows if row.get("path") == "/provider/refresh"]
media_faults = [event for event in faults["events"] if event["requestKind"] == "MEDIA"]
refresh_faults = [event for event in faults["events"] if event["requestKind"] == "REFRESH"]

if scenario == "N8_BARE_403":
    if len(media) != 1:
        fail(f"N8_BARE_403: expected 1 media request, found {len(media)}")
    if refresh or refresh_faults:
        fail("N8_BARE_403: the bare 403 profile has no provider refresh")
    if media[0]["status"] != 403:
        fail(f"N8_BARE_403: media request status {media[0]['status']} != 403")
    if len(media_faults) != 1:
        fail(f"N8_BARE_403: expected 1 media fault, found {len(media_faults)}")
    fault = single_fault(media_faults, media[0]["requestId"], "N8_BARE_403")
    if fault["faultKind"] != "HTTP_403_BARE" or fault["statusCode"] != 403:
        fail(
            "N8_BARE_403: expected one HTTP_403_BARE 403 fault, got "
            f"{fault['faultKind']} {fault['statusCode']}"
        )
    if fault["providerSignal"] != "NONE":
        fail(f"N8_BARE_403: a bare 403 carries no provider signal, got {fault['providerSignal']}")
elif scenario in ("N9_429_DELAY_SECONDS", "N9_429_HTTP_DATE"):
    if len(media) != 2:
        fail(f"{scenario}: expected exactly 2 media requests, found {len(media)}")
    if refresh or refresh_faults:
        fail(f"{scenario}: a throttled retry uses no provider refresh")
    if len(media_faults) != 1:
        fail(f"{scenario}: expected 1 media fault, found {len(media_faults)}")
    fault = single_fault(media_faults, media[0]["requestId"], scenario)
    if fault["faultKind"] != "HTTP_429" or fault["statusCode"] != 429:
        fail(f"{scenario}: expected one HTTP_429 fault, got {fault['faultKind']} {fault['statusCode']}")
    if scenario == "N9_429_DELAY_SECONDS":
        expected_retry_after = {
            "rawKind": "DELAY_SECONDS",
            "delaySeconds": 2,
            "notBeforeUtcEpochMs": None,
        }
    else:
        expected_retry_after = {
            "rawKind": "HTTP_DATE",
            "delaySeconds": None,
            "notBeforeUtcEpochMs": faults["providerWallClockEpochMs"] + 2_000,
        }
    actual_retry_after = None
    if fault["retryAfter"] is not None:
        actual_retry_after = {
            key: fault["retryAfter"][key]
            for key in ("rawKind", "delaySeconds", "notBeforeUtcEpochMs")
        }
    if actual_retry_after != expected_retry_after:
        fail(f"{scenario}: normalized Retry-After {actual_retry_after} != {expected_retry_after}")
    if media[0]["status"] != 429:
        fail(f"{scenario}: first media request status {media[0]['status']} != 429")
    if media[1]["status"] != 206:
        fail(f"{scenario}: retried media request status {media[1]['status']} != 206")
    elapsed_ns = (
        media[1]["handlerStartedAtMonotonicNs"] - media[0]["handlerStartedAtMonotonicNs"]
    )
    if elapsed_ns < 2_000 * 1_000_000:
        fail(
            f"{scenario}: the retry started {elapsed_ns} ns after the throttled "
            "request (HOST_MEDIA_LAB_MONOTONIC), before 2000 ms"
        )
else:
    if len(media) != 2:
        fail(f"{scenario}: expected 2 media requests, found {len(media)}")
    if len(refresh) != 1:
        fail(f"{scenario}: expected 1 refresh request, found {len(refresh)}")
    if len(media_faults) != 1:
        fail(f"{scenario}: expected 1 media fault, found {len(media_faults)}")
    if len(refresh_faults) != 1:
        fail(f"{scenario}: expected 1 refresh fault, found {len(refresh_faults)}")
    stale = single_fault(media_faults, media[0]["requestId"], scenario)
    if stale["faultKind"] != "BINDING_STALE" or stale["providerSignal"] != "BINDING_STALE_CONFIRMED":
        fail(
            f"{scenario}: expected a BINDING_STALE fault with the stale signal, got "
            f"{stale['faultKind']} {stale['providerSignal']}"
        )
    if media[0]["status"] != 403 or "/provider/gen-1/fixtures/" not in str(media[0]["path"]):
        fail(f"{scenario}: the first media request is not a gen-1 403")
    if media[1]["status"] != 206 or "/provider/gen-2/fixtures/" not in str(media[1]["path"]):
        fail(f"{scenario}: the second media request is not a gen-2 206")
    if refresh[0]["status"] != 200:
        fail(f"{scenario}: refresh status {refresh[0]['status']} != 200")
    succeeded = single_fault(refresh_faults, refresh[0]["requestId"], scenario)
    if (
        succeeded["faultKind"] != "REFRESH_SUCCEEDED"
        or succeeded["providerBindingGeneration"] != "gen-2"
    ):
        fail(
            f"{scenario}: expected one REFRESH_SUCCEEDED to gen-2, got "
            f"{succeeded['faultKind']} {succeeded['providerBindingGeneration']}"
        )

print(
    f"M2-D provider-side proof: {scenario} ({family}/{variant}) matches the "
    "Media Lab origin trace and provider fault events"
)
PY
      printf 'API %s: provider-side proof only (legacy test-output path); instrumentation passed\n' "$API"
      exit 0
    fi

    ADDITIONAL_OUTPUT_ROOT="core/engine/build/outputs/connected_android_test_additional_output"
    test -d "$ADDITIONAL_OUTPUT_ROOT"

    mapfile -t EVIDENCE < <(
      find "$ADDITIONAL_OUTPUT_ROOT" -type f \
        -path "*/m2-d-provider-evidence/$SCENARIO/recovery-budget-events.json" -print
    )
    if [[ "${#EVIDENCE[@]}" -ne 1 ]]; then
      printf 'expected exactly one M2-D provider evidence set for %s, found %s\n' \
        "$SCENARIO" "${#EVIDENCE[@]}" >&2
      printf '%s\n' "${EVIDENCE[@]}" >&2
      exit 1
    fi
    cp "$ORIGIN_TRACE" "$OUTPUT_ROOT/origin-requests.jsonl"
    cp "$PROVIDER_FAULTS" "$OUTPUT_ROOT/provider-fault-events.json"

    GATES=(--require-gate M2-ACC-05 --require-gate M2-ACC-08)
    if [[ "$SCENARIO" == "N10_BINDING_EXPIRED_REFRESH" ]]; then
      GATES+=(--require-gate M2-ACC-07)
    fi
    if [[ "$SCENARIO" != "N8_BARE_403" ]]; then
      GATES+=(--require-gate M2-ACC-06)
    fi
    verify_case "$(dirname "${EVIDENCE[0]}")" "$OUTPUT_ROOT" \
      --origin "$OUTPUT_ROOT/origin-requests.jsonl" \
      --provider-faults "$OUTPUT_ROOT/provider-fault-events.json" \
      "${GATES[@]}"
    printf 'M2-D device provider evidence verified for %s\n' "$SCENARIO"
    ;;

  *)
    printf 'unknown mode %s\n' "$MODE" >&2
    exit 2
    ;;
esac
