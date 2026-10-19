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
                      val frameSizes: Int = FRAMES_NORMAL, val replay: File? = null, val track: Boolean = true)

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
        @Volatile var tsCrcBad = 0L          // 'C': TS packet with a bad CRC
        @Volatile var driftRate = 0.0       // Hz/s the NCO is ramping at (drift tracking)
        @Volatile var researches = 0L
        @Volatile var demodSince = 0L        // System.nanoTime() when leandvb started
        @Volatile var locks = 0L
        @Volatile var tsBytes = 0L
        @Volatile var queueDrops = 0L
    }

    val status = Status()
    /** leandvb --fd-const lines ("CONST ...", "SYMBOLS ..."), for the constellation view */
    @Volatile var onConstellation: ((String) -> Unit)? = null
    val demux = TsDemux { type, au, pts -> video.feed(type, au, pts) }
    /** the TS as it comes from leandvb, for the recording */
    @Volatile var onTs: ((ByteArray, Int) -> Unit)? = null
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
    @Volatile private var lastFrameLock = 0L          // last good frame ('_' or '.') or start / new search
    // ldpc_tool returns frames in batches of 32: the receiver is alive while a good frame came within 2.5 batches
    private val batchNs = (32 * frameSymbols() / cfg.sr * 1e9).toLong()
    private val aliveNs = maxOf(10_000_000_000L, batchNs * 5 / 2)
    fun alive() = System.nanoTime() - lastFrameLock < aliveNs
    @Volatile private var ncoRate = 0.0                       // Hz/s, set by track(), applied packet by packet
    private var infoFd: java.io.FileDescriptor? = null
    private var constFd: java.io.FileDescriptor? = null
    // ~4 s of samples to leandvb: it stops reading while ldpc_tool works on a batch of 32 frames
    private val queue = ArrayBlockingQueue<ByteArray>(4096)
    private var proc: Process? = null
    private val threads = mutableListOf<Thread>()

    fun start() {
        thread("rx-spectrum") { specWorker() }
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

    // ---- spectrum: the raw samples (before the NCO, as the search sees them), averaged FFT a few times a second

    /** dB per bin, -fs/2 .. +fs/2 (SPEC_N bins), smoothed; empty until the first update */
    @Volatile var spectrum = FloatArray(0); private set
    private val specBuf = FloatArray(2 * SPEC_N * SPEC_SEGS)
    private var specFill = 0
    @Volatile private var specReady = false
    private val specLock = Object()

    private fun specFeed(iq: FloatArray) {
        if (specReady) return                          // the worker has not taken the last block yet
        val k = minOf(iq.size, specBuf.size - specFill)
        System.arraycopy(iq, 0, specBuf, specFill, k)
        specFill += k
        if (specFill == specBuf.size) { specReady = true; synchronized(specLock) { specLock.notify() } }
    }

    private fun specWorker() {
        val win = FloatArray(SPEC_N) { (0.5 - 0.5 * cos(2 * PI * it / SPEC_N)).toFloat() }
        val re = FloatArray(SPEC_N); val im = FloatArray(SPEC_N)
        var avg: FloatArray? = null
        try {
            while (running) {
                synchronized(specLock) { if (!specReady) specLock.wait(500) }
                if (!specReady) continue
                val pow = FloatArray(SPEC_N)
                for (sg in 0 until SPEC_SEGS) {
                    val o = 2 * sg * SPEC_N
                    for (k in 0 until SPEC_N) { re[k] = specBuf[o + 2 * k] * win[k]; im[k] = specBuf[o + 2 * k + 1] * win[k] }
                    fft(re, im)
                    for (k in 0 until SPEC_N) { val m = (k + SPEC_N / 2) % SPEC_N; pow[k] += re[m] * re[m] + im[m] * im[m] }
                }
                specFill = 0; specReady = false
                val db = FloatArray(SPEC_N) { (10 * log10(pow[it] / SPEC_SEGS + 1e-12)).toFloat() }
                val a = avg?.let { prev -> FloatArray(SPEC_N) { prev[it] * 0.7f + db[it] * 0.3f } } ?: db
                avg = a
                spectrum = a
                Thread.sleep(200)                       // ~4 updates a second
            }
        } catch (_: InterruptedException) {}
    }

    fun stop() {
        running = false
        try { infoFd?.let { android.system.Os.close(it) } } catch (_: Throwable) {}
        try { constFd?.let { android.system.Os.close(it) } } catch (_: Throwable) {}
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
        specFeed(iq)
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
        // NCO: move the signal to 0 Hz; drift tracking ramps its frequency packet by packet (the phasor stays continuous)
        val rate = ncoRate
        if (rate != 0.0 && !searching && alive()) {          // no FREQ reports without frames: do not keep ramping
            ncoOffset += rate * (iq.size / 2) / fs
            status.offsetHz = ncoOffset
        }
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
        removeDc(out)
        return out
    }

    // The receiver's own DC (LO leakage, ADC offset) sits at 0 Hz; cs8 keeps it (cs4 removes it on the chip). With the
    // carrier only a few kHz off the LO it lands inside the signal and nothing decodes (seen: MER -4 dB at 27 dB SNR).
    // A running mean over packets (~0.1 s) taken off every sample: a notch a few Hz wide at 0 Hz.
    private var dcI = 0f
    private var dcQ = 0f
    private fun removeDc(iq: FloatArray) {
        var si = 0f; var sq = 0f
        var i = 0
        while (i < iq.size) { si += iq[i]; sq += iq[i + 1]; i += 2 }
        val n = iq.size / 2
        if (n == 0) return
        dcI += (si / n - dcI) * 0.02f
        dcQ += (sq / n - dcQ) * 0.02f
        i = 0
        while (i < iq.size) { iq[i] -= dcI; iq[i + 1] -= dcQ; i += 2 }
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

    /** Symbols of one PLFRAME (the longer size enabled): data slots, PLHEADER, pilot blocks. */
    private fun frameSymbols(): Double {
        val bits = if (cfg.frameSizes and FRAMES_NORMAL != 0) 64800 else 16200
        val slots = bits / (if (cfg.const == "8PSK") 3 else 2) / 90
        return 90.0 + slots * 90 + (slots - 1) / 16 * 36
    }

    // ---- leandvb

    private fun startDemod() {
        val modcod = MODCODS[cfg.const to cfg.cr] ?: throw IOException("no MODCOD for ${cfg.const} ${cfg.cr}")
        // --fd-info goes to a FIFO (fd 3 through sh), so that stderr carries only leandvb's frame marks and messages
        fun fifo(name: String): Pair<File, java.io.FileDescriptor> {
            val f = File(workDir, name)
            f.delete()
            android.system.Os.mkfifo(f.path, "600".toInt(8))
            return f to android.system.Os.open(f.path, android.system.OsConstants.O_RDWR, 0)   // O_RDWR: no blocking open
        }
        val (infoFifo, fd) = fifo("leandvb-info")
        val (constFifo, cfd) = fifo("leandvb-const")
        infoFd = fd; constFd = cfd
        val cmd = mutableListOf(
            "/system/bin/sh", "-c", "exec \"\$0\" \"\$@\" 3>\"\$INFO_FIFO\" 4>\"\$CONST_FIFO\"",
            File(nativeDir, "libleandvb.so").path, "--f32", "--float-scale", if (wide) "512" else "128",
            "-f", fs.toInt().toString(), "--sr", cfg.sr.toString(), "--standard", "DVB-S2",
            "--const", cfg.const, "--cr", cfg.cr, "--fastdrift", "-d",
            "--modcods", (1L shl modcod).toString(), "--framesizes", cfg.frameSizes.toString(),
            "--ldpc-helper", File(nativeDir, "libldpc_tool.so").path, "--fd-info", "3", "--fd-const", "4")
        // --fastlock: no ~5 normal frames of data thrown away before each new search (4 s at 40 kS/s); the first lock on
        // normal frames is often a false one (MER < 0), and this gets out of it sooner (rx/sim_lock.py)
        if (cfg.sr < 100_000) cmd += listOf("--sampler", "rrc", "--rrc-rej", "50", "--fastlock")
        if (fs > 3 * cfg.sr) cmd += "--cnr"
        // 2000 ppm instead of leandvb's 100: a C3 loop from the flash runs at 160 MHz / whole cycles (333 333 Bd for 333 k)
        cmd += listOf("--sr-tol", "0.002")
        val pb = ProcessBuilder(cmd)
        pb.environment()["INFO_FIFO"] = infoFifo.path
        pb.environment()["CONST_FIFO"] = constFifo.path
        val p = pb.start()
        proc = p
        status.state = "demodulating"
        status.demodSince = System.nanoTime()
        lastFrameLock = status.demodSince
        log("leandvb started: ${cfg.const} ${cfg.cr}, frames ${frameSizeName(cfg.frameSizes)}${if (fs > 3 * cfg.sr) ", CNR" else ""}; " +
            "first picture after about %.0f s (ldpc_tool decodes 32 frames at a time)".format(batchNs / 1e9))
        thread("rx-fdinfo") {
            try {
                java.io.FileInputStream(fd).bufferedReader(Charsets.ISO_8859_1).forEachLine { info(it) }
            } catch (_: Throwable) {}
        }
        thread("rx-const") {
            try {
                java.io.FileInputStream(cfd).bufferedReader(Charsets.ISO_8859_1).forEachLine { l -> onConstellation?.invoke(l) }
            } catch (_: Throwable) {}
        }
        thread("rx-watch") {
            // no frame lock for 10 s: the signal has moved further than the drift tracking follows; search again
            try {
                while (running) {
                    Thread.sleep(1000)
                    // leandvb prints FRAMELOCK only on a change, so it can stay "1" after the signal is gone:
                    // what counts is good frames coming out of the LDPC/BCH decoders
                    if (!searching && !searchBusy && !alive()) {
                        status.researches++
                        status.state = "lost: searching again"
                        log("no good frame for %.0f s: searching again".format(aliveNs / 1e9))
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
                // time to the first TS and the first decoded picture, for comparing frame sizes and symbol rates
                var firstTs = true
                val frames0 = video.frames
                var firstPicture = true
                fun since() = (System.nanoTime() - status.demodSince) / 1e9
                while (true) {
                    val n = inp.read(b)
                    if (n < 0) break
                    if (firstTs) { firstTs = false; log("first TS %.1f s after leandvb started".format(since())) }
                    status.tsBytes += n
                    demux.feed(b, n)
                    onTs?.invoke(b, n)
                    if (firstPicture && video.frames > frames0) {
                        firstPicture = false; log("first picture %.1f s after leandvb started".format(since()))
                    }
                }
            } catch (_: Throwable) {}
            if (running) { status.state = "leandvb ended"; log("leandvb ended (exit ${try { p.exitValue() } catch (_: Throwable) { "?" }})") }
        }
        thread("rx-info") {
            // leandvb -d: marks without newlines – '_' clean frame, '.' corrected, '!' / ':' failed, 'C' TS packet with a bad
            // CRC – mixed with messages that need not start on a new line ("____Start TS at 0", "_!___CUnsupported ...").
            // A mark is a mark at the start of a line or right after another mark; anything else starts a message.
            try {
                val r = p.errorStream.bufferedReader(Charsets.ISO_8859_1)
                val line = StringBuilder()
                var inMarks = true
                var ended = false        // after "sch stopped" leandvb dumps its pipes, lines starting with '.'
                while (true) {
                    val c = r.read()
                    if (c < 0) break
                    val ch = c.toChar()
                    if (ch == '\n') {
                        if (line.isNotEmpty()) { message(line.toString()); if ("sch stopped" in line) ended = true }
                        line.setLength(0); inMarks = true
                    } else if (inMarks && !ended && ch in "_.!:C") {
                        when (ch) {
                            '_' -> { status.framesOk++; lastFrameLock = System.nanoTime() }
                            '.' -> { status.framesFixed++; lastFrameLock = System.nanoTime() }
                            'C' -> status.tsCrcBad++
                            else -> status.framesBad++
                        }
                    } else {
                        line.append(ch); inMarks = false
                    }
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
            "FRAMELOCK" -> status.frameLock = v != 0.0
        }
    }

    /**
     * Drift tracking: the oscillators (no TCXO) walk by kHz as they warm up - the C3 up to some 260 Hz/s after power-up.
     * A first-order loop on the carrier leandvb reports (FREQ, left after the NCO): the NCO ramps at 0.5 x FREQ Hz/s,
     * at most 600 Hz/s, smoothly (steps of 200 Hz upset leandvb's lock at 50 kS/s: replay 58 -> 8 good frames). A drift
     * of r Hz/s stays r / 0.5 behind (260 Hz/s: about 500 Hz, well inside what leandvb follows). As rx_dvbs2.py's Nco.
     */
    private fun track(freq: Double) {
        // only on a live signal: a stale FRAMELOCK on noise would walk the NCO away (MER -13 dB seen when the TX stopped)
        val rate = if (!cfg.track || searching || !alive() || !(status.mer > 3.0)) 0.0
                   else (0.5 * freq).coerceIn(-600.0, 600.0)
        ncoRate = rate
        status.driftRate = rate
    }

    companion object {
        const val SPEC_N = 1024; const val SPEC_SEGS = 8
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
