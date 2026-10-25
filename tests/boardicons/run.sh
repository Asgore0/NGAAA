#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
TEST_WORK_DIR="${TEST_WORK_DIR:-$PROJECT_ROOT/.build/boardicon-tests}"
mkdir -p "$TEST_WORK_DIR/classes"
if [[ -z "${JAVA_HOME:-}" && -x /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/javac ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
python3 "$PROJECT_ROOT/tests/boardicons/verify-assets.py"
javac --release 8 -Xlint:all -Werror -encoding UTF-8 -d "$TEST_WORK_DIR/classes" \
  "$PROJECT_ROOT/app/src/main/java/cn/ngapure/app/BoardIconCatalog.java" "$PROJECT_ROOT/tests/boardicons/BoardIconCatalogTest.java"
java -cp "$TEST_WORK_DIR/classes" cn.ngapure.app.BoardIconCatalogTest "$PROJECT_ROOT/tests/boardicons/verified-icons.tsv"
