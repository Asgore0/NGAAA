#!/usr/bin/env bash
set -euo pipefail
TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "$TEST_DIR/../.." && pwd)"
PRODUCT_JAR="${PRODUCT_JAR:?Set PRODUCT_JAR to the exact production classes.jar}"
PRODUCT_APK="${PRODUCT_APK:?Set PRODUCT_APK to the matching candidate APK}"
SIGNING_DIR="${SIGNING_DIR:?Set SIGNING_DIR to a fixture debug-keystore directory}"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[[ -n "$SDK" ]] || { echo 'ANDROID_SDK_ROOT or ANDROID_HOME required' >&2; exit 1; }
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
BT="$SDK/build-tools/${BUILD_TOOLS_VERSION:-35.0.0}"
BUILD_DIR="${BUILD_DIR:-$PROJECT/.build/read-lifecycle-device}"
[[ -f "$PRODUCT_JAR" && -f "$PRODUCT_APK" && -f "$SIGNING_DIR/ngapure-debug.keystore" ]] || { echo 'Product jar/APK and fixture debug keystore required' >&2; exit 1; }
mkdir -p "$BUILD_DIR"
STAGE="$(mktemp -d "$BUILD_DIR/stage.XXXXXXXX")"
trap 'rm -rf "$STAGE"' EXIT
mkdir -p "$STAGE/classes" "$STAGE/dex"
read -r PRODUCT_JAR_SHA PRODUCT_APK_SHA < <(python3 - "$PRODUCT_JAR" "$PRODUCT_APK" <<'PY'
import hashlib,sys
from pathlib import Path
print(*(hashlib.sha256(Path(p).read_bytes()).hexdigest() for p in sys.argv[1:]))
PY
)
javap -p -c -classpath "$PRODUCT_JAR" cn.ngapure.app.NativeCommunity > "$STAGE/community.txt"
javap -p -classpath "$PRODUCT_JAR" cn.ngapure.app.NativeCommunityView > "$STAGE/lifecycle.txt"
javap -p -c -classpath "$PRODUCT_JAR" cn.ngapure.app.NgaRatings > "$STAGE/ratings.txt"
python3 - "$STAGE" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1]);c=(p/'community.txt').read_text()
assert 'Landroid/text/Spanned;' in c and 'Ljava/lang/CharSequence;' not in c.split('android/text/Html.fromHtml:')[-1].split('\n')[0], 'Real Android Html bytecode required'
assert 'RequestCancellation$Token reads' in (p/'lifecycle.txt').read_text(), 'Community cancellation fix required'
assert 'RequestCancellation.track' in (p/'ratings.txt').read_text(), 'Ratings cancellation fix required'
PY
javac --release 8 -Xlint:all -Werror -encoding UTF-8 -cp "$SDK/platforms/android-35/android.jar:$PRODUCT_JAR" -d "$STAGE/classes" "$TEST_DIR/ReadFixtureActivity.java" "$TEST_DIR/ReadLifecycleProbe.java"
jar cf "$STAGE/helper-only.jar" -C "$STAGE/classes" .
"$BT/d8" --release --min-api 26 --lib "$SDK/platforms/android-35/android.jar" --output "$STAGE/dex" "$PRODUCT_JAR" "$STAGE/helper-only.jar"
"$BT/aapt2" link -I "$SDK/platforms/android-35/android.jar" --manifest "$TEST_DIR/AndroidManifest.xml" -o "$STAGE/unaligned.apk"
cp "$STAGE/dex/classes.dex" "$STAGE/classes.dex"
(cd "$STAGE" && zip -q unaligned.apk classes.dex)
"$BT/zipalign" -f 4 "$STAGE/unaligned.apk" "$BUILD_DIR/read-lifecycle-fixture.apk"
"$BT/apksigner" sign --ks "$SIGNING_DIR/ngapure-debug.keystore" --ks-key-alias ngapure-debug --ks-pass pass:android --key-pass pass:android "$BUILD_DIR/read-lifecycle-fixture.apk"
"$BT/aapt2" dump permissions "$BUILD_DIR/read-lifecycle-fixture.apk" > "$STAGE/permissions.txt"
python3 - "$TEST_DIR/AndroidManifest.xml" "$STAGE/permissions.txt" <<'PY'
from pathlib import Path
import sys
text='\n'.join(Path(p).read_text() for p in sys.argv[1:])
assert 'android.permission.INTERNET' not in text and 'sharedUserId' not in text, 'Own UID/no INTERNET required'
PY
python3 "$TEST_DIR/bind-product.py" "$PRODUCT_JAR" "$PRODUCT_APK" "$STAGE/helper-only.jar" "$BUILD_DIR/read-lifecycle-fixture.apk" "$BUILD_DIR/product-class-binding.json" "$PRODUCT_JAR_SHA" "$PRODUCT_APK_SHA"
printf '%s\n' "$BUILD_DIR/read-lifecycle-fixture.apk"
