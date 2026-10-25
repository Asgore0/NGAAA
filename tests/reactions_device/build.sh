#!/usr/bin/env bash
set -euo pipefail
BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "$BASE/../.." && pwd)"
: "${PRODUCT_JAR:?Set PRODUCT_JAR to the immutable product classes.jar}"
: "${PRODUCT_APK:?Set PRODUCT_APK to the matching product APK}"
: "${PRODUCT_JAR_SHA256:?Set the independently recorded product JAR SHA256}"
: "${PRODUCT_APK_SHA256:?Set the independently recorded product APK SHA256}"
: "${SIGNING_DIR:?Set SIGNING_DIR to the private signing directory}"
: "${BUILD_DIR:?Set an absolute isolated helper build directory}"
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"
SDK="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"
BT="$SDK/build-tools/35.0.0"
ANDROID="$SDK/platforms/android-35/android.jar"
[[ "$BUILD_DIR" == /* && "$BUILD_DIR" != / && "$BUILD_DIR" != "$PROJECT" ]]
[[ -f "$SIGNING_DIR/ngapure-debug.keystore" ]]
python3 - "$PRODUCT_JAR" "$PRODUCT_APK" "$PRODUCT_JAR_SHA256" "$PRODUCT_APK_SHA256" <<'PY'
import hashlib,pathlib,sys
for raw,expected in [(sys.argv[1],sys.argv[3]),(sys.argv[2],sys.argv[4])]:
    if hashlib.sha256(pathlib.Path(raw).read_bytes()).hexdigest()!=expected:raise SystemExit('Explicit product hash mismatch')
PY
mkdir -p "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$BUILD_DIR/assets"
cp "$PRODUCT_JAR" "$BUILD_DIR/production.jar"
"$JDK/bin/javac" --release 8 -Xlint:all -Werror -encoding UTF-8 -cp "$ANDROID:$BUILD_DIR/production.jar" -d "$BUILD_DIR/classes" "$BASE/ReactionHostActivity.java" "$BASE/NativeReactionProbe.java"
"$JDK/bin/jar" cf "$BUILD_DIR/helper.jar" -C "$BUILD_DIR/classes" .
python3 - "$BASE" "$BUILD_DIR" "$PRODUCT_APK" <<'PY'
import hashlib,json,pathlib,sys,zipfile
base,build,apk=map(pathlib.Path,sys.argv[1:]);sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
prefixes=('cn/ngapure/app/NativeVoteBar','cn/ngapure/app/NativeThreadActions','cn/ngapure/app/NativeThreadActionDialog','cn/ngapure/app/VoteMetrics','cn/ngapure/app/NativeGlassDialog')
with zipfile.ZipFile(build/'production.jar') as jar:
    classes={n:hashlib.sha256(jar.read(n)).hexdigest() for n in jar.namelist() if n.startswith(prefixes) and n.endswith('.class')}
    if not any(n.startswith(prefixes[0]) for n in classes):raise SystemExit('NativeVoteBar production class is required')
report=dict(productApkSha256=sha(apk),productJarSha256=sha(build/'production.jar'),productionClassSha256=classes,productionClassesRewritten=False,helperOwnUid=True,internetPermission=False,syntheticReactionsOnly=True,mainClientStarted=False,realCommunityMutationCount=0,sourceSha256={p.name:sha(p) for p in base.iterdir() if p.is_file()})
(build/'assets/binding.json').write_text(json.dumps(report,sort_keys=True)+'\n');(build/'binding.json').write_text(json.dumps(report,indent=2)+'\n')
PY
"$BT/d8" --release --min-api 26 --lib "$ANDROID" --output "$BUILD_DIR/dex" "$BUILD_DIR/production.jar" "$BUILD_DIR/helper.jar"
"$BT/aapt2" link -I "$ANDROID" --manifest "$BASE/AndroidManifest.xml" -A "$BUILD_DIR/assets" -o "$BUILD_DIR/unaligned.apk"
python3 - "$BUILD_DIR" <<'PY'
import pathlib,sys,zipfile
root=pathlib.Path(sys.argv[1])
with zipfile.ZipFile(root/'unaligned.apk','a',compression=zipfile.ZIP_DEFLATED) as out:
    for p in sorted((root/'dex').glob('classes*.dex')):out.write(p,p.name)
PY
"$BT/zipalign" -f 4 "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/reactions-probe.apk"
"$BT/apksigner" sign --ks "$SIGNING_DIR/ngapure-debug.keystore" --ks-key-alias ngapure-debug --ks-pass pass:android --key-pass pass:android "$BUILD_DIR/reactions-probe.apk"
"$BT/apksigner" verify "$BUILD_DIR/reactions-probe.apk"
"$BT/aapt2" dump permissions "$BUILD_DIR/reactions-probe.apk" > "$BUILD_DIR/permissions.txt"
if rg -q 'android.permission.INTERNET' "$BUILD_DIR/permissions.txt"; then echo 'Unexpected INTERNET permission' >&2; exit 1; fi
printf '%s\n' "$BUILD_DIR/reactions-probe.apk"
