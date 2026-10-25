#!/usr/bin/env bash
set -euo pipefail
BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "$BASE/../../.." && pwd)"
: "${PRODUCT_JAR:?Set PRODUCT_JAR to the immutable production classes.jar}"
: "${PRODUCT_APK:?Set PRODUCT_APK to the matching production APK}"
: "${PRODUCT_JAR_SHA256:?Set the independently recorded production jar SHA256}"
: "${PRODUCT_APK_SHA256:?Set the independently recorded production APK SHA256}"
: "${SIGNING_DIR:?Set SIGNING_DIR to the private signing directory}"
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"
SDK="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"
BT="$SDK/build-tools/35.0.0"
ANDROID="$SDK/platforms/android-35/android.jar"
BUILD="${BUILD_DIR:-$PROJECT/.build/scope-lifecycle-device}"
python3 - "$PRODUCT_JAR" "$PRODUCT_APK" "$PRODUCT_JAR_SHA256" "$PRODUCT_APK_SHA256" <<'PY'
import hashlib,pathlib,sys
jar,apk=map(pathlib.Path,sys.argv[1:3])
for path,expected in [(jar,sys.argv[3]),(apk,sys.argv[4])]:
    if hashlib.sha256(path.read_bytes()).hexdigest()!=expected:raise SystemExit('Explicit production hash mismatch')
PY
mkdir -p "$BUILD/classes" "$BUILD/dex" "$BUILD/assets"
cp "$PRODUCT_JAR" "$BUILD/production.jar"
"$JDK/bin/javac" --release 8 -Xlint:all -Werror -encoding UTF-8 -cp "$ANDROID:$BUILD/production.jar" -d "$BUILD/classes" "$BASE/ScopePauseActivity.java" "$BASE/ScopeLifecycleProbe.java"
"$JDK/bin/jar" cf "$BUILD/helper.jar" -C "$BUILD/classes" .
python3 - "$BASE" "$BUILD" "$PRODUCT_APK" <<'PY'
import hashlib,json,pathlib,sys,zipfile
base,build,apk=map(pathlib.Path,sys.argv[1:]);sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
with zipfile.ZipFile(build/'production.jar') as jar:
    classes={n:hashlib.sha256(jar.read(n)).hexdigest() for n in jar.namelist() if n.startswith('cn/ngapure/app/MainActivity') and n.endswith('.class')}
report=dict(productApkSha256=sha(apk),productJarSha256=sha(build/'production.jar'),productionMainClassSha256=classes,productionClassesRewritten=False,helperOwnUid=True,internetPermission=False,syntheticSessionsOnly=True,realClientStarted=False,sourceSha256={p.name:sha(p) for p in base.iterdir() if p.is_file()})
(build/'assets/binding.json').write_text(json.dumps(report,sort_keys=True)+'\n');(build/'binding.json').write_text(json.dumps(report,indent=2)+'\n')
PY
"$BT/d8" --release --min-api 26 --lib "$ANDROID" --output "$BUILD/dex" "$BUILD/production.jar" "$BUILD/helper.jar"
"$BT/aapt2" link -I "$ANDROID" --manifest "$BASE/AndroidManifest.xml" -A "$BUILD/assets" -o "$BUILD/unaligned.apk"
python3 - "$BUILD" <<'PY'
import pathlib,sys,zipfile
root=pathlib.Path(sys.argv[1])
with zipfile.ZipFile(root/'unaligned.apk','a',compression=zipfile.ZIP_DEFLATED) as archive:
    for p in (root/'dex').glob('classes*.dex'):archive.write(p,p.name)
PY
"$BT/zipalign" -f 4 "$BUILD/unaligned.apk" "$BUILD/scope-lifecycle-probe.apk"
"$BT/apksigner" sign --ks "$SIGNING_DIR/ngapure-debug.keystore" --ks-key-alias ngapure-debug --ks-pass pass:android --key-pass pass:android "$BUILD/scope-lifecycle-probe.apk"
"$BT/apksigner" verify "$BUILD/scope-lifecycle-probe.apk"
printf '%s\n' "$BUILD/scope-lifecycle-probe.apk"
