#!/usr/bin/env bash
set -euo pipefail

DEST_DIR="${1:?destination directory required}"
VERSION="2.12.0"
ASSET="toxiproxy-server-linux-amd64"
EXPECTED_SHA256="556d891134a3c582dc1e1a3f7335fd55142e5965769855a00b944e13e48302fc"
DOWNLOAD_URL="https://github.com/Shopify/toxiproxy/releases/download/v${VERSION}/${ASSET}"
API_PORT="18474"

mkdir -p "$DEST_DIR"
BIN="$DEST_DIR/toxiproxy-server"
TMP="$DEST_DIR/${ASSET}.download"

cleanup() {
  if [[ -n "${SERVER_PID:-}" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
    kill "$SERVER_PID" 2>/dev/null || true
    for _ in {1..20}; do
      kill -0 "$SERVER_PID" 2>/dev/null || break
      sleep 0.1
    done
    kill -9 "$SERVER_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

curl --fail --location --silent --show-error --retry 3 --retry-all-errors   "$DOWNLOAD_URL" -o "$TMP"
printf '%s  %s\n' "$EXPECTED_SHA256" "$TMP" | sha256sum --check --status
install -m 0755 "$TMP" "$BIN"
rm -f "$TMP"

VERSION_TEXT="$("$BIN" -version 2>&1)"
grep -F "$VERSION" <<<"$VERSION_TEXT" >/dev/null

"$BIN" -host 127.0.0.1 -port "$API_PORT"   >"$DEST_DIR/toxiproxy.out" 2>"$DEST_DIR/toxiproxy.err" &
SERVER_PID=$!

API_VERSION=""
PROXIES=""
for attempt in {1..30}; do
  if API_VERSION="$(curl --fail --silent --show-error "http://127.0.0.1:${API_PORT}/version" 2>/dev/null)" &&
     PROXIES="$(curl --fail --silent --show-error "http://127.0.0.1:${API_PORT}/proxies" 2>/dev/null)"; then
    break
  fi
  if [[ "$attempt" -eq 30 ]]; then
    cat "$DEST_DIR/toxiproxy.out" >&2 || true
    cat "$DEST_DIR/toxiproxy.err" >&2 || true
    exit 1
  fi
  sleep 0.2
done

grep -F "$VERSION" <<<"$API_VERSION" >/dev/null
python3 - "$PROXIES" <<'PY'
import json
import sys
value = json.loads(sys.argv[1])
if value != {}:
    raise SystemExit(f"expected zero proxies, got: {value!r}")
PY

ACTUAL_SHA256="$(sha256sum "$BIN" | awk '{print $1}')"
cleanup
SERVER_PID=""

if curl --fail --silent "http://127.0.0.1:${API_PORT}/version" >/dev/null 2>&1; then
  echo "toxiproxy API still reachable after shutdown" >&2
  exit 1
fi

python3 - "$VERSION" "$ACTUAL_SHA256" "$EXPECTED_SHA256" "$API_VERSION" <<'PY'
import json
import sys

version, actual, expected, api_version = sys.argv[1:]
print(json.dumps({
    "schemaVersion": 1,
    "tool": "toxiproxy",
    "version": version,
    "asset": "toxiproxy-server-linux-amd64",
    "sha256": actual,
    "sha256Matches": actual == expected,
    "apiVersionReported": api_version.strip(),
    "apiHealthy": True,
    "proxiesEmpty": True,
    "processStopped": True,
}, sort_keys=True))
PY
