#!/usr/bin/env bash
# M2-B route-events-v1 verification (.work/milestones/M2.md sections 8, 16).
#
#   host   <output-root>
#       Re-runs RouteEvidenceHostTest (production reducer/guard/recorder over
#       scripted API 23/24/28/34/36 signal sequences) and verifies every
#       artifact with the independent host oracle.
#
#   device <output-root> <api>
#       After :core:engine:connectedDebugAndroidTest: requires the
#       AndroidDefaultRouteMonitorTest instrumentation cases to have passed and,
#       on API >= 34, verifies the exported route-events-v1 artifact.
set -euo pipefail

MODE="${1:?usage: verify-m2-b-route-evidence.sh host|device <output-root> [api]}"
OUTPUT_ROOT="${2:?output root required}"
ORACLE="scripts/measurement/m2_route_oracle.py"

rm -rf "$OUTPUT_ROOT"
mkdir -p "$OUTPUT_ROOT"

case "$MODE" in
  host)
    SOURCE_ROOT="core/engine/build/m2-b-route"
    CASES=(
      api23-legacy-snapshots
      api24-unordered-callbacks
      api28-startup-suspended
      api34-vpn-continuity
      api36-direct-transient-vpn
    )
    rm -rf "$SOURCE_ROOT"

    ./gradlew --dependency-verification=strict \
      :core:engine:testDebugUnitTest \
      --tests io.github.definitelystable.spongetube.core.engine.route.RouteEvidenceHostTest \
      --rerun-tasks

    for case_id in "${CASES[@]}"; do
      source="$SOURCE_ROOT/$case_id"
      test -s "$source/route-events.json"
      mkdir -p "$OUTPUT_ROOT/$case_id"
      cp "$source/route-events.json" "$OUTPUT_ROOT/$case_id/route-events.json"
      python3 "$ORACLE" verify \
        --input "$OUTPUT_ROOT/$case_id/route-events.json" \
        --output "$OUTPUT_ROOT/$case_id/route-verification-summary.json" \
        --expected-api "$(tr -d '[:space:]' < "$source/android-api.txt")"
    done
    printf 'M2-B host route evidence verified for %s cases\n' "${#CASES[@]}"
    ;;

  device)
    API="${3:?device mode requires the emulator API level}"
    RESULTS_ROOT="core/engine/build/outputs/androidTest-results"
    test -d "$RESULTS_ROOT"

    python3 - "$RESULTS_ROOT" <<'PY'
import pathlib
import sys
import xml.etree.ElementTree as ET

CLASS = "io.github.definitelystable.spongetube.core.engine.route.AndroidDefaultRouteMonitorTest"
EXPECTED = {
    "accessNetworkStateIsGrantedWithoutLocationPermission",
    "observesActualDefaultRouteAndProducesRouteEvidence",
    "repeatedOpenAndShutdownLeaksNoPlatformRegistration",
    "callbacksAfterShutdownDoNotChangeState",
}
passed = set()
for path in pathlib.Path(sys.argv[1]).rglob("TEST-*.xml"):
    for case in ET.parse(path).getroot().iter("testcase"):
        if case.get("classname") != CLASS:
            continue
        if any(child.tag in ("failure", "error", "skipped") for child in case):
            sys.exit(f"{case.get('name')} did not pass in {path}")
        passed.add(case.get("name"))
missing = EXPECTED - passed
if missing:
    sys.exit(f"M2-B instrumentation cases missing: {sorted(missing)}")
print(f"M2-B instrumentation: {len(passed)} AndroidDefaultRouteMonitorTest cases passed")
PY

    if [[ "$API" -lt 34 ]]; then
      printf 'API %s: route-events-v1 export not staged (legacy test-output path); instrumentation proof only\n' "$API"
      exit 0
    fi

    ADDITIONAL_OUTPUT_ROOT="core/engine/build/outputs/connected_android_test_additional_output"
    mapfile -t EVIDENCE < <(
      find "$ADDITIONAL_OUTPUT_ROOT" -type f -path '*/m2-b-route-evidence/route-events.json' -print
    )
    if [[ "${#EVIDENCE[@]}" -ne 1 ]]; then
      printf 'expected exactly one route-events.json, found %s\n' "${#EVIDENCE[@]}" >&2
      printf '%s\n' "${EVIDENCE[@]}" >&2
      exit 1
    fi
    cp "${EVIDENCE[0]}" "$OUTPUT_ROOT/route-events.json"
    test "$(tr -d '[:space:]' < "$(dirname "${EVIDENCE[0]}")/android-api.txt")" = "$API"

    python3 "$ORACLE" verify \
      --input "$OUTPUT_ROOT/route-events.json" \
      --output "$OUTPUT_ROOT/route-verification-summary.json" \
      --expected-api "$API" \
      --require-source MONITOR_LIFECYCLE \
      --require-source BOOTSTRAP_ACTIVE_NETWORK
    printf 'M2-B device route evidence verified on API %s\n' "$API"
    ;;

  *)
    printf 'unknown mode %s\n' "$MODE" >&2
    exit 2
    ;;
esac
