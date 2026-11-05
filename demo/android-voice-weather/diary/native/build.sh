#!/bin/sh
set -eu
native_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
sdk_dir=${ANDROID_SDK_ROOT:-"$HOME/Library/Android/sdk"}
ndk_dir=${ANDROID_NDK_HOME:-"$sdk_dir/ndk/28.2.13676358"}
case $(uname -s) in Darwin) host_tag=darwin-x86_64;; Linux) host_tag=linux-x86_64;; *) echo 'Unsupported build host' >&2; exit 2;; esac
cc="$ndk_dir/toolchains/llvm/prebuilt/$host_tag/bin/armv7a-linux-androideabi27-clang"
[ -x "$cc" ] || { echo "Missing Android NDK compiler: $cc" >&2; exit 2; }
mkdir -p "$native_dir/build/lib"
unzip -jo "$native_dir/../../app/libs/sherpa-onnx-1.13.8.aar" 'jni/armeabi-v7a/libonnxruntime.so' 'jni/armeabi-v7a/libsherpa-onnx-c-api.so' -d "$native_dir/build/lib"
"$cc" -Wall -Wextra -Werror -O2 -fPIE -pie "$native_dir/offline_speech.c" -L "$native_dir/build/lib" -lsherpa-onnx-c-api -lm -Wl,-rpath,'$ORIGIN/lib' -o "$native_dir/build/nxp-offline-speech"
