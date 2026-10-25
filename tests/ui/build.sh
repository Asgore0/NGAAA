#!/usr/bin/env bash
set -euo pipefail
TEST_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK_ROOT" ]]; then
  for candidate in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" /usr/lib/android-sdk; do
    if [[ -d "$candidate/platforms" ]]; then SDK_ROOT="$candidate"; break; fi
  done
fi
if [[ -z "$SDK_ROOT" ]]; then
  echo 'Set ANDROID_SDK_ROOT (or ANDROID_HOME) to an installed Android SDK.' >&2
  exit 1
fi
PLATFORM="$SDK_ROOT/platforms/android-${ANDROID_API_LEVEL:-35}"
BUILD_TOOLS="$SDK_ROOT/build-tools/${ANDROID_BUILD_TOOLS_VERSION:-35.0.0}"
TEST_BUILD="${UI_TEST_BUILD_DIR:-$TEST_DIR/.build}"
if [[ -n "${JAVA_HOME:-}" ]]; then
  JAVAC="$JAVA_HOME/bin/javac"
  JAR="$JAVA_HOME/bin/jar"
else
  JAVAC=javac
  JAR=jar
fi
for dependency in "$PLATFORM/android.jar" "$PLATFORM/uiautomator.jar" \
  "$PLATFORM/optional/android.test.base.jar" "$BUILD_TOOLS/d8"; do
  if [[ ! -f "$dependency" ]]; then echo "Missing SDK dependency: $dependency" >&2; exit 1; fi
done
command -v "$JAVAC" >/dev/null || { echo 'JDK17 javac is required; set JAVA_HOME.' >&2; exit 1; }
command -v "$JAR" >/dev/null || { echo 'JDK jar is required; set JAVA_HOME.' >&2; exit 1; }
mkdir -p "$TEST_BUILD/classes" "$TEST_BUILD/dex"
"$JAVAC" --release 8 -Xlint:all -Werror -encoding UTF-8 \
  -classpath "$PLATFORM/android.jar:$PLATFORM/uiautomator.jar:$PLATFORM/optional/android.test.base.jar" \
  -d "$TEST_BUILD/classes" "$TEST_DIR/src/cn/ngapure/gesture/LiquidGestureTest.java" "$TEST_DIR/src/cn/ngapure/gesture/NativeTextFixtureTest.java" "$TEST_DIR/src/cn/ngapure/gesture/EverydayTextInput.java"
"$JAR" cf "$TEST_BUILD/gesture-classes.jar" -C "$TEST_BUILD/classes" .
"$BUILD_TOOLS/d8" --release --min-api 26 \
  --lib "$PLATFORM/android.jar" --lib "$PLATFORM/uiautomator.jar" \
  --lib "$PLATFORM/optional/android.test.base.jar" \
  --output "$TEST_BUILD/dex" "$TEST_BUILD/gesture-classes.jar"
"$JAR" cf "$TEST_BUILD/gesture-driver.jar" -C "$TEST_BUILD/dex" classes.dex
if command -v sha256sum >/dev/null; then
  sha256sum "$TEST_BUILD/gesture-driver.jar"
else
  shasum -a 256 "$TEST_BUILD/gesture-driver.jar"
fi
"$JAR" tf "$TEST_BUILD/gesture-driver.jar"
printf 'Built UI test helper: %s\n' "$TEST_BUILD/gesture-driver.jar"
