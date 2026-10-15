package com.ok1cdj.atvrx

import java.util.ArrayDeque

/**
 * A constant-rate MPEG transport stream of one H.264 video service, pulled one packet at a time by the DVB-S2 encoder:
 * every packet advances the mux clock by 1504 / [rate] seconds, so the stream runs at exactly the channel's TS rate,
 * padded with null packets. PAT and PMT every [psiInterval] s, SDT every 2 x that (they cost little: at 40 kS/s one
 * second is 38 packets), PCR on the video PID at most every [pcrInterval] s (in a video packet when one goes out, else
 * an adaptation-field-only packet). PTS = the mux time when the access unit starts going out, plus the time it takes
 * to send it, plus [delay].
 */
class TsMux(val rate: Double, private val service: String, private val provider: String,
            private val psiInterval: Double = 1.0, private val pcrInterval: Double = 0.2, private val delay: Double = 0.4) {
    private class Au(val data: ByteArray, val key: Boolean)

    private val queue = ArrayDeque<Au>()
    private val lock = Object()
    private var packets = 0L
    private val cc = IntArray(0x2000)
    private var nextPsi = 0.0
    private var sdtCount = 0
    private var lastPcr = -1.0
    private var cur: ByteArray? = null          // PES of the access unit going out
    private var curOff = 0
    private var lastPts = -1L
    @Volatile var queuedBytes = 0; private set
    @Volatile var droppedAus = 0L; private set
    @Volatile var videoBytes = 0L; private set   // PES bytes sent
    @Volatile var nullPackets = 0L; private set

    val time get() = packets * 1504.0 / rate

    /**
     * Queues an access unit (Annex B, with SPS/PPS before key frames). When more than [maxQueue] seconds of video wait,
     * non-key frames are dropped until the next key frame (the encoder overshoots at very low bit rates).
     */
    fun addAu(data: ByteArray, key: Boolean, maxQueue: Double = 2.0) = synchronized(lock) {
        if (!key && queuedBytes * 8.0 / rate > maxQueue) { droppedAus++; return@synchronized }
        queue.addLast(Au(data, key))
        queuedBytes += data.size
    }

    /** The next 188-byte packet into out[off]. */
    fun next(out: ByteArray, off: Int = 0) {
        val t = time
        packets++
        if (t >= nextPsi) {
            nextPsi = t + psiInterval
            sdtCount = 0
            return psi(out, off, PID_PAT, pat())
        }
        if (sdtCount == 0) { sdtCount = 1; return psi(out, off, PID_PMT, pmt()) }
        if (sdtCount == 1) {
            sdtCount = 2
            if (Math.floor(nextPsi / psiInterval).toLong() % 2 == 0L) return psi(out, off, PID_SDT, sdt())
        }
        val pcrDue = lastPcr < 0 || t - lastPcr >= pcrInterval
        if (cur == null) synchronized(lock) {
            queue.pollFirst()?.let { au ->
                queuedBytes -= au.data.size
                cur = pes(au.data, t)
                curOff = 0
            }
        }
        val pes = cur
        if (pes == null) {
            if (pcrDue) return pcrOnly(out, off, t)
            nullPackets++
            out.fill(0xFF.toByte(), off, off + 188)
            out[off] = 0x47; out[off + 1] = 0x1F; out[off + 2] = 0xFF.toByte(); out[off + 3] = 0x10
            return
        }
        // a video packet: header, adaptation field (PCR and / or stuffing), payload
        val start = curOff == 0
        val left = pes.size - curOff
        val afMin = if (pcrDue) 8 else 0                    // length byte + flags + 6 PCR bytes
        val room = 184 - afMin
        val n = minOf(left, room)
        val af = 184 - n                                    // adaptation field bytes incl. its length byte
        out[off] = 0x47
        out[off + 1] = ((if (start) 0x40 else 0) or (PID_VIDEO shr 8)).toByte()
        out[off + 2] = PID_VIDEO.toByte()
        out[off + 3] = ((if (af > 0) 0x30 else 0x10) or nextCc(PID_VIDEO)).toByte()
        var p = off + 4
        if (af > 0) {
            out[p] = (af - 1).toByte()
            if (af > 1) {
                out[p + 1] = if (pcrDue) 0x10 else 0                    // PCR flag
                var q = p + 2
                if (pcrDue) { writePcr(out, q, t); q += 6; lastPcr = t }
                while (q < p + af) out[q++] = 0xFF.toByte()
            }
            p += af
        }
        System.arraycopy(pes, curOff, out, p, n)
        curOff += n
        videoBytes += n
        if (curOff >= pes.size) cur = null
    }

    private fun pes(au: ByteArray, t: Double): ByteArray {
        // PTS: when the whole access unit will have arrived (at the TS rate) plus a margin; never backwards
        var pts = Math.round((t + (au.size + 200) * 8.0 / rate * 1.1 + delay) * 90000)
        if (lastPts >= 0 && pts <= lastPts) pts = lastPts + 1
        lastPts = pts
        val h = ByteArray(14)
        h[2] = 1; h[3] = 0xE0.toByte()                      // start code, video stream 0
        // PES_packet_length 0: unbounded (video in TS)
        h[6] = 0x84.toByte()                                // '10', data alignment indicator
        h[7] = 0x80.toByte()                                // PTS only
        h[8] = 5
        h[9] = (0x21 or (((pts shr 30) and 7).toInt() shl 1)).toByte()
        h[10] = (pts shr 22).toByte()
        h[11] = ((((pts shr 15) and 0x7F).toInt() shl 1) or 1).toByte()
        h[12] = (pts shr 7).toByte()
        h[13] = ((((pts and 0x7F).toInt()) shl 1) or 1).toByte()
        return h + au
    }

    private fun pcrOnly(out: ByteArray, off: Int, t: Double) {
        out.fill(0xFF.toByte(), off, off + 188)
        out[off] = 0x47
        out[off + 1] = (PID_VIDEO shr 8).toByte(); out[off + 2] = PID_VIDEO.toByte()
        out[off + 3] = (0x20 or ((cc[PID_VIDEO] + 15) and 15)).toByte()   // adaptation field only: the previous counter
        out[off + 4] = 183.toByte()
        out[off + 5] = 0x10
        writePcr(out, off + 6, t)
        lastPcr = t
    }

    private fun writePcr(b: ByteArray, p: Int, t: Double) {
        val pcr = Math.round(t * 27_000_000)
        val base = (pcr / 300) and 0x1FFFFFFFFL
        val ext = (pcr % 300).toInt()
        b[p] = (base shr 25).toByte(); b[p + 1] = (base shr 17).toByte(); b[p + 2] = (base shr 9).toByte()
        b[p + 3] = (base shr 1).toByte()
        b[p + 4] = ((((base and 1).toInt()) shl 7) or 0x7E or (ext shr 8)).toByte()
        b[p + 5] = ext.toByte()
    }

    private fun nextCc(pid: Int): Int { val c = cc[pid]; cc[pid] = (c + 1) and 15; return c }

    /** A PSI section in one packet (all our tables fit). */
    private fun psi(out: ByteArray, off: Int, pid: Int, section: ByteArray) {
        out.fill(0xFF.toByte(), off, off + 188)
        out[off] = 0x47
        out[off + 1] = (0x40 or (pid shr 8)).toByte(); out[off + 2] = pid.toByte()
        out[off + 3] = (0x10 or nextCc(pid)).toByte()
        out[off + 4] = 0                                    // pointer field
        System.arraycopy(section, 0, out, off + 5, section.size)
    }

    private fun section(tableId: Int, ext: Int, body: ByteArray, flags: Int = 0xB0): ByteArray {
        val len = 5 + body.size + 4
        val s = ByteArray(3 + len)
        s[0] = tableId.toByte()
        s[1] = (flags or (len shr 8)).toByte(); s[2] = len.toByte()
        s[3] = (ext shr 8).toByte(); s[4] = ext.toByte()
        s[5] = 0xC1.toByte()                                // version 0, current
        s[6] = 0; s[7] = 0                                  // section 0 of 0
        System.arraycopy(body, 0, s, 8, body.size)
        val crc = crc32(s, s.size - 4)
        for (i in 0 until 4) s[s.size - 4 + i] = (crc shr (24 - 8 * i)).toByte()
        return s
    }

    private fun pat() = section(0x00, TSID, byteArrayOf(0, PROGRAM.toByte(), (0xE0 or (PID_PMT shr 8)).toByte(), PID_PMT.toByte()))

    private fun pmt() = section(0x02, PROGRAM, byteArrayOf(
        (0xE0 or (PID_VIDEO shr 8)).toByte(), PID_VIDEO.toByte(), 0xF0.toByte(), 0,   // PCR PID, no program info
        0x1B, (0xE0 or (PID_VIDEO shr 8)).toByte(), PID_VIDEO.toByte(), 0xF0.toByte(), 0))   // H.264

    private fun sdt(): ByteArray {
        val prov = provider.toByteArray(Charsets.ISO_8859_1).take(40).toByteArray()
        val name = service.toByteArray(Charsets.ISO_8859_1).take(40).toByteArray()
        val desc = byteArrayOf(0x48, (3 + prov.size + name.size).toByte(), 0x01, prov.size.toByte()) + prov +
            byteArrayOf(name.size.toByte()) + name           // service descriptor: digital television
        val svc = byteArrayOf(0, PROGRAM.toByte(), 0xFC.toByte(),
            (0x80 or (desc.size shr 8)).toByte(), desc.size.toByte()) + desc   // running, not scrambled
        val body = byteArrayOf(0, ONID.toByte(), 0xFF.toByte()) + svc
        return section(0x42, TSID, body, 0xF0)                // SDT: reserved_future_use = 1
    }

    companion object {
        const val PID_PAT = 0x0000
        const val PID_SDT = 0x0011
        const val PID_PMT = 0x1000
        const val PID_VIDEO = 0x0100
        const val PROGRAM = 1
        const val TSID = 1
        const val ONID = 1

        fun crc32(b: ByteArray, n: Int): Int {
            var c = -1
            for (i in 0 until n) {
                c = c xor ((b[i].toInt() and 0xFF) shl 24)
                repeat(8) { c = if (c < 0) (c shl 1) xor 0x04C11DB7 else c shl 1 }
            }
            return c
        }
    }
}
