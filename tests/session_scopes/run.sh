#!/usr/bin/env bash
set -euo pipefail
PROJECT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
BUILD="${TEST_WORK_DIR:-$PROJECT/.build/session-scope-tests}"
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
mkdir -p "$BUILD/classes"
cd "$PROJECT"
"$JDK/bin/javac" --release 8 -Xlint:all -Werror -encoding UTF-8 -d "$BUILD/classes" \
  app/src/main/java/cn/ngapure/app/SessionScopeSnapshot.java tests/session_scopes/SessionScopeSnapshotTest.java
"$JDK/bin/java" -cp "$BUILD/classes" cn.ngapure.app.SessionScopeSnapshotTest
