#!/usr/bin/env bash
# Pure parser/sizing checks; does not create Android views, use network or access a device.
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_WORK_DIR="${TEST_WORK_DIR:-$PROJECT_ROOT/.build/nativecontent-tests}"
mkdir -p "$TEST_WORK_DIR/classes"
if [[ -z "${JAVA_HOME:-}" && -x /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/javac ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}}"
ANDROID_JAR="$SDK_ROOT/platforms/android-35/android.jar"
if [[ ! -f "$ANDROID_JAR" ]]; then printf 'Set ANDROID_SDK_ROOT to an SDK with API35.\n' >&2; exit 1; fi
cd "$PROJECT_ROOT"
(cd tests/nativecontent/fixtures && shasum -a 256 -c SHA256SUMS)
javac --release 8 -Xlint:all,-auxiliaryclass -Werror -encoding UTF-8 -classpath "$ANDROID_JAR" \
  -d "$TEST_WORK_DIR/classes" app/src/main/java/cn/ngapure/app/PostRenderer.java \
  app/src/main/java/cn/ngapure/app/AppPalette.java \
  app/src/main/java/cn/ngapure/app/RequestCancellation.java app/src/main/java/cn/ngapure/app/AvatarSources.java \
  app/src/main/java/cn/ngapure/app/NativeAvatarResolver.java app/src/main/java/cn/ngapure/app/NativeMediaCache.java app/src/main/java/cn/ngapure/app/NativeImages.java app/src/main/java/cn/ngapure/app/NativePictureView.java \
  app/src/main/java/cn/ngapure/app/NativeMediaPolicy.java app/src/main/java/cn/ngapure/app/NativeMediaPlayer.java \
  app/src/main/java/cn/ngapure/app/NativeEncryptedContent.java app/src/main/java/cn/ngapure/app/NativeTableModel.java \
  app/src/main/java/cn/ngapure/app/FoldGeometry.java app/src/main/java/cn/ngapure/app/FoldAdaptiveLayout.java app/src/main/java/cn/ngapure/app/NativeDialogGeometry.java \
  app/src/main/java/cn/ngapure/app/FlowTextWhitespace.java app/src/main/java/cn/ngapure/app/NativeRichText.java tests/nativecontent/NativeContentTest.java tests/nativecontent/NativeMediaContentTest.java
java -classpath "$TEST_WORK_DIR/classes:$ANDROID_JAR" cn.ngapure.app.NativeContentTest "$PROJECT_ROOT/tests/nativecontent/fixtures"

java -classpath "$TEST_WORK_DIR/classes:$ANDROID_JAR" cn.ngapure.app.NativeMediaContentTest
