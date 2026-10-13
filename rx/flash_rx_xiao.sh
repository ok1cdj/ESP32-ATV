#!/bin/sh
# Writes the ESP32-DATV-RX receiver firmware into the flash of the XIAO ESP32-S3 Plus through its only (native) USB port.
# After this the board boots the receiver by itself; espdr-rx / rx_dvbs2.py find it running and load nothing
# (board.ensure: load="auto" returns as soon as the receiver answers).
# Put the board in download mode first: hold BOOT, tap RESET, release BOOT.
#   rx/flash_rx_xiao.sh [PORT]   (default RX_PORT from boards.sh)
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
RX="$ROOT/third_party/ESP32-DATV-RX"
. "$ROOT/boards.sh"
PORT=${1:-$RX_PORT}
(cd "$RX/firmware" && sha256sum -c --ignore-missing SHA256SUMS)
"$RX/.venv/bin/python" -m esptool --chip esp32s3 --port "$PORT" --before default-reset --after hard-reset write-flash \
  0x0 "$RX/firmware/bootloader.bin" 0x8000 "$RX/firmware/partition-table.bin" 0x10000 "$RX/firmware/rx.bin"
echo "done: replug or tap RESET, then 'lsusb | grep 303a:1001' and rx/rx.sh"
