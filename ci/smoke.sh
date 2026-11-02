#!/usr/bin/env bash
# Installs the debug APK on the emulator and walks the main path: open the app, switch to the
# Demo service, open a source, swipe a song, then close and reopen. Fails on any crash.
set -u
APK=app/build/outputs/apk/debug/app-debug.apk
PKG=com.slidedify.app
API=$(adb shell getprop ro.build.version.sdk | tr -d '\r')

fail() {
  echo "::error::(API $API) $1"
  adb logcat -d > logcat.txt
  grep -A60 "FATAL EXCEPTION" logcat.txt || grep -iE "AndroidRuntime|$PKG" logcat.txt | tail -80
  adb exec-out screencap -p > smoke-screen.png
  exit 1
}

check() {
  sleep "${2:-6}"
  adb logcat -d > logcat.txt
  grep -q "FATAL EXCEPTION" logcat.txt && fail "crash after: $1"
  adb shell pidof "$PKG" > /dev/null || fail "app not running after: $1"
  echo "ok: $1"
}

# Taps the centre of the first on-screen element whose text matches $1.
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  local bounds
  bounds=$(adb exec-out cat /sdcard/ui.xml | tr '>' '\n' | grep -m1 "text=\"$1\"" | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
  [ -z "$bounds" ] && { echo "::warning::'$1' not on screen"; return 1; }
  set -- $bounds
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

adb install -r "$APK"
adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity"
check "launch" 15

tap_text "Demo" && check "switch to Demo"
tap_text "Me gusta" && check "open Me gusta" 10

SIZE=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)
W=${SIZE%x*}; H=${SIZE#*x}
adb shell input swipe $((W / 2)) $((H / 2)) $((W / 2)) $((H / 5)) 300
check "swipe up"
adb shell input tap $((W / 2)) $((H / 2))
check "tap card"

adb exec-out screencap -p > smoke-screen.png
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/.MainActivity"
check "relaunch" 12
adb logcat -d > logcat.txt
echo "App works on API $API"
