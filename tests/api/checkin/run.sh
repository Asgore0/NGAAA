#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)"
TEST_WORK_DIR="${TEST_WORK_DIR:-$PROJECT_ROOT/.build/checkin-account-tests}"
mkdir -p "$TEST_WORK_DIR/classes"
if [[ -z "${JAVA_HOME:-}" && -x /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/javac ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}}"
ANDROID_JAR="$SDK_ROOT/platforms/android-35/android.jar"
JSON_JAR="${CHECKIN_JSON_JAR:-$PROJECT_ROOT/../../work/research/org-json-test-only.jar}"
[[ -f "$JSON_JAR" && -f "$ANDROID_JAR" ]] || { echo 'Local test-only org.json and Android API35 are required; this script never downloads dependencies' >&2; exit 1; }
[[ "$(shasum -a 256 "$JSON_JAR" | awk '{print $1}')" == 3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed ]] || { echo 'Test JSON checksum mismatch' >&2; exit 1; }
cd "$PROJECT_ROOT"
(cd tests/api/checkin/fixtures && shasum -a 256 -c SHA256SUMS)
# The real API and account guard, then the test helpers, compile without warning exclusions.
javac --release 8 -Xlint:all -Werror -encoding UTF-8 -cp "$JSON_JAR:$ANDROID_JAR" \
  -d "$TEST_WORK_DIR/classes" tests/api/android/text/Html.java \
  tests/api/checkin/android/content/Context.java tests/api/checkin/cn/ngapure/app/TestContext.java \
  tests/api/checkin/cn/ngapure/app/NgaStore.java app/src/main/java/cn/ngapure/app/LoginPreferences.java \
  app/src/main/java/cn/ngapure/app/NgaApi.java app/src/main/java/cn/ngapure/app/TopicPreviewImages.java app/src/main/java/cn/ngapure/app/PostRenderer.java app/src/main/java/cn/ngapure/app/ForumRoute.java app/src/main/java/cn/ngapure/app/BoardIconCatalog.java app/src/main/java/cn/ngapure/app/RequestCancellation.java app/src/main/java/cn/ngapure/app/AvatarSources.java \
  app/src/main/java/cn/ngapure/app/VoteMetrics.java
javac --release 8 -Xlint:all -Werror -encoding UTF-8 -cp "$TEST_WORK_DIR/classes:$JSON_JAR:$ANDROID_JAR" \
  -d "$TEST_WORK_DIR/classes" app/src/main/java/cn/ngapure/app/LoginPreferences.java \
  tests/api/checkin/android/content/Context.java tests/api/checkin/cn/ngapure/app/TestContext.java \
  tests/api/checkin/cn/ngapure/app/NgaStore.java tests/api/checkin/cn/ngapure/app/CheckInAccountRaceTest.java
java -cp "$TEST_WORK_DIR/classes:$JSON_JAR:$ANDROID_JAR" cn.ngapure.app.CheckInAccountRaceTest tests/api/checkin/fixtures
