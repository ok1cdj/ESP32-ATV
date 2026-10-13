#!/usr/bin/env python3
"""Like ESP32-DATV-RX's tools/sim_wide.py, with one more step before leandvb: decimation of the received stream to about
--sps samples per symbol. At low symbol rates the 333 ksps stream has 3..8 samples per symbol; this tests whether
lowpass + decimation (and leandvb's default linear sampler) beats leandvb's RRC sampler on the full-rate stream.

    rx/sim_decim.py build/ref_67k.cf32 --fs-in 1000000 --fs-out 333333.333 --sr 66666 --bits 8 --esn0 9 \
        --sps 2 --seed 1 --ldpc-helper third_party/ESP32-DATV-RX/build/LDPC/ldpc_tool

Pipeline: noise (at fs-in) -> resample to fs-out (the chip's rate) -> quantise (cs8 / cs4) -> [decimate to sps x sr] -> leandvb.
"""
import argparse
import os
import sys
from fractions import Fraction

import numpy as np
from scipy import signal

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "third_party", "ESP32-DATV-RX", "tools"))
from sim_wide import add_noise, decode, quantize, resample  # noqa: E402


def decimate(x, fs, sr, sps, taps):
    """Lowpass + rational resampling of x (at fs) to about sps samples per symbol. The passband keeps the signal
    (half bandwidth 0.675 x sr for roll-off 0.35); the stopband starts where the new rate would alias into it."""
    r = Fraction(sps * sr / fs).limit_denominator(64)
    up, down = r.numerator, r.denominator
    if up >= down:
        return x, fs
    fs_hi = fs * up
    fs_new = fs * up / down
    edge_pass = 0.675 * sr
    edge_stop = fs_new - edge_pass                                   # what lands above this aliases into the signal
    h = signal.remez(taps * max(up, down) + 1, [0, edge_pass, edge_stop, fs_hi / 2], [up, 0], fs=fs_hi)
    return signal.resample_poly(x, up, down, window=h).astype(np.complex64), fs_new


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("ref")
    ap.add_argument("--fs-in", type=float, default=1000000)
    ap.add_argument("--fs-out", type=float, default=333333.333)
    ap.add_argument("--sr", type=float, required=True)
    ap.add_argument("--bits", type=int, default=8)
    ap.add_argument("--scale", choices=["fixed", "block", "fine"], default="block")
    ap.add_argument("--block", type=int, default=256)
    ap.add_argument("--target", type=float, default=2.5)
    ap.add_argument("--esn0", type=float)
    ap.add_argument("--sps", type=float, default=0, help="decimate to about this many samples per symbol (0 = no decimation)")
    ap.add_argument("--taps", type=int, default=24, help="filter taps per polyphase branch (default 24)")
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--repeat", type=int, default=1)
    ap.add_argument("--leandvb")
    ap.add_argument("--ldpc-helper")
    ap.add_argument("--realtime", action="store_true")
    ap.add_argument("--linger", type=float, default=15)
    ap.add_argument("--extra", help="more leandvb options")
    args = ap.parse_args()

    rng = np.random.default_rng(args.seed)
    x = np.fromfile(args.ref, np.complex64)
    x = x - x.mean()
    if args.esn0 is not None:
        x = add_noise(x, args.fs_in, args.sr, args.esn0, rng)
    x = resample(x, args.fs_in, args.fs_out)
    if args.bits < 16:
        x, _ = quantize(x, args.bits, args.scale, args.block, args.target)
    fs = args.fs_out
    if args.sps:
        x, fs = decimate(x, fs, args.sr, args.sps, args.taps)
    good, bad, locks, ts = decode(x, fs, args)
    print(f"fs {fs / 1e3:6.1f}k ({fs / args.sr:.2f} sps) bits {args.bits} Es/N0 {args.esn0} seed {args.seed}: "
          f"frames ok {good:3d} bad {bad:3d} locks {locks} TS {ts:5d}", flush=True)


if __name__ == "__main__":
    main()
