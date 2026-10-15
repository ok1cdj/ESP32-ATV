package com.ok1cdj.atvrx

import java.util.ArrayDeque

/**
 * A constant-rate MPEG transport stream of one service - H.264 video and optionally AAC audio (ADTS) - pulled one packet
 * at a time by the DVB-S2 encoder: every packet advances the mux clock by 1504 / [rate] seconds, so the stream runs at
 * exactly the channel's TS rate, padded with null packets. PAT and PMT every [psiInterval] s, SDT every 2 x that (they
 * cost little: at 40 kS/s one second is 38 packets), PCR on the video PID at most every [pcrInterval] s (in a video packet
 * when one goes out, else an adaptation-field-only packet). Audio goes out before video (small and regular).
 *
 * Time stamps come from the capture time (System.nanoTime / 1000, the clock of the encoders' input), so picture and
 * sound stay together: PTS = capture time on the mux clock + [delay]. The mux clock runs ahead of the capture clock by
 * what the transmitter has buffered; that lead is followed as it grows (start-up) and kept. [delay] has to cover the
 * encoders' latency and sending a key frame; a non-key video frame that would arrive after its PTS is dropped.
 */
class TsMux(val rate: Double, private val service: String, private val provider: String, val audio: Boolean = false,
            val delay: Double = 0.6, private val psiInterval: Double = 1.0, private val pcrInterval: Double = 0.2) {
    private class Au(val data: ByteArray, val key: Boolean, val us: Long)
    private class Pes(val data: ByteArray) { var off = 0 }

    private val video = ArrayDeque<Au>()
    private val sound = ArrayDeque<Au>()
    private val lock = Object()
    private var packets = 0L
    private val cc = IntArray(0x2000)
    private var nextPsi = 0.0
    private var sdtCount = 0
    private var lastPcr = -1.0
    private var lead = Double.NaN               // mux time - capture time [s]
    private var vCur: Pes? = null               // the PES going out
    private var aCur: Pes? = null
    private var vLastPts = -1L
    private var aLastPts = -1L
    // ADTS frames waiting to be packed into one PES (one frame per PES wastes most of a packet at low bit rates)
    private val aPend = java.io.ByteArrayOutputStream()
    private var aPendUs = 0L
    private var aPendDur = 0L
    @Volatile var queuedBytes = 0; private set
    @Volatile var droppedAus = 0L; private set
    @Volatile var videoBytes = 0L; private set   // PES bytes sent
    @Volatile var audioBytes = 0L; private set
    @Volatile var nullPackets = 0L; private set
    @Volatile var lateAus = 0L; private set      // sent although they arrive after their PTS (key frames, audio)
    /** the least PTS - (start of sending + its duration at the TS rate) so far [s], video and audio */
    @Volatile var videoSlack = Double.MAX_VALUE; private set
    @Volatile var audioSlack = Double.MAX_VALUE; private set

    val time get() = packets * 1504.0 / rate

    /** Queues an access unit (Annex B, with SPS/PPS before key frames) captured at [us] (System.nanoTime / 1000). */
    fun addAu(data: ByteArray, key: Boolean, us: Long) = synchronized(lock) {
        // nobody pulls (not started, stalled): keep at most 10 s
        if (!key && queuedBytes * 8.0 / rate > 10.0) { droppedAus++; return@synchronized }
        video.addLast(Au(data, key, us))
        queuedBytes += data.size
    }

    /** Queues one ADTS frame of [durUs] whose first sample was captured at [us]. */
    fun addAudio(adts: ByteArray, us: Long, durUs: Long) = synchronized(lock) {
        if (!audio) return@synchronized
        if (aPend.size() == 0) aPendUs = us
        aPend.write(adts)
        aPendDur += durUs
        if (aPend.size() >= AUDIO_PES_BYTES || aPendDur >= AUDIO_PES_US) {
            sound.addLast(Au(aPend.toByteArray(), true, aPendUs))
            aPend.reset(); aPendDur = 0
        }
    }

    /** The next 188-byte packet into out[off]. */
    fun next(out: ByteArray, off: Int = 0) {
        val t = time
        packets++
        val l = t - System.nanoTime() / 1e9
        if (lead.isNaN() || l > lead + 0.05) lead = l
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
        if (vCur == null) vCur = takeVideo(t)
        if (aCur == null && audio) aCur = takeAudio(t)
        val v = vCur
        val a = aCur
        when {
            a != null && !pcrDue -> {
                payload(out, off, PID_AUDIO, a, false, t)
                if (a.off >= a.data.size) aCur = null
            }
            v != null -> {
                payload(out, off, PID_VIDEO, v, pcrDue, t)
                if (v.off >= v.data.size) vCur = null
            }
            pcrDue -> pcrOnly(out, off, t)
            else -> {
                nullPackets++
                out.fill(0xFF.toByte(), off, off + 188)
                out[off] = 0x47; out[off + 1] = 0x1F; out[off + 2] = 0xFF.toByte(); out[off + 3] = 0x10
            }
        }
    }

    private fun ptsOf(us: Long) = Math.round((us / 1e6 + lead + delay) * 90000)

    /** The next video access unit as a PES; non-key frames that would come late are dropped. */
    private fun takeVideo(t: Double): Pes? {
        synchronized(lock) {
            while (true) {
                val au = video.pollFirst() ?: return null
                queuedBytes -= au.data.size
                var pts = ptsOf(au.us)
                val slack = pts / 90000.0 - (t + (au.data.size + 200) * 8.0 / rate)
                if (slack < 0 && !au.key) { droppedAus++; continue }
                if (slack < 0) lateAus++
                if (slack < videoSlack) videoSlack = slack
                if (vLastPts >= 0 && pts <= vLastPts) pts = vLastPts + 1
                vLastPts = pts
                return Pes(pesHeader(0xE0, pts, 0) + au.data)
            }
        }
    }

    private fun takeAudio(t: Double): Pes? = synchronized(lock) {
        val au = sound.pollFirst() ?: return@synchronized null
        var pts = ptsOf(au.us)
        val slack = pts / 90000.0 - (t + au.data.size * 8.0 / rate)
        if (slack < 0) lateAus++
        if (slack < audioSlack) audioSlack = slack
        if (aLastPts >= 0 && pts <= aLastPts) pts = aLastPts + 1
        aLastPts = pts
        Pes(pesHeader(0xC0, pts, au.data.size) + au.data)
    }

    /** PES header with a PTS; [len] the payload length (0: unbounded, video in TS) */
    private fun pesHeader(streamId: Int, pts: Long, len: Int): ByteArray {
        val h = ByteArray(14)
        h[2] = 1; h[3] = streamId.toByte()
        val pl = if (len > 0) len + 8 else 0
        h[4] = (pl shr 8).toByte(); h[5] = pl.toByte()
        h[6] = 0x84.toByte()                                // '10', data alignment indicator
        h[7] = 0x80.toByte()                                // PTS only
        h[8] = 5
        h[9] = (0x21 or (((pts shr 30) and 7).toInt() shl 1)).toByte()
        h[10] = (pts shr 22).toByte()
        h[11] = ((((pts shr 15) and 0x7F).toInt() shl 1) or 1).toByte()
        h[12] = (pts shr 7).toByte()
        h[13] = ((((pts and 0x7F).toInt()) shl 1) or 1).toByte()
        return h
    }

    /** A packet of a PES: header, adaptation field (PCR and / or stuffing), payload. */
    private fun payload(out: ByteArray, off: Int, pid: Int, pes: Pes, pcr: Boolean, t: Double) {
        val start = pes.off == 0
        val left = pes.data.size - pes.off
        val afMin = if (pcr) 8 else 0                       // length byte + flags + 6 PCR bytes
        val n = minOf(left, 184 - afMin)
        val af = 184 - n                                    // adaptation field bytes incl. its length byte
        out[off] = 0x47
        out[off + 1] = ((if (start) 0x40 else 0) or (pid shr 8)).toByte()
        out[off + 2] = pid.toByte()
        out[off + 3] = ((if (af > 0) 0x30 else 0x10) or nextCc(pid)).toByte()
        var p = off + 4
        if (af > 0) {
            out[p] = (af - 1).toByte()
            if (af > 1) {
                out[p + 1] = if (pcr) 0x10 else 0                       // PCR flag
                var q = p + 2
                if (pcr) { writePcr(out, q, t); q += 6; lastPcr = t }
                while (q < p + af) out[q++] = 0xFF.toByte()
            }
            p += af
        }
        System.arraycopy(pes.data, pes.off, out, p, n)
        pes.off += n
        if (pid == PID_VIDEO) videoBytes += n else audioBytes += n
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

    private fun pmt(): ByteArray {
        var body = byteArrayOf(
            (0xE0 or (PID_VIDEO shr 8)).toByte(), PID_VIDEO.toByte(), 0xF0.toByte(), 0,   // PCR PID, no program info
            0x1B, (0xE0 or (PID_VIDEO shr 8)).toByte(), PID_VIDEO.toByte(), 0xF0.toByte(), 0)   // H.264
        if (audio) body += byteArrayOf(0x0F, (0xE0 or (PID_AUDIO shr 8)).toByte(), PID_AUDIO.toByte(), 0xF0.toByte(), 0)  // AAC ADTS
        return section(0x02, PROGRAM, body)
    }

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
        const val PID_AUDIO = 0x0101
        const val PROGRAM = 1
        const val TSID = 1
        const val ONID = 1
        private const val AUDIO_PES_BYTES = 360         // about 2 packets
        private const val AUDIO_PES_US = 250_000L

        /** PTS delay for a video bit rate: encoder latency, the transmitter's buffer, sending a key frame (bigger at low rates) */
        fun delayFor(videoBps: Int) = if (videoBps < 80_000) 1.5 else 0.8

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
