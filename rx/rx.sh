#!/bin/sh
# Receive the beacon: XIAO ESP32-S3 Plus -> leandvb (+ ldpc_tool) -> ffplay, via ESP32-DATV-RX's rx_dvbs2.py.
#   rx/rx.sh            stand-alone beacon 333 kS/s: --wide (500 ksps cs4)
#   rx/rx.sh 125000     125 kS/s: 333 ksps cs8
#   rx/rx.sh 66666      low rates (tx/make_beacons.sh 66666 / 50000 / 40000): cs8; rx_dvbs2.py switches leandvb to the RRC sampler below 125 kS/s
# Extra options go to rx_dvbs2.py: --gain 10 --ppm X --record out.ts --no-search --ldpc bitflip -v ...
# ldpc_tool decodes 32 frames at a time: the first picture comes after ~32 normal frames (66 kS/s: ~16 s, 40 kS/s: ~27 s);
# --ldpc bitflip shows it after a frame or two, with about 5 dB less margin.
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
RX="$ROOT/third_party/ESP32-DATV-RX"
SR=${1:-333000}; [ $# -gt 0 ] && shift
MODE=
[ "$SR" -gt 200000 ] && MODE=--wide
exec "$RX/.venv/bin/python" "$RX/rx_dvbs2.py" --freq 2300.5 --sr "$SR" $MODE --cr 3/4 --const QPSK "$@"
