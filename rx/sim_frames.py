#!/usr/bin/env python3
"""Normal vs short DVB-S2 frames of the beacon, simulated end to end (no hardware): threshold, time to first TS, LDPC
iterations. For each symbol rate and frame size:
  beacon (tx/make_beacons.sh, FRAME=normal|short) -> rx/sim_beacon.py (RRC 0.35, carrier offset) ->
  ESP32-DATV-RX tools/sim_wide.py (AWGN at Es/N0, resample to the receiver's 333 ksps, 8 bit) -> leandvb + ldpc_tool.

    third_party/ESP32-DATV-RX/.venv/bin/python rx/sim_frames.py [--rates 40000 66666] [--esn0 1 7 0.5] [--seeds 1 2 3]

- Decoded share: TS packets at an Es/N0 / TS packets of the same signal without added noise (the clean run loses the same
  partial ldpc_tool batch at the end of the file, so the ratio is fair). Threshold: lowest Es/N0 where the mean share
  over the seeds is >= 90 %. The signal lasts 96 normal PLFRAMEs (80 s at 40 kS/s), the same time for short frames.
- Time to first TS: leandvb fed in real time (sim_wide.py --realtime), seconds from the first sample to the first TS bytes.
- LDPC iterations: mean per frame from ldpc_tool_iter (ldpc_tool built with tools/ldpc_tool-iterations.patch, -DITER_REPORT;
  build it with --build-iter). Results: build/sim-frames/{results.csv,results.md}.
"""
import argparse
import csv
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
RX = os.path.join(ROOT, "third_party", "ESP32-DATV-RX")
PY = os.path.join(RX, ".venv", "bin", "python")
LDPC = os.path.join(RX, "build", "LDPC")
OUT = os.path.join(ROOT, "build", "sim-frames")
SPS = 5                                             # reference samples per symbol (fs_in = 5 x SR)
NORMAL_SYMS = 33282                                 # QPSK normal PLFRAME with pilots


def build_iter():
    """ldpc_tool_iter: ldpc_tool with the iteration report, next to ldpc_tool (same objects and flags as tools/install.sh)."""
    src = os.path.join(OUT, "ldpc_tool_iter.cc")
    subprocess.run(["patch", "-s", "-o", src, os.path.join(LDPC, "ldpc_tool.cc"),
                    os.path.join(RX, "tools", "ldpc_tool-iterations.patch")], check=True)
    subprocess.run(["g++", "-march=native", "-std=c++11", "-W", "-Wall", "-Ofast", "-fno-exceptions", "-fno-rtti",
                    "-DITER_REPORT", "-I", LDPC, src] + [os.path.join(LDPC, o + ".o") for o in
                   ("tables_handler", "itls_handler", "mods_handler")] + ["-o", os.path.join(LDPC, "ldpc_tool_iter")], check=True)


def beacon(sr, frame):
    d = os.path.join(OUT, "beacons")
    subprocess.run([os.path.join(ROOT, "tx", "make_beacons.sh"), str(sr)], check=True, stdout=subprocess.DEVNULL,
                   env=dict(os.environ, CALL="N0CALL", FRAME=frame, OUTDIR=d))
    return os.path.join(d, f"n0call-{(sr + 500) // 1000}k" + ("-short" if frame == "short" else ""))


def reference(sr, frame, seconds, offset):
    b = beacon(sr, frame)
    nsym = int(re.search(r"BEACON_SYMBOLS\s+(\d+)", open(os.path.join(b, "beacon_cfg.h")).read()).group(1))
    loops = int(seconds * sr / nsym + 0.999)
    ref = os.path.join(OUT, f"ref-{sr}-{frame}.cf32")
    if not os.path.exists(ref):
        subprocess.run([PY, os.path.join(HERE, "sim_beacon.py"), os.path.join(b, "beacon.bin"), "-o", ref, "--sps", str(SPS),
                        "--baud", str(sr), "--loops", str(loops), "--offset", str(offset), "--symbols", str(nsym)],
                       check=True, stdout=subprocess.DEVNULL)
    return ref


def run(ref, sr, frame, esn0=None, seed=1, realtime=False):
    # 66666 x 5 = 333330: no resampling for a 0.001 % difference (the ratio has no small fraction for the resampler)
    fs_out = SPS * sr if abs(SPS * sr - 333333) < 333 else 333333
    cmd = [PY, os.path.join(RX, "tools", "sim_wide.py"), ref, "--fs-in", str(SPS * sr), "--fs-out", str(fs_out), "--sr", str(sr),
           "--bits", "8", "--scale", "fixed", "--target", "20", "--repeat", "1", "--seed", str(seed), "--frame", frame,
           "--ldpc-helper", os.path.join(LDPC, "ldpc_tool_iter"), "--extra", "--sampler rrc --rrc-rej 50"]
    if esn0 is not None:
        cmd += ["--esn0", str(esn0)]
    if realtime:
        cmd += ["--realtime", "--linger", "5"]
    line = subprocess.run(cmd, capture_output=True, text=True, check=True).stdout.strip().splitlines()[-1]
    num = lambda k: (m.group(1) if (m := re.search(k + r"\s+([0-9.]+)", line)) else None)
    return dict(ok=int(num("frames ok")), bad=int(num("bad")), ts=int(num("TS")),
                first=float(num("first TS")) if num("first TS") else None,
                iters=float(num("LDPC iterations")) if num("LDPC iterations") else None)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--rates", type=int, nargs="+", default=[40000, 66666])
    ap.add_argument("--esn0", type=float, nargs=3, default=[1.0, 7.0, 0.5], metavar=("FROM", "TO", "STEP"))
    ap.add_argument("--seeds", type=int, nargs="+", default=[1, 2, 3])
    ap.add_argument("--offset", type=float, default=3000, help="carrier offset left after tuning [Hz] (default 3000)")
    ap.add_argument("--build-iter", action="store_true", help="(re)build ldpc_tool_iter first")
    ap.add_argument("--no-realtime", action="store_true", help="skip the time-to-first-TS runs")
    a = ap.parse_args()
    os.makedirs(OUT, exist_ok=True)
    if a.build_iter or not os.path.exists(os.path.join(LDPC, "ldpc_tool_iter")):
        build_iter()
    lo, hi, step = a.esn0
    grid = [round(lo + i * step, 2) for i in range(int(round((hi - lo) / step)) + 1)]
    rows, summary = [], []
    for sr in a.rates:
        seconds = 96 * NORMAL_SYMS / sr
        for frame in ("normal", "short"):
            ref = reference(sr, frame, seconds, a.offset)
            clean = run(ref, sr, frame)
            print(f"{sr} {frame}: clean TS {clean['ts']}, frames ok {clean['ok']}", flush=True)
            share = {}
            for e in grid:
                rs = [run(ref, sr, frame, e, s) for s in a.seeds]
                share[e] = sum(r["ts"] for r in rs) / len(rs) / max(1, clean["ts"])
                it = [r["iters"] for r in rs if r["iters"] is not None]
                for s, r in zip(a.seeds, rs):
                    rows.append(dict(sr=sr, frame=frame, esn0=e, seed=s, ok=r["ok"], bad=r["bad"], ts=r["ts"],
                                     share=round(r["ts"] / max(1, clean["ts"]), 3), iters=r["iters"]))
                print(f"  Es/N0 {e:4.1f}: decoded {100 * share[e]:5.1f} %, LDPC iterations "
                      f"{sum(it) / len(it) if it else float('nan'):.1f}", flush=True)
            thr = next((e for e in grid if share[e] >= 0.9 and all(share[x] >= 0.9 for x in grid if x >= e)), None)
            itr = [r["iters"] for r in rows if r["sr"] == sr and r["frame"] == frame and thr is not None
                   and r["esn0"] == thr and r["iters"] is not None]
            first = {}
            if not a.no_realtime:
                for e in ([thr + 1.0] if thr is not None else []) + [8.0]:
                    first[e] = run(ref, sr, frame, e, 1, realtime=True)["first"]
                    print(f"  first TS at Es/N0 {e:.1f}: {first[e]} s", flush=True)
            summary.append(dict(sr=sr, frame=frame, thr=thr, iters=(sum(itr) / len(itr) if itr else None), first=first))
    with open(os.path.join(OUT, "results.csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    md = ["| SR | frames | threshold Es/N0 (≥ 90 % TS) | LDPC iterations at threshold | first TS (s) |", "|---|---|---|---|---|"]
    for s in summary:
        ft = ", ".join(f"{v:.1f} at {e:g} dB" if v is not None else f"– at {e:g} dB" for e, v in s["first"].items()) or "–"
        md.append(f"| {s['sr'] / 1e3:.1f}k | {s['frame']} | {s['thr'] if s['thr'] is not None else '> ' + str(hi)} dB | "
                  f"{s['iters']:.1f} | {ft} |" if s["iters"] is not None else
                  f"| {s['sr'] / 1e3:.1f}k | {s['frame']} | {s['thr']} dB | – | {ft} |")
    open(os.path.join(OUT, "results.md"), "w").write("\n".join(md) + "\n")
    print("\n".join(md))


if __name__ == "__main__":
    sys.exit(main())
