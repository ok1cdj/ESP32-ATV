"""The leakage search against the vectors (python3 -m pytest rx/dccal); the app's DcCalibratorTest runs the same files."""
import glob
import json
import math
import os
import sys

import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
from dccal.core import Calibrator, LeakModel, bowl_db, bowl_start, fit_bowl, run  # noqa: E402

VECTORS = sorted(glob.glob(os.path.join(HERE, "vectors", "*.json")))


@pytest.mark.parametrize("path", VECTORS, ids=[os.path.basename(p)[:-5] for p in VECTORS])
def test_vector(path):
    v = json.load(open(path))
    m = v["model"]
    r = run(Calibrator(start=v["start"], step=v["step"]), LeakModel(m["l0"], m["gi"], m["gq"], m["floor"], v["noise"]).measure)
    e = v["expect"]
    assert math.hypot(r["i"] - e["optimum"][0], r["q"] - e["optimum"][1]) <= e["tol"]
    assert r["i"] == pytest.approx(e["core"]["i"], abs=1e-6)
    assert r["q"] == pytest.approx(e["core"]["q"], abs=1e-6)
    assert r["measurements"] == e["core"]["measurements"]
    assert r["converged"] == e["core"]["converged"]


def test_vectors_exist():
    assert len(VECTORS) >= 10


def test_fit_exact():
    # points of a known bowl, no noise: the fit finds it again from a rough guess
    x = [1.5, -2.0, 3.0, 0.4, 2.5, math.log(0.01)]
    pts = [(i, q, bowl_db(x, i, q)[0]) for i in (-1, 0.5, 2, 4) for q in (-4, -2.5, -1, 0.5)]
    got, c = fit_bowl(pts, bowl_start(pts))
    assert c < 1e-12
    assert got[0] == pytest.approx(1.5, abs=1e-6) and got[1] == pytest.approx(-2.0, abs=1e-6)


def test_gradient():
    x = [0.3, -1.1, 1.7, -0.2, 0.9, math.log(0.5)]
    m, g = bowl_db(x, 1.2, 0.4)
    for k in range(6):
        xp = list(x)
        xp[k] += 1e-6
        assert (bowl_db(xp, 1.2, 0.4)[0] - m) / 1e-6 == pytest.approx(g[k], rel=1e-4, abs=1e-6)


def test_first_round_goes_to_the_lowest():
    # 7 points cannot test 6 parameters: round 1 moves to the lowest point measured, not to a vertex fitted through noise
    cal = Calibrator(step=4.0)
    for i, q in cal.next_points():
        cal.add(i, q, 5.0 if (i, q) == (0.0, -4.0) else 27.0 + 0.1 * i + 0.2 * q)
    assert cal.round == 1 and cal.centre == pytest.approx((0.0, -4.0))


def test_converges():
    m = LeakModel((3.0, -2.0), (1, 0), (0, 1), 1e-3)
    r = run(Calibrator(), m.measure)
    assert r["converged"] and r["i"] == pytest.approx(-3.0, abs=1e-3) and r["q"] == pytest.approx(2.0, abs=1e-3)
