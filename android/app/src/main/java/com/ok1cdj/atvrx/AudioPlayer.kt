package com.ok1cdj.atvrx

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** MPEG audio layer I / II / III with minimp3 (JNI, libmpadec.so): Android has no MP2 decoder. */
class MpegAudioDecoder : AutoCloseable {
    private var h = nativeNew()
    private var buf = ByteArray(16384)
    private var fill = 0
    private val pcm = ShortArray(MAX_SAMPLES)
    private val info = IntArray(4)
    var layer = 0; private set

    private var nextPts = -1L

    /**
     * Appends a PES payload (PTS [pts], -1: none) and decodes every whole frame in what has come:
     * out(pcm, interleaved samples, channels, Hz, PTS of the first sample or -1).
     */
    fun feed(d: ByteArray, pts: Long, out: Pcm) {
        if (pts >= 0 && fill == 0) nextPts = pts             // the PES starts with a frame (as muxers send audio)
        if (fill + d.size > buf.size) {
            if (fill + d.size > MAX_PENDING) fill = 0             // no frame in 64 kB: not MPEG audio, start again
            if (fill + d.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, fill + d.size))
        }
        System.arraycopy(d, 0, buf, fill, d.size)
        fill += d.size
        var p = 0
        while (p < fill) {
            val n = nativeDecode(h, buf, p, fill - p, pcm, info)
            if (info[0] == 0) break                               // a frame not complete yet (the first sync wants two)
            p += info[0]
            if (n > 0) {
                layer = info[3]
                out(pcm, n * info[1], info[1], info[2], nextPts)
                if (nextPts >= 0) nextPts += n * 90_000L / info[2]
            }
        }
        System.arraycopy(buf, p, buf, 0, fill - p)
        fill -= p
    }

    override fun close() { if (h != 0L) nativeFree(h); h = 0 }

    companion object {
        private const val MAX_SAMPLES = 1152 * 2
        private const val MAX_PENDING = 65536
        init { System.loadLibrary("mpadec") }
        @JvmStatic private external fun nativeNew(): Long
        @JvmStatic private external fun nativeFree(h: Long)
        @JvmStatic private external fun nativeDecode(h: Long, input: ByteArray, off: Int, len: Int, pcm: ShortArray, info: IntArray): Int
    }
}

/**
 * AAC in ADTS with the platform decoder. Android's decoder plays nothing when told "is-adts" (seen on Android 16), so the
 * headers are cut off here and the AudioSpecificConfig (csd-0) is made from the first one: LC at the core rate, which
 * also plays HE-AAC with implicit SBR signalling (what our TX and most encoders send in ADTS). A new header (rate,
 * channels) starts the decoder again.
 */
class AacAdtsDecoder : AutoCloseable {
    private var codec: MediaCodec? = null
    private var config = -1                                     // profile / rate index / channels of the running codec
    private var pending = ByteArray(8192)
    private var fill = 0
    private val bi = MediaCodec.BufferInfo()
    private var outShorts = ShortArray(0)
    var rate = 0; private set
    var channels = 0; private set
    var he = false; private set

    private var nextPts = -1L                                  // 90 kHz, of the next ADTS frame

    /** As [MpegAudioDecoder.feed]: a PES payload of ADTS frames with its PTS. */
    fun feed(d: ByteArray, pts: Long, out: Pcm) {
        if (pts >= 0 && fill == 0) nextPts = pts
        if (fill + d.size > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, fill + d.size))
        System.arraycopy(d, 0, pending, fill, d.size)
        fill += d.size
        var p = 0
        while (fill - p >= 7) {
            if (pending[p] != 0xFF.toByte() || (pending[p + 1].toInt() and 0xF6) != 0xF0) { p++; continue }   // sync, layer 0
            val len = ((pending[p + 3].toInt() and 3) shl 11) or ((pending[p + 4].toInt() and 0xFF) shl 3) or
                ((pending[p + 5].toInt() and 0xFF) shr 5)
            if (len < 7) { p++; continue }
            if (fill - p < len) break
            val head = if (pending[p + 1].toInt() and 1 == 0) 9 else 7         // protection_absent 0: CRC after the header
            val profile = (pending[p + 2].toInt() and 0xFF) shr 6
            val sfi = (pending[p + 2].toInt() shr 2) and 15
            val ch = ((pending[p + 2].toInt() and 1) shl 2) or ((pending[p + 3].toInt() and 0xFF) shr 6)
            val cfg = (profile shl 8) or (sfi shl 4) or ch
            if (cfg != config) start(profile, sfi, ch)
            codec?.let { decode(it, pending, p + head, len - head, out) }
            // 1024 samples at the core rate (also with SBR, which doubles the rate and the samples)
            if (nextPts >= 0) nextPts += 1024 * 90_000L / (RATES.getOrNull(sfi) ?: 48000)
            p += len
        }
        System.arraycopy(pending, p, pending, 0, fill - p)
        fill -= p
    }

    private fun start(profile: Int, sfi: Int, ch: Int) {
        close()
        val hz = RATES.getOrNull(sfi) ?: return
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, hz, maxOf(ch, 1))
        val aot = profile + 1
        f.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(byteArrayOf(((aot shl 3) or (sfi shr 1)).toByte(),
            (((sfi and 1) shl 7) or (ch shl 3)).toByte())))
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        c.configure(f, null, null, 0)
        c.start()
        codec = c
        config = (profile shl 8) or (sfi shl 4) or ch
        rate = hz; channels = ch
    }

    private var steady = 0L                                     // µs, for input without a PTS

    private fun decode(c: MediaCodec, d: ByteArray, off: Int, len: Int, out: Pcm) {
        val i = c.dequeueInputBuffer(50_000)
        if (i >= 0) {
            val b = c.getInputBuffer(i)!!
            b.clear()
            val n = minOf(len, b.remaining())
            b.put(d, off, n)
            // the PTS through the codec in µs; none: a steady count (always positive, so that -1 stays "none")
            c.queueInputBuffer(i, 0, n, if (nextPts >= 0) (nextPts * 100 + 8) / 9 + NO_PTS else (steady++), 0)
        }
        while (true) {
            val o = c.dequeueOutputBuffer(bi, 0)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = c.outputFormat
                rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                he = rate > (RATES.getOrNull(config shr 4 and 15) ?: rate)
                continue
            }
            if (o < 0) break
            val n = bi.size / 2
            if (outShorts.size < n) outShorts = ShortArray(n)
            c.getOutputBuffer(o)!!.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(outShorts, 0, n)
            c.releaseOutputBuffer(o, false)
            // HE-AAC: Android's decoder (FDK) stamps its output 47 ms earlier than ffmpeg's (measured with the same stream;
            // TX takes the codec delay from ffmpeg, the PC receivers): moved so that both play it in sync
            val fix = if (he) HE_FIX else 0
            if (n > 0) out(outShorts, n, channels, rate,
                if (bi.presentationTimeUs >= NO_PTS) (bi.presentationTimeUs - NO_PTS) * 9 / 100 + fix else -1)
        }
    }

    override fun close() {
        codec?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        codec = null; config = -1
    }

    companion object {
        private val RATES = listOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
        private const val NO_PTS = 1L shl 40                    // µs offset of real PTS through the codec
        private const val HE_FIX = 47L * 90                    // 90 kHz
    }
}

/** decoded PCM: (samples interleaved, count, channels, Hz, PTS of the first sample in 90 kHz or -1) */
typealias Pcm = (ShortArray, Int, Int, Int, Long) -> Unit

/**
 * The sound of the received service: audio PES packets from [TsDemux] go through a queue to their own thread (decoding
 * and the blocking AudioTrack must not hold up the TS), are decoded (AAC: [AacAdtsDecoder], MPEG audio incl. MP2:
 * [MpegAudioDecoder]) and played. LATM and AC-3 are only named. [muted] keeps decoding (the info line) but plays nothing.
 */
class AudioPlayer {
    private class Pes(val type: Int, val data: ByteArray, val pts: Long)

    @Volatile var muted = false
    @Volatile var description: String? = null; private set
    @Volatile var frames = 0L; private set          // PES packets decoded
    @Volatile var dropped = 0L; private set
    @Volatile var error: String? = null; private set
    /** every block of decoded PCM as it goes to the track (debug: RxReplayTestActivity writes it to a file) */
    @Volatile var tap: ((pcm: ShortArray, n: Int, channels: Int, hz: Int, pts: Long) -> Unit)? = null

    private val queue = ArrayBlockingQueue<Pes>(1024)
    @Volatile private var running = true
    @Volatile private var resetPending = false
    private var aac: AacAdtsDecoder? = null
    private var mpeg: MpegAudioDecoder? = null
    private var track: AudioTrack? = null
    private var trackRate = 0
    private var trackChannels = 0
    private var lastType = -1
    // the clock: which PTS starts at which frame written to the track (frames = samples per channel)
    private val anchors = ArrayDeque<LongArray>()               // [frame, pts]
    private val ts = android.media.AudioTimestamp()
    private var written = 0L
    @Volatile private var lastWriteNs = 0L
    private val thread = Thread({ loop() }, "rx-audio").apply { start() }

    fun feed(type: Int, data: ByteArray, pts: Long) {
        if (type !in TsDemux.PLAYABLE) { description = "${TsDemux.audioName(type)} – not supported"; return }
        while (!queue.offer(Pes(type, data, pts))) { queue.poll(); dropped++ }
    }

    /** A new run: drop what is queued, the decoders and the track (done on the audio thread). */
    fun reset() {
        queue.clear()
        resetPending = true
        description = null; frames = 0; dropped = 0; error = null
    }

    fun release() {
        running = false
        thread.interrupt()
        thread.join(1000)
    }

    private fun loop() {
        while (running) {
            val p = try { queue.poll(200, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break }
            if (resetPending) { closeAll(); resetPending = false }
            if (p == null) continue
            try {
                if (p.type != lastType) { closeAll(); lastType = p.type }
                val out: Pcm = { pcm, n, ch, hz, pts -> play(pcm, n, ch, hz, pts) }
                if (p.type == TsDemux.ST_AAC_ADTS) {
                    val d = aac ?: AacAdtsDecoder().also { aac = it }
                    d.feed(p.data, p.pts, out)
                    if (d.rate > 0) description = "AAC%s %d kHz %d ch".format(if (d.he) " (HE)" else "", d.rate / 1000, d.channels)
                } else {
                    val d = mpeg ?: MpegAudioDecoder().also { mpeg = it }
                    d.feed(p.data, p.pts, out)
                    if (d.layer > 0) description = "MP%d %d kHz %d ch".format(d.layer, trackRate / 1000, trackChannels)
                }
                frames++
            } catch (e: Exception) {
                error = e.message ?: e.toString()
                closeAll()
            }
        }
        closeAll()
    }

    /**
     * The PTS (90 kHz) of the sound coming out now: the last anchor at or before the frame at the speaker, plus the
     * frames played since. Null without sound, or when the track has played everything and nothing came for 0.5 s (the
     * signal is gone: the picture must not wait for it).
     */
    fun clock(): Long? = synchronized(anchors) {
        val t = track ?: return null
        // the frame at the speaker now: from the track's time stamp (it includes the output latency and is not
        // rounded to the mixer's blocks), else the play position
        val head = if (t.getTimestamp(ts)) ts.framePosition + (System.nanoTime() - ts.nanoTime) * trackRate / 1_000_000_000L
            else t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        if (head >= written && System.nanoTime() - lastWriteNs > 500_000_000L) return null
        while (anchors.size > 1 && anchors[1][0] <= head) anchors.removeFirst()
        val a = anchors.firstOrNull() ?: return null
        if (a[0] > head) return null
        a[1] + (head - a[0]) * 90_000L / trackRate
    }

    private fun play(pcm: ShortArray, n: Int, ch: Int, hz: Int, pts: Long) {
        if (ch !in 1..2 || hz <= 0) return
        if (track == null || hz != trackRate || ch != trackChannels) synchronized(anchors) {
            // under the clock's lock: clock() must not ask a released track
            track?.release()
            val mask = if (ch == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val min = AudioTrack.getMinBufferSize(hz, mask, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(hz).setChannelMask(mask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(min * 2, hz * ch * 2 / 5))          // at least 0.2 s
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build().apply { play() }
            trackRate = hz; trackChannels = ch
            anchors.clear(); written = 0
        }
        tap?.invoke(pcm, n, ch, hz, pts)
        val t = track ?: return
        t.setVolume(if (muted) 0f else 1f)
        if (pts >= 0) synchronized(anchors) {
            // a new anchor only when the time base jumps: small PTS jitter would make the clock jump back and forth
            val last = anchors.lastOrNull()
            val expect = last?.let { it[1] + (written - it[0]) * 90_000L / hz }
            if (expect == null || Math.abs(TsDemux.ptsDiff(pts, expect)) > 9_000) anchors.addLast(longArrayOf(written, pts))
        }
        var off = 0
        while (off < n && track === t) {
            val m = t.write(pcm, off, n - off, AudioTrack.WRITE_BLOCKING)
            if (m <= 0) break
            off += m
            synchronized(anchors) { written += m / ch }
            lastWriteNs = System.nanoTime()
        }
    }

    private fun closeAll() {
        aac?.close(); aac = null
        mpeg?.close(); mpeg = null
        synchronized(anchors) {
            track?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
            track = null; trackRate = 0; trackChannels = 0; lastType = -1
            anchors.clear(); written = 0
        }
    }
}
