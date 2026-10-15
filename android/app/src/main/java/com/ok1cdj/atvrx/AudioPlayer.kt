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

    /** Appends a PES payload and decodes every whole frame in what has come: out(pcm, interleaved samples, channels, Hz). */
    fun feed(d: ByteArray, out: (ShortArray, Int, Int, Int) -> Unit) {
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
            if (n > 0) { layer = info[3]; out(pcm, n * info[1], info[1], info[2]) }
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

    fun feed(d: ByteArray, out: (ShortArray, Int, Int, Int) -> Unit) {
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

    private var pts = 0L

    private fun decode(c: MediaCodec, d: ByteArray, off: Int, len: Int, out: (ShortArray, Int, Int, Int) -> Unit) {
        val i = c.dequeueInputBuffer(50_000)
        if (i >= 0) {
            val b = c.getInputBuffer(i)!!
            b.clear()
            val n = minOf(len, b.remaining())
            b.put(d, off, n)
            c.queueInputBuffer(i, 0, n, pts, 0)
            pts += 20_000
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
            if (n > 0) out(outShorts, n, channels, rate)
        }
    }

    override fun close() {
        codec?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        codec = null; config = -1
    }

    companion object {
        private val RATES = listOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
    }
}

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
    @Volatile var tap: ((pcm: ShortArray, n: Int, channels: Int, hz: Int) -> Unit)? = null

    private val queue = ArrayBlockingQueue<Pes>(1024)
    @Volatile private var running = true
    @Volatile private var resetPending = false
    private var aac: AacAdtsDecoder? = null
    private var mpeg: MpegAudioDecoder? = null
    private var track: AudioTrack? = null
    private var trackRate = 0
    private var trackChannels = 0
    private var lastType = -1
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
                val out = { pcm: ShortArray, n: Int, ch: Int, hz: Int -> play(pcm, n, ch, hz) }
                if (p.type == TsDemux.ST_AAC_ADTS) {
                    val d = aac ?: AacAdtsDecoder().also { aac = it }
                    d.feed(p.data, out)
                    if (d.rate > 0) description = "AAC%s %d kHz %d ch".format(if (d.he) " (HE)" else "", d.rate / 1000, d.channels)
                } else {
                    val d = mpeg ?: MpegAudioDecoder().also { mpeg = it }
                    d.feed(p.data, out)
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

    private fun play(pcm: ShortArray, n: Int, ch: Int, hz: Int) {
        if (ch !in 1..2 || hz <= 0) return
        if (track == null || hz != trackRate || ch != trackChannels) {
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
        }
        tap?.invoke(pcm, n, ch, hz)
        val t = track ?: return
        t.setVolume(if (muted) 0f else 1f)
        t.write(pcm, 0, n, AudioTrack.WRITE_BLOCKING)
    }

    private fun closeAll() {
        aac?.close(); aac = null
        mpeg?.close(); mpeg = null
        track?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        track = null; trackRate = 0; trackChannels = 0; lastType = -1
    }
}
