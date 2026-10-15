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

    /** Text lines received so far (and clears them). */
    fun takeLines(): List<String> = synchronized(lock) {
        val s = text.toString()
        val cut = s.lastIndexOf('\n') + 1
        text.delete(0, cut)
        s.substring(0, cut).split('\n').map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
    }

    /** Waits until a line satisfies [match] (returns it) or [timeoutMs] passes (null); keeps the other lines. */
    fun waitLine(timeoutMs: Long, match: (String) -> Boolean): String? {
        val end = System.currentTimeMillis() + timeoutMs
        val seen = ArrayList<String>()
        try {
            while (true) {
                for (l in takeLines()) {
                    if (match(l)) return l
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

    /** lines not consumed by waitLine, for the log */
    val lines = java.util.Collections.synchronizedList(ArrayList<String>())

    fun command(s: String) = usb.write((s + "\n").toByteArray())

    /** INFO: the firmware's identity line ("ESP32DATV 1 PA_GPIO 3") and its beacon line; null if this is not a C3. */
    fun info(): Pair<String, String?>? {
        command("")
        command("INFO")
        val id = waitLine(1500) { it.startsWith("ESP32DATV") } ?: return null
        val beacon = waitLine(300) { it.startsWith("BEACON ") }
        return id to beacon
    }

    /** The DC correction the firmware holds (BDC: "BDC i q (saved|built-in)"), in DAC codes. */
    fun dc(): Pair<Double, Double>? {
        command("BDC")
        val l = waitLine(1000) { it.startsWith("BDC ") } ?: return null
        val p = l.split(' ')
        return p.getOrNull(1)?.toDoubleOrNull()?.let { i -> p.getOrNull(2)?.toDoubleOrNull()?.let { q -> i to q } }
    }

    /** PA n: the external PA enable output; the firmware answers "OK PA n GPIO g" (older firmware: nothing). */
    fun pa(on: Boolean) {
        command("PA ${if (on) 1 else 0}")
        waitLine(1000) { it.startsWith("OK PA") || it.startsWith("ERR") }
    }

    /** Starts a transmission: returns the firmware's "OK QPSKT ..." line, throws on ERR or silence. */
    fun start(cmd: String): String {
        command(cmd)
        val l = waitLine(8000) { it.startsWith("OK ") || it.startsWith("ERR") } ?: throw IOException("no answer from the C3")
        if (l.startsWith("ERR")) throw IOException("C3: $l")
        sentSince = 0
        return l
    }

    fun send(b: ByteArray, n: Int) = usb.write(if (n == b.size) b else b.copyOf(n))

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
