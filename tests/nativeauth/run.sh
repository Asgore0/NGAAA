#!/usr/bin/env bash
# Test-only JSON dependency; no network requests or account/session access in these checks.
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_WORK_DIR="${TEST_WORK_DIR:-$PROJECT_ROOT/.build/nativeauth-tests}"
mkdir -p "$TEST_WORK_DIR/classes"
if [[ -z "${JAVA_HOME:-}" && -x /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/javac ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}}"
ANDROID_JAR="$SDK_ROOT/platforms/android-35/android.jar"
if [[ ! -f "$ANDROID_JAR" ]]; then printf 'Android SDK Platform 35 android.jar is required.\n' >&2; exit 1; fi
JSON_JAR="$TEST_WORK_DIR/json-20240303.jar"
JSON_SHA256=3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed
if [[ ! -f "$JSON_JAR" ]]; then
  CACHED_JAR="$PROJECT_ROOT/../../work/research/org-json-test-only.jar"
  if [[ -f "$CACHED_JAR" ]]; then cp "$CACHED_JAR" "$JSON_JAR";
  else curl -fL --max-time 60 https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar -o "$JSON_JAR"; fi
fi
if [[ "$(shasum -a 256 "$JSON_JAR" | awk '{print $1}')" != "$JSON_SHA256" ]]; then
  printf 'Test-only org.json checksum mismatch.\n' >&2; exit 1
fi
cd "$PROJECT_ROOT"
(cd tests/nativeauth/fixtures && shasum -a 256 -c SHA256SUMS)
javac --release 8 -Xlint:all -Werror -encoding UTF-8 -classpath "$JSON_JAR:$ANDROID_JAR" \
  -d "$TEST_WORK_DIR/classes" app/src/main/java/cn/ngapure/app/NativeAuth.java app/src/main/java/cn/ngapure/app/RequestCancellation.java \
  app/src/main/java/cn/ngapure/app/NativeQrRecoveryStore.java \
  tests/nativeauth/NativeAuthFixtureTest.java tests/nativeauth/NativeQrRecoveryStoreTest.java
java -classpath "$TEST_WORK_DIR/classes:$JSON_JAR:$ANDROID_JAR" cn.ngapure.app.NativeAuthFixtureTest "$PROJECT_ROOT/tests/nativeauth/fixtures"
java -classpath "$TEST_WORK_DIR/classes:$JSON_JAR:$ANDROID_JAR" cn.ngapure.app.NativeQrRecoveryStoreTest
