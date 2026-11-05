#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
sdk_dir=${ANDROID_HOME:-"$HOME/Library/Android/sdk"}
ndk_dir=${ANDROID_NDK_HOME:-"$sdk_dir/ndk/28.2.13676358"}
case $(uname -s) in Darwin) host_tag=darwin-x86_64;; Linux) host_tag=linux-x86_64;; *) exit 2;; esac
cc="$ndk_dir/toolchains/llvm/prebuilt/$host_tag/bin/armv7a-linux-androideabi27-clang"
mkdir -p build/sherpa-compare/lib
unzip -jo ../../demo/android-voice-weather/app/libs/sherpa-onnx-1.13.8.aar 'jni/armeabi-v7a/libonnxruntime.so' 'jni/armeabi-v7a/libsherpa-onnx-c-api.so' -d build/sherpa-compare/lib
for name in asr_compare kws_compare paraformer_compare vad_compare kws_vad_compare hello_compare hello_pronunciation_compare latency_compare endpoint_compare; do
 "$cc" -Wall -Wextra -Werror -O2 -fPIE -pie "native/$name.c" -L build/sherpa-compare/lib -lsherpa-onnx-c-api -o "build/sherpa-compare/$name"
done
mkdir -p build/voice-deps/lib/armeabi-v7a
"$cc" -Wall -Wextra -Werror -O2 -fPIC -shared native/sherpa_engine.c -L build/sherpa-compare/lib -lsherpa-onnx-c-api -Wl,-soname,libaluo-sherpa.so -o build/voice-deps/lib/armeabi-v7a/libaluo-sherpa.so
