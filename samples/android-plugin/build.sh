#!/usr/bin/env bash
# Builds build/sample-plugin.apk, a MaterialTV Android plugin, with the bare SDK tools (no Gradle).
# Needs: a JDK 17+, an Android SDK with a platforms;android-NN and build-tools.
# Usage: ./build.sh   then   adb install -r build/sample-plugin.apk
# It's signed with its own throwaway key (sample.keystore, made on the first build).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-${LOCALAPPDATA:-}/Android/Sdk}}
PLATFORM=$(ls -d "$SDK"/platforms/android-* 2>/dev/null | sort -V | tail -1)
BT=$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)
JAR=$PLATFORM/android.jar
[[ -f $JAR ]] || { echo "error: no android.jar under $SDK/platforms" >&2; exit 1; }
AAPT2=$BT/aapt2; [[ -x $AAPT2 ]] || AAPT2=$BT/aapt2.exe
ZIPALIGN=$BT/zipalign; [[ -x $ZIPALIGN ]] || ZIPALIGN=$BT/zipalign.exe

rm -rf build && mkdir -p build/gen build/classes build/dex
"$AAPT2" link -o build/unsigned.apk -I "$JAR" --manifest AndroidManifest.xml \
  --min-sdk-version 28 --target-sdk-version 28 --version-code 1 --version-name 1.0 --java build/gen
javac -encoding UTF-8 --release 11 -nowarn -classpath "$JAR" -d build/classes $(find src build/gen -name '*.java')
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 28 --lib "$JAR" \
  --output build/dex $(find build/classes -name '*.class')
cp build/unsigned.apk build/with-dex.apk
jar uf build/with-dex.apk -C build/dex classes.dex
"$ZIPALIGN" -f -p 4 build/with-dex.apk build/aligned.apk

[[ -f sample.keystore ]] || keytool -genkeypair -keystore sample.keystore -storepass sample-plugin -keypass sample-plugin \
  -alias sample -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=MaterialTV sample plugin" >/dev/null
java -jar "$BT/lib/apksigner.jar" sign --ks sample.keystore --ks-pass pass:sample-plugin \
  --ks-key-alias sample --out build/sample-plugin.apk build/aligned.apk
echo "built: build/sample-plugin.apk"
