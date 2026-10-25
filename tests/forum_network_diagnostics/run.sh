#!/usr/bin/env bash
set -euo pipefail
PROJECT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
BUILD="${TEST_WORK_DIR:-$PROJECT/.build/forum-network-diagnostics-tests}"
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
SDK="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"
JSON="${TEST_JSON_JAR:-$PROJECT/../../work/research/org-json-test-only.jar}"
ANDROID="$SDK/platforms/android-35/android.jar"
[[ -f "$JSON" && -f "$ANDROID" ]] || { echo 'Local verified JSON and Android API35 required; no downloads.' >&2; exit 1; }
[[ "$(shasum -a 256 "$JSON" | awk '{print $1}')" == 3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed ]] || { echo 'Test JSON checksum mismatch' >&2; exit 1; }
mkdir -p "$BUILD/classes"
cd "$PROJECT"
"$JDK/bin/javac" --release 8 -Xlint:all -Werror -encoding UTF-8 -cp "$JSON:$ANDROID" -d "$BUILD/classes" \
  app/src/main/java/cn/ngapure/app/NgaApi.java app/src/main/java/cn/ngapure/app/TopicPreviewImages.java \
  app/src/main/java/cn/ngapure/app/PostRenderer.java app/src/main/java/cn/ngapure/app/ForumRoute.java \
  app/src/main/java/cn/ngapure/app/BoardIconCatalog.java app/src/main/java/cn/ngapure/app/RequestCancellation.java \
  app/src/main/java/cn/ngapure/app/AvatarSources.java app/src/main/java/cn/ngapure/app/VoteMetrics.java \
  app/src/main/java/cn/ngapure/app/LoginPreferences.java tests/api/android/text/Html.java \
  tests/api/checkin/android/content/Context.java tests/api/checkin/cn/ngapure/app/TestContext.java \
  tests/api/checkin/cn/ngapure/app/NgaStore.java tests/forum_network_diagnostics/ForumNetworkDiagnosticsTest.java
"$JDK/bin/java" -cp "$BUILD/classes:$JSON:$ANDROID" cn.ngapure.app.ForumNetworkDiagnosticsTest
