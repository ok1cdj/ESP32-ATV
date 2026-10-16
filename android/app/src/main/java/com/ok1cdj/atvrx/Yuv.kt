package com.ok1cdj.atvrx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.Image

/**
 * YUV_420_888 frames (camera, video decoder) -> ARGB bitmaps (BT.601, limited range), taking every [step]-th pixel of
 * the crop rectangle in both directions: a 1080p video at step 3 costs what a 640x360 one does. The newest frame is
 * swapped in whole and never written while [draw] shows it.
 */
class YuvToBitmap(private val step: Int = 1) {
    private var bitmap: Bitmap? = null
    private var spare: Bitmap? = null
    private var argb = IntArray(0)

    fun convert(img: Image) {
        val crop = img.cropRect
        val w = crop.width() / step; val h = crop.height() / step
        if (argb.size != w * h) argb = IntArray(w * h)
        val yp = img.planes[0]; val up = img.planes[1]; val vp = img.planes[2]
        val yb = yp.buffer; val ub = up.buffer; val vb = vp.buffer
        val yrs = yp.rowStride; val yps = yp.pixelStride
        val urs = up.rowStride; val ups = up.pixelStride; val vrs = vp.rowStride; val vps = vp.pixelStride
        var o = 0
        for (j in 0 until h) {
            val y = crop.top + j * step
            val yo = y * yrs; val uo = (y shr 1) * urs; val vo = (y shr 1) * vrs
            for (i in 0 until w) {
                val x = crop.left + i * step
                val Y = ((yb.get(yo + x * yps).toInt() and 0xFF) - 16).coerceAtLeast(0) * 1192
                val U = (ub.get(uo + (x shr 1) * ups).toInt() and 0xFF) - 128
                val V = (vb.get(vo + (x shr 1) * vps).toInt() and 0xFF) - 128
                val r = (Y + 1634 * V) shr 10
                val g = (Y - 833 * V - 400 * U) shr 10
                val b = (Y + 2066 * U) shr 10
                argb[o++] = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
            }
        }
        val bm = spare?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bm.setPixels(argb, 0, w, 0, 0, w, h)
        synchronized(this) { spare = bitmap; bitmap = bm }
    }

    /** [f] with the newest frame (null before the first), which stays untouched until [f] returns */
    fun <T> draw(f: (Bitmap?) -> T): T = synchronized(this) { f(bitmap) }
}

/** The station's identification over a picture (camera, file): a dark strip at the bottom, callsign left, [line] right. */
object IdStrip {
    fun draw(cv: Canvas, w: Int, h: Int, call: String, line: String, paint: Paint) {
        val sh = h * 0.13f
        paint.color = Color.argb(170, 0, 0, 0)
        cv.drawRect(0f, h - sh, w.toFloat(), h.toFloat(), paint)
        paint.color = Color.WHITE
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = sh * 0.72f
        paint.textAlign = Paint.Align.LEFT
        cv.drawText(call, h * 0.03f, h - sh * 0.24f, paint)
        paint.typeface = Typeface.DEFAULT
        paint.textSize = sh * 0.45f
        paint.textAlign = Paint.Align.RIGHT
        cv.drawText(line, w - h * 0.03f, h - sh * 0.3f, paint)
    }
}
