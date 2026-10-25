#!/usr/bin/env bash
set -euo pipefail
TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$TEST_DIR/../.." && pwd)"
: "${PRODUCT_JAR:?Set PRODUCT_JAR to the exact immutable production classes.jar}"
: "${PRODUCT_APK:?Set PRODUCT_APK to the matching production APK}"
: "${SIGNING_DIR:?Set SIGNING_DIR to the private directory containing ngapure-debug.keystore}"
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}}"
BUILD_TOOLS="$SDK/build-tools/35.0.0"
BUILD_DIR="${BUILD_DIR:-$PROJECT_ROOT/.build/recreation-device}"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
[[ -f "$PRODUCT_JAR" && -f "$PRODUCT_APK" && -f "$SIGNING_DIR/ngapure-debug.keystore" && -f "$ANDROID_JAR" ]] || { printf 'Missing explicit production artifact, signing key, or Android API35.\n' >&2; exit 1; }
mkdir -p "$BUILD_DIR"
python3 - "$BUILD_DIR" <<'PY'
import pathlib,shutil,sys
root=pathlib.Path(sys.argv[1])
for name in ['helper-classes','dex']:
    child=root/name
    if child.exists():shutil.rmtree(child)
    child.mkdir()
PY
# Compile only these helper sources; target production classes remain in the installed application.
"$JDK/bin/javac" --release 8 -Xlint:all -Werror -encoding UTF-8 \
    -cp "$ANDROID_JAR:$PRODUCT_JAR" -d "$BUILD_DIR/helper-classes" \
    "$TEST_DIR/MainRecreationProbe.java" "$TEST_DIR/AvatarCropLifecycleProbe.java" "$TEST_DIR/ImageViewportLifecycleProbe.java" "$TEST_DIR/AvatarFixtureProvider.java"
"$JDK/bin/jar" cf "$BUILD_DIR/helper-only.jar" -C "$BUILD_DIR/helper-classes" .
"$BUILD_TOOLS/d8" --release --min-api 26 --lib "$ANDROID_JAR" \
    --classpath "$PRODUCT_JAR" --output "$BUILD_DIR/dex" "$BUILD_DIR/helper-only.jar"
"$BUILD_TOOLS/aapt2" link -I "$ANDROID_JAR" --manifest "$TEST_DIR/AndroidManifest.xml" -o "$BUILD_DIR/unaligned.apk"
python3 - "$BUILD_DIR" <<'PY'
import pathlib,sys,zipfile
root=pathlib.Path(sys.argv[1])
with zipfile.ZipFile(root/'unaligned.apk','a',compression=zipfile.ZIP_DEFLATED) as archive:
    archive.write(root/'dex/classes.dex','classes.dex')
PY
"$BUILD_TOOLS/zipalign" -f 4 "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/recreation-probe.apk"
"$BUILD_TOOLS/apksigner" sign --ks "$SIGNING_DIR/ngapure-debug.keystore" \
    --ks-key-alias ngapure-debug --ks-pass pass:android --key-pass pass:android "$BUILD_DIR/recreation-probe.apk"
"$BUILD_TOOLS/apksigner" verify "$BUILD_DIR/recreation-probe.apk"
python3 - "$BUILD_DIR" "$PRODUCT_JAR" "$PRODUCT_APK" "$TEST_DIR" <<'PY'
import hashlib,json,pathlib,sys
build,jar,apk,source=map(pathlib.Path,sys.argv[1:]);sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
report={'productJarSHA256':sha(jar),'productApkSHA256':sha(apk),'helperApkSHA256':sha(build/'recreation-probe.apk'),'targetPackage':'cn.ngapure.app','networkPermissionDeclaredByHelper':False,'productionClassesBundledIntoHelper':False,'sourceSHA256':{name:sha(source/name) for name in ['MainRecreationProbe.java','AvatarCropLifecycleProbe.java','ImageViewportLifecycleProbe.java','AvatarFixtureProvider.java','AndroidManifest.xml','build.sh']},'method':'Instrumentation calls installed MainActivity Activity.recreate; runner must independently verify installed product APK hash.'}
(build/'binding.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
PY
printf '%s\n' "$BUILD_DIR/recreation-probe.apk"
