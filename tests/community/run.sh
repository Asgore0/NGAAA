#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
TEST_WORK_DIR="${TEST_WORK_DIR:-$PROJECT_ROOT/.build/community-tests}"
mkdir -p "$TEST_WORK_DIR/classes"
if [[ -z "${JAVA_HOME:-}" && -x /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/javac ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}}"
ANDROID_JAR="$SDK_ROOT/platforms/android-35/android.jar"
JSON_JAR="$TEST_WORK_DIR/json-20240303.jar"
if [[ ! -f "$JSON_JAR" ]]; then
  CACHED_JAR="$PROJECT_ROOT/../../work/research/org-json-test-only.jar"
  if [[ -f "$CACHED_JAR" ]]; then cp "$CACHED_JAR" "$JSON_JAR";
  else curl -fL --max-time 60 https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar -o "$JSON_JAR"; fi
fi
JSON_SHA=3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed
[[ "$(shasum -a 256 "$JSON_JAR" | awk '{print $1}')" == "$JSON_SHA" ]] || { echo 'Test JSON checksum mismatch' >&2; exit 1; }
[[ -f "$ANDROID_JAR" ]] || { echo 'API 35 SDK required' >&2; exit 1; }
cd "$PROJECT_ROOT"
(cd tests/community/fixtures && shasum -a 256 -c SHA256SUMS)
# Compile the real shared dependencies with the same strict Java 8 checks.
javac --release 8 -Xlint:all -Werror -encoding UTF-8 -cp "$JSON_JAR:$ANDROID_JAR" \
  -d "$TEST_WORK_DIR/classes" tests/api/android/text/Html.java \
  app/src/main/java/cn/ngapure/app/AvatarSources.java app/src/main/java/cn/ngapure/app/VoteMetrics.java \
  app/src/main/java/cn/ngapure/app/NgaApi.java app/src/main/java/cn/ngapure/app/TopicPreviewImages.java app/src/main/java/cn/ngapure/app/PostRenderer.java app/src/main/java/cn/ngapure/app/ForumRoute.java app/src/main/java/cn/ngapure/app/BoardIconCatalog.java app/src/main/java/cn/ngapure/app/RequestCancellation.java app/src/main/java/cn/ngapure/app/NgaStore.java app/src/main/java/cn/ngapure/app/SessionScopeSnapshot.java app/src/main/java/cn/ngapure/app/ForumFavoriteSyncState.java \
  app/src/main/java/cn/ngapure/app/LoginPreferences.java app/src/main/java/cn/ngapure/app/NativeQrRecoveryStore.java \
  app/src/main/java/cn/ngapure/app/NativeAuth.java
# New network layer and fixture driver are strictly warning-free independently.
javac --release 8 -Xlint:all -Werror -encoding UTF-8 -cp "$TEST_WORK_DIR/classes:$JSON_JAR:$ANDROID_JAR" \
  -d "$TEST_WORK_DIR/classes" app/src/main/java/cn/ngapure/app/NativeCommunity.java \
  app/src/main/java/cn/ngapure/app/NativeUserActions.java app/src/main/java/cn/ngapure/app/NativeUserBlockStore.java \
  app/src/main/java/cn/ngapure/app/NativeCloudBlocks.java app/src/main/java/cn/ngapure/app/NativeAvatarUpload.java \
  app/src/main/java/cn/ngapure/app/CommunityWritePolicy.java app/src/main/java/cn/ngapure/app/NativeMessageDrafts.java \
  app/src/main/java/cn/ngapure/app/CommunityMessageDelivery.java \
  tests/community/NativeCommunityTest.java tests/community/CommunityTransportCancellationTest.java tests/community/CommunityWritePolicyTest.java tests/community/NativeMessageDraftsTest.java \
  tests/community/NativeMessageDraftsCommitFailureTest.java \
  tests/community/CommunityMessageDeliveryTest.java
# Compile the real private-message view and rich-text/media components; no test substitutes.
javac --release 8 -Xlint:all -Werror -encoding UTF-8 -cp "$ANDROID_JAR:$TEST_WORK_DIR/classes:$JSON_JAR" \
  -d "$TEST_WORK_DIR/classes" app/src/main/java/cn/ngapure/app/PostRenderer.java \
  app/src/main/java/cn/ngapure/app/NativeLinkTarget.java app/src/main/java/cn/ngapure/app/BrowserLinks.java \
  app/src/main/java/cn/ngapure/app/NativeGlassDialog.java app/src/main/java/cn/ngapure/app/GlassPanel.java app/src/main/java/cn/ngapure/app/IconView.java app/src/main/java/cn/ngapure/app/Motion.java \
  app/src/main/java/cn/ngapure/app/NativeAvatarResolver.java app/src/main/java/cn/ngapure/app/NativeImages.java app/src/main/java/cn/ngapure/app/NativeMediaCache.java app/src/main/java/cn/ngapure/app/FlowTextWhitespace.java app/src/main/java/cn/ngapure/app/NativeRichText.java app/src/main/java/cn/ngapure/app/AvatarView.java \
  app/src/main/java/cn/ngapure/app/NativeTableModel.java app/src/main/java/cn/ngapure/app/NativeMediaPolicy.java \
  app/src/main/java/cn/ngapure/app/NativeMediaPlayer.java app/src/main/java/cn/ngapure/app/NativeEncryptedContent.java \
  app/src/main/java/cn/ngapure/app/FoldGeometry.java app/src/main/java/cn/ngapure/app/FoldAdaptiveLayout.java app/src/main/java/cn/ngapure/app/NativeDialogGeometry.java \
  app/src/main/java/cn/ngapure/app/NativeUserActions.java app/src/main/java/cn/ngapure/app/NativeUserBlockStore.java \
  app/src/main/java/cn/ngapure/app/NativeCloudBlocks.java app/src/main/java/cn/ngapure/app/NativeAvatarUpload.java \
  app/src/main/java/cn/ngapure/app/NativeFollowingView.java app/src/main/java/cn/ngapure/app/NativeActivitiesView.java app/src/main/java/cn/ngapure/app/NativeVoteBar.java app/src/main/java/cn/ngapure/app/NativeCommunityView.java tests/community/CommunityLinkTest.java tests/community/CommunityPresentationTest.java
java -cp "$TEST_WORK_DIR/classes:$JSON_JAR:$ANDROID_JAR" cn.ngapure.app.NativeCommunityTest tests/community/fixtures
java -cp "$TEST_WORK_DIR/classes:$JSON_JAR:$ANDROID_JAR" cn.ngapure.app.CommunityTransportCancellationTest
java -cp "$TEST_WORK_DIR/classes" cn.ngapure.app.CommunityWritePolicyTest
java -cp "$TEST_WORK_DIR/classes:$ANDROID_JAR" cn.ngapure.app.CommunityLinkTest
java -cp "$TEST_WORK_DIR/classes:$JSON_JAR:$ANDROID_JAR" cn.ngapure.app.CommunityPresentationTest
java -cp "$TEST_WORK_DIR/classes:$JSON_JAR:$ANDROID_JAR" cn.ngapure.app.NativeMessageDraftsTest
java -cp "$TEST_WORK_DIR/classes:$JSON_JAR:$ANDROID_JAR" cn.ngapure.app.NativeMessageDraftsCommitFailureTest
java -cp "$TEST_WORK_DIR/classes:$JSON_JAR:$ANDROID_JAR" cn.ngapure.app.CommunityMessageDeliveryTest
