package com.ok1cdj.atvrx

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Nulling the carrier leakage of the C3: the search of rx/dccal/core.py, line for line (both pass the vectors in
 * rx/dccal/vectors/, see DcCalibratorTest). No Android in here.
 *
 * The leakage tone's power over the DC correction d = (i, q) [DAC codes] is a bowl over a floor,
 * P = (d - d*)^T L L^T (d - d*) + exp(lf), fitted in dB (Levenberg-Marquardt) to every point measured; parameters
 * x = (ci, cq, l11, l21, l22, lf). Per round the caller measures [nextPoints] (centre, +-h on I and Q, two diagonals);
 * the fit's null is the next centre, at most 2 h away; h halves, or doubles (up to maxStep) when that limit cut the move
 * short; the centre stays within [reach] of the start. A fit counts when the measurements span SIGNIFICANT times its
 * rms misfit per degree of freedom; without one the centre goes to the lowest point. Done when the centre moves less
 * than [tol] after round 2 on a fit that counts ([converged]), or after [maxRounds].
 */
class DcCalibrator(
    start: Pair<Double, Double> = 0.0 to 0.0,
    step: Double = 4.0,
    private val tol: Double = 0.1,
    private val maxRounds: Int = 8,
    private val minStep: Double = 0.5,
    maxStep: Double? = null,
    private val reach: Double = 16.0,
) {
    class Point(val i: Double, val q: Double, val db: Double)

    class Result(
        val i: Double, val q: Double, val rounds: Int, val measurements: Int, val converged: Boolean,
        val floorDb: Double?, val suppressionDb: Double?, val leakCodes: Double?, val misfitDb: Double?,
    )

    val start = start
    var centre = start; private set
    var step = step; private set
    private val maxStep = maxStep ?: 2 * step
    val points = ArrayList<Point>()
    var round = 0; private set
    var done = false; private set
    var converged = false; private set
    var fit: DoubleArray? = null; private set
    var misfit: Double? = null; private set
    val moves = ArrayList<Double>()

    fun pattern(): List<Pair<Double, Double>> {
        val (ci, cq) = centre
        val h = step
        val r = h / sqrt(2.0)
        return listOf(ci to cq, ci - h to cq, ci + h to cq, ci to cq - h, ci to cq + h, ci + r to cq + r, ci - r to cq + r)
    }

    private fun have(i: Double, q: Double) = points.any { abs(it.i - i) < ROUND && abs(it.q - q) < ROUND }

    /** The points still to measure in this round (empty when done). */
    fun nextPoints(): List<Pair<Double, Double>> {
        if (done) return emptyList()
        val todo = pattern().filter { !have(it.first, it.second) }
        if (todo.isEmpty()) {
            finishRound()
            return nextPoints()
        }
        return todo
    }

    fun add(i: Double, q: Double, db: Double) {
        points.add(Point(i, q, db))
        if (!done && pattern().all { have(it.first, it.second) }) finishRound()
    }

    private fun best() = points.minBy { it.db }

    private fun finishRound() {
        round++
        var (x, c) = fitBowl(points, bowlStart(points))
        fit?.let { prev ->
            val (x2, c2) = fitBowl(points, prev)
            if (c2 < c) { x = x2; c = c2 }
        }
        val n = points.size
        val rms = if (n > 6) sqrt(c / (n - 6)) else Double.POSITIVE_INFINITY
        val span = points.maxOf { it.db } - points.minOf { it.db }
        val counts = span >= SIGNIFICANT * rms && abs(x[2] * x[4]) > 1e-9
        val v: Pair<Double, Double>
        if (counts) {
            fit = x; misfit = rms
            v = x[0] to x[1]
        } else {
            val b = best()
            v = b.i to b.q
        }
        val (ci, cq) = centre
        val lim = 2 * step
        var dx = max(-lim, min(lim, v.first - ci))
        var dy = max(-lim, min(lim, v.second - cq))
        val clipped = dx != v.first - ci || dy != v.second - cq
        var ni = ci + dx
        var nq = cq + dy
        val far = hypot(ni - start.first, nq - start.second)
        if (far > reach) {
            ni = start.first + (ni - start.first) * reach / far
            nq = start.second + (nq - start.second) * reach / far
            dx = ni - ci; dy = nq - cq
        }
        centre = ni to nq
        val move = hypot(dx, dy)
        moves.add(move)
        step = if (clipped) min(step * 2, maxStep) else max(step / 2, minStep)
        if (counts && round >= 2 && move < tol) {
            done = true; converged = true
        } else if (round >= maxRounds) {
            done = true
        }
    }

    fun result(): Result {
        val x = fit ?: return Result(centre.first, centre.second, round, points.size, converged, null, null, null, misfit)
        val at = bowlDb(x, centre.first, centre.second).first
        val st = bowlDb(x, start.first, start.second).first
        val trace = x[2] * x[2] + x[3] * x[3] + x[4] * x[4]
        val leak = sqrt(max(0.0, Math.pow(10.0, st / 10) - Math.pow(10.0, at / 10)) / (trace / 2))
        return Result(centre.first, centre.second, round, points.size, converged, at, st - at, leak, misfit)
    }

    companion object {
        const val ROUND = 1e-6
        const val SIGNIFICANT = 6.0
        val DB = 10 / ln(10.0)

        /** Gaussian elimination with partial pivoting; null if singular. */
        fun solve(m: Array<DoubleArray>, v: DoubleArray): DoubleArray? {
            val n = v.size
            val a = Array(n) { r -> DoubleArray(n + 1) { c -> if (c < n) m[r][c] else v[r] } }
            for (col in 0 until n) {
                var piv = col
                for (r in col until n) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
                if (abs(a[piv][col]) < 1e-300) return null
                val t = a[col]; a[col] = a[piv]; a[piv] = t
                for (r in col + 1 until n) {
                    val k = a[r][col] / a[col][col]
                    for (c in col..n) a[r][c] -= k * a[col][c]
                }
            }
            val x = DoubleArray(n)
            for (r in n - 1 downTo 0) {
                var s = a[r][n]
                for (c in r + 1 until n) s -= a[r][c] * x[c]
                x[r] = s / a[r][r]
            }
            return x
        }

        /** The model in dB at (i, q) and its gradient with respect to x = (ci, cq, l11, l21, l22, lf). */
        fun bowlDb(x: DoubleArray, i: Double, q: Double): Pair<Double, DoubleArray> {
            val di = i - x[0]
            val dq = q - x[1]
            val u = x[2] * di + x[3] * dq
            val v = x[4] * dq
            val f = exp(x[5])
            val p = u * u + v * v + f
            val k = DB / p
            return DB * ln(p) to doubleArrayOf(-2 * u * x[2] * k, (-2 * u * x[3] - 2 * v * x[4]) * k, 2 * u * di * k,
                2 * u * dq * k, 2 * v * dq * k, f * k)
        }

        /** Least squares in dB of the bowl to the points from x0 (Levenberg-Marquardt): (x, sum of squares [dB^2]). */
        fun fitBowl(points: List<Point>, x0: DoubleArray, iters: Int = 60): Pair<DoubleArray, Double> {
            val lfMin = (points.minOf { it.db } - 40) / DB
            val lfMax = (points.maxOf { it.db } + 10) / DB
            fun cost(x: DoubleArray): Double {
                var s = 0.0
                for (p in points) { val r = p.db - bowlDb(x, p.i, p.q).first; s += r * r }
                return s
            }
            var x = x0.copyOf()
            x[5] = min(max(x[5], lfMin), lfMax)
            var c = cost(x)
            var lam = 1e-3
            for (it in 0 until iters) {
                val jtj = Array(6) { DoubleArray(6) }
                val jtr = DoubleArray(6)
                for (p in points) {
                    val (m, g) = bowlDb(x, p.i, p.q)
                    val r = p.db - m
                    for (a in 0 until 6) {
                        jtr[a] += g[a] * r
                        for (b in 0 until 6) jtj[a][b] += g[a] * g[b]
                    }
                }
                var better = false
                var dx: DoubleArray? = null
                while (lam < 1e10) {
                    val m = Array(6) { a -> DoubleArray(6) { b -> jtj[a][b] + (if (a == b) lam * jtj[a][a] + 1e-12 else 0.0) } }
                    dx = solve(m, jtr)
                    if (dx != null) {
                        val xn = DoubleArray(6) { k -> x[k] + dx[k] }
                        xn[5] = min(max(xn[5], lfMin), lfMax)
                        val cn = cost(xn)
                        if (cn < c) {
                            x = xn; c = cn; lam /= 3; better = true
                            break
                        }
                    }
                    lam *= 4
                }
                if (!better || dx!!.maxOf { abs(it) } < 1e-7) break
            }
            return x to c
        }

        /** A first guess: the null at the lowest point, a round bowl through the highest one, the floor at the lowest. */
        fun bowlStart(points: List<Point>): DoubleArray {
            val lo = points.minBy { it.db }
            val hi = points.maxBy { it.db }
            val r2 = max((hi.i - lo.i) * (hi.i - lo.i) + (hi.q - lo.q) * (hi.q - lo.q), 1e-6)
            val a = sqrt(max(Math.pow(10.0, hi.db / 10) - Math.pow(10.0, lo.db / 10), 1e-6) / r2)
            return doubleArrayOf(lo.i, lo.q, a, 0.0, a, lo.db / DB)
        }
    }
}
