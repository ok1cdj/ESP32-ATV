#!/bin/sh
# Generates the stand-alone beacon of ESP32-DATV-SA with our callsign: picture (H.264 test card) + SDT.
# Symbol rates (our fork, branch esp32-atv): 40000 50000 66666 100000 125000 250000 333000 400000 500000.
#   [AMP=300] [CAL=file.json] [OUTDIR=dir] [FRAME=short] tx/make_beacons.sh [BAUD] [FREQ_MHZ] [FRAMES]
#     -> tx/beacons/<call>-<kS/s>k[-a<AMP>][-short]/{beacon.bin,beacon_cfg.h,beacon_fec.h,beacon.ts}
# FRAME=short: DVB-S2 short FECFRAMEs (16200 bits, 7 TS packets each at 3/4): PLFRAMEs 4x shorter, so PLHEADERs and the
# 32-frame batches of ldpc_tool come 4x more often – the first picture comes much sooner at low symbol rates.
# The loop and the modulator tables (288 B x S, S = samples per symbol at 8 MS/s) share the ~78 KB heap of the C3, so the
# default number of PLFRAMEs follows the rate: normal 2 below 125 kS/s, else 4; short 8 below 66 kS/s, 12 below
# 100 kS/s, else 16 (8 short frames take the RAM of 2 normal ones).
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CALL=${CALL:-SV0SYH}
AMP=${AMP:-13}               # DAC amplitude 1..480: 13 = -27 dB (upstream default), 300 = 0 dB (full level, a few mW)
CAL=${CAL:-$ROOT/tx/cal_c3.json}   # DC / I/Q trims of our C3 (carrier leakage), measured with rx/cal_dc.py
FRAME=${FRAME:-normal}
case "$FRAME" in
  normal) SUFFIX=; CARD=" ";;              # card text exactly as before short frames: "3/4  50 kS/s"
  short)  SUFFIX=-short; CARD=" short ";;
  *) echo "FRAME must be normal or short"; exit 1;;
esac
BAUD=${1:-333000}
. "$ROOT/boards.sh"
FREQ=${2:-$ATV_FREQ}
if [ "$FRAME" = short ]; then
  FRAMES=${3:-$( if [ "$BAUD" -lt 66000 ]; then echo 8; elif [ "$BAUD" -lt 100000 ]; then echo 12; else echo 16; fi )}
else
  FRAMES=${3:-$([ "$BAUD" -lt 125000 ] && echo 2 || echo 4)}
fi
KS=$(( (BAUD + 500) / 1000 ))
OUT="${OUTDIR:-$ROOT/tx/beacons}/$(echo "$CALL" | tr A-Z a-z)-${KS}k$([ "$AMP" = 13 ] || echo "-a$AMP")$SUFFIX"
# A Python with numpy and Pillow: after ". export.sh" of ESP-IDF, python3 is IDF's venv, which has neither.
PY=${PYTHON:-}
for c in python3 /usr/bin/python3; do
  [ -n "$PY" ] && break
  "$c" -c "import numpy, PIL" 2>/dev/null && PY=$c
done
[ -n "$PY" ] || { echo "no python3 with numpy and Pillow (pip install -r third_party/ESP32-DATV-SA/host/requirements.txt)"; exit 1; }
mkdir -p "$OUT"
"$PY" "$ROOT/third_party/ESP32-DATV-SA/host/make_beacon.py" \
  --freq "$FREQ" --baud "$BAUD" --fec 3/4 --frame "$FRAME" --frames "$FRAMES" --amp "$AMP" $([ -f "$CAL" ] && echo --cal "$CAL") \
  --card-text "$CALL" "ESP32-ATV beacon  $FREQ MHz" "DVB-S2 QPSK 3/4$CARD $KS kS/s" \
  --service-name "$CALL" --service-provider "$CALL ESP32-ATV" \
  --out "$OUT" --ts "$OUT/beacon.ts"
