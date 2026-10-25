#!/usr/bin/env bash
set -euo pipefail
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
TEST_WORK_DIR="${TEST_WORK_DIR:-$ROOT/.build/direct-reaction-tests}"
mkdir -p "$TEST_WORK_DIR/classes"
if [[ -z "${JAVA_HOME:-}" && -x /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/javac ]]; then export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home; fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
javac --release 8 -Xlint:all -Werror -encoding UTF-8 -d "$TEST_WORK_DIR/classes" "$ROOT/app/src/main/java/cn/ngapure/app/NativeReactionController.java" "$ROOT/tests/direct_reactions/DirectReactionTest.java"
java -cp "$TEST_WORK_DIR/classes" cn.ngapure.app.DirectReactionTest
