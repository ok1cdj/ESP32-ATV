---
title: ATV RX – guide (beta)
---

**ATV RX** is a DVB-S2 receiver for amateur television on 13 cm. The radio is a **Seeed XIAO ESP32-S3** on USB-OTG;
an **Android phone or tablet** demodulates (leandvb), decodes the LDPC (ldpc_tool) and shows the picture together
with MER, constellation and stream information. Its counterpart is the stand-alone ESP32-C3 beacon
([ESP32-DATV-SA](https://github.com/ok1cdj/ESP32-DATV-SA)) – and the same app also **transmits**: with a XIAO
ESP32-C3 on USB-OTG it sends a test card or the phone's camera live as DVB-S2 (switch **RX | TX** at the top).

> **Beta.** Transmitting is for licensed radio amateurs only, on frequencies allowed by your licence and band plan.
> The receiver is free software under the GNU GPL v3.

![ATV RX on a phone: the beacon picture, reception status, spectrum and constellation](img/atvrx-app.png)

## What you need

| part | note |
|---|---|
| **Seeed XIAO ESP32-S3** or **S3 Plus** | with an external antenna on U.FL (2.3 GHz – the Wi-Fi kit antenna is fine on a desk) |
| **USB-C OTG** cable or adapter | the phone must support USB host (OTG); the S3 is powered by the phone |
| **Android 9+**, **arm64** CPU | tested: OnePlus 10 Pro (Android 16) |
| a computer with **Chrome / Edge** | once, to write the firmware into the S3 |
| for TX: **Seeed XIAO ESP32-C3** | with the [ESP32-DATV-SA](https://github.com/ok1cdj/ESP32-DATV-SA) firmware and an antenna on U.FL |

## 1. Receiver firmware into the ESP32-S3 (once)

**From the browser (easiest):** open the [web flasher](flasher/) in Chrome or Edge on a computer, connect the XIAO
with a data cable, hold **BOOT**, tap **RESET**, release BOOT, click *Install*, choose the port and confirm. Tap
**RESET** when it is done. The board then enumerates as `303a:1001` and starts the receiver at every power-up.

**From the command line** (this repository, Linux): `third_party/ESP32-DATV-RX/tools/install.sh`, then
`rx/flash_rx_xiao.sh` (download mode as above).

## 2. The app

1. Download the APK from [Releases](https://github.com/ok1cdj/ESP32-ATV/releases) (`atvrx-….apk`; checksum in
   `SHA256SUMS`).
2. Open it on the phone and allow installing from that source (*Install unknown apps*).
3. On the first start allow **notifications**: reception runs in a background service, whose notification shows the
   status and a Stop button.
4. Connect the S3 through OTG. Android offers to open ATV RX and asks for **access to the USB device** – allow it.

Updates: a new version from Releases installs over the old one (same signature).

## 3. Receiving

1. Pick a **Preset…** at the top, e.g. *Beacon 50k QPSK 3/4* (2340.000 MHz, 50 kS/s). A preset sets frequency, symbol
   rate, modulation, code rate and frame size; **Save** stores your own settings.
2. **Gain**: 10 dB on a desk, 30 dB and more over a distance. A strong signal with a high gain overloads the receiver
   (MER drops, lock comes and goes).
3. **Start.** The app looks for the signal for a second (FFT), tunes to its centre and starts the demodulator.
4. The **first picture** comes when ldpc_tool has decoded the first batch of 32 frames: about 3 s at 333 kS/s, 20 s at
   50 kS/s, 27 s at 40 kS/s (the first, partial batch is often lost, so up to twice that in practice). After that the
   picture runs smoothly (delayed by that batch). A beacon with **short frames** (`FRAME=short`) and a *Beacon … short*
   preset cut the wait by 4, to 5–8 s, at the same sensitivity ([short frames](https://github.com/ok1cdj/ESP32-ATV/blob/master/docs/2026-10-15-short-frames.en.md)).

You can turn the screen off or switch to another app – reception goes on in the service. Stop it with **Stop** in the
app or in the notification.

### What the figures mean

| line | meaning |
|---|---|
| `carrier` | carrier offset from the tuned frequency; `NCO` = software correction, `residual` = what the demodulator still sees |
| `drift steps` | how often the app followed the oscillator drift (ESP32 boards have no TCXO; they move by kHz while warming up) |
| `re-searches` | how often the signal was lost and searched again (10 s – at low rates 2.5 LDPC batches – without a good frame) |
| `LOCK`, `FRAME` | demodulator lock and DVB-S2 frame sync |
| `MER` | modulation error ratio in dB – the main quality figure; QPSK 3/4 needs about > 5 dB, 8PSK 3/5 > 10 dB |
| `CNR` | carrier-to-noise ratio (only when the sample rate is > 3× the symbol rate, i.e. up to 100 kS/s) |
| `frames ok / fixed / bad` | frames without errors / corrected by BCH / not decoded |
| `TS`, `TEI`, `CC` | transport stream rate (10 s average), packets with errors, continuity counter breaks |
| `service`, `video` | service and provider name from the SDT, codec and resolution |
| `USB lost`, `queue drops` | losses on USB and in the queue to the demodulator – should be 0 |
| constellation (square) | symbol points: QPSK 4 clusters, 8PSK 8 clusters; a ring = too little signal or phase noise |

## 4. Transmitter (ESP32-C3 beacon)

The transmitter is a fork of [ESP32-DATV-SA](https://github.com/ok1cdj/ESP32-DATV-SA) (PolyForm Noncommercial).
The beacon is built on a PC **with your callsign** – on the test card and as the service name:

```sh
CALL=YOURCALL tx/make_beacons.sh 50000        # 50 kS/s; AMP=300 for full level (0 dB), default -27 dB
. ~/esp/esp-idf-v6.1/export.sh && tx/flash_tx.sh tx/beacons/<call>-50k
```

- The frequency is in `boards.sh` (`ATV_FREQ`, default **2340.000 MHz** – the 2322–2355 MHz ATV segment of the IARU
  Region 1 band plan). Check your national rules.
- The **DC calibration of the carrier leakage** (`rx/cal_dc.py --save`, S3 on the PC) is valid for one frequency –
  repeat it after a change.
- The beacon is QPSK only; 8PSK and live pictures come from the app (section 5) or from a PC
  (`third_party/ESP32-DATV-SA/host/tx_dvbs.py`).

## 5. Transmitting from the phone (TX, beta)

![TX: test card from the phone, status of the C3 and the stream](img/atvrx-tx.png)

The phone encodes the picture (H.264), the transport stream and DVB-S2 itself and streams the symbols to the C3, which
only modulates (its PC mode). The C3 needs the ESP32-DATV-SA firmware (the beacon firmware above is the same one: a
command from USB stops the beacon and starts the live mode).

1. Connect the **XIAO ESP32-C3** through OTG instead of the S3 (one port: RX and TX take turns). Put the C3 and its
   antenna **away from the phone** – lying next to it the signal came 20 dB weaker and did not decode.
2. Switch to **TX** at the top, allow USB access (and the camera when you use it).
3. Set frequency (2300–2450 MHz, the firmware refuses anything else), symbol rate, **QPSK / 8PSK**, code rate,
   frame size (**short** gives the first picture 4× sooner at low rates), pilots, **callsign**, **AMP** (300 = 0 dB,
   a few mW; 13 = −27 dB; 8PSK up to 420) and the **picture**: test card, back or front camera.
4. **Start TX.** The app reads the C3's DC calibration (`BDC`) and starts it; the status shows the C3 buffer (it
   should stay near 3000), the TS and video rates. TX runs in the background with the screen off; stop it with
   **Stop TX** or in the notification.

- The camera picture turns upright the way you hold the phone (landscape fills the frame) and carries the callsign
  and the mode in a strip at the bottom.
- Video bit rate and frame rate are automatic (about 70 % of the TS rate; 320×180 5 fps at 40–66 kS/s, 640×360 15 fps
  at 333 kS/s) or set by hand.
- Receive it with a second phone, the PC (`rx/rx.sh 333000 --frame short`) or any DVB-S2 receiver. 8PSK: use normal
  frames for leandvb-based receivers (they do not decode short 8PSK).
- Tested on the bench: QPSK 3/4 50 kS/s short MER 17 dB, 333 kS/s MER 15 dB; 8PSK 2/3 333 kS/s MER 16 dB.

## Troubleshooting

| symptom | try |
|---|---|
| *ESP32-S3 not connected* | a data cable (not charge-only), OTG enabled on the phone, firmware written (board = `303a:1001`) |
| *searching: no signal* | transmitter on, right frequency, antennas on both boards; more gain |
| locked but `bad` grows | too little signal (low MER) – more TX level / gain, better antennas; QPSK rather than 8PSK |
| MER drops with a strong signal | receiver overloaded – less gain |
| `USB lost` / `queue drops` > 0 | another OTG cable / adapter; no heavy apps at the same time |
| frames but no picture | wrong code rate or modulation in the preset (the beacon is QPSK 3/4) |
| TX: *no ESP32-DATV answer* | the S3 is connected, not the C3 (both are `303a:1001`), or the C3 has another firmware |
| TX: received weak, MER low | move the C3 and its antenna away from the phone |

Bugs and ideas: [issues](https://github.com/ok1cdj/ESP32-ATV/issues).

## Licences

ATV RX: GNU GPL v3. Parts: leansdr / leandvb (GPL-3), LDPC / ldpc_tool (Ahmet Inan, 0BSD-style),
ESP32-DATV-RX (BSD-3-Clause), esp32-sdr-trx (0BSD, S3 firmware).
