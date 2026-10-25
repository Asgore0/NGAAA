#!/usr/bin/env bash
set -euo pipefail
PROJECT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
BUILD="${TEST_WORK_DIR:-$PROJECT/.build/startup-task-tests}"
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
SDK="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"
ANDROID="$SDK/platforms/android-35/android.jar"
mkdir -p "$BUILD/classes"
cd "$PROJECT"
sources=()
while IFS= read -r source; do sources+=("$source"); done < <(rg --files tests/startup_tasks -g '*.java' | LC_ALL=C sort)
"$JDK/bin/javac" --release 8 -Xlint:all -Werror -encoding UTF-8 -cp "$ANDROID" -d "$BUILD/classes" \
 "${sources[@]}" tests/checkin_service/android/content/ComponentName.java tests/checkin_service/android/app/job/JobService.java tests/checkin_service/android/app/job/JobParameters.java \
 tests/notifications/support/cn/ngapure/app/MainActivity.java tests/notifications/support/cn/ngapure/app/R.java \
 app/src/main/java/cn/ngapure/app/StartupTaskQueue.java app/src/main/java/cn/ngapure/app/CheckInService.java app/src/main/java/cn/ngapure/app/NativeNotifications.java app/src/main/java/cn/ngapure/app/UnreadNotificationPolicy.java
"$JDK/bin/java" -cp "$BUILD/classes:$ANDROID" cn.ngapure.app.StartupTasksTest
"$JDK/bin/java" -cp "$BUILD/classes:$ANDROID" cn.ngapure.app.NotificationFastPathTest
