#!/bin/sh
# Builds the ESP32-C5 receiver for the 6 cm band and flashes it.
#   . ~/esp/esp-idf-v6.1/export.sh
#   rx/c5rx/flash.sh [PORT]      (C5RX_PORT from boards.sh; "-" only builds)
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
. "$ROOT/boards.sh"
PORT=${1:-$C5RX_PORT}
command -v idf.py >/dev/null || { echo "idf.py not found: . ~/esp/esp-idf-v6.1/export.sh"; exit 1; }
B="$ROOT/build/c5rx"
IDF="idf.py -C $ROOT/rx/c5rx -B $B -DSDKCONFIG=$B/sdkconfig"
$IDF build
[ "$PORT" = "-" ] || $IDF -p "$PORT" flash
