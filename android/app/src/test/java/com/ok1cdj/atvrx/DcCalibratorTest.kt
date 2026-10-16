package com.ok1cdj.atvrx

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot
import kotlin.math.log10

/** DcCalibrator against rx/dccal/vectors/ (the same files as rx/dccal/test_core.py). */
class DcCalibratorTest {
    companion object { const val CORE_TOL = 1e-3 }

    /** The synthetic transmitter of core.py's LeakModel: |L0 + gi i + gq q|^2 + floor in dB, with the noise in turn. */
    private class LeakModel(v: JSONObject) {
        private val m = v.getJSONObject("model")
        private fun c(k: String) = m.getJSONArray(k).let { it.getDouble(0) to it.getDouble(1) }
        private val l0 = c("l0"); private val gi = c("gi"); private val gq = c("gq")
        private val floor = m.getDouble("floor")
        private val noise: JSONArray = v.getJSONArray("noise")
        private var k = 0

        private fun db(i: Double, q: Double, floorFactor: Double): Double {
            val re = l0.first + gi.first * i + gq.first * q
            val im = l0.second + gi.second * i + gq.second * q
            return 10 * log10(re * re + im * im + floor * floorFactor)
        }

        fun measure(i: Double, q: Double): Double {
            if (noise.length() == 0) { k++; return db(i, q, 1.0) }
            val n = noise.get(k % noise.length())
            k++
            return if (n is JSONArray) db(i, q, n.getDouble(0)) + n.getDouble(1) else db(i, q, 1.0) + (n as Number).toDouble()
        }
    }

    private fun vectors(): List<File> {
        val dir = File(System.getProperty("dccal.vectors") ?: "../../rx/dccal/vectors")
        return dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
    }

    @Test
    fun vectors_exist() = assertTrue(vectors().size >= 10)

    @Test
    fun all_vectors() {
        val bad = ArrayList<String>()
        for (f in vectors()) {
            val v = JSONObject(f.readText())
            val st = v.getJSONArray("start")
            val cal = DcCalibrator(st.getDouble(0) to st.getDouble(1), v.getDouble("step"))
            val model = LeakModel(v)
            while (!cal.done) for ((i, q) in cal.nextPoints()) cal.add(i, q, model.measure(i, q))
            val r = cal.result()
            val e = v.getJSONObject("expect")
            val opt = e.getJSONArray("optimum")
            val core = e.getJSONObject("core")
            val name = f.name
            val off = hypot(r.i - opt.getDouble(0), r.q - opt.getDouble(1))
            val vsCore = hypot(r.i - core.getDouble("i"), r.q - core.getDouble("q"))
            // an iterative fit: the last digits differ from Python's, CORE_TOL [codes] is far below anything that matters
            if (off > e.getDouble("tol") || vsCore > CORE_TOL || r.measurements != core.getInt("measurements") ||
                r.converged != core.getBoolean("converged"))
                bad += "$name: %.6f %.6f (core.py %.6f %.6f, %d measurements, core.py %d; off the optimum %.3f)".format(
                    r.i, r.q, core.getDouble("i"), core.getDouble("q"), r.measurements, core.getInt("measurements"), off)
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun first_round_goes_to_the_lowest() {
        val cal = DcCalibrator(step = 4.0)
        for ((i, q) in cal.nextPoints()) cal.add(i, q, if (i == 0.0 && q == -4.0) 5.0 else 27.0 + 0.1 * i + 0.2 * q)
        assertEquals(1, cal.round)
        assertEquals(0.0, cal.centre.first, 1e-12)
        assertEquals(-4.0, cal.centre.second, 1e-12)
    }
}
