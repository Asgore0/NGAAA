#!/usr/bin/env bash
set -euo pipefail
HELPER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "$HELPER_DIR/../.." && pwd)"
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"
SDK="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"
BT="$SDK/build-tools/35.0.0"
BUILD="${IMAGE_SAVE_BUILD_DIR:?Set an absolute isolated helper build directory}"
PRODUCT_JAR="${PRODUCT_JAR:?Set PRODUCT_JAR to the exact pinned candidate production jar}"
PRODUCT_APK="${PRODUCT_APK:?Set PRODUCT_APK to the exact candidate APK for class binding}"
SIGNING_DIR="${SIGNING_DIR:?Set SIGNING_DIR to the external debug keystore directory}"
EXPECTED_JAR="${PRODUCT_JAR_SHA256:?Set the reviewed production jar SHA256}"
EXPECTED_APK="${PRODUCT_APK_SHA256:?Set the reviewed candidate APK SHA256}"
[[ "$BUILD" == /* && "$BUILD" != / && "$BUILD" != "$PROJECT" ]] || { echo 'An absolute isolated helper build directory is required' >&2; exit 1; }
[[ "$(shasum -a 256 "$PRODUCT_JAR" | awk '{print $1}')" == "$EXPECTED_JAR" ]] || { echo 'Pinned production jar checksum mismatch' >&2; exit 1; }
[[ "$(shasum -a 256 "$PRODUCT_APK" | awk '{print $1}')" == "$EXPECTED_APK" ]] || { echo 'Pinned candidate APK checksum mismatch' >&2; exit 1; }
[[ -f "$SIGNING_DIR/ngapure-debug.keystore" ]] || { echo 'Explicit external debug signing key required' >&2; exit 1; }
[[ -f "$PRODUCT_JAR" && -f "$PRODUCT_APK" ]] || { echo 'Exact product jar and candidate APK required' >&2; exit 1; }
rm -rf "$BUILD/classes" "$BUILD/dex"
mkdir -p "$BUILD/classes" "$BUILD/dex"
"$JDK/bin/javap" -p -c -classpath "$PRODUCT_JAR" cn.ngapure.app.NativeCommunity > "$BUILD/html-signature.txt"
rg -q 'android/text/Html.fromHtml:.*Landroid/text/Spanned;' "$BUILD/html-signature.txt" || { echo 'Real Android Spanned bytecode required' >&2; exit 1; }
if rg -q 'android/text/Html.fromHtml:.*Ljava/lang/CharSequence;' "$BUILD/html-signature.txt"; then echo 'JVM Html stub bytecode forbidden' >&2; exit 1; fi
"$JDK/bin/javac" --release 8 -Xlint:all -Werror -encoding UTF-8 -cp "$SDK/platforms/android-35/android.jar:$PRODUCT_JAR" -d "$BUILD/classes" "$HELPER_DIR/ImageSaveProvider.java" "$HELPER_DIR/ImageSaveProbe.java"
"$JDK/bin/jar" cf "$BUILD/probe.jar" -C "$BUILD/classes" .
"$BT/d8" --release --min-api 26 --lib "$SDK/platforms/android-35/android.jar" --output "$BUILD/dex" "$PRODUCT_JAR" "$BUILD/probe.jar"
"$BT/aapt2" link -I "$SDK/platforms/android-35/android.jar" --manifest "$HELPER_DIR/AndroidManifest.xml" -o "$BUILD/unaligned.apk"
cp "$BUILD/dex/classes.dex" "$BUILD/classes.dex"
(cd "$BUILD" && zip -q unaligned.apk classes.dex)
"$BT/zipalign" -f 4 "$BUILD/unaligned.apk" "$BUILD/image-save-fixture.apk"
"$BT/apksigner" sign --ks "${SIGNING_DIR}/ngapure-debug.keystore" --ks-key-alias ngapure-debug --ks-pass pass:android --key-pass pass:android "$BUILD/image-save-fixture.apk"
"$BT/aapt2" dump permissions "$BUILD/image-save-fixture.apk" > "$BUILD/permissions.txt"
if rg -q 'android.permission.INTERNET|sharedUserId' "$HELPER_DIR/AndroidManifest.xml" "$BUILD/permissions.txt"; then echo 'Fixture must not request INTERNET or share a UID' >&2; exit 1; fi
python3 "$HELPER_DIR/bind-device-classes.py" "$PRODUCT_JAR" "$BUILD/probe.jar" "$PRODUCT_APK" "$BUILD/image-save-fixture.apk" "$BUILD/product-class-binding.json"
printf '%s\n' "$BUILD/image-save-fixture.apk"
