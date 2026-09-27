#!/usr/bin/env bash
set -euo pipefail

DEST_DIR="${1:?destination directory required}"
VERSION="6.6.0"
TAG="v6.6.0"
COMMIT="6b79bc819fefa18e2ff6e522d303ecb1b1c62cb4"
REMOTE="https://github.com/iproute2/iproute2.git"
SOURCE_DIR="$DEST_DIR/iproute2-src"
TC_BIN="$DEST_DIR/tc"

for tool in git gcc make bison flex pkg-config; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "required build tool is missing: $tool" >&2
    exit 1
  }
done

rm -rf "$SOURCE_DIR"
mkdir -p "$DEST_DIR"
git init --quiet "$SOURCE_DIR"
git -C "$SOURCE_DIR" remote add origin "$REMOTE"
git -C "$SOURCE_DIR" fetch --quiet --depth=1 --no-tags origin "refs/tags/$TAG"

FETCHED="$(git -C "$SOURCE_DIR" rev-parse 'FETCH_HEAD^{commit}')"
if [[ "$FETCHED" != "$COMMIT" ]]; then
  echo "iproute2 $TAG resolved to $FETCHED, expected $COMMIT" >&2
  exit 1
fi

git -C "$SOURCE_DIR" checkout --quiet --detach "$COMMIT"
(
  cd "$SOURCE_DIR"
  ./configure --libbpf_force=off >/dev/null
  make --jobs=2 tc/tc >/dev/null
)

install -m 0755 "$SOURCE_DIR/tc/tc" "$TC_BIN"
VERSION_TEXT="$("$TC_BIN" -Version 2>&1)"
grep -F "iproute2-$VERSION" <<<"$VERSION_TEXT" >/dev/null

python3 - "$VERSION" "$TAG" "$COMMIT" "$VERSION_TEXT" "$TC_BIN" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

version, tag, commit, version_text, path = sys.argv[1:]
digest = hashlib.sha256(Path(path).read_bytes()).hexdigest()
print(json.dumps({
    "schemaVersion": 1,
    "tool": "tc",
    "iproute2Version": version,
    "tag": tag,
    "gitCommit": commit,
    "binarySha256": digest,
    "versionReported": version_text.strip(),
    "systemPackageReplacement": False,
}, sort_keys=True))
PY
