# DVB-S2 short frames for the low-rate beacon

*2026-10-15 · [česky](2026-10-15-short-frames.cs.md)*

## Why

The receiver decodes LDPC with [ldpc_tool](https://github.com/Blockstream/LDPC), which works on batches of 32 frames. At
low symbol rates a batch of normal frames (64800 bits) takes long to fill, so the first picture is late:

| Symbol rate | Normal PLFRAME | Batch of 32 | Short PLFRAME | Batch of 32 |
|---|---|---|---|---|
| 40 kS/s | 0.83 s | 27 s | 0.21 s | 6.7 s |
| 50 kS/s | 0.67 s | 21 s | 0.17 s | 5.4 s |
| 66.7 kS/s | 0.50 s | 16 s | 0.13 s | 4.0 s |

(QPSK with pilots: 33282 symbols per normal PLFRAME, 8370 per short one.)

Short frames (16200 bits) are 4× shorter, so the batch fills 4× sooner. The same holds for everything that waits for a
frame: frame lock after a fade, the re-search watchdog, the MER figure. The price is less useful bit rate – the beacon
puts whole TS packets in each frame, 7 per short frame against 32 per normal one at QPSK 3/4, so 13 % less for the same
symbol rate (about 4 % if packets ran across frames) – and in theory a few tenths of a dB of coding gain.

## How to use it

Transmitter (XIAO ESP32-C3, fork [ok1cdj/ESP32-DATV-SA](https://github.com/ok1cdj/ESP32-DATV-SA)):
```sh
FRAME=short tx/make_beacons.sh 40000          # -> tx/beacons/<call>-40k-short/  (also 50000, 66666, …)
tx/flash_tx.sh tx/beacons/<call>-40k-short
```
The test card says "DVB-S2 QPSK 3/4 short 40 kS/s", the front panel "S2 QPSK 3/4 SF"; the C3's `INFO` command shows the
frame size. The loop holds 8 short frames below 66 kS/s, 12 below 100 kS/s and 16 above (8 short frames take the RAM
of 2 normal ones).

Receiver:
- Android app: presets *Beacon 40k / 50k / 66.7k QPSK 3/4 short*, or the frame size *short* / *normal+short*.
- PC: `rx/rx.sh 40000 --frame short` (`--frame auto` takes both).
- Sensitivity sweep on the bench: `FRAME=short rx/level_sweep.sh 40000 120 300 95 30 13`.

## Simulation

`rx/sim_frames.py` runs the whole chain without hardware: beacon from `tx/make_beacons.sh` → `rx/sim_beacon.py`
(RRC 0.35, carrier offset) → `tools/sim_wide.py` of ESP32-DATV-RX (AWGN at the given Es/N0, resampled to the
receiver's 333 ksps, 8 bit) → leandvb (RRC sampler) + ldpc_tool. Signal: 96 normal frames long (80 s at 40 kS/s,
48 s at 66.7 kS/s), the same time for short frames; 3 noise seeds.

Decoded share = TS packets at that Es/N0 / TS packets of the same signal without noise:

| Es/N0 (dB) | 40k normal | 40k short | 66.7k normal | 66.7k short |
|---|---|---|---|---|
| 4.0 | 4.6 % | 20.8 % | 9.5 % | 24.7 % |
| 4.5 | 100 % | 99.1 % | 98.9 % | 96.7 % |
| 5.0 | 81.8 % | 99.7 % | 33.9 % | 93.8 % |
| 5.5 | 83.3 % | 99.9 % | 100 % | 96.9 % |
| 6.0 | 100 % | 99.7 % | 101 % | 100 % |
| 6.5 | 65.1 % | 100 % | 101 % | 100 % |
| 7.0 | 66.1 % | 100 % | 83.1 % | 100 % |

| | 40k normal | 40k short | 66.7k normal | 66.7k short |
|---|---|---|---|---|
| first TS (real time) | 59 s at 8 dB | 8.4 s at 5.5 and 8 dB | 40 s at 8 dB | 8.4 s at 5.5 dB, 5.2 s at 8 dB |
| LDPC iterations at 4.5 dB | – | 16.3 | – | 14.0 |

Findings:
- **Threshold** about Es/N0 4.5 dB for both frame sizes (no difference at a 0.5 dB step).
- **Above the threshold short frames stay steady** (≥ 94 %), normal ones swing: losing one batch of 32 frames costs
  27 s of signal at 40 kS/s, and with only 2–3 batches per run one loss moves the share by a third. More runs would be
  needed for a fair figure for normal frames; the swing itself is what a viewer sees.
- **First picture** 5–8 s with short frames against 40–59 s with normal ones. That is more than the batch time
  (27 / 16 s) because the first, partial batch is usually lost.

Reproduce:
```sh
PY=third_party/ESP32-DATV-RX/.venv/bin/python
$PY rx/sim_frames.py --build-iter --rates 40000 66666 --esn0 4 7 0.5 --seeds 1 2 3  # results in build/sim-frames/
```

## On the bench (2026-10-15)

XIAO ESP32-C3 beacon 40 kS/s QPSK 3/4 at 0 dB (AMP 300), 2340.000 MHz, XIAO ESP32-S3 at gain 10 dB, antennas on the
same desk. The C3 drifted +20 to +30 kHz from the receiver's LO while warming up. Times from the start of leandvb:

| | normal frames | short frames |
|---|---|---|
| PC (`rx/rx.sh`), first TS | 28, 33, 35, 35 s; once none in 95 s | 8.4–8.8 s (4 runs, one with `--frame auto`) |
| Android, first picture | 29.0, 29.2 s; once none in 33 s | 8.7, 8.8, 9.0 s |
| first lock (PC) | 5 of 5 false (MER −11 to −15 dB, ±7–10 kHz off), re-lock after ~10 s or not at all | 5 of 5 right away (MER 14–24 dB) |
| after the first picture | 97 of 98 frames | 385 of 386 frames (90 s), phone 96 of 96, MER 26 dB |

- Short frames bring the picture **3.3× (phone) to 3.8× (PC) sooner** on the bench, close to the batch time (6.7 s) plus a frame or two.
- A surprise the simulation did not show: with normal frames leandvb's **first lock was a false one** every time; with
  short frames, whose PLHEADERs come 4× as often, never. In the simulation the carrier was 3 kHz off, here 20–30 kHz.
- A receiver set to normal frames does not decode a short-frame beacon (0 frames, MER fine) – pick the right preset or
  *normal+short*.
- The threshold was not measured on the bench (near field); the simulation says it is the same.

## The false first lock (simulation, 2026-10-15)

`rx/sim_lock.py` feeds leandvb in real time with the receivers' options and logs FRAMELOCK, FREQ and MER, so a false
lock shows up directly. At 40 kS/s, Es/N0 20 dB, 3 seeds per case:

- **Normal frames lock falsely without anything added** – in 9 of 12 runs (carrier 300 or 3000 Hz off, drift 0 or
  70 Hz/s); the first TS came after 32–50 s, twice not in 50 s. **Short frames: 12 of 12 right at once**, first TS 8.4 s.
- **Not the carrier leakage:** a tone of −40, −20 or −10 dBc at the carrier (the C3's leakage) or at 0 Hz (DC left in the
  receiver) changes nothing. The C3's calibrated leakage is about −54 dBc at 0 dB anyway.
- **Why** (leansdr `s2_frame_receiver`): the carrier frequency is first estimated from the 90 symbols of the PLHEADER,
  then refined from the phase of the pilots, 1476 symbols apart. A wrong start can hold – the pilots line up, the data
  rotate (MER < 0) – and is checked only once per frame: every 0.83 s with normal frames, 0.21 s with short ones.
  Tracking may go as far as ±Rs/4 (`--freq-tol 0.25`), hence the ±10 kHz seen on the bench. After losing a lock,
  leandvb throws away about 5 normal frames of data (4 s at 40 kS/s) before it searches again.
- **`--fastlock`** stops that throwing away. It does not prevent the false first lock, but leandvb gets out of it sooner:
  first TS 31–40 s in all 6 runs, against 34–44 s and 2 runs without a picture; near the threshold (Es/N0 5–6 dB) it
  decoded as much or more. A narrower `--freq-tol` only moves the false lock to the new limit and loses real signals
  that drift – not used. The PC receiver and the app now run leandvb with `--fastlock` below 100 kS/s.

On the bench (same beacon, 40 kS/s normal frames, 0 dB) `--fastlock` gave a picture in every run but hardly sooner:
PC first TS 28–52 s after leandvb started (5 of 5; before 28–35 s and once none), false first lock in 3 of 5 (before
5 of 5), once 15 false locks in a row before the right one; phone first picture 29.0, 30.0, 37.3 s (before 29.0, 29.2 s
and once none in 33 s). The C3, just reset, drifted by about 260 Hz/s while warming up; the PC receiver, which does
not follow drift as the app does, came close to the ±10 kHz limit.

Short frames remain the better choice at low symbol rates.

```sh
PY=third_party/ESP32-DATV-RX/.venv/bin/python
$PY rx/sim_lock.py --frame normal short --offset 300 3000 --drift 0 70        # results per run; logs in build/sim-lock/
$PY rx/sim_lock.py --frame normal --tone -40 -20 -10 --tone-at carrier lo
$PY rx/sim_lock.py --frame normal --drift 70 --extra - fastlock freq-tol=0.1
```
