package com.ok1cdj.atvrx

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Hardware video decoder on a Surface, on its own thread behind a queue of access units (the TS thread never waits).
 * With sound ([clock]: the PTS the audio is playing, null when there is none) every picture is decoded shortly before
 * its PTS comes and shown at it, so that picture and sound stay together - also when ldpc_tool hands out frames in
 * batches of 32 (tens of seconds at 40 kS/s). Without sound every access unit is shown as soon as it is decoded,
 * ignoring the time stamps (the beacon restarts its time base on every loop; the PC chain does the same with ffmpeg
 * setpts). The codec starts at the first parameter set (H.264 SPS / HEVC VPS), so decoding begins on a clean picture.
 * The Surface can come and go (screen off, app in the background): without one nothing is decoded, the TS still flows.
 */
class VideoDecoder(private var surface: Surface? = null) {
    private class Au(val type: Int, val data: ByteArray, val pts: Long)

    @Volatile var width = 0; private set
    @Volatile var height = 0; private set
    @Volatile var mime: String? = null; private set
    @Volatile var frames = 0L; private set
    @Volatile var error: String? = null; private set
    /** the PTS (90 kHz) the sound is playing now, null: no sound (show pictures at once) */
    @Volatile var clock: (() -> Long?)? = null
    /** picture minus sound when the last picture was shown [ms] (positive: the picture late), null: not synchronised */
    @Volatile var avMs: Long? = null; private set
    /** debug: every picture handed to the Surface (its PTS, the System.nanoTime it is to be shown at) */
    @Volatile var onRender: ((pts: Long, atNs: Long) -> Unit)? = null

    private var codec: MediaCodec? = null
    private var inPts = 0L
    private val info = MediaCodec.BufferInfo()
    private val queue = ArrayBlockingQueue<Au>(QUEUE)
    @Volatile private var generation = 0                 // reset / new Surface: drop what is being waited for
    @Volatile private var running = true
    private val thread = Thread({ loop() }, "rx-video").apply { start() }

    @Synchronized
    fun setSurface(s: Surface?) {
        if (s === surface) return
        releaseCodec()
        surface = s
        generation++
    }

    /** A new run: drop the decoder, the queue and the figures of the previous one (the codec restarts at the next parameter set). */
    @Synchronized
    fun reset() {
        queue.clear()
        releaseCodec()
        generation++
        width = 0; height = 0; mime = null; frames = 0; error = null; avMs = null
    }

    fun feed(streamType: Int, au: ByteArray, pts: Long) {
        if (surface == null) return
        while (!queue.offer(Au(streamType, au, pts))) queue.poll()
    }

    private fun loop() {
        while (running) {
            val au = try { queue.poll(200, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break } ?: continue
            val gen = generation
            pace(au, gen)
            synchronized(this) {
                if (gen != generation || surface == null) return@synchronized
                try {
                    val c = codec ?: start(au.type, au.data) ?: return@synchronized
                    val i = c.dequeueInputBuffer(100_000)
                    if (i >= 0) {
                        val inb = c.getInputBuffer(i)!!
                        inb.clear()
                        val n = minOf(au.data.size, inb.remaining())
                        inb.put(au.data, 0, n)
                        // the PTS through the codec in µs, rounded up so that x 9 / 100 gives it back exactly; without
                        // one a steady 25 fps
                        val us = if (au.pts >= 0) (au.pts * 100 + 8) / 9 else inPts.also { inPts += 40_000 }
                        c.queueInputBuffer(i, 0, n, us, 0)
                    }
                    drain(c)
                } catch (e: Exception) {
                    error = e.message
                    releaseCodec()
                }
            }
        }
    }

    /** With sound: wait until the picture is due within [LEAD] (not for a jump of the time base, at most [WAIT_MAX]). */
    private fun pace(au: Au, gen: Int) {
        if (au.pts < 0) return
        val t0 = System.nanoTime()
        while (running && gen == generation) {
            val clk = clock?.invoke() ?: return
            val d = TsDemux.ptsDiff(au.pts, clk)
            if (d <= LEAD || d > JUMP) return
            if (System.nanoTime() - t0 > WAIT_MAX) return
            try { Thread.sleep(10) } catch (_: InterruptedException) { return }
        }
    }

    private fun start(streamType: Int, au: ByteArray): MediaCodec? {
        val m = when (streamType) {
            TsDemux.ST_H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
            TsDemux.ST_HEVC -> MediaFormat.MIMETYPE_VIDEO_HEVC
            TsDemux.ST_MPEG2 -> MediaFormat.MIMETYPE_VIDEO_MPEG2
            else -> return null
        }
        if (!startsWithParameters(streamType, au)) return null
        val c = MediaCodec.createDecoderByType(m)
        c.configure(MediaFormat.createVideoFormat(m, 640, 360), surface, null, 0)
        c.start()
        codec = c; mime = m; error = null
        return c
    }

    /** Does the access unit carry the parameter sets (Annex B NAL units)? */
    private fun startsWithParameters(streamType: Int, au: ByteArray): Boolean {
        if (streamType == TsDemux.ST_MPEG2) return true
        var i = 0
        while (i + 3 < au.size) {
            if (au[i].toInt() == 0 && au[i + 1].toInt() == 0 && au[i + 2].toInt() == 1) {
                val h = au[i + 3].toInt() and 0xFF
                if (streamType == TsDemux.ST_H264 && (h and 0x1F) == 7) return true
                if (streamType == TsDemux.ST_HEVC && ((h shr 1) and 0x3F) == 32) return true
                i += 3
            } else i++
        }
        return false
    }

    private fun drain(c: MediaCodec) {
        while (true) {
            val o = c.dequeueOutputBuffer(info, 0)
            when {
                o >= 0 -> { render(c, o, info.presentationTimeUs * 9 / 100); frames++ }
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> c.outputFormat.let {
                    width = it.getInteger(MediaFormat.KEY_WIDTH); height = it.getInteger(MediaFormat.KEY_HEIGHT)
                }
                else -> return
            }
        }
    }

    /** At the time the sound reaches its PTS (up to a second ahead, the most a Surface takes), else now. */
    private fun render(c: MediaCodec, o: Int, pts: Long) {
        val clk = clock?.invoke()
        val now = System.nanoTime()
        if (clk == null) { avMs = null; c.releaseOutputBuffer(o, true); onRender?.invoke(pts, now); return }
        val d = TsDemux.ptsDiff(pts, clk)
        val at = if (d in 1 until 90_000) now + d * 1_000_000_000L / 90_000 else now
        avMs = if (d < 0) -d / 90 else 0
        c.releaseOutputBuffer(o, at)
        onRender?.invoke(pts, at)
    }

    private fun releaseCodec() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
    }

    @Synchronized
    fun release() = releaseCodec()

    companion object {
        private const val QUEUE = 2048                      // access units: 27 s at 15 fps and then some
        private const val LEAD = 27_000L                    // decode 300 ms before the PTS
        private const val JUMP = 60 * 90_000L               // further ahead: a new time base, do not wait (a batch of
        private const val WAIT_MAX = 60_000_000_000L        //   ldpc_tool at 40 kS/s with normal frames is 27 s)
    }
}
