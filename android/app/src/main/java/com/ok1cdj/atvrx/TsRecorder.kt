package com.ok1cdj.atvrx

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Records the received MPEG-TS as it comes out of leandvb, nothing transcoded: the file holds exactly what was sent
 * (H.264 / HEVC, MP2 / MP3 / AAC, service name) and plays in VLC or mpv; a gap in the signal is a gap in the file.
 * Next to it a .srt of the same name with one cue a second from [subtitle] (MER, CNR, carrier ...), shown by the
 * player over the picture – for range tests.
 * Written by a thread of its own through a queue, so that slow storage never holds up reception: when the queue is
 * full the recording loses data ([dropped]), not the receiver. The file starts with the last PAT and PMT and then a
 * video key frame (SPS), so it plays cleanly from its first byte; without one within 10 s it starts at a PAT anyway.
 * Android 10+: Download/ATV RX/ through MediaStore (no permission); Android 9: the app's own Movies folder.
 */
class TsRecorder(ctx: Context, baseName: String, private val demux: TsDemux, private val subtitle: () -> String,
                 private val log: (String) -> Unit) {
    val name = "$baseName.ts"
    /** where it is, for the log */
    val where: String
    @Volatile var bytes = 0L; private set
    @Volatile var dropped = 0L; private set
    @Volatile var started = false; private set          // a key frame (or a PAT) came, writing
    val since = System.nanoTime()
    @Volatile var error: String? = null; private set

    private val ts: OutputStream
    private val srt: OutputStream
    private val removeTs: () -> Unit       // nothing decoded at all: the empty .ts goes, the .srt (MER log) stays
    private val queue = ArrayBlockingQueue<ByteArray>(512)   // 512 reads of leandvb's output, seconds at these rates
    @Volatile private var running = true
    private val thread: Thread

    init {
        if (Build.VERSION.SDK_INT >= 29) {
            val r = ctx.contentResolver
            fun open(file: String, mime: String): Pair<android.net.Uri, OutputStream> {
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                }
                val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw IOException("cannot create $file")
                return uri to (r.openOutputStream(uri) ?: throw IOException("cannot open $file"))
            }
            val (tsUri, tsOut) = open(name, "video/mp2t")
            ts = tsOut
            removeTs = { r.delete(tsUri, null, null) }
            srt = open("$baseName.srt", "application/x-subrip").second
            where = "Download/$FOLDER/$name"
        } else {
            val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), FOLDER).apply { mkdirs() }
            ts = File(dir, name).outputStream()
            removeTs = { File(dir, name).delete() }
            srt = File(dir, "$baseName.srt").outputStream()
            where = File(dir, name).path
        }
        thread = Thread({ run() }, "rx-rec").apply { start() }
    }

    /** From the TS thread: a copy goes into the queue, never blocks. */
    fun feed(b: ByteArray, n: Int) {
        if (!running || n <= 0) return
        if (!queue.offer(b.copyOf(n))) dropped += n
    }

    fun stop() {
        running = false
        thread.join(3000)
    }

    private fun run() {
        val out = ts.buffered(64 * 1024)
        val sub = srt.bufferedWriter()
        var cue = 0
        var nextCue = System.nanoTime() + 1_000_000_000L
        try {
            while (running || queue.isNotEmpty()) {
                val b = queue.poll(200, TimeUnit.MILLISECONDS)
                if (b != null) {
                    if (started) { out.write(b); bytes += b.size }
                    else beginning(b)?.let { started = true; out.write(it); bytes += it.size }
                }
                val now = System.nanoTime()
                if (now >= nextCue) {
                    // one cue a second, the time of the recording (the player starts the file at 0 as well)
                    val t0 = (nextCue - since) / 1_000_000 - 1000
                    cue++
                    sub.write("$cue\n${srtTime(t0)} --> ${srtTime(t0 + 1000)}\n${subtitle()}\n\n")
                    sub.flush()
                    nextCue += 1_000_000_000L
                    if (cue % 10 == 0 && freeBytes() < MIN_FREE) {
                        error = "storage nearly full"
                        log("recording stopped: storage nearly full")
                        running = false
                    }
                }
            }
        } catch (e: Exception) {
            error = e.message
            log("recording failed: ${e.message}")
        } finally {
            running = false
            try { out.close() } catch (_: IOException) {}
            try { sub.close() } catch (_: IOException) {}
            if (bytes == 0L) try { removeTs() } catch (_: Exception) {}
        }
    }

    val active get() = running

    // before the start: the stream so far, the last PAT and PMT, looking for a key frame of the video
    private var head = ByteArray(0)
    private var lastPat: ByteArray? = null
    private var lastPmt: ByteArray? = null
    private var firstData = 0L

    /** What to write first (PAT, PMT, the key frame's packet and what follows it), or null while still looking. */
    private fun beginning(b: ByteArray): ByteArray? {
        if (firstData == 0L) firstData = System.nanoTime()
        head += b
        var p = 0
        while (head.size - p >= 188) {
            if (head[p] != SYNC || (head.size - p > 188 && head[p + 188] != SYNC)) { p++; continue }
            val pid = ((head[p + 1].toInt() and 0x1F) shl 8) or (head[p + 2].toInt() and 0xFF)
            val pusi = head[p + 1].toInt() and 0x40 != 0
            if (pusi) when (pid) {
                0 -> lastPat = head.copyOfRange(p, p + 188)
                demux.pmtPid -> lastPmt = head.copyOfRange(p, p + 188)
                demux.videoPid -> {
                    val pat = lastPat; val pmt = lastPmt
                    if (pat != null && pmt != null && keyFrame(head, p, demux.videoType))
                        return pat + pmt + head.copyOfRange(p, head.size).also { head = ByteArray(0) }
                }
            }
            p += 188
        }
        head = head.copyOfRange(p, head.size)
        val pat = lastPat
        if (pat != null && System.nanoTime() - firstData > 10_000_000_000L) {
            log("recording: no key frame within 10 s, starting anyway")
            return pat + (lastPmt ?: ByteArray(0)) + head.also { head = ByteArray(0) }
        }
        return null
    }

    companion object {
        const val FOLDER = "ATV RX"
        private const val MIN_FREE = 200L * 1024 * 1024

        private const val SYNC = 0x47.toByte()

        /**
         * Does the PES starting in the TS packet at [p] begin a key frame: a start code of an SPS / IDR (H.264), a VPS /
         * SPS / IRAP (HEVC) or a sequence header (MPEG-2) in this first packet, which is where encoders put them.
         */
        fun keyFrame(b: ByteArray, p: Int, type: Int): Boolean {
            val end = p + 188
            var o = p + 4
            val afc = (b[p + 3].toInt() shr 4) and 3
            if (afc and 2 != 0) o += 1 + (b[o].toInt() and 0xFF)
            if (afc and 1 == 0 || o + 9 > end) return false
            if (b[o].toInt() != 0 || b[o + 1].toInt() != 0 || b[o + 2].toInt() != 1) return false
            o += 9 + (b[o + 8].toInt() and 0xFF)
            for (i in o until end - 3) {
                if (b[i].toInt() != 0 || b[i + 1].toInt() != 0 || b[i + 2].toInt() != 1) continue
                val n = b[i + 3].toInt() and 0xFF
                when (type) {
                    TsDemux.ST_HEVC -> if (((n shr 1) and 0x3F).let { it in 16..21 || it in 32..34 }) return true
                    TsDemux.ST_MPEG2 -> if (n == 0xB3) return true
                    else -> if ((n and 0x1F).let { it == 5 || it == 7 }) return true
                }
            }
            return false
        }

        fun srtTime(ms: Long): String {
            val m = maxOf(0, ms)
            return "%02d:%02d:%02d,%03d".format(m / 3_600_000, m / 60_000 % 60, m / 1000 % 60, m % 1000)
        }

        fun freeBytes(): Long = try { StatFs(Environment.getExternalStorageDirectory().path).availableBytes } catch (_: Exception) { Long.MAX_VALUE }
    }
}
