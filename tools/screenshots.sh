#!/usr/bin/env bash
# Installiert die APK im Emulator, öffnet die wichtigsten Seiten und macht Screenshots.
# Aufruf: tools/screenshots.sh <apk> <ausgabeordner>
set -x
APK="$1"
OUT="$2"
PKG=de.abilas.gxtube
mkdir -p "$OUT"

# Systemdialoge (z. B. "Pixel Launcher isn't responding" im langsamen Emulator) ausblenden
adb shell settings put global hide_error_dialogs 1 || true
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS || true

adb install -r "$APK"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
adb logcat -c || true

shot() {
  sleep "${2:-8}"
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1 || true
  sleep 1
  adb exec-out screencap -p > "$OUT/$1.png"
}
route() { adb shell am start -n "$PKG/.MainActivity" --es gxtube_route "$1"; }
link() { adb shell am start -a android.intent.action.VIEW -d "$1" "$PKG"; }

adb shell am start -n "$PKG/.MainActivity"
shot 01_startseite 30
adb shell input swipe 540 1700 540 700 400
shot 02_startseite_gescrollt 6
route shorts
shot 03_shorts 25
route subscriptions
shot 04_abos 6
route you
shot 05_du 5
link "https://www.youtube.com/results?search_query=minecraft"
shot 06_suche 15
link "https://www.youtube.com/watch?v=jNQXAC9IVRw"
shot 07_video 25
adb shell input tap 540 330
shot 08_video_bedienung 2
adb shell input keyevent KEYCODE_BACK
shot 09_miniplayer 5
link "https://www.youtube.com/channel/UCLA_DiR1FfKNvjuUpBHmylQ"
shot 10_kanal 15
route settings
shot 11_einstellungen 4
adb shell cmd uimode night yes
route home
shot 12_dunkel 10

adb logcat -d > "$OUT/logcat_full.txt" || true
grep -E "gxtube|AndroidRuntime|PlayerController|StreamResolver|ExoPlayer|MediaCodec|FATAL" "$OUT/logcat_full.txt" | tail -800 > "$OUT/logcat.txt" || true
rm -f "$OUT/logcat_full.txt"
ls -la "$OUT"
