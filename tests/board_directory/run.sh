#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
TEST_WORK_DIR="${TEST_WORK_DIR:-$PROJECT_ROOT/.build/board-directory-tests}"
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
mkdir -p "$TEST_WORK_DIR/classes"
cd "$PROJECT_ROOT"
"$JDK/bin/javac" --release 8 -Xlint:all -Werror -encoding UTF-8 -d "$TEST_WORK_DIR/classes" \
  app/src/main/java/cn/ngapure/app/ForumRoute.java \
  app/src/main/java/cn/ngapure/app/BoardDirectory.java \
  tests/board_directory/BoardDirectoryTest.java
"$JDK/bin/java" -cp "$TEST_WORK_DIR/classes" cn.ngapure.app.BoardDirectoryTest
