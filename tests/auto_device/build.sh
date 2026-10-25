#!/usr/bin/env bash
set -euo pipefail
TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "$TEST_DIR/../.." && pwd)"
: "${SIGNING_DIR:?Set an external local-debug signing directory}"
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"
SDK="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"
BT="$SDK/build-tools/35.0.0"
BUILD="$PROJECT/.build/auto-device"
mkdir -p "$BUILD/classes" "$BUILD/dex"
"$JDK/bin/javac" --release 8 -Xlint:all -Werror -encoding UTF-8 \
    -cp "$SDK/platforms/android-35/android.jar:$PROJECT/.build/app/classes" \
    -d "$BUILD/classes" "$TEST_DIR/AutoFavoriteSyncProbe.java"
"$JDK/bin/jar" cf "$BUILD/tests.jar" -C "$BUILD/classes" .
"$BT/d8" --release --min-api 26 --lib "$SDK/platforms/android-35/android.jar" \
    --classpath "$PROJECT/.build/app/classes" --output "$BUILD/dex" "$BUILD/tests.jar"
"$BT/aapt2" link -I "$SDK/platforms/android-35/android.jar" \
    --manifest "$TEST_DIR/AndroidManifest.xml" -o "$BUILD/unaligned.apk"
cp "$BUILD/dex/classes.dex" "$BUILD/classes.dex"
(cd "$BUILD" && zip -q unaligned.apk classes.dex)
"$BT/zipalign" -f 4 "$BUILD/unaligned.apk" "$BUILD/verify.apk"
"$BT/apksigner" sign --ks "$SIGNING_DIR/ngapure-debug.keystore" \
    --ks-key-alias ngapure-debug --ks-pass pass:android --key-pass pass:android "$BUILD/verify.apk"
printf '%s\n' "$BUILD/verify.apk"
