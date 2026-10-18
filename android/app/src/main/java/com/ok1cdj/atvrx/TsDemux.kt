package com.ok1cdj.atvrx

/**
 * MPEG-TS demultiplexer for one DVB-S2 service: PAT -> PMT -> the first video and the first audio stream; SDT for the
 * service name and provider. Video PES packets are reassembled and handed out whole (one access unit each, as the beacon
 * and usual encoders send them), audio PES packets whole too to [onAudio], both with their PTS (90 kHz, -1: none). Sections
 * are taken from single TS packets, which is what small PSI/SI tables fit in.
 */
class TsDemux(private val onVideo: (streamType: Int, es: ByteArray, pts: Long) -> Unit) {
    @Volatile var onAudio: ((streamType: Int, es: ByteArray, pts: Long) -> Unit)? = null
    @Volatile var audioPid = -1; private set
    @Volatile var audioType = -1; private set
    @Volatile var service: String? = null; private set
    @Volatile var provider: String? = null; private set
    @Volatile var videoPid = -1; private set
    @Volatile var videoType = -1; private set
    @Volatile var packets = 0L; private set
    @Volatile var errorPackets = 0L; private set     // transport_error_indicator set by the demodulator
    @Volatile var ccErrors = 0L; private set

    @Volatile var pmtPid = -1; private set
    private val buf = ByteArray(188 * 64)
    private var fill = 0
    private var pes = java.io.ByteArrayOutputStream(64 * 1024)
    private var pesOk = false
    private var lastCc = -1
    private val apes = java.io.ByteArrayOutputStream(16 * 1024)
    private var apesOk = false
    private var aLastCc = -1
    private var aLen = 0                                    // PES_packet_length of the audio PES (0: unbounded)

    fun feed(b: ByteArray, n: Int) {
        var i = 0
        while (i < n) {
            val k = minOf(n - i, buf.size - fill)
            System.arraycopy(b, i, buf, fill, k)
            fill += k; i += k
            var p = 0
            while (fill - p >= 188) {
                if (buf[p] != 0x47.toByte()) { p++; continue }  // resync
                packet(buf, p)
                p += 188
            }
            System.arraycopy(buf, p, buf, 0, fill - p)
            fill -= p
        }
    }

    private fun packet(b: ByteArray, o: Int) {
        packets++
        val b1 = b[o + 1].toInt() and 0xFF
        if (b1 and 0x80 != 0) {
            errorPackets++
            val pid = pidOf(b, o)
            if (pid == videoPid) pesOk = false
            if (pid == audioPid) apesOk = false
            return
        }
        val pid = pidOf(b, o)
        val pusi = b1 and 0x40 != 0
        val b3 = b[o + 3].toInt() and 0xFF
        val afc = (b3 shr 4) and 3
        if (afc and 1 == 0) return
        var p = o + 4
        if (afc and 2 != 0) p += 1 + (b[o + 4].toInt() and 0xFF)
        if (p >= o + 188) return
        when {
            pid == 0 && pusi -> section(b, p, o + 188)?.let { pat(b, it, o + 188) }
            pid == pmtPid && pusi -> section(b, p, o + 188)?.let { pmt(b, it, o + 188) }
            pid == 0x11 && pusi -> section(b, p, o + 188)?.let { sdt(b, it, o + 188) }
            pid == videoPid -> video(b, p, o + 188, pusi, b3 and 0x0F)
            pid == audioPid -> audio(b, p, o + 188, pusi, b3 and 0x0F)
        }
    }

    private fun pidOf(b: ByteArray, o: Int) = ((b[o + 1].toInt() and 0x1F) shl 8) or (b[o + 2].toInt() and 0xFF)
    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    /** Start of the section after the pointer field, if the whole section is in this packet. */
    private fun section(b: ByteArray, p: Int, end: Int): Int? {
        val s = p + 1 + u8(b, p)
        if (s + 3 > end) return null
        val len = ((u8(b, s + 1) and 0x0F) shl 8) or u8(b, s + 2)
        return if (s + 3 + len <= end) s else null
    }

    private fun sectionEnd(b: ByteArray, s: Int) = s + 3 + (((u8(b, s + 1) and 0x0F) shl 8) or u8(b, s + 2)) - 4  // before CRC

    private fun pat(b: ByteArray, s: Int, end: Int) {
        if (u8(b, s) != 0x00) return
        var i = s + 8
        val e = sectionEnd(b, s)
        while (i + 4 <= e) {
            val prog = (u8(b, i) shl 8) or u8(b, i + 1)
            val pid = ((u8(b, i + 2) and 0x1F) shl 8) or u8(b, i + 3)
            if (prog != 0) { pmtPid = pid; return }
            i += 4
        }
    }

    private fun pmt(b: ByteArray, s: Int, end: Int) {
        if (u8(b, s) != 0x02) return
        var i = s + 12 + (((u8(b, s + 10) and 0x0F) shl 8) or u8(b, s + 11))
        val e = sectionEnd(b, s)
        var video = false
        var playable: Pair<Int, Int>? = null; var other: Pair<Int, Int>? = null     // (pid, kind)
        while (i + 5 <= e) {
            val type = u8(b, i)
            val pid = ((u8(b, i + 1) and 0x1F) shl 8) or u8(b, i + 2)
            val esInfo = ((u8(b, i + 3) and 0x0F) shl 8) or u8(b, i + 4)
            if (!video && (type == ST_H264 || type == ST_HEVC || type == ST_MPEG2)) {
                if (pid != videoPid) { videoPid = pid; pes.reset(); pesOk = false }
                videoType = type
                video = true
            }
            val a = audioOf(type, b, i + 5, minOf(e, i + 5 + esInfo))
            if (a in PLAYABLE) { if (playable == null) playable = pid to a } else if (a != 0 && other == null) other = pid to a
            i += 5 + esInfo
        }
        // audio: the first one we can play, else the first one there is (shown as not supported)
        (playable ?: other)?.let { (pid, a) ->
            if (pid != audioPid) { audioPid = pid; apes.reset(); apesOk = false; aLastCc = -1 }
            audioType = a
        }
    }

    /** The audio kind of a PMT entry (ST_* or AC3 for AC-3 / E-AC-3 in a private stream), 0: not audio. */
    private fun audioOf(type: Int, b: ByteArray, d0: Int, d1: Int): Int {
        if (type == ST_MPEG1_AUDIO || type == ST_MPEG2_AUDIO || type == ST_AAC_ADTS || type == ST_AAC_LATM) return type
        if (type == 0x81 || type == 0x87) return AC3
        if (type == 0x06) {                                         // private data: AC-3 / E-AC-3 descriptors (DVB)
            var j = d0
            while (j + 2 <= d1) {
                val tag = u8(b, j)
                if (tag == 0x6A || tag == 0x7A) return AC3
                j += 2 + u8(b, j + 1)
            }
        }
        return 0
    }

    private fun sdt(b: ByteArray, s: Int, end: Int) {
        if (u8(b, s) != 0x42) return
        var i = s + 11
        val e = sectionEnd(b, s)
        if (i + 5 > e) return
        val loopEnd = minOf(e, i + 5 + (((u8(b, i + 3) and 0x0F) shl 8) or u8(b, i + 4)))
        i += 5
        while (i + 2 <= loopEnd) {
            val tag = u8(b, i); val len = u8(b, i + 1)
            if (tag == 0x48 && i + 2 + len <= loopEnd) {        // service_descriptor
                var j = i + 3
                val pl = u8(b, j); provider = text(b, j + 1, pl); j += 1 + pl
                val nl = u8(b, j); service = text(b, j + 1, nl)
                return
            }
            i += 2 + len
        }
    }

    /** DVB text (EN 300 468 annex A): default table ~ Latin; a leading byte < 0x20 selects a table, skipped. */
    private fun text(b: ByteArray, i: Int, n: Int): String {
        var k = i; var m = n
        if (m > 0 && u8(b, k) < 0x20) { k++; m-- }
        return String(b, k, m, Charsets.ISO_8859_1).trim()
    }

    private fun video(b: ByteArray, p: Int, end: Int, pusi: Boolean, cc: Int) {
        if (lastCc >= 0 && cc != ((lastCc + 1) and 0x0F)) { ccErrors++; pesOk = false }
        lastCc = cc
        if (pusi) {
            flushPes()
            pes.reset()
            pesOk = true
        }
        if (pesOk) pes.write(b, p, end - p)
    }

    private fun audio(b: ByteArray, p: Int, end: Int, pusi: Boolean, cc: Int) {
        if (aLastCc >= 0 && cc != ((aLastCc + 1) and 0x0F)) { ccErrors++; apesOk = false }
        aLastCc = cc
        if (pusi) {
            flushAudio()
            apes.reset()
            apesOk = true
        }
        if (!apesOk) return
        if (pusi) aLen = if (end - p >= 6) (u8(b, p + 4) shl 8) or u8(b, p + 5) else 0
        apes.write(b, p, end - p)
        // bounded PES (audio usually is): out as soon as it is complete, not at the next one
        if (aLen > 0 && apes.size() >= 6 + aLen) { flushAudio(); apes.reset(); apesOk = false }
    }

    private fun flushAudio() {
        val cb = onAudio ?: return
        if (!apesOk || apes.size() < 9) return
        val d = apes.toByteArray()
        if (d[0].toInt() != 0 || d[1].toInt() != 0 || d[2].toInt() != 1) return
        val len = (u8(d, 4) shl 8) or u8(d, 5)
        val stop = if (len > 0) minOf(d.size, 6 + len) else d.size
        val start = 9 + u8(d, 8)
        if (start < stop) cb(audioType, d.copyOfRange(start, stop), pts(d, start))
    }

    private fun flushPes() {
        if (!pesOk || pes.size() < 9) return
        val d = pes.toByteArray()
        if (d[0].toInt() != 0 || d[1].toInt() != 0 || d[2].toInt() != 1) return
        val start = 9 + u8(d, 8)
        if (start < d.size) onVideo(videoType, d.copyOfRange(start, d.size), pts(d, start))
    }

    /** PTS of a PES (header in d, payload from [start]), -1: none */
    private fun pts(d: ByteArray, start: Int) = if (u8(d, 7) and 0x80 != 0 && start >= 14)
        (((u8(d, 9) shr 1) and 7).toLong() shl 30) or (u8(d, 10).toLong() shl 22) or ((u8(d, 11) shr 1).toLong() shl 15) or
            (u8(d, 12).toLong() shl 7) or (u8(d, 13) shr 1).toLong()
    else -1L

    companion object {
        const val ST_MPEG2 = 0x02; const val ST_H264 = 0x1B; const val ST_HEVC = 0x24
        const val ST_MPEG1_AUDIO = 0x03; const val ST_MPEG2_AUDIO = 0x04; const val ST_AAC_ADTS = 0x0F; const val ST_AAC_LATM = 0x11
        const val AC3 = 0x100                                     // AC-3 / E-AC-3, whatever the stream type
        val PLAYABLE = setOf(ST_MPEG1_AUDIO, ST_MPEG2_AUDIO, ST_AAC_ADTS)

        /** a - b of two 33-bit PTS (90 kHz), across the wrap */
        fun ptsDiff(a: Long, b: Long): Long = ((a - b + (1L shl 32)) and ((1L shl 33) - 1)) - (1L shl 32)

        fun audioName(t: Int) = when (t) {
            ST_MPEG1_AUDIO, ST_MPEG2_AUDIO -> "MPEG audio"; ST_AAC_ADTS -> "AAC"; ST_AAC_LATM -> "AAC LATM"; AC3 -> "AC-3"
            else -> "–"
        }
    }
}
