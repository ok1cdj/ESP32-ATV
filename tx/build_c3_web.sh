#!/bin/sh
# Builds the XIAO ESP32-C3 transmitter firmware for everyone (GitHub Pages, the app's flasher): our fork of ESP32-DATV-SA
# without the built-in test card (CONFIG_DATV_BUILTIN_BEACON=n - nothing goes out until the operator stores a loop with his
# callsign: the app's TX screen or tx/c3_vidw.py), XIAO front panel pins.
#   . ~/esp/esp-idf-v6.1/export.sh;  tx/build_c3_web.sh [OUTDIR]      (default build/c3-web/out)
# OUTDIR: bootloader.bin (0x0), partition-table.bin (0x8000), tx.bin (0x10000), version.txt.
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=${1:-$ROOT/build/c3-web/out}
FW="$ROOT/build/c3-web/src"
command -v idf.py >/dev/null || { echo "idf.py not found: . ~/esp/esp-idf-v6.1/export.sh"; exit 1; }
mkdir -p "$FW" "$OUT"
rsync -a --delete --exclude build --exclude sdkconfig --exclude sdkconfig.old "$ROOT/third_party/ESP32-DATV-SA/firmware/" "$FW/"
printf 'CONFIG_DATV_BOARD_XIAO_C3=y\nCONFIG_DATV_BUILTIN_BEACON=n\n' >> "$FW/sdkconfig.defaults"
VER=$(git -C "$ROOT/third_party/ESP32-DATV-SA" describe --tags --always --dirty)
cd "$FW"
rm -f sdkconfig
idf.py -DPROJECT_VER="$VER" set-target esp32c3 >/dev/null
idf.py -DPROJECT_VER="$VER" build >/dev/null
cp build/bootloader/bootloader.bin build/partition_table/partition-table.bin "$OUT/"
cp build/esp32_datv.bin "$OUT/tx.bin"
echo "$VER" > "$OUT/version.txt"
ls -la "$OUT"
