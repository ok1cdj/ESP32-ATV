#!/bin/sh
# Receive the beacon: XIAO ESP32-S3 Plus -> leandvb (+ ldpc_tool) -> ffplay, via ESP32-DATV-RX's rx_dvbs2.py.
#   rx/rx.sh            stand-alone beacon 333 kS/s: --wide (500 ksps cs4)
#   rx/rx.sh 125000     125 kS/s: 333 ksps cs8
#   rx/rx.sh 66666      low rates (tx/make_beacons.sh 66666 / 50000 / 40000): cs8; rx_dvbs2.py switches leandvb to the RRC sampler below 100 kS/s
# Extra options go to rx_dvbs2.py: --gain 10 --ppm X --record out.ts --no-search --ldpc bitflip --frame short -v ...
# ldpc_tool decodes 32 frames at a time: the first picture comes after ~32 normal frames (66 kS/s: ~16 s, 40 kS/s: ~27 s);
# a beacon with short frames (FRAME=short tx/make_beacons.sh) and --frame short cuts that by 4 (~4 s / ~7 s);
# --ldpc bitflip shows it after a frame or two, with about 5 dB less margin.
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
RX="$ROOT/third_party/ESP32-DATV-RX"
. "$ROOT/boards.sh"
SR=${1:-333000}; [ $# -gt 0 ] && shift
MODE=
[ "$SR" -gt 200000 ] && MODE=--wide
exec "$RX/.venv/bin/python" "$RX/rx_dvbs2.py" --freq "$ATV_FREQ" --sr "$SR" $MODE --cr 3/4 --const QPSK --device "$RX_PORT" "$@"
