# DATV on 6 cm with the ESP32-C5 (experimental)

*November 2026 · [česky](6cm-c5.cs.md)*

> [!WARNING]
> **Transmitting on 6 cm (5.65–5.85 GHz) requires an amateur radio licence.** The IARU Region 1 ATV segment is
> 5700–5720 MHz; Wi-Fi sits just below it (EU channel 140 ends at 5710 MHz). Transmit only with your own callsign and
> within your national rules.

The ESP32-C5 is the only Espressif chip with a 5 GHz radio. Two **Waveshare ESP32-C5-Zero** boards (ESP32-C5HF4, 48 MHz
crystal, U.FL connector) make a DVB-S2 transmitter and receiver on **5715.000 MHz** with no other RF hardware:

```
ESP32-C5 (tx/c5cw: DVB-S2 beacon QPSK 3/4, 125 / 250 kBd, 5715.000 MHz)
   ~ 6 cm ~>
ESP32-C5 (rx/c5rx: IQ estimator -> halfband -> cs4 250 / 333 / 400 ksps over USB, the S3 receiver's protocol)
   -> Android: ATV RX/TX (detects the C5 itself)      or      PC: rx/c5_play.sh (espdr nb.py, leandvb, ffplay)
```

**Status:** branch `c5-6cm`, not in the releases or the web flasher yet. 2026-11-10: the C5 beacon was decoded by a second
C5 on a PC and on a phone (lock, picture, MER 11–17 dB). 400 ksps (symbol rates up to 250 kBd) is written but not yet
tested on the boards.

## Transmitter – `tx/c5cw`

The path follows [fedaykinofdune/esp32-C5-sdr-tx](https://github.com/fedaykinofdune/esp32-C5-sdr-tx) (GPL-3): the vendor CW
(`esp_phy_wifi_tx_tone`) tunes any MHz and turns the transmit chain on, `phy_stop_tx_tone(1)` stops its tone, and the DAC
then takes the TX DC register (0x600a0c08), into which the CPU stores the I/Q samples (15 cycles a store), paced on the
cycle counter with interrupts masked. The beacon uses the QPSK RRC 0.35 modulator tables of ESP32-DATV-SA (`qpsk_lut.h`).

```sh
. ~/esp/esp-idf-v6.1/export.sh                       # ESP-IDF v6.1 with esp32c5 (install.sh esp32c5)
CALL=YOURCALL BAND=6cm tx/make_beacons.sh 125000     # -> tx/beacons/yourcall-125k-6cm (ATV6_FREQ of boards.sh)
tx/c5cw/flash.sh tx/beacons/yourcall-125k-6cm        # C5_PORT of boards.sh; "-" only builds
```

Commands on the C5's USB console (one per line):

| Command | Meaning |
|---|---|
| `BEACON [amp] [S] [dci dcq]` | the beacon on `F`: amp = largest \|I\|, \|Q\| in DAC codes (default 150), S samples per symbol (default 16: 2 MS/s at 125 kBd, 4 MS/s at 250 kBd), dci dcq = carrier leakage trim; any byte stops it |
| `F 5715.000` | frequency in MHz (2300–5900) |
| `CW [i q]` / `TONE kHz [amp]` | a carrier / a single tone for a spectrum analyser |
| `SCALE n`, `ANT 1\|0`, `OFF`, `ST` | DAC scale (default 15), U.FL / chip antenna, off, state |

**The carrier leakage** changes every time the C5 starts, and the cs4 receiver removes it only when it lies within
~±0.5 kHz of its LO. `tx/c5_dccal.py` finds the trim (the transmitter sends `CW i q`, the receiver measures; both C5s on
the PC):

```sh
third_party/ESP32-DATV-RX/.venv/bin/python tx/c5_dccal.py --beacon      # finds dci dcq and starts BEACON 150 16 dci dcq
```

Measured (tinySA Ultra over the air, levels approximate): carrier −8 dBm on 5710 MHz, LO leakage 39 dBc, image 42 dBc,
sampling images (S=16) ~34 dBc. **The crystal warms up while transmitting:** about +100 kHz (19 ppm) from a cold start
within a few minutes. Power the transmitter straight from a computer, not through a hub or a power bank (on the C3 that
ruined the MER).

## Receiver – `rx/c5rx`

The C5 has no dump path like the S3. The firmware reads the **IQ estimator** (running sums of the 10-bit ADC at 80 MHz):
the difference of two reads is a boxcar over the samples in between. It is read on a fixed clock at twice the output rate,
a halfband FIR decimates by 2, and the output goes as **cs4** (4-bit I/Q with a gain in the packet header) in the S3
receiver's packets over USB Serial/JTAG. It also speaks the S3's binary control protocol, so `espdr nb.py` and the app
drive it unchanged.

| Output | Reads | Cycles per read | Symbol rate |
|---|---|---|---|
| 250 ksps | 500 k/s | 480 | up to 148 kBd |
| 333 ksps | 667 k/s | 360 | up to 197 kBd |
| 400 ksps (`NB_SET_DECIM 25`) | 800 k/s | 300 | up to 250 kBd (untested) |

For 400 ksps the firmware reads the estimator only twice (I, then Q; Q comes 75 ns late, an image of −28 dB at the band
edge) and uses a shorter halfband (19 taps, −40 dB). 500 ksps is too much for the CPU, cs8 too much for the USB.

```sh
rx/c5rx/flash.sh                       # C5RX_PORT of boards.sh
rx/c5_play.sh 125000                   # PC: SR (up to 250000), optionally the frequency in MHz; GAIN=0..89, CR=3/4, FRAMES=short
```

The two C5s' crystals differ by up to ~150 kHz at 5.7 GHz, so `rx/c5_play.sh` first captures 16 ms at 2 MS/s and tunes to
the centre of the strongest signal within ±500 kHz.

## Android

The app recognises the C5 receiver by the flags of `CTL_INFO 3` and remembers it: always cs4, 250 ksps up to 148 kS/s,
333 ksps up to 197 kS/s and 400 ksps up to 250 kS/s (with a firmware that has it). The signal search reaches ±300 kHz.
Enter 5715.000 MHz.

## Known issues

- **Worse reception at short range than further away:** the receiver overloads (fixed gain, 0 dB in the app = index 30 of
  0–89). Turning the gain down helps for now; an AGC is planned.
- Both C5s sometimes end up in the ROM bootloader after being connected to the phone (a reset helps).
- With the transmitting C5 on the same hub the app keeps "identifying" the boards (`c5cw` answers neither as a C3 nor as
  a receiver).
- Crystal drift: the app and `rx/c5_play.sh` follow it, but a warming transmitter can still walk out of the window.
