# Our boards (sourced by tx/ and rx/ scripts); override with TX_PORT=... / RX_PORT=...
# Both are 303a:1001 (native USB Serial/JTAG), so they are told apart by MAC in /dev/serial/by-id.
TX_PORT=${TX_PORT:-/dev/serial/by-id/usb-Espressif_USB_JTAG_serial_debug_unit_AC:27:6E:7F:9F:34-if00}   # XIAO ESP32-C3
RX_PORT=${RX_PORT:-/dev/serial/by-id/usb-Espressif_USB_JTAG_serial_debug_unit_28:84:85:8A:C1:78-if00}   # XIAO ESP32-S3 Plus
C5_PORT=${C5_PORT:-/dev/serial/by-id/usb-Espressif_USB_JTAG_serial_debug_unit_44:BD:8D:D0:B3:98-if00}   # Waveshare ESP32-C5-Zero, 6 cm TX
C5RX_PORT=${C5RX_PORT:-/dev/serial/by-id/usb-Espressif_USB_JTAG_serial_debug_unit_44:BD:8D:D0:A3:A0-if00}   # Waveshare ESP32-C5-Zero, 6 cm RX
# Operating frequency [MHz]: IARU Region 1 13 cm band plan, ATV segment 2322-2355 MHz (2300.5 was used until 2026-10-14,
# 2340.000 until 2026-10-16). Not on a multiple of 20 MHz: there the XIAO ESP32-S3's PLL has a spur of its own (2320, 2340,
# 2360 MHz: 12-28 dB above the noise), which lands inside the signal.
ATV_FREQ=${ATV_FREQ:-2345.000}
# 6 cm (ESP32-C5, tx/c5cw, rx/c5rx): IARU Region 1 ATV segment 5700-5720 MHz. Above EU Wi-Fi channel 140 (5690-5710 MHz), not on a
# multiple of 20 MHz (the S3's PLL spurs) nor of 48 MHz (the C5's crystal: 5712, 5760).
ATV6_FREQ=${ATV6_FREQ:-5715.000}
