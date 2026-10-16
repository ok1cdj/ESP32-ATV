"""The leakage search against the vectors (python3 -m pytest rx/dccal); the app's DcCalibratorTest runs the same files."""
import glob
import json
import math
import os
import sys

import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))
from dccal.core import Calibrator, LeakModel, fit_quadratic, quad_vertex, run  # noqa: E402

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
    k = (2.0, 3.0, 0.5, -1.0, 2.0, 7.0)
    pts = [(i, q, 10 * math.log10(k[0] * i * i + k[1] * q * q + k[2] * i * q + k[3] * i + k[4] * q + k[5]))
           for i, q in [(0, 0), (1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (-1, 1), (2, -1)]]
    f = fit_quadratic(pts)
    assert f == pytest.approx(k, rel=1e-9)
    vi, vq = quad_vertex(f)
    assert 4 * vi + 0.5 * vq - 1 == pytest.approx(0, abs=1e-9)        # dP/di = 0
    assert 0.5 * vi + 6 * vq + 2 == pytest.approx(0, abs=1e-9)        # dP/dq = 0


def test_no_bowl_goes_downhill():
    # a plane: no vertex; the centre moves 2 steps against the gradient (1, 2), the step doubles, not converged
    cal = Calibrator(step=1.0, max_rounds=1)
    for i, q in cal.next_points():
        cal.add(i, q, 10 * math.log10(10 + i + 2 * q))
    g = math.hypot(1, 2)
    assert cal.done and not cal.converged
    assert cal.centre == pytest.approx((-2 / g, -4 / g))
    assert cal.step == 2.0


def test_converges():
    m = LeakModel((3.0, -2.0), (1, 0), (0, 1), 1e-3)
    r = run(Calibrator(), m.measure)
    assert r["converged"] and r["i"] == pytest.approx(-3.0, abs=1e-3) and r["q"] == pytest.approx(2.0, abs=1e-3)
