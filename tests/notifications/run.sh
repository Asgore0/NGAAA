#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
TEST_WORK_DIR="${TEST_WORK_DIR:-$PROJECT_ROOT/.build/notifications-tests}"
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
SDK="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"
ANDROID="$SDK/platforms/android-35/android.jar"
JSON="${TEST_JSON_JAR:-$PROJECT_ROOT/../../work/research/org-json-test-only.jar}"
[[ -f "$ANDROID" && -f "$JSON" ]] || { echo 'Cached Android API35 and test JSON required; no dependency is downloaded.' >&2;exit 1; }
[[ "$(shasum -a 256 "$JSON" | awk '{print $1}')" == 3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed ]] || { echo 'Test JSON checksum mismatch' >&2;exit 1; }
mkdir -p "$TEST_WORK_DIR/policy" "$TEST_WORK_DIR/adapter" "$TEST_WORK_DIR/service"
cd "$PROJECT_ROOT"
"$JDK/bin/javac" --release 8 -Xlint:all -Werror -encoding UTF-8 -d "$TEST_WORK_DIR/policy" app/src/main/java/cn/ngapure/app/UnreadNotificationPolicy.java tests/notifications/UnreadNotificationPolicyTest.java
"$JDK/bin/java" -cp "$TEST_WORK_DIR/policy" cn.ngapure.app.UnreadNotificationPolicyTest
# Compile the real API adapter, policy and service. Activity/resource markers are never instantiated.
"$JDK/bin/javac" --release 8 -Xlint:all -Werror -encoding UTF-8 -cp "$ANDROID:$JSON" -sourcepath app/src/main/java \
  -d "$TEST_WORK_DIR/adapter" tests/notifications/support/cn/ngapure/app/MainActivity.java tests/notifications/support/cn/ngapure/app/R.java \
  app/src/main/java/cn/ngapure/app/NativeCommunity.java app/src/main/java/cn/ngapure/app/NativeNotifications.java \
  app/src/main/java/cn/ngapure/app/UnreadNotificationPolicy.java app/src/main/java/cn/ngapure/app/UnreadNotificationService.java tests/notifications/UnreadSnapshotTest.java tests/notifications/UnreadBaselineCommitFailureTest.java
"$JDK/bin/java" -cp "$TEST_WORK_DIR/adapter:$JSON:$ANDROID" cn.ngapure.app.UnreadSnapshotTest
"$JDK/bin/java" -cp "$TEST_WORK_DIR/adapter:$JSON:$ANDROID" cn.ngapure.app.UnreadBaselineCommitFailureTest
# Exercise the real service and compiled real read transport. Framework callbacks and
# notification delivery are synthetic; a URL handler supplies in-memory HTTPS only.
"$JDK/bin/javac" --release 8 -Xlint:all -Werror -encoding UTF-8 -cp "$TEST_WORK_DIR/adapter:$JSON:$ANDROID" \
  -d "$TEST_WORK_DIR/service" \
  tests/notifications/service/android/content/Context.java \
  tests/notifications/service/android/app/job/JobParameters.java tests/notifications/service/android/app/job/JobService.java \
  tests/notifications/service/android/os/Looper.java tests/notifications/service/android/os/Handler.java \
  tests/notifications/service/cn/ngapure/app/NativeNotifications.java \
  app/src/main/java/cn/ngapure/app/RequestCancellation.java app/src/main/java/cn/ngapure/app/UnreadNotificationService.java \
  tests/notifications/service/cn/ngapure/app/UnreadServiceLifecycleTest.java
"$JDK/bin/java" -cp "$TEST_WORK_DIR/service:$TEST_WORK_DIR/adapter:$JSON:$ANDROID" cn.ngapure.app.UnreadServiceLifecycleTest
