"""Nulling the carrier leakage of the C3: the search, without I/O (plain Python, so that the app's DcCalibrator.kt is a line
for line port and both pass the same vectors in rx/dccal/vectors/).

The leakage is a tone of complex amplitude L0 + gi * i + gq * q for the DC correction (i, q) in DAC codes, so its power is a
quadratic form plus the floor of the measurement: P = a i^2 + b q^2 + c i q + d i + e q + f (linear power; an ellipse, not a
circle, when the I and Q paths differ). Per round the caller measures a pattern of 7 points around the centre (centre, +-h on I,
+-h on Q, two diagonals), in dB; then the quadratic is fitted to every point so far (weights 1 / P^2: the noise of a power
measurement is relative), its vertex is the new centre, at most 2 h from the old one; h halves, or doubles (up to max_step) when
that limit cut the move short. Without a bowl (no positive definite fit yet) the centre goes 2 h downhill on the
fit and h doubles (up to max_step). Done when the centre moves less than tol after round 2, or after max_rounds.

    cal = Calibrator(start=(0, 0), step=4)
    while not cal.done:
        for i, q in cal.next_points():
            cal.add(i, q, measure_db(i, q))
    cal.result()
"""
import math

ROUND = 1e-6          # points closer than this are the same point
SIGNIFICANT = 6.0     # a fit counts when the measurements span this many times its rms misfit


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


def fit_quadratic(points):
    """Weighted least squares of P = a i^2 + b q^2 + c i q + d i + e q + f to [(i, q, p_db)]; None if under-determined.
    The coordinates are taken relative to the mean point (conditioning)."""
    if len(points) < 6:
        return None
    i0 = sum(p[0] for p in points) / len(points)
    q0 = sum(p[1] for p in points) / len(points)
    m = [[0.0] * 6 for _ in range(6)]
    v = [0.0] * 6
    for i, q, db in points:
        p = 10 ** (db / 10)
        x, y = i - i0, q - q0
        row = (x * x, y * y, x * y, x, y, 1.0)
        w = 1.0 / (p * p)
        for r in range(6):
            v[r] += w * row[r] * p
            for c in range(6):
                m[r][c] += w * row[r] * row[c]
    k = _solve(m, v)
    if k is None:
        return None
    a, b, c, d, e, f = k
    # back to absolute coordinates: x = i - i0, y = q - q0
    return (a, b, c,
            d - 2 * a * i0 - c * q0,
            e - 2 * b * q0 - c * i0,
            f + a * i0 * i0 + b * q0 * q0 + c * i0 * q0 - d * i0 - e * q0)


def quad_value(k, i, q):
    a, b, c, d, e, f = k
    return a * i * i + b * q * q + c * i * q + d * i + e * q + f


def quad_vertex(k):
    """The minimum of the quadratic, None unless it is a bowl (positive definite)."""
    a, b, c, d, e, _ = k
    det = 4 * a * b - c * c
    if a <= 0 or b <= 0 or det <= 0:
        return None
    return ((-2 * b * d + c * e) / det, (-2 * a * e + c * d) / det)


class Calibrator:
    def __init__(self, start=(0.0, 0.0), step=4.0, tol=0.05, max_rounds=8, min_step=0.25, max_step=16.0):
        self.centre = (float(start[0]), float(start[1]))
        self.start = self.centre
        self.step = float(step)
        self.tol = tol
        self.max_rounds = max_rounds
        self.min_step = min_step
        self.max_step = max_step
        self.points = []              # (i, q, p_db) in the order measured
        self.round = 0                # rounds finished
        self.done = False
        self.fit = None
        self.moves = []               # how far the centre moved in each round
        self.converged = False        # done because the centre stood still on a significant bowl (not out of rounds)

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

    def _significant(self, k):
        """Is the bowl more than noise? The measurements have to span SIGNIFICANT times the rms misfit (dB) of the fit."""
        res = []
        for i, q, db in self.points:
            p = quad_value(k, i, q)
            res.append(db - 10 * math.log10(p) if p > 0 else 30.0)
        rms = math.sqrt(sum(r * r for r in res) / len(res))
        span = max(p[2] for p in self.points) - min(p[2] for p in self.points)
        return span >= SIGNIFICANT * rms

    def _finish_round(self):
        self.round += 1
        k = fit_quadratic(self.points)
        v = quad_vertex(k) if k and self._significant(k) else None
        ci, cq = self.centre
        if v is None:
            # no bowl (far from the optimum the curvature drowns in the noise): to the lowest point measured, with a
            # bigger step; zoom in only when the centre itself is the lowest
            if k:                     # downhill on the fit: 2 h against its gradient at the centre
                gi = 2 * k[0] * ci + k[2] * cq + k[3]
                gq = 2 * k[1] * cq + k[2] * ci + k[4]
                g = math.hypot(gi, gq)
                b = (ci - 2 * self.step * gi / g, cq - 2 * self.step * gq / g) if g > 0 else self._best()
            else:
                b = self._best()
            move = math.hypot(b[0] - ci, b[1] - cq)
            self.centre = (b[0], b[1])
            self.step = min(self.step * 2, self.max_step) if move > ROUND else max(self.step / 2, self.min_step)
            self.moves.append(move)
            if self.round >= self.max_rounds:
                self.done = True
            return
        self.fit = k
        lim = 2 * self.step
        dx = max(-lim, min(lim, v[0] - ci))
        dy = max(-lim, min(lim, v[1] - cq))
        self.centre = (ci + dx, cq + dy)
        move = math.hypot(dx, dy)
        self.moves.append(move)
        clipped = dx != v[0] - ci or dy != v[1] - cq
        # the vertex within reach: zoom in; else walk on with a bigger step
        self.step = min(self.step * 2, self.max_step) if clipped else max(self.step / 2, self.min_step)
        if self.round >= 2 and move < self.tol:
            self.done = self.converged = True
        elif self.round >= self.max_rounds:
            self.done = True

    def result(self):
        """The correction found and what the fit says about it (None where there is no fit)."""
        r = {"i": self.centre[0], "q": self.centre[1], "rounds": self.round, "measurements": len(self.points),
             "moves": list(self.moves), "done": self.done,
             "converged": self.converged}
        k = self.fit
        if k:
            p_min = quad_value(k, *self.centre)
            if p_min <= 0:            # the fit dips below zero at a deep null: the lowest measurement instead
                p_min = 10 ** (self._best()[2] / 10)
            p_start = quad_value(k, *self.start)
            a, b = k[0], k[1]
            r["floor_db"] = 10 * math.log10(p_min)
            r["suppression_db"] = 10 * math.log10(p_start / p_min) if p_start > 0 else None
            # the leakage at the start in DAC codes: sqrt((P(start) - P(vertex)) / mean curvature)
            r["leak_codes"] = math.sqrt(max(0.0, p_start - p_min) / ((a + b) / 2))
        return r


class LeakModel:
    """A synthetic transmitter: tone |L0 + gi i + gq q|^2 + floor, in dB, with a given noise sequence (dB, used in turn)."""

    def __init__(self, l0, gi, gq, floor, noise=()):
        self.l0, self.gi, self.gq, self.floor = complex(*l0), complex(*gi), complex(*gq), floor
        self.noise = list(noise)
        self.k = 0

    def true_db(self, i, q):
        return 10 * math.log10(abs(self.l0 + self.gi * i + self.gq * q) ** 2 + self.floor)

    def measure(self, i, q):
        n = self.noise[self.k % len(self.noise)] if self.noise else 0.0
        self.k += 1
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
