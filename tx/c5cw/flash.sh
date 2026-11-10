#!/bin/sh
# Builds the ESP32-C5 bench transmitter (CW / tone on 5 GHz) and flashes it.
#   . ~/esp/esp-idf-v6.1/export.sh
#   tx/c5cw/flash.sh [PORT]      (C5_PORT from boards.sh; "-" only builds)
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
. "$ROOT/boards.sh"
PORT=${1:-$C5_PORT}
command -v idf.py >/dev/null || { echo "idf.py not found: . ~/esp/esp-idf-v6.1/export.sh"; exit 1; }
B="$ROOT/build/c5cw"
idf.py -C "$ROOT/tx/c5cw" -B "$B" -DSDKCONFIG="$B/sdkconfig" build
[ "$PORT" = "-" ] || idf.py -C "$ROOT/tx/c5cw" -B "$B" -DSDKCONFIG="$B/sdkconfig" -p "$PORT" flash
