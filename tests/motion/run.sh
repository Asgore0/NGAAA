#!/usr/bin/env bash
set -euo pipefail
TEST_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_DIR="$(CDPATH= cd -- "$TEST_DIR/../.." && pwd)"
BUILD_DIR="${TEST_WORK_DIR:-${MOTION_TEST_BUILD_DIR:-$TEST_DIR/.build}}"
if [[ -n "${JAVA_HOME:-}" ]]; then JAVAC="$JAVA_HOME/bin/javac"; JAVA="$JAVA_HOME/bin/java"; else JAVAC=javac; JAVA=java; fi
mkdir -p "$BUILD_DIR"
"$JAVAC" --release 8 -Xlint:all -Werror -encoding UTF-8 -d "$BUILD_DIR" \
  "$PROJECT_DIR/app/src/main/java/cn/ngapure/app/LiquidMotion.java" "$TEST_DIR/MotionContinuityTest.java" \
  "$TEST_DIR/MotionResponsivenessTest.java"
"$JAVA" -cp "$BUILD_DIR" cn.ngapure.app.MotionContinuityTest
"$JAVA" -cp "$BUILD_DIR" cn.ngapure.app.MotionResponsivenessTest
