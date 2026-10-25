#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
TEST_WORK_DIR="${TEST_WORK_DIR:-$PROJECT_ROOT/.build/board-visits-tests}"
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
SDK="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"
ANDROID="$SDK/platforms/android-35/android.jar"
JSON="${TEST_JSON_JAR:-$PROJECT_ROOT/../../work/research/org-json-test-only.jar}"
[[ -f "$ANDROID" && -f "$JSON" ]] || { echo 'Cached Android API35 and test JSON required; no dependencies are downloaded.' >&2; exit 1; }
[[ "$(shasum -a 256 "$JSON" | awk '{print $1}')" == 3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed ]] || { echo 'Test JSON checksum mismatch' >&2; exit 1; }
mkdir -p "$TEST_WORK_DIR/classes"
cd "$PROJECT_ROOT"
"$JDK/bin/javac" --release 8 -Xlint:all -Werror -encoding UTF-8 -cp "$JSON:$ANDROID" -d "$TEST_WORK_DIR/classes" \
  app/src/main/java/cn/ngapure/app/ForumRoute.java \
  app/src/main/java/cn/ngapure/app/AvatarSources.java \
  app/src/main/java/cn/ngapure/app/BoardVisits.java \
  tests/board_visits/BoardVisitsTest.java
"$JDK/bin/java" -cp "$TEST_WORK_DIR/classes:$JSON:$ANDROID" cn.ngapure.app.BoardVisitsTest
