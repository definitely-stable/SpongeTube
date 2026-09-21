#!/usr/bin/env bash
set -euo pipefail

API_LEVEL="${1:?api level required}"
AVD_NAME="${2:?avd name required}"
OUTPUT_DIR="${3:?output directory required}"
BOOT_TIMEOUT_SECONDS="${4:-}"
if [[ -z "$BOOT_TIMEOUT_SECONDS" ]]; then
  if (( API_LEVEL <= 23 )); then
    BOOT_TIMEOUT_SECONDS=420
  else
    BOOT_TIMEOUT_SECONDS=240
  fi
fi

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK_ROOT" ]]; then
  echo "::error::ANDROID_SDK_ROOT/ANDROID_HOME is not set"
  exit 1
fi

SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/avdmanager"
ADB="$SDK_ROOT/platform-tools/adb"
EMULATOR="$SDK_ROOT/emulator/emulator"
IMAGE="system-images;android-${API_LEVEL};google_apis;x86_64"
AVD_HOME="${RUNNER_TEMP:-/tmp}/spongetube-avd-${API_LEVEL}-${AVD_NAME}"

test -x "$SDKMANAGER"
test -x "$AVDMANAGER"
mkdir -p "$OUTPUT_DIR" "$AVD_HOME"

yes | "$SDKMANAGER" --licenses >/dev/null || true

if [[ ! -x "$ADB" ]]; then
  "$SDKMANAGER" "platform-tools"
fi
if [[ ! -x "$EMULATOR" ]]; then
  "$SDKMANAGER" "emulator"
fi
if [[ ! -d "$SDK_ROOT/system-images/android-${API_LEVEL}/google_apis/x86_64" ]]; then
  "$SDKMANAGER" "$IMAGE"
fi

export ANDROID_AVD_HOME="$AVD_HOME"
export PATH="$SDK_ROOT/platform-tools:$SDK_ROOT/emulator:$PATH"

if [[ -n "${GITHUB_PATH:-}" ]]; then
  echo "$SDK_ROOT/platform-tools" >> "$GITHUB_PATH"
  echo "$SDK_ROOT/emulator" >> "$GITHUB_PATH"
fi
if [[ -n "${GITHUB_ENV:-}" ]]; then
  echo "ANDROID_AVD_HOME=$AVD_HOME" >> "$GITHUB_ENV"
fi

echo "no" | "$AVDMANAGER" create avd \
  --force \
  --name "$AVD_NAME" \
  --package "$IMAGE" \
  --device "pixel_5" \
  --path "$AVD_HOME/$AVD_NAME.avd"

test -f "$AVD_HOME/$AVD_NAME.ini"

if [[ -e /dev/kvm ]]; then
  sudo chmod 666 /dev/kvm
fi

"$ADB" start-server
"$EMULATOR" \
  -avd "$AVD_NAME" \
  -no-window \
  -no-audio \
  -no-boot-anim \
  -no-snapshot \
  -accel on \
  -cores 2 \
  -memory 2048 \
  -gpu swiftshader_indirect \
  > "$OUTPUT_DIR/emulator.log" 2>&1 &
EMULATOR_PID=$!
echo "$EMULATOR_PID" > "$OUTPUT_DIR/emulator.pid"

for attempt in {1..90}; do
  if ! kill -0 "$EMULATOR_PID" 2>/dev/null; then
    cat "$OUTPUT_DIR/emulator.log"
    exit 1
  fi
  if "$ADB" devices | grep -Eq '^emulator-[0-9]+[[:space:]]+device$'; then
    break
  fi
  sleep 1
done

if ! timeout "$BOOT_TIMEOUT_SECONDS" bash -c '
  until [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d "\r")" == "1" ]]; do
    sleep 2
  done
'; then
  echo "::error::emulator API $API_LEVEL did not complete boot within ${BOOT_TIMEOUT_SECONDS}s"
  "$ADB" devices -l || true
  "$ADB" shell getprop > "$OUTPUT_DIR/getprop-timeout.txt" 2>&1 || true
  "$ADB" logcat -d -v threadtime > "$OUTPUT_DIR/logcat-timeout.txt" 2>&1 || true
  tail -n 200 "$OUTPUT_DIR/emulator.log" || true
  exit 124
fi

"$ADB" shell settings put global window_animation_scale 0
"$ADB" shell settings put global transition_animation_scale 0
"$ADB" shell settings put global animator_duration_scale 0

"$ADB" shell getprop ro.build.version.sdk | tr -d '\r' > "$OUTPUT_DIR/device-api.txt"
"$ADB" shell getprop ro.build.fingerprint | tr -d '\r' > "$OUTPUT_DIR/device-fingerprint.txt"
"$ADB" shell getprop ro.product.model | tr -d '\r' > "$OUTPUT_DIR/device-model.txt"
"$ADB" shell getprop ro.product.cpu.abi | tr -d '\r' > "$OUTPUT_DIR/device-abi.txt"

ACTUAL_API="$(cat "$OUTPUT_DIR/device-api.txt")"
if [[ "$ACTUAL_API" != "$API_LEVEL" ]]; then
  echo "::error::expected API $API_LEVEL, emulator reports $ACTUAL_API"
  exit 1
fi
