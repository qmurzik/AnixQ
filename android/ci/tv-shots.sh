#!/bin/bash
# Actual release APK, live services and remote keys. No injected accounts or catalogue fixtures.
set -euo pipefail
mkdir -p shots
adb install -r AnixQ-TV.apk
P=ru.qmods.anixq.tv
A=$P/.MainActivity
S=14421--kimetsu-no-yaiba-anime
shot() { sleep "$2"; adb exec-out screencap -p > "shots/$1.png"; test -s "shots/$1.png"; }
key() { for k in "$@"; do adb shell input keyevent "$k"; sleep 0.5; done; }
launch() { adb shell am force-stop "$P"; adb shell am start -W -n "$A" "$@"; }
adb logcat -c
launch --es ui classic;                       shot 01-classic-home 22
launch --es ui classic --es tab 5;            shot 02-classic-settings 5
launch --es ui panorama;                      shot 10-panorama-home 22
key DPAD_LEFT;                               shot 11-panorama-navigation 2
key DPAD_DOWN DPAD_CENTER DPAD_RIGHT;         shot 12-panorama-catalog-remote 12
launch --es ui panorama --es tab 2;           shot 13-panorama-search 7
launch --es ui panorama -a android.intent.action.SEARCH --es query Naruto; shot 14-panorama-search-results 15
launch --es ui panorama --es tab 3;           shot 15-panorama-library 5
launch --es ui panorama --es tab 4;           shot 16-panorama-schedule 15
launch --es ui panorama --es tab 5;           shot 17-panorama-settings 5
launch --es ui panorama --es slug "$S";      shot 18-panorama-title 18
key DPAD_DOWN DPAD_DOWN;                     shot 19-panorama-title-episodes 3
# Capture real transitions, focus movement and rail expansion, not a generated animation.
launch --es ui panorama
sleep 10
adb shell screenrecord --time-limit 35 --bit-rate 5000000 /sdcard/panorama-navigation.mp4 &
RECORD_PID=$!
sleep 2
key DPAD_LEFT DPAD_DOWN DPAD_CENTER DPAD_RIGHT
sleep 3
key DPAD_DOWN DPAD_RIGHT DPAD_RIGHT DPAD_LEFT BACK
sleep 2
key DPAD_DOWN DPAD_CENTER DPAD_RIGHT
sleep 3
key BACK DPAD_DOWN DPAD_CENTER DPAD_RIGHT
wait "$RECORD_PID" || true
adb pull /sdcard/panorama-navigation.mp4 shots/panorama-navigation.mp4
adb logcat -d > shots/logcat.txt
adb logcat -d -s AnixQ AndroidRuntime > shots/app-log.txt
if rg -q 'FATAL EXCEPTION' shots/app-log.txt; then
  echo 'Android runtime failure captured; inspect app-log.txt'
  exit 1
fi
