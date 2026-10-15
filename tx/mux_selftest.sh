#!/bin/sh
# TX mux check without the C3: MuxSelfTest in the debug app (installDebug first) -> build/z1/NAME.ts, then tx/avsync.py.
#   tx/mux_selftest.sh TS_BPS SECONDS NAME.ts AUDIO_BPS LEAD_S     e.g. tx/mux_selftest.sh 58000 30 st40.ts 12000 1.4
D=/sdcard/Android/data/com.ok1cdj.atvrx/files
OUT="$(dirname "$0")/../build/z1"; mkdir -p "$OUT"; cd "$OUT"
adb shell am force-stop com.ok1cdj.atvrx
adb shell am start -n com.ok1cdj.atvrx/.MuxSelfTestActivity --es args "'$*'" >/dev/null
sleep "$2"
until adb shell cat "$D/$3.log" 2>/dev/null | grep -q DONE; do sleep 2; done
adb shell cat "$D/$3.log" | grep -v DONE | sed 's/: lip sync.*//'
adb pull "$D/$3" . >/dev/null && python3 ../../tx/avsync.py "$3" 2>&1 | tail -1
