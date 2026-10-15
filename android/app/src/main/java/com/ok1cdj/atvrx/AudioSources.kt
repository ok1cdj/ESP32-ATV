package com.ok1cdj.atvrx

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder

/**
 * The callsign in Morse (the station's identification in the sound): [wpm] words per minute, [hz] tone with 5 ms raised
 * cosine edges (no clicks), from [firstS] on every [periodS] seconds of the sample count.
 */
class CwId(call: String, private val periodS: Double, private val firstS: Double = 1.0, wpm: Int = 20,
           private val hz: Double = 700.0, private val level: Double = 0.3) {
    private val keying: BooleanArray                        // one element per dot length: on / off
    private val dotS = 1.2 / wpm
    private var rate = 0
    private var env = FloatArray(0)

    init {
        val k = ArrayList<Boolean>()
        for ((i, ch) in call.uppercase().withIndex()) {
            if (ch == ' ') { repeat(4) { k += false }; continue }       // 7 dots between words (3 already after the letter)
            val code = MORSE[ch] ?: continue
            if (i > 0 && call[i - 1] != ' ') repeat(2) { k += false }   // 3 dots between letters (1 after the element)
            for (e in code) {
                repeat(if (e == '-') 3 else 1) { k += true }
                k += false
            }
        }
        keying = k.toBooleanArray()
    }

    val seconds get() = keying.size * dotS

    /** The envelope at [r], smoothed so that every element starts and ends with a raised cosine. */
    private fun envelope(r: Int): FloatArray {
        if (r == rate) return env
        val per = (dotS * r).toInt()
        val edge = (0.005 * r).toInt().coerceAtLeast(1)
        fun win(x: Int) = if (x >= edge) 1f else (0.5 - 0.5 * Math.cos(Math.PI * (x + 0.5) / edge)).toFloat()
        val out = FloatArray(keying.size * per)
        var d = 0
        while (d < keying.size) {                           // every run of "on" dots: one element
            if (!keying[d]) { d++; continue }
            var e = d
            while (e < keying.size && keying[e]) e++
            val s0 = d * per; val s1 = e * per
            for (i in s0 until s1) out[i] = minOf(win(i - s0), win(s1 - 1 - i))
            d = e
        }
        rate = r; env = out
        return out
    }

    /** Sample k (counted from the start of the sound) at rate r, -1..1. */
    fun sample(k: Long, r: Int): Float {
        val e = envelope(r)
        val first = (firstS * r).toLong()
        if (k < first) return 0f
        val pos = ((k - first) % (periodS * r).toLong()).toInt()
        if (pos >= e.size) return 0f
        return (level * e[pos] * Math.sin(2 * Math.PI * hz * k / r)).toFloat()
    }

    companion object {
        private val MORSE = mapOf(
            'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".", 'F' to "..-.", 'G' to "--.", 'H' to "....",
            'I' to "..", 'J' to ".---", 'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.", 'O' to "---", 'P' to ".--.",
            'Q' to "--.-", 'R' to ".-.", 'S' to "...", 'T' to "-", 'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-",
            'Y' to "-.--", 'Z' to "--..", '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-",
            '5' to ".....", '6' to "-....", '7' to "--...", '8' to "---..", '9' to "----.", '/' to "-..-.")
    }
}

/** Only the callsign in Morse, silence between (no microphone). */
fun cwPcm(cw: CwId) = GeneratedPcm { k, r -> (cw.sample(k, r) * 32767).toInt().toShort() }

/**
 * The phone's microphone (the one for video recording), with [overlay] (the CW identification) added. The capture time
 * of every buffer comes from AudioRecord's time stamp (frame position <-> System.nanoTime), so it lines up with the camera.
 */
class MicPcm(private val overlay: CwId? = null) : PcmSource {
    private var rec: AudioRecord? = null
    private var rate = 48000
    private var k = 0L
    private val ts = AudioTimestamp()

    @SuppressLint("MissingPermission")                     // TxActivity asks for RECORD_AUDIO before TX starts
    override fun start(rate: Int) {
        this.rate = rate
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(MediaRecorder.AudioSource.CAMCORDER, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, rate / 5 * 2))
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); throw IllegalStateException("microphone not available") }
        r.startRecording()
        rec = r
        k = 0
    }

    override fun read(buf: ShortArray, n: Int): Long {
        val r = rec ?: throw IllegalStateException("microphone stopped")
        var got = 0
        while (got < n) {
            val m = r.read(buf, got, n - got)
            if (m < 0) throw IllegalStateException("microphone: error $m")
            got += m
        }
        val now = System.nanoTime()
        // capture time of buf[0]: from the time stamp when there is one, else "the last sample is now"
        val first = if (r.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS)
            ts.nanoTime + (k - ts.framePosition) * 1_000_000_000L / rate
        else now - n * 1_000_000_000L / rate
        overlay?.let { cw ->
            for (i in 0 until n) buf[i] = (buf[i] + cw.sample(k + i, rate) * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }
        k += n
        return first / 1000
    }

    override fun stop() {
        rec?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        rec = null
    }
}
