package com.ok1cdj.atvrx

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** FileSource.resample: the sound of a video brought to the AAC encoder's rate and to one loop period. */
class FileSourceTest {
    private fun tone(hz: Double, rate: Int, n: Int) = ShortArray(n) { (10000 * sin(2 * PI * hz * it / rate)).toInt().toShort() }

    @Test
    fun keepsTheToneAcrossRates() {
        // 1 kHz at 44.1 kHz -> 24 kHz: still 1 kHz, the same level (linear interpolation: well under 1 % off at 1 kHz)
        val src = tone(1000.0, 44100, 44100)
        val out = FileSource.resample(src, src.size, 44100, 24000, 24000)
        val ref = tone(1000.0, 24000, 24000)
        val err = out.indices.maxOf { abs(out[it] - ref[it]) }
        assertEquals(0.0, err / 10000.0, 0.01)
    }

    @Test
    fun exactLengthSilenceAfterTheEnd() {
        // 0.5 s of sound for a 1 s period: n samples, the second half silent
        val src = ShortArray(24000) { 1000 }
        val out = FileSource.resample(src, src.size, 48000, 24000, 24000)
        assertEquals(24000, out.size)
        assertEquals(1000, out[11999].toInt())
        assertEquals(0, out[12000].toInt())
        assertEquals(0, out[23999].toInt())
    }

    @Test
    fun cutsALongerTrack() {
        val src = ShortArray(96000) { (it / 48).toShort() }               // 2 s, a ramp of 1 per ms
        val out = FileSource.resample(src, src.size, 48000, 48000, 48000)
        assertEquals(48000, out.size)
        assertEquals(999, out[47999].toInt())
    }

    @Test
    fun usesOnlyTheDecodedLength() {
        // the decode buffer is larger than what was decoded: nothing past len may leak in
        val src = ShortArray(1000) { if (it < 100) 500 else 32767 }
        val out = FileSource.resample(src, 100, 8000, 8000, 200)
        assertEquals(500, out[99].toInt())
        assertEquals(0, out[100].toInt())
    }
}
