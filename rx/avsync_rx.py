#!/usr/bin/env python3
"""Lip sync of the app's RX (debug RxReplayTestActivity on a replay of a tx/mux_selftest.sh TS: a white flash and a
1 kHz beep every 2 s): when the beep comes out of the track (its PTS on the audio clock) vs when the white picture is
to be shown (the render time the decoder was given; which PTS are white comes from the TS, decoded with ffmpeg).

    python3 rx/avsync_rx.py NAME.pcm NAME.ev SOURCE.ts [CHANNELS]
"""
import subprocess
import sys

import numpy as np

pcm_file, ev_file, ts_file = sys.argv[1], sys.argv[2], sys.argv[3]
ch = int(sys.argv[4]) if len(sys.argv) > 4 else 2

# the white pictures of the TS: their PTS (90 kHz, as in the PES headers)
fr = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v", "-show_entries", "frame=pts", "-of", "csv=p=0", ts_file],
                    capture_output=True, text=True).stdout.split()
vpts = [int(v.strip(",")) for v in fr if v.strip(",").isdigit()]
raw = subprocess.run(["ffmpeg", "-v", "error", "-i", ts_file, "-map", "0:v", "-vf", "scale=64:36", "-fps_mode", "passthrough",
                      "-pix_fmt", "gray", "-f", "rawvideo", "-"], capture_output=True).stdout
luma = np.frombuffer(raw, np.uint8).reshape(-1, 64 * 36).mean(1)
white = {vpts[i] for i in range(1, min(len(vpts), len(luma))) if luma[i] > 200 and luma[i - 1] <= 200}
blocks, clock, imgs, renders = [], [], [], []
for line in open(ev_file):
    p = line.split()
    if p[0] == "PCM":
        blocks.append((int(p[1]), int(p[2]), int(p[3])))
    elif p[0] == "CLOCK":
        clock.append((int(p[1]), int(p[2])))
    elif p[0] == "IMG":
        imgs.append(int(p[1]))
    elif p[0] == "RENDER":
        renders.append((int(p[1]), int(p[2])))
x = np.fromfile(pcm_file, np.int16).astype(float).reshape(-1, ch)[:, 0]
hz = blocks[0][2]
# PTS of every sample from its block
pts = np.empty(len(x))
k = 0
for bp, n, r in blocks:
    pts[k:k + n] = bp + np.arange(n) * 90000 / r if bp >= 0 else np.nan
    k += n
env = np.convolve(np.abs(x), np.ones(hz // 100) / (hz // 100), "same")
on = env > 2000
starts = [i for i in range(1, len(on)) if on[i] and not on[i - 1]]
starts = [s for j, s in enumerate(starts) if j == 0 or s - starts[j - 1] > hz]
cn = np.array([c[0] for c in clock], float); cp = np.array([c[1] for c in clock], float)
beeps = []
for s in starts:
    bp = pts[s]
    j = np.searchsorted(cp, bp)
    if 0 < j < len(cp) and cp[j] - cp[j - 1] < 9000:      # bracketed by clock samples 20 ms apart, no jump
        beeps.append(cn[j - 1] + (bp - cp[j - 1]) / (cp[j] - cp[j - 1]) * (cn[j] - cn[j - 1]))
flashes = [at for p, at in renders if p in white]
rt = {a for _, a in renders}
match = sum(1 for t in imgs if t in rt)
print(f"PCM {len(x) / hz:.1f} s, beeps {len(starts)} (on the clock {len(beeps)}), pictures {len(imgs)} "
      f"(ImageReader time stamp = render time: {match}), flashes {len(flashes)} of {len(white)} in the TS, clock samples {len(clock)}")
d = [(b - min(flashes, key=lambda f: abs(f - b))) / 1e6 for b in beeps] if flashes else []
d = [v for v in d if abs(v) < 900]
if d:
    print("beep - flash [ms]: %s  (mean %.0f)" % (" ".join("%.0f" % v for v in d), np.mean(d)))
