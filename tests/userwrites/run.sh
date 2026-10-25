#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
: "${TEST_WORK_DIR:?Set an absolute isolated host test directory}"
[[ "$TEST_WORK_DIR" == /* && "$TEST_WORK_DIR" != / && "$TEST_WORK_DIR" != "$PROJECT_ROOT" ]] || { echo 'Absolute isolated host directory required' >&2; exit 1; }
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
mkdir -p "$TEST_WORK_DIR/classes"
"$JDK/bin/javac" --release 8 -Xlint:all -Werror -encoding UTF-8 -d "$TEST_WORK_DIR/classes" \
 "$PROJECT_ROOT/app/src/main/java/cn/ngapure/app/NativeUserWrites.java" "$PROJECT_ROOT/tests/userwrites/NativeUserWritesTest.java"
"$JDK/bin/java" -cp "$TEST_WORK_DIR/classes" cn.ngapure.app.NativeUserWritesTest
