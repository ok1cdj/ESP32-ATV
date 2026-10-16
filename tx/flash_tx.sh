#!/bin/sh
# Builds ESP32-DATV-SA for the XIAO ESP32-C3 with our beacon and flashes it.
# Firmware from our fork (third_party/ESP32-DATV-SA, branch esp32-atv); it is copied to build/tx-firmware and the beacon
# files are overlaid there, so the submodule keeps its own beacon.
#   . ~/esp/esp-idf-v6.1/export.sh
#   tx/flash_tx.sh BEACON_DIR [PORT]     (e.g. tx/beacons/yourcall-333k from make_beacons.sh; TX_PORT from boards.sh)
#   SDKCONFIG_EXTRA="CONFIG_A=y CONFIG_B=n" tx/flash_tx.sh ...   (extra sdkconfig lines)
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
BEACON=${1:?usage: tx/flash_tx.sh BEACON_DIR [PORT]  (from CALL=YOURCALL tx/make_beacons.sh)}
. "$ROOT/boards.sh"
PORT=${2:-$TX_PORT}
command -v idf.py >/dev/null || { echo "idf.py not found: . ~/esp/esp-idf-v6.1/export.sh"; exit 1; }
FW="$ROOT/build/tx-firmware"
mkdir -p "$FW"
rsync -a --delete --exclude build --exclude sdkconfig --exclude sdkconfig.old "$ROOT/third_party/ESP32-DATV-SA/firmware/" "$FW/src/"
cp "$BEACON/beacon.bin" "$BEACON/beacon_cfg.h" "$BEACON/beacon_fec.h" "$FW/src/main/"
echo "CONFIG_DATV_BOARD_XIAO_C3=y" >> "$FW/src/sdkconfig.defaults"   # front panel OLED on D2/D0 (GPIO4/2)
# SDKCONFIG_EXTRA: more sdkconfig lines for an experiment, e.g. SDKCONFIG_EXTRA=CONFIG_ESP_PHY_RF_CAL_NONE=y
[ -z "$SDKCONFIG_EXTRA" ] || printf '%s\n' $SDKCONFIG_EXTRA >> "$FW/src/sdkconfig.defaults"
cd "$FW/src"
# a new sdkconfig whenever sdkconfig.defaults changed (e.g. the video partition table of upstream's 2026-10-14 firmware):
# an existing sdkconfig keeps its old values otherwise
cmp -s sdkconfig.defaults ../sdkconfig.defaults.last || { rm -f sdkconfig; cp sdkconfig.defaults ../sdkconfig.defaults.last; }
[ -f sdkconfig ] || idf.py set-target esp32c3
idf.py build
[ "$PORT" = "-" ] || idf.py -p "$PORT" flash
