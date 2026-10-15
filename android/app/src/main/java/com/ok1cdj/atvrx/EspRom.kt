package com.ok1cdj.atvrx

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.Deflater

/**
 * The ESP32-C3 / ESP32-S3 ROM serial loader over the chips' USB-Serial/JTAG (an open [UsbCdc] after [enterLoader]),
 * written from Espressif's protocol description (esptool docs, "Serial Protocol"): SLIP frames; a request is
 * 00 cmd size(2) checksum(4) data, a response 01 cmd size(2) value(4) data, the ROM's status in the data's last four
 * bytes (status, error, 0, 0); *_DATA checksums are 0xEF XOR the payload. Compressed writes (FLASH_DEFL_*) in 1 KB
 * blocks (the ROM loader's block size), checked with SPI_FLASH_MD5 (the ROM answers 32 hex digits).
 */
class EspRom(private val usb: UsbCdc, private val log: (String) -> Unit) {
    private val rx = ByteArray(16384)
    private var rxLen = 0
    private var rxPos = 0

    // ---- SLIP

    private fun send(cmd: Int, data: ByteArray, checksum: Int = 0) {
        val p = ByteArray(8 + data.size)
        p[0] = 0; p[1] = cmd.toByte()
        p[2] = data.size.toByte(); p[3] = (data.size shr 8).toByte()
        for (i in 0 until 4) p[4 + i] = (checksum shr (8 * i)).toByte()
        System.arraycopy(data, 0, p, 8, data.size)
        val o = ByteArrayOutputStream(p.size + 16)
        o.write(0xC0)
        for (b in p) when (b.toInt() and 0xFF) {
            0xC0 -> { o.write(0xDB); o.write(0xDC) }
            0xDB -> { o.write(0xDB); o.write(0xDD) }
            else -> o.write(b.toInt())
        }
        o.write(0xC0)
        val bytes = o.toByteArray()
        var off = 0
        while (off < bytes.size) {                       // USB writes in pieces of at most 16 KB
            val n = minOf(16384, bytes.size - off)
            usb.write(bytes.copyOfRange(off, off + n))
            off += n
        }
    }

    private fun readByte(deadline: Long): Int {
        while (rxPos >= rxLen) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return -1
            rxLen = usb.read(rx, minOf(left, 200).toInt()); rxPos = 0
        }
        return rx[rxPos++].toInt() and 0xFF
    }

    /** The next SLIP frame, or null at the deadline. */
    private fun frame(deadline: Long): ByteArray? {
        var c: Int
        do { c = readByte(deadline); if (c < 0) return null } while (c != 0xC0)
        val o = ByteArrayOutputStream()
        while (true) {
            c = readByte(deadline); if (c < 0) return null
            when (c) {
                0xC0 -> if (o.size() == 0) continue else return o.toByteArray()
                0xDB -> when (readByte(deadline)) { 0xDC -> o.write(0xC0); 0xDD -> o.write(0xDB); else -> return null }
                else -> o.write(c)
            }
        }
    }

    class Response(val value: Int, val data: ByteArray)

    /** A command and its response (other frames, e.g. the rest of SYNC's answers, are skipped). */
    fun command(cmd: Int, data: ByteArray, checksum: Int = 0, timeoutMs: Long = 3000): Response {
        send(cmd, data, checksum)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val f = frame(deadline) ?: throw IOException("no answer to command 0x%02x".format(cmd))
            if (f.size < 8 || f[0].toInt() != 1 || (f[1].toInt() and 0xFF) != cmd) continue
            val size = (f[2].toInt() and 0xFF) or ((f[3].toInt() and 0xFF) shl 8)
            val value = le32(f, 4)
            val d = f.copyOfRange(8, minOf(f.size, 8 + size))
            if (d.size >= 4 && d[d.size - 4].toInt() != 0)
                throw IOException("command 0x%02x failed: error 0x%02x".format(cmd, d[d.size - 3].toInt() and 0xFF))
            return Response(value, if (d.size >= 4) d.copyOf(d.size - 4) else d)
        }
    }

    fun sync() {
        val d = byteArrayOf(0x07, 0x07, 0x12, 0x20) + ByteArray(32) { 0x55 }
        repeat(8) {
            try { command(SYNC, d, timeoutMs = 300); drain(); return } catch (_: IOException) {}
        }
        throw IOException("the ROM loader does not answer SYNC")
    }

    private fun drain() {                                // SYNC gets several answers
        val end = System.currentTimeMillis() + 200
        while (frame(end) != null) {}
    }

    /** GET_SECURITY_INFO's chip id: 5 ESP32-C3, 9 ESP32-S3. */
    fun chipId(): Int {
        val r = command(GET_SECURITY_INFO, ByteArray(0))
        if (r.data.size < 16) throw IOException("short security info")
        return le32(r.data, 12)
    }

    fun attachFlash() { command(SPI_ATTACH, ByteArray(8)) }

    /**
     * Writes [image] at [offset], compressed; [progress] gets (bytes done, total) of the image. Then checks it with
     * SPI_FLASH_MD5.
     */
    fun flash(image: ByteArray, offset: Int, progress: (Int, Int) -> Unit) {
        val def = Deflater(9)
        def.setInput(image); def.finish()
        val z = ByteArrayOutputStream()
        val buf = ByteArray(65536)
        while (!def.finished()) z.write(buf, 0, def.deflate(buf))
        def.end()
        val comp = z.toByteArray()
        val blocks = (comp.size + BLOCK - 1) / BLOCK
        // the ROM erases the whole region in FLASH_DEFL_BEGIN: about 30 s per MB at worst
        val eraseMs = maxOf(3000L, image.size.toLong() * 30_000L / 1_048_576L)
        command(FLASH_DEFL_BEGIN, words(image.size, blocks, BLOCK, offset, 0), timeoutMs = eraseMs)
        for (seq in 0 until blocks) {
            val part = comp.copyOfRange(seq * BLOCK, minOf(comp.size, (seq + 1) * BLOCK))
            var ck = 0xEF
            for (b in part) ck = ck xor (b.toInt() and 0xFF)
            command(FLASH_DEFL_DATA, words(part.size, seq, 0, 0) + part, ck, timeoutMs = 10_000)
            progress(minOf(image.size, ((seq + 1).toLong() * image.size / blocks).toInt()), image.size)
        }
        val md5 = command(SPI_FLASH_MD5, words(offset, image.size, 0, 0), timeoutMs = 10_000L + image.size / 100).data
        val got = String(md5.copyOf(minOf(32, md5.size)), Charsets.US_ASCII).lowercase()
        val want = MessageDigest.getInstance("MD5").digest(image).joinToString("") { "%02x".format(it) }
        if (got != want) throw IOException("MD5 at 0x%x: flash %s, image %s".format(offset, got, want))
    }

    /** FLASH_DEFL_END staying in the loader; the caller then resets the chip into its firmware. */
    fun finish() { command(FLASH_DEFL_END, words(1)) }

    private fun words(vararg w: Int): ByteArray {
        val b = ByteArray(4 * w.size)
        w.forEachIndexed { i, v -> for (k in 0 until 4) b[4 * i + k] = (v shr (8 * k)).toByte() }
        return b
    }

    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    companion object {
        const val SYNC = 0x08; const val SPI_ATTACH = 0x0D; const val GET_SECURITY_INFO = 0x14
        const val FLASH_DEFL_BEGIN = 0x10; const val FLASH_DEFL_DATA = 0x11; const val FLASH_DEFL_END = 0x12
        const val SPI_FLASH_MD5 = 0x13
        const val BLOCK = 0x400                            // the ROM loader's write block
        const val CHIP_C3 = 5; const val CHIP_S3 = 9

        /**
         * Into the ROM loader through USB-Serial/JTAG's line states (DTR = GPIO9 boot strap, RTS = reset), the sequence
         * Espressif documents for these chips: idle, boot strap low, reset through (1, 1), release reset. The chip then
         * enumerates again (as the same 303a:1001).
         */
        fun enterLoader(usb: UsbCdc) {
            usb.setLines(dtr = false, rts = false); Thread.sleep(100)
            usb.setLines(dtr = true, rts = false); Thread.sleep(100)
            usb.setLines(dtr = true, rts = true)
            usb.setLines(dtr = false, rts = true); Thread.sleep(100)
            usb.setLines(dtr = false, rts = false)
        }

        /** Out of the loader into the flashed firmware: a reset pulse on RTS with the boot strap released. */
        fun hardReset(usb: UsbCdc) {
            usb.setLines(dtr = false, rts = true); Thread.sleep(100)
            usb.setLines(dtr = false, rts = false)
        }
    }
}
