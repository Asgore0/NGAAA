#!/usr/bin/env bash
set -euo pipefail
BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "$BASE/../.." && pwd)"
: "${TEST_WORK_DIR:?Set isolated test work directory}"
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
mkdir -p "$TEST_WORK_DIR/classes"
"$JDK/bin/javac" --release 8 -Xlint:all -Werror -encoding UTF-8 -d "$TEST_WORK_DIR/classes" \
  "$PROJECT/app/src/main/java/cn/ngapure/app/PostRenderer.java" \
  "$BASE/src/cn/ngapure/entityprobe/EntityCases.java" "$BASE/src/cn/ngapure/entityprobe/EntityRendererTest.java"
"$JDK/bin/java" -cp "$TEST_WORK_DIR/classes" cn.ngapure.entityprobe.EntityRendererTest
