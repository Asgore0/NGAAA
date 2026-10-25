#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
TEST_WORK_DIR="${TEST_WORK_DIR:-$PROJECT_ROOT/.build/screenreads-tests}"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
mkdir -p "$TEST_WORK_DIR/classes"
"$JAVA_HOME/bin/javac" --release 8 -Xlint:all -Werror -encoding UTF-8 -d "$TEST_WORK_DIR/classes" \
  "$PROJECT_ROOT/app/src/main/java/cn/ngapure/app/RequestCancellation.java" \
  "$PROJECT_ROOT/app/src/main/java/cn/ngapure/app/ScreenReadTasks.java" \
  "$PROJECT_ROOT/app/src/main/java/cn/ngapure/app/ScopedCache.java" \
  "$PROJECT_ROOT/tests/screenreads/ScreenReadTasksTest.java"
"$JAVA_HOME/bin/java" -cp "$TEST_WORK_DIR/classes" cn.ngapure.app.ScreenReadTasksTest
