#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_WORK_DIR="${TEST_WORK_DIR:-$PROJECT_ROOT/.build/credentialauth-tests}"
mkdir -p "$TEST_WORK_DIR/classes"
if [[ -z "${JAVA_HOME:-}" && -x /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/javac ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}}"
ANDROID_JAR="$SDK_ROOT/platforms/android-35/android.jar"
[[ -f "$ANDROID_JAR" ]] || { printf 'Android SDK Platform 35 is required.\n' >&2; exit 1; }
JSON_JAR="$TEST_WORK_DIR/json-20240303.jar"
JSON_SHA256=3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed
if [[ ! -f "$JSON_JAR" ]]; then
  CACHED_JAR="$PROJECT_ROOT/../../work/research/org-json-test-only.jar"
  if [[ -f "$CACHED_JAR" ]]; then cp "$CACHED_JAR" "$JSON_JAR";
  else curl -fL --max-time 60 https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar -o "$JSON_JAR"; fi
fi
[[ "$(shasum -a 256 "$JSON_JAR" | awk '{print $1}')" == "$JSON_SHA256" ]] || { printf 'Test-only JSON dependency checksum mismatch.\n' >&2; exit 1; }
cd "$PROJECT_ROOT"
(cd tests/credentialauth/fixtures && shasum -a 256 -c SHA256SUMS)
javac --release 8 -Xlint:all -Werror -encoding UTF-8 -classpath "$JSON_JAR:$ANDROID_JAR" \
  -d "$TEST_WORK_DIR/classes" app/src/main/java/cn/ngapure/app/NativeAuth.java \
  app/src/main/java/cn/ngapure/app/NativeCredentialAuth.java \
  app/src/main/java/cn/ngapure/app/NgaStore.java app/src/main/java/cn/ngapure/app/SessionScopeSnapshot.java app/src/main/java/cn/ngapure/app/ForumFavoriteSyncState.java app/src/main/java/cn/ngapure/app/LoginPreferences.java app/src/main/java/cn/ngapure/app/NativeQrRecoveryStore.java \
  app/src/main/java/cn/ngapure/app/NgaApi.java app/src/main/java/cn/ngapure/app/TopicPreviewImages.java app/src/main/java/cn/ngapure/app/PostRenderer.java app/src/main/java/cn/ngapure/app/ForumRoute.java app/src/main/java/cn/ngapure/app/BoardIconCatalog.java app/src/main/java/cn/ngapure/app/VoteMetrics.java app/src/main/java/cn/ngapure/app/AvatarSources.java app/src/main/java/cn/ngapure/app/RequestCancellation.java \
  app/src/main/java/cn/ngapure/app/FoldGeometry.java app/src/main/java/cn/ngapure/app/FoldAdaptiveLayout.java app/src/main/java/cn/ngapure/app/NativeDialogGeometry.java \
  app/src/main/java/cn/ngapure/app/NativeGlassDialog.java app/src/main/java/cn/ngapure/app/GlassPanel.java app/src/main/java/cn/ngapure/app/IconView.java app/src/main/java/cn/ngapure/app/Motion.java \
  app/src/main/java/cn/ngapure/app/NativeCaptchaDialog.java tests/credentialauth/CredentialAuthTest.java tests/credentialauth/AuthNetworkCancellationTest.java
java -classpath "$TEST_WORK_DIR/classes:$JSON_JAR:$ANDROID_JAR" cn.ngapure.app.CredentialAuthTest "$PROJECT_ROOT/tests/credentialauth/fixtures"
java -classpath "$TEST_WORK_DIR/classes:$JSON_JAR:$ANDROID_JAR" cn.ngapure.app.AuthNetworkCancellationTest
