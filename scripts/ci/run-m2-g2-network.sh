#!/usr/bin/env bash
set -euo pipefail

FAMILY="${1:?scenario profile N2/N3/N5/N5GE required}"
case "$FAMILY" in
  N2)
    slug="n2"
    variant="HIGH_RTT_JITTER"
    scenario="test-fixtures/network/m2/n2-high-rtt-jitter.json"
    harness_mode="start"
    ;;
  N3)
    slug="n3"
    variant="BURST_PACKET_LOSS"
    scenario="test-fixtures/network/m2/n3-burst-packet-loss.json"
    harness_mode="pulse"
    ;;
  N5)
    slug="n5"
    variant="BURST_LOSS"
    scenario="test-fixtures/network/m2/n5-burst-loss.json"
    harness_mode="start"
    ;;
  N5GE)
    slug="n5-ge"
    variant="BURST_LOSS_GE_MOMENT_MATCH"
    scenario="test-fixtures/network/m2/n5-ge-moment-match.json"
    harness_mode="start"
    ;;
  *)
    echo "unsupported G2-C NETWORK profile: $FAMILY" >&2
    exit 2
    ;;
esac

: "${SOURCE_HEAD_SHA:?SOURCE_HEAD_SHA required}"
ROOT="$PWD/build/m2-g2-network/$slug"
TOOLS="${RUNNER_TEMP:?RUNNER_TEMP required}/m2-g2-network-tools-$slug"
RAW_ROOT="$ROOT/raw"
TRIAL_ROOT="$ROOT/trials"
SERVER_ROOT="$ROOT/server"
OUTPUT_ROOT="$ROOT/output"
RUN_ID="m2-g2-$slug-api36"
PAIR_ID="m2-g2-$slug-api36-cold"
PLAN_ASSET="m2-g2-$slug-plan.json"
PLAN_ASSET_PATH="core/engine/src/androidTest/assets/$PLAN_ASSET"
TRACE="$SERVER_ROOT/requests.jsonl"

mkdir -p "$ROOT" "$TOOLS" "$RAW_ROOT" "$TRIAL_ROOT" "$SERVER_ROOT" "$OUTPUT_ROOT"   core/engine/src/androidTest/assets

cleanup() {
  set +e
  if [[ -n "${HARNESS_PID:-}" ]] && kill -0 "$HARNESS_PID" 2>/dev/null; then
    kill "$HARNESS_PID" >/dev/null 2>&1 || true
    wait "$HARNESS_PID" >/dev/null 2>&1 || true
  fi
  if [[ -n "${LAB_PID:-}" ]] && kill -0 "$LAB_PID" 2>/dev/null; then
    kill "$LAB_PID" >/dev/null 2>&1 || true
    wait "$LAB_PID" >/dev/null 2>&1 || true
  fi
  bash scripts/faults/netem_control.sh remove >/dev/null 2>&1 || true
  bash scripts/faults/m2_e0_netns.sh teardown >/dev/null 2>&1 || true
  rm -f "$PLAN_ASSET_PATH"
}
trap cleanup EXIT

python3 scripts/measurement/m2_transport_pair_plan.py build   --scenario "$scenario"   --work test-fixtures/network/m2/g2/work-f1-video-segment-1.json   --device-state test-fixtures/network/m2/g2/device-api36-emulator.json   --cache-state test-fixtures/network/m2/g2/cache-empty-http-disabled.json   --recovery-policy test-fixtures/network/m2/g2/recovery-sponge-v2.json   --route-policy test-fixtures/network/m2/g2/route-exact-default.json   --run-id "$RUN_ID"   --pair-id "$PAIR_ID"   --ordering-seed 20261001   --blocks 2   --playback-mode SPONGE   --connection-state COLD   --output "$ROOT/plan.json"

python3 scripts/measurement/m2_transport_pair_plan.py verify   --plan "$ROOT/plan.json"   --scenario "$scenario"   --work test-fixtures/network/m2/g2/work-f1-video-segment-1.json   --device-state test-fixtures/network/m2/g2/device-api36-emulator.json   --cache-state test-fixtures/network/m2/g2/cache-empty-http-disabled.json   --recovery-policy test-fixtures/network/m2/g2/recovery-sponge-v2.json   --route-policy test-fixtures/network/m2/g2/route-exact-default.json

cp "$ROOT/plan.json" "$PLAN_ASSET_PATH"
python3 - "$ROOT/plan.json" > "$ROOT/schedule.txt" <<'PY'
import json, sys
plan = json.load(open(sys.argv[1], encoding="utf-8"))
for block in plan["blocks"]:
    for row in sorted(block["trials"], key=lambda value: value["positionInBlock"]):
        print(row["trialId"])
PY
test "$(wc -l < "$ROOT/schedule.txt")" -eq 4

./gradlew --dependency-verification=strict --configuration-cache   :core:engine:assembleDebugAndroidTest   :tools:media-lab:installDist

bash scripts/ci/prepare-emulator.sh 36 "m2-g2-$slug-api36" "$ROOT/device"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
test -n "$SDK_ROOT"
export PATH="$SDK_ROOT/platform-tools:$SDK_ROOT/emulator:$PATH"
export ANDROID_AVD_HOME="${RUNNER_TEMP}/spongetube-avd-36-m2-g2-$slug-api36"
command -v adb >/dev/null
adb get-state | grep -Fxq device

checkout_sha="$(git rev-parse HEAD)"
[[ "$SOURCE_HEAD_SHA" =~ ^[0-9a-f]{40}$ ]]
[[ "$checkout_sha" =~ ^[0-9a-f]{40}$ ]]
printf '%s\n' "$SOURCE_HEAD_SHA" > "$ROOT/source-head-commit.txt"
printf '%s\n' "$checkout_sha" > "$ROOT/checkout-commit.txt"

bash scripts/faults/install_iproute2_tc.sh "$TOOLS" > "$ROOT/tc-version.txt"
export M2_TC_BIN="$TOOLS/tc"

LAB_BIN="$(find tools/media-lab/build/install -type f -name media-lab -perm -111 | head -1)"
test -n "$LAB_BIN"
LAB_BIN="$(realpath "$LAB_BIN")"

bash scripts/faults/m2_e0_netns.sh prepare
bash scripts/faults/netem_control.sh prepare-fidelity
NS="$(bash scripts/faults/m2_e0_netns.sh namespace)"
MEDIA_ADDR="$(bash scripts/faults/m2_e0_netns.sh media-address)"
LAB_IF="$(bash scripts/faults/m2_e0_netns.sh lab-interface)"

# Canonical N5 remains at the standard packetization profile. The separate
# deterministic GE effect profile uses MTU 512 to provide a larger, frozen
# packet opportunity window without mutating application work.
if [[ "$FAMILY" == "N5GE" ]]; then
  sudo -n ip netns exec "$NS" ip link set dev "$LAB_IF" mtu 512
fi

test -n "${ImageOS:-}" && test -n "${ImageVersion:-}"
python3 scripts/faults/m2_network_environment.py   --tc-bin "$M2_TC_BIN"   --tc-source-sha "$TOOLS/iproute2-source.sha256"   --runner-image-os "$ImageOS"   --runner-image-version "$ImageVersion"   --output "$ROOT/fault-engine-fingerprint.json"

bash scripts/faults/netem_control.sh inspect-link-state > "$ROOT/link-state.json"
expected_mtu=1500
[[ "$FAMILY" == "N5GE" ]] && expected_mtu=512
jq -e --argjson mtu "$expected_mtu" '.mtu == $mtu' "$ROOT/link-state.json" >/dev/null

adb get-state | tee "$ROOT/adb-before.txt" | grep -Fxq device
serial="$(adb get-serialno | tr -d '\r')"
[[ "$serial" =~ ^emulator-[0-9]+$ ]]
api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
abi="$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
fingerprint="$(adb shell getprop ro.build.fingerprint | tr -d '\r')"
build_id="$(adb shell getprop ro.build.id | tr -d '\r')"
security_patch="$(adb shell getprop ro.build.version.security_patch | tr -d '\r')"
android_kernel="$(adb shell uname -r | tr -d '\r')"
test "$api" = "36"
test "$abi" = "x86_64"
adb shell dumpsys battery > "$ROOT/battery.txt"
grep -Fq 'AC powered: true' "$ROOT/battery.txt"

python3 - "$ROOT/android-runtime.json" "$api" "$abi" "$fingerprint" "$build_id" "$security_patch" "$android_kernel" <<'PY'
import json, sys
path, api, abi, fingerprint, build_id, security_patch, kernel = sys.argv[1:]
with open(path, "w", encoding="utf-8") as handle:
    json.dump({
        "deviceClass": "ANDROID_EMULATOR",
        "androidApi": int(api),
        "abi": abi,
        "buildFingerprint": fingerprint,
        "buildId": build_id,
        "securityPatch": security_patch,
        "kernelRelease": kernel,
        "batteryPolicy": "CI_POWERED",
        "acPowered": True,
    }, handle, indent=2, sort_keys=True)
    handle.write("\n")
PY

python3 scripts/measurement/m2_transport_environment.py build   --run-id "$RUN_ID"   --source-head-commit "$SOURCE_HEAD_SHA"   --checkout-commit "$checkout_sha"   --fault-engine-fingerprint "$ROOT/fault-engine-fingerprint.json"   --link-state "$ROOT/link-state.json"   --android-runtime "$ROOT/android-runtime.json"   --output "$ROOT/transport-experiment-environment-v1.json"

python3 scripts/measurement/m2_transport_environment.py verify   --environment "$ROOT/transport-experiment-environment-v1.json"   --fault-engine-fingerprint "$ROOT/fault-engine-fingerprint.json"   --link-state "$ROOT/link-state.json"   --android-runtime "$ROOT/android-runtime.json"

sudo -n ip netns exec "$NS"   env JAVA_HOME="$JAVA_HOME" PATH="$JAVA_HOME/bin:/usr/bin:/bin"   "$LAB_BIN" serve     "--fixture-root=$PWD/test-fixtures/media"     "--trace=$TRACE"     "--session-id=m2-g2-$slug"     --profile=N0     "--data-bind-address=$MEDIA_ADDR"     --data-port=18081     --control-port=18082   > "$SERVER_ROOT/media-lab.out" 2> "$SERVER_ROOT/media-lab.err" &
LAB_PID=$!

for attempt in {1..60}; do
  if sudo -n ip netns exec "$NS"     curl --fail --silent http://127.0.0.1:18082/__lab/config     > "$SERVER_ROOT/config.json"; then
    break
  fi
  if [[ "$attempt" -eq 60 ]]; then
    cat "$SERVER_ROOT/media-lab.err" >&2 || true
    exit 1
  fi
  sleep 0.1
done

if adb reverse --list | grep -Eq 'tcp:18081([[:space:]]|$)'; then
  echo "G2-C media path must not use adb reverse" >&2
  exit 1
fi

jitter_seed="$(python3 - <<'PY'
import json
print(json.load(open("test-fixtures/network/m2/g2/recovery-sponge-v2.json"))["jitterSeed"])
PY
)"
test "$jitter_seed" = "424243"

scope_snapshot() {
  local output="$1"
  local packets bytes
  packets="$(sudo -n ip netns exec "$NS" cat "/sys/class/net/$LAB_IF/statistics/tx_packets")"
  bytes="$(sudo -n ip netns exec "$NS" cat "/sys/class/net/$LAB_IF/statistics/tx_bytes")"
  python3 - "$output" "$packets" "$bytes" <<'PY'
import json, sys
path, packets, bytes_ = sys.argv[1:]
with open(path, "w", encoding="utf-8") as handle:
    json.dump({"mediaPackets": int(packets), "mediaBytes": int(bytes_)}, handle, sort_keys=True)
    handle.write("\n")
PY
}

trace_count() {
  if [[ -f "$TRACE" ]]; then
    wc -l < "$TRACE"
  else
    printf '0\n'
  fi
}

await_trace_settle() {
  local raw_file="$1"
  local before_count="$2"
  python3 - "$TRACE" "$raw_file" "$before_count" <<'PY'
import json
import pathlib
import sys
import time

trace_path = pathlib.Path(sys.argv[1])
raw_path = pathlib.Path(sys.argv[2])
before = int(sys.argv[3])
raw_trial = json.loads(raw_path.read_text(encoding="utf-8"))
correlated = {
    int(value) for value in raw_trial["proof"]["correlatedOriginRequestIds"]
}
if not correlated:
    raise SystemExit("successful G2-C trial exposed no origin correlation")

deadline = time.monotonic() + 5.0
stable_signature = None
stable_since = None

def complete_rows():
    if not trace_path.exists():
        return []
    raw = trace_path.read_text(encoding="utf-8")
    complete_end = raw.rfind("\n")
    if complete_end < 0:
        return []
    rows = []
    for line in raw[:complete_end].splitlines():
        if line.strip():
            rows.append(json.loads(line))
    return rows

while time.monotonic() < deadline:
    rows = complete_rows()
    trial_rows = rows[before:]
    ids = {
        row.get("requestId")
        for row in trial_rows
        if isinstance(row.get("requestId"), int)
    }
    # A physical owner may fail before reaching HTTP (for example
    # CONNECT_TIMEOUT). Wait only for device-correlated origin-reaching owners,
    # then require the complete trace partition to remain stable long enough to
    # capture any transport-internal replay that occurred before chain terminal.
    enough = correlated.issubset(ids)
    signature = (
        len(rows),
        tuple(row.get("requestId") for row in trial_rows),
    )
    now = time.monotonic()
    if enough:
        if signature == stable_signature:
            if stable_since is not None and now - stable_since >= 0.5:
                print(len(rows))
                raise SystemExit(0)
        else:
            stable_signature = signature
            stable_since = now
    else:
        stable_signature = None
        stable_since = None
    time.sleep(0.05)

raise SystemExit(
    "Media Lab trace did not settle with all device-correlated requests "
    f"within 5s: before={before}, correlated={sorted(correlated)}"
)
PY
}

verify_active_state() {
  local path="$1"
  python3 - "$FAMILY" "$path" <<'PY'
import json, sys
family, path = sys.argv[1:]
value = json.load(open(path, encoding="utf-8"))
common = {
    "direction": "DOWNSTREAM",
    "ipFamily": "IPV4",
    "l4Protocol": "TCP",
    "mediaPortScoped": True,
    "scope": "MEDIA_DATA_ONLY",
}
expected = {
    "N2": {**common, "delayUs": 100000, "jitterUs": 30000, "delayCorrelationPpm": 250000, "randomSeed": 424242},
    "N3": {**common, "lossPpm": 1000000},
    "N5": {**common, "lossPpm": 20000, "burstCorrelationPpm": 250000, "randomSeed": 424242},
    "N5GE": {
        **common,
        "goodToBadPpm": 15000,
        "badToGoodPpm": 735000,
        "badLossPpm": 1000000,
        "goodLossPpm": 0,
        "randomSeed": 424242,
    },
}[family]
if value != expected:
    raise SystemExit(f"{family} active netem state drift: {value!r}")
PY
}

while IFS= read -r trial_id; do
  test -n "$trial_id"
  echo "Executing frozen G2-C $FAMILY trial: $trial_id"
  one="$TRIAL_ROOT/$trial_id"
  mkdir -p "$one/harness"

  bash scripts/faults/netem_control.sh remove >/dev/null 2>&1 || true
  bash scripts/faults/netem_control.sh assert-clean
  scope_snapshot "$one/scope-before.json"
  origin_before="$(trace_count)"
  printf '%s\n' "$origin_before" > "$one/origin-before-count.txt"

  common=(
    --scenario "$scenario"
    --evidence "$one/harness/fault-harness-events.json"
    --qdisc-state "$one/harness/qdisc-applied.json"
    --filter-state "$one/harness/filter.json"
    --final-qdisc-state "$one/harness/qdisc-final.json"
    --active-state "$one/harness/netem-active-state.json"
    --clean-state "$one/harness/netem-clean-state.json"
    --run-id "$RUN_ID"
    --session-id "m2-g2-$slug-$trial_id"
  )

  HARNESS_PID=""
  if [[ "$harness_mode" == "pulse" ]]; then
    python3 scripts/faults/m2_network_harness.py pulse "${common[@]}"       > "$one/harness/pulse.out" 2> "$one/harness/pulse.err" &
    HARNESS_PID=$!
    for attempt in {1..120}; do
      if [[ -s "$one/harness/fault-harness-events.json" ]] &&
         jq -e '.events[-1].operation == "FAULT_ARMED"'            "$one/harness/fault-harness-events.json" >/dev/null 2>&1; then
        break
      fi
      if ! kill -0 "$HARNESS_PID" 2>/dev/null; then
        cat "$one/harness/pulse.err" >&2 || true
        exit 1
      fi
      [[ "$attempt" -lt 120 ]] || {
        echo "$FAMILY harness did not arm" >&2
        exit 1
      }
      sleep 0.025
    done
  else
    python3 scripts/faults/m2_network_harness.py start "${common[@]}"
  fi

  verify_active_state "$one/harness/netem-active-state.json"

  ./gradlew --dependency-verification=strict     :core:engine:connectedDebugAndroidTest     -Pandroid.testInstrumentationRunnerArguments.class=io.github.definitelystable.spongetube.core.engine.route.TransportPairNetworkAndroidTest     -Pandroid.testInstrumentationRunnerArguments.spongetube.m2g2.originBaseUrl="http://$MEDIA_ADDR:18081"     -Pandroid.testInstrumentationRunnerArguments.spongetube.m2g2.trialId="$trial_id"     -Pandroid.testInstrumentationRunnerArguments.spongetube.m2g2.networkScenario="$FAMILY"     -Pandroid.testInstrumentationRunnerArguments.spongetube.m2g2.recoveryJitterSeed="$jitter_seed"

  if [[ "$harness_mode" == "pulse" ]]; then
    wait "$HARNESS_PID"
    HARNESS_PID=""
  else
    python3 scripts/faults/m2_network_harness.py stop "${common[@]}"
  fi
  bash scripts/faults/netem_control.sh assert-clean
  scope_snapshot "$one/scope-after.json"

  source_file="$(find core/engine/build/outputs/connected_android_test_additional_output     -type f -path "*/m2-g2-network/$slug/$trial_id.json" -print -quit)"
  test -n "$source_file"
  cp "$source_file" "$RAW_ROOT/$trial_id.json"

  origin_after="$(await_trace_settle "$RAW_ROOT/$trial_id.json" "$origin_before")"
  printf '%s\n' "$origin_after" > "$one/origin-after-count.txt"
  adb get-state | grep -Fxq device
done < "$ROOT/schedule.txt"

test "$(find "$RAW_ROOT" -maxdepth 1 -type f -name '*.json' | wc -l)" -eq 4
test -s "$TRACE"

python3 scripts/ci/verify-m2-g2-network.py   --plan "$ROOT/plan.json"   --raw-dir "$RAW_ROOT"   --trial-root "$TRIAL_ROOT"   --origin-trace "$TRACE"   --scenario "$scenario"   --work test-fixtures/network/m2/g2/work-f1-video-segment-1.json   --device-state test-fixtures/network/m2/g2/device-api36-emulator.json   --cache-state test-fixtures/network/m2/g2/cache-empty-http-disabled.json   --recovery-policy test-fixtures/network/m2/g2/recovery-sponge-v2.json   --route-policy test-fixtures/network/m2/g2/route-exact-default.json   --environment "$ROOT/transport-experiment-environment-v1.json"   --fault-engine-fingerprint "$ROOT/fault-engine-fingerprint.json"   --link-state "$ROOT/link-state.json"   --android-runtime "$ROOT/android-runtime.json"   --output-dir "$OUTPUT_ROOT"

python3 scripts/measurement/m2_transport_pair_plan.py verify-results   --plan "$ROOT/plan.json"   --trials "$OUTPUT_ROOT/transport-evaluation-trials-v1.json"   --scenario "$scenario"   --work test-fixtures/network/m2/g2/work-f1-video-segment-1.json   --device-state test-fixtures/network/m2/g2/device-api36-emulator.json   --cache-state test-fixtures/network/m2/g2/cache-empty-http-disabled.json   --recovery-policy test-fixtures/network/m2/g2/recovery-sponge-v2.json   --route-policy test-fixtures/network/m2/g2/route-exact-default.json

adb get-state | tee "$ROOT/adb-after.txt" | grep -Fxq device
cleanup
trap - EXIT
bash scripts/faults/m2_e0_netns.sh assert-clean
printf 'ok\n' > "$ROOT/cleanup.txt"
