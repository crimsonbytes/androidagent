#!/usr/bin/env bash
# Captures the README screenshots of the English build on a connected emulator.
# Run tools/build-english.sh first; the instrumentation APK is signed with the same key.
# Usage: tools/english-screenshots.sh [out-dir]   (default: build-en/screenshots)
# Env:   ANDROID_SDK_ROOT, KEYSTORE, KEYSTORE_PASSWORD (same as build-english.sh)
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT}"
BT="$ANDROID_SDK_ROOT/build-tools/35.0.0"
SDK_JAR="$ANDROID_SDK_ROOT/platforms/android-36/android.jar"
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
KEYSTORE=${KEYSTORE:-$HOME/.androidagent-en-keys/release.jks}
: "${KEYSTORE_PASSWORD:?Set KEYSTORE_PASSWORD}"
DEST=${1:-build-en/screenshots}
APK=$(ls build-en/androidagent-*-en.apk | head -1)
OUT=build-en/screenshot-tests
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex"

"$BT/aapt2" link -I "$SDK_JAR" --manifest tests/screenshots/AndroidManifest.xml -o "$OUT/base.apk"
javac --release 8 -classpath "$SDK_JAR:build-en/classes" -encoding UTF-8 -d "$OUT/classes" tests/screenshots/*.java
"$BT/d8" --min-api 30 --lib "$SDK_JAR" --classpath build-en/classes --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
python3 - "$OUT/base.apk" "$OUT/dex/classes.dex" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], 'a', zipfile.ZIP_DEFLATED) as z: z.write(sys.argv[2], 'classes.dex')
PY
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias buddy --ks-pass "pass:$KEYSTORE_PASSWORD" --out "$OUT/screenshots.apk" "$OUT/aligned.apk"

# Same frame as the earlier README screenshots: 720x1480 at 320 dpi, dark theme.
"$ADB" shell wm size 720x1480
"$ADB" shell wm density 320
"$ADB" shell cmd uimode night yes
"$ADB" shell settings put global hide_error_dialogs 1
"$ADB" uninstall dev.mateuszsury.androidagent >/dev/null 2>&1 || true
"$ADB" install --no-incremental "$APK"
"$ADB" install --no-incremental -r "$OUT/screenshots.apk"
"$ADB" shell rm -rf /sdcard/Download/androidagent-screenshots
"$ADB" shell am instrument -w dev.mateuszsury.androidagent.screenshots/dev.mateuszsury.androidagent.EnglishScreenshots | tee "$OUT/instrument.txt"
mkdir -p "$DEST"
"$ADB" pull /sdcard/Download/androidagent-screenshots/. "$DEST/"
ls -l "$DEST"
grep -q 'Screenshots: 0 failures' "$OUT/instrument.txt"
