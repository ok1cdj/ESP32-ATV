# Our boards (sourced by tx/ and rx/ scripts); override with TX_PORT=... / RX_PORT=...
# Both are 303a:1001 (native USB Serial/JTAG), so they are told apart by MAC in /dev/serial/by-id.
TX_PORT=${TX_PORT:-/dev/serial/by-id/usb-Espressif_USB_JTAG_serial_debug_unit_AC:27:6E:7F:9F:34-if00}   # XIAO ESP32-C3
RX_PORT=${RX_PORT:-/dev/serial/by-id/usb-Espressif_USB_JTAG_serial_debug_unit_28:84:85:8A:C1:78-if00}   # XIAO ESP32-S3 Plus
