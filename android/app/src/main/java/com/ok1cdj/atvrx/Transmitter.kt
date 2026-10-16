package com.ok1cdj.atvrx

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.util.concurrent.locks.LockSupport

/**
 * Live DVB-S2 transmission through the XIAO ESP32-C3 (ESP32-DATV(-SA) firmware, PC streaming mode): test card or
 * camera ([TestCard], [CameraSource]) -> H.264 ([CanvasEncoder]), sound ([MicPcm], [CwId]) -> AAC ([AacEncoder]) -> constant-rate TS ([TsMux]) -> DVB-S2 ([S2Encoder], continuous framing) -> packed QPSK symbols
 * -> USB, kept at the firmware's buffer target by its fill reports.
 */
class Transmitter(private val ctx: android.content.Context, private val usbManager: UsbManager, private val device: UsbDevice,
                  val cfg: Config, private val log: (String) -> Unit) {
    /** const: QPSK or 8PSK; source: SOURCE_CARD, SOURCE_BACK or SOURCE_FRONT (camera); sound: SOUND_*; videoKbps, fps,
     *  audioKbps: 0 = automatic */
    data class Config(val freqKhz: Long, val baud: Int, val rate: String, val shortFrame: Boolean, val pilots: Boolean,
                      val amp: Int, val call: String, val service: String, val provider: String, val source: String = SOURCE_CARD,
                      val const: String = "QPSK", val videoKbps: Int = 0, val fps: Int = 0, val sound: String = SOUND_OFF,
                      val audioKbps: Int = 0)

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
        @Volatile var audioKbps = 0
        @Volatile var lo = ""
    }

    val status = Status()
    @Volatile private var running = true
    private var thread: Thread? = null
    @Volatile var video: CanvasEncoder? = null; private set
    @Volatile var camera: CameraSource? = null; private set
    @Volatile var mux: TsMux? = null; private set
    @Volatile var audio: AacEncoder? = null; private set

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
            log("C3: $id${beacon?.let { " · $it" } ?: ""}${if (link.booted) " (reset by opening the port)" else ""}")
            val dc = link.dc(cfg.freqKhz / 1000.0) ?: (0.0 to 0.0)        // the C3's table at our frequency
            log("C3 DC correction %.3f %.3f".format(dc.first, dc.second))
            link.pa(false)

            val psk8 = cfg.const == "8PSK"
            enc = S2Encoder(cfg.const, cfg.rate, cfg.shortFrame, cfg.pilots)
            val sps = if (psk8) C3Link.psk8Sps(cfg.baud) else C3Link.qpskSps(cfg.baud)
            val baud = if (psk8) C3Link.psk8Baud(cfg.baud, sps) else C3Link.qpskBaud(cfg.baud, sps)
            val tsRate = enc.tsRate(baud)
            status.baud = baud; status.tsRate = tsRate
            // video: what is left after tables, PCR, PES headers and the sound, with a margin for the encoder's overshoot; by
            // hand at most 90 % of the TS rate
            val audioBps = audioBps(cfg, tsRate)
            val videoBps = if (cfg.videoKbps > 0) minOf(cfg.videoKbps * 1000, (tsRate * 0.9).toInt() - audioBps)
                else (tsRate * 0.72).toInt() - audioBps
            if (videoBps < 10_000) throw java.io.IOException("no room for the picture: less sound (audio kb/s)")
            status.videoKbps = videoBps / 1000; status.audioKbps = audioBps / 1000
            // picture size from the video bit rate, frame rate from the symbol rate unless given
            val (w, h) = when {
                videoBps < 80_000 -> 320 to 180
                videoBps < 200_000 -> 480 to 270
                else -> 640 to 360
            }
            val fps = if (cfg.fps > 0) cfg.fps.coerceIn(1, 30) else if (videoBps < 80_000) 5 else if (videoBps < 200_000) 10 else 15
            val m = TsMux(tsRate, cfg.service, cfg.provider, audio = audioBps > 0, delay = TsMux.delayFor(videoBps))
            mux = m
            val mode = "DVB-S2 ${cfg.const} ${cfg.rate}${if (cfg.shortFrame) " short" else ""}  %.1f kS/s".format(java.util.Locale.ROOT, cfg.baud / 1000.0)
            val painter: Painter = if (cfg.source == SOURCE_CARD)
                TestCard(cfg.call, "ESP32-ATV TX  %.3f MHz".format(java.util.Locale.ROOT, cfg.freqKhz / 1000.0), mode)
            else CameraSource(ctx, cfg.source == SOURCE_BACK, if (w > 320) 640 else 320, cfg.call,
                "%.3f MHz  %s".format(java.util.Locale.ROOT, cfg.freqKhz / 1000.0, mode)).also {
                camera = it
                it.start()
            }
            val c = CanvasEncoder(w, h, fps, videoBps, if (videoBps < 80_000) 4 else 2, painter) { au, key, us -> m.addAu(au, key, us) }
            video = c
            c.start()
            log("video ${c.encoderName}: ${w}x$h $fps fps, ${videoBps / 1000} kb/s; TS %.1f kb/s".format(tsRate / 1000))
            camera?.let { log("camera ${if (cfg.source == SOURCE_BACK) "back" else "front"} ${it.size}") }
            soundNote(cfg, tsRate, audioBps)?.let(log)

            val target = 3000
            val cmd = "%s %.3f %d %d %d %d %d %d %d %d %d %d".format(java.util.Locale.ROOT, if (psk8) "PSK8T" else "QPSKT",
                cfg.freqKhz / 1000.0, cfg.baud, sps,
                cfg.amp, 86400, 0, target, Math.round(16 * dc.first), Math.round(16 * dc.second), 10000, 0)
            log("> $cmd")
            val ok = link.start(cmd)
            log(ok)
            status.lo = Regex("LO (\\S+)").find(ok)?.groupValues?.get(1) ?: ""
            status.state = "transmitting"
            status.since = System.nanoTime()
            // the sound starts with the mux: captured earlier, it would come too late for its time stamps
            if (audioBps > 0) audio = AacEncoder(audioBps, pcmSource(cfg, CW_PERIOD_S)) { f, us, dur -> m.addAudio(f, us, dur) }.also {
                it.start()
                log("sound ${cfg.sound}: ${it.encoderName} ${it.description}")
            }

            // symbols -> bytes across frame boundaries, first in the low bits: QPSK 4 per byte (bit 0 = I > 0, bit 1 = Q > 0),
            // 8PSK 2 per byte (the phase index k in a nibble)
            val sym = ByteArray(enc.frameSymbols)
            val pkt = ByteArray(188)
            var out = ByteArray(65536)
            var outLen = 0
            var acc = 0; var nacc = 0
            fun produceFrame() {
                while (!enc.ready()) { m.next(pkt); enc.push(pkt) }
                val n = enc.frame(sym)
                if (outLen + n / 2 + 2 > out.size) out = out.copyOf(out.size * 2)
                if (psk8) for (i in 0 until n) {
                    acc = acc or ((sym[i].toInt() and 7) shl (4 * nacc))
                    if (++nacc == 2) { out[outLen++] = acc.toByte(); acc = 0; nacc = 0 }
                } else for (i in 0 until n) {
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
                audio?.error?.let { throw java.io.IOException(it) }
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
            audio?.stop()
            enc?.close()
            link?.close()
        }
    }

    companion object {
        const val SOURCE_CARD = "test card"
        const val SOURCE_BACK = "camera (back)"
        const val SOURCE_FRONT = "camera (front)"
        const val SOUND_OFF = "off"
        const val SOUND_MIC = "microphone"
        const val SOUND_CW = "CW ID"
        const val SOUND_MIC_CW = "mic + CW ID"
        val SOUNDS = listOf(SOUND_OFF, SOUND_MIC, SOUND_CW, SOUND_MIC_CW)
        const val CW_PERIOD_S = 30.0
        /** below this TS rate "auto" sends no sound: it would take a quarter of the picture's bits */
        const val AUDIO_AUTO_MIN_TS = 80_000

        /** the sound's bit rate: by hand, or automatic from the TS rate (none below [AUDIO_AUTO_MIN_TS]); 0 = no sound */
        fun audioBps(cfg: Config, tsRate: Double) = when {
            cfg.sound == SOUND_OFF -> 0
            cfg.audioKbps > 0 -> cfg.audioKbps * 1000
            tsRate < AUDIO_AUTO_MIN_TS -> 0
            tsRate <= 200_000 -> 24_000
            else -> 64_000
        }

        fun soundNote(cfg: Config, tsRate: Double, audioBps: Int) = when {
            cfg.sound == SOUND_OFF -> null
            audioBps == 0 -> "sound: none at TS %.0f kb/s with audio kb/s auto (below %d) – choose kb/s to send it"
                .format(tsRate / 1e3, AUDIO_AUTO_MIN_TS / 1000)
            else -> "sound: %d kb/s = %.0f %% of the TS".format(audioBps / 1000, 100.0 * audioBps / tsRate)
        }

        /** the microphone and / or the callsign in Morse every [cwPeriod] s */
        fun pcmSource(cfg: Config, cwPeriod: Double): PcmSource {
            val cw = if (cfg.sound == SOUND_CW || cfg.sound == SOUND_MIC_CW) CwId(cfg.call, cwPeriod) else null
            return if (cfg.sound == SOUND_CW) cwPcm(cw!!) else MicPcm(cw)
        }

        /** phase index k (odd for QPSK) -> 2-bit code: k1 I+Q+ = 3, k3 I-Q+ = 2, k5 I-Q- = 0, k7 I+Q- = 1 */
        private val QCODE = intArrayOf(0, 3, 0, 2, 0, 0, 0, 1)
    }
}
