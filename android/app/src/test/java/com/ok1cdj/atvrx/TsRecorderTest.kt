package com.ok1cdj.atvrx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TsRecorderTest {
    private fun packet(pid: Int, pusi: Boolean) = ByteArray(188).also {
        it[0] = 0x47; it[1] = ((if (pusi) 0x40 else 0) or (pid shr 8)).toByte(); it[2] = pid.toByte(); it[3] = 0x10
    }

    /** a TS packet starting a PES with [es] in it */
    private fun pes(pid: Int, es: ByteArray) = packet(pid, true).also {
        val h = byteArrayOf(0, 0, 1, 0xE0.toByte(), 0, 0, 0x80.toByte(), 0x80.toByte(), 5, 0x21, 0, 1, 0, 1)
        System.arraycopy(h, 0, it, 4, h.size); System.arraycopy(es, 0, it, 4 + h.size, es.size)
    }

    @Test fun keyFrames() {
        val aud = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())
        assertTrue(TsRecorder.keyFrame(pes(0x100, aud + byteArrayOf(0, 0, 0, 1, 0x67, 0x42)), 0, TsDemux.ST_H264))   // SPS
        assertFalse(TsRecorder.keyFrame(pes(0x100, aud + byteArrayOf(0, 0, 0, 1, 0x41, 0x9A.toByte())), 0, TsDemux.ST_H264))  // P slice
        assertTrue(TsRecorder.keyFrame(pes(0x100, byteArrayOf(0, 0, 1, 0x40, 1)), 0, TsDemux.ST_HEVC))                 // VPS
        assertFalse(TsRecorder.keyFrame(pes(0x100, byteArrayOf(0, 0, 1, 0x02, 1)), 0, TsDemux.ST_HEVC))                // TRAIL_R
        assertFalse(TsRecorder.keyFrame(packet(0x100, true), 0, TsDemux.ST_H264))                                      // no PES
    }

    @Test fun srtTime() {
        assertEquals("00:00:00,000", TsRecorder.srtTime(-5))
        assertEquals("01:02:03,004", TsRecorder.srtTime(3_723_004))
    }
}
