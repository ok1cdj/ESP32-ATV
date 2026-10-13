#!/bin/sh
# Generates the stand-alone beacon of ESP32-DATV-SA with our callsign: picture (H.264 test card) + SDT.
# Symbol rates (our fork, branch esp32-atv): 40000 50000 66666 100000 125000 250000 333000 400000 500000.
#   [AMP=300] [OUTDIR=dir] tx/make_beacons.sh [BAUD] [FREQ_MHZ] [FRAMES]   -> tx/beacons/<call>-<kS/s>k/{beacon.bin,beacon_cfg.h,beacon_fec.h,beacon.ts}
# Below 125 kS/s the loop defaults to 2 PLFRAMEs: the modulator tables grow with the samples per symbol (256 B x S),
# and the loop and the tables share the heap.
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CALL=${CALL:-SV0SYH}
AMP=${AMP:-13}               # DAC amplitude 1..480: 13 = -27 dB (upstream default), 300 = 0 dB (full level, a few mW)
BAUD=${1:-333000}
FREQ=${2:-2300.500}
FRAMES=${3:-$([ "$BAUD" -lt 125000 ] && echo 2 || echo 4)}
KS=$(( (BAUD + 500) / 1000 ))
OUT="${OUTDIR:-$ROOT/tx/beacons}/$(echo "$CALL" | tr A-Z a-z)-${KS}k$([ "$AMP" = 13 ] || echo "-a$AMP")"
# A Python with numpy and Pillow: after ". export.sh" of ESP-IDF, python3 is IDF's venv, which has neither.
PY=${PYTHON:-}
for c in python3 /usr/bin/python3; do
  [ -n "$PY" ] && break
  "$c" -c "import numpy, PIL" 2>/dev/null && PY=$c
done
[ -n "$PY" ] || { echo "no python3 with numpy and Pillow (pip install -r third_party/ESP32-DATV-SA/host/requirements.txt)"; exit 1; }
mkdir -p "$OUT"
"$PY" "$ROOT/third_party/ESP32-DATV-SA/host/make_beacon.py" \
  --freq "$FREQ" --baud "$BAUD" --fec 3/4 --frames "$FRAMES" --amp "$AMP" \
  --card-text "$CALL" "ESP32-ATV beacon  $FREQ MHz" "DVB-S2 QPSK 3/4  $KS kS/s" \
  --service-name "$CALL" --service-provider "$CALL ESP32-ATV" \
  --out "$OUT" --ts "$OUT/beacon.ts"
