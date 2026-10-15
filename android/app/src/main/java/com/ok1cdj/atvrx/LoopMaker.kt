package com.ok1cdj.atvrx

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * "Save as beacon": records [seconds] of the TX picture (test card or camera, the same chain as live TX: Canvas -> H.264
 * -> TS) in real time, encodes it as a stand-alone loop - QPSK, whole TS packets per BBFRAME so that the loop joins up,
 * the first packet's CRC slot from the last one - and stores it in the C3's "video" partition (VIDW). The header
 * (DATVVID1 version 3, ESP32-ATV's fork) carries symbol rate, MODCOD, frame size, pilots, service, frequency and level:
 * the C3 then transmits it by itself after every power-up, without the phone.
 */
class LoopMaker(private val ctx: Context, private val usbManager: UsbManager, private val device: UsbDevice,
                private val cfg: Transmitter.Config, private val seconds: Int, private val log: (String) -> Unit) {
    @Volatile var painter: Painter? = null; private set

    /** Runs on the caller's thread (minutes at most); throws with the reason on failure. */
    fun run() {
        if (cfg.const != "QPSK") throw IOException("the stand-alone loop is QPSK only")
        val sper = Math.round(C3Link.CPU_HZ.toDouble() / cfg.baud)
        if ((sper - 4) / 20 < 16) throw IOException("symbol rate too high for a stand-alone loop (about 333 kS/s at most)")
        val baud = C3Link.CPU_HZ.toDouble() / sper                      // what the C3 sends
        val enc = S2Encoder("QPSK", cfg.rate, cfg.shortFrame, cfg.pilots)
        try {
            val fs = enc.frameSymbols
            val ppf = enc.packetsPerFrame
            val tsRate = ppf * 1504.0 * baud / fs
            val nframes = (seconds * baud / fs).toInt() / 2 * 2             // the firmware wants an even number of frames
            if (nframes < 2) throw IOException("too short")
            val npk = nframes * ppf
            val nsym = nframes * fs
            if (HEADER + nsym / 4 + TAIL > PARTITION) throw IOException("too long for the flash: fewer seconds")
            log("loop: %.1f Bd, %d frames of %d symbols, %d packets, TS %.1f kb/s, %.1f s".format(baud, nframes, fs, ppf,
                tsRate / 1e3, nsym / baud))

            // the TS, recorded in real time (the mux clock must follow the picture's)
            val ts = ByteArray(188 * npk)
            val m = TsMux(tsRate, cfg.service, cfg.provider)
            val videoBps = if (cfg.videoKbps > 0) minOf(cfg.videoKbps * 1000, (tsRate * 0.9).toInt()) else (tsRate * 0.70).toInt()
            val (w, h) = if (videoBps < 80_000) 320 to 180 else if (videoBps < 200_000) 480 to 270 else 640 to 360
            val fps = if (cfg.fps > 0) cfg.fps else if (videoBps < 80_000) 5 else if (videoBps < 200_000) 10 else 15
            val mode = "DVB-S2 QPSK ${cfg.rate}${if (cfg.shortFrame) " short" else ""}  %.1f kS/s".format(java.util.Locale.ROOT, baud / 1e3)
            var camera: CameraSource? = null
            val p: Painter = if (cfg.source == Transmitter.SOURCE_CARD)
                TestCard(cfg.call, "ESP32-ATV beacon  %.3f MHz".format(java.util.Locale.ROOT, cfg.freqKhz / 1000.0), mode)
            else CameraSource(ctx, cfg.source == Transmitter.SOURCE_BACK, if (w > 320) 640 else 320, cfg.call,
                "%.3f MHz  %s".format(java.util.Locale.ROOT, cfg.freqKhz / 1000.0, mode)).also { camera = it; it.start() }
            painter = p
            val video = CanvasEncoder(w, h, fps, videoBps, 2, p) { au, key -> m.addAu(au, key) }
            video.start()
            log("recording ${w}x$h $fps fps ${videoBps / 1000} kb/s for %.1f s…".format(npk * 1504.0 / tsRate))
            try {
                val t0 = System.nanoTime()
                for (i in 0 until npk) {
                    val due = t0 + (i * 1504.0 / tsRate * 1e9).toLong()
                    val wait = due - System.nanoTime()
                    if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                    m.next(ts, 188 * i)
                    video.error?.let { throw IOException(it) }
                    camera?.error?.let { throw IOException(it) }
                }
            } finally {
                video.stop()
                camera?.stop()
                painter = null
            }
            log("recorded: video %.0f kb/s, %d frames dropped".format(m.videoBytes * 8 / m.time / 1e3, m.droppedAus))

            // symbols: 4 per byte, first in the low bits (bit 0 = I > 0, bit 1 = Q > 0); then the first 64 bytes again
            enc.setPrevCrc(ts, 188 * (npk - 1))
            val sym = ByteArray(fs)
            val data = ByteArray(nsym / 4 + TAIL)
            var o = 0; var acc = 0; var nacc = 0
            for (f in 0 until nframes) {
                if (enc.frameWhole(ts, 188 * f * ppf, sym) < 0) throw IOException("lost TS sync")
                for (k in 0 until fs) {
                    acc = acc or (QCODE[sym[k].toInt() and 7] shl (2 * nacc))
                    if (++nacc == 4) { data[o++] = acc.toByte(); acc = 0; nacc = 0 }
                }
            }
            System.arraycopy(data, 0, data, nsym / 4, TAIL)
            val image = header(nsym, Math.round(baud).toInt(), data, MODCODS.indexOf(cfg.rate) + 1) + data
            log("loop: ${image.size} bytes (%d %% of the partition)".format(100L * image.size / PARTITION))

            // into the C3
            val conn = usbManager.openDevice(device) ?: throw IOException("openDevice failed")
            val link = C3Link(UsbCdc(conn, device))
            try {
                Thread.sleep(1200)
                link.info() ?: throw IOException("no ESP32-DATV answer: is this the C3?")
                if (link.vidw == null) throw IOException("this C3 firmware cannot store a loop: write the current one (menu → Firmware)")
                var last = -1
                val line = link.storeLoop(image) { done, total ->
                    val pct = done * 100 / total
                    if (pct / 20 != last) { last = pct / 20; log("storing $pct %") }
                }
                log(line)
                log("stored: the C3 now transmits it by itself, also after power-up (unplug it from the phone)")
            } finally {
                link.close()
            }
        } finally {
            enc.close()
        }
    }

    private fun header(nsym: Int, baud: Int, data: ByteArray, modcod: Int): ByteArray {
        val b = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
        b.put("DATVVID1".toByteArray())
        b.putInt(3); b.putInt(nsym); b.putInt(baud); b.putInt(data.size)
        b.putInt(java.util.zip.CRC32().apply { update(data) }.value.toInt())
        b.put(if (cfg.pilots) 1 else 0); b.put(modcod.toByte()); b.put(if (cfg.shortFrame) 1 else 0); b.put(0)
        b.put(cfg.service.toByteArray(Charsets.ISO_8859_1).copyOf(32).also { it[31] = 0 })
        b.put(cfg.provider.toByteArray(Charsets.ISO_8859_1).copyOf(32).also { it[31] = 0 })
        b.putInt(cfg.freqKhz.toInt()); b.putShort(cfg.amp.toShort()); b.putShort(0)
        return b.array()
    }

    companion object {
        private const val HEADER = 4096
        private const val TAIL = 64
        private const val PARTITION = 0x2F0000
        private val QCODE = intArrayOf(0, 3, 0, 2, 0, 0, 0, 1)
        private val MODCODS = S2Encoder.QPSK_RATES
    }
}
