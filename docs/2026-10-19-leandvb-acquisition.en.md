# leandvb: the false first lock and QPSK 3/5, 5/6 (2026-10-19)

[Česky](2026-10-19-leandvb-acquisition.cs.md)

Three things the receivers (the app and `rx_dvbs2.py`, both on leandvb from pabr/leansdr `work` 84c59e1) did wrong,
all in how leandvb acquires a DVB-S2 signal (`s2_frame_receiver` in `dvbs2.h`), and the fix:
`tools/leandvb-acquisition.patch` in ESP32-DATV-RX, applied by its `tools/install.sh` (PC) and by
`android/native/build-native.sh` (app). Upstream leansdr has no fix.

## What went wrong

| Symptom | Cause |
|---|---|
| Normal frames at low rates: the first lock was usually false (MER −11 to −15 dB, carrier kHz off), the picture came after 30–50 s or never | The PLHEADER search looked at a window of 13050 symbols, 39 % of a normal QPSK frame (33282): often there was no PLHEADER in it and the best correlation was somewhere in the data. leandvb locked on that. Short frames (8370 symbols) always fit, so they never showed it. |
| QPSK 3/5 and 5/6 with normal frames never decoded, even from a clean signal (locked, MER fine, every frame failed) | The first frame's PLSCODE was decoded with only the rough frequency of the differential correlator. A rotation of about 180° across the PLHEADER turns a code into the one that differs in its second half: QPSK 3/5 was read as 1/2, 5/6 as 4/5. The frequency fit on that wrong PLHEADER left the carrier off by whole cycles between pilots. Pilots and PLHEADERs then lined up, the data in between rotated. leandvb never checks the carrier again once locked. |
| 8PSK with short frames never decoded in `rx/test_dvbs2enc.py` | Not leandvb: it keeps two of the longest frames of input unprocessed, so 40 short 8PSK frames did not fill one 32-frame batch of ldpc_tool. The test now sends 56. |

Our encoder was right all along: its codewords pass every LDPC and BCH check, and leansdr's own transmitter
(`leandvbtx`) gave the same failure when its symbols went through the same channel.

## The fix

1. With normal frames allowed, the PLHEADER search covers the longest frame.
2. On the first frame, every allowed PLSCODE gets its own frequency, phase and amplitude fit; the best MER wins.
   A candidate whose frequency fit lands more than Rs/16 from the LLR decoder's choice is skipped: the PILOTS bit
   negates every other symbol, which a fit takes for a carrier shift of Rs/2.
3. A first PLHEADER more than 6 dB below the Es/N0 its MODCOD needs is not locked on.

## Results

`rx/sim_lock.py` (leandvb in real time, the receivers' options), 6 seeds per case:

| Case | Before | After |
|---|---|---|
| 40 kS/s normal, Es/N0 20 dB, carrier 0.3 / 3 kHz off, drift 0 / 70 Hz/s | 10 of 12 false (2026-10-15: 9 of 12) | 24 of 24 right, one lock, locked at 2.5 s, first TS 28.3 s |
| 40 kS/s short, same | right | 24 of 24 right, locked at 1.9 s, first TS 8.4 s |
| 40 kS/s normal, Es/N0 4.5 dB | 6 of 6 false, picture in 2 | 6 of 6 right, picture in all |
| 40 kS/s short, Es/N0 4.5 dB | 4 of 6 false | 6 of 6 right |
| 333 kS/s normal and short | right | right, unchanged (first TS 3.3 / 1.0 s) |

`rx/test_dvbs2enc.py` part B (every QPSK and 8PSK code rate, normal and short, clean signal): all decode, QPSK 3/5 and
5/6 normal included. With normal frames the first picture still takes about 28 s at 40 kS/s: ldpc_tool's batch of 32
frames, not the acquisition. Short frames remain the better choice at low rates.

On the bench (2026-10-20, C3 → S3 on the PC, 0 dB, `rx/rx.sh`, the C3 just started and drifting 20–300 Hz/s):

| Case | Before (2026-10-15, with `--fastlock`) | After |
|---|---|---|
| 40 kS/s normal beacon, 5 runs | first TS 28–52 s, once none; false first lock in 3 of 5, once 15 in a row | first TS 31.8, 32.4, 32.0, 31.8, 32.4 s; one lock per run, right from 8.7 s; MER 12–19 dB |
| QPSK 3/5 normal, 125 kS/s (`tx/tx_s2enc.py`) | no frame | first TS 12.9 s, 193 frames in 60 s, one lock, MER ~14 dB |
| QPSK 5/6 normal, 125 kS/s | no frame | first TS 13.1 s, 192 frames, one lock, MER ~13.5 dB |

Without any signal leandvb still locks on noise now and then (every 15–20 s, MER −13 to −16 dB): harmless, but the
app shows a lock.

Left over: once in the test (QPSK 4/5 short, one data pattern, clean signal) the carrier after the PLHEADER was about
335 ppm of Rs off. That is half a cycle between pilots, and the lock went wrong. Unpatched leandvb does the same; other
data decodes. It has not been seen with noise (72 simulated short-frame runs).

To get it: PC `third_party/ESP32-DATV-RX/tools/install.sh` (rebuilds leandvb), app from this release on.
