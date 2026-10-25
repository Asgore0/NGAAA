#!/usr/bin/env bash
set -euo pipefail
BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "$BASE/../.." && pwd)"
: "${PRODUCT_JAR:?Set PRODUCT_JAR to the immutable production classes.jar}"
: "${PRODUCT_APK:?Set PRODUCT_APK to the matching immutable production APK}"
: "${PRODUCT_JAR_SHA256:?Set the independently recorded production jar SHA256}"
: "${PRODUCT_APK_SHA256:?Set the independently recorded production APK SHA256}"
: "${SIGNING_DIR:?Set SIGNING_DIR to the private signing directory}"
: "${BUILD_DIR:?Set BUILD_DIR to an absolute fresh isolated helper build directory}"
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"
SDK="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"
BT="$SDK/build-tools/35.0.0"
ANDROID="$SDK/platforms/android-35/android.jar"
BUILD="$BUILD_DIR"
[[ "$BUILD" == /* && "$BUILD" != / && "$BUILD" != "$PROJECT" ]] || { echo 'Fresh absolute isolated helper directory required' >&2; exit 1; }
python3 - "$PRODUCT_JAR" "$PRODUCT_APK" "$PRODUCT_JAR_SHA256" "$PRODUCT_APK_SHA256" "$BUILD" <<'PY'
import hashlib,pathlib,sys
for raw,expected in [(sys.argv[1],sys.argv[3]),(sys.argv[2],sys.argv[4])]:
    if hashlib.sha256(pathlib.Path(raw).read_bytes()).hexdigest()!=expected:raise SystemExit('Explicit immutable production hash mismatch')
build=pathlib.Path(sys.argv[5]);build.mkdir(parents=True,exist_ok=True)
if any(build.iterdir()):raise SystemExit('Fresh isolated directory required; refusing stale helper classes')
PY
mkdir -p "$BUILD/classes" "$BUILD/dex" "$BUILD/assets"
cp "$PRODUCT_JAR" "$BUILD/production.jar"
"$JDK/bin/javac" --release 8 -Xlint:all -Werror -encoding UTF-8 -cp "$ANDROID:$BUILD/production.jar" -d "$BUILD/classes" "$BASE/DirectoryLayoutProbe.java"
"$JDK/bin/jar" cf "$BUILD/helper.jar" -C "$BUILD/classes" .
python3 - "$BASE" "$BUILD" "$PRODUCT_APK" <<'PY'
import hashlib,json,pathlib,sys,zipfile
base,build,apk=map(pathlib.Path,sys.argv[1:]);sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
with zipfile.ZipFile(build/'production.jar') as jar:
    classes={n:hashlib.sha256(jar.read(n)).hexdigest() for n in jar.namelist() if n.endswith('.class')}
assets={}
with zipfile.ZipFile(apk) as product:
    for name in product.namelist():
        if not name.startswith('assets/') or name.endswith('/'):continue
        rel=pathlib.PurePosixPath(name)
        if '..' in rel.parts or '\\' in name or rel.is_absolute():raise SystemExit('Unsafe product asset path')
        target=build/name;target.parent.mkdir(parents=True,exist_ok=True);data=product.read(name);target.write_bytes(data);assets[name]=hashlib.sha256(data).hexdigest()
if 'assets/forums.json' not in assets:raise SystemExit('Exact production public catalog asset required')
report={'productApkSha256':sha(apk),'productJarSha256':sha(build/'production.jar'),'productionClassSha256':classes,'productionClassesRewritten':False,'productionAssetsCopiedByteExactly':True,'productionAssetSha256':assets,'helperOwnUid':True,'internetPermission':False,'realClientStarted':False,'sourceSha256':{p.name:sha(p) for p in base.iterdir() if p.is_file()},'localActivityDensityOverride':160,'paneWidthsDp':[390,520,760],'fontScales':[1,1.3,2],'colorModes':['light','dark'],'titleCases':['晴风村','消费电子IT新闻','二次元国家地理','明日方舟终末地'],'mode':'EXACT_PRODUCT_JAR_AND_PUBLIC_ASSETS'}
(build/'assets/binding.json').write_text(json.dumps(report,sort_keys=True)+'\n');(build/'binding.json').write_text(json.dumps(report,indent=2)+'\n')
PY
"$BT/d8" --release --min-api 26 --lib "$ANDROID" --output "$BUILD/dex" "$BUILD/production.jar" "$BUILD/helper.jar"
"$BT/aapt2" link -I "$ANDROID" --manifest "$BASE/AndroidManifest.xml" -A "$BUILD/assets" -o "$BUILD/unaligned.apk"
python3 - "$BUILD" <<'PY'
import pathlib,sys,zipfile
build=pathlib.Path(sys.argv[1])
with zipfile.ZipFile(build/'unaligned.apk','a',compression=zipfile.ZIP_DEFLATED) as archive:
    for p in (build/'dex').glob('classes*.dex'):archive.write(p,p.name)
PY
"$BT/zipalign" -f 4 "$BUILD/unaligned.apk" "$BUILD/directory-layout-probe.apk"
"$BT/apksigner" sign --ks "$SIGNING_DIR/ngapure-debug.keystore" --ks-key-alias ngapure-debug --ks-pass pass:android --key-pass pass:android "$BUILD/directory-layout-probe.apk"
"$BT/apksigner" verify "$BUILD/directory-layout-probe.apk"
"$BT/aapt2" dump permissions "$BUILD/directory-layout-probe.apk" > "$BUILD/permissions.txt"
if rg -q 'android.permission.INTERNET|sharedUserId' "$BASE/AndroidManifest.xml" "$BUILD/permissions.txt"; then echo 'No INTERNET or shared UID permitted' >&2; exit 1; fi
printf '%s\n' "$BUILD/directory-layout-probe.apk"
