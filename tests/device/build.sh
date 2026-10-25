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
BUILD="${TEST_DEVICE_BUILD_DIR:-$PROJECT/.build/device}"
PRODUCT_CLASSES="${PRODUCT_CLASSES:-$PROJECT/.build/app/classes.jar}"
rm -rf "$BUILD/classes" "$BUILD/dex"
mkdir -p "$BUILD/classes" "$BUILD/dex"
"$JDK/bin/javac" --release 8 -Xlint:all -Werror -encoding UTF-8 -cp "$SDK/platforms/android-35/android.jar:$PRODUCT_CLASSES" -d "$BUILD/classes" "$TEST_DIR/NativeVerify.java" "$TEST_DIR/ProtocolProbe.java" "$TEST_DIR/CaptchaProbe.java" "$TEST_DIR/CaptchaLifecycleProbe.java" "$TEST_DIR/PageJumpProbe.java" "$TEST_DIR/GlassDialogProbe.java" "$TEST_DIR/CredentialIdentityProbe.java" "$TEST_DIR/BoardFeaturesProbe.java" "$TEST_DIR/CloudFavoritesReadProbe.java" "$TEST_DIR/FavoriteProbeState.java" "$TEST_DIR/FavoriteSyncProbe.java" "$TEST_DIR/CloudToggleRoundTripProbe.java" "$TEST_DIR/ReaderProbe.java" "$TEST_DIR/ReplyRouteProbe.java" "$TEST_DIR/MediaProbe.java" "$TEST_DIR/OptimizationProbe.java" "$TEST_DIR/PaginationProbe.java" "$TEST_DIR/AdaptiveUiProbe.java" "$TEST_DIR/TypographyProbe.java" "$TEST_DIR/AvatarProbe.java" "$TEST_DIR/AvatarLiveProbe.java" "$TEST_DIR/RichTextLinksProbe.java"
"$JDK/bin/jar" cf "$BUILD/tests.jar" -C "$BUILD/classes" .
"$BT/d8" --release --min-api 26 --lib "$SDK/platforms/android-35/android.jar" --classpath "$PRODUCT_CLASSES" --output "$BUILD/dex" "$BUILD/tests.jar"
"$BT/aapt2" link -I "$SDK/platforms/android-35/android.jar" --manifest "$TEST_DIR/AndroidManifest.xml" -o "$BUILD/unaligned.apk"
cp "$BUILD/dex/classes.dex" "$BUILD/classes.dex"
(cd "$BUILD" && zip -q unaligned.apk classes.dex)
"$BT/zipalign" -f 4 "$BUILD/unaligned.apk" "$BUILD/verify.apk"
"$BT/apksigner" sign --ks "$SIGNING_DIR/ngapure-debug.keystore" --ks-key-alias ngapure-debug --ks-pass pass:android --key-pass pass:android "$BUILD/verify.apk"
printf '%s\n' "$BUILD/verify.apk"
