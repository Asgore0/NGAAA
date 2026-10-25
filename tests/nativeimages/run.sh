#!/usr/bin/env bash
# Real media loader concurrency; all Android mechanics and HTTPS responses are isolated fixtures.
set -euo pipefail
PROJECT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
BUILD="${TEST_WORK_DIR:-$PROJECT/.build/nativeimages-tests}"
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
SDK="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"
ANDROID="$SDK/platforms/android-35/android.jar"
JSON="${TEST_JSON_JAR:-$PROJECT/../../work/research/org-json-test-only.jar}"
[[ -f "$ANDROID" && -f "$JSON" ]] || { echo 'Local Android API35 and test JSON are required; no dependencies are downloaded.' >&2; exit 1; }
[[ "$(shasum -a 256 "$JSON" | awk '{print $1}')" == 3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed ]] || { echo 'Test JSON checksum mismatch' >&2; exit 1; }
mkdir -p "$BUILD/classes"
STUBS=()
while IFS= read -r file; do STUBS+=("$file"); done < <(rg --files "$PROJECT/tests/nativeimages/stubs" -g '*.java' | sort)
"$JDK/bin/javac" --release 8 -Xlint:all -Werror -encoding UTF-8 -cp "$ANDROID:$JSON" -d "$BUILD/classes" \
  "${STUBS[@]}" "$PROJECT/app/src/main/java/cn/ngapure/app/RequestCancellation.java" \
  "$PROJECT/app/src/main/java/cn/ngapure/app/AvatarSources.java" "$PROJECT/app/src/main/java/cn/ngapure/app/NativeAvatarResolver.java" \
  "$PROJECT/app/src/main/java/cn/ngapure/app/NativeMediaCache.java" "$PROJECT/app/src/main/java/cn/ngapure/app/NativeImages.java" "$PROJECT/tests/nativeimages/InlineMediaSharedTest.java" "$PROJECT/tests/nativeimages/NativeMediaCacheTest.java" "$PROJECT/tests/nativeimages/NativeImageReadCancellationTest.java" "$PROJECT/tests/nativeimages/NativeSourceDimensionsTest.java" "$PROJECT/tests/nativeimages/DecodeBackpressureTest.java" "$PROJECT/tests/nativeimages/NativeImageCacheAdmissionTest.java" "$PROJECT/tests/nativeimages/NativeMediaPayloadTest.java"
"$JDK/bin/java" -cp "$BUILD/classes:$JSON:$ANDROID" cn.ngapure.app.InlineMediaSharedTest

"$JDK/bin/java" -cp "$BUILD/classes:$JSON:$ANDROID" cn.ngapure.app.NativeMediaCacheTest

"$JDK/bin/java" -cp "$BUILD/classes:$JSON:$ANDROID" cn.ngapure.app.NativeImageReadCancellationTest true

"$JDK/bin/java" -cp "$BUILD/classes:$JSON:$ANDROID" cn.ngapure.app.NativeSourceDimensionsTest

"$JDK/bin/java" -cp "$BUILD/classes:$JSON:$ANDROID" cn.ngapure.app.DecodeBackpressureTest true

"$JDK/bin/java" -cp "$BUILD/classes:$JSON:$ANDROID" cn.ngapure.app.NativeImageCacheAdmissionTest

"$JDK/bin/java" -cp "$BUILD/classes:$JSON:$ANDROID" cn.ngapure.app.NativeMediaPayloadTest
