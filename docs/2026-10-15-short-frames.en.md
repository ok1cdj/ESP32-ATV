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

## Still to do

Check on the bench and over a distance: first-picture time and frame counts with real hardware, and whether the
drift and fades of boards without a TCXO favour short frames even more.
