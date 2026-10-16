#!/usr/bin/env python3
"""Test vectors for the leakage search (core.py and the app's DcCalibrator.kt) -> rx/dccal/vectors/*.json.

    python3 rx/dccal/make_vectors.py                       # the synthetic set
    python3 rx/dccal/make_vectors.py --log run.json [--run K] NAME    # a run recorded by rx/cal_dc.py --log -> vectors/NAME.json

A vector is a transmitter model and a noise sequence, so that both languages measure the same numbers at whatever points they
ask for: model {l0, gi, gq: [re, im], floor}, noise [dB, used in turn], start, step, and the expected result: the optimum of
the model with a tolerance, and the result of core.py (the port has to agree with it closely).
A recorded run becomes a model by fitting core.py's bowl to its measurements; its residuals are the noise sequence.
"""
import argparse
import json
import math
import os
import random
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
from dccal.core import Calibrator, LeakModel, bowl_db, bowl_start, fit_bowl, run  # noqa: E402

OUT = os.path.join(HERE, "vectors")


def vector(name, l0, gi, gq, floor, noise, start=(0.0, 0.0), step=4.0, tol=0.1, note=""):
    m = LeakModel(l0, gi, gq, floor, noise)
    r = run(Calibrator(start=start, step=step), m.measure)
    o = m.optimum()
    err = math.hypot(r["i"] - o[0], r["q"] - o[1])
    if err > tol:
        sys.exit(f"{name}: core.py misses the optimum by {err:.3f} codes (tolerance {tol})")
    return {"name": name, "note": note, "model": {"l0": list(l0), "gi": list(gi), "gq": list(gq), "floor": floor},
            "noise": list(noise), "start": list(start), "step": step,
            "expect": {"optimum": [round(o[0], 6), round(o[1], 6)], "tol": tol,
                       "core": {"i": r["i"], "q": r["q"], "measurements": r["measurements"], "rounds": r["rounds"],
                                "converged": r["converged"]}}}


def synthetic():
    rnd = random.Random(20261016)
    vs = []
    # ours: about 4 codes of leakage (BDC 0.576 -3.829 at 2340 MHz), 40 dB above the floor, 0.3 dB of noise
    vs.append(vector("ours-2340", (-0.576, 3.829), (1, 0), (0, 1), 15.0 * 1e-4, [rnd.gauss(0, 0.3) for _ in range(64)],
                     note="like our C3 at 2340 MHz, ideal I/Q"))
    vs.append(vector("noiseless", (3.0, -2.0), (1, 0), (0, 1), 1e-3, [], note="no noise: the exact optimum"))
    for k in range(10):
        l0 = (rnd.uniform(-8, 8), rnd.uniform(-8, 8))
        g, ph = rnd.uniform(0.8, 1.25), math.radians(rnd.uniform(-15, 15))
        gq = (g * math.sin(ph), g * math.cos(ph))                    # the Q path off in gain and phase: an ellipse
        floor = 10 ** (-rnd.uniform(20, 45) / 10) * (l0[0] ** 2 + l0[1] ** 2)
        sigma = rnd.choice((0.1, 0.3, 0.5, 1.0))
        start = (rnd.uniform(-3, 3), rnd.uniform(-3, 3)) if k % 2 else (0.0, 0.0)
        step = rnd.choice((2.0, 4.0, 8.0))
        vs.append(vector(f"random-{k}", l0, (1, 0), gq, floor, [rnd.gauss(0, sigma) for _ in range(128)], start, step,
                         tol=0.1 if sigma <= 0.5 else 0.2, note=f"noise {sigma} dB, floor {10 * math.log10(floor / (l0[0] ** 2 + l0[1] ** 2)):.0f} dB, "
                              f"I/Q gain {g:.2f} phase {math.degrees(ph):.0f} deg"))
    # like the board (2026-10-16): |L0| 2..8 codes, the tone 25..35 dB above the noise at BDC 0 0, the noise power of a
    # measurement fluctuating (4 windows x 3 bins: gamma, 12 degrees of freedom), 0.7 dB of jitter
    for k in range(8):
        l0 = complex(rnd.uniform(-1, 1), rnd.uniform(-1, 1))
        l0 *= rnd.uniform(2, 8) / abs(l0)
        snr = rnd.uniform(25, 35)
        g, ph = rnd.uniform(0.85, 1.15), math.radians(rnd.uniform(-10, 10))
        floor = abs(l0) ** 2 / 10 ** (snr / 10)
        noise = [[rnd.gammavariate(12, 1 / 12), rnd.gauss(0, 0.7)] for _ in range(128)]
        vs.append(vector(f"board-{k}", (l0.real, l0.imag), (1, 0), (g * math.sin(ph), g * math.cos(ph)), floor, noise,
                         tol=0.3, note=f"board-like: |L0| {abs(l0):.1f} codes, tone {snr:.0f} dB above the noise"))
    # far away: the optimum 15 codes off, step 2 (the trust region has to walk there)
    vs.append(vector("far", (12.0, -9.0), (1, 0), (0, 1), 0.02, [rnd.gauss(0, 0.3) for _ in range(64)], step=2.0,
                     note="15 codes away with a small first step"))
    return vs


def from_log(path, name, run_index=0, tol=0.3):
    """A recorded run: the bowl of core.py fitted to its measurements, as a transmitter model (u + j v with u = l11 di + l21 dq,
    v = l22 dq: gi = l11, gq = l21 + j l22, L0 = -(gi ci + gq cq)); its residuals are the noise sequence."""
    log = json.load(open(path))
    note = log.get("note", path)
    log = log["runs"][run_index]
    pts = [(m["i"], m["q"], m["db"]) for m in log["measurements"]]
    x, c = fit_bowl(pts, bowl_start(pts))
    ci, cq, l11, l21, l22, lf = x
    gi, gq = complex(l11, 0), complex(l21, l22)
    l0 = -(gi * ci + gq * cq)
    res = [db - bowl_db(x, i, q)[0] for i, q, db in pts]
    print(f"{name}: {len(pts)} measurements, null {ci:+.3f} {cq:+.3f}, fit rms {math.sqrt(c / len(pts)):.1f} dB")
    return vector(name, (l0.real, l0.imag), (gi.real, gi.imag), (gq.real, gq.imag), math.exp(lf), res,
                  tuple(log.get("start", (0.0, 0.0))), float(log.get("step", 4.0)), tol=tol,
                  note=f"recorded: {note}, {log.get('freq_mhz', 0):.3f} MHz, fit rms {math.sqrt(c / len(pts)):.1f} dB")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--log", help="a run recorded by rx/cal_dc.py --log")
    ap.add_argument("--run", type=int, default=0, help="which run of the log (a sweep has one per frequency)")
    ap.add_argument("name", nargs="?")
    a = ap.parse_args()
    os.makedirs(OUT, exist_ok=True)
    vs = [from_log(a.log, a.name or "recorded", a.run)] if a.log else synthetic()
    for v in vs:
        p = os.path.join(OUT, v["name"] + ".json")
        json.dump(v, open(p, "w"), indent=1)
        e = v["expect"]
        print(f"{p}: optimum {e['optimum'][0]:+.3f} {e['optimum'][1]:+.3f}, core {e['core']['i']:+.3f} {e['core']['q']:+.3f} "
              f"({e['core']['measurements']} measurements), tol {e['tol']}")


if __name__ == "__main__":
    main()
