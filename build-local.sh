#!/usr/bin/env bash
# Offline SDK build. The source checkout never receives a signing key.
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ "${1:-}" == --help ]]; then
  printf '%s\n' 'Usage: bash build-local.sh [--check]' \
    'Requires JDK 17, Android SDK API 35 and build-tools 35.0.0, Python 3 and zip.' \
    'JAVA_HOME, ANDROID_SDK_ROOT/ANDROID_HOME select installed tools.' \
    'OUTPUT_APK defaults to dist/NGAAA-<version>-debug.apk.' \
    'SIGNING_DIR selects an external local-debug key directory.' \
    'Custom signing: SIGNING_KEYSTORE, SIGNING_ALIAS, NGAAA_STORE_PASSWORD, NGAAA_KEY_PASSWORD.'
  exit 0
fi
[[ $# == 0 || ( $# == 1 && "$1" == --check ) ]] || { printf 'Unknown argument. Use --help.\n' >&2; exit 2; }
source "$PROJECT_ROOT/scripts/env.sh"
ngaaa_env
BUILD_DIR="$PROJECT_ROOT/.build/app"
APP_MAIN="$PROJECT_ROOT/app/src/main"
APP_VERSION="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$APP_MAIN/AndroidManifest.xml")"
[[ "$APP_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { printf 'Invalid manifest version.\n' >&2; exit 1; }
OUTPUT_APK="${OUTPUT_APK:-$PROJECT_ROOT/dist/NGAAA-$APP_VERSION-debug.apk}"
BUILD_TOOLS="$ANDROID_SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION"
ANDROID_JAR="$ANDROID_SDK_ROOT/platforms/android-35/android.jar"
for tool in aapt2 d8 zipalign apksigner; do
  [[ -x "$BUILD_TOOLS/$tool" ]] || { printf 'Missing Android tool: %s\n' "$BUILD_TOOLS/$tool" >&2; exit 1; }
done
command -v zip >/dev/null 2>&1 || { printf 'Install zip.\n' >&2; exit 1; }
CUSTOM_SIGNING=0
if [[ -n "${SIGNING_KEYSTORE:-}" ]]; then
  CUSTOM_SIGNING=1
  KEYSTORE="$SIGNING_KEYSTORE"
  : "${SIGNING_ALIAS:?Set SIGNING_ALIAS for the existing external keystore}"
  : "${NGAAA_STORE_PASSWORD:?Set NGAAA_STORE_PASSWORD in the environment}"
  : "${NGAAA_KEY_PASSWORD:?Set NGAAA_KEY_PASSWORD in the environment}"
  [[ -f "$KEYSTORE" ]] || { printf 'Custom signing keystore does not exist.\n' >&2; exit 1; }
else
  SIGNING_DIR="${SIGNING_DIR:-${XDG_DATA_HOME:-$HOME/.local/share}/NGAAA/signing}"
  KEYSTORE="$SIGNING_DIR/ngapure-debug.keystore"
  SIGNING_ALIAS=ngapure-debug
  NGAAA_STORE_PASSWORD=android
  NGAAA_KEY_PASSWORD=android
fi
export NGAAA_STORE_PASSWORD NGAAA_KEY_PASSWORD
# Resolve symlinks before either generating or using a key. Build cleanup is local.
python3 - "$PROJECT_ROOT" "$KEYSTORE" "$BUILD_DIR" <<'PY'
from pathlib import Path
import sys
root, key, build = (Path(x).resolve() for x in sys.argv[1:])
if key == root or root in key.parents:
    raise SystemExit('Signing keys must be outside this source repository')
if build != root / '.build' / 'app':
    raise SystemExit('Refusing build directory outside the source build cache')
PY
if [[ "${1:-}" == --check ]]; then
  printf 'Toolchain and external signing configuration checks passed; nothing built.\n'
  exit 0
fi
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/generated" "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$(dirname "$OUTPUT_APK")"
printf 'Compiling Android resources...\n'
"$BUILD_TOOLS/aapt2" compile --dir "$APP_MAIN/res" -o "$BUILD_DIR/resources.zip"
LINK_ARGS=(link -o "$BUILD_DIR/resources.apk" --manifest "$APP_MAIN/AndroidManifest.xml"
  -I "$ANDROID_JAR" --java "$BUILD_DIR/generated" --min-sdk-version 26 --target-sdk-version 35)
[[ ! -d "$APP_MAIN/assets" ]] || LINK_ARGS+=(-A "$APP_MAIN/assets")
"$BUILD_TOOLS/aapt2" "${LINK_ARGS[@]}" "$BUILD_DIR/resources.zip"
SOURCES=()
while IFS= read -r -d '' source; do SOURCES+=("$source"); done < <(find "$APP_MAIN/java" "$BUILD_DIR/generated" -name '*.java' -type f -print0)
[[ ${#SOURCES[@]} -gt 0 ]] || { printf 'No Java sources.\n' >&2; exit 1; }
printf 'Compiling %s Java source files...\n' "${#SOURCES[@]}"
"$JAVA_HOME/bin/javac" -encoding UTF-8 --release 8 -classpath "$ANDROID_JAR" -d "$BUILD_DIR/classes" "${SOURCES[@]}"
"$JAVA_HOME/bin/jar" cf "$BUILD_DIR/classes.jar" -C "$BUILD_DIR/classes" .
"$BUILD_TOOLS/d8" --min-api 26 --lib "$ANDROID_JAR" --output "$BUILD_DIR/dex" "$BUILD_DIR/classes.jar"
cp "$BUILD_DIR/resources.apk" "$BUILD_DIR/unsigned.apk"
(cd "$BUILD_DIR/dex" && zip -q "$BUILD_DIR/unsigned.apk" classes*.dex)
"$BUILD_TOOLS/zipalign" -f -p 4 "$BUILD_DIR/unsigned.apk" "$BUILD_DIR/aligned.apk"
if [[ "$CUSTOM_SIGNING" == 0 && ! -f "$KEYSTORE" ]]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  chmod 700 "$(dirname "$KEYSTORE")"
  (umask 077; "$JAVA_HOME/bin/keytool" -genkeypair -noprompt -keystore "$KEYSTORE" \
    -storetype PKCS12 -storepass:env NGAAA_STORE_PASSWORD -keypass:env NGAAA_KEY_PASSWORD \
    -alias "$SIGNING_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
    -dname 'CN=NGAAA Local Development,OU=Development,O=NGAAA,C=CN')
fi
"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$SIGNING_ALIAS" \
  --ks-pass env:NGAAA_STORE_PASSWORD --key-pass env:NGAAA_KEY_PASSWORD --out "$OUTPUT_APK" "$BUILD_DIR/aligned.apk"
"$BUILD_TOOLS/apksigner" verify --verbose "$OUTPUT_APK"
"$BUILD_TOOLS/zipalign" -c -p 4 "$OUTPUT_APK"
python3 - "$OUTPUT_APK" <<'PY'
from pathlib import Path
import hashlib, sys
path = Path(sys.argv[1]).resolve()
print('Built and verified:', path)
print('SHA-256:', hashlib.sha256(path.read_bytes()).hexdigest())
PY
