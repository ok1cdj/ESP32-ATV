#!/bin/sh
# PC-driven transmission (symbols streamed over USB), for symbol rates the stand-alone beacon cannot do (e.g. 125 kS/s).
# Uses the ffmpeg test pattern; the service name carries our callsign.
#   tx/tx_pc.sh [BAUD] [extra tx_dvbs.py options]   e.g.  tx/tx_pc.sh 125000 --port /dev/ttyACM0
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CALL=${CALL:-SV0SYH}
BAUD=${1:-125000}; [ $# -gt 0 ] && shift
. "$ROOT/boards.sh"
exec python3 "$ROOT/third_party/ESP32-DATV-SA/host/tx_dvbs.py" --freq "$ATV_FREQ" --baud "$BAUD" \
  --dvbs2 --fec 3/4 --pilots --test --service-name "$CALL" --service-provider "$CALL ESP32-ATV" "$@"
