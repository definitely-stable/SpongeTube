#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 3 ]]; then
  echo "Usage: $0 <host-data-port> [device-serial] [device-port]" >&2
  exit 64
fi

HOST_PORT="$1"
SERIAL="${2:-}"
DEVICE_PORT="${3:-18080}"

ADB=(adb)
if [[ -n "$SERIAL" ]]; then
  ADB+=(-s "$SERIAL")
fi

"${ADB[@]}" reverse "tcp:$DEVICE_PORT" "tcp:$HOST_PORT"
echo "SpongeTube Media Lab data plane: http://localhost:$DEVICE_PORT/"
echo "Control port is intentionally not reversed."
