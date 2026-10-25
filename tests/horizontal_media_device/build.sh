#!/usr/bin/env bash
set -euo pipefail
BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
: "${PRODUCT_JAR:?Set immutable product classes jar}"
: "${PRODUCT_APK:?Set matching product APK}"
: "${SIGNING_DIR:?Set private signing directory}"
: "${BUILD_DIR:?Set isolated helper build directory}"
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"
SDK="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"
BT="$SDK/build-tools/35.0.0"
ANDROID="$SDK/platforms/android-35/android.jar"
[[ "$BUILD_DIR" == /* && "$BUILD_DIR" != / && "$BUILD_DIR" != "$BASE" ]] || { echo 'Fresh absolute isolated helper directory required' >&2; exit 1; }
python3 - "$PRODUCT_JAR" "$PRODUCT_APK" "${PRODUCT_JAR_SHA256:-}" "${PRODUCT_APK_SHA256:-}" "$BUILD_DIR" <<'PY'
import hashlib,pathlib,sys
for raw,expected in [(sys.argv[1],sys.argv[3]),(sys.argv[2],sys.argv[4])]:
 if expected and hashlib.sha256(pathlib.Path(raw).read_bytes()).hexdigest()!=expected:raise SystemExit('Explicit production input hash mismatch')
build=pathlib.Path(sys.argv[5]);build.mkdir(parents=True,exist_ok=True)
if any(build.iterdir()):raise SystemExit('Fresh isolated directory required; refusing stale helper classes')
PY
mkdir -p "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$BUILD_DIR/assets"
cp "$PRODUCT_JAR" "$BUILD_DIR/production.jar"
cp "$BASE/assets/synthetic-two-frame.gif" "$BUILD_DIR/assets/"
"$JDK/bin/javac" --release 8 -Xlint:all -Werror -encoding UTF-8 -cp "$ANDROID:$BUILD_DIR/production.jar" -d "$BUILD_DIR/classes" "$BASE"/src/cn/ngapure/horizontalprobe/*.java
"$JDK/bin/jar" cf "$BUILD_DIR/helper.jar" -C "$BUILD_DIR/classes" .
python3 - "$BASE" "$BUILD_DIR" "$PRODUCT_APK" <<'PY'
import hashlib,json,pathlib,sys,zipfile
base,build,apk=map(pathlib.Path,sys.argv[1:]);sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
with zipfile.ZipFile(build/'production.jar') as jar:
 classes={n:hashlib.sha256(jar.read(n)).hexdigest() for n in jar.namelist() if n.endswith('.class')}
for name in ['NativeRichText','PostRenderer','NativeTableModel','FlowTextWhitespace','NativeImages']:
 if 'cn/ngapure/app/'+name+'.class' not in classes:raise SystemExit('Missing required full product reader class: '+name)
with zipfile.ZipFile(build/'helper.jar') as helper:
 if set(classes).intersection(helper.namelist()):raise SystemExit('Helper shadows production class')
report=dict(productApkSha256=sha(apk),productJarSha256=sha(build/'production.jar'),productionClassSha256=classes,productionClassesRewritten=False,helperOwnUid=True,internetPermission=False,syntheticImageOnly=True,mainClientStarted=False,mode='EXACT_FULL_PRODUCT_JAR',blockAttachmentContractOptIn=True,legacyInlineModePreserved=True,fixtureSha256={p.name:sha(p) for p in (build/'assets').iterdir() if p.is_file()},sourceSha256={str(p.relative_to(base)):sha(p) for p in base.rglob('*') if p.is_file()})
(build/'assets/binding.json').write_text(json.dumps(report,sort_keys=True)+'\n');(build/'binding.json').write_text(json.dumps(report,indent=2)+'\n')
PY
"$BT/d8" --release --min-api 28 --lib "$ANDROID" --output "$BUILD_DIR/dex" "$BUILD_DIR/production.jar" "$BUILD_DIR/helper.jar"
"$BT/aapt2" link -I "$ANDROID" --manifest "$BASE/AndroidManifest.xml" -A "$BUILD_DIR/assets" -o "$BUILD_DIR/unaligned.apk"
(cd "$BUILD_DIR/dex" && zip -q "$BUILD_DIR/unaligned.apk" classes*.dex)
"$BT/zipalign" -f 4 "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/horizontal-media-probe.apk"
"$BT/apksigner" sign --ks "$SIGNING_DIR/ngapure-debug.keystore" --ks-key-alias ngapure-debug --ks-pass pass:android --key-pass pass:android "$BUILD_DIR/horizontal-media-probe.apk"
"$BT/apksigner" verify "$BUILD_DIR/horizontal-media-probe.apk"
"$BT/aapt2" dump permissions "$BUILD_DIR/horizontal-media-probe.apk" > "$BUILD_DIR/permissions.txt"
if rg -q 'android.permission.INTERNET|sharedUserId' "$BASE/AndroidManifest.xml" "$BUILD_DIR/permissions.txt"; then echo 'No INTERNET or shared UID permitted' >&2; exit 1; fi
printf '%s\n' "$BUILD_DIR/horizontal-media-probe.apk"
shasum -a 256 "$BUILD_DIR/horizontal-media-probe.apk"
