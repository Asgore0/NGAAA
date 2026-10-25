#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
TEST_WORK_DIR="${TEST_WORK_DIR:-$PROJECT_ROOT/.build/votes-state-tests}"
mkdir -p "$TEST_WORK_DIR/classes"
if [[ -z "${JAVA_HOME:-}" && -x /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/javac ]]; then export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home; fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
JSON_JAR="$(bash "$PROJECT_ROOT/scripts/fetch-test-deps.sh")"
cd "$PROJECT_ROOT"
javac --release 8 -Xlint:all -Werror -encoding UTF-8 -cp "$JSON_JAR" -d "$TEST_WORK_DIR/classes" app/src/main/java/cn/ngapure/app/VoteMetrics.java tests/votes_state/VoteStateTest.java
java -cp "$TEST_WORK_DIR/classes:$JSON_JAR" cn.ngapure.app.VoteStateTest
