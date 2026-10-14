#!/bin/sh
# Phase F2 on the phone: leandvb + ldpc_tool (NEON = the app's, and the generic build) decode a recording; prints time and frames.
#   [SCALE=128] android/native/bench-phone.sh RECORDING.cf32 FS SR [ADB_SERIAL]   (SCALE: leandvb --float-scale, 1 for rx/sim_* output)
# RECORDING: cf32 with the signal centred (rx/raw2cf32.py output, shifted to 0 Hz), e.g. build/f1/shifted.cf32 250000 50000
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
REC=$1; FS=$2; SR=$3
A="adb${4:+ -s $4}"
B="$ROOT/android/native/build"
T=/data/local/tmp
$A push "$ROOT/android/app/src/main/jniLibs/arm64-v8a/libleandvb.so" $T/leandvb >/dev/null
$A push "$ROOT/android/app/src/main/jniLibs/arm64-v8a/libldpc_tool.so" $T/ldpc_tool >/dev/null
[ -f "$B/ldpc_tool_generic" ] && $A push "$B/ldpc_tool_generic" $T/ldpc_tool_generic >/dev/null
$A shell "[ -f $T/rec.cf32 ] && [ \$(stat -c%s $T/rec.cf32) = $(stat -c%s "$REC") ]" 2>/dev/null || $A push "$REC" $T/rec.cf32
$A shell chmod 755 $T/leandvb $T/ldpc_tool $T/ldpc_tool_generic 2>/dev/null || true
SAMPLER=; [ "$SR" -lt 100000 ] && SAMPLER="--sampler rrc --rrc-rej 50" || true
for tool in ldpc_tool ldpc_tool_generic; do
  $A shell "cd $T && [ -x $tool ] || exit 0
    s=\$(cat /proc/uptime)
    ./leandvb --f32 --float-scale ${SCALE:-128} -f $FS --sr $SR --standard DVB-S2 --const QPSK --cr 3/4 --fastdrift -d \
      --modcods 128 --framesizes 1 --ldpc-helper ./$tool $SAMPLER < rec.cf32 > out.ts 2> err.txt
    e=\$(cat /proc/uptime)
    echo \"$tool: \$(echo \$s \$e | awk '{printf \"%.2f s\", \$3 - \$1}'), ok \$(grep -o _ err.txt | wc -l), bad \$(grep -oE '[:!]' err.txt | wc -l), \
locks \$(grep -c LOCKED err.txt), TS \$(( \$(stat -c%s out.ts) / 188 ))\""
done
echo "recording: $(( $(stat -c%s "$REC") / 8 / FS )) s of signal"
