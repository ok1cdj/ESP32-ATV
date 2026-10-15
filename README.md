# ESP32-ATV

[![Android APK](https://github.com/ok1cdj/ESP32-ATV/actions/workflows/android.yml/badge.svg)](https://github.com/ok1cdj/ESP32-ATV/actions/workflows/android.yml)

DATV on 13 cm with ESP32 boards: a stand-alone **ESP32-C3 DVB-S2 beacon** and **ATV RX**, a receiver made of a
**XIAO ESP32-S3** on USB-OTG and an **Android** phone or tablet (beta).

- **Guide / návod:** <https://ok1cdj.github.io/ESP32-ATV/> ([English](docs/rx-android.en.md), [česky](docs/rx-android.cs.md))
- **APK:** [Releases](https://github.com/ok1cdj/ESP32-ATV/releases) ([changelog](CHANGELOG.md)) · **S3 firmware:** [web flasher](https://ok1cdj.github.io/ESP32-ATV/flasher/)
- Licensed radio amateurs only. GNU GPL v3 ([LICENSE](LICENSE)); third-party parts keep their licences (below).

```
XIAO ESP32-C3 (ESP32-DATV-SA beacon, DVB-S2 QPSK 3/4, 40–500 kS/s, 2340.000 MHz)
   ~ 13 cm ~>
XIAO ESP32-S3 (ESP32-DATV-RX firmware: radio dump 16 MS/s -> 500 ksps cs4 / 333 ksps cs8 over native USB)
   -> Android: ATV RX (leandvb + ldpc_tool, MediaCodec)      or      PC: rx/rx.sh (rx_dvbs2.py, leandvb, ffplay)
```

| Part | Project | Licence |
|---|---|---|
| TX firmware | [ok1cdj/ESP32-DATV-SA](https://github.com/ok1cdj/ESP32-DATV-SA), fork of [barisdinc/ESP32-DATV-SA](https://github.com/barisdinc/ESP32-DATV-SA) (`third_party/`) | PolyForm Noncommercial |
| RX firmware + PC receiver | [ok1cdj/ESP32-DATV-RX](https://github.com/ok1cdj/ESP32-DATV-RX), fork of [barisdinc/ESP32-DATV-RX](https://github.com/barisdinc/ESP32-DATV-RX) (`third_party/`) | BSD-3 / 0BSD, leansdr GPL-3 |
| Android app, scripts | this repository (`android/`, `tx/`, `rx/`) | GPL-3 |

## Forks

Both projects in `third_party/` are our forks: branch **`esp32-atv`** with our commits, the original repository is the
remote `upstream`. Update: `git -C third_party/<repo> fetch upstream && git -C third_party/<repo> rebase upstream/main`.

| Fork | Changes |
|---|---|
| ESP32-DATV-SA | beacon 40–500 kS/s (rate table, default rate from `BEACON_BAUD`, `make_beacon.py`); DC/IQ correction of the beacon (`--cal`, `BDC` command); DVB-S2 short frames (`--frame short`); branch `xiao-panel`: front panel pins for the XIAO ESP32-C3 (untested) |
| ESP32-DATV-RX | `rx_dvbs2.py --sampler auto/linear/rrc`, `--rrc-rej` (RRC below 100 kS/s), `--device`, `--frame normal/short/auto`, DC removal in the 8-bit mode, MER / CNR in the status line, `--decimate` (experimental) |

## One-time setup (PC, Linux)
```sh
git submodule update --init --recursive
sh third_party/ESP32-DATV-RX/tools/install.sh      # venv (espdr, esptool), leandvb, ldpc_tool
sudo cp third_party/ESP32-DATV-RX/esp32-sdr-trx/udev/70-espdr.rules /etc/udev/rules.d/ \
  && sudo udevadm control --reload && sudo udevadm trigger   # keeps ModemManager off the port (any byte stops the stream)
# ESP-IDF for the C3 (v6.1)
git clone --depth 1 -b v6.1 --recursive --shallow-submodules https://github.com/espressif/esp-idf ~/esp/esp-idf-v6.1
~/esp/esp-idf-v6.1/install.sh esp32c3
```
Board ports are told apart by MAC address in `boards.sh` (both boards are `303a:1001`); edit it for your boards.

## Frequency
One place: `ATV_FREQ` in `boards.sh` – **2340.000 MHz**, the 2322–2355 MHz ATV segment of the IARU Region 1 band plan
(check your national rules). Used by `tx/make_beacons.sh`, `tx/tx_pc.sh`, `rx/rx.sh`, `rx/level_sweep.sh`,
`rx/cal_dc.py`; the app defaults to it.

## Transmitter – XIAO ESP32-C3
```sh
CALL=YOURCALL tx/make_beacons.sh 50000   # test card + SDT with your callsign -> tx/beacons/yourcall-50k/ (AMP=300: 0 dB)
. ~/esp/esp-idf-v6.1/export.sh
tx/flash_tx.sh tx/beacons/yourcall-50k   # builds in build/tx-firmware (the submodule stays clean) and flashes
```
Check: the C3 serial console command `INFO`. Rates: 40000 50000 66666 100000 125000 250000 333000 400000 500000 – the
loop runs at 8 MS/s with a whole number of 16–232 samples per symbol; below 125 kS/s it holds 2 PLFRAMEs so that the
modulator tables (256 B × S) fit on the heap. Other modulations (8PSK …) or rates: PC mode `tx/tx_pc.sh 33000`.

### Short frames (faster first picture at low rates)
```sh
FRAME=short tx/make_beacons.sh 40000     # -> tx/beacons/<call>-40k-short/, DVB-S2 short FECFRAMEs (16200 bits)
```
The receiver's LDPC decoder (ldpc_tool) works on batches of 32 frames. Short frames are 4× shorter, so a batch fills 4×
sooner: in simulation at 40–66 kS/s the first picture came after 5–8 s instead of 40–60 s (the first, partial batch of
normal frames is usually lost), at the same threshold (QPSK 3/4: Es/N0 ≈ 4.5 dB for both). Receive with the *… short* presets in the app or `rx/rx.sh 40000 --frame short`. Details:
[short frames](docs/2026-10-15-short-frames.en.md).

### DC correction (carrier leakage)
The C3 leaks its carrier (a tone in the middle of the signal) at a fixed level, which limits reception at low levels.
Calibrate with the receiver (S3 on the PC), **once per frequency** – the leakage and its correction depend on it:
```sh
AMP=13 tx/make_beacons.sh 40000 && tx/flash_tx.sh tx/beacons/<call>-40k   # low level: the leakage stands out
third_party/ESP32-DATV-RX/.venv/bin/python rx/cal_dc.py --save            # BDC i q -> measure -> fit; saved in the C3 (NVS) and tx/cal_c3.json
```
`tx/make_beacons.sh` then bakes `tx/cal_c3.json` into every beacon. By hand: `BDC [i q | SAVE | RESET]` on the C3's USB.

## Receiver – Android (ATV RX)
See the [guide](docs/rx-android.en.md). Build it yourself:
```sh
android/native/build-native.sh        # leandvb + ldpc_tool for arm64 with the NDK (fetches leansdr and LDPC itself)
cd android && ./gradlew assembleDebug
```

## Receiver – PC (XIAO ESP32-S3, one USB-C port)
1. U.FL antenna. Hold BOOT, tap RESET, release BOOT.
2. `rx/flash_rx_xiao.sh` – the receiver firmware into the flash (or the [web flasher](https://ok1cdj.github.io/ESP32-ATV/flasher/)). After a reset: `lsusb | grep 303a:1001`.
3. Receive:
   - `rx/rx.sh` – beacon 333 kS/s, `--wide` mode (500 ksps, 4 bit)
   - `rx/rx.sh 125000` – 125 kS/s (333 ksps, 8 bit)
   - `rx/rx.sh 66666` / `50000` / `40000` – low rates: 8 bit, RRC sampler of leandvb below 100 kS/s; the first picture
     after ~32 frames (66 kS/s ≈ 16 s), with `--ldpc bitflip` at once but ~5 dB less sensitive
   - `rx/rx.sh 40000 --frame short` – a beacon with short frames (`FRAME=short tx/make_beacons.sh 40000`)
   - options: `--gain 10`, `--ppm X`, `--record x.ts`, `-v`
4. Spectrum: `rx/spectrum.sh`, SDR++ / SDRangel on `127.0.0.1:1234` (rtl_tcp).

Other tools: `rx/level_sweep.sh` (sensitivity sweep, `FRAME=short` for short frames), `rx/sim_frames.py` (normal vs short frames simulated end to end), `rx/raw2cf32.py` (raw packets recorded by the app -> cf32, decode).

## Test without hardware
```sh
PY=third_party/ESP32-DATV-RX/.venv/bin/python
NS=$(grep -oP 'BEACON_SYMBOLS\s+\K[0-9]+' tx/beacons/sv0syh-333k/beacon_cfg.h)
$PY rx/sim_beacon.py tx/beacons/sv0syh-333k/beacon.bin -o build/beacon_ref.cf32 --loops 20 --sps 3 --baud 333333.3333 --symbols $NS
$PY third_party/ESP32-DATV-RX/tools/sim_wide.py build/beacon_ref.cf32 --fs-in 1000000 --fs-out 500000 --sr 333333 \
    --bits 4 --esn0 9 --repeat 1 --ldpc-helper third_party/ESP32-DATV-RX/build/LDPC/ldpc_tool
```
