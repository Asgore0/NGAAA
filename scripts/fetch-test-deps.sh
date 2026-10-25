#!/usr/bin/env bash
# Test-only dependency. An existing TEST_JSON_JAR permits a fully offline run.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="${TEST_JSON_JAR:-$ROOT/.build/test-deps/json-20240303.jar}"
EXPECTED=3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed
if [[ ! -f "$DEST" ]]; then
  [[ "${OFFLINE:-0}" != 1 ]] || { printf 'Offline: provide TEST_JSON_JAR or download the test dependency first.\n' >&2; exit 1; }
  mkdir -p "$(dirname "$DEST")"
  TEMP="$(mktemp "${DEST}.download.XXXXXX")"
  trap 'rm -f "$TEMP"' EXIT
  curl --fail --location --proto '=https' --proto-redir '=https' --max-time 60 \
    https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar -o "$TEMP"
  python3 - "$TEMP" "$EXPECTED" <<'PY'
import hashlib, pathlib, sys
if hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest() != sys.argv[2]:
    raise SystemExit('Test dependency checksum mismatch')
PY
  mv "$TEMP" "$DEST"
fi
python3 - "$DEST" "$EXPECTED" <<'PY'
import hashlib, pathlib, sys
path = pathlib.Path(sys.argv[1]).resolve()
if hashlib.sha256(path.read_bytes()).hexdigest() != sys.argv[2]:
    raise SystemExit('Test dependency checksum mismatch')
print(path)
PY
