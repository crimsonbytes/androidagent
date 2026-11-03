#!/usr/bin/env bash
# Builds the English-translated androidagent APK from this source tree, reusing the
# prebuilt assets and native libraries (Termux runtime, QEMU, Docker guest, USB JNI)
# from the official v0.5.0 release APK. Only the Java code, resources, manifest and
# the translated assets/termux helper files come from this tree.
#
# Usage: tools/build-english.sh <official-androidagent-0.5.0.apk>
# Env:   ANDROID_SDK_ROOT (needs platforms/android-36 and build-tools/35.0.0), JAVA_HOME,
#        KEYSTORE (optional; created if missing), KEYSTORE_PASSWORD (optional).
set -euo pipefail
cd "$(dirname "$0")/.."

BASE_APK=${1:?usage: tools/build-english.sh <official-0.5.0.apk>}
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT}"
BT="$ANDROID_SDK_ROOT/build-tools/35.0.0"
SDK_JAR="$ANDROID_SDK_ROOT/platforms/android-36/android.jar"
KEYSTORE=${KEYSTORE:-$HOME/.androidagent-en-keys/release.jks}
PACKAGE=dev.mateuszsury.androidagent
INFO=app/src/main/java/dev/mateuszsury/androidagent/BuildInfo.java
VERSION_NAME=$(sed -n 's/.*VERSION = "\(.*\)";/\1/p' "$INFO")
VERSION_CODE=$(sed -n 's/.*VERSION_CODE = \([0-9]*\);/\1/p' "$INFO")
SRC=app/src/main
OUT=build-en
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/assets" "$OUT/jniLibs"

echo "1/7 Reusing assets and native libraries from $(basename "$BASE_APK")"
python3 - "$BASE_APK" "$OUT" "$SRC/assets" <<'PY'
import shutil, sys, zipfile
from pathlib import Path
apk, out, src_assets = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
assets, libs = out / 'assets', out / 'jniLibs'
with zipfile.ZipFile(apk) as z:
    for info in z.infolist():
        name = info.filename
        if name.endswith('/'):
            continue
        if name.startswith('assets/'):
            target = assets / name[len('assets/'):]
        elif name.startswith('lib/'):
            target = libs / name[len('lib/'):]
        else:
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(z.read(name))
# The translated helper scripts, agent skill and prompts come from this tree.
termux = src_assets / 'termux'
for f in termux.rglob('*'):
    if f.is_file():
        target = assets / 'termux' / f.relative_to(termux)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(f, target)
print('assets:', sum(1 for p in assets.rglob('*') if p.is_file()),
      'native libs:', sum(1 for p in libs.rglob('*.so')))
PY

echo "2/7 Compiling resources"
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/res.zip"

echo "3/7 Linking resources and manifest"
sed "s|<manifest |<manifest package=\"$PACKAGE\" |" "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
"$BT/aapt2" link -I "$SDK_JAR" --manifest "$OUT/AndroidManifest.xml" \
  --min-sdk-version 30 --target-sdk-version 36 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -A "$OUT/assets" -0 gz -0 zip --java "$OUT/gen" -o "$OUT/base.apk" "$OUT/res.zip"

echo "4/7 Compiling Java"
javac --release 8 -classpath "$SDK_JAR" -Xlint:-options -encoding UTF-8 \
  -d "$OUT/classes" $(find "$SRC/java" "$OUT/gen" -name '*.java')

echo "5/7 Dexing"
"$BT/d8" --release --min-api 30 --lib "$SDK_JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "6/7 Packaging and aligning"
cp "$OUT/base.apk" "$OUT/unaligned.apk"
python3 - "$OUT/unaligned.apk" "$OUT/dex/classes.dex" "$OUT/jniLibs" <<'PY'
import sys, zipfile
from pathlib import Path
with zipfile.ZipFile(sys.argv[1], 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(sys.argv[2], 'classes.dex')
    for f in sorted(Path(sys.argv[3]).glob('*/*.so')):
        z.write(f, 'lib/' + str(f.relative_to(sys.argv[3])), compress_type=zipfile.ZIP_DEFLATED)
PY
python3 tools/zipalign.py "$OUT/unaligned.apk" "$OUT/aligned.apk"

echo "7/7 Signing"
if [ ! -f "$KEYSTORE" ]; then
  echo "Creating signing key at $KEYSTORE"
  mkdir -p "$(dirname "$KEYSTORE")"
  KEYSTORE_PASSWORD=${KEYSTORE_PASSWORD:-$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)}
  keytool -genkeypair -keystore "$KEYSTORE" -alias buddy -keyalg RSA -keysize 4096 -validity 10000 \
    -storepass "$KEYSTORE_PASSWORD" -keypass "$KEYSTORE_PASSWORD" \
    -dname "CN=androidagent English build, O=Personal" >/dev/null 2>&1
fi
: "${KEYSTORE_PASSWORD:?Set KEYSTORE_PASSWORD for an existing keystore}"
APK="$OUT/androidagent-$VERSION_NAME-en.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias buddy --ks-pass "pass:$KEYSTORE_PASSWORD" \
  --out "$APK" "$OUT/aligned.apk"
"$BT/apksigner" verify --print-certs "$APK"
sha256sum "$APK"
echo "Built $APK"
