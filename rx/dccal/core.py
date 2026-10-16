"""Nulling the carrier leakage of the C3: the search, without I/O (plain Python, so that the app's DcCalibrator.kt is a line
for line port and both pass the same vectors in rx/dccal/vectors/).

The leakage is a tone of complex amplitude L0 + gi * i + gq * q for the DC correction (i, q) in DAC codes, so its power is a
quadratic bowl over a floor: P = (d - d*)^T M (d - d*) + F, M positive definite (an ellipse, not a circle, when the I and Q paths
differ), F the noise and what no correction removes. It is fitted in dB (Levenberg-Marquardt, every point weighted the same: the
noise of a power measurement is relative, and on the board it is several dB near the null, where a fit in linear power would
follow the lowest point alone), with M = L L^T, L lower triangular, and F = exp(lf): the parameters ci, cq, l11, l21, l22, lf.
Per round the caller measures a pattern of 7 points around the centre (centre, +-h on I, +-h on Q, two diagonals); the null of
the fit to every point so far is the new centre, at most 2 h from the old one; h halves, or doubles (up to max_step) when that
limit cut the move short; the centre stays within reach of the start. A fit counts when the measurements span SIGNIFICANT times its rms misfit (per degree of freedom); without one the centre goes
to the lowest point measured. Done when the centre moves less than tol after round 2 on a fit that counts (converged), or after
max_rounds.

    cal = Calibrator(start=(0, 0), step=4)
    while not cal.done:
        for i, q in cal.next_points():
            cal.add(i, q, measure_db(i, q))
    cal.result()
"""
import math

ROUND = 1e-6          # points closer than this are the same point
SIGNIFICANT = 6.0     # a fit counts when the measurements span this many times its rms misfit [dB]
DB = 10 / math.log(10)


def _solve(m, v):
    """Gaussian elimination with partial pivoting; None if singular."""
    n = len(v)
    a = [row[:] + [v[k]] for k, row in enumerate(m)]
    for col in range(n):
        piv = max(range(col, n), key=lambda r: abs(a[r][col]))
        if abs(a[piv][col]) < 1e-300:
            return None
        a[col], a[piv] = a[piv], a[col]
        for r in range(col + 1, n):
            k = a[r][col] / a[col][col]
            for c in range(col, n + 1):
                a[r][c] -= k * a[col][c]
    x = [0.0] * n
    for r in range(n - 1, -1, -1):
        s = a[r][n] - sum(a[r][c] * x[c] for c in range(r + 1, n))
        x[r] = s / a[r][r]
    return x


def bowl_db(x, i, q):
    """The model in dB at (i, q), and its gradient with respect to the parameters x = (ci, cq, l11, l21, l22, lf)."""
    ci, cq, l11, l21, l22, lf = x
    di, dq = i - ci, q - cq
    u = l11 * di + l21 * dq
    v = l22 * dq
    f = math.exp(lf)
    p = u * u + v * v + f
    k = DB / p
    return DB * math.log(p), (-2 * u * l11 * k, (-2 * u * l21 - 2 * v * l22) * k, 2 * u * di * k, 2 * u * dq * k,
                              2 * v * dq * k, f * k)


def fit_bowl(points, x0, iters=60):
    """Least squares in dB of the bowl to [(i, q, p_db)] from x0 (Levenberg-Marquardt); (x, sum of squares [dB^2])."""
    lf_min = (min(p[2] for p in points) - 40) / DB          # the floor from 40 dB under the lowest point
    lf_max = (max(p[2] for p in points) + 10) / DB          # to 10 dB over the highest

    def cost(x):
        return sum((db - bowl_db(x, i, q)[0]) ** 2 for i, q, db in points)

    x = list(x0)
    x[5] = min(max(x[5], lf_min), lf_max)
    c = cost(x)
    lam = 1e-3
    for _ in range(iters):
        jtj = [[0.0] * 6 for _ in range(6)]
        jtr = [0.0] * 6
        for i, q, db in points:
            m, g = bowl_db(x, i, q)
            r = db - m
            for a in range(6):
                jtr[a] += g[a] * r
                for b in range(6):
                    jtj[a][b] += g[a] * g[b]
        better = False
        while lam < 1e10:
            m = [[jtj[a][b] + (lam * jtj[a][a] + 1e-12 if a == b else 0.0) for b in range(6)] for a in range(6)]
            dx = _solve(m, jtr)
            if dx is not None:
                xn = [x[k] + dx[k] for k in range(6)]
                xn[5] = min(max(xn[5], lf_min), lf_max)
                cn = cost(xn)
                if cn < c:
                    x, c, lam, better = xn, cn, lam / 3, True
                    break
            lam *= 4
        if not better or max(abs(d) for d in dx) < 1e-7:
            break
    return x, c


def bowl_start(points):
    """A first guess: the null at the lowest point, a round bowl through the highest one, the floor at the lowest."""
    lo = min(points, key=lambda p: p[2])
    hi = max(points, key=lambda p: p[2])
    r2 = max((hi[0] - lo[0]) ** 2 + (hi[1] - lo[1]) ** 2, 1e-6)
    a = math.sqrt(max(10 ** (hi[2] / 10) - 10 ** (lo[2] / 10), 1e-6) / r2)
    return [lo[0], lo[1], a, 0.0, a, lo[2] / DB]


class Calibrator:
    def __init__(self, start=(0.0, 0.0), step=4.0, tol=0.1, max_rounds=8, min_step=0.5, max_step=None, reach=16.0):
        self.centre = (float(start[0]), float(start[1]))
        self.start = self.centre
        self.step = float(step)
        self.tol = tol
        self.max_rounds = max_rounds
        self.min_step = min_step
        self.max_step = 2 * self.step if max_step is None else max_step
        self.reach = reach            # the centre stays within this distance of the start [codes]
        self.points = []              # (i, q, p_db) in the order measured
        self.round = 0                # rounds finished
        self.done = False
        self.fit = None               # the last fit that counted (parameters as bowl_db)
        self.misfit = None            # its rms misfit [dB]
        self.moves = []               # how far the centre moved in each round
        self.converged = False        # done because the centre stood still on a fit that counts (not out of rounds)

    def pattern(self):
        ci, cq = self.centre
        h = self.step
        r = h / math.sqrt(2)
        return [(ci, cq), (ci - h, cq), (ci + h, cq), (ci, cq - h), (ci, cq + h), (ci + r, cq + r), (ci - r, cq + r)]

    def _have(self, i, q):
        return any(abs(p[0] - i) < ROUND and abs(p[1] - q) < ROUND for p in self.points)

    def next_points(self):
        """The points still to measure in this round ([] when done)."""
        if self.done:
            return []
        todo = [pt for pt in self.pattern() if not self._have(*pt)]
        if not todo:                  # all there already (a repeated centre): the round is complete
            self._finish_round()
            return self.next_points()
        return todo

    def add(self, i, q, p_db):
        self.points.append((float(i), float(q), float(p_db)))
        if not self.done and all(self._have(*pt) for pt in self.pattern()):
            self._finish_round()

    def _best(self):
        return min(self.points, key=lambda p: p[2])

    def _finish_round(self):
        self.round += 1
        # from the last fit and from a fresh guess (a wrong early fit must not hold the search): the better one
        x, c = fit_bowl(self.points, bowl_start(self.points))
        if self.fit:
            x2, c2 = fit_bowl(self.points, self.fit)
            if c2 < c:
                x, c = x2, c2
        n = len(self.points)
        rms = math.sqrt(c / (n - 6)) if n > 6 else float("inf")      # per degree of freedom: 7 points hardly test 6 parameters
        span = max(p[2] for p in self.points) - min(p[2] for p in self.points)
        counts = span >= SIGNIFICANT * rms and abs(x[2] * x[4]) > 1e-9
        if counts:
            self.fit, self.misfit = x, rms
            v = (x[0], x[1])
        else:
            b = self._best()
            v = (b[0], b[1])
        ci, cq = self.centre
        lim = 2 * self.step
        dx = max(-lim, min(lim, v[0] - ci))
        dy = max(-lim, min(lim, v[1] - cq))
        clipped = dx != v[0] - ci or dy != v[1] - cq
        ni, nq = ci + dx, cq + dy
        far = math.hypot(ni - self.start[0], nq - self.start[1])
        if far > self.reach:          # out of reach: back onto its edge
            ni = self.start[0] + (ni - self.start[0]) * self.reach / far
            nq = self.start[1] + (nq - self.start[1]) * self.reach / far
            dx, dy = ni - ci, nq - cq
        self.centre = (ni, nq)
        move = math.hypot(dx, dy)
        self.moves.append(move)
        self.step = min(self.step * 2, self.max_step) if clipped else max(self.step / 2, self.min_step)
        if counts and self.round >= 2 and move < self.tol:
            self.done = self.converged = True
        elif self.round >= self.max_rounds:
            self.done = True

    def result(self):
        """The correction found and what the fit says about it (None where there is no fit)."""
        r = {"i": self.centre[0], "q": self.centre[1], "rounds": self.round, "measurements": len(self.points),
             "moves": list(self.moves), "done": self.done, "converged": self.converged,
             "floor_db": None, "suppression_db": None, "leak_codes": None, "misfit_db": self.misfit}
        x = self.fit
        if x:
            at, _ = bowl_db(x, *self.centre)
            st, _ = bowl_db(x, *self.start)
            r["floor_db"] = at
            r["suppression_db"] = st - at
            # the leakage at the start in DAC codes: sqrt((P(start) - P(null)) / mean curvature); M = L L^T
            trace = x[2] ** 2 + x[3] ** 2 + x[4] ** 2
            r["leak_codes"] = math.sqrt(max(0.0, 10 ** (st / 10) - 10 ** (at / 10)) / (trace / 2))
        return r


class LeakModel:
    """A synthetic transmitter: tone |L0 + gi i + gq q|^2 + floor, in dB, with a given noise sequence, used in turn. An entry is
    a number (dB added to the measurement) or a pair [n, j]: the floor times n (the noise power fluctuates) and j dB added."""

    def __init__(self, l0, gi, gq, floor, noise=()):
        self.l0, self.gi, self.gq, self.floor = complex(*l0), complex(*gi), complex(*gq), floor
        self.noise = list(noise)
        self.k = 0

    def true_db(self, i, q, floor_factor=1.0):
        return 10 * math.log10(abs(self.l0 + self.gi * i + self.gq * q) ** 2 + self.floor * floor_factor)

    def measure(self, i, q):
        n = self.noise[self.k % len(self.noise)] if self.noise else 0.0
        self.k += 1
        if isinstance(n, (list, tuple)):
            return self.true_db(i, q, n[0]) + n[1]
        return self.true_db(i, q) + n

    def optimum(self):
        """The (i, q) that nulls the tone: L0 + gi i + gq q = 0 for real i, q."""
        # [Re gi Re gq; Im gi Im gq] [i; q] = -[Re L0; Im L0]
        a, b, c, d = self.gi.real, self.gq.real, self.gi.imag, self.gq.imag
        det = a * d - b * c
        return ((-self.l0.real * d + self.l0.imag * b) / det, (-a * self.l0.imag + c * self.l0.real) / det)


def run(cal, measure):
    """Drives cal with measure(i, q) -> dB until done; returns cal.result()."""
    while not cal.done:
        for i, q in cal.next_points():
            cal.add(i, q, measure(i, q))
    return cal.result()
