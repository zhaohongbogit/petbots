#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
bt="$sdk/build-tools/35.0.0"
jar="$sdk/platforms/android-29/android.jar"
engine="${OFFLINE_ENGINE:-build/native/imx7-local-chat}"
if [ ! -f "$engine" ]; then
 echo '请先运行 bash native/build.sh，或用 OFFLINE_ENGINE 指定 ARMv7 可执行文件。' >&2
 exit 1
fi
mkdir -p build
if [ ! -f build/test.keystore ]; then
 openssl rand -hex 24 > build/signing-pass
 chmod 600 build/signing-pass
 keytool -genkeypair -keystore build/test.keystore -storepass:file build/signing-pass -keypass:file build/signing-pass -alias test -keyalg RSA -validity 3650 -dname "CN=Xiaoqi Offline Local Test" >/dev/null 2>&1
fi
for target in board phone; do
 mkdir -p "build/$target/classes" "build/$target/dex"
 sources=("$target"/src/com/zhb/*/*.java)
 if [ "$target" = board ]; then sources+=(../imx7d-pet-screen/src/com/zhb/petscreen/*.java); fi
 javac -source 8 -target 8 -encoding UTF-8 -classpath "$jar" -d "build/$target/classes" "${sources[@]}"
 classes=(); while IFS= read -r file; do classes+=("$file"); done < <(find "build/$target/classes" -name '*.class')
 "$bt/d8" --min-api 27 --lib "$jar" --output "build/$target/dex" "${classes[@]}"
 "$bt/aapt" package -f -M "$target/AndroidManifest.xml" -I "$jar" -F "build/$target/unsigned.apk"
 (cd "build/$target/dex" && zip -q -j ../unsigned.apk classes.dex)
 if [ "$target" = board ]; then
  mkdir -p build/board/libs/lib/armeabi-v7a
  cp "$engine" build/board/libs/lib/armeabi-v7a/libimx7_local_chat.so
  "$sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip" build/board/libs/lib/armeabi-v7a/libimx7_local_chat.so
  (cd build/board/libs && zip -q ../unsigned.apk lib/armeabi-v7a/libimx7_local_chat.so)
 fi
 "$bt/zipalign" -f 4 "build/$target/unsigned.apk" "build/$target/aligned.apk"
 "$bt/apksigner" sign --ks build/test.keystore --ks-pass file:build/signing-pass --out "build/$target.apk" "build/$target/aligned.apk"
 "$bt/apksigner" verify "build/$target.apk"
 echo "$target APK: build/$target.apk"
done
