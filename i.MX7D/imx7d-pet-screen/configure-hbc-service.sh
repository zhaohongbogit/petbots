#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
base="${1:?Usage: bash configure-hbc-service.sh <test-agent-base> [device-serial]}"
[[ "$base" =~ ^https?://[A-Za-z0-9._:/-]+$ ]] || { echo "Invalid service URL" >&2; exit 1; }
device="${2:-000000c4cddac462}"
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
adb="$sdk/platform-tools/adb"
[ -f build/aluotest/test.apk ] || { echo 'Build tests/device/build.sh first.' >&2; exit 1; }
"$adb" -s "$device" install -r build/aluotest/test.apk
"$adb" -s "$device" shell am force-stop com.zhb.petscreen
result=$("$adb" -s "$device" shell am instrument -w -e baseUrl "$base" com.zhb.petscreen.aluotest/cn.aihongbo.voiceweather.HbcProvisionTest)
printf "%s\n" "$result"
[[ "$result" == *"PASS: private endpoint provisioned."* ]] || exit 1
"$adb" -s "$device" shell am start -W -n com.zhb.petscreen/.ChatActivity
