package com.ok1cdj.atvrx

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat

/** 16-bit mono PCM delivered in real time (microphone, generator). */
interface PcmSource {
    fun start(rate: Int)
    /** Fills buf[0 until n], blocking until the samples exist; the capture time of the first one (System.nanoTime / 1000). */
    fun read(buf: ShortArray, n: Int): Long
    fun stop()
}

/** A generated signal at its real-time pace: sample k is "captured" at start + k / rate. */
class GeneratedPcm(private val sample: (k: Long, rate: Int) -> Short) : PcmSource {
    private var rate = 48000
    private var t0 = 0L
    private var k = 0L
    override fun start(rate: Int) { this.rate = rate; t0 = System.nanoTime(); k = 0 }
    override fun read(buf: ShortArray, n: Int): Long {
        val first = t0 + k * 1_000_000_000L / rate
        val wait = t0 + (k + n) * 1_000_000_000L / rate - System.nanoTime()
        if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
        for (i in 0 until n) buf[i] = sample(k + i, rate)
        k += n
        return first / 1000
    }
    override fun stop() {}
}

/**
 * Mono AAC for TX with Android's own encoder (c2.android.aac.encoder: every device has it, and it does HE-AAC; vendor
 * encoders often only LC): HE-AAC v1 at 24 kHz for low bit rates, AAC-LC at 48 kHz above [HE_MAX] b/s. Every frame goes
 * to [onFrame] with an ADTS header (implicit SBR signalling: LC at the core rate, which every AAC decoder plays), the
 * capture time of its first sample and its duration. The time is moved back by the codec's delay (encoder + decoder: 2048
 * samples at the core rate, HE-AAC another 962 for SBR - measured 44 / 207 ms with MuxSelfTest), so that the sound
 * plays with the picture captured at the same moment.
 */
class AacEncoder(val bitrate: Int, private val src: PcmSource, private val onFrame: (adts: ByteArray, us: Long, durUs: Long) -> Unit) {
    val he = bitrate <= HE_MAX
    val rate = if (he) 24000 else 48000
    private val frameSamples = if (he) 2048 else 1024
    private val delayUs = if (he) 2048 * 2_000_000L / rate + 962 * 1_000_000L / rate else 2048 * 1_000_000L / rate
    private var codec: MediaCodec? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile var frames = 0L; private set
    @Volatile var error: String? = null; private set
    @Volatile var encoderName = ""; private set

    val description get() = "%s %d kb/s %d kHz".format(if (he) "HE-AAC" else "AAC", bitrate / 1000, rate / 1000)

    fun start() {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, if (he) MediaCodecInfo.CodecProfileLevel.AACObjectHE
                else MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * frameSamples * 2)
        }
        val c = try { MediaCodec.createByCodecName("c2.android.aac.encoder") }
            catch (_: Exception) { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC) }
        encoderName = c.name
        c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        c.start()
        codec = c
        running = true
        thread = Thread({ loop(c) }, "tx-aac").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(1000)
        try { codec?.stop() } catch (_: Exception) {}
        codec?.release(); codec = null
        src.stop()
    }

    private fun loop(c: MediaCodec) {
        val pcm = ShortArray(frameSamples)
        val info = MediaCodec.BufferInfo()
        val core = SAMPLE_RATES.indexOf(if (he) rate / 2 else rate)
        val durUs = frameSamples * 1_000_000L / rate
        try {
            src.start(rate)
            while (running) {
                val us = src.read(pcm, frameSamples)
                val i = c.dequeueInputBuffer(100_000)
                if (i >= 0) {
                    val b = c.getInputBuffer(i)!!
                    b.clear()
                    b.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, 0, frameSamples)
                    c.queueInputBuffer(i, 0, 2 * frameSamples, us, 0)
                }
                while (true) {
                    val o = c.dequeueOutputBuffer(info, 0)
                    if (o < 0) break
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val len = info.size + 7
                        val adts = ByteArray(len)
                        adts[0] = 0xFF.toByte(); adts[1] = 0xF1.toByte()           // MPEG-4, no CRC
                        adts[2] = ((1 shl 6) or (core shl 2)).toByte()             // AAC LC, core sampling rate, mono:
                        adts[3] = ((1 shl 6) or (len shr 11)).toByte()             //   channel configuration 1
                        adts[4] = (len shr 3).toByte()
                        adts[5] = (((len and 7) shl 5) or 0x1F).toByte()           // buffer fullness 0x7FF: VBR
                        adts[6] = 0xFC.toByte()
                        val b = c.getOutputBuffer(o)!!
                        b.position(info.offset); b.get(adts, 7, info.size)
                        frames++
                        onFrame(adts, info.presentationTimeUs - delayUs, durUs)
                    }
                    c.releaseOutputBuffer(o, false)
                }
            }
        } catch (e: Exception) {
            if (running) error = "audio: ${e.message}"
        }
    }

    companion object {
        const val HE_MAX = 32_000
        private val SAMPLE_RATES = listOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000)
    }
}
