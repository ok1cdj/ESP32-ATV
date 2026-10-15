package com.ok1cdj.atvrx

import android.graphics.Color
import android.graphics.Paint
import java.io.FileOutputStream

/**
 * Debug only: the TX chain without the C3 - picture (H.264) + sound (AAC) -> TsMux -> a file, pulled in real time with
 * the transmitter's lead. Every 2 s the picture flashes white and a 1 kHz beep sounds for 100 ms, both on the capture
 * clock, so that the lip sync can be measured from the file (PTS of the white frame vs the beep).
 *
 *   adb shell am start -n com.ok1cdj.atvrx/.MuxSelfTestActivity --es args "TS_BPS SECONDS NAME.ts [AUDIO_BPS (0: none)] [LEAD_S]"
 *   -> /sdcard/Android/data/com.ok1cdj.atvrx/files/NAME.ts and NAME.ts.log
 * (app_process gets killed by the system now and then: the app has a foreground process.)
 */
object MuxSelfTest {
    private const val PERIOD = 2_000_000_000L
    private const val FLASH = 100_000_000L

    fun run(a: Array<String>, println: (String) -> Unit) {
        val tsRate = a[0].toDouble()
        val seconds = a[1].toDouble()
        val audioBps = a.getOrNull(3)?.toInt() ?: 0
        val leadS = a.getOrNull(4)?.toDouble() ?: 0.6
        val videoBps = (tsRate * 0.72).toInt() - audioBps
        val (w, h) = if (videoBps < 80_000) 320 to 180 else if (videoBps < 200_000) 480 to 270 else 640 to 360
        val fps = if (videoBps < 80_000) 5 else if (videoBps < 200_000) 10 else 15
        val m = TsMux(tsRate, "SELFTEST", "ESP32-ATV", audio = audioBps > 0, delay = TsMux.delayFor(videoBps))
        val paint = Paint().apply { color = Color.WHITE }   // no text: app_process has no fonts
        var n = 0
        var phases = 0L; var nph = 0                       // when the flash frames are drawn after the beep starts
        val painter = Painter { cv, pw, ph ->
            val ph0 = System.nanoTime() % PERIOD
            val flash = ph0 < FLASH
            if (flash) { phases += ph0; nph++ }
            cv.drawColor(if (flash) Color.WHITE else Color.DKGRAY)
            if (!flash) { val x = (n++ % 20) * pw / 20f; cv.drawRect(x, ph / 3f, x + pw / 20f, 2 * ph / 3f, paint) }
        }
        val video = CanvasEncoder(w, h, fps, videoBps, if (videoBps < 80_000) 4 else 2, painter) { au, key, us -> m.addAu(au, key, us) }
        // the beep on the same clock: sample k is captured at t0 + k / rate
        val pcm = object : PcmSource {
            var rate = 0; var t0 = 0L; var k = 0L
            override fun start(rate: Int) { this.rate = rate; t0 = System.nanoTime(); k = 0 }
            override fun read(buf: ShortArray, n: Int): Long {
                val wait = t0 + (k + n) * 1_000_000_000L / rate - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                for (i in 0 until n) {
                    val t = t0 + (k + i) * 1_000_000_000L / rate
                    buf[i] = if (t % PERIOD < FLASH) (12000 * Math.sin(2 * Math.PI * 1000 * (k + i) / rate)).toInt().toShort() else 0
                }
                val first = (t0 + k * 1_000_000_000L / rate) / 1000
                k += n
                return first
            }
            override fun stop() {}
        }
        val aac = if (audioBps > 0) AacEncoder(audioBps, pcm) { f, us, dur -> m.addAudio(f, us, dur) } else null
        video.start()
        aac?.start()
        println("video ${video.encoderName} ${w}x$h $fps fps ${videoBps / 1000} kb/s; audio ${aac?.let { "${it.encoderName} ${it.description}" } ?: "none"}; " +
            "TS %.1f kb/s, delay %.2f s, lead %.2f s".format(tsRate / 1e3, m.delay, leadS))
        val out = FileOutputStream(a[2])
        val pkt = ByteArray(188 * 64)
        val t0 = System.nanoTime()
        try {
            while (m.time < seconds) {
                // keep the mux [leadS] ahead of real time, like the transmitter's buffer (filled at the start)
                val target = (System.nanoTime() - t0) / 1e9 + leadS
                var k = 0
                while (m.time < target && k < 64) { m.next(pkt, 188 * k); k++ }
                if (k > 0) out.write(pkt, 0, 188 * k) else Thread.sleep(5)
                video.error?.let { throw RuntimeException(it) }
                aac?.error?.let { throw RuntimeException(it) }
            }
        } finally {
            video.stop(); aac?.stop(); out.close()
        }
        println("frames %d, audio frames %d; sent video %.1f kb/s, audio %.1f kb/s, null %.0f %%; dropped %d, late %d; slack video %.3f s, audio %.3f s"
            .format(video.frames, aac?.frames ?: 0, m.videoBytes * 8 / m.time / 1e3, m.audioBytes * 8 / m.time / 1e3,
                100.0 * m.nullPackets * 1504 / rate(m), m.droppedAus, m.lateAus, m.videoSlack, m.audioSlack))
        if (nph > 0) println("flash drawn %.1f ms after the beep starts (mean of %d): lip sync = measured audio - video + that".format(phases / 1e6 / nph, nph))
    }

    private fun rate(m: TsMux) = m.time * m.rate
}
