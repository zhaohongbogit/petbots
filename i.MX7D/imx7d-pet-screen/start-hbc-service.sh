#!/bin/bash
set -euo pipefail
# Run the existing server without editing hbc-agent or enabling its messaging/short-link channels.
backend="${1:-$HOME/Source/hbc-agent}"
jdk="${HBC_JAVA_HOME:-$HOME/Library/Java/JavaVirtualMachines/jbr-21.0.11-1/Contents/Home}"
bundled="$(cd "$(dirname "$0")" && pwd)/build/hbc-service/spring-agent.jar"
jar="${HBC_SERVICE_JAR:-$bundled}"
if [ ! -f "$jar" ]; then jar="$backend/spring-agent/target/spring-agent-0.1.0.jar"; fi
[ -f "$jar" ] || { echo 'Build spring-agent first; expected target JAR is absent.' >&2; exit 1; }
export HBC_ENV_FILE="${HBC_ENV_FILE:-$backend/.env}"
export PORT="${PORT:-8788}" HOST="${HOST:-0.0.0.0}"
export FEISHU_ENABLED=false HBC_SHORT_LINK_ENABLED=false WEB_CHAT_ENABLED=false
export LOG_LEVEL=warn LOG_FILE="${HBC_SERVICE_LOG:-/private/tmp/imx7-hbc-agent.log}"
exec "$jdk/bin/java" -jar "$jar"
