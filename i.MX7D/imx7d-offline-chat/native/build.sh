#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
cmake="$sdk/cmake/3.22.1/bin/cmake"
"$cmake" -S native -B build/native -G Ninja -DCMAKE_MAKE_PROGRAM="$sdk/cmake/3.22.1/bin/ninja" -DCMAKE_TOOLCHAIN_FILE="$sdk/ndk/28.2.13676358/build/cmake/android.toolchain.cmake" -DANDROID_ABI=armeabi-v7a -DANDROID_PLATFORM=android-27 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_FLAGS='-mcpu=cortex-a7 -mfpu=neon-vfpv4' -DCMAKE_CXX_FLAGS='-mcpu=cortex-a7 -mfpu=neon-vfpv4'
"$cmake" --build build/native -j 4
