package com.ok1cdj.atvrx

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface

/**
 * Hardware video decoder on a Surface. Every access unit is shown as soon as it is decoded, ignoring the PCR/PTS
 * time base: the beacon restarts its time base on every loop (the PC chain does the same with ffmpeg setpts).
 * The codec starts at the first parameter set (H.264 SPS / HEVC VPS), so decoding begins on a clean picture.
 */
class VideoDecoder(private val surface: Surface) {
    @Volatile var width = 0; private set
    @Volatile var height = 0; private set
    @Volatile var mime: String? = null; private set
    @Volatile var frames = 0L; private set
    @Volatile var error: String? = null; private set

    private var codec: MediaCodec? = null
    private var pts = 0L
    private val info = MediaCodec.BufferInfo()

    fun feed(streamType: Int, au: ByteArray) {
        try {
            val c = codec ?: start(streamType, au) ?: return
            val i = c.dequeueInputBuffer(20_000)
            if (i >= 0) {
                val inb = c.getInputBuffer(i)!!
                inb.clear()
                val n = minOf(au.size, inb.remaining())
                inb.put(au, 0, n)
                c.queueInputBuffer(i, 0, n, pts, 0)
                pts += 40_000
            }
            drain(c)
        } catch (e: Exception) {
            error = e.message
            release()
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
                o >= 0 -> { c.releaseOutputBuffer(o, true); frames++ }
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> c.outputFormat.let {
                    width = it.getInteger(MediaFormat.KEY_WIDTH); height = it.getInteger(MediaFormat.KEY_HEIGHT)
                }
                else -> return
            }
        }
    }

    fun release() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
    }
}
