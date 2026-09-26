#!/usr/bin/env bash
set -euo pipefail

OUTPUT_DIR="${1:?output directory required}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOCK="$ROOT/tools/fault-harness/toolchain.lock.json"

for tool in curl sha256sum python3 tar make gcc flex bison pkg-config; do
  command -v "$tool" >/dev/null
done

readarray -t META < <(
  python3 - "$LOCK" <<'PY'
import json, pathlib, sys
data = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
tool = data["netem"]["labTc"]
for key in ("version", "asset", "sha256", "downloadSource"):
    value = tool.get(key)
    if not isinstance(value, str) or not value:
        raise SystemExit(f"invalid lab tc lock field: {key}")
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

[[ "$ASSET" == "iproute2-${VERSION}.tar.xz" ]] || {
  echo "unexpected iproute2 asset: $ASSET" >&2
  exit 2
}
[[ "$EXPECTED_SHA" =~ ^[0-9a-f]{64}$ ]] || {
  echo "invalid iproute2 SHA-256" >&2
  exit 2
}
[[ "$SOURCE" == "https://www.kernel.org/pub/linux/utils/net/iproute2/${ASSET}" ]] || {
  echo "unexpected iproute2 source" >&2
  exit 2
}

mkdir -p "$OUTPUT_DIR"
ARCHIVE="$OUTPUT_DIR/$ASSET"
SOURCE_DIR="$OUTPUT_DIR/iproute2-${VERSION}"
BIN="$OUTPUT_DIR/tc"
rm -rf "$ARCHIVE" "$SOURCE_DIR" "$BIN"

curl --fail --location --silent --show-error \
  --proto '=https' --tlsv1.2 \
  --retry 3 --retry-all-errors \
  "$SOURCE" -o "$ARCHIVE"

ACTUAL_SHA="$(sha256sum "$ARCHIVE" | awk '{print $1}')"
if [[ "$ACTUAL_SHA" != "$EXPECTED_SHA" ]]; then
  echo "iproute2 checksum mismatch" >&2
  echo "expected: $EXPECTED_SHA" >&2
  echo "actual:   $ACTUAL_SHA" >&2
  rm -f "$ARCHIVE"
  exit 1
fi

tar -xJf "$ARCHIVE" -C "$OUTPUT_DIR"
test -d "$SOURCE_DIR"

(
  cd "$SOURCE_DIR"
  make -s config.mk
  make -s -j2 -C lib
  make -s -j2 -C tc tc
)

install -m 0755 "$SOURCE_DIR/tc/tc" "$BIN"
VERSION_OUTPUT="$("$BIN" -V)"
[[ "$VERSION_OUTPUT" == "tc utility, iproute2-${VERSION}"* ]] || {
  echo "unexpected tc version output: $VERSION_OUTPUT" >&2
  exit 1
}

printf '%s\n' "$VERSION_OUTPUT"
printf '%s\n' "$ACTUAL_SHA" > "$OUTPUT_DIR/iproute2-source.sha256"
rm -rf "$ARCHIVE" "$SOURCE_DIR"
