#!/usr/bin/env bash
set -euo pipefail
PROJECT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
BUILD="${TEST_WORK_DIR:-$PROJECT/.build/reliability-tests}"
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
SDK="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"
JSON="${TEST_JSON_JAR:-${CHECKIN_JSON_JAR:-}}"
if [[ -z "$JSON" ]]; then
  for candidate in "$PROJECT/.build/api-parser-tests/json-20240303.jar" "$PROJECT/../../work/research/org-json-test-only.jar"; do
    if [[ -f "$candidate" ]]; then JSON="$candidate"; break; fi
  done
fi
ANDROID="$SDK/platforms/android-35/android.jar"
[[ -f "$JSON" && -f "$ANDROID" ]] || { echo 'Local test JSON and Android API35 required; no dependencies are downloaded.' >&2; exit 1; }
[[ "$(shasum -a 256 "$JSON" | awk '{print $1}')" == 3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed ]] || { echo 'Test JSON checksum mismatch' >&2; exit 1; }
mkdir -p "$BUILD/transport" "$BUILD/store"
cd "$PROJECT"
COMMON=(app/src/main/java/cn/ngapure/app/NgaApi.java app/src/main/java/cn/ngapure/app/TopicPreviewImages.java app/src/main/java/cn/ngapure/app/PostRenderer.java app/src/main/java/cn/ngapure/app/ForumRoute.java app/src/main/java/cn/ngapure/app/BoardIconCatalog.java app/src/main/java/cn/ngapure/app/RequestCancellation.java app/src/main/java/cn/ngapure/app/AvatarSources.java app/src/main/java/cn/ngapure/app/VoteMetrics.java app/src/main/java/cn/ngapure/app/LoginPreferences.java tests/api/android/text/Html.java)
"$JDK/bin/javac" --release 8 -Xlint:all -Werror -encoding UTF-8 -cp "$JSON:$ANDROID" -d "$BUILD/transport" \
  "${COMMON[@]}" tests/api/checkin/android/content/Context.java tests/api/checkin/cn/ngapure/app/TestContext.java \
  tests/api/checkin/cn/ngapure/app/NgaStore.java tests/reliability/cn/ngapure/app/TransportLifecycleTest.java tests/reliability/cn/ngapure/app/RequestCancellationTest.java
"$JDK/bin/java" -cp "$BUILD/transport:$JSON:$ANDROID" cn.ngapure.app.TransportLifecycleTest
"$JDK/bin/java" -cp "$BUILD/transport:$JSON:$ANDROID" cn.ngapure.app.RequestCancellationTest
"$JDK/bin/javac" --release 8 -Xlint:all -Werror -encoding UTF-8 -cp "$JSON:$ANDROID" -d "$BUILD/store" \
  "${COMMON[@]}" app/src/main/java/cn/ngapure/app/NgaStore.java app/src/main/java/cn/ngapure/app/SessionScopeSnapshot.java app/src/main/java/cn/ngapure/app/ForumFavoriteSyncState.java app/src/main/java/cn/ngapure/app/NativeAuth.java \
  app/src/main/java/cn/ngapure/app/NativeQrRecoveryStore.java tests/reliability/android/content/Context.java \
  tests/reliability/cn/ngapure/app/LocalTopicStoreTest.java
"$JDK/bin/java" -cp "$BUILD/store:$JSON:$ANDROID" cn.ngapure.app.LocalTopicStoreTest
