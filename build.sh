#!/usr/bin/env bash
# Builds build/m3home.apk with the bare Android SDK tools (no Gradle, no libraries).
# Needs: a JDK 17+, an Android SDK with any platforms;android-NN and build-tools.
# Usage: ./build.sh            (run from Git Bash on Windows, or any bash)
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

VERSION_CODE=19
VERSION_NAME=1.11.3
MIN_SDK=28     # Fire OS 7 (Android 9) and newer

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-${LOCALAPPDATA:-}/Android/Sdk}}
PLATFORM=$(ls -d "$SDK"/platforms/android-* 2>/dev/null | sort -V | tail -1)
BT=$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)
JAR=$PLATFORM/android.jar
[[ -f $JAR ]] || { echo "error: no android.jar under $SDK/platforms" >&2; exit 1; }
[[ -d $BT ]]  || { echo "error: no build-tools under $SDK" >&2; exit 1; }
AAPT2=$BT/aapt2; [[ -x $AAPT2 ]] || AAPT2=$BT/aapt2.exe
ZIPALIGN=$BT/zipalign; [[ -x $ZIPALIGN ]] || ZIPALIGN=$BT/zipalign.exe
echo "sdk: $(basename "$PLATFORM"), build-tools $(basename "$BT")"

# Start clean, but keep build/wallpapers (the screensaver bundle for the wallpapers-v1 release).
find build -mindepth 1 -maxdepth 1 ! -name wallpapers -exec rm -rf {} + 2>/dev/null || true
mkdir -p build/gen build/classes build/dex

echo "resources"
"$AAPT2" compile --dir res -o build/res.zip
"$AAPT2" link -o build/unsigned.apk -I "$JAR" \
  --manifest AndroidManifest.xml \
  --min-sdk-version "$MIN_SDK" --target-sdk-version "$MIN_SDK" \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --java build/gen build/res.zip

echo "java"
SEP=":"; case "${OSTYPE:-}" in msys*|cygwin*|win*) SEP=";" ;; esac
LIBS=$(ls libs/*.jar 2>/dev/null || true)   # libs/zxing-core (QR codes), Apache 2.0
CP="$JAR"; for l in $LIBS; do CP="$CP$SEP$l"; done
javac -encoding UTF-8 --release 11 -Xlint:-options -nowarn \
  -classpath "$CP" -d build/classes \
  $(find src build/gen -name '*.java')

echo "dex"
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --release --min-api "$MIN_SDK" --lib "$JAR" --output build/dex \
  $(find build/classes -name '*.class') $LIBS

echo "package"
cp build/unsigned.apk build/with-dex.apk
jar uf build/with-dex.apk -C build/dex classes.dex
# Assets go in with jar, not aapt2 -A: on Windows aapt2 stores them with backslashes
# (assets/fonts\x.ttf), which Android can't find. jar always writes forward slashes.
jar uf build/with-dex.apk assets
"$ZIPALIGN" -f -p 4 build/with-dex.apk build/aligned.apk

# Your signing key. Keep m3home.keystore and keystore.properties safe and private (both are
# git-ignored): updates only install over an app signed with the same key.
# The password comes from $M3_KEYSTORE_PASS, else keystore.properties; a first build makes both.
KS_PASS=${M3_KEYSTORE_PASS:-}
if [[ -z $KS_PASS && -f keystore.properties ]]; then
  KS_PASS=$(sed -n 's/^password=//p' keystore.properties | tr -d '\r')
fi
if [[ ! -f m3home.keystore ]]; then
  if [[ -z $KS_PASS ]]; then
    KS_PASS=$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')
    printf 'password=%s\n' "$KS_PASS" > keystore.properties
    echo "created a new signing key; its password is in keystore.properties (back both up)"
  fi
  keytool -genkeypair -keystore m3home.keystore -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias m3home -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=MaterialTV" >/dev/null
fi
[[ -n $KS_PASS ]] || { echo "error: set M3_KEYSTORE_PASS or create keystore.properties (password=...)" >&2; exit 1; }
export M3_KS_PASS=$KS_PASS   # handed to apksigner via the environment, not the command line
java -jar "$BT/lib/apksigner.jar" sign --ks m3home.keystore --ks-pass env:M3_KS_PASS \
  --ks-key-alias m3home --out build/m3home.apk build/aligned.apk
java -jar "$BT/lib/apksigner.jar" verify build/m3home.apk
echo "built: build/m3home.apk ($(wc -c < build/m3home.apk) bytes)"
