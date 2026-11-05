#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build/tests
javac -encoding UTF-8 -d build/tests board/src/com/zhb/petscreen/OfflineCommands.java ../imx7d-pet-screen/src/com/zhb/petscreen/HomeworkPlanner.java ../imx7d-pet-screen/src/com/zhb/petscreen/ExpressionAction.java tests/OfflineCommandsTest.java
java -cp build/tests com.zhb.petscreen.OfflineCommandsTest
