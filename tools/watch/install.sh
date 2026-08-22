#!/usr/bin/env bash
# Build the debug APK and install it on the connected watch.
set -eu
cd "$(dirname "$0")/../.."
bash tools/watch/connect.sh
./gradlew :app:assembleDebug -q
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm list packages | grep -q com.emotiveautomaton.wristwork && echo "INSTALLED: com.emotiveautomaton.wristwork"
