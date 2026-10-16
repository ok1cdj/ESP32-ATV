#!/usr/bin/env python3
"""Test vectors for the leakage search (core.py and the app's DcCalibrator.kt) -> rx/dccal/vectors/*.json.

    python3 rx/dccal/make_vectors.py                       # the synthetic set
    python3 rx/dccal/make_vectors.py --log run.json [--run K] NAME    # a run recorded by rx/cal_dc.py --log -> vectors/NAME.json

A vector is a transmitter model and a noise sequence, so that both languages measure the same numbers at whatever points they
ask for: model {l0, gi, gq: [re, im], floor}, noise [dB, used in turn], start, step, and the expected result: the optimum of
the model with a tolerance, and the result of core.py (the port has to agree with it closely).
A recorded run becomes a model by fitting |L0 + gi i + gq q|^2 + floor to its measurements (least squares in dB); its
residuals are the noise sequence.
"""
import argparse
import json
import math
import os
import random
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
from dccal.core import Calibrator, LeakModel, run  # noqa: E402

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
    # far away: the optimum 15 codes off, step 2 (the trust region has to walk there)
    vs.append(vector("far", (12.0, -9.0), (1, 0), (0, 1), 0.02, [rnd.gauss(0, 0.3) for _ in range(64)], step=2.0,
                     note="15 codes away with a small first step"))
    return vs


def from_log(path, name, run_index=0):
    """A recorded run: fit the physical model to the measurements by Gauss-Newton in dB."""
    import numpy as np
    log = json.load(open(path))
    note = log.get("note", path)
    log = log["runs"][run_index]
    pts = np.array([(m["i"], m["q"], m["db"]) for m in log["measurements"]], float)
    i, q, db = pts.T
    # start: the quadratic fit of core.py, then the model's parameters [l0r, l0i, gir, gii, gqr, gqi, log floor]
    x = np.array([0.0, 0.0, 1.0, 0.0, 0.0, 1.0, math.log(10 ** (db.min() / 10))])
    best = pts[np.argmin(db)]
    x[0], x[1] = -best[0], -best[1]
    scale = 10 ** ((db.max()) / 10) / max(1.0, float(np.max(best[0] ** 2 + best[1] ** 2)))
    x[2:6] *= math.sqrt(scale)
    x[0:2] *= math.sqrt(scale)

    def model(x):
        lr = x[0] + x[2] * i + x[4] * q
        li = x[1] + x[3] * i + x[5] * q
        return 10 * np.log10(lr * lr + li * li + np.exp(x[6]))

    for _ in range(200):
        r = db - model(x)
        jac = np.empty((len(db), 7))
        for k in range(7):
            dx = np.zeros(7)
            dx[k] = 1e-6 * max(1.0, abs(x[k]))
            jac[:, k] = (model(x + dx) - model(x)) / dx[k]
        step, *_ = np.linalg.lstsq(jac, r, rcond=None)
        x += step
        if np.max(np.abs(step)) < 1e-9:
            break
    res = db - model(x)
    print(f"{name}: {len(db)} measurements, fit rms {np.sqrt(np.mean(res ** 2)):.2f} dB")
    return vector(name, (x[0], x[1]), (x[2], x[3]), (x[4], x[5]), float(np.exp(x[6])), [float(v) for v in res],
                  tuple(log.get("start", (0.0, 0.0))), float(log.get("step", 4.0)),
                  note=f"recorded: {note}, {log.get('freq_mhz', 0):.3f} MHz")


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
