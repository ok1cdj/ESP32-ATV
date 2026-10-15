#!/bin/sh
# Sensitivity sweep: for each transmitter level (DAC amplitude) build + flash the beacon, receive for a while, log the result.
#   CALL=YOURCALL [FRAME=short] rx/level_sweep.sh BAUD SECONDS [AMP ...]       e.g. rx/level_sweep.sh 40000 120 300 95 30 13 9 4 2 1
# Results: build/sweep/<baud>[-short].txt (one line per level). Stops after two levels in a row without a decoded frame.
# FRAME=short: beacons with DVB-S2 short frames and rx.sh --frame short.
# RX gain is fixed (GAIN, default 30 dB) so that the levels compare. When the search finds no signal, the receiver is tuned to
# the centre found at the previous level (--no-search).
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
[ -n "$CALL" ] || { echo "Set your callsign: CALL=YOURCALL $0 ..."; exit 1; }
BAUD=$1; SECS=$2; shift 2
GAIN=${GAIN:-30}
FRAME=${FRAME:-normal}
SUF=; [ "$FRAME" = short ] && SUF=-short
K=$(( (BAUD + 500) / 1000 ))
DIR="$ROOT/build/sweep"; mkdir -p "$DIR"
OUT="$DIR/${K}k$SUF.txt"
: > "$OUT"
. ~/esp/esp-idf-v6.1/export.sh >/dev/null 2>&1
. "$ROOT/boards.sh"
FREQ=$ATV_FREQ; misses=0
for AMP in "$@"; do
  DB=$(python3 -c "import math; print(f'{20*math.log10($AMP/300):+.1f}')")
  AMP=$AMP FRAME=$FRAME OUTDIR="$DIR/beacons" "$ROOT/tx/make_beacons.sh" "$BAUD" >/dev/null
  "$ROOT/tx/flash_tx.sh" "$DIR/beacons/$(echo "$CALL" | tr A-Z a-z)-${K}k$([ "$AMP" = 13 ] || echo "-a$AMP")$SUF" > "$DIR/flash.log" 2>&1
  sleep 3
  LOG="$DIR/${K}k${SUF}_a$AMP.log"
  timeout -s INT "$SECS" "$ROOT/rx/rx.sh" "$BAUD" --gain "$GAIN" --no-play --frame "$FRAME" > "$LOG" 2>&1 || true
  how=search
  if grep -q "search: no signal" "$LOG"; then
    timeout -s INT "$SECS" "$ROOT/rx/rx.sh" "$BAUD" --gain "$GAIN" --no-play --frame "$FRAME" --freq "$FREQ" --no-search > "$LOG" 2>&1 || true
    how="fixed $FREQ"
  else
    c=$(grep "search: signal" "$LOG" | tail -1 | sed 's/.*centre \([0-9.]*\) MHz.*/\1/')
    [ -n "$c" ] && FREQ=$c
  fi
  snr=$(grep "search: signal" "$LOG" | tail -1 | sed 's/.*signal \([0-9]*\) dB above.*/\1/')
  last=$(grep "^frames ok" "$LOG" | tail -1)
  ok=$(echo "$last" | sed 's/frames ok \([0-9]*\).*/\1/'); ok=$(( ${ok:-1} - 1 ))
  bad=$(echo "$last" | sed 's/.*with errors \([0-9]*\).*/\1/'); bad=$(( ${bad:-1} - 1 ))
  locks=$(echo "$last" | sed 's/.*locks \([0-9]*\).*/\1/')
  ts=$(echo "$last" | sed 's/.*TS packets \([0-9]*\).*/\1/')
  mer=$(echo "$last" | grep -o 'MER [-0-9.]*' | cut -d' ' -f2)
  line="amp $AMP ($DB dB): search ${snr:--} dB ($how) | frames ok $ok bad $bad locks ${locks:-0} TS ${ts:-0} MER ${mer:--}"
  echo "$line" | tee -a "$OUT"
  if [ "$ok" -le 0 ]; then misses=$((misses + 1)); else misses=0; fi
  [ $misses -ge 2 ] && break
done
