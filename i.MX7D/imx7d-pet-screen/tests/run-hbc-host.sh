#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
json="${HBC_JSON_JAR:-$HOME/.m2/repository/org/json/json/20210307/json-20210307.jar}"
[ -f "$json" ] || { echo 'Set HBC_JSON_JAR to an org.json implementation JAR.' >&2; exit 1; }
mkdir -p build/hbc-host-tests build/hbc-host-stubs/android/util
cat > build/hbc-host-stubs/android/util/Log.java <<'JAVA'
package android.util;public final class Log { public static int i(String tag,String message){return 0;} }
JAVA
javac -encoding UTF-8 -cp "$json:$sdk/platforms/android-29/android.jar" -d build/hbc-host-tests build/hbc-host-stubs/android/util/Log.java src/cn/aihongbo/voiceweather/HbcAgentClient.java src/cn/aihongbo/voiceweather/HbcFixedLogin.java src/cn/aihongbo/voiceweather/HbcTravelSession.java src/cn/aihongbo/voiceweather/HbcVoiceQuote.java tests/cn/aihongbo/voiceweather/HbcQuoteFlowTest.java
java -cp "build/hbc-host-tests:$json:$sdk/platforms/android-29/android.jar" cn.aihongbo.voiceweather.HbcQuoteFlowTest
