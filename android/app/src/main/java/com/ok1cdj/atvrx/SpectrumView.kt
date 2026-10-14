package com.ok1cdj.atvrx

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * Spectrum of the samples from the ESP32-S3 (before the NCO): -fs/2 .. +fs/2 around the tuned frequency, 10 dB grid,
 * the NCO (where the receiver put the signal centre) as a line and the occupied band (SR × 1.35) around it.
 */
class SpectrumView(context: Context) : View(context) {
    private val trace = Paint().apply { color = Color.rgb(0x5A, 0xC8, 0xFA); strokeWidth = 2.5f; style = Paint.Style.STROKE; isAntiAlias = true }
    private val grid = Paint().apply { color = Color.rgb(0x3C, 0x40, 0x43); strokeWidth = 1f }
    private val nco = Paint().apply { color = Color.rgb(0xFF, 0x9F, 0x0A); strokeWidth = 2f }
    private val band = Paint().apply { color = Color.argb(0x30, 0xFF, 0x9F, 0x0A) }
    private val label = Paint().apply { color = Color.rgb(0x9A, 0xA0, 0xA6); textSize = 24f; isAntiAlias = true }
    private val path = Path()

    @Volatile private var db = FloatArray(0)
    @Volatile private var fs = 0.0
    @Volatile private var ncoHz = Double.NaN
    @Volatile private var sr = 0

    fun update(spectrum: FloatArray, fs: Double, ncoHz: Double, sr: Int) {
        db = spectrum; this.fs = fs; this.ncoHz = ncoHz; this.sr = sr
        invalidate()
    }

    fun clear() { db = FloatArray(0); ncoHz = Double.NaN; invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(Color.rgb(0x12, 0x12, 0x14))
        val d = db
        if (d.isEmpty() || fs <= 0) { c.drawText("spectrum", 8f, h / 2, label); return }
        // vertical scale: from just under the noise floor, 10 dB grid lines
        val sorted = d.sorted()
        val floor = sorted[d.size / 10]
        val top = maxOf(sorted.last(), floor + 30f) + 3f
        val bottom = floor - 6f
        fun y(v: Float) = h - (v - bottom) / (top - bottom) * h
        var g = (Math.ceil(bottom / 10.0) * 10).toFloat()
        while (g < top) { c.drawLine(0f, y(g), w, y(g), grid); g += 10f }
        fun x(hz: Double) = ((hz / fs + 0.5) * w).toFloat()
        // frequency grid every 50 / 100 kHz
        val step = if (fs > 400e3) 100e3 else 50e3
        var f = -Math.floor(fs / 2 / step) * step
        while (f <= fs / 2) {
            c.drawLine(x(f), 0f, x(f), h, grid)
            c.drawText("%+.0f".format(f / 1e3), x(f) + 4f, h - 6f, label)
            f += step
        }
        if (!ncoHz.isNaN()) {
            val half = sr * 1.35 / 2
            c.drawRect(x(ncoHz - half), 0f, x(ncoHz + half), h, band)
            c.drawLine(x(ncoHz), 0f, x(ncoHz), h, nco)
        }
        path.reset()
        for (i in d.indices) {
            val px = i * w / (d.size - 1); val py = y(d[i])
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        c.drawPath(path, trace)
        c.drawText("kHz", w - 52f, 24f, label)
    }
}
