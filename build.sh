#!/usr/bin/env bash
# Gradle-free APK build: aapt2 -> javac -> d8 -> zipalign -> apksigner.
#
# Required env:
#   JAVA_HOME          JDK 17
#   ANDROID_SDK_ROOT   Android SDK with build-tools;$BUILD_TOOLS and platforms;android-34
#   KEYSTORE           path to your signing keystore (see README)
#   APP_ID             Android application ID, e.g. io.github.<user>.dvrviewer
#                      (asked for interactively if unset and stdin is a terminal)
# Optional env:
#   KEY_ALIAS   (default androiddebugkey)
#   KS_PASS     keystore password (default android)
#   KEY_PASS    key password (default $KS_PASS)
#   BUILD_TOOLS build-tools version (default 34.0.0)
#   OUT_DIR     intermediates dir (default ./out)
#   APK_OUT     output APK (default ./DvrCam.apk)
set -euo pipefail

PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
source "$PROJ/env.sh"

die() { echo "build.sh: $*" >&2; exit 1; }

[ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ] || die "set JAVA_HOME to a JDK 17."
[ -n "${ANDROID_SDK_ROOT:-}" ] || die "set ANDROID_SDK_ROOT to your Android SDK."

BUILD_TOOLS="${BUILD_TOOLS:-34.0.0}"
BT="$ANDROID_SDK_ROOT/build-tools/$BUILD_TOOLS"
AJAR="$ANDROID_SDK_ROOT/platforms/android-34/android.jar"
[ -x "$BT/aapt2" ] || die "missing $BT (install with: sdkmanager \"build-tools;$BUILD_TOOLS\")."
[ -f "$AJAR" ]     || die "missing $AJAR (install with: sdkmanager \"platforms;android-34\")."

[ -n "${KEYSTORE:-}" ] || die "set KEYSTORE to your signing keystore (README shows how to make one)."
[ -f "$KEYSTORE" ]     || die "KEYSTORE file not found: $KEYSTORE"
export KS_PASS="${KS_PASS:-android}"
export KEY_PASS="${KEY_PASS:-$KS_PASS}"
KEY_ALIAS="${KEY_ALIAS:-androiddebugkey}"

if [ -z "${APP_ID:-}" ]; then
  if [ -t 0 ]; then
    read -r -p "Application ID (e.g. io.github.<user>.dvrviewer): " APP_ID
  else
    die "set APP_ID to your application ID, e.g. APP_ID=io.github.<user>.dvrviewer"
  fi
fi
# Java-identifier segments, at least one dot (Android rule: each segment starts with a letter).
[[ "$APP_ID" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$ ]] \
  || die "APP_ID '$APP_ID' is not valid: use dot-separated segments of letters, digits, '_' (each starts with a letter), with at least one dot."
echo "Application ID: $APP_ID"

OUT="${OUT_DIR:-$PROJ/out}"
APK="${APK_OUT:-$PROJ/DvrCam.apk}"
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex" "$(dirname "$APK")"

echo "[1/6] aapt2 compile resources + link (manifest + res -> base apk)"
"$BT/aapt2" compile --dir "$PROJ/res" -o "$OUT/res.zip"
"$BT/aapt2" link \
    -o "$OUT/base.apk" \
    -I "$AJAR" \
    --manifest "$PROJ/AndroidManifest.xml" \
    --rename-manifest-package "$APP_ID" \
    --min-sdk-version 24 --target-sdk-version 34 \
    --version-code 1 --version-name 1.0 \
    "$OUT/res.zip"

echo "[2/6] javac"
"$JAVA_HOME/bin/javac" -source 8 -target 8 -nowarn -encoding UTF-8 \
    -bootclasspath "$AJAR" -classpath "$AJAR" \
    -d "$OUT/classes" \
    $(find "$PROJ/src" -name '*.java') 2>&1 | grep -v 'bootstrap class path' || true
[ -n "$(find "$OUT/classes" -name '*.class' -print -quit)" ] || die "javac produced no classes."

echo "[3/6] d8 (classes -> dex)"
"$BT/d8" --release --lib "$AJAR" --min-api 24 \
    --output "$OUT/dex" \
    $(find "$OUT/classes" -name '*.class')

echo "[4/6] add classes.dex into apk"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
cp "$OUT/dex/classes.dex" "$OUT/classes.dex"
python3 -I - "$OUT/unsigned.apk" "$OUT/classes.dex" <<'PY'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
print("   classes.dex added")
PY

echo "[5/6] zipalign"
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[6/6] sign"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
    --ks-pass env:KS_PASS --key-pass env:KEY_PASS \
    --min-sdk-version 24 \
    --out "$APK" "$OUT/aligned.apk"

echo "---- verify ----"
"$BT/apksigner" verify "$APK"
ls -la "$APK"
"$BT/aapt2" dump badging "$APK" 2>/dev/null | grep -E '^(package|launchable-activity|sdkVersion|targetSdkVersion)'
