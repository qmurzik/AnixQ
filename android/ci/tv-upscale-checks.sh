#!/bin/bash
set -euo pipefail
mkdir -p upscale-checks
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c
adb shell am instrument -w -r -e class ru.qmods.anixq.tv.upscale.UpscaleGpuTest,ru.qmods.anixq.tv.upscale.UpscalePlaybackTest ru.qmods.anixq.tv.test/androidx.test.runner.AndroidJUnitRunner | tee upscale-checks/instrumentation.txt
adb logcat -d > upscale-checks/logcat.txt
mkdir -p upscale-checks/frames
adb exec-out run-as ru.qmods.anixq.tv tar -C files/upscale-checks -cf - . > upscale-checks/frames.tar
if [ -s upscale-checks/frames.tar ]; then tar -xf upscale-checks/frames.tar -C upscale-checks/frames; fi
python3 - <<'PY'
from pathlib import Path
text=Path('upscale-checks/instrumentation.txt').read_text()
assert 'OK (6 tests)' in text and 'FAILURES' not in text and 'INSTRUMENTATION_FAILED' not in text, text[-4000:]
PY
