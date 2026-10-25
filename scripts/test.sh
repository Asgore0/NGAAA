#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ "${1:---list}" == --list ]]; then
  printf 'renderer\nentity\n'
  (cd "$ROOT/tests" && find . -name run.sh -not -path '*/.build/*' | sed 's|^./||; s|/run.sh$||' | sort)
  exit 0
fi
[[ $# == 1 ]] || { printf 'Usage: bash scripts/test.sh <suite|--list>\n' >&2; exit 2; }
SUITE="$1"
[[ "$SUITE" =~ ^[a-z0-9_]+(/[a-z0-9_]+)?$ ]] || { printf 'Invalid suite name.\n' >&2; exit 2; }
case "$SUITE" in
  renderer) RUNNER="$ROOT/test-renderer.sh" ;;
  entity) RUNNER="$ROOT/tests/entity_device/run-jvm.sh" ;;
  *) RUNNER="$ROOT/tests/$SUITE/run.sh" ;;
esac
[[ -f "$RUNNER" ]] || { printf 'Unknown JVM suite: %s\n' "$SUITE" >&2; exit 2; }
source "$ROOT/scripts/env.sh"
ngaaa_env
command -v shasum >/dev/null 2>&1 || { printf 'Install shasum (Perl Digest::SHA) for fixture checks.\n' >&2; exit 1; }
command -v rg >/dev/null 2>&1 || { printf 'Install ripgrep (rg) for test source enumeration.\n' >&2; exit 1; }
TEST_JSON_JAR="$(bash "$ROOT/scripts/fetch-test-deps.sh")"
export TEST_JSON_JAR AVATAR_JSON_JAR="$TEST_JSON_JAR" CHECKIN_JSON_JAR="$TEST_JSON_JAR"
export TEST_WORK_DIR="${TEST_WORK_DIR:-$ROOT/.build/tests/${SUITE//\//-}}"
mkdir -p "$TEST_WORK_DIR"
if [[ "$TEST_JSON_JAR" != "$TEST_WORK_DIR/json-20240303.jar" ]]; then
  cp "$TEST_JSON_JAR" "$TEST_WORK_DIR/json-20240303.jar"
fi
cd "$ROOT"
exec bash "$RUNNER"
