package com.ok1cdj.atvrx

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The TX test card: colour bars, callsign, two text lines and a clock (so that the picture visibly runs). */
class TestCard(@Volatile var call: String, @Volatile var line2: String, @Volatile var line3: String) : Painter {
    private val bars = intArrayOf(Color.rgb(192, 192, 192), Color.rgb(192, 192, 0), Color.rgb(0, 192, 192),
        Color.rgb(0, 192, 0), Color.rgb(192, 0, 192), Color.rgb(192, 0, 0), Color.rgb(0, 0, 192))
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    @Synchronized                                       // the encoder's thread and the preview both draw
    override fun draw(cv: Canvas, w: Int, h: Int) {
        val top = h * 0.58f
        val bw = w / bars.size.toFloat()
        bars.forEachIndexed { i, col -> paint.color = col; cv.drawRect(i * bw, 0f, (i + 1) * bw, top, paint) }
        paint.color = Color.BLACK
        cv.drawRect(0f, top, w.toFloat(), h.toFloat(), paint)
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.color = Color.WHITE
        paint.textSize = h * 0.17f
        cv.drawText(call, w / 2f, top + h * 0.17f, paint)
        paint.typeface = Typeface.DEFAULT
        paint.textSize = h * 0.075f
        paint.color = Color.YELLOW
        cv.drawText(line2, w / 2f, top + h * 0.27f, paint)
        paint.color = Color.WHITE
        cv.drawText(line3, w / 2f, top + h * 0.36f, paint)
        // the clock in a black box in the bottom right corner of the bars, so that it is readable on any bar
        paint.textAlign = Paint.Align.RIGHT
        paint.textSize = h * 0.08f
        val t = clock.format(Date())
        val tw = paint.measureText(t)
        paint.color = Color.BLACK
        cv.drawRect(w - tw - h * 0.07f, top - h * 0.13f, w.toFloat(), top, paint)
        paint.color = Color.WHITE
        cv.drawText(t, w - h * 0.035f, top - h * 0.035f, paint)
    }

}
