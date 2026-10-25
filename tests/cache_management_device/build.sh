#!/usr/bin/env bash
set -euo pipefail
HELPER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "$HELPER_DIR/../.." && pwd)"
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"
SDK="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"
BT="$SDK/build-tools/35.0.0"
BUILD="${CACHE_MANAGEMENT_BUILD_DIR:?Set an absolute isolated helper build directory}"
PRODUCT_JAR="${PRODUCT_JAR:?Set PRODUCT_JAR to the immutable production jar}"
PRODUCT_APK="${PRODUCT_APK:?Set PRODUCT_APK to the exact candidate APK}"
SIGNING_DIR="${SIGNING_DIR:?Set SIGNING_DIR to the external debug keystore directory}"
EXPECTED_JAR="${PRODUCT_JAR_SHA256:?Set the reviewed immutable production jar SHA256}"
EXPECTED_APK="${PRODUCT_APK_SHA256:?Set the reviewed immutable APK SHA256}"
[[ "$BUILD" == /* && "$BUILD" != / && "$BUILD" != "$PROJECT" ]] || { echo 'An absolute isolated helper build directory is required' >&2; exit 1; }
[[ -f "$PRODUCT_JAR" && -f "$PRODUCT_APK" && -f "$SIGNING_DIR/ngapure-debug.keystore" ]] || { echo 'Immutable product artifacts and explicit external debug key required' >&2; exit 1; }
[[ "$(shasum -a 256 "$PRODUCT_JAR" | awk '{print $1}')" == "$EXPECTED_JAR" ]] || { echo 'Pinned product JAR checksum mismatch' >&2; exit 1; }
[[ "$(shasum -a 256 "$PRODUCT_APK" | awk '{print $1}')" == "$EXPECTED_APK" ]] || { echo 'Pinned product APK checksum mismatch' >&2; exit 1; }
rm -rf "$BUILD/classes" "$BUILD/dex" "$BUILD/assets"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$BUILD/assets"
"$JDK/bin/javac" --release 8 -Xlint:all -Werror -encoding UTF-8 -cp "$SDK/platforms/android-35/android.jar:$PRODUCT_JAR" -d "$BUILD/classes" "$HELPER_DIR/CacheHostActivity.java" "$HELPER_DIR/CacheManagementProbe.java"
"$JDK/bin/jar" cf "$BUILD/helper.jar" -C "$BUILD/classes" .
python3 - "$PRODUCT_JAR" "$PRODUCT_APK" "$BUILD/helper.jar" "$BUILD/assets/binding.json" "$EXPECTED_JAR" "$EXPECTED_APK" <<'PY'
from pathlib import Path
from zipfile import ZipFile
import hashlib,json,sys
jar,apk,helper,out=map(Path,sys.argv[1:5]);expected_jar,expected_apk=sys.argv[5:]
sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
if sha(jar)!=expected_jar or sha(apk)!=expected_apk:raise SystemExit('Immutable product changed before D8')
with ZipFile(helper) as archive:
 if any(name.startswith('cn/ngapure/app/') for name in archive.namelist()):raise SystemExit('Helper must not shadow production classes')
with ZipFile(jar) as archive:
 names=sorted(name for name in archive.namelist() if name.endswith('.class'))
 if not names or len(set(names))!=len(names):raise SystemExit('Unique product class entries required')
 classes={name:hashlib.sha256(archive.read(name)).hexdigest() for name in names}
out.write_text(json.dumps(dict(schema=1,productJarSha256=expected_jar,productApkSha256=expected_apk,productionClassesRewritten=False,helperOwnUid=True,internetPermission=False,mainAppDataCopied=False,fixturesGeneratedLocally=True,classSha256=classes),sort_keys=True,separators=(',',':'))+'\n')
PY
"$BT/d8" --release --min-api 26 --lib "$SDK/platforms/android-35/android.jar" --output "$BUILD/dex" "$PRODUCT_JAR" "$BUILD/helper.jar"
"$BT/aapt2" link -I "$SDK/platforms/android-35/android.jar" --manifest "$HELPER_DIR/AndroidManifest.xml" -A "$BUILD/assets" -o "$BUILD/unaligned.apk"
(cd "$BUILD/dex" && zip -q "$BUILD/unaligned.apk" classes*.dex)
"$BT/zipalign" -f 4 "$BUILD/unaligned.apk" "$BUILD/cache-management-fixture.apk"
"$BT/apksigner" sign --ks "$SIGNING_DIR/ngapure-debug.keystore" --ks-key-alias ngapure-debug --ks-pass pass:android --key-pass pass:android "$BUILD/cache-management-fixture.apk"
"$BT/apksigner" verify "$BUILD/cache-management-fixture.apk"
"$BT/aapt2" dump permissions "$BUILD/cache-management-fixture.apk" > "$BUILD/permissions.txt"
if rg -q 'android.permission.INTERNET|sharedUserId' "$HELPER_DIR/AndroidManifest.xml" "$BUILD/permissions.txt"; then echo 'Helper must not request INTERNET or share a UID' >&2; exit 1; fi
python3 "$HELPER_DIR/bind-device-classes.py" "$PRODUCT_JAR" "$BUILD/helper.jar" "$PRODUCT_APK" "$BUILD/cache-management-fixture.apk" "$BUILD/product-class-binding.json"
[[ "$(shasum -a 256 "$PRODUCT_JAR" | awk '{print $1}')" == "$EXPECTED_JAR" && "$(shasum -a 256 "$PRODUCT_APK" | awk '{print $1}')" == "$EXPECTED_APK" ]] || { echo 'Immutable product changed during build' >&2; exit 1; }
printf '%s\n' "$BUILD/cache-management-fixture.apk"
shasum -a 256 "$BUILD/cache-management-fixture.apk"
