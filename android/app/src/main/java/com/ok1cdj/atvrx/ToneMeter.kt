package com.ok1cdj.atvrx

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.io.IOException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/**
 * The leakage tone of the C3 in a block of [N] samples from the S3, as rx/cal_dc.py's ToneMeter: the power spectrum
 * averaged over [SUB] windows (20 Hz bins at 333 ksps), the tone's power the sum of the 3 bins around its frequency,
 * against the mean noise per bin around it [dB; the noise alone gives about 0]. Its frequency: first the strongest line
 * anywhere but the receiver's own DC; then, from a line at least [STRONG] dB above the noise, the strongest bin within
 * [TRACK] Hz of the prediction. Weaker lines do not move it (near the null the strongest bin is noise): the tone is
 * taken where it should be by now, with the drift rate of the strong lines of the last 5 s (about -100 Hz/s while the
 * C3 warms up). Samples: interleaved I, Q, conjugated (RF up = frequency up), full scale 1.
 */
class ToneMeter(private val fs: Double) {
    class Reading(val db: Double, val hz: Double, val clip: Double)

    var f: Double? = null; private set          // the last strong line [Hz] and when [s]
    private var t = 0.0
    var rate = 0.0; private set                  // drift [Hz/s]
    private val hist = ArrayList<Pair<Double, Double>>()
    private val m = N / SUB
    private val w = FloatArray(m) { (0.5 - 0.5 * cos(2 * PI * it / (m - 1))).toFloat() }
    private val freqs = DoubleArray(m) { (it - m / 2) * fs / m }

    fun reset() { f = null; rate = 0.0; hist.clear() }

    private fun now() = System.nanoTime() / 1e9

    fun predict(at: Double = now()): Double? = f?.let { it + rate * (at - t) }

    /** Power spectrum of one window, fft-shifted (bin k = freqs[k]), DC of the window removed. */
    private fun window(iq: FloatArray, from: Int): DoubleArray {
        var mi = 0.0; var mq = 0.0
        for (k in 0 until m) { mi += iq[2 * (from + k)]; mq += iq[2 * (from + k) + 1] }
        mi /= m; mq /= m
        val re = FloatArray(m) { k -> ((iq[2 * (from + k)] - mi) * w[k]).toFloat() }
        val im = FloatArray(m) { k -> ((iq[2 * (from + k) + 1] - mq) * w[k]).toFloat() }
        Receiver.fft(re, im)
        return DoubleArray(m) { k -> val j = (k + m / 2) % m; re[j].toDouble() * re[j] + im[j].toDouble() * im[j] }
    }

    /**
     * The leakage tone among other lines (the S3 has one of its own at exactly the nominal frequency): spectra at
     * different corrections, the line whose level changes most between them (at least [LOCATE] dB) and is strong in one;
     * its frequency is then the tracked one (the drift learned afresh). False if there is none.
     */
    fun locate(blocks: List<FloatArray>): Boolean {
        val ps = blocks.map { x -> DoubleArray(m).also { p -> for (s in 0 until SUB) { val w = window(x, s * m); for (k in 0 until m) p[k] += w[k] / SUB } } }
        val hi = DoubleArray(m) { k -> ps.maxOf { it[k] } }
        val lo = DoubleArray(m) { k -> ps.minOf { it[k] } }
        val ring = (0 until m).filter { abs(freqs[it]) > 3e3 }
        val noise = ring.map { hi[it] }.sorted()[ring.size / 2] / 0.84
        var best = -1; var score = 0.0
        for (k in ring) {
            if (k < 1 || k > m - 2 || hi[k] <= noise * Math.pow(10.0, STRONG / 10)) continue
            val r = hi[k] / maxOf(lo[k], 1e-30)
            if (r > score) { score = r; best = k }
        }
        if (best < 0 || score < Math.pow(10.0, LOCATE / 10)) return false
        f = freqs[best]; t = now(); rate = 0.0; hist.clear()
        return true
    }

    /** Did the transmitter stop within the block? The tone's level in the windows differs by more than 10 dB (strong tone only). */
    fun gap(iq: FloatArray): Boolean {
        val fr = predict() ?: return false
        val k = nearest(fr)
        val lv = (0 until SUB).map { s ->
            val p = window(iq, s * m)
            val med = p.sorted()[m / 2]
            10 * log10((p[k - 1] + p[k] + p[k + 1]) / med + 1e-30)
        }
        return lv.max() - lv.min() > 10 && lv.max() > 25
    }

    private fun nearest(hz: Double) = ((hz / fs * m).let { Math.round(it).toInt() } + m / 2).coerceIn(1, m - 2)

    fun measure(iq: FloatArray): Reading {
        var clip = 0
        for (k in 0 until N) if (abs(iq[2 * k]) >= 0.99f || abs(iq[2 * k + 1]) >= 0.99f) clip++
        val tn = now()
        val p = DoubleArray(m)
        for (s in 0 until SUB) { val ps = window(iq, s * m); for (k in 0 until m) p[k] += ps[k] / SUB }
        val fr = predict(tn)
        fun usable(k: Int) = k in 1 until m - 1 && abs(freqs[k]) > 3e3
        var k = -1
        for (j in 0 until m) {
            if (!usable(j)) continue
            if (fr != null && abs(freqs[j] - fr) >= TRACK) continue
            if (k < 0 || p[j] > p[k]) k = j
        }
        if (k < 0) for (j in 0 until m) if (usable(j) && (k < 0 || p[j] > p[k])) k = j
        fun level(k: Int): Double {
            val ring = (0 until m).filter { j -> abs(freqs[j] - freqs[k]) > 3e3 && abs(freqs[j] - freqs[k]) < 20e3 && abs(freqs[j]) > 3e3 }
                .map { p[it] }.sorted()
            val low = ring.subList(0, (0.9 * ring.size).toInt())
            val noise = low.average() / 0.84                       // the lowest 90 %: no stray lines
            return 10 * log10((p[k - 1] + p[k] + p[k + 1]) / (3 * noise))
        }
        var db = level(k)
        var fk: Double
        if (db >= STRONG) {
            val a = kotlin.math.ln(p[k - 1]); val b = kotlin.math.ln(p[k]); val c = kotlin.math.ln(p[k + 1])
            fk = freqs[k] + (if (a - 2 * b + c < 0) 0.5 * (a - c) / (a - 2 * b + c) else 0.0) * fs / m
            hist.removeAll { tn - it.first >= 5 }
            hist.add(tn to fk)
            if (hist.size >= 2 && hist.last().first - hist.first().first > 1.0) rate = slope(hist).coerceIn(-300.0, 300.0)
            f = fk; t = tn
        } else if (fr != null) {
            fk = fr
            db = level(nearest(fr))
        } else {
            fk = freqs[k]
        }
        return Reading(db, fk, clip.toDouble() / N)
    }

    private fun slope(h: List<Pair<Double, Double>>): Double {
        val t0 = h.last().first
        val mt = h.sumOf { it.first - t0 } / h.size
        val mf = h.sumOf { it.second } / h.size
        var sxy = 0.0; var sxx = 0.0
        for ((tt, ff) in h) { sxy += (tt - t0 - mt) * (ff - mf); sxx += (tt - t0 - mt) * (tt - t0 - mt) }
        return if (sxx > 0) sxy / sxx else 0.0
    }

    companion object {
        const val N = 1 shl 16               // 0.2 s at 333 ksps
        const val SUB = 4
        const val TRACK = 400.0
        const val STRONG = 12.0
        const val LOCATE = 3.0
    }
}

/** The S3 streaming cs8 at 333 ksps for the calibration: the newest samples, interleaved I, Q, conjugated. */
class ToneStream(usbManager: UsbManager, device: UsbDevice, loHz: Long, gainSelector: Int) : AutoCloseable {
    val fs = 1e6 / 3
    private val link: EspLink
    private val lock = Object()
    private var buf = FloatArray(2 * ToneMeter.N * 2)
    private var n = 0                         // samples in buf
    @Volatile private var collecting = false

    init {
        val conn = usbManager.openDevice(device) ?: throw IOException("openDevice failed (S3)")
        link = EspLink(UsbCdc(conn, device))
        link.send(EspLink.ESP_STOP)
        try { link.await(EspLink.ESP_RUN, 1500) } catch (_: IOException) {}
        link.checkFirmware()
        link.configure(loHz, 3, EspLink.FMT_CS8, gainSelector)
        link.onPacket = { p -> feed(p) }
        link.resetStats()
        link.send(EspLink.ESP_RUN, 0)
    }

    private fun feed(p: EspLink.Packet) {
        if (!collecting || p.format != EspLink.FMT_CS8) return
        val o = p.off + EspLink.NB_HEADER
        synchronized(lock) {
            if (2 * (n + p.count) > buf.size) buf = buf.copyOf(2 * (n + p.count) * 2)
            for (j in 0 until p.count) {
                buf[2 * (n + j)] = p.buf[o + 2 * j] / 128f
                buf[2 * (n + j) + 1] = -p.buf[o + 2 * j + 1] / 128f
            }
            n += p.count
            (lock as Object).notifyAll()
        }
    }

    /** [count] samples received after now + [settleMs]. */
    fun take(count: Int, settleMs: Long): FloatArray {
        if (settleMs > 0) Thread.sleep(settleMs)
        synchronized(lock) { n = 0; collecting = true }
        val end = System.currentTimeMillis() + 10_000
        synchronized(lock) {
            while (n < count) {
                link.error?.let { throw IOException("S3: ${it.message}", it) }
                val left = end - System.currentTimeMillis()
                if (left <= 0) throw IOException("no samples from the S3")
                (lock as Object).wait(minOf(left, 100))
            }
            collecting = false
            return buf.copyOf(2 * count)
        }
    }

    override fun close() {
        try { link.send(EspLink.ESP_STOP) } catch (_: IOException) {}
        link.close()
    }
}
