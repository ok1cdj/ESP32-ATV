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

**Status:** released in v0.19-beta (2026-11-11): both firmwares from the app and the web flasher, presets in the app.
2026-11-10: the C5 beacon was decoded by a second
C5 on a PC and on a phone (lock, picture, MER 11–17 dB). 2026-11-11: 250 kBd through 400 ksps on the PC and on the phone
(3 m apart with the boards' stick-on antennas: MER 13–17 dB, 319 frames clean of 320, no USB packets lost).

**The C5 transmitter does what the C3 does:** it stores a loop recorded on the phone (TX screen → *Save as beacon*) and
transmits it by itself after power-up, and **Start TX sends live from the phone** (camera, test card, file; QPSK or 8PSK)
— new after v0.19.1-beta, received on air by the C5 receiver (from a PC and from the phone).

## Transmitter – `tx/c5cw`

The path follows [fedaykinofdune/esp32-C5-sdr-tx](https://github.com/fedaykinofdune/esp32-C5-sdr-tx) (GPL-3): the vendor CW
(`esp_phy_wifi_tx_tone`) tunes any MHz and turns the transmit chain on, `phy_stop_tx_tone(1)` stops its tone, and the DAC
then takes the TX DC register (0x600a0c08), into which the CPU stores the I/Q samples (15 cycles a store), paced on the
cycle counter with interrupts masked. The beacon uses the QPSK RRC 0.35 modulator tables of ESP32-DATV-SA (`qpsk_lut.h`).

**A beacon from the phone** (as with the C3): write the transmitter firmware (app: menu → Firmware, *6 cm transmitter*; or
`tx/c5cw/flash.sh none`), plug the C5 into the phone, on the TX screen enter 5715.000 MHz, the symbol rate (up to 250 kS/s
for the C5 receiver) and your callsign, then *Save as beacon*. The loop (DATVVID1 as the C3's, up to ~3 MB of flash) starts at
once and after every power-up; any byte on its USB stops it, and as on the C3 it starts again after 5 s without a
command (after a valid command - the app's - only `PLAY`, `BDC`, a new loop or a reset bring it back). The C3's AMP maps to the C5 as
AMP × 150 / 300 (AMP 300 = the level used in these tests; the output power was not measured). 2026-11-11: a loop saved on the phone (250 kS/s, short
frames) received by the C5 receiver on the phone, MER ~17 dB, 1084 frames clean of 1088.

**Live TX** (Start on the TX screen, or `third_party/ESP32-DATV-SA/host/tx_dvbs.py` on a PC): the C3's `QPSKT` / `PSK8T`
streaming protocol, the phone encodes DVB-S2 and sends raw symbols. QPSK with 16 samples per symbol at any rate of 2–333 kBd
(bench, 8 s each: 40k, 125k, 250k no late sample; 333k 145 late of 2.8 M symbols); 8PSK with at least 100 CPU cycles per
sample (S 60 at 40k, 19 at 125k, 9 at 250k, up to 300 kBd), no late sample on the bench. The C5 receiver takes up to 250 kS/s.
2026-11-13, symbols from the app's encoder (`tx/tx_s2enc.py`, short frames) sent live, received by the C5 receiver on a PC
(`rx/c5_play.sh`): QPSK 3/4 at 40k, 125k and 250k and 8PSK 2/3 at 125k and 250k decoded, service OK1CDJ, H.264 640×360.
2026-11-14 from the phone (Start TX): 40k and 66.7k QPSK with sound, 250k 8PSK 2/3 with camera and microphone (3 min with the
screen off: 56127 TS packets, no continuity break); a 40k loop stored with *Save as beacon* starts from a charger too.

**A beacon built into the firmware** (PC, for development):

```sh
. ~/esp/esp-idf-v6.1/export.sh                       # ESP-IDF v6.1 with esp32c5 (install.sh esp32c5)
CALL=YOURCALL BAND=6cm tx/make_beacons.sh 125000     # -> tx/beacons/yourcall-125k-6cm (ATV6_FREQ of boards.sh)
tx/c5cw/flash.sh tx/beacons/yourcall-125k-6cm        # C5_PORT of boards.sh; "-" only builds; "none": no built-in beacon
```

Commands on the C5's USB console (one per line):

| Command | Meaning |
|---|---|
| `BEACON [amp] [S] [dci dcq]` | the beacon on `F`: amp = largest \|I\|, \|Q\| in DAC codes (default 150), S samples per symbol (default 16: 2 MS/s at 125 kBd, 4 MS/s at 250 kBd), dci dcq = carrier leakage trim; any byte stops it |
| `PLAY` | the stored loop, else the built-in beacon, as after power-up |
| `VIDW …`, `INFO` | store a loop, identify the board: as the C3 (the app's *Save as beacon*) |
| `QPSKT` / `PSK8T f_MHz baud sps …` | live TX as the C3 (raw symbols on USB, `B7` fill reports); sps is chosen by the C5 |
| `BDC …` | carrier leakage trim per frequency as the C3: `GET [f]`, `i q`, `SAVE`, `PUT f i q`, `LIST`, `RESET`, `TEST on [f]` / `off` |
| `DC [i q]` | a single carrier leakage trim, used where the `BDC` table has no entry, kept in flash |
| `F 5715.000` | frequency in MHz (2300–5900) |
| `CW [i q]` / `TONE kHz [amp]` | a carrier / a single tone for a spectrum analyser |
| `SCALE n`, `ANT 1\|0`, `OFF`, `ST` | DAC scale (default 15), U.FL / chip antenna, off, state |

**The carrier leakage** changes every time the C5 starts, and the cs4 receiver removes it only when it lies within
~±0.5 kHz of its LO. `tx/c5_dccal.py` finds the trim (the transmitter sends `CW i q`, the receiver measures; both C5s on
the PC):

```sh
third_party/ESP32-DATV-RX/.venv/bin/python tx/c5_dccal.py --beacon      # finds dci dcq and starts BEACON 150 16 dci dcq
third_party/ESP32-DATV-RX/.venv/bin/python tx/c5_dccal.py --save        # ... and keeps them for that frequency (BDC PUT)
```
The stored loop, the built-in beacon and live TX take the trim for their frequency from the `BDC` table.

For a stored loop send the trim found with `DC dci dcq` (kept in flash). The phone cannot calibrate the C5 yet.

**The output power has not been measured:** the transmitter was never connected to a spectrum analyser by cable. Over the
air (tinySA Ultra with an antenna near the board, levels only a rough indication, not the transmitter's power): carrier
about −8 dBm at the analyser on 5710 MHz; the relative figures are more reliable: LO leakage 39 dBc, image 42 dBc,
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
| 400 ksps (`NB_SET_DECIM 25`) | 800 k/s | 300 | up to 250 kBd |

For 400 ksps the firmware reads the estimator only twice (I, then Q; Q comes 75 ns late, an image of −28 dB at the band
edge) and uses a shorter halfband (19 taps, −40 dB). 500 ksps is too much for the CPU, cs8 too much for the USB.

```sh
rx/c5rx/flash.sh                       # C5RX_PORT of boards.sh
rx/c5_play.sh 125000                   # PC: SR (up to 250000), optionally the frequency in MHz; GAIN=0..89, CR=3/4, FRAMES=short
```

The two C5s' crystals differ by up to ~150 kHz at 5.7 GHz, so `rx/c5_play.sh` first captures 16 ms at 2 MS/s and tunes to
the centre of the strongest signal within ±500 kHz.

**The LED** of the C5-Zero: dim blue = firmware running and waiting, green = receiving, red = transmitting, dark = the ROM
loader (reset it).

## Android

Both firmwares can be written from the phone: menu → Firmware asks, for an ESP32-C5, whether it becomes the 6 cm receiver or
transmitter (or the web flasher, which has a button for each).

The app recognises the C5 receiver by the flags of `CTL_INFO 3` and remembers it: always cs4, 250 ksps up to 148 kS/s,
333 ksps up to 197 kS/s and 400 ksps up to 250 kS/s (with a firmware that has it). The signal search reaches ±300 kHz.
Enter 5715.000 MHz.

## Known issues

- **Worse reception at short range than further away:** the receiver overloads (fixed gain, 0 dB in the app = index 30 of
  0–89). Turning the gain down helps for now; an AGC is planned.
- Both C5s sometimes end up in the ROM bootloader after being connected to the phone (a reset helps).
- The C5's carrier trim is set on the PC (`tx/c5_dccal.py --save`); the app's transmitter calibration is for the C3 and
  the S3 on 13 cm only.
- After many runs without a restart the transmitter's carrier leakage once stopped all decoding (also of the stored loop);
  a restart of the C5 cured it. Trim it with `tx/c5_dccal.py --save` when reception fails with a clean spectrum.
- Crystal drift: the app and `rx/c5_play.sh` follow it, but a warming transmitter can still walk out of the window.
