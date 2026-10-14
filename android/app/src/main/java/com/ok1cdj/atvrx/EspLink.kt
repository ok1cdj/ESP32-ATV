package com.ok1cdj.atvrx

import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

/**
 * Control protocol and stream parser of the ESP32-S3 receiver firmware (esp32-sdr-trx narrowband image), a port of
 * espdr/nb.py: requests B4 op arg seq crc32, responses B5 .. value crc32, sample packets E5 5D with a 20-byte header,
 * bench blocks BE EF counter (64 bytes).
 */
class EspLink(private val usb: UsbCdc) {
    data class Response(val op: Int, val status: Int, val value: Long)

    /** One sample packet; the whole packet (header + payload) is buf[off until off + size]. */
    class Packet(val format: Int, val count: Int, val dropped: Int, val seq: Long, val first: Long, val shift: Int,
                 val buf: ByteArray, val off: Int, val size: Int)

    class Stats {
        var packets = 0L; var samples = 0L; var lost = 0L; var dropped = 0; var skipped = 0L; var bad = 0L
        var bytes = 0L; var benchBlocks = 0L; var benchBad = 0L
    }

    @Volatile var onPacket: ((Packet) -> Unit)? = null
    @Volatile var bench = false
    val stats = Stats()

    private val responses = LinkedBlockingQueue<Response>()
    private var seq = 0
    private var buf = ByteArray(1 shl 18)
    private var start = 0
    private var end = 0
    private var expected = -1L
    private var benchCounter = -1
    @Volatile private var running = true
    @Volatile var error: Throwable? = null
    private val reader = Thread({
        try {
            usb.readLoop({ running }) { b, n -> feed(b, n) }
        } catch (t: Throwable) {
            error = t
        }
    }, "usb-reader").apply { start() }

    fun close() {
        running = false
        reader.join(2000)
        usb.close()
    }

    // ---- control

    private fun request(op: Int, arg: Int): ByteArray {
        seq = (seq + 1) and 0xFFFF
        val b = ByteArray(10)
        b[0] = REQ_MAGIC.toByte(); b[1] = op.toByte()
        b[2] = arg.toByte(); b[3] = (arg shr 8).toByte()
        b[4] = seq.toByte(); b[5] = (seq shr 8).toByte()
        val crc = CRC32().apply { update(b, 0, 6) }.value
        for (i in 0 until 4) b[6 + i] = (crc shr (8 * i)).toByte()
        return b
    }

    /** Sends op (with ESP_ARG_HIGH first for arguments above 16 bits); does not wait for the answer. */
    fun send(op: Int, arg: Long = 0) {
        if (arg > 0xFFFF) {
            usb.write(request(ESP_ARG_HIGH, (arg shr 16).toInt()))
            await(ESP_ARG_HIGH, 5000)
        }
        usb.write(request(op, (arg and 0xFFFF).toInt()))
    }

    fun await(op: Int, timeoutMs: Long): Response {
        val until = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            error?.let { throw IOException("USB reader: ${it.message}", it) }
            val left = (until - System.nanoTime()) / 1_000_000
            if (left <= 0) throw IOException("no response to op $op")
            val r = responses.poll(minOf(left, 200), TimeUnit.MILLISECONDS) ?: continue
            if (r.op == op) return r
        }
    }

    fun command(op: Int, arg: Long = 0, allow: Set<Int> = setOf(CTL_OK)): Response {
        send(op, arg)
        val r = await(op, 5000)
        if (r.status !in allow) throw IOException("op $op arg $arg: status ${r.status}")
        return r
    }

    fun stat(index: Int) = command(CTL_STATUS, index.toLong()).value

    fun checkFirmware() {
        val id = command(CTL_INFO, 0).value
        if (id != FIRMWARE_ID) throw IOException("unexpected firmware id 0x%08X".format(id))
        if (command(NB_SET_FORMAT, 1, setOf(CTL_OK, CTL_UNKNOWN_OP)).status == CTL_UNKNOWN_OP)
            throw IOException("not the narrowband firmware")
        if (stat(ESP_STAT_RADIO) != 0L) throw IOException("radio initialisation failed on the ESP32-S3")
    }

    /** Programs radio and decimator (order as espdr configure_receiver); returns the LO the ESP settled on [Hz]. */
    fun configure(freqHz: Long, decim: Int, format: Int, gain: Int): Long {
        command(ESP_SET_RATE, ESP_RATE_16M.toLong())
        command(ESP_SET_WIDTH, 20)
        val lo = command(ESP_SET_LO, freqHz).value
        command(ESP_SET_GAIN, gain.toLong())
        command(NB_SET_DECIM, decim.toLong())
        command(NB_SET_FORMAT, format.toLong())
        command(NB_SET_OUTSHIFT, if (format == FMT_CS8) 4L else 0L)
        return lo
    }

    fun resetStats() = synchronized(this) {
        stats.packets = 0; stats.samples = 0; stats.lost = 0; stats.dropped = 0; stats.skipped = 0; stats.bad = 0
        stats.bytes = 0; stats.benchBlocks = 0; stats.benchBad = 0
        expected = -1; benchCounter = -1
    }

    // ---- stream parser (reader thread)

    private fun u16(i: Int) = (buf[i].toInt() and 0xFF) or ((buf[i + 1].toInt() and 0xFF) shl 8)
    private fun u32(i: Int) = (u16(i).toLong()) or (u16(i + 2).toLong() shl 16)

    private fun feed(b: ByteArray, n: Int) = synchronized(this) {
        stats.bytes += n
        if (end + n > buf.size) {
            System.arraycopy(buf, start, buf, 0, end - start)
            end -= start; start = 0
            if (end + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, end + n))
        }
        System.arraycopy(b, 0, buf, end, n)
        end += n
        parse()
    }

    private fun parse() {
        while (end - start > 0) {
            val b0 = buf[start].toInt() and 0xFF
            if (b0 == RESP_MAGIC) {
                if (end - start < 16) return
                val crc = CRC32().apply { update(buf, start, 12) }.value
                if (u32(start + 12) == crc) {
                    responses.add(Response(buf[start + 2].toInt() and 0xFF, buf[start + 3].toInt() and 0xFF, u32(start + 8)))
                    start += 16
                    continue
                }
            } else if (b0 == 0xE5) {
                if (end - start < NB_HEADER) return
                var sum = 0
                for (k in 0 until 10) sum += u16(start + 2 * k)
                if ((buf[start + 1].toInt() and 0xFF) == 0x5D && (sum and 0xFFFF) == 0xFFFF) {
                    val fmt = u16(start + 2) and 0xFF
                    val count = u16(start + 4)
                    val size = NB_HEADER + count * (BYTES_PER_SAMPLE[fmt] ?: 4)
                    if (end - start < size) return
                    val first = u32(start + 12)
                    if (expected >= 0 && first != expected) stats.lost += (first - expected) and 0xFFFFFFFFL
                    expected = (first + count) and 0xFFFFFFFFL
                    stats.packets++; stats.samples += count.toLong(); stats.dropped = u16(start + 6)
                    onPacket?.invoke(Packet(fmt, count, u16(start + 6), u32(start + 8), first, u16(start + 16), buf, start, size))
                    start += size
                    continue
                }
                stats.bad++
            } else if (bench && b0 == 0xBE) {
                if (end - start < 64) return
                if ((buf[start + 1].toInt() and 0xFF) == 0xEF) {
                    val c = u16(start + 2)
                    if (benchCounter >= 0 && c != ((benchCounter + 1) and 0xFFFF)) stats.benchBad++
                    benchCounter = c
                    stats.benchBlocks++
                    start += 64
                    continue
                }
                stats.benchBad++
            }
            start++
            stats.skipped++
        }
    }

    companion object {
        const val REQ_MAGIC = 0xB4; const val RESP_MAGIC = 0xB5
        const val CTL_INFO = 1; const val CTL_STATUS = 3
        const val ESP_RUN = 17; const val ESP_STOP = 18; const val ESP_ARG_HIGH = 19
        const val ESP_SET_LO = 20; const val ESP_SET_RATE = 21; const val ESP_SET_WIDTH = 22; const val ESP_SET_GAIN = 24
        const val ESP_RATE_16M = 1
        const val NB_SET_DECIM = 29; const val NB_SET_FORMAT = 30; const val NB_SET_OUTSHIFT = 31; const val NB_BENCH = 32
        const val ESP_STAT_SERVICE_MAX0 = 11; const val ESP_STAT_SERVICE_MAX1 = 12; const val ESP_STAT_RADIO = 13
        const val NB_STAT_DROPPED = 32; const val NB_STAT_FIFO_PEAK = 33
        const val CTL_OK = 0; const val CTL_UNKNOWN_OP = 1
        const val FIRMWARE_ID = 0x49515306L
        const val NB_HEADER = 20
        const val FMT_CS16 = 0; const val FMT_CS8 = 1; const val FMT_CS4 = 2
        val BYTES_PER_SAMPLE = mapOf(FMT_CS16 to 4, FMT_CS8 to 2, FMT_CS4 to 1)
        /** espdr gain selector from dB (rx_dvbs2.py) */
        fun gainSelector(db: Double) = (30 + db * 50 / 49.6).toInt().coerceIn(0, 127)
    }
}
