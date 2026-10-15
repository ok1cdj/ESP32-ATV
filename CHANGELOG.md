# Changelog

ATV RX (the Android app), the beacon tools and the PC receiver. Versions are the app's release tags.

## Unreleased

- PC receiver (`rx_dvbs2.py`): follows the transmitter's drift with a software NCO (`--no-track` turns it off): the
  ESP32-C3 drifts by some 260 Hz/s after a reset and leandvb alone loses it beyond ±sr/4.
- The app follows the drift the same way (a smooth ramp instead of 50 Hz steps, which kept up with about 100 Hz/s only);
  the status shows the rate (`drift on +46 Hz/s`).
- The app warns about modes leandvb does not decode even from a clean signal: QPSK 3/5 and 5/6 with normal frames,
  8PSK with short frames (RX and TX).

## 0.6-beta – 2026-10-15

- **RX | TX** switch at the top of the app: receive with the ESP32-S3, transmit with the ESP32-C3.
- **TX (beta of a beta):** a XIAO ESP32-C3 with the ESP32-DATV(-SA) firmware on USB-OTG sends a test card with your
  callsign live as DVB-S2 QPSK (any code rate, normal or short frames, pilots, 40–500 kS/s). Guide: section 5.
  Our own DVB-S2 encoder (`android/native/dvbs2enc`, bit-exact with ESP32-DATV's encoders; tests in
  `rx/test_dvbs2enc.py`). Bench: 50 kS/s short frames MER 17 dB, 333 kS/s MER 16 dB, picture decodes. Keep the C3 and
  its antenna away from the phone (on the phone the signal came 20 dB weaker and did not decode).
- **TX from the camera:** picture *camera (back)* / *camera (front)*: the phone's camera (Camera2), turned upright for
  the way the phone is held, with the callsign and the mode in a strip at the bottom. Bench: 333 kS/s 640x360 ~16 fps,
  MER 15 dB, watched live on the PC.
- TX runs in a foreground service (notification with Stop TX): it goes on with the screen off, also from the camera.
- TX in **8PSK** (3/5 … 9/10; bench: 8PSK 2/3 333 kS/s normal frames, MER 16 dB); video bit rate and frame rate by hand
  (empty = automatic; the picture size follows the bit rate).
- leandvb runs with `--fastlock` below 100 kS/s (app and `rx_dvbs2.py`): with normal frames its first lock is often a
  false one, and it no longer throws away ~4 s of signal before searching again (first picture 31–40 s instead of
  34–44 s or none, in simulation; on the bench a picture in every run, but hardly sooner). Details and the new simulator `rx/sim_lock.py`:
  [short frames, false first lock](docs/2026-10-15-short-frames.en.md#the-false-first-lock-simulation-2026-10-15).

## 0.5-beta – 2026-10-15

### DVB-S2 short frames
At low symbol rates the first picture comes about 3.5× sooner: on the bench at 40 kS/s 8.4–9.0 s instead of 28–35 s,
and the receiver locks right at once (with normal frames the first lock was a false one in every PC run). Details:
[docs/2026-10-15-short-frames.en.md](docs/2026-10-15-short-frames.en.md).
- Beacon: `FRAME=short tx/make_beacons.sh 40000` (fork ESP32-DATV-SA `make_beacon.py --frame short`).
- App: presets *Beacon 40k / 50k / 66.7k QPSK 3/4 short*.
- PC: `rx/rx.sh 40000 --frame short` (`rx_dvbs2.py --frame normal/short/auto`), `FRAME=short rx/level_sweep.sh`,
  simulation `rx/sim_frames.py`.

### App
- Spectrum view (raw samples, NCO line, signal band) next to the constellation; info across the full width.
- The receiver's own DC is removed (8-bit mode): reception failed when the beacon's carrier was a few kHz from the LO.
- Drift tracking can be turned off (*drift* switch).
- Save / Delete preset, Replay, USB test and About moved to the ⋮ menu.
- A new Start shows a black picture until new video is decoded (it used to show the last picture of the previous run)
  and resets the video figures.
- The log tells how long the first TS and the first picture took.

### PC receiver (fork ESP32-DATV-RX)
- MER, CNR and FREQ in the `rx_dvbs2.py` status line.
- DC removal in the 8-bit (cs8) mode, as in the app.

### Docs
- README: short frames; Android guide: first picture with short frames.

## 0.4-beta – 2026-10-14

First public beta.
- Android app ATV RX: XIAO ESP32-S3 over USB-OTG, leandvb + ldpc_tool (NEON) on the phone, H.264/HEVC picture with
  MediaCodec, reception in a foreground service; DVB-S2 QPSK / 8PSK, code rate and frame size choice, drift tracking,
  constellation, MER / CNR, frame and TS counters, service name, presets.
- Beacon on the XIAO ESP32-C3 at 2340.000 MHz, 40–500 kS/s, with DC correction of the carrier leakage.
- GitHub Pages: guide (English / Czech) and a web flasher for the S3 receiver firmware; CI builds the APK.
