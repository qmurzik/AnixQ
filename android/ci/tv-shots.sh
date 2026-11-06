#!/bin/bash
# Screenshots of every TV screen on the emulator (run by .github/workflows/android-tv.yml).
set +e
mkdir -p shots
adb install -r AnixQ-TV.apk
P=ru.qmods.anixq.tv; A=$P/.MainActivity; S=14421--kimetsu-no-yaiba-anime
shot() { sleep "$2"; adb exec-out screencap -p > "shots/$1.png"; echo "shot $1"; }
key() { for k in "$@"; do adb shell input keyevent "$k"; sleep 0.6; done; }
launch() { adb shell am force-stop $P; adb shell am start -W -n $A "$@"; }

launch;                       shot 01-home 25
key DPAD_DOWN;                shot 02-home-focus 4
launch -e tab 1;              shot 03-catalog 15
launch -e tab 2;              shot 04-search 10
key DPAD_CENTER DPAD_RIGHT DPAD_CENTER DPAD_DOWN DPAD_CENTER; shot 05-search-typed 10
launch -e tab 4;              shot 06-settings 6
key DPAD_CENTER;              shot 07-pair 8
launch -e slug $S;            shot 08-title 15
key DPAD_DOWN DPAD_DOWN;      shot 09-title-episodes 4
launch -e slug $S --ez play true; shot 10-player 40
key DPAD_UP;                  shot 11-player-controls 3
key BACK DPAD_DOWN;           shot 12-player-episodes 3
adb logcat -d > shots/logcat.txt
exit 0
