# Nulling the carrier leakage of the C3 (LO calibration)

*2026-10-16 · [česky](2026-10-16-lo-calibration.cs.md)*

## Why

The XIAO ESP32-C3 leaks its carrier: a tone in the middle of the signal, about −34 dBc of the beacon at AMP 300 (0 dB) on
our board, that does not go down with the level and so limits reception at low levels. The firmware adds a DC correction
(i, q) in DAC codes to the baseband (`BDC`); the right one nulls the tone. Finding it needs a receiver: the XIAO
ESP32-S3.

## The new method: the leakage alone (`BDC TEST`)

Until now the C3 transmitted its beacon at a low level (AMP 13) and the tone was measured against the signal around it.
Now the firmware sends the leakage alone:

- `BDC TEST on [f_MHz]`: no modulation, only the DC correction (dithered over the samples of a symbol, so fractions of a
  code count), the PA off, at `f_MHz` or the beacon's frequency. Never saved, off after a reset; 120 s after the last
  `BDC` command the transmitter goes off. `BDC i q` takes a new correction while the transmitter keeps running: stopping
  and starting it calibrates the PHY afresh, which moved the leakage by tenths of a code.
- The receiver streams without a break; the tone is measured with a Goertzel filter (3 bins of 20 Hz, 4 windows of
  0.05 s averaged) against the noise floor. It is found as the line that follows the correction: the S3 has a line of its
  own at exactly the nominal frequency, which once caught the old meter. Its frequency is tracked with its drift (about
  −100 Hz/s for the first minute after the C3 starts).
- The search ([rx/dccal/core.py](../rx/dccal/core.py), in the app
  [DcCalibrator.kt](../android/app/src/main/java/com/ok1cdj/atvrx/DcCalibrator.kt)) fits the bowl
  P = (d − d\*)ᵀ M (d − d\*) + F to every measurement, in dB (Levenberg–Marquardt), and measures 7 points around the
  fit's null per round with a shrinking step. Near the null the measurements scatter by several dB; a fit in linear power
  followed the lowest point alone and lost the null in half of the runs on the board.

```sh
PY=third_party/ESP32-DATV-RX/.venv/bin/python
$PY rx/cal_dc.py --mode test                        # ATV_FREQ of boards.sh, 15 s warm-up, the result only
$PY rx/cal_dc.py --mode test --boots 5 --save       # 5 starts of the C3, the mean into its table and tx/cal_c3.json
$PY rx/cal_dc.py --mode test --freqs 2320,2340,2370 --boots 2 --log sweep.json
$PY rx/cal_dc.py --mode test --eval 0.5 -3.8        # how much a given correction takes off
```
Tests without hardware: `python3 -m pytest rx/dccal` and in the app `./gradlew testDebugUnitTest`. Both run the same
vectors ([rx/dccal/vectors](../rx/dccal/vectors)): synthetic transmitters, board-like ones (fluctuating noise, jitter)
and runs recorded on the board (`make_vectors.py --log`).

The old method (`rx/cal_dc.py` without `--mode`) is unchanged. It was not compared on the board this time: the C3 holds
a loop at AMP 420, and the old method needs the beacon at AMP 13. On 2026-10-14 it gave +23.1 → +2.2 dB against the
signal around the tone, which was the limit of that measurement.

## Results (2026-10-16, the bench, antennas a few cm apart, 2340 MHz)

| | |
|---|---|
| the tone without a correction | 25–35 dB above the noise of the S3 (in 20 Hz), about −34 dBc of AMP 300 |
| one calibration | 21–56 measurements, **10–25 s** (median 20 s) plus the warm-up; the old method took 1.5–2 min |
| in the same start of the C3 | **−22 dB** median (15…27 dB), to about **−57 dBc**; limited by the noise of the S3 |
| twice in the same start | 0.1–0.9 codes apart (the leakage still moves for minutes after the start) |
| **from one start of the C3 to the next** | **the null moves by 2.1 codes rms** (7 starts; I −2.9…+0.5, Q −6.9…−2.8) |
| a stored value in another start | the mean of the 7: **2–17 dB, median 9 dB**; the old stored 0.576 −3.829: median 7 dB |
| frequency, within one start | 2320–2430 MHz: the null within 0.3–0.4 codes rms; 2310 MHz a little off (measured first) |

What follows from it:

- **The leakage depends on the start of the C3 more than on anything else.** The PHY calibrates its own TX DC at every
  start, and our correction only removes what that leaves. `CONFIG_ESP_PHY_RF_CAL_NONE` (the stored PHY calibration,
  none at the start) gave 1.0 instead of 1.5 codes rms over 3 starts – not enough to leave ESP-IDF's default.
- **The frequency hardly matters** (within a start, 2320–2430 MHz). Drift of the frequency and of the leakage both come
  from the chip warming up, but a TCXO would not change the leakage, only the frequency.
- So: **calibrate just before transmitting** for the full 20 dB (the app does it with both boards on a hub); a stored
  value is worth about 9 dB. The C3's table (`BDC SAVE`, `BDC PUT`, up to 16 frequencies, interpolated) works, but one
  value is as good as a table.

## In the app

Menu ⋮ → *Transmitter calibration (S3 + C3)*: both boards on USB through a hub (a powered one: the C3 draws more while
transmitting). The app tells the two 303a:1001 boards apart by their USB serial number (the MAC), finds which is which
once and remembers it. The C3 warms up (60 s by default), the tone is found, the search runs (live level in dB), *Save*
stores the result in the C3 (`BDC PUT`) and in the app (by MAC and frequency). A firmware upload from the app leaves the
table in the C3 (it does not touch the NVS); if it is gone anyway, the app puts its copy back. The TX screen takes the
C3's value for its frequency (`BDC GET f`). Unlike Linux, Android does not reset the C3 when the app opens its port
(checked: the ROM's boot lines do not come), so as long as the C3 stays powered on the hub, a calibration in the app
holds for the transmissions after it in full; after a power cycle only the ~9 dB of a stored value remain.
Tried on the phone (OnePlus 10 Pro, a hub, 2026-10-16): both boards identified, 12.8 dB in one run, stored in the C3.

## The receiver's own line at n × 20 MHz

The line that once caught the tone meter is the XIAO ESP32-S3's: a spur of its PLL exactly on multiples of 20 MHz (by
the S3's clock), whatever the LO is set to – 2320.000 MHz 28.5 dB above the noise, 2340.000 MHz 12 dB, 2360.000 MHz
26 dB, nothing at 2330 or 2350 MHz. At 2340.000 MHz it lay inside the beacon's signal (the C3's carrier is about 18 kHz
higher), so `ATV_FREQ` is now **2345.000 MHz**. The calibration does not change with it.
