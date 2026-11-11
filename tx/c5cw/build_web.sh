#!/bin/sh
# Builds the ESP32-C5 transmitter (6 cm) for everyone (GitHub Pages, the app's flasher): no built-in beacon - nothing goes out until
# the operator stores a loop with his callsign (the app's TX screen → Save as beacon).
#   . ~/esp/esp-idf-v6.1/export.sh;  tx/c5cw/build_web.sh [OUTDIR]      (default build/c5tx-web/out)
# OUTDIR: bootloader.bin (0x2000 on the C5), partition-table.bin (0x8000), c5tx.bin (0x10000), version.txt.
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=${1:-$ROOT/build/c5tx-web/out}
B="$ROOT/build/c5tx-web/build"
command -v idf.py >/dev/null || { echo "idf.py not found: . ~/esp/esp-idf-v6.1/export.sh"; exit 1; }
mkdir -p "$OUT"
VER=$(git -C "$ROOT" describe --tags --always --dirty)
rm -rf "$B"
C5CW_BEACON= idf.py -C "$ROOT/tx/c5cw" -B "$B" -DSDKCONFIG="$B/sdkconfig" -DPROJECT_VER="$VER" build >/dev/null
cp "$B/bootloader/bootloader.bin" "$B/partition_table/partition-table.bin" "$OUT/"
cp "$B/c5cw.bin" "$OUT/c5tx.bin"
echo "$VER" > "$OUT/version.txt"
ls -la "$OUT"
