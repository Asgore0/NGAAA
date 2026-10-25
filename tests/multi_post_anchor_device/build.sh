#!/usr/bin/env bash
# Isolated own-UID helper. Compile only the fixture; consume explicit immutable product classes.
set -euo pipefail
BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "$BASE/../.." && pwd)"
BUILD="${BUILD_DIR:-$PROJECT/.build/multi-post-anchor-device}"
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
SDK="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"
BT="$SDK/build-tools/35.0.0"
ANDROID="$SDK/platforms/android-35/android.jar"
: "${PRODUCT_CLASSES:?Set explicit immutable product classes JAR}"
: "${PRODUCT_APK:?Set exact product APK}"
: "${SIGNING_DIR:?Set external signing directory; never packaged}"
[[ -f "$PRODUCT_CLASSES" && -f "$PRODUCT_APK" && -f "$SIGNING_DIR/ngapure-debug.keystore" ]] || { echo "Explicit product artifacts/signing key missing" >&2; exit 1; }
export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"
mkdir -p "$BUILD/helper" "$BUILD/assets" "$BUILD/dex"
python3 - "$BASE" "$BUILD" "$PRODUCT_CLASSES" "$PRODUCT_APK" <<'PY'
import hashlib,json,sys,zipfile
from pathlib import Path
base,build,classes,apk=map(Path,sys.argv[1:]);sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
with zipfile.ZipFile(classes) as z:
 assert len(z.namelist())==len(set(z.namelist()))
 hashes={n:hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if n.endswith('.class')}
assert 'cn/ngapure/app/NativeRichText.class' in hashes
sources=list((base/'src').rglob('*.java'))+[base/'AndroidManifest.xml',base/'build.sh']
binding=dict(productClassesJarSha256=sha(classes),productApkSha256=sha(apk),productionClassSha256=hashes,helperSourceSha256={str(p.relative_to(base)):sha(p) for p in sources},productionClassesRewritten=False,sourceProvenance='EXPLICIT_IMMUTABLE_PRODUCT_JAR_AND_APK',internetPermission=False,helperOwnUid=True,syntheticMediaOnly=True,mainAccountRead=False,realNetworkRequests=0)
(build/'assets/binding.json').write_text(json.dumps(binding,sort_keys=True,separators=(',',':'))+'\n')
(build/'binding.json').write_text(json.dumps(binding,sort_keys=True,indent=2)+'\n')
PY
"$JDK/bin/javac" --release 8 -Xlint:all -Werror -encoding UTF-8 -cp "$ANDROID:$PRODUCT_CLASSES" -d "$BUILD/helper" \
 "$BASE/src/cn/ngapure/anchorprobe/AnchorHostActivity.java" "$BASE/src/cn/ngapure/anchorprobe/MultiPostAnchorProbe.java"
"$JDK/bin/jar" cf "$BUILD/helper.jar" -C "$BUILD/helper" .
"$BT/d8" --release --min-api 28 --lib "$ANDROID" --output "$BUILD/dex" "$PRODUCT_CLASSES" "$BUILD/helper.jar"
"$BT/aapt2" link -I "$ANDROID" --manifest "$BASE/AndroidManifest.xml" -A "$BUILD/assets" -o "$BUILD/unaligned.apk"
(cd "$BUILD/dex" && zip -q "$BUILD/unaligned.apk" classes*.dex)
"$BT/zipalign" -f 4 "$BUILD/unaligned.apk" "$BUILD/multi-post-anchor-probe.apk"
"$BT/apksigner" sign --ks "$SIGNING_DIR/ngapure-debug.keystore" --ks-key-alias ngapure-debug --ks-pass pass:android --key-pass pass:android "$BUILD/multi-post-anchor-probe.apk"
"$BT/apksigner" verify "$BUILD/multi-post-anchor-probe.apk"
shasum -a 256 "$BUILD/multi-post-anchor-probe.apk"
