#!/usr/bin/env bash
# Installs the debug APK on the emulator and walks the main path: welcome screen, try the demo,
# open a source, swipe a song, visit every tab, then close and reopen. Fails on any crash.
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
  bounds=$(adb exec-out cat /sdcard/ui.xml | tr '>' '\n' | grep -m1 -F "text=\"$1" | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
  [ -z "$bounds" ] && { echo "::warning::'$1' not on screen"; return 1; }
  set -- $bounds
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

# Like tap_text, but scrolls down (up to 4 times) until the element shows up.
tap_scrolling() {
  for _ in 1 2 3 4 5; do
    tap_text "$1" && return 0
    SIZE=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)
    W=${SIZE%x*}; H=${SIZE#*x}
    adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) 300
    sleep 1
  done
  return 1
}

adb install -r "$APK"
adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity"
check "launch" 15

tap_scrolling "Probar sin cuenta" || fail "Probar sin cuenta not on screen"; check "try the demo"
tap_scrolling "Me gusta" || fail "Me gusta not on screen"; check "open Me gusta" 10

SIZE=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)
W=${SIZE%x*}; H=${SIZE#*x}
adb shell input swipe $((W / 2)) $((H / 2)) $((W / 2)) $((H / 5)) 300
check "swipe up"
adb shell input tap $((W / 2)) $((H / 2))
check "tap card"
tap_text "Varias" || fail "Varias not on screen"; check "send to several"
adb exec-out screencap -p > smoke-multi.png
adb shell input keyevent KEYCODE_BACK
sleep 1

adb exec-out screencap -p > smoke-swipe.png
adb shell input keyevent KEYCODE_BACK
check "back to home"
adb exec-out screencap -p > smoke-home.png
tap_text "Conexiones" || fail "Conexiones not on screen"; check "connections tab"
adb exec-out screencap -p > smoke-connections.png
tap_text "Ajustes" || fail "Ajustes not on screen"; check "settings tab"
adb exec-out screencap -p > smoke-settings.png
tap_scrolling "DESTINOS" || fail "DESTINOS not on screen"; check "destinations editor"
adb shell input keyevent KEYCODE_BACK
tap_text "Transferir" || fail "Transferir not on screen"; check "transfer tab"
tap_text "Inicio" || fail "Inicio not on screen"; check "home tab"

adb exec-out screencap -p > smoke-screen.png
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/.MainActivity"
check "relaunch" 12
adb logcat -d > logcat.txt
echo "App works on API $API"
