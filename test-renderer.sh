#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -z "${JAVA_HOME:-}" && -x /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/javac ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi
TEST_BUILD="${TEST_WORK_DIR:-$PROJECT_ROOT/.build/renderer-tests}"
mkdir -p "$TEST_BUILD"
javac --release 8 -encoding UTF-8 -d "$TEST_BUILD" \
  "$PROJECT_ROOT/app/src/main/java/cn/ngapure/app/PostRenderer.java" \
  "$PROJECT_ROOT/tests/PostRendererTest.java"
java -cp "$TEST_BUILD" cn.ngapure.app.PostRendererTest
