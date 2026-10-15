package com.ok1cdj.atvrx

/**
 * DVB-S2 encoder (android/native/dvbs2enc, JNI library libdvbs2enc.so) in its continuous framing: TS packets go in one
 * at a time, PLFRAMEs come out as phase indices k (symbol = e^(j pi k / 4)).
 */
class S2Encoder(val const: String, val rate: String, val shortFrame: Boolean, val pilots: Boolean) : AutoCloseable {
    private var h = nativeNew(if (const == "8PSK") 1 else 0, rate, shortFrame, pilots)
    val frameSymbols = nativeFrameSymbols(h)
    val modcod = nativeModcod(h)
    /** data field bits per frame: packets run across frames, so the TS rate is baud x dfl / frameSymbols */
    val dfl = nativeKbch(h) - 80

    fun tsRate(baud: Double) = baud * dfl / frameSymbols

    /** 1: taken, 0: buffer full (take a frame first), -1: no 0x47 sync byte */
    fun push(packet: ByteArray, off: Int = 0) = nativePush(h, packet, off)
    fun ready() = nativeReady(h)
    /** the next PLFRAME into out (at least frameSymbols bytes); its symbol count, 0 when not ready */
    fun frame(out: ByteArray) = nativeStreamFrame(h, out)

    override fun close() {
        if (h != 0L) nativeFree(h)
        h = 0
    }

    companion object {
        init { System.loadLibrary("dvbs2enc") }
        @JvmStatic private external fun nativeNew(constellation: Int, rate: String, shortFrame: Boolean, pilots: Boolean): Long
        @JvmStatic private external fun nativeFree(h: Long)
        @JvmStatic private external fun nativeFrameSymbols(h: Long): Int
        @JvmStatic private external fun nativeKbch(h: Long): Int
        @JvmStatic private external fun nativeModcod(h: Long): Int
        @JvmStatic private external fun nativeReady(h: Long): Boolean
        @JvmStatic private external fun nativePush(h: Long, buf: ByteArray, off: Int): Int
        @JvmStatic private external fun nativeStreamFrame(h: Long, out: ByteArray): Int

        val QPSK_RATES = listOf("1/4", "1/3", "2/5", "1/2", "3/5", "2/3", "3/4", "4/5", "5/6", "8/9", "9/10")
        val PSK8_RATES = listOf("3/5", "2/3", "3/4", "5/6", "8/9", "9/10")
    }
}
