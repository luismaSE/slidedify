#!/usr/bin/env bash
# Installs the debug APK on the emulator, opens it and fails if it crashes, printing the stack trace.
set -u
APK=app/build/outputs/apk/debug/app-debug.apk
PKG=com.slidedify.app
adb install -r "$APK"
adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity"
sleep 20
adb logcat -d > logcat.txt
if grep -q "FATAL EXCEPTION" logcat.txt; then
  echo "::error::The app crashed on start"
  grep -A60 "FATAL EXCEPTION" logcat.txt
  exit 1
fi
if ! adb shell pidof "$PKG" > /dev/null; then
  echo "::error::The app is not running after start"
  grep -iE "AndroidRuntime|$PKG" logcat.txt | tail -80
  exit 1
fi
adb exec-out screencap -p > smoke-screen.png
echo "App started fine"
