#!/bin/bash
set -euo pipefail
PROJECT_ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
TEST_WORK_DIR="${TEST_WORK_DIR:-$PROJECT_ROOT/.build/user-action-tests}"
mkdir -p "$TEST_WORK_DIR/classes"
if [[ -z "${JAVA_HOME:-}" && -x /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/javac ]]; then export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home; fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}}"
ANDROID_JAR="$SDK_ROOT/platforms/android-35/android.jar"
JSON_JAR="${TEST_JSON_JAR:-$PROJECT_ROOT/../../work/research/org-json-test-only.jar}"
[[ "$(shasum -a 256 "$JSON_JAR" | awk '{print $1}')" == 3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed ]] || { echo 'Test JSON checksum mismatch' >&2; exit 1; }
cd "$PROJECT_ROOT"
javac --release 8 -Xlint:all -Werror -encoding UTF-8 -cp "$JSON_JAR:$ANDROID_JAR" -d "$TEST_WORK_DIR/classes" \
 tests/api/android/text/Html.java tests/useractions/android/content/Context.java app/src/main/java/cn/ngapure/app/AvatarSources.java app/src/main/java/cn/ngapure/app/VoteMetrics.java \
 app/src/main/java/cn/ngapure/app/NgaApi.java app/src/main/java/cn/ngapure/app/TopicPreviewImages.java app/src/main/java/cn/ngapure/app/PostRenderer.java app/src/main/java/cn/ngapure/app/ForumRoute.java app/src/main/java/cn/ngapure/app/BoardIconCatalog.java \
 app/src/main/java/cn/ngapure/app/RequestCancellation.java app/src/main/java/cn/ngapure/app/NgaStore.java app/src/main/java/cn/ngapure/app/SessionScopeSnapshot.java app/src/main/java/cn/ngapure/app/ForumFavoriteSyncState.java \
 app/src/main/java/cn/ngapure/app/LoginPreferences.java app/src/main/java/cn/ngapure/app/NativeQrRecoveryStore.java app/src/main/java/cn/ngapure/app/NativeAuth.java \
 app/src/main/java/cn/ngapure/app/NativeCommunity.java app/src/main/java/cn/ngapure/app/NativeUserActions.java app/src/main/java/cn/ngapure/app/NativeUserBlockStore.java \
 app/src/main/java/cn/ngapure/app/NativeCloudBlocks.java app/src/main/java/cn/ngapure/app/NativeAvatarUpload.java tests/useractions/NativeUserActionsTest.java tests/useractions/NativeUserBlocksTest.java tests/useractions/NativeUserFilterTest.java
java -cp "$TEST_WORK_DIR/classes:$JSON_JAR:$ANDROID_JAR" cn.ngapure.app.NativeUserActionsTest
java -cp "$TEST_WORK_DIR/classes:$JSON_JAR:$ANDROID_JAR" cn.ngapure.app.NativeUserBlocksTest
java -cp "$TEST_WORK_DIR/classes:$JSON_JAR:$ANDROID_JAR" cn.ngapure.app.NativeUserFilterTest
