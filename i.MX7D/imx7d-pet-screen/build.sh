#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
bt="$sdk/build-tools/35.0.0"
jar="$sdk/platforms/android-29/android.jar"
python3 -c 'import pathlib, shutil; p=pathlib.Path("build/classes"); shutil.rmtree(p,ignore_errors=True)'
mkdir -p build/classes build/dex
assets=build/sherpa-assets
output=build/pet-screen.apk
if [ "${1:-}" = "--reuse-model" ]; then
    assets=build/sherpa-assets-reuse
    output=build/pet-screen-update.apk
fi
python3 prepare_vosk.py --reuse-model
python3 prepare_sherpa.py "${1:-}"
sh native/build.sh
shared=../../demo/android-voice-weather/app/src/main/java/cn/aihongbo/voiceweather
find src -name '*.java' > build/sources.list
for name in DialogueEngine SemanticDecision CityCatalog WeatherClient; do
    echo "$shared/$name.java" >> build/sources.list
done
javac -source 8 -target 8 -encoding UTF-8 -classpath "$jar:build/voice-deps/vosk-android.jar:build/voice-deps/jna.jar" -d build/classes @build/sources.list
find build/classes -name '*.class' > build/classes.list
"$bt/d8" --min-api 27 --lib "$jar" --output build/dex @build/classes.list build/voice-deps/vosk-android.jar build/voice-deps/jna.jar
"$bt/aapt" package -f -M AndroidManifest.xml -I "$jar" -A "$assets" -F build/unsigned.apk
(cd build/dex && zip -q -j ../unsigned.apk classes.dex)
# API27 can map page-aligned native libraries directly from the APK. Keep only the active engine.
(cd build/voice-deps && zip -q -0 ../unsigned.apk lib/armeabi-v7a/libonnxruntime.so lib/armeabi-v7a/libsherpa-onnx-c-api.so lib/armeabi-v7a/libaluo-sherpa.so)
"$bt/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
if [ ! -f build/test.keystore ]; then
    openssl rand -hex 24 > build/signing-pass
    chmod 600 build/signing-pass
    keytool -genkeypair -keystore build/test.keystore -storepass:file build/signing-pass -keypass:file build/signing-pass -alias test -keyalg RSA -validity 3650 -dname "CN=Pet Screen Local Test" >/dev/null 2>&1
fi
"$bt/apksigner" sign --ks build/test.keystore --ks-pass file:build/signing-pass --out "$output" build/aligned.apk
"$bt/apksigner" verify "$output"
