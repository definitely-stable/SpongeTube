#!/usr/bin/env bash
set -euo pipefail

OUTPUT_DIR="${1:?output directory required}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOCK="$ROOT/tools/fault-harness/toolchain.lock.json"

command -v curl >/dev/null
command -v sha256sum >/dev/null
command -v python3 >/dev/null
test -f "$LOCK"

readarray -t META < <(
  python3 - "$LOCK" <<'PY'
import json, pathlib, sys
data = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
tool = data["toxiproxy"]
for key in ("version", "asset", "sha256", "downloadSource"):
    value = tool.get(key)
    if not isinstance(value, str) or not value:
        raise SystemExit(f"invalid toxiproxy lock field: {key}")
print(tool["version"])
print(tool["asset"])
print(tool["sha256"])
print(tool["downloadSource"])
PY
)

VERSION="${META[0]}"
ASSET="${META[1]}"
EXPECTED_SHA="${META[2]}"
SOURCE="${META[3]}"

case "$ASSET" in
  toxiproxy-server-linux-amd64) ;;
  *) echo "unsupported pinned Toxiproxy asset: $ASSET" >&2; exit 2 ;;
esac
[[ "$VERSION" == "2.12.0" ]] || {
  echo "unexpected Toxiproxy version in lock: $VERSION" >&2
  exit 2
}
[[ "$EXPECTED_SHA" =~ ^[0-9a-f]{64}$ ]] || {
  echo "invalid pinned SHA-256" >&2
  exit 2
}
[[ "$SOURCE" == "https://github.com/Shopify/toxiproxy/releases/download/v${VERSION}/${ASSET}" ]] || {
  echo "unexpected Toxiproxy source" >&2
  exit 2
}

mkdir -p "$OUTPUT_DIR"
TMP="$OUTPUT_DIR/.${ASSET}.download"
BIN="$OUTPUT_DIR/toxiproxy-server"
rm -f "$TMP" "$BIN"

curl --fail --location --silent --show-error \
  --proto '=https' --tlsv1.2 \
  --retry 3 --retry-all-errors \
  "$SOURCE" -o "$TMP"

ACTUAL_SHA="$(sha256sum "$TMP" | awk '{print $1}')"
if [[ "$ACTUAL_SHA" != "$EXPECTED_SHA" ]]; then
  echo "Toxiproxy checksum mismatch" >&2
  echo "expected: $EXPECTED_SHA" >&2
  echo "actual:   $ACTUAL_SHA" >&2
  rm -f "$TMP"
  exit 1
fi

install -m 0755 "$TMP" "$BIN"
rm -f "$TMP"

VERSION_OUTPUT="$("$BIN" -version)"
[[ "$VERSION_OUTPUT" == "toxiproxy-server version ${VERSION}" ]] || {
  echo "unexpected Toxiproxy version output: $VERSION_OUTPUT" >&2
  exit 1
}

printf '%s\n' "$VERSION_OUTPUT"
printf '%s\n' "$ACTUAL_SHA" > "$OUTPUT_DIR/toxiproxy-server.sha256"
