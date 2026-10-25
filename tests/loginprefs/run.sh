#!/usr/bin/env bash
set -euo pipefail
TEST_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT="$(CDPATH= cd -- "$TEST_DIR/../.." && pwd)"
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
SDK="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"
BUILD="${TEST_WORK_DIR:-$PROJECT/.build/login-preference-tests}"
mkdir -p "$BUILD"
"$JDK/bin/javac" --release 8 -Xlint:all -Werror -encoding UTF-8 -cp "$SDK/platforms/android-35/android.jar" -d "$BUILD" "$PROJECT/app/src/main/java/cn/ngapure/app/LoginPreferences.java" "$TEST_DIR/LoginPreferencesTest.java"
"$JDK/bin/java" -cp "$BUILD:$SDK/platforms/android-35/android.jar" cn.ngapure.app.LoginPreferencesTest
