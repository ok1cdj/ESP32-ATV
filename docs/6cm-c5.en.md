# DATV with the ESP32-C5: 13 and 6 cm, one board for RX and TX (experimental)

*November 2026 · [česky](6cm-c5.cs.md)*

> [!WARNING]
> **Transmitting requires an amateur radio licence**, on 13 cm (2.3–2.45 GHz) as on 6 cm (5.65–5.85 GHz). The IARU
> Region 1 ATV segment on 6 cm is 5700–5720 MHz; Wi-Fi sits just below it (EU channel 140 ends at 5710 MHz). Transmit only
> with your own callsign and within your national rules.

The ESP32-C5 is the only Espressif chip with a 5 GHz radio, and it has the 2.4 GHz band too. A **Waveshare ESP32-C5-Zero**
(ESP32-C5HF4, 48 MHz crystal, U.FL connector) is a DVB-S2 transmitter and receiver for **13 cm and 6 cm** with no other RF
hardware. With the transceiver firmware **one board does both RX and TX**: the phone transmits through it and receives
through it, without swapping boards.

```
ESP32-C5 transceiver (tx/c5cw + rx/c5rx in one image)
   TX: live from the phone or a stored loop, DVB-S2 QPSK / 8PSK, 2-333 kBd
   RX: IQ estimator -> halfband -> cs4 250 / 333 / 400 ksps over USB (the S3 receiver's protocol), up to 250 kBd
   2300-2450 MHz (13 cm, e.g. 2345.000)  and  5650-5900 MHz (6 cm, e.g. 5715.000)
   -> Android: ATV RX/TX (RX and TX screens, the same board)      or      PC: rx/c5_play.sh, tx/tx_s2enc.py
```

**Status**
- v0.19-beta (2026-11-11): 6 cm receiver and stored beacon, both firmwares from the app and the web flasher.
- v0.20-beta (2026-11-14): live TX through the C5, carrier trim per frequency, beacon restart as on the C3.
- Next version (branch `trx`): **the transceiver** (one board for RX and TX), **13 cm on the C5**, our own GPL-3 modulator
  tables. Tested 2026-11-14 ([test report](2026-11-14-c5-transceiver.en.md)): C5 → C5 on 13 and 6 cm at 40–250 kBd, and a
  QSO with the IZ7PDX parrot made with a single C5.

## Quick start (phone)

1. **Firmware:** connect the C5 through OTG, menu ⋮ → **Firmware** → *Write firmware…*, choose *transceiver (RX + TX)*
   (or *receiver* for a board that only receives). Or the [web flasher](https://ok1cdj.github.io/ESP32-ATV/flasher/), or
   on a PC `tx/c5cw/flash.sh none` (transceiver) / `rx/c5rx/flash.sh` (receiver).
2. **Receive:** **RX**, a preset or your own settings – 2345.000 MHz on 13 cm, 5715.000 MHz on 6 cm, up to 250 kS/s –,
   **Start**. The app recognises the C5 by itself.
3. **Transmit live:** **TX**, frequency, symbol rate, QPSK or 8PSK, your **callsign**, picture and sound, **Start TX**.
4. **Stored beacon:** on the TX screen *Save as beacon*: the C5 keeps the loop and transmits it after every power-up
   (from a charger too).
5. Switch between **RX** and **TX** with the same board plugged in. AMP 300 = the level of our tests; the output power has
   not been measured.

## One board for RX and TX – the transceiver

`tx/c5cw` builds the receiver (`rx/c5rx/main/main.c` with `C5_TRX`) into its image. One USB port answers the
transmitter's text commands (as the C3) and the receiver's binary protocol (as the S3: 0xB4 at the start of a line), so the
app's RX and TX screens both take the same board (INFO `ESP32DATV 1 C5 RXTX`; with a hub the app finds it for either role).
The receiver's `G`, `CAP`, `STREAM` work on it too; `F` and `ANT` set both. After a transmission the receiver sets the radio
up again before its next run. A request of the receiver keeps the stored beacon off.

Tested 2026-11-14 with both C5s on the transceiver image (from a PC): board A transmits, board B receives and the other way
round, on 2345 and 5715 MHz, QPSK 125k / 250k and 8PSK 250k, each board switching TX → RX and RX → TX without a reset – all
decoded without a TS error. With one C5 alone a QSO with the IZ7PDX parrot (OK1KCI, 2345 MHz) worked at 40–250 kBd from a PC and at 250 kBd from the
phone (Start TX, then the RX screen): the call transmitted, then the reply received on the same board
([test report](2026-11-14-c5-transceiver.en.md)).

## Transmitter

The path follows [fedaykinofdune/esp32-C5-sdr-tx](https://github.com/fedaykinofdune/esp32-C5-sdr-tx) (GPL-3): the vendor CW
(`esp_phy_wifi_tx_tone`) tunes any MHz and turns the transmit chain on, `phy_stop_tx_tone(1)` stops its tone, and the DAC
then takes the TX DC register (0x600a0c08), into which the CPU stores the I/Q samples (15 cycles a store), paced on the
cycle counter with interrupts masked. The RRC 0.35 modulator tables and the fractional sample clock are our own
(`tx/common/rrc_tables.h`, `tx/common/frac_clock.h`, GPL-3); the C5 firmware takes nothing from ESP32-DATV-SA.

**Live TX** (Start on the TX screen; on a PC `tx/tx_s2enc.py` with the app's encoder): the C3's `QPSKT` / `PSK8T` streaming
protocol, the phone encodes DVB-S2 and sends raw symbols. QPSK with 16 samples per symbol at any rate of 2–333 kBd; 8PSK with
at least 100 CPU cycles per sample (S 60 at 40k, 19 at 125k, 9 at 250k), up to 300 kBd. On the bench no late sample up to
250 kBd (QPSK 333k: 145 late of 2.8 M symbols). From the phone (2026-11-14): 40k and 66.7k QPSK with sound, 250k 8PSK with
camera and microphone, 3 min with the screen off without a TS error.

**Stored loop** (TX screen → *Save as beacon*, as on the C3): DATVVID1 loop up to ~3 MB of flash, QPSK, any rate up to
333 kBd, short or normal frames. It starts at once and after every power-up; any byte on its USB stops it, and it starts
again after 5 s without a command (after a valid command – the app's – only `PLAY`, `BDC`, a new loop or a reset bring it
back). The C3's AMP maps to the C5 as AMP × 150 / 300.

**A beacon built into the firmware** (PC, for development):

```sh
. ~/esp/esp-idf-v6.1/export.sh                       # ESP-IDF v6.1 with esp32c5 (install.sh esp32c5)
CALL=YOURCALL BAND=6cm tx/make_beacons.sh 125000     # -> tx/beacons/yourcall-125k-6cm (ATV6_FREQ); without BAND: 13 cm (ATV_FREQ)
tx/c5cw/flash.sh tx/beacons/yourcall-125k-6cm        # C5_PORT of boards.sh; "-" only builds; "none": no built-in beacon (as the web)
```

Commands on the C5's USB console (one per line):

| Command | Meaning |
|---|---|
| `QPSKT` / `PSK8T f_MHz baud sps …` | live TX as the C3 (raw symbols on USB, `B7` fill reports); sps is chosen by the C5 |
| `PLAY` | the stored loop, else the built-in beacon, as after power-up |
| `VIDW …`, `INFO` | store a loop, identify the board: as the C3 (the app's *Save as beacon*) |
| `BEACON [amp] [S] [dci dcq]` | the built-in beacon on `F`: amp = largest \|I\|, \|Q\| in DAC codes (default 150), S samples per symbol (default 16); any byte stops it |
| `BDC …` | carrier leakage trim per frequency as the C3: `GET [f]`, `i q`, `SAVE`, `PUT f i q`, `LIST`, `RESET`, `TEST on [f]` / `off` |
| `DC [i q]` | a single carrier leakage trim, used where the `BDC` table has no entry, kept in flash |
| `F 2345.000` | frequency in MHz (2300–5900), for the transmitter and the receiver |
| `G n`, `CAP n`, `STREAM …` | the receiver: gain index 0–89, a capture at 2 MS/s, a stream (transceiver; as on `rx/c5rx`) |
| `CW [i q]` / `TONE kHz [amp]` | a carrier / a single tone for a spectrum analyser |
| `SCALE n`, `ANT 1\|0`, `OFF`, `ST` | DAC scale (default 15), U.FL / chip antenna, off, state |

**The carrier leakage** changes every time the C5 starts, and the cs4 receiver removes it only when it lies within
~±0.5 kHz of its LO. It is about 1.5 DAC codes (≈ −40 dB under the beacon's peak level) on both bands. `tx/c5_dccal.py` finds
the trim with two C5s on a PC (the transmitter sends `CW i q`, the receiver measures):

```sh
third_party/ESP32-DATV-RX/.venv/bin/python tx/c5_dccal.py --freq 2345 --save   # 13 cm; --freq 5715 for 6 cm; --beacon starts it
```

The stored loop, the built-in beacon and live TX take the trim for their frequency from the `BDC` table. The phone cannot
calibrate the C5 yet.

**The output power has not been measured:** the transmitter was never connected to a spectrum analyser by cable. Over the
air on 5710 MHz (tinySA Ultra with an antenna near the board, a rough indication only): carrier about −8 dBm at the
analyser, LO leakage 39 dBc, image 42 dBc, sampling images (S=16) ~34 dBc. **The crystal warms up while transmitting:**
about +100 kHz on 6 cm (19 ppm) within a few minutes of a cold start. Power the board straight from a computer or the phone,
not through a hub or a power bank (on the C3 that ruined the MER).

## Receiver

The C5 has no dump path like the S3. The firmware reads the **IQ estimator** (running sums of the 10-bit ADC at 80 MHz):
the difference of two reads is a boxcar over the samples in between. It is read on a fixed clock at twice the output rate,
a halfband FIR decimates by 2, and the output goes as **cs4** (4-bit I/Q with a gain in the packet header) in the S3
receiver's packets over USB Serial/JTAG. It speaks the S3's binary control protocol, so `espdr nb.py` and the app drive it
unchanged. The same receiver is in both firmwares (`rx/c5rx` alone, and in the transceiver).

| Output | Reads | Cycles per read | Symbol rate |
|---|---|---|---|
| 250 ksps | 500 k/s | 480 | up to 148 kBd |
| 333 ksps | 667 k/s | 360 | up to 197 kBd |
| 400 ksps (`NB_SET_DECIM 25`) | 800 k/s | 300 | up to 250 kBd |

For 400 ksps the firmware reads the estimator only twice (I, then Q; Q comes 75 ns late, an image of −28 dB at the band
edge) and uses a shorter halfband (19 taps, −40 dB). 500 ksps is too much for the CPU, cs8 too much for the USB.

```sh
rx/c5_play.sh 125000 2345.000          # PC: SR (up to 250000), frequency in MHz (default ATV6_FREQ); GAIN=0..89, CR=3/4, FRAMES=short
```

The two C5s' crystals differ by up to ~150 kHz at 5.7 GHz (+13…+29 kHz measured at 2.3 GHz), so `rx/c5_play.sh` first
captures at 2 MS/s and tunes to the centre of the strongest signal within ±500 kHz; the app searches ±300 kHz.

## Measured (2026-11-14, from a PC, QPSK 3/4 short frames unless stated)

| Test | Result |
|---|---|
| C5 → C5, 2345 MHz, 40k / 50k / 66.7k / 100k / 125k / 250k QPSK, 250k 8PSK 2/3 | all decoded, 0 TS errors, no late TX sample |
| C5 → C5, 5715 MHz, 40k / 125k / 250k QPSK, 125k / 250k 8PSK | all decoded, 0 TS errors |
| one transceiver: TX → RX and RX → TX without a reset (2345 / 5715 MHz) | all decoded |
| QSO with the IZ7PDX parrot (OK1KCI, 2345 MHz), one C5 | 40k–250k: call decoded by the parrot (MER 15–21 dB), reply received without a TS error; 125k only with short frames; 333k only towards the parrot |

Details: [test report 2026-11-14](2026-11-14-c5-transceiver.en.md).

## Android

The app recognises the C5 receiver by the flags of `CTL_INFO 3` and the transceiver by its INFO line, and remembers them:
always cs4, 250 ksps up to 148 kS/s, 333 ksps up to 197 kS/s and 400 ksps up to 250 kS/s. It takes the C5 on 2300–2450 and
5650–5900 MHz (the S3 only on 13 cm). The TX presets *… 6 cm … (C5)* are for 5715 MHz; on 13 cm the C5 takes the 2345 MHz
presets as the C3.

**The LED** of the C5-Zero: dim blue = firmware running and waiting, green = receiving, red = transmitting, dark = the ROM
loader (reset it). The C5 has **no PA output** (`PA 0` is answered as on the C3, `PA 1` refused – the C3's PA enable is
GPIO3).

## Known issues

- **Worse reception at short range than further away:** the receiver overloads (fixed gain, 0 dB in the app = index 30 of
  0–89). Turning the gain down helps for now; an AGC is planned.
- The C5 sometimes ends up in the ROM bootloader after being connected to the phone (a reset helps).
- The C5's carrier trim is set on the PC (`tx/c5_dccal.py --save`); the app's transmitter calibration is for the C3 and the
  S3 only.
- After many runs without a restart the transmitter's carrier leakage once stopped all decoding (also of the stored loop);
  a restart of the C5 cured it. Trim it with `tx/c5_dccal.py --save` when reception fails with a clean spectrum.
- Crystal drift: the app and `rx/c5_play.sh` follow it, but a warming transmitter can still walk out of the window.
- The C5 receiver takes up to 250 kS/s: a reply at 333k (e.g. from the IZ7PDX parrot) cannot be received.
