#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
bt="$sdk/build-tools/35.0.0"
jar="$sdk/platforms/android-29/android.jar"
python3 -c 'import pathlib, shutil; p=pathlib.Path("build/aluotest/classes"); shutil.rmtree(p,ignore_errors=True)'
mkdir -p build/aluotest/classes build/aluotest/dex build/aluotest/extra-assets
javac -source 8 -target 8 -encoding UTF-8 -cp "$jar:build/classes:build/voice-deps/vosk-android.jar:build/voice-deps/jna.jar" -d build/aluotest/classes tests/device/*.java tests/cn/aihongbo/voiceweather/HbcQuoteFlowTest.java
find build/aluotest/classes -name '*.class' > build/aluotest/classes.list
jar cf build/aluotest/main-classes.jar -C build/classes .
"$bt/d8" --min-api 27 --lib "$jar" --classpath build/aluotest/main-classes.jar --classpath build/voice-deps/vosk-android.jar --classpath build/voice-deps/jna.jar --output build/aluotest/dex @build/aluotest/classes.list
"$bt/aapt" package -f -M tests/device/AndroidManifest.xml -I "$jar" -A ../../demo/android-voice-weather/app/src/androidTest/assets -A build/aluotest/extra-assets -F build/aluotest/unsigned.apk
(cd build/aluotest/dex && zip -q -j ../unsigned.apk classes.dex)
"$bt/zipalign" -f 4 build/aluotest/unsigned.apk build/aluotest/aligned.apk
"$bt/apksigner" sign --ks build/test.keystore --ks-pass file:build/signing-pass --out build/aluotest/test.apk build/aluotest/aligned.apk
"$bt/apksigner" verify build/aluotest/test.apk
