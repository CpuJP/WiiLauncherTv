#!/usr/bin/env bash
# Usage: tools/yt-play.sh "search terms"   Opens YouTube search on the TV and plays the first result with real key presses.
set -e
cd "$(dirname "$0")/.."
T=tools/tv
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb} -s ${TV:-192.168.80.21:5555}"
Q="${1:?search terms}"
$T shell input keyevent KEYCODE_WAKEUP
$T shell am start -a android.intent.action.VIEW -d "https://www.youtube.com/results?search_query=${Q// /+}" com.google.android.youtube.tv
for i in $(seq 1 15); do
  sleep 1
  ui=$($ADB exec-out uiautomator dump /dev/tty 2>/dev/null || true)
  # "Who's watching?" profile picker: pick the focused (first) profile
  if grep -qiE "Quién está viendo|Who's watching" <<<"$ui"; then echo "profile picker -> OK"; $T shell input keyevent KEYCODE_DPAD_CENTER; continue; fi
  if grep -qiE "Resultados de la búsqueda|Search results" <<<"$ui"; then break; fi
done
$T shell input keyevent KEYCODE_DPAD_CENTER
sleep 6
$ADB shell dumpsys media_session | grep -A12 "package=com.google.android.youtube.tv" | grep -q "state=3" && echo "PLAYING" || { echo "not playing yet"; exit 1; }
