#!/usr/bin/env bash
set -euo pipefail
BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "$BASE/../.." && pwd)"
JDK="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"
SDK="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"
ANDROID="$SDK/platforms/android-35/android.jar"
BT="$SDK/build-tools/35.0.0"
BUILD="${BUILD_DIR:-$PROJECT/.build/media-device-tests}"
: "${PRODUCT_CLASSES:?Set PRODUCT_CLASSES to the final immutable product classes.jar}"
: "${PRODUCT_APK:?Set PRODUCT_APK to the exact candidate APK}"
: "${SIGNING_DIR:?Set SIGNING_DIR to the directory containing ngapure-debug.keystore}"
[[ -f "$PRODUCT_CLASSES" && -f "$PRODUCT_APK" && -f "$SIGNING_DIR/ngapure-debug.keystore" ]] || { echo "Product artifacts or explicit signing key missing" >&2; exit 1; }
rm -rf "$BUILD/production" "$BUILD/helper" "$BUILD/dex" "$BUILD/assets"
mkdir -p "$BUILD/production" "$BUILD/helper" "$BUILD/dex" "$BUILD/assets"
cp "$BASE/assets/synthetic-two-frame.gif" "$BASE/assets/synthetic-large-two-frame.gif" "$BASE/assets/synthetic-exif-rotate6.jpg" "$BASE/assets/synthetic-static.png" "$BUILD/assets/"
cp "$PRODUCT_CLASSES" "$BUILD/production.jar"
(cd "$BUILD/production" && "$JDK/bin/jar" xf "$BUILD/production.jar")
"$JDK/bin/javac" --release 8 -Xlint:all -Werror -encoding UTF-8 -cp "$ANDROID:$BUILD/production.jar" -d "$BUILD/helper" \
 "$BASE/src/cn/ngapure/imageprobe/ProbeHostActivity.java" "$BASE/src/cn/ngapure/imageprobe/NativeImagesProbe.java" "$BASE/src/cn/ngapure/imageprobe/NativeMediaPlayerProbe.java"
python3 - "$PROJECT" "$BASE" "$BUILD" "$PRODUCT_APK" <<'PY'
from pathlib import Path
import hashlib,json,sys,os,zipfile
project,base,build,apk=map(Path,sys.argv[1:]);sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
classes={str(p.relative_to(build/'production')):sha(p) for p in (build/'production').rglob('*.class')}
names=['NativeMediaPlayer','NativeMediaPolicy','NativeMediaCache','NativeImages','NativeRichText','PostRenderer','NativeEncryptedContent','NativeAvatarResolver','AvatarSources','RequestCancellation']
archive=os.environ.get('SOURCE_ARCHIVE','')
if archive:
 source_archive=Path(archive)
 with zipfile.ZipFile(source_archive) as z:
  sources={}
  for n in names:
   suffix='app/src/main/java/cn/ngapure/app/'+n+'.java'
   matches=[name for name in z.namelist() if name.endswith(suffix)]
   if not matches and n=='NativeMediaCache':continue
   if len(matches)!=1:raise SystemExit('Ambiguous source archive entry: '+n)
   sources[n+'.java']=hashlib.sha256(z.read(matches[0])).hexdigest()
 source_provenance=dict(kind='IMMUTABLE_PRODUCT_SOURCE_ARCHIVE',archiveSha256=sha(source_archive))
else:
 sources={n+'.java':sha(project/'app/src/main/java/cn/ngapure/app'/f'{n}.java') for n in names}
 source_provenance=dict(kind='CURRENT_WORKING_TREE_AT_HELPER_BUILD')
binding=dict(schema=1,mode='EXACT_PRODUCT_CLASSES_JAR',productClassesJarSha256=sha(build/'production.jar'),productApkSha256=sha(apk),classSha256=classes,sourceSha256=sources,productionClassesRewritten=False,internetPermission=False,helperOwnUid=True,mainAppDataCopied=False,fixturesGeneratedLocally=True,sourceProvenance=source_provenance)
(build/'assets/binding.json').write_text(json.dumps(binding,sort_keys=True,separators=(',',':'))+'\n')
(build/'production-class-binding.json').write_text(json.dumps(binding,sort_keys=True,indent=2)+'\n')
PY
"$JDK/bin/jar" cf "$BUILD/helper.jar" -C "$BUILD/helper" .
"$BT/d8" --release --min-api 28 --lib "$ANDROID" --output "$BUILD/dex" "$BUILD/production.jar" "$BUILD/helper.jar"
"$BT/aapt2" link -I "$ANDROID" --manifest "$BASE/AndroidManifest.xml" -A "$BUILD/assets" -o "$BUILD/unaligned.apk"
(cd "$BUILD/dex" && zip -q "$BUILD/unaligned.apk" classes*.dex)
"$BT/zipalign" -f 4 "$BUILD/unaligned.apk" "$BUILD/native-images-probe.apk"
"$BT/apksigner" sign --ks "$SIGNING_DIR/ngapure-debug.keystore" --ks-key-alias ngapure-debug --ks-pass pass:android --key-pass pass:android "$BUILD/native-images-probe.apk"
"$BT/apksigner" verify "$BUILD/native-images-probe.apk"
printf '%s\n' "$BUILD/native-images-probe.apk"
shasum -a 256 "$BUILD/native-images-probe.apk"
