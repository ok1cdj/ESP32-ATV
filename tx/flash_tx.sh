#!/bin/sh
# Builds ESP32-DATV-SA for the XIAO ESP32-C3 with our beacon and flashes it.
# Firmware from our fork (third_party/ESP32-DATV-SA, branch esp32-atv); it is copied to build/tx-firmware and the beacon
# files are overlaid there, so the submodule keeps its own beacon.
#   . ~/esp/esp-idf-v6.1/export.sh
#   tx/flash_tx.sh [BEACON_DIR] [PORT]     (default tx/beacons/sv0syh-333k, TX_PORT from boards.sh)
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
BEACON=${1:-$ROOT/tx/beacons/sv0syh-333k}
. "$ROOT/boards.sh"
PORT=${2:-$TX_PORT}
command -v idf.py >/dev/null || { echo "idf.py not found: . ~/esp/esp-idf-v6.1/export.sh"; exit 1; }
FW="$ROOT/build/tx-firmware"
mkdir -p "$FW"
rsync -a --delete --exclude build --exclude sdkconfig --exclude sdkconfig.old "$ROOT/third_party/ESP32-DATV-SA/firmware/" "$FW/src/"
cp "$BEACON/beacon.bin" "$BEACON/beacon_cfg.h" "$BEACON/beacon_fec.h" "$FW/src/main/"
cd "$FW/src"
[ -f sdkconfig ] || idf.py set-target esp32c3
idf.py build
[ "$PORT" = "-" ] || idf.py -p "$PORT" flash
