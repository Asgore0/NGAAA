#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_WORK_DIR="${TEST_WORK_DIR:-$PROJECT_ROOT/.build/posting-fixture-tests}"
mkdir -p "$TEST_WORK_DIR/classes"
if [[ -z "${JAVA_HOME:-}" && -x /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/javac ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
JSON_JAR="${TEST_JSON_JAR:-$PROJECT_ROOT/../../work/research/org-json-test-only.jar}"
if [[ ! -f "$JSON_JAR" ]]; then
  printf 'Missing test-only org.json 20240303 jar; see tests/api/run.sh. No network is used by these posting tests.\n' >&2; exit 1
fi
EXPECTED=3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed
ACTUAL="$(shasum -a 256 "$JSON_JAR" | awk '{print $1}')"
[[ "$ACTUAL" == "$EXPECTED" ]] || { printf 'Test-only JSON dependency checksum mismatch.\n' >&2; exit 1; }
cd "$PROJECT_ROOT"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}}"
ANDROID_JAR="$SDK_ROOT/platforms/android-35/android.jar"
[[ -f "$ANDROID_JAR" ]] || { printf 'Android API 35 jar is required for the SharedPreferences interface.\n' >&2; exit 1; }
javac -encoding UTF-8 -source 8 -target 8 -Xlint:all,-options -Werror -classpath "$JSON_JAR:$ANDROID_JAR" \
  -d "$TEST_WORK_DIR/classes" tests/posting/NgaStore.java \
  app/src/main/java/cn/ngapure/app/NativePosting.java app/src/main/java/cn/ngapure/app/NativeDrafts.java app/src/main/java/cn/ngapure/app/NativeTopicRating.java app/src/main/java/cn/ngapure/app/RequestCancellation.java \
  tests/posting/PostingFixtureTest.java tests/posting/DraftFixtureTest.java tests/posting/DraftCommitFailureTest.java tests/posting/TopicRatingFixtureTest.java tests/posting/PostingCancellationTest.java
java -classpath "$TEST_WORK_DIR/classes:$JSON_JAR:$ANDROID_JAR" cn.ngapure.app.PostingFixtureTest
java -classpath "$TEST_WORK_DIR/classes:$JSON_JAR:$ANDROID_JAR" cn.ngapure.app.DraftFixtureTest
java -classpath "$TEST_WORK_DIR/classes:$JSON_JAR:$ANDROID_JAR" cn.ngapure.app.DraftCommitFailureTest
java -classpath "$TEST_WORK_DIR/classes:$JSON_JAR:$ANDROID_JAR" cn.ngapure.app.TopicRatingFixtureTest
java -classpath "$TEST_WORK_DIR/classes:$JSON_JAR:$ANDROID_JAR" cn.ngapure.app.PostingCancellationTest
