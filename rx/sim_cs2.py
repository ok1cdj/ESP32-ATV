#!/usr/bin/env python3
"""cs2 plan, step 1: how much do 2-bit samples (cs2) and the CIC's droop cost, against today's receiver chain? End to end,
no hardware: beacon symbols -> RRC 0.35 at the chip's 16 MS/s -> + white noise at 16 MS/s -> CIC4 /R1 exactly as the
ESP32-S3 computes it (box^4 kernel, decimation phase of dsp.c) -> [FIR] -> quantiser -> leandvb + ldpc_tool.

    third_party/ESP32-DATV-RX/.venv/bin/python rx/sim_cs2.py [--cases 333k 500k 1M] [--esn0 3 9 0.5] [--seeds 1 2]
        [--frames 64] [--jobs 3]

Chains (one set of signal and noise streams per symbol rate, the variants share them):
  cs4-500k   today's --wide mode: CIC /16 -> FIR /2 (dsp_taps_r2_2) -> 500 ksps, 4 bit, per-packet gain (0.75 dB steps)
  f32-1M     CIC /16 -> 1 MS/s floating point: the droop alone        (f32-2M: CIC /8 -> 2 MS/s)
  f32c-1M    the same with an 11-tap droop compensation FIR (+-0.7 dB to 0.34 fs) at 1 MS/s
  cs2-1M     CIC /16 -> 1 MS/s, 2 bit (levels +-1, +-3; threshold at sigma), per-packet gain, no compensation
  cs2c-1M    compensation FIR before the 2-bit quantiser (on the chip)
  cs2pc-1M   2 bit first, compensation on the PC after unpacking
  cs2c-1M+3/-3/+6/-6   cs2c with the gain that many dB off the optimum
- The 1 MS/s symbol rate uses the 500k beacon's symbols (the C3 table stops at 500k; the frames do not depend on the rate).
- Es/N0 is the symbol energy over the noise density of the white noise added at 16 MS/s (the chip's ADC noise: aliasing
  through the CIC is included). Decoded share: TS packets / TS packets of the clean float chain. Threshold: lowest Es/N0
  from which on the share stays >= 90 % (mean over the seeds), plus the share at --high Es/N0. Results: build/sim-cs2/{results.csv,results.md}.
"""
import argparse
import csv
import os
import sys
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace

import numpy as np
from scipy import signal as sps

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
RX = os.path.join(ROOT, "third_party", "ESP32-DATV-RX")
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(RX, "tools"))
from sim_beacon import rrc                     # noqa: E402
from sim_frames import beacon                  # noqa: E402
from sim_wide import decode, quantize          # noqa: E402

OUT = os.path.join(ROOT, "build", "sim-cs2")
FS_CHIP = 16_000_000
NORMAL_SYMS = 33282                            # QPSK normal PLFRAME with pilots
OFFSET = 3000                                  # carrier offset left after tuning [Hz]
CASES = {"333k": (333333, 16), "500k": (500000, 16), "1M": (1000000, 8)}  # symbol rate, R1


def symbols(sr, n):
    d = beacon(min(sr, 500000), "normal")
    nsym = int(open(os.path.join(d, "beacon_cfg.h")).read().split("BEACON_SYMBOLS")[1].split()[0].rstrip("u"))
    b = np.fromfile(os.path.join(d, "beacon.bin"), np.uint8)
    s = np.stack([(b >> (2 * k)) & 3 for k in range(4)], axis=1).ravel()[:nsym]
    sym = (((2.0 * (s & 1) - 1) + 1j * (2.0 * (s >> 1) - 1)) / np.sqrt(2)).astype(np.complex64)
    return np.tile(sym, n // nsym + 1)[:n]


def cic_kernel(r):
    k = np.ones(r)
    for _ in range(3):
        k = np.convolve(k, np.ones(r))
    return k / k.sum()                         # 4r - 3 taps, unity gain at DC


class Cic:
    """Streaming CIC4 /r over 16 MS/s chunks (any length): output j = kernel over the pairs ending at r*j + r - 1."""

    def __init__(self, r):
        self.r, self.k = r, cic_kernel(r).astype(np.float32)
        self.hist = np.zeros(len(self.k) - 1, np.complex64)
        self.pos = 0                           # input index of hist's end (= of the next chunk's start)

    def __call__(self, x):
        buf = np.concatenate([self.hist, x])
        L = len(self.k)
        # outputs whose last input lies in this chunk: input indices n = r*j + r - 1 in [pos, pos + len(x))
        n0 = -(-(self.pos - (self.r - 1)) // self.r) * self.r + (self.r - 1)
        ends = np.arange(n0, self.pos + len(x), self.r) - self.pos + (L - 1)  # positions in buf
        y = np.zeros(len(ends), np.complex64)
        for t in range(L):                     # kernel is symmetric: orientation does not matter
            y += self.k[t] * buf[ends - t]
        self.hist = buf[len(buf) - (L - 1):]
        self.pos += len(x)
        return y


def chip_streams(sr, r1, frames, seeds, chunk_syms=20000):
    """Signal and unit noise (E|n|^2 = 1 at 16 MS/s), both after the CIC; returns (sig, {seed: noise}, Ps at 16 MS/s)."""
    N = FS_CHIP // sr if FS_CHIP % sr == 0 else None
    if N is None:                              # 333333: 48 samples per symbol (16e6 / 48 = 333333.3)
        N = round(FS_CHIP / sr)
    total = frames * NORMAL_SYMS
    sym = symbols(sr, total)
    h = rrc(0.35, N, 10).astype(np.float32) * np.sqrt(N)  # unit symbol energy -> mean |s|^2 = 1
    cic = Cic(r1)
    out, tail, ps_acc, ps_n = [], np.zeros(len(h) - 1, np.complex64), 0.0, 0
    for i in range(0, total, chunk_syms):
        y = sps.upfirdn(h, sym[i:i + chunk_syms], up=N).astype(np.complex64)
        y[:len(tail)] += tail
        tail = y[chunk_syms * N:].copy() if i + chunk_syms < total else np.zeros(0, np.complex64)
        y = y[:min(chunk_syms, total - i) * N]
        ps_acc += float(np.sum(np.abs(y) ** 2)); ps_n += len(y)
        out.append(cic(y))
    sig = np.concatenate(out)
    noise = {}
    nin = total * N
    for s in seeds:
        rng = np.random.default_rng(1000 + s)
        cic = Cic(r1)
        parts = []
        for i in range(0, nin, 1 << 22):
            m = min(1 << 22, nin - i)
            n = ((rng.standard_normal(m, np.float32) + 1j * rng.standard_normal(m, np.float32)) / np.sqrt(2)).astype(np.complex64)
            parts.append(cic(n))
        noise[s] = np.concatenate(parts)
    return sig, noise, ps_acc / ps_n, FS_CHIP / N


def droop_comp(r1, taps=11, edge=0.38):
    """Symmetric FIR at the CIC's output rate that inverts its droop up to `edge` x fs (the signal's edge at 2 sps: 0.34)."""
    f = np.linspace(0, edge, 64)
    hc = np.abs(np.sinc(f) / np.sinc(f / r1)) ** 4   # CIC4 /r1 response at f x (output rate)
    bands = np.column_stack([f[:-1], f[1:]]).ravel()
    want = np.column_stack([1 / hc[:-1], 1 / hc[1:]]).ravel()
    bands = np.append(bands, [edge + 0.04, 0.5])
    want = np.append(want, [0, 0])
    c = sps.firls(taps, bands, want, fs=1.0)
    return c.astype(np.float32)


def fir_r2_2():
    """Today's stage-2 FIR for 500 ksps (dsp_taps.h), unity gain."""
    src = open(os.path.join(RX, "esp32-sdr-trx", "firmware", "src", "dsp_taps.h")).read()
    body = src.split("dsp_taps_r2_2[41] = {")[1].split("}")[0]
    t = np.array([int(v) for v in body.replace("\n", " ").split(",") if v.strip()], np.float32)
    return t / t.sum()


def variants(case):
    sr, r1 = CASES[case]
    fs = FS_CHIP // r1
    k = "1M" if fs == 1_000_000 else "2M"
    v = []
    if case == "333k":
        v.append(("cs4-500k", dict(fir2=True, bits=4, target=2.5)))
    v += [(f"f32-{k}", dict(bits=32)), (f"f32c-{k}", dict(bits=32, comp="chip")),
          (f"cs2-{k}", dict(bits=2, target=1.0)), (f"cs2c-{k}", dict(bits=2, target=1.0, comp="chip"))]
    if case == "500k":
        v.append((f"cs2pc-{k}", dict(bits=2, target=1.0, comp="pc")))
        for db in (3, -3, 6, -6):
            v.append((f"cs2c-{k}{db:+d}", dict(bits=2, target=10 ** (db / 20), comp="chip")))
    return v


def chain(sig, noise, a, opt, r1):
    """Builds one receiver input (complex64 at the output rate) and its rate."""
    fs = FS_CHIP / r1
    x = sig if noise is None else sig + np.float32(a) * noise
    t = np.arange(len(x)) / fs
    x = (x * np.exp(2j * np.pi * OFFSET * t)).astype(np.complex64)
    if opt.get("fir2"):
        x = sps.upfirdn(fir_r2_2(), x, down=2).astype(np.complex64)
        fs /= 2
    if opt.get("comp") == "chip":
        x = sps.lfilter(droop_comp(r1), 1.0, x).astype(np.complex64)
    if opt["bits"] < 16:
        x, _ = quantize(x, opt["bits"], "fine", 256, opt["target"])
    if opt.get("comp") == "pc":
        x = sps.lfilter(droop_comp(r1), 1.0, x).astype(np.complex64)
    return x, fs


def run_one(x, fs, sr, extra=None):
    args = SimpleNamespace(leandvb=os.path.join(RX, "build", "leansdr", "src", "apps", "leandvb"), repeat=1,
                           ldpc_helper=os.path.join(RX, "build", "LDPC", "ldpc_tool"), frame="normal", extra=extra,
                           realtime=False, sr=sr)
    good, bad, locks, ts, _, _ = decode(x, fs, args)
    return ts, good, bad


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--cases", nargs="+", default=list(CASES), choices=list(CASES))
    ap.add_argument("--esn0", type=float, nargs=3, default=[3.0, 9.0, 0.5], metavar=("FROM", "TO", "STEP"))
    ap.add_argument("--seeds", type=int, nargs="+", default=[1, 2])
    ap.add_argument("--frames", type=int, default=64, help="normal PLFRAMEs per run (default 64)")
    ap.add_argument("--jobs", type=int, default=3)
    ap.add_argument("--variants", nargs="*", help="only these chains (default all)")
    ap.add_argument("--extra", help="more leandvb options, e.g. '--sampler rrc --rrc-rej 50'")
    ap.add_argument("--high", type=float, nargs="*", default=[15.0, 25.0], help="also the share at these Es/N0 (one seed)")
    a = ap.parse_args()
    os.makedirs(OUT, exist_ok=True)
    lo, hi, step = a.esn0
    grid = [round(lo + i * step, 2) for i in range(int(round((hi - lo) / step)) + 1)]
    rows, summary = [], []
    pool = ThreadPoolExecutor(a.jobs)
    for case in a.cases:
        sr, r1 = CASES[case]
        sig, noise, ps, _ = chip_streams(sr, r1, a.frames, a.seeds)
        x, fs = chain(sig, None, 0, dict(bits=32, comp="chip"), r1)
        clean = run_one(x, fs, sr, a.extra)[0]
        print(f"{case}: {len(sig)} samples at {FS_CHIP / r1 / 1e6:g} MS/s, clean TS {clean}", flush=True)
        for name, opt in [v for v in variants(case) if not a.variants or v[0] in a.variants]:
            share, thr, run_ok = {}, None, 0
            for e in grid:                     # upwards; stop after three grid points in a row at >= 90 %
                amp = np.sqrt(ps / (sr * 10 ** (e / 10)) * FS_CHIP)
                jobs = []
                for s in a.seeds:
                    x, fs = chain(sig, noise[s], amp, opt, r1)
                    jobs.append(pool.submit(run_one, x, fs, sr, a.extra))
                res = [j.result() for j in jobs]
                share[e] = sum(r[0] for r in res) / len(res) / max(1, clean)
                for s, r in zip(a.seeds, res):
                    rows.append(dict(case=case, variant=name, esn0=e, seed=s, ts=r[0], ok=r[1], bad=r[2],
                                     share=round(r[0] / max(1, clean), 3)))
                print(f"  {name:12s} Es/N0 {e:4.1f}: {100 * share[e]:5.1f} %", flush=True)
                if share[e] >= 0.9:
                    thr = e if thr is None else thr
                    run_ok += 1
                    if run_ok == 3:
                        break
                else:
                    thr, run_ok = None, 0
            high = {}
            for e in a.high:                   # a strong signal: 2-bit samples of it need the right gain
                amp = np.sqrt(ps / (sr * 10 ** (e / 10)) * FS_CHIP)
                x, fs = chain(sig, noise[a.seeds[0]], amp, opt, r1)
                high[e] = run_one(x, fs, sr, a.extra)[0] / max(1, clean)
            summary.append(dict(case=case, variant=name, thr=thr, high=high))
            print(f"  {name}: threshold {thr} dB; " + ", ".join(f"{e:g} dB {100 * v:.0f} %" for e, v in high.items()), flush=True)
        del sig, noise
    with open(os.path.join(OUT, "results.csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    md = ["| symbol rate | chain | threshold Es/N0 (≥ 90 % TS) | " + " | ".join(f"TS at {e:g} dB" for e in a.high) + " |",
          "|---|---|---|" + "---|" * len(a.high)]
    md += [f"| {s['case']} | {s['variant']} | {s['thr'] if s['thr'] is not None else '> ' + str(hi)} dB | "
           + " | ".join(f"{100 * v:.0f} %" for v in s["high"].values()) + " |" for s in summary]
    open(os.path.join(OUT, "results.md"), "w").write("\n".join(md) + "\n")
    print("\n".join(md))


if __name__ == "__main__":
    sys.exit(main())
