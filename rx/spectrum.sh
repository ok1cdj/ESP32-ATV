#!/bin/sh
# rtl_tcp server (333 ksps cu8) on 127.0.0.1:1234 for a spectrum view in SDR++ / SDRangel (Remote TCP Input, rtl_tcp protocol).
# Note: it announces an R820T tuner; a client that enforces the R820T range may refuse 2300 MHz.
#   rx/spectrum.sh [extra espdr-rx options, e.g. --ppm X --gain 40]
ROOT=$(cd "$(dirname "$0")/.." && pwd)
. "$ROOT/boards.sh"
exec "$ROOT/third_party/ESP32-DATV-RX/.venv/bin/espdr-rx" --decim 3 --listen 127.0.0.1:1234 --port "$RX_PORT" "$@"
