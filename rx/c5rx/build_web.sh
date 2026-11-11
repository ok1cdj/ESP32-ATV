#!/bin/sh
# Builds the ESP32-C5 receiver (6 cm) for everyone (GitHub Pages, the app's flasher).
#   . ~/esp/esp-idf-v6.1/export.sh;  rx/c5rx/build_web.sh [OUTDIR]      (default build/c5-web/out)
# OUTDIR: bootloader.bin (0x2000 on the C5), partition-table.bin (0x8000), c5rx.bin (0x10000), version.txt.
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=${1:-$ROOT/build/c5-web/out}
B="$ROOT/build/c5-web/build"
command -v idf.py >/dev/null || { echo "idf.py not found: . ~/esp/esp-idf-v6.1/export.sh"; exit 1; }
mkdir -p "$OUT"
VER=$(git -C "$ROOT" describe --tags --always --dirty)
rm -rf "$B"
idf.py -C "$ROOT/rx/c5rx" -B "$B" -DSDKCONFIG="$B/sdkconfig" -DPROJECT_VER="$VER" build >/dev/null
cp "$B/bootloader/bootloader.bin" "$B/partition_table/partition-table.bin" "$B/c5rx.bin" "$OUT/"
echo "$VER" > "$OUT/version.txt"
ls -la "$OUT"
