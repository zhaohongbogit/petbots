#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
bt="$sdk/build-tools/35.0.0"
jar="$sdk/platforms/android-29/android.jar"
mkdir -p build/classes build/dex
javac -source 8 -target 8 -encoding UTF-8 -classpath "$jar" -d build/classes src/com/zhb/petscreen/*.java
"$bt/d8" --min-api 27 --lib "$jar" --output build/dex build/classes/com/zhb/petscreen/*.class
"$bt/aapt" package -f -M AndroidManifest.xml -I "$jar" -F build/unsigned.apk
(cd build/dex && zip -q -j ../unsigned.apk classes.dex)
"$bt/zipalign" -f 4 build/unsigned.apk build/aligned.apk
if [ ! -f build/test.keystore ]; then
    openssl rand -hex 24 > build/signing-pass
    chmod 600 build/signing-pass
    keytool -genkeypair -keystore build/test.keystore -storepass:file build/signing-pass -keypass:file build/signing-pass -alias test -keyalg RSA -validity 3650 -dname "CN=Pet Screen Local Test" >/dev/null 2>&1
fi
"$bt/apksigner" sign --ks build/test.keystore --ks-pass file:build/signing-pass --out build/pet-screen.apk build/aligned.apk
"$bt/apksigner" verify build/pet-screen.apk
