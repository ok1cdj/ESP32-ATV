package com.ok1cdj.atvrx

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/**
 * The receive chain of the app (phase F3), as rx_dvbs2.py on the PC:
 *   ESP32-S3 (cs8 333 ksps below 200 kS/s, else cs4 500 ksps) -> samples, conjugated -> search for the signal in the
 *   first second (averaged FFT, as rx_dvbs2.py search) -> NCO moves it to 0 Hz -> leandvb (+ ldpc_tool) -> TS ->
 *   TsDemux -> VideoDecoder.
 * The receiver's LO stays where it is; the offset is taken out in software, which is where drift tracking goes later.
 */
class Receiver(
    private val conn: UsbDeviceConnection?,
    private val device: UsbDevice?,
    private val nativeDir: String,
    private val workDir: File,
    val cfg: Config,
    private val video: VideoDecoder,
    private val log: (String) -> Unit,
) {
    /** replay: raw packets recorded by the USB test (F1) instead of the ESP32-S3, played at their sample rate */
    data class Config(val freqHz: Long, val sr: Int, val const: String = "QPSK", val cr: String = "3/4", val gainDb: Double,
                      val frameSizes: Int = FRAMES_NORMAL, val replay: File? = null)

    /** What the screen shows; written by the worker threads. */
    class Status {
        @Volatile var state = "starting"
        @Volatile var offsetHz = 0.0
        @Volatile var searchDb = 0.0
        @Volatile var lock = false
        @Volatile var frameLock = false
        @Volatile var mer = Double.NaN
        @Volatile var ss = Double.NaN
        @Volatile var freq = Double.NaN
        @Volatile var cnr = Double.NaN
        @Volatile var framesOk = 0L          // '_' clean
        @Volatile var framesFixed = 0L       // '.' corrected by BCH
        @Volatile var framesBad = 0L         // '!' (ldpc_tool) or ':' (built-in LDPC): dropped
        @Volatile var driftSteps = 0L
        @Volatile var researches = 0L
        @Volatile var demodSince = 0L        // System.nanoTime() when leandvb started
        @Volatile var locks = 0L
        @Volatile var tsBytes = 0L
        @Volatile var queueDrops = 0L
    }

    val status = Status()
    val demux = TsDemux { type, au -> video.feed(type, au) }
    @Volatile var link: EspLink? = null; private set

    // a replay takes rate and format from its first packet header (word 1: format | decimation << 8)
    private val replayHead = cfg.replay?.inputStream()?.use { s -> ByteArray(4).also { s.read(it) } }
    private val decim = replayHead?.let { it[3].toInt() and 0xFF } ?: if (cfg.sr > 200_000) 2 else 3
    private val format = replayHead?.let { it[2].toInt() and 0xFF } ?: if (cfg.sr > 200_000) EspLink.FMT_CS4 else EspLink.FMT_CS8
    private val wide = format == EspLink.FMT_CS4
    val fs = 1e6 / decim

    @Volatile private var running = true
    @Volatile private var searching = true
    @Volatile private var searchBusy = false
    private val search = FloatArray(2 * fs.toInt())
    private var searchFill = 0
    // NCO as a rotating phasor (no trig per sample); offset changes come from the search and the drift tracking
    @Volatile private var ncoOffset = 0.0
    private var ncoFor = Double.NaN
    private var ncoRe = 1f; private var ncoIm = 0f
    private var stepRe = 1f; private var stepIm = 0f
    @Volatile private var lastFrameLock = 0L
    private var lastTrack = 0L
    private var infoFd: java.io.FileDescriptor? = null
    // ~4 s of samples to leandvb: it stops reading while ldpc_tool works on a batch of 32 frames
    private val queue = ArrayBlockingQueue<ByteArray>(4096)
    private var proc: Process? = null
    private val threads = mutableListOf<Thread>()

    fun start() {
        if (cfg.replay != null) return startReplay(cfg.replay)
        thread("rx-control") {
            try {
                val l = EspLink(UsbCdc(conn!!, device!!))
                link = l
                // a run may still be streaming (the app was killed without ESP_STOP): end it first
                l.send(EspLink.ESP_STOP)
                try { l.await(EspLink.ESP_RUN, 1500) } catch (_: IOException) {}
                l.checkFirmware()
                val lo = l.configure(cfg.freqHz, decim, format, EspLink.gainSelector(cfg.gainDb))
                log("LO %.6f MHz, %.0f ksps %s, SR %d, gain %.0f dB".format(lo / 1e6, fs / 1e3, if (wide) "cs4" else "cs8", cfg.sr, cfg.gainDb))
                status.state = "searching"
                l.onPacket = ::onPacket
                l.onJump = { e, f -> log("USB: packet index %d, expected %d (%+d)".format(f, e, f - e)) }
                l.resetStats()
                l.send(EspLink.ESP_RUN, 0)
                // the run's answer comes when it ends: after stop(), or when the ESP fails
                while (running) {
                    val r = try { l.await(EspLink.ESP_RUN, 1000) } catch (e: IOException) {
                        if (l.error != null) throw e
                        continue
                    }
                    if (running) { status.state = "ESP run ended (code ${r.value})"; log(status.state) }
                    break
                }
            } catch (t: Throwable) {
                status.state = "error: ${t.message}"
                log(status.state)
            }
        }
    }

    private fun startReplay(f: File) {
        log("replay %s: %.0f ksps %s".format(f.name, fs / 1e3, if (wide) "cs4" else "cs8"))
        status.state = "searching (replay)"
        thread("rx-replay") {
            try {
                val d = f.readBytes()
                val t0 = System.nanoTime()
                var samples = 0L
                var i = 0
                while (running && i + EspLink.NB_HEADER <= d.size) {
                    val w = { k: Int -> (d[i + 2 * k].toInt() and 0xFF) or ((d[i + 2 * k + 1].toInt() and 0xFF) shl 8) }
                    if (w(0) != 0x5DE5) { i++; continue }
                    val fmt = w(1) and 0xFF
                    val count = w(2)
                    val size = EspLink.NB_HEADER + count * (EspLink.BYTES_PER_SAMPLE[fmt] ?: 4)
                    if (i + size > d.size) break
                    onPacket(EspLink.Packet(fmt, count, w(3), 0, 0, w(8), d, i, size))
                    i += size
                    samples += count
                    val ahead = samples / fs - (System.nanoTime() - t0) / 1e9   // real time, as from the radio
                    if (ahead > 0.01) Thread.sleep((ahead * 1000).toLong())
                }
                if (running) { status.state = "replay ended"; log("replay ended") }
            } catch (_: InterruptedException) {
            } catch (t: Throwable) { status.state = "error: ${t.message}"; log(status.state) }
        }
    }

    fun stop() {
        running = false
        try { infoFd?.let { android.system.Os.close(it) } } catch (_: Throwable) {}
        try { link?.send(EspLink.ESP_STOP) } catch (_: Throwable) {}
        try { link?.await(EspLink.ESP_RUN, 2000) } catch (_: Throwable) {}
        proc?.destroy()
        threads.forEach { it.interrupt() }
        link?.close()
        threads.forEach { it.join(1000) }
        video.release()
    }

    private fun thread(name: String, body: () -> Unit) = Thread(body, name).also { threads += it; it.start() }

    // ---- USB reader thread

    private fun onPacket(p: EspLink.Packet) {
        val iq = samples(p)
        if (searching) {
            if (searchBusy) return                   // the last second is still being analysed
            val k = minOf(iq.size, search.size - searchFill)
            System.arraycopy(iq, 0, search, searchFill, k)
            searchFill += k
            if (searchFill == search.size) {
                searchFill = 0
                searchBusy = true
                // off the USB reader thread: the FFTs take a moment and the ESP's FIFO is small
                thread("rx-search") {
                    try {
                        if (findSignal()) {
                            if (proc == null) startDemod() else { status.state = "demodulating"; lastFrameLock = System.nanoTime() }
                            searching = false
                        }
                    } catch (t: Throwable) { status.state = "error: ${t.message}"; log(status.state) }
                    searchBusy = false
                }
            }
            return
        }
        // NCO: move the signal to 0 Hz
        val off = ncoOffset
        if (off != ncoFor) {
            ncoFor = off
            val w = -2 * PI * off / fs
            stepRe = cos(w).toFloat(); stepIm = sin(w).toFloat()
        }
        var cr = ncoRe; var ci = ncoIm
        val sr = stepRe; val si = stepIm
        var i = 0
        while (i < iq.size) {
            val re = iq[i]; val im = iq[i + 1]
            iq[i] = re * cr - im * ci; iq[i + 1] = re * ci + im * cr
            val t = cr * sr - ci * si; ci = cr * si + ci * sr; cr = t
            i += 2
        }
        val g = 1f / kotlin.math.sqrt(cr * cr + ci * ci)      // keep |phasor| = 1
        ncoRe = cr * g; ncoIm = ci * g
        val bb = ByteBuffer.allocate(iq.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.asFloatBuffer().put(iq)
        if (!queue.offer(bb.array())) status.queueDrops++
    }

    /** One packet as interleaved float I, Q, conjugated (the radio delivers LO minus RF), full scale about 1. */
    private fun samples(p: EspLink.Packet): FloatArray {
        val o = p.off + EspLink.NB_HEADER
        val out = FloatArray(2 * p.count)
        if (p.format == EspLink.FMT_CS4) {
            val gb = p.shift shr 8
            val g = CS4_MANT[(gb shr 4) and 7] / (1 shl (8 + (gb and 15))).toFloat()
            val k = 1f / (g * 32768f)
            for (j in 0 until p.count) {
                val b = p.buf[o + j].toInt()
                val vi = (b shl 28) shr 28           // bits 0..3, signed
                val vq = (b shl 24) shr 28           // bits 4..7, signed
                out[2 * j] = (vi + 0.5f) * k
                out[2 * j + 1] = -(vq + 0.5f) * k
            }
        } else {
            for (j in 0 until p.count) {
                out[2 * j] = p.buf[o + 2 * j] / 128f
                out[2 * j + 1] = -p.buf[o + 2 * j + 1] / 128f
            }
        }
        return out
    }

    /** rx_dvbs2.py search: averaged 1024-point spectrum, 9-bin smoothing, 95th vs 10th percentile, centre of the -3 dB band. */
    private fun findSignal(): Boolean {
        val n = 1024
        val win = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
        val pow = DoubleArray(n)
        val re = FloatArray(n); val im = FloatArray(n)
        val segs = search.size / 2 / n
        for (s in 0 until segs) {
            for (k in 0 until n) { re[k] = search[2 * (s * n + k)] * win[k]; im[k] = search[2 * (s * n + k) + 1] * win[k] }
            fft(re, im)
            for (k in 0 until n) { val m = (k + n / 2) % n; pow[k] += (re[m] * re[m] + im[m] * im[m]).toDouble() }
        }
        val db = DoubleArray(n) { 10 * log10(pow[it] / segs + 1e-12) }
        val sm = DoubleArray(n) { k -> (-4..4).sumOf { db[(k + it).coerceIn(0, n - 1)] } / 9 }
        val usable = (0 until n).filter { kotlin.math.abs((it - n / 2) * fs / n) < 0.45 * fs }
        val sorted = usable.map { sm[it] }.sorted()
        val floor = sorted[(0.10 * (sorted.size - 1)).toInt()]
        val peak = sorted[(0.95 * (sorted.size - 1)).toInt()]
        status.searchDb = peak - floor
        if (peak - floor < 6) {
            status.state = "searching: no signal (%.1f dB above the floor)".format(peak - floor)
            return false
        }
        val occ = usable.filter { sm[it] > peak - 3 }
        val centre = ((occ.first() + occ.last()) / 2.0 - n / 2) * fs / n
        status.offsetHz = centre
        ncoOffset = centre
        log("signal %.0f dB above the floor, -3 dB width %.0f kHz, centre %+.1f kHz".format(
            peak - floor, (occ.last() - occ.first()) * fs / n / 1e3, centre / 1e3))
        return true
    }

    // ---- leandvb

    private fun startDemod() {
        val modcod = MODCODS[cfg.const to cfg.cr] ?: throw IOException("no MODCOD for ${cfg.const} ${cfg.cr}")
        // --fd-info goes to a FIFO (fd 3 through sh), so that stderr carries only leandvb's frame marks and messages
        val fifo = File(workDir, "leandvb-info")
        fifo.delete()
        android.system.Os.mkfifo(fifo.path, "600".toInt(8))
        val fd = android.system.Os.open(fifo.path, android.system.OsConstants.O_RDWR, 0)   // O_RDWR: no blocking open
        infoFd = fd
        val cmd = mutableListOf(
            "/system/bin/sh", "-c", "exec \"\$0\" \"\$@\" 3>\"\$INFO_FIFO\"",
            File(nativeDir, "libleandvb.so").path, "--f32", "--float-scale", if (wide) "512" else "128",
            "-f", fs.toInt().toString(), "--sr", cfg.sr.toString(), "--standard", "DVB-S2",
            "--const", cfg.const, "--cr", cfg.cr, "--fastdrift", "-d",
            "--modcods", (1L shl modcod).toString(), "--framesizes", cfg.frameSizes.toString(),
            "--ldpc-helper", File(nativeDir, "libldpc_tool.so").path, "--fd-info", "3")
        if (cfg.sr < 100_000) cmd += listOf("--sampler", "rrc", "--rrc-rej", "50")
        if (fs > 3 * cfg.sr) cmd += "--cnr"
        val pb = ProcessBuilder(cmd)
        pb.environment()["INFO_FIFO"] = fifo.path
        val p = pb.start()
        proc = p
        status.state = "demodulating"
        status.demodSince = System.nanoTime()
        lastFrameLock = status.demodSince
        log("leandvb started: ${cfg.const} ${cfg.cr}, frames ${frameSizeName(cfg.frameSizes)}${if (fs > 3 * cfg.sr) ", CNR" else ""}")
        thread("rx-fdinfo") {
            try {
                java.io.FileInputStream(fd).bufferedReader(Charsets.ISO_8859_1).forEachLine { info(it) }
            } catch (_: Throwable) {}
        }
        thread("rx-watch") {
            // no frame lock for 10 s: the signal has moved further than the drift tracking follows; search again
            try {
                while (running) {
                    Thread.sleep(1000)
                    // leandvb prints FRAMELOCK only when it changes: the last state it reported holds
                    if (status.frameLock) lastFrameLock = System.nanoTime()
                    if (!searching && !searchBusy && System.nanoTime() - lastFrameLock > 10_000_000_000L) {
                        status.researches++
                        status.state = "lost: searching again"
                        log("no frame lock for 10 s: searching again")
                        searchFill = 0
                        searching = true
                    }
                }
            } catch (_: InterruptedException) {}
        }
        thread("rx-feed") {
            try {
                val out = p.outputStream
                while (running) {
                    val b = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    out.write(b)
                }
            } catch (_: Throwable) {}
        }
        thread("rx-ts") {
            try {
                val inp = p.inputStream
                val b = ByteArray(188 * 64)
                while (true) {
                    val n = inp.read(b)
                    if (n < 0) break
                    status.tsBytes += n
                    demux.feed(b, n)
                }
            } catch (_: Throwable) {}
            if (running) { status.state = "leandvb ended"; log("leandvb ended (exit ${try { p.exitValue() } catch (_: Throwable) { "?" }})") }
        }
        thread("rx-info") {
            // leandvb -d: one mark per frame, no newline: '_' clean, '.' corrected, '!' / ':' failed; then message lines
            try {
                val r = p.errorStream.bufferedReader(Charsets.ISO_8859_1)
                val line = StringBuilder()
                while (true) {
                    val c = r.read()
                    if (c < 0) break
                    val ch = c.toChar()
                    if (line.isEmpty() && ch in "_.!:") {
                        when (ch) {
                            '_' -> status.framesOk++
                            '.' -> status.framesFixed++
                            else -> status.framesBad++
                        }
                    } else if (ch == '\n') {
                        message(line.toString()); line.setLength(0)
                    } else line.append(ch)
                }
            } catch (_: Throwable) {}
        }
    }

    private fun message(l: String) {
        if ("LOCKED" in l) status.locks++
        if (l.startsWith("***") || "fatal" in l.lowercase() || l.startsWith("Spawning")) log("leandvb: $l")
    }

    /** --fd-info lines: "MER 10.6", "LOCK 1", "FREQ -2342" (residual carrier offset after the NCO, Hz), ... */
    private fun info(l: String) {
        val m = INFO.find(l) ?: return
        val v = m.groupValues[2].toDoubleOrNull() ?: return
        when (m.groupValues[1]) {
            "MER" -> status.mer = v
            "CNR" -> status.cnr = v
            "SS" -> status.ss = v
            "FREQ" -> { status.freq = v; track(v) }
            "LOCK" -> status.lock = v != 0.0
            "FRAMELOCK" -> { status.frameLock = v != 0.0; if (v != 0.0) lastFrameLock = System.nanoTime() }
        }
    }

    /**
     * Drift tracking: the oscillators (no TCXO) walk by kHz as they warm up. While locked, move the NCO towards the
     * carrier leandvb reports, in small steps (at most every 0.5 s) that its own tracking follows without losing lock.
     */
    private fun track(freq: Double) {
        if (!status.frameLock || searching) return
        val now = System.nanoTime()
        if (now - lastTrack < 500_000_000L) return
        lastTrack = now
        if (kotlin.math.abs(freq) < 300) return
        val maxStep = maxOf(200.0, cfg.sr * 0.002)
        val step = kotlin.math.sign(freq) * minOf(kotlin.math.abs(freq) * 0.5, maxStep)
        ncoOffset += step
        status.offsetHz = ncoOffset
        status.driftSteps++
    }

    companion object {
        const val FRAMES_NORMAL = 1; const val FRAMES_SHORT = 2; const val FRAMES_BOTH = 3   // leandvb --framesizes
        fun frameSizeName(f: Int) = when (f) { FRAMES_NORMAL -> "normal"; FRAMES_SHORT -> "short"; else -> "normal+short" }
        private val CS4_MANT = floatArrayOf(256f, 279f, 304f, 332f, 362f, 395f, 431f, 470f)
        private val INFO = Regex("""\b(MER|SS|FREQ|LOCK|FRAMELOCK|CNR|VBER)\s+(-?[0-9.]+)""")

        /** DVB-S2 MODCODs (EN 302 307-1 table 12) of QPSK and 8PSK, for leandvb --modcods. */
        val MODCODS: Map<Pair<String, String>, Int> =
            listOf("1/4", "1/3", "2/5", "1/2", "3/5", "2/3", "3/4", "4/5", "5/6", "8/9", "9/10")
                .mapIndexed { i, r -> ("QPSK" to r) to i + 1 }.toMap() +
            listOf("3/5", "2/3", "3/4", "5/6", "8/9", "9/10").mapIndexed { i, r -> ("8PSK" to r) to i + 12 }.toMap()

        /** In-place radix-2 FFT (n a power of two). */
        fun fft(re: FloatArray, im: FloatArray) {
            val n = re.size
            val tw = twiddles.getOrPut(n) { FloatArray(n) { k -> if (k < n / 2) cos(-2 * PI * k / n).toFloat() else sin(-2 * PI * (k - n / 2) / n).toFloat() } }
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
            }
            var len = 2
            while (len <= n) {
                val step = n / len
                for (i in 0 until n step len) {
                    for (k in 0 until len / 2) {
                        val wr = tw[k * step]; val wi = tw[n / 2 + k * step]
                        val a = i + k; val b = a + len / 2
                        val vr = re[b] * wr - im[b] * wi
                        val vi = re[b] * wi + im[b] * wr
                        re[b] = re[a] - vr; im[b] = im[a] - vi
                        re[a] += vr; im[a] += vi
                    }
                }
                len = len shl 1
            }
        }
        private val twiddles = java.util.concurrent.ConcurrentHashMap<Int, FloatArray>()
    }
}
