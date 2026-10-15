package com.ok1cdj.atvrx

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.util.concurrent.locks.LockSupport

/**
 * Live DVB-S2 transmission through the XIAO ESP32-C3 (ESP32-DATV(-SA) firmware, PC streaming mode): test card or
 * camera ([TestCard], [CameraSource]) -> H.264 ([CanvasEncoder]) -> constant-rate TS ([TsMux]) -> DVB-S2 ([S2Encoder], continuous framing) -> packed QPSK symbols
 * -> USB, kept at the firmware's buffer target by its fill reports.
 */
class Transmitter(private val ctx: android.content.Context, private val usbManager: UsbManager, private val device: UsbDevice,
                  val cfg: Config, private val log: (String) -> Unit) {
    /** source: SOURCE_CARD, SOURCE_BACK or SOURCE_FRONT (camera) */
    data class Config(val freqKhz: Long, val baud: Int, val rate: String, val shortFrame: Boolean, val pilots: Boolean,
                      val amp: Int, val call: String, val service: String, val provider: String, val source: String = SOURCE_CARD)

    class Status {
        @Volatile var state = "starting"
        @Volatile var since = 0L
        @Volatile var fill = 0
        @Volatile var underruns = 0
        @Volatile var plframes = 0L
        @Volatile var bytes = 0L
        @Volatile var tsRate = 0.0
        @Volatile var baud = 0.0
        @Volatile var videoKbps = 0
        @Volatile var lo = ""
    }

    val status = Status()
    @Volatile private var running = true
    private var thread: Thread? = null
    @Volatile var video: CanvasEncoder? = null; private set
    @Volatile var camera: CameraSource? = null; private set
    @Volatile var mux: TsMux? = null; private set

    fun start() {
        thread = Thread({ run() }, "tx").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(4000)
    }

    private fun run() {
        var link: C3Link? = null
        var enc: S2Encoder? = null
        try {
            val conn = usbManager.openDevice(device) ?: throw java.io.IOException("openDevice failed")
            link = C3Link(UsbCdc(conn, device))
            Thread.sleep(1200)                                  // opening the port may reset the C3
            val (id, beacon) = link.info() ?: throw java.io.IOException("no ESP32-DATV answer: is this the C3? (the S3 receiver is the same 303a:1001)")
            log("C3: $id${beacon?.let { " · $it" } ?: ""}")
            val dc = link.dc() ?: (0.0 to 0.0)
            log("C3 DC correction %.3f %.3f".format(dc.first, dc.second))
            link.pa(false)

            enc = S2Encoder("QPSK", cfg.rate, cfg.shortFrame, cfg.pilots)
            val sps = C3Link.qpskSps(cfg.baud)
            val baud = C3Link.qpskBaud(cfg.baud, sps)
            val tsRate = enc.tsRate(baud)
            status.baud = baud; status.tsRate = tsRate
            // video: what is left after tables, PCR and PES headers, with a margin for the encoder's overshoot
            val videoBps = (tsRate * 0.72).toInt()
            status.videoKbps = videoBps / 1000
            val (w, h, fps) = when {
                cfg.baud < 100_000 -> Triple(320, 180, 5)
                cfg.baud < 250_000 -> Triple(480, 270, 10)
                else -> Triple(640, 360, 15)
            }
            val m = TsMux(tsRate, cfg.service, cfg.provider)
            mux = m
            val mode = "DVB-S2 QPSK ${cfg.rate}${if (cfg.shortFrame) " short" else ""}  %.1f kS/s".format(java.util.Locale.ROOT, cfg.baud / 1000.0)
            val painter: Painter = if (cfg.source == SOURCE_CARD)
                TestCard(cfg.call, "ESP32-ATV TX  %.3f MHz".format(java.util.Locale.ROOT, cfg.freqKhz / 1000.0), mode)
            else CameraSource(ctx, cfg.source == SOURCE_BACK, if (w > 320) 640 else 320, cfg.call,
                "%.3f MHz  %s".format(java.util.Locale.ROOT, cfg.freqKhz / 1000.0, mode)).also {
                camera = it
                it.start()
            }
            val c = CanvasEncoder(w, h, fps, videoBps, if (cfg.baud < 100_000) 4 else 2, painter) { au, key -> m.addAu(au, key) }
            video = c
            c.start()
            log("video ${c.encoderName}: ${w}x$h $fps fps, ${videoBps / 1000} kb/s; TS %.1f kb/s".format(tsRate / 1000))
            camera?.let { log("camera ${if (cfg.source == SOURCE_BACK) "back" else "front"} ${it.size}") }

            val target = 3000
            val cmd = "QPSKT %.3f %d %d %d %d %d %d %d %d %d %d".format(java.util.Locale.ROOT, cfg.freqKhz / 1000.0, cfg.baud, sps,
                cfg.amp, 86400, 0, target, Math.round(16 * dc.first), Math.round(16 * dc.second), 10000, 0)
            log("> $cmd")
            val ok = link.start(cmd)
            log(ok)
            status.lo = Regex("LO (\\S+)").find(ok)?.groupValues?.get(1) ?: ""
            status.state = "transmitting"
            status.since = System.nanoTime()

            // symbols -> 4 per byte (first in the low bits; bit 0 = I > 0, bit 1 = Q > 0), across frame boundaries
            val sym = ByteArray(enc.frameSymbols)
            val pkt = ByteArray(188)
            var out = ByteArray(65536)
            var outLen = 0
            var acc = 0; var nacc = 0
            fun produceFrame() {
                while (!enc.ready()) { m.next(pkt); enc.push(pkt) }
                val n = enc.frame(sym)
                if (outLen + n / 4 + 2 > out.size) out = out.copyOf(out.size * 2)
                for (i in 0 until n) {
                    acc = acc or (QCODE[sym[i].toInt() and 7] shl (2 * nacc))
                    if (++nacc == 4) { out[outLen++] = acc.toByte(); acc = 0; nacc = 0 }
                }
                status.plframes++
            }
            val chunk = ByteArray(2048)
            while (running) {
                link.error?.let { throw it }
                status.fill = link.fill; status.underruns = link.underruns
                c.error?.let { throw java.io.IOException(it) }
                camera?.error?.let { throw java.io.IOException(it) }
                val todo = target - (link.fill + link.sentSince)          // pairs of bytes
                if (todo >= 32) {
                    val nb = 2 * minOf(todo.toInt(), chunk.size / 2)
                    while (outLen < nb) produceFrame()
                    System.arraycopy(out, 0, chunk, 0, nb)
                    System.arraycopy(out, nb, out, 0, outLen - nb)
                    outLen -= nb
                    link.send(chunk, nb)
                    link.sentSince += nb / 2
                    status.bytes += nb
                } else {
                    LockSupport.parkNanos(500_000)
                }
            }
            status.state = "stopping"
            link.stop()?.let { log(it) }
            status.state = "stopped"
        } catch (t: Throwable) {
            status.state = "error: ${t.message}"
            log(status.state)
            try { link?.stop() } catch (_: Throwable) {}
        } finally {
            video?.stop()
            camera?.stop()
            enc?.close()
            link?.close()
        }
    }

    companion object {
        const val SOURCE_CARD = "test card"
        const val SOURCE_BACK = "camera (back)"
        const val SOURCE_FRONT = "camera (front)"

        /** phase index k (odd for QPSK) -> 2-bit code: k1 I+Q+ = 3, k3 I-Q+ = 2, k5 I-Q- = 0, k7 I+Q- = 1 */
        private val QCODE = intArrayOf(0, 3, 0, 2, 0, 0, 0, 1)
    }
}
