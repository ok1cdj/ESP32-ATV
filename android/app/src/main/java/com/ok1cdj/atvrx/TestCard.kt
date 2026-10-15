package com.ok1cdj.atvrx

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The TX test card: colour bars, callsign, two text lines and a clock (so that the picture visibly runs), drawn with
 * a Canvas straight into a hardware H.264 encoder's input surface at [fps]. Every access unit goes to [onAu] in
 * Annex B with an access unit delimiter, key frames with SPS / PPS in front (receivers can join at any key frame).
 */
class TestCard(val width: Int, val height: Int, val fps: Int, val bitrate: Int, private val keyInterval: Int,
               @Volatile var call: String, @Volatile var line2: String, @Volatile var line3: String,
               private val onAu: (ByteArray, Boolean) -> Unit) {
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    @Volatile private var running = false
    private var drawThread: Thread? = null
    private var drainThread: Thread? = null
    @Volatile var frames = 0L; private set
    @Volatile var error: String? = null; private set
    @Volatile var encoderName = ""; private set

    fun start() {
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyInterval)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            // the clock repaints only a corner; repeat the last frame if nothing new comes (some encoders stall)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / fps)
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoderName = c.name
        try {
            c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // not every encoder takes CBR, baseline or level 3.1: plain settings
            f.removeKey(MediaFormat.KEY_BITRATE_MODE); f.removeKey(MediaFormat.KEY_PROFILE); f.removeKey(MediaFormat.KEY_LEVEL)
            c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        surface = c.createInputSurface()
        c.start()
        codec = c
        running = true
        drawThread = Thread({ drawLoop() }, "tx-card").apply { start() }
        drainThread = Thread({ drainLoop(c) }, "tx-h264").apply { start() }
    }

    fun stop() {
        running = false
        drawThread?.join(1000)
        try { codec?.signalEndOfInputStream() } catch (_: Exception) {}
        drainThread?.join(1000)
        try { codec?.stop() } catch (_: Exception) {}
        codec?.release(); codec = null
        surface?.release(); surface = null
    }

    private fun drawLoop() {
        val s = surface ?: return
        val period = 1_000_000_000L / fps
        var next = System.nanoTime()
        try {
            while (running) {
                val canvas = s.lockHardwareCanvas()
                try { draw(canvas, width, height) } finally { s.unlockCanvasAndPost(canvas) }
                next += period
                val wait = next - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt()) else next = System.nanoTime()
            }
        } catch (e: Exception) {
            if (running) error = "card: ${e.message}"
        }
    }

    private fun drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var csd = ByteArray(0)
        try {
            while (true) {
                val i = c.dequeueOutputBuffer(info, 100_000)
                if (i < 0) { if (!running && i == MediaCodec.INFO_TRY_AGAIN_LATER) break else continue }
                val buf = c.getOutputBuffer(i)!!
                val data = ByteArray(info.size)
                buf.position(info.offset); buf.get(data)
                c.releaseOutputBuffer(i, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) { csd = data; continue }
                val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                val au = ByteArrayOutputStream(data.size + csd.size + 6)
                au.write(AUD)
                if (key && !startsWithSps(data)) au.write(csd)
                au.write(data)
                frames++
                onAu(au.toByteArray(), key)
            }
        } catch (e: Exception) {
            if (running) error = "encoder: ${e.message}"
        }
    }

    private fun startsWithSps(d: ByteArray): Boolean {
        val o = if (d.size > 4 && d[2].toInt() == 1) 3 else 4
        return d.size > o && (d[o].toInt() and 0x1F) == 7
    }

    private val bars = intArrayOf(Color.rgb(192, 192, 192), Color.rgb(192, 192, 0), Color.rgb(0, 192, 192),
        Color.rgb(0, 192, 0), Color.rgb(192, 0, 192), Color.rgb(192, 0, 0), Color.rgb(0, 0, 192))
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    /** The card at w x h (also used for the preview). */
    fun draw(cv: Canvas, w: Int, h: Int) {
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

    companion object {
        private val AUD = byteArrayOf(0, 0, 0, 1, 9, 0xF0.toByte())
    }
}
