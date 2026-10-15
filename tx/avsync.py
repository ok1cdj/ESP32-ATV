#!/usr/bin/env python3
"""Check a MuxSelfTest TS (tx/mux_selftest.sh) - streams, decode errors, PTS vs arrival (PCR), and the lip sync of the white flash vs the beep."""
import json, subprocess, sys
import numpy as np

f = sys.argv[1]
probe = json.loads(subprocess.run(["ffprobe", "-v", "error", "-show_streams", "-show_programs", "-of", "json", f], capture_output=True, text=True).stdout)
for s in probe["streams"]:
    print("stream", s["index"], s["codec_name"], s.get("profile", ""), s.get("width", ""), s.get("sample_rate", ""), "pid", s.get("id"))
err = subprocess.run(["ffmpeg", "-v", "error", "-i", f, "-f", "null", "-"], capture_output=True, text=True).stderr.strip().splitlines()
print("decode errors:", len(err), *err[:5], sep="\n  " if err else " ")

# video: per frame PTS and mean luma
fr = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v", "-show_entries", "frame=pts_time", "-of", "csv=p=0", f], capture_output=True, text=True).stdout.split()
pts_v = np.array([float(x) for x in fr])
w, h = 64, 36
raw = subprocess.run(["ffmpeg", "-v", "error", "-i", f, "-map", "0:v", "-vf", f"scale={w}:{h}", "-fps_mode", "passthrough", "-pix_fmt", "gray", "-f", "rawvideo", "-"], capture_output=True).stdout
luma = np.frombuffer(raw, np.uint8).reshape(-1, h * w).mean(1)
n = min(len(luma), len(pts_v)); luma, pts_v = luma[:n], pts_v[:n]
flash = [pts_v[i] for i in range(1, n) if luma[i] > 200 and luma[i - 1] <= 200]

# audio: beep onsets from the PCM, on the audio stream's own time line
start_a = float(subprocess.run(["ffprobe", "-v", "error", "-select_streams", "a", "-show_entries", "stream=start_time", "-of", "csv=p=0", f], capture_output=True, text=True).stdout.split()[0])
sr = 8000
pcm = subprocess.run(["ffmpeg", "-v", "error", "-i", f, "-map", "0:a", "-ac", "1", "-ar", str(sr), "-f", "s16le", "-"], capture_output=True).stdout
x = np.abs(np.frombuffer(pcm, np.int16).astype(float))
env = np.convolve(x, np.ones(40) / 40, "same")
on = env > 2000
beeps = [start_a + i / sr for i in range(1, len(on)) if on[i] and not on[i - 1]]
beeps = [b for k, b in enumerate(beeps) if k == 0 or b - beeps[k - 1] > 1.0]
print("flashes", len(flash), "beeps", len(beeps))
d = []
for b in beeps:
    if flash:
        v = min(flash, key=lambda t: abs(t - b))
        if abs(v - b) < 0.9: d.append(b - v)
if d:
    d = np.array(d) * 1000
    print("audio - video [ms]: mean %.0f, min %.0f, max %.0f (n %d; a frame is %.0f ms)" % (d.mean(), d.min(), d.max(), len(d), 1000 * np.median(np.diff(pts_v))))
