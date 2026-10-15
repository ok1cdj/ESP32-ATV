package com.ok1cdj.atvrx

import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import java.io.ByteArrayOutputStream

/** Draws one video frame at w x h (the TX picture source: test card, camera). */
fun interface Painter {
    fun draw(cv: Canvas, w: Int, h: Int)
}

/**
 * Video for TX: [painter] draws every frame with a Canvas straight into a hardware H.264 encoder's input surface at
 * [fps]. Every access unit goes to [onAu] in Annex B with an access unit delimiter, key frames with SPS / PPS in front
 * (receivers can join at any key frame).
 */
class CanvasEncoder(val width: Int, val height: Int, val fps: Int, val bitrate: Int, private val keyInterval: Int,
                    val painter: Painter, private val onAu: (ByteArray, Boolean) -> Unit) {
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
                try { painter.draw(canvas, width, height) } finally { s.unlockCanvasAndPost(canvas) }
                next += period
                val wait = next - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt()) else next = System.nanoTime()
            }
        } catch (e: Exception) {
            if (running) error = "picture: ${e.message}"
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

    companion object {
        private val AUD = byteArrayOf(0, 0, 0, 1, 9, 0xF0.toByte())
    }
}
