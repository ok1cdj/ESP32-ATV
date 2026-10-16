package com.ok1cdj.atvrx

import java.io.IOException

/**
 * The XIAO ESP32-C3 transmitter (ESP32-DATV(-SA) firmware) in its PC streaming mode, over [UsbCdc]: text commands go
 * down; after "QPSKT ..." the raw symbol stream (QPSK: 4 symbols per byte, first in the low bits, bit 0 = I > 0,
 * bit 1 = Q > 0); the firmware answers with text lines and 4-byte fill reports B7 fill_lo fill_hi underruns (fill in
 * pairs of bytes) interleaved with them. The transmission stops after 0.5 s without data, or on A5 FF.
 * A command from USB also stops the stand-alone beacon.
 */
class C3Link(private val usb: UsbCdc) : AutoCloseable {
    @Volatile var fill = 0; private set          // symbol buffer fill at the last report [pairs of bytes]
    @Volatile var underruns = 0; private set     // the firmware's counter (8 bit)
    @Volatile var reports = 0L; private set
    /** pairs of bytes sent since the last fill report (the firmware has not counted them yet) */
    @Volatile var sentSince = 0L
    @Volatile var error: Throwable? = null; private set
    private val text = StringBuilder()
    private val lock = Object()
    @Volatile private var running = true
    private var pend = ByteArray(0)

    private val reader = Thread({
        try {
            usb.readLoop({ running }, depth = 4, size = 4096) { b, n -> feed(b, n) }
        } catch (t: Throwable) {
            if (running) error = t
        }
    }, "c3-reader").apply { start() }

    private fun feed(b: ByteArray, n: Int) {
        val d = if (pend.isEmpty()) b.copyOf(n) else pend + b.copyOf(n)
        var i = 0
        synchronized(lock) {
            while (i < d.size) {
                val c = d[i].toInt() and 0xFF
                if (c == 0xB7) {
                    if (i + 4 > d.size) break
                    fill = (d[i + 1].toInt() and 0xFF) or ((d[i + 2].toInt() and 0xFF) shl 8)
                    underruns = d[i + 3].toInt() and 0xFF
                    sentSince = 0
                    reports++
                    i += 4
                } else {
                    text.append(c.toChar())
                    i++
                }
            }
            if (text.length > 16384) text.delete(0, text.length - 8192)
            (lock as Object).notifyAll()
        }
        pend = d.copyOfRange(i, d.size)
    }

    private val ahead = ArrayDeque<String>()                          // lines read but not looked at yet

    /** Text lines received so far (and clears them). */
    fun takeLines(): List<String> = synchronized(lock) {
        val s = text.toString()
        val cut = s.lastIndexOf('\n') + 1
        text.delete(0, cut)
        val l = ahead.toList() + s.substring(0, cut).split('\n').map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
        ahead.clear()
        l
    }

    /**
     * Waits until a line satisfies [match] (returns it) or [timeoutMs] passes (null). Lines before it go to [lines];
     * lines after it in the same batch stay for the next call (INFO answers several lines at once).
     */
    fun waitLine(timeoutMs: Long, match: (String) -> Boolean): String? {
        val end = System.currentTimeMillis() + timeoutMs
        val seen = ArrayList<String>()
        try {
            while (true) {
                val batch = takeLines()
                for ((i, l) in batch.withIndex()) {
                    if (match(l)) {
                        synchronized(lock) { batch.subList(i + 1, batch.size).asReversed().forEach { ahead.addFirst(it) } }
                        return l
                    }
                    seen += l
                }
                val left = end - System.currentTimeMillis()
                if (left <= 0) return null
                synchronized(lock) { (lock as Object).wait(minOf(left, 50)) }
            }
        } finally {
            lines += seen
        }
    }

    /** after info(): the firmware's VIDW line ("VIDW 1 VIDEO ..."; null: no VIDW command) and FW line */
    @Volatile var vidw: String? = null; private set
    @Volatile var fw: String? = null; private set

    /** lines not consumed by waitLine, for the log */
    val lines = java.util.Collections.synchronizedList(ArrayList<String>())

    fun command(s: String) = usb.write((s + "\n").toByteArray())

    /** INFO: the firmware's identity line ("ESP32DATV 1 PA_GPIO 3") and its beacon line; null if this is not a C3. */
    fun info(): Pair<String, String?>? {
        command("")
        command("INFO")
        val id = waitLine(1500) { it.startsWith("ESP32DATV") } ?: return null
        val beacon = waitLine(300) { it.startsWith("BEACON ") }
        vidw = waitLine(300) { it.startsWith("VIDW ") }
        fw = lines.lastOrNull { it.startsWith("FW ") }
        return id to beacon
    }

    /**
     * The DC correction the firmware holds, in DAC codes: for [mhz] from its table ("BDC GET f": "BDC i q (src) F f_kHz TEST t"),
     * or the one it uses now ("BDC"; older firmware: "BDC i q (saved|built-in)", and no GET).
     */
    fun dc(mhz: Double? = null): Pair<Double, Double>? {
        command(if (mhz != null) "BDC GET %.3f".format(java.util.Locale.ROOT, mhz) else "BDC")
        val l = waitLine(1000) { it.startsWith("BDC ") || it.startsWith("ERR") } ?: return null
        if (l.startsWith("ERR")) return if (mhz != null) dc() else null
        val p = l.split(' ')
        return p.getOrNull(1)?.toDoubleOrNull()?.let { i -> p.getOrNull(2)?.toDoubleOrNull()?.let { q -> i to q } }
    }

    /** The DC table (BDC LIST: "BDCT n", then "BDCT f_kHz i q"): f [MHz] to (i, q); null if the firmware has none. */
    fun dcTable(): List<Triple<Double, Double, Double>>? {
        command("BDC LIST")
        val head = waitLine(1000) { it.startsWith("BDCT ") || it.startsWith("ERR") } ?: return null
        if (head.startsWith("ERR")) return null
        val n = head.split(' ').getOrNull(1)?.trim()?.toIntOrNull() ?: return null
        return (0 until n).mapNotNull {
            val p = waitLine(500) { l -> l.startsWith("BDCT ") }?.trim()?.split(' ') ?: return@mapNotNull null
            val f = p.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
            Triple(f / 1000, p.getOrNull(2)?.toDoubleOrNull() ?: 0.0, p.getOrNull(3)?.toDoubleOrNull() ?: 0.0)
        }
    }

    /** BDC PUT: an entry of the table without transmitting; false if the firmware refuses it (or has no PUT). */
    fun dcPut(mhz: Double, i: Double, q: Double): Boolean {
        command("BDC PUT %.3f %.3f %.3f".format(java.util.Locale.ROOT, mhz, i, q))
        val l = waitLine(1000) { it.startsWith("BDC ") || it.startsWith("ERR") } ?: return false
        return l.startsWith("BDC ")
    }

    /** PA n: the external PA enable output; the firmware answers "OK PA n GPIO g" (older firmware: nothing). */
    fun pa(on: Boolean) {
        command("PA ${if (on) 1 else 0}")
        waitLine(1000) { it.startsWith("OK PA") || it.startsWith("ERR") }
    }

    /** Starts a transmission: returns the firmware's "OK QPSKT ..." line, throws on ERR or silence. */
    fun start(cmd: String): String {
        command(cmd)
        val l = waitLine(8000) { it.startsWith("OK QPSKT") || it.startsWith("ERR") } ?: throw IOException("no answer from the C3")
        if (l.startsWith("ERR")) throw IOException("C3: $l")
        sentSince = 0
        return l
    }

    fun send(b: ByteArray, n: Int) = usb.write(if (n == b.size) b else b.copyOf(n))

    /**
     * Stores a stand-alone loop in the "video" partition with ESP32-ATV's VIDW command (fork of ESP32-DATV-SA): BEGIN
     * (the firmware erases what it needs), DATA blocks of 4 KB with a CRC32 each, END (CRC32 of everything, the loop
     * checked and started). Returns the firmware's VIDEO line; throws when the firmware has no VIDW or a step fails.
     */
    fun storeLoop(data: ByteArray, progress: (Int, Int) -> Unit): String {
        fun crc(b: ByteArray, o: Int, n: Int) = java.util.zip.CRC32().apply { update(b, o, n) }.value
        command("VIDW BEGIN %d %08x".format(data.size, crc(data, 0, data.size)))
        val begin = waitLine(60_000) { it.startsWith("OK VIDW BEGIN") || it.startsWith("ERR") } ?: throw IOException("no answer to VIDW BEGIN")
        if (!begin.startsWith("OK")) throw IOException("C3: $begin")
        var off = 0
        while (off < data.size) {
            val n = minOf(4096, data.size - off)
            usb.write("VIDW DATA %d %d %08x\n".format(off, n, crc(data, off, n)).toByteArray() + data.copyOfRange(off, off + n))
            val r = waitLine(5000) { it.startsWith("OK VIDW DATA $off") || it.startsWith("ERR") } ?: throw IOException("no answer at $off")
            if (!r.startsWith("OK")) throw IOException("C3: $r")
            off += n
            progress(off, data.size)
        }
        command("VIDW END")
        var video = ""
        val end = waitLine(30_000) { if (it.startsWith("VIDEO")) video = it; it.contains("VIDW END") || it.startsWith("ERR") }
            ?: throw IOException("no answer to VIDW END")
        if (!end.startsWith("OK")) throw IOException("C3: $end $video")
        return video
    }

    /** Stops the stream (A5 FF) and waits for "TX END ..." */
    fun stop(): String? {
        try { usb.write(byteArrayOf(0xA5.toByte(), 0xFF.toByte())) } catch (_: IOException) {}
        return waitLine(3000) { "TX END" in it }
    }

    override fun close() {
        running = false
        reader.join(1000)
        usb.close()
    }

    companion object {
        const val CPU_HZ = 160_000_000L

        /**
         * Samples per symbol for QPSK, as the firmware's streaming loops need them (the rule of ESP32-DATV's
         * tx_dvbs.py auto_sps): hand-scheduled loops at 20 or 24 CPU cycles per sample with 16..232 samples per
         * symbol when that hits the rate within 1 %, 8 at exactly 1 MBd, otherwise 16 / 8 / 4 at up to 4 MS/s.
         */
        fun qpskSps(baud: Int): Int {
            if (baud == 1_000_000) return 8
            var best: Pair<Double, Int>? = null
            for (period in intArrayOf(20, 24)) {
                val sps = Math.round(CPU_HZ.toDouble() / (period * baud)).toInt()
                if (sps in 16..232) {
                    val err = Math.abs(CPU_HZ.toDouble() / (period * sps) / baud - 1)
                    if (err <= 0.01 && (best == null || err < best.first - 1e-9)) best = err to sps
                }
            }
            best?.let { return it.second }
            return if (baud * 16L <= 4_000_000) 16 else if (baud * 8L <= 4_000_000) 8 else 4
        }

        private const val P8_MIN_PERIOD = 75L

        /**
         * Samples per symbol for 8PSK (tx_dvbs.py auto_sps_8psk): the 8 MS/s loop with 16..64 samples per symbol when
         * that hits the rate within 1 % (125..500 kS/s), below that a C loop with at least 75 cycles per DAC sample.
         */
        fun psk8Sps(baud: Int): Int {
            val sps = Math.round(CPU_HZ.toDouble() / (20 * baud)).toInt()
            if (sps in 16..64 && Math.abs(CPU_HZ.toDouble() / (20 * sps) / baud - 1) <= 0.01) return sps
            for (s in 64 downTo 16) if (CPU_HZ / (baud.toLong() * s) >= P8_MIN_PERIOD) return s
            throw IllegalArgumentException("8PSK: symbol rate too high for the C3")
        }

        /** The symbol rate the firmware sends for 8PSK at that sps (tx_dvbs.py output_baud). */
        fun psk8Baud(baud: Int, sps: Int): Double {
            val sampleHz = baud.toLong() * sps
            val period = (CPU_HZ + sampleHz / 2) / sampleHz
            if (period != 20L && sps in 16..64 && CPU_HZ / sampleHz >= P8_MIN_PERIOD) return baud.toDouble()
            if (sps in 16..64 && period == 20L && CPU_HZ / baud == period * sps) return baud.toDouble()
            return CPU_HZ.toDouble() / (period * sps)
        }

        /** The symbol rate the firmware actually sends for QPSK at that sps (its clock is 160 MHz / whole cycles). */
        fun qpskBaud(baud: Int, sps: Int): Double {
            val sampleHz = baud.toLong() * sps
            val period = (CPU_HZ + sampleHz / 2) / sampleHz
            val generic = sps in 16..232 && (period == 20L || period == 24L)
            if (generic && CPU_HZ / baud == period * sps) return baud.toDouble()
            return CPU_HZ.toDouble() / (period * sps)
        }
    }
}
