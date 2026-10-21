# Changelog

ATV RX (the Android app), the beacon tools and the PC receiver. Versions are the app's release tags.

## Unreleased

### Receiver: gain and drift while receiving
- **Gain − / +** (1 dB, repeating while held) next to the gain field; **while receiving the gain and the drift
  tracking change at once**: the S3 ends a run on any input, so the app stops the run, sets the gain and starts a new
  one, a second after the last step (a series of steps costs one gap). A typed gain applies on Done.
- On the phone: at 400 kS/s each change cost exactly one bad frame and one re-lock, the picture went on; at 40 kS/s
  (normal frames) a change leaves leandvb losing and regaining the lock, with false locks, for a minute or two.
  The S3 has some 13 ms without samples per change (bench); filling that gap is an idea for later.
- Frequency, symbol rate, modulation, code rate, frames and the preset are **greyed out while receiving**: they used to
  stay editable and only took effect after Stop / Start.

## 0.16.1-beta – 2026-10-21

### Receiver: finds the signal again after a loss (400 kS/s)
- **The signal is found again when it comes back:** after a retune the S3's LO sits on the signal, which then fills
  the window and leaves no noise floor, so once the signal had gone (the transmitter off for a while) the search
  could no longer tell it from noise and stayed "no signal" until Stop / Start (OK1PHU's log: three times in
  five minutes). After a loss the LO now goes back to the frequency entered and the search starts over, as at Start.
- **The LO stays within ±150 kHz of the frequency entered:** a window full of noise has a power centre too, and the
  search walked the LO 241 kHz away after a loss (OK1PHU); beyond 150 kHz it goes back to the frequency entered.
- On the bench (phone, C3 at 400 kS/s 60 kHz off, transmitter off for 25, 25 and 70 s): the LO went back each time,
  stayed put on the noise and found the signal again (+51 → +69 → +74 kHz), MER 11–14 dB, no USB loss.
- **CNR is no longer shown when it is not measured** (the window must be at least 3× the symbol rate; at 400 kS/s
  the line said "CNR – dB"): the status line and the recording's .srt.

## 0.16-beta – 2026-10-21

### Receiver: 400 kS/s, retune to the signal
- **400 kS/s is the receiver's top rate** (OK1PHU received it from a PlutoSDR with the Perseverance 0303 firmware).
  500 kS/s (~675 kHz wide) does not fit the S3's 500 ksps and is gone from the receiver's rate list (the TX tab and
  the C3 beacon still go to 500 kS/s, for other receivers).
- **The app retunes the S3 to the signal** in the 500 ksps mode (above 200 kS/s): the LO used to stay put and the NCO
  took the offset out, so a signal 66 kHz off (a Pluto and an S3 differ by some 27 ppm) lost its lower edge (MER
  10.7 dB, 57 locks in 84 s). Now the run stops, the LO moves to the centre and the search runs again, as
  `rx_dvbs2.py` did. A signal wider than the search window, which then fills it, is centred on its power (the
  -3 dB band of the first search is pulled towards 0); also in `rx_dvbs2.py`, where it never found the signal again
  after the first retune and left ~20 kHz to the drift tracking (half a minute at 600 Hz/s).
- On the bench (PC, C3 at 400 kS/s 60 kHz off): +48 → +64 → +69 kHz, leandvb's residual some 50 Hz, MER 12–13 dB
  from the first frame (before: 18 kHz residual, MER 10.9 dB, 35 s until the drift tracking caught up).
- On the phone (same setup, app tuned to 2345.000 MHz): +51.9 → +69.7 → +76.6 kHz, first picture 5.6 s after the
  demodulator started; 141 s with 1600 good frames, none bad, one lock, no USB loss, MER 9–11 dB.

## 0.15-beta – 2026-10-20

### Receiver: leandvb acquisition fixed
- **No more false first lock with normal frames:** leandvb searched for the PLHEADER in a window of 39 % of a normal
  QPSK frame and often locked on the data (MER −11 to −15 dB, picture after 30–50 s or never). In simulation at
  40 kS/s: 24 of 24 right locks at 2.5 s (before 10 of 12 false), also at Es/N0 4.5 dB (before 6 of 6 false).
- **QPSK 3/5 and 5/6 with normal frames now decode:** the first PLSCODE was misread (3/5 as 1/2, 5/6 as 4/5) and the
  carrier kept off by whole cycles. The app's warning about these modes is gone; 8PSK with short frames was never
  broken (a test artifact).
- A patch to leansdr (`tools/leandvb-acquisition.patch` in ESP32-DATV-RX), used by the app and by `rx_dvbs2.py`
  (rerun `third_party/ESP32-DATV-RX/tools/install.sh`). Details: docs/2026-10-19-leandvb-acquisition.en.md.
- On the bench (PC, 0 dB): the 40 kS/s normal-frame beacon gave its first TS after 31.8–32.4 s in 5 of 5 runs with
  one right lock each (before 28–52 s, once none, false first lock in 3 of 5); QPSK 3/5 and 5/6 normal at 125 kS/s
  decoded at once (first TS 13 s).

## 0.14-beta – 2026-10-19

### Problem reports (users with both boards on a hub)
- **Report a problem…** (menu ⋮, and in About): a zip with the app's log, the phone, the USB devices (VID:PID, MAC,
  role, how many: a hub), the boards' firmware, the settings, battery and storage, and the app's own logcat; sent by
  **e-mail** (any app of the share sheet) or attached to a new **GitHub issue** (the zip is saved in Download/ATV RX/,
  the issue is pre-filled). With both boards on a hub the phone's port is taken, so adb is out of reach.
- The app's log is kept in a file (1 MB, rotated) across restarts and crashes: every screen's lines, USB devices coming
  and going, crashes, and every 10 s the receiver's counters (lock, MER, frames, USB lost / jumps / ESP drop, queue) or
  the transmitter's (C3 buffer, underruns), with the battery.

## 0.13-beta – 2026-10-18

### RX: recording (users' request)
- **● REC** while receiving: the received MPEG-TS into a file as it is, nothing transcoded (picture, sound, service
  name), from the next key frame on (PAT and PMT before it), through a queue of its own so that slow storage never
  disturbs reception. Next to it a **.srt** with the time, MER, CNR, carrier offset and frames every second (for range tests). Download/ATV RX/
  (Android 10+, no permission); *Stop REC* in the notification; stops with reception or when the storage is nearly
  full. Works with *Replay recording* too. Plays in VLC / mpv (Linux VLC: the packages vlc-plugin-dvb
  and vlc-plugin-ffmpeg). An empty .ts (nothing decoded) is removed, the .srt kept.
- The spectrum and constellation are cleared after Stop.

## 0.12-beta – 2026-10-18

### Updates (users' request)
- **Update check:** once a day the app asks GitHub for a newer release (betas included) and the web for newer firmware
  of the boards it knows (the C3 tells its version, the S3 the one the app last wrote into it), and shows a dialog:
  *Download* / *Skip this version* / *Later*. Menu ⋮ → *Check for updates* (now) and *Daily update check: on/off*.
  The firmware screen's *Check versions on the web* lists the boards with an older firmware; the TX log says so too.
- Guide: Obtainium for automatic updates; the S3 belongs straight in the port, not on a hub. Web: both firmwares
  (S3 + C3) and flashing from the app on the landing page and the flasher.

## 0.11-beta – 2026-10-16

### TX: your own picture or video (users' request)
- **Picture → picture / video file** (live TX and *Save as beacon*): **File…** picks a picture or video from the phone
  (the system's picker, no storage permission). A picture is sent whole, a video loops at its own pace and turns upright
  by its rotation tag; any resolution (shrunk to the video size). **callsign** puts the camera's strip over it (on by
  default).
- **Sound *from the video*:** its sound track, in sync with the picture on every pass of the loop (up to 10 minutes).
- *Save as beacon*: length **video** = the whole video (up to what the C3's flash holds at that rate).
- The preview shows the chosen file while TX is stopped.

## 0.10-beta – 2026-10-16

### Transmitter calibration (the C3's carrier leakage)
- **In the app:** menu ⋮ → *Transmitter calibration (S3 + C3)* with **both boards on a USB hub**: the C3 sends its
  leakage alone, the S3 measures it, the search takes about 20 s after a warm-up and typically takes 20 dB off; *Save*
  stores it in the C3 (for that frequency) and in the app. Android does not reset the C3 when the app opens it, so the
  calibration holds until the C3 loses power; after a power cycle a stored value is worth about 9 dB (the leakage moves
  by about 2 DAC codes from one start of the C3 to the next). Details: `docs/2026-10-16-lo-calibration.en.md`.
- **Two boards on a hub:** the S3 and the C3 (both 303a:1001) are told apart by their USB serial number (MAC), found out
  once and remembered; RX, TX, the firmware screen (asks which one) and the calibration pick theirs. One board alone
  works as before.
- A firmware upload from the app keeps the C3's correction table and restores it from the app if it is gone; TX takes
  the C3's correction for its frequency.
- **C3 firmware** (fork ESP32-DATV-SA, on the web for the app's and the web flasher): `BDC TEST on [f] | off` (the
  leakage alone, PA off, never saved, off after 120 s without a command), a correction table by frequency in NVS
  (`BDC SAVE`, `PUT`, `LIST`, `GET [f]`, interpolated), new corrections in TEST without restarting the transmitter.
- **PC:** `rx/cal_dc.py --mode test` (20 s instead of 1.5–2 min; `--boots N` averages N starts, `--freqs`, `--eval`,
  `--log`); the search in `rx/dccal/core.py` (a bowl fitted in dB) and in the app (`DcCalibrator.kt`), both tested on
  the same vectors, also runs recorded on the board. The old method (`rx/cal_dc.py` without `--mode`) stays.

### Frequency 2345.000 MHz
- The XIAO ESP32-S3's PLL has a line of its own exactly on every multiple of 20 MHz (2320, 2340, 2360 MHz: 12–28 dB
  above the noise), which at 2340.000 MHz lay inside the signal. `ATV_FREQ`, the app's default and presets and the loop
  tools now use **2345.000 MHz** (ATV segment 2322–2355 MHz). The app keeps a frequency you saved: choose a preset.
- Bench, 40 kS/s short, QPSK 3/4: decodes down to AMP 9 (−30.5 dB; 2 % bad frames), not at AMP 6 – there the leakage
  is stronger than the signal.

### Other
- README and the guides: transmitting needs an amateur radio licence (in bold, EN / CZ), credits to the projects this
  builds on, Buy Me a Coffee; About in the app: the same, with the licences of the C3 firmware.

## 0.9-beta – 2026-10-15

### Sound
- **RX:** the app plays the sound of the service – AAC (ADTS, also HE-AAC) with Android's decoder and MPEG audio
  (**MP2**, MP3) with minimp3 (CC0; Android has no MP2 decoder). The picture follows the sound's clock, so the two stay
  together also when ldpc_tool hands out frames in batches (tens of seconds at 40 kS/s); without sound the picture is
  shown as it comes, as before. ⋮ → *Sound off / on*; the status shows `audio …` and `A/V sync`. AAC in LATM and AC-3
  are named, not played.
- **TX:** *sound*: off, microphone, **CW ID** (the callsign in Morse, 20 WPM, 700 Hz, every 30 s; once per loop in a
  stored beacon) or mic + CW ID; AAC from Android's own encoder (HE-AAC up to 32 kb/s, else AAC-LC). *auto*: no sound
  below 80 kb/s of TS (the picture needs the bits at 40–50 kS/s; choose kb/s to send it), 24 kb/s up to 200 kb/s,
  64 kb/s above. TX with the microphone goes on with the screen off.
- Picture and sound get their time stamps from the moment they were captured (before: when the picture was sent), and
  the codec delay of AAC is taken out: lip sync within 20 ms measured from the TS, on the phone within 3 ms (MP2,
  AAC-LC). The H.264 encoder repeats a frame after two periods instead of one (a late frame came right behind its repeat).
- **PC:** `rx_dvbs2.py` (`rx/rx.sh`) plays the sound too: a service with sound goes straight to ffplay, one without is
  retimed as before; `--no-sound`.
- Bench: 333 kS/s camera + microphone (lip sync OK), 66.7 kS/s CW ID, 40 kS/s mic + CW ID at 12 kb/s, a stored beacon
  with CW ID (once per 19.5 s loop), screen off.
- Tools without hardware: `rx/make_replay.py` (a *Replay* recording from any TS), `tx/mux_selftest.sh` + `tx/avsync.py`
  (TX mux lip sync), `rx/avsync_rx.py` (RX lip sync on a replay); debug-only activities in the app for them.

### Other
- The callsign is the operator's: no ready-made beacons in the repository any more; `make_beacons.sh`, `tx_pc.sh`,
  `level_sweep.sh` need `CALL`, `make_flash_loop.py` needs `--call`.

## 0.8-beta – 2026-10-15

- The app is now **ATV RX/TX** (it transmits too); package and APK file names stay (updates install over 0.7).
- **Firmware from the phone:** menu ⋮ → *Firmware (S3 / C3)* writes the current firmware from the web into the board on
  OTG (ESP32 ROM loader: the chip is detected, S3 receiver / C3 transmitter; MD5 checked; progress bar). Firmware on
  GitHub Pages for both boards (the C3 built by CI), also on the web flasher; a new firmware needs no new app.
- **Stand-alone beacon from the phone:** TX → *Save as beacon in the C3* records the test card or the camera (10–60 s)
  with the current settings and stores it in the C3, which then transmits it by itself after every power-up – any
  symbol rate up to 333 kS/s, any QPSK code rate, short frames, with its frequency and level (bench: 50 kS/s short,
  MER 25 dB). The web firmware transmits nothing until a beacon is stored.
- C3 firmware (fork ESP32-DATV-SA, branch xiao-panel): upstream's video from the flash merged; loops at any rate
  (header version 3); `VIDW` to store a loop over USB; `FW` version in `INFO`.
- PC: `tx/make_flash_loop.py` (a loop from any video), `tx/c3_vidw.py` (store it), `tx/build_c3_web.sh`, `tx/make_fw_site.py`.
- Receivers (app, `rx_dvbs2.py --sr-tol`) tolerate 2000 ppm of symbol rate instead of leandvb's 100: a C3 loop saved for
  333 k runs at 333 333 Bd (160 MHz / 480) and did not lock at the 333 k preset; at 40 k simulated without change.

## 0.7-beta – 2026-10-15

- PC receiver (`rx_dvbs2.py`): follows the transmitter's drift with a software NCO (`--no-track` turns it off): the
  ESP32-C3 drifts by some 260 Hz/s after a reset and leandvb alone loses it beyond ±sr/4.
- The app follows the drift the same way (a smooth ramp instead of 50 Hz steps, which kept up with about 100 Hz/s only);
  the status shows the rate (`drift +46 Hz/s`).
- Layout: a one-line RX | TX switch; everything below the picture scrolls (nothing is cut off any more), status lines
  shortened and sized to the screen's width, gain / drift and Start / ⋮ on rows of their own; TX: pilots next to the
  picture source, the preview shows the test card also while stopped.
- The app warns about modes leandvb does not decode even from a clean signal: QPSK 3/5 and 5/6 with normal frames,
  8PSK with short frames (RX and TX).
- TX: video kb/s and fps chosen from lists with *auto*.
- Guide: what AMP means (DAC drive level, dB table); new screenshots of the RX and TX screens.

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
