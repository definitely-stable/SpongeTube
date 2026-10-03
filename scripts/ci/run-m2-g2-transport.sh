#!/usr/bin/env bash
set -euo pipefail

: "${SOURCE_HEAD_SHA:?SOURCE_HEAD_SHA required}"
ROOT="$PWD/build/m2-g2-transport/n6"
TOOLS="${RUNNER_TEMP:?RUNNER_TEMP required}/m2-g2-transport-tools"
RAW_ROOT="$ROOT/raw"
TRIAL_ROOT="$ROOT/trials"
SERVER_ROOT="$ROOT/server"
OUTPUT_ROOT="$ROOT/output"
RUN_ID="m2-g2-n6-api36"
PAIR_ID="m2-g2-n6-api36-cold"
SCENARIO="$PWD/test-fixtures/network/m2/n6-transport-reset.json"
PLAN_ASSET="m2-g2-n6-plan.json"
PLAN_ASSET_PATH="core/engine/src/androidTest/assets/$PLAN_ASSET"
TRACE="$SERVER_ROOT/requests.jsonl"
CONTROL_PORT=18475
CONTROL_URL="http://127.0.0.1:$CONTROL_PORT"

mkdir -p "$ROOT" "$TOOLS" "$RAW_ROOT" "$TRIAL_ROOT" "$SERVER_ROOT" "$OUTPUT_ROOT"   core/engine/src/androidTest/assets

cleanup() {
  set +e
  if [[ -n "${CONTROLLER_PID:-}" ]] && kill -0 "$CONTROLLER_PID" 2>/dev/null; then
    kill "$CONTROLLER_PID" >/dev/null 2>&1 || true
    wait "$CONTROLLER_PID" >/dev/null 2>&1 || true
  fi
  adb reverse --remove "tcp:$CONTROL_PORT" >/dev/null 2>&1 || true
  if [[ -n "${LAB_PID:-}" ]] && kill -0 "$LAB_PID" 2>/dev/null; then
    kill "$LAB_PID" >/dev/null 2>&1 || true
    wait "$LAB_PID" >/dev/null 2>&1 || true
  fi
  if [[ -n "${TOXI_PID:-}" ]] && kill -0 "$TOXI_PID" 2>/dev/null; then
    kill "$TOXI_PID" >/dev/null 2>&1 || true
    wait "$TOXI_PID" >/dev/null 2>&1 || true
  fi
  bash scripts/faults/m2_e0_netns.sh teardown >/dev/null 2>&1 || true
  rm -f "$PLAN_ASSET_PATH"
}
trap cleanup EXIT

python3 scripts/measurement/m2_transport_pair_plan.py build   --scenario "$SCENARIO"   --work test-fixtures/network/m2/g2/work-f1-video-segment-1.json   --device-state test-fixtures/network/m2/g2/device-api36-emulator.json   --cache-state test-fixtures/network/m2/g2/cache-empty-http-disabled.json   --recovery-policy test-fixtures/network/m2/g2/recovery-sponge-v2.json   --route-policy test-fixtures/network/m2/g2/route-exact-default.json   --run-id "$RUN_ID"   --pair-id "$PAIR_ID"   --ordering-seed 20261001   --blocks 2   --playback-mode SPONGE   --connection-state COLD   --output "$ROOT/plan.json"

python3 scripts/measurement/m2_transport_pair_plan.py verify   --plan "$ROOT/plan.json"   --scenario "$SCENARIO"   --work test-fixtures/network/m2/g2/work-f1-video-segment-1.json   --device-state test-fixtures/network/m2/g2/device-api36-emulator.json   --cache-state test-fixtures/network/m2/g2/cache-empty-http-disabled.json   --recovery-policy test-fixtures/network/m2/g2/recovery-sponge-v2.json   --route-policy test-fixtures/network/m2/g2/route-exact-default.json

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

bash scripts/ci/prepare-emulator.sh 36 m2-g2-n6-api36 "$ROOT/device"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
test -n "$SDK_ROOT"
export PATH="$SDK_ROOT/platform-tools:$SDK_ROOT/emulator:$PATH"
export ANDROID_AVD_HOME="${RUNNER_TEMP}/spongetube-avd-36-m2-g2-n6-api36"
adb get-state | grep -Fxq device

checkout_sha="$(git rev-parse HEAD)"
[[ "$SOURCE_HEAD_SHA" =~ ^[0-9a-f]{40}$ ]]
[[ "$checkout_sha" =~ ^[0-9a-f]{40}$ ]]
printf '%s\n' "$SOURCE_HEAD_SHA" > "$ROOT/source-head-commit.txt"
printf '%s\n' "$checkout_sha" > "$ROOT/checkout-commit.txt"

bash scripts/faults/install_toxiproxy.sh "$TOOLS" > "$ROOT/toxiproxy-version.txt"
TOXI_BIN="$(realpath "$TOOLS/toxiproxy-server")"
TOXI_SHA="$(cat "$TOOLS/toxiproxy-server.sha256")"
[[ "$TOXI_SHA" =~ ^[0-9a-f]{64}$ ]]

LAB_BIN="$(find tools/media-lab/build/install -type f -name media-lab -perm -111 | head -1)"
test -n "$LAB_BIN"
LAB_BIN="$(realpath "$LAB_BIN")"

bash scripts/faults/m2_e0_netns.sh prepare
NS="$(bash scripts/faults/m2_e0_netns.sh namespace)"
MEDIA_ADDR="$(bash scripts/faults/m2_e0_netns.sh media-address)"
API="http://$MEDIA_ADDR:18474"

sudo -n ip netns exec "$NS"   "$TOXI_BIN" -host "$MEDIA_ADDR" -port 18474   > "$ROOT/toxiproxy.out" 2> "$ROOT/toxiproxy.err" &
TOXI_PID=$!
for attempt in {1..50}; do
  if curl --fail --silent "$API/version" >/dev/null; then
    break
  fi
  [[ "$attempt" -lt 50 ]] || {
    cat "$ROOT/toxiproxy.err" >&2 || true
    exit 1
  }
  sleep 0.1
done

sudo -n ip netns exec "$NS"   env JAVA_HOME="$JAVA_HOME" PATH="$JAVA_HOME/bin:/usr/bin:/bin"   "$LAB_BIN" serve     "--fixture-root=$PWD/test-fixtures/media"     "--trace=$TRACE"     --session-id=m2-g2-n6     --profile=N0     --data-port=18081     --control-port=18082   > "$SERVER_ROOT/media-lab.out" 2> "$SERVER_ROOT/media-lab.err" &
LAB_PID=$!

for attempt in {1..60}; do
  if sudo -n ip netns exec "$NS"     curl --fail --silent http://127.0.0.1:18082/__lab/config     > "$SERVER_ROOT/config.json"; then
    break
  fi
  [[ "$attempt" -lt 60 ]] || {
    cat "$SERVER_ROOT/media-lab.err" >&2 || true
    exit 1
  }
  sleep 0.1
done

if adb reverse --list | grep -Eq 'tcp:18080([[:space:]]|$)'; then
  echo "G2-D media path must not use adb reverse" >&2
  exit 1
fi

api_level="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
abi="$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
fingerprint="$(adb shell getprop ro.build.fingerprint | tr -d '\r')"
build_id="$(adb shell getprop ro.build.id | tr -d '\r')"
security_patch="$(adb shell getprop ro.build.version.security_patch | tr -d '\r')"
android_kernel="$(adb shell uname -r | tr -d '\r')"
test "$api_level" = "36"
test "$abi" = "x86_64"
adb shell dumpsys battery > "$ROOT/battery.txt"
grep -Fq 'AC powered: true' "$ROOT/battery.txt"

python3 - "$ROOT/android-runtime.json" "$api_level" "$abi" "$fingerprint" "$build_id" "$security_patch" "$android_kernel" <<'PY'
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

python3 scripts/measurement/m2_transport_reset_environment.py build \
  --run-id "$RUN_ID" \
  --source-head-commit "$SOURCE_HEAD_SHA" \
  --checkout-commit "$checkout_sha" \
  --toxiproxy-sha256 "$TOXI_SHA" \
  --android-runtime "$ROOT/android-runtime.json" \
  --output "$ROOT/transport-reset-environment-v1.json"

python3 scripts/measurement/m2_transport_reset_environment.py verify \
  --environment "$ROOT/transport-reset-environment-v1.json" \
  --android-runtime "$ROOT/android-runtime.json"

jitter_seed="$(python3 - <<'PY'
import json
print(json.load(open("test-fixtures/network/m2/g2/recovery-sponge-v2.json"))["jitterSeed"])
PY
)"
test "$jitter_seed" = "424243"

trace_count() {
  if [[ -f "$TRACE" ]]; then wc -l < "$TRACE"; else printf '0\n'; fi
}

await_trace_settle() {
  local raw_file="$1"
  local before_count="$2"
  python3 - "$TRACE" "$raw_file" "$before_count" <<'PY'
import json, pathlib, sys, time
trace_path = pathlib.Path(sys.argv[1])
raw_path = pathlib.Path(sys.argv[2])
before = int(sys.argv[3])
raw_trial = json.loads(raw_path.read_text(encoding="utf-8"))
correlated = {int(value) for value in raw_trial["proof"]["correlatedOriginRequestIds"]}
if not correlated:
    raise SystemExit("successful G2-D trial exposed no origin correlation")
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
    return [json.loads(line) for line in raw[:complete_end].splitlines() if line.strip()]

while time.monotonic() < deadline:
    rows = complete_rows()
    trial_rows = rows[before:]
    ids = {row.get("requestId") for row in trial_rows if isinstance(row.get("requestId"), int)}
    enough = correlated.issubset(ids)
    signature = (len(rows), tuple(row.get("requestId") for row in trial_rows))
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
raise SystemExit("Media Lab trace did not settle with device-correlated N6 requests")
PY
}

while IFS= read -r trial_id; do
  test -n "$trial_id"
  echo "Executing frozen G2-D N6 trial: $trial_id"
  one="$TRIAL_ROOT/$trial_id"
  mkdir -p "$one/harness"

  python3 scripts/faults/toxiproxy_control.py --api "$API" assert-clean
  origin_before="$(trace_count)"
  printf '%s\n' "$origin_before" > "$one/origin-before-count.txt"

  common=(
    --scenario "$SCENARIO"
    --api "$API"
    --evidence "$one/harness/fault-harness-events.json"
    --run-id "$RUN_ID"
    --session-id "m2-g2-n6-$trial_id"
  )

  python3 scripts/faults/m2_transport_harness.py start "${common[@]}"     --state "$one/harness/toxiproxy-active-state.json"     --listen "$MEDIA_ADDR:18080"     --upstream 127.0.0.1:18081

  jq -e '
    .name == "m2e-media" and .enabled == true and
    (.toxics | length) == 1 and
    .toxics[0].name == "m2e-fault" and
    .toxics[0].type == "reset_peer" and
    .toxics[0].stream == "downstream" and
    .toxics[0].toxicityPpm == 1000000 and
    .toxics[0].attributes.timeout == 0
  ' "$one/harness/toxiproxy-active-state.json" >/dev/null

  rm -f "$one/controller-ready.json" "$one/reset-trigger.json"
  adb reverse --remove "tcp:$CONTROL_PORT" >/dev/null 2>&1 || true

  python3 scripts/faults/m2_transport_reset_controller.py \
    --port "$CONTROL_PORT" \
    --trial-id "$trial_id" \
    --trace "$TRACE" \
    --origin-before-count "$origin_before" \
    --trigger "$one/reset-trigger.json" \
    --ready "$one/controller-ready.json" \
    --scenario "$SCENARIO" \
    --api "$API" \
    --evidence "$one/harness/fault-harness-events.json" \
    --disarmed-state "$one/harness/toxiproxy-disarmed-state.json" \
    --run-id "$RUN_ID" \
    --session-id "m2-g2-n6-$trial_id" \
    > "$one/controller.out" 2> "$one/controller.err" &
  CONTROLLER_PID=$!

  for attempt in {1..200}; do
    if [[ -s "$one/controller-ready.json" ]] &&
       jq -e --arg trial "$trial_id" '
         .state == "LISTENING" and
         .controlProtocol == "ADB_REVERSE_LOOPBACK_HTTP_V1" and
         .trialId == $trial
       ' "$one/controller-ready.json" >/dev/null 2>&1; then
      break
    fi
    if ! kill -0 "$CONTROLLER_PID" 2>/dev/null; then
      cat "$one/controller.err" >&2 || true
      wait "$CONTROLLER_PID" || true
      exit 1
    fi
    [[ "$attempt" -lt 200 ]] || {
      echo "G2-D reset controller did not become ready" >&2
      exit 1
    }
    sleep 0.025
  done

  adb reverse "tcp:$CONTROL_PORT" "tcp:$CONTROL_PORT"
  adb reverse --list | grep -Fq "tcp:$CONTROL_PORT tcp:$CONTROL_PORT"
  if adb reverse --list | grep -Eq 'tcp:18080([[:space:]]|$)'; then
    echo "G2-D media path unexpectedly entered adb reverse" >&2
    exit 1
  fi

  ./gradlew --dependency-verification=strict \
    :core:engine:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=io.github.definitelystable.spongetube.core.engine.route.TransportPairNetworkAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.spongetube.m2g2.originBaseUrl="http://$MEDIA_ADDR:18080" \
    -Pandroid.testInstrumentationRunnerArguments.spongetube.m2g2.trialId="$trial_id" \
    -Pandroid.testInstrumentationRunnerArguments.spongetube.m2g2.scenario=N6 \
    -Pandroid.testInstrumentationRunnerArguments.spongetube.m2g2.recoveryJitterSeed="$jitter_seed" \
    -Pandroid.testInstrumentationRunnerArguments.spongetube.m2g2.transportControlUrl="$CONTROL_URL"

  wait "$CONTROLLER_PID"
  CONTROLLER_PID=""
  adb reverse --remove "tcp:$CONTROL_PORT"
  test -s "$one/reset-trigger.json"

  source_file="$(find core/engine/build/outputs/connected_android_test_additional_output     -type f -path "*/m2-g2-transport/n6/$trial_id.json" -print -quit)"
  test -n "$source_file"
  cp "$source_file" "$RAW_ROOT/$trial_id.json"

  origin_after="$(await_trace_settle "$RAW_ROOT/$trial_id.json" "$origin_before")"
  printf '%s\n' "$origin_after" > "$one/origin-after-count.txt"

  python3 scripts/faults/m2_transport_harness.py stop "${common[@]}"     --state "$one/harness/toxiproxy-clean-state.json"
  python3 scripts/faults/toxiproxy_control.py --api "$API" assert-clean
  adb get-state | grep -Fxq device
done < "$ROOT/schedule.txt"

test "$(find "$RAW_ROOT" -maxdepth 1 -type f -name '*.json' | wc -l)" -eq 4
test -s "$TRACE"

python3 scripts/ci/verify-m2-g2-transport.py   --plan "$ROOT/plan.json"   --raw-dir "$RAW_ROOT"   --trial-root "$TRIAL_ROOT"   --origin-trace "$TRACE"   --scenario "$SCENARIO"   --environment "$ROOT/transport-reset-environment-v1.json"   --android-runtime "$ROOT/android-runtime.json"   --work test-fixtures/network/m2/g2/work-f1-video-segment-1.json   --device-state test-fixtures/network/m2/g2/device-api36-emulator.json   --cache-state test-fixtures/network/m2/g2/cache-empty-http-disabled.json   --recovery-policy test-fixtures/network/m2/g2/recovery-sponge-v2.json   --route-policy test-fixtures/network/m2/g2/route-exact-default.json   --output-dir "$OUTPUT_ROOT"

python3 scripts/measurement/m2_transport_pair_plan.py verify-results   --plan "$ROOT/plan.json"   --trials "$OUTPUT_ROOT/transport-evaluation-trials-v1.json"   --scenario "$SCENARIO"   --work test-fixtures/network/m2/g2/work-f1-video-segment-1.json   --device-state test-fixtures/network/m2/g2/device-api36-emulator.json   --cache-state test-fixtures/network/m2/g2/cache-empty-http-disabled.json   --recovery-policy test-fixtures/network/m2/g2/recovery-sponge-v2.json   --route-policy test-fixtures/network/m2/g2/route-exact-default.json

adb get-state | grep -Fxq device
cleanup
trap - EXIT
bash scripts/faults/m2_e0_netns.sh assert-clean
printf 'ok\n' > "$ROOT/cleanup.txt"
