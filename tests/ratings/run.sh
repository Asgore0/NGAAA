#!/usr/bin/env bash
# Offline ratings contract checks. The JSON dependency is test-only and is not packaged in APK.
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_WORK_DIR="${TEST_WORK_DIR:-$PROJECT_ROOT/.build/ratings-tests}"
mkdir -p "$TEST_WORK_DIR/classes"

if [[ -z "${JAVA_HOME:-}" && -x /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/javac ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}}"
ANDROID_JAR="$SDK_ROOT/platforms/android-35/android.jar"
if [[ ! -f "$ANDROID_JAR" ]]; then
  printf 'Set ANDROID_SDK_ROOT to an Android SDK containing API 35.\n' >&2; exit 1
fi

JSON_JAR="$TEST_WORK_DIR/json-20240303.jar"
JSON_SHA256=3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed
if [[ ! -f "$JSON_JAR" ]]; then
  CACHED_JSON_JAR="$PROJECT_ROOT/.build/api-parser-tests/json-20240303.jar"
  if [[ -f "$CACHED_JSON_JAR" ]]; then cp "$CACHED_JSON_JAR" "$JSON_JAR"
  elif [[ -f "$PROJECT_ROOT/../../work/research/org-json-test-only.jar" ]]; then cp "$PROJECT_ROOT/../../work/research/org-json-test-only.jar" "$JSON_JAR"
  else curl -fL --max-time 60 https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar -o "$JSON_JAR"; fi
fi
ACTUAL_JSON_SHA256="$(shasum -a 256 "$JSON_JAR" | awk '{print $1}')"
if [[ "$ACTUAL_JSON_SHA256" != "$JSON_SHA256" ]]; then
  printf 'Test-only org.json dependency checksum mismatch.\n' >&2; exit 1
fi

cd "$PROJECT_ROOT"
javac -encoding UTF-8 --release 8 -classpath "$JSON_JAR:$ANDROID_JAR" \
  -sourcepath app/src/main/java -d "$TEST_WORK_DIR/classes" \
  app/src/main/java/cn/ngapure/app/NgaRatings.java tests/ratings/RatingsFixtureTest.java
java -classpath "$TEST_WORK_DIR/classes:$JSON_JAR:$ANDROID_JAR" cn.ngapure.app.RatingsFixtureTest
mkdir -p "$TEST_WORK_DIR/transport"
javac -encoding UTF-8 --release 8 -Xlint:all -Werror -classpath "$JSON_JAR:$ANDROID_JAR" \
  -d "$TEST_WORK_DIR/transport" tests/api/checkin/android/content/Context.java \
  tests/api/checkin/cn/ngapure/app/TestContext.java tests/api/checkin/cn/ngapure/app/NgaStore.java \
  app/src/main/java/cn/ngapure/app/LoginPreferences.java app/src/main/java/cn/ngapure/app/RequestCancellation.java \
  app/src/main/java/cn/ngapure/app/ScreenReadTasks.java app/src/main/java/cn/ngapure/app/NgaRatings.java \
  tests/ratings/RatingsTransportCancellationTest.java
java -classpath "$TEST_WORK_DIR/transport:$JSON_JAR:$ANDROID_JAR" cn.ngapure.app.RatingsTransportCancellationTest
