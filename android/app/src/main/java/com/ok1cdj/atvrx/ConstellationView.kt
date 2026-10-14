package com.ok1cdj.atvrx

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * Constellation of the received symbols (leandvb --fd-const: "CONST n x,y ..." ideal points once, then
 * "SYMBOLS n x,y ..." one symbol per slot). Shows the last [KEEP] symbols over the ideal points.
 */
class ConstellationView(context: Context) : View(context) {
    private val dot = Paint().apply { color = Color.rgb(0x5A, 0xC8, 0xFA); alpha = 140 }
    private val ideal = Paint().apply { color = Color.rgb(0xFF, 0x9F, 0x0A); strokeWidth = 3f; style = Paint.Style.STROKE }
    private val axis = Paint().apply { color = Color.DKGRAY; strokeWidth = 1f }
    private val pts = FloatArray(2 * KEEP)
    private var n = 0
    private var head = 0
    @Volatile private var idealPts = FloatArray(0)
    private var scale = 128f

    /** A line from the leandvb constellation FIFO (worker thread). */
    fun line(l: String) {
        val parts = l.trim().split(' ')
        if (parts.size < 2) return
        val xy = parts.drop(2).mapNotNull { p ->
            val c = p.indexOf(','); if (c < 0) null else (p.substring(0, c).toFloatOrNull() ?: return@mapNotNull null) to
                (p.substring(c + 1).toFloatOrNull() ?: return@mapNotNull null)
        }
        when (parts[0]) {
            "CONST" -> {
                idealPts = FloatArray(2 * xy.size).also { a -> xy.forEachIndexed { i, (x, y) -> a[2 * i] = x; a[2 * i + 1] = y } }
                scale = maxOf(1f, xy.maxOf { (x, y) -> maxOf(kotlin.math.abs(x), kotlin.math.abs(y)) }) * 1.6f
            }
            "SYMBOLS" -> synchronized(pts) {
                for ((x, y) in xy) {
                    pts[2 * head] = x; pts[2 * head + 1] = y
                    head = (head + 1) % KEEP
                    if (n < KEEP) n++
                }
            }
        }
        postInvalidateOnAnimation()
    }

    fun clear() = synchronized(pts) { n = 0; head = 0; idealPts = FloatArray(0); postInvalidate() }

    override fun onMeasure(w: Int, h: Int) {
        val s = MeasureSpec.getSize(w)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val s = width.toFloat()
        val m = s / 2
        c.drawColor(Color.rgb(0x12, 0x12, 0x14))
        c.drawLine(0f, m, s, m, axis); c.drawLine(m, 0f, m, s, axis)
        val k = m / scale
        synchronized(pts) {
            for (i in 0 until n) c.drawCircle(m + pts[2 * i] * k, m - pts[2 * i + 1] * k, 2.2f, dot)
        }
        val ip = idealPts
        for (i in 0 until ip.size / 2) c.drawCircle(m + ip[2 * i] * k, m - ip[2 * i + 1] * k, 7f, ideal)
    }

    companion object { const val KEEP = 1500 }
}
