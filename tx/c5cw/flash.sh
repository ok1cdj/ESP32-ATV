#!/bin/sh
# Builds the ESP32-C5 bench transmitter (CW / tone / beacon on 5 GHz) and flashes it.
#   . ~/esp/esp-idf-v6.1/export.sh
#   tx/c5cw/flash.sh BEACON_DIR [PORT]      (e.g. tx/beacons/yourcall-250k-6cm from make_beacons.sh; C5_PORT from boards.sh; "-" only builds)
#   BEACON_DIR "none": no built-in beacon, as the web build (only a loop stored with VIDW is transmitted)
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
B1=${1:?usage: tx/c5cw/flash.sh BEACON_DIR|none [PORT]  (from CALL=YOURCALL tx/make_beacons.sh)}
if [ "$B1" = none ]; then BEACON=; else BEACON=$(cd "$B1" && pwd); fi
. "$ROOT/boards.sh"
PORT=${2:-$C5_PORT}
command -v idf.py >/dev/null || { echo "idf.py not found: . ~/esp/esp-idf-v6.1/export.sh"; exit 1; }
B="$ROOT/build/c5cw"
export C5CW_BEACON="$BEACON"
IDF="idf.py -C $ROOT/tx/c5cw -B $B -DSDKCONFIG=$B/sdkconfig"
# CMake reads the directory at configure time only: reconfigure when it changed
[ "$(cat "$B/beacon.last" 2>/dev/null)" = "$BEACON" ] || { $IDF reconfigure; echo "$BEACON" > "$B/beacon.last"; }
$IDF build
[ "$PORT" = "-" ] || $IDF -p "$PORT" flash
