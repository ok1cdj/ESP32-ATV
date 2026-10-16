package com.ok1cdj.atvrx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.media.AudioFormat
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.OpenableColumns
import java.io.IOException
import java.nio.ByteOrder

/**
 * A picture or a video from the phone's storage as the TX picture (live and "Save as beacon"), chosen with the system's
 * file picker (a content URI, no storage permission). A picture is shown whole (black bars), a video plays in an endless
 * loop at its own pace: MediaExtractor -> MediaCodec into an ImageReader, each frame released at [t0] + its time stamp
 * (+ the loop period after every pass), converted at most [fps] times a second and shrunk while converting (a 4K video
 * costs what a 640x360 one does). With [overlay] the callsign strip of the camera goes over it. Its sound: [FilePcm],
 * on the same clock.
 */
class FileSource(private val ctx: Context, private val uri: Uri, private val maxWidth: Int, private val fps: Int,
                 @Volatile var call: String, @Volatile var line: String, private val overlay: Boolean) : LivePainter {
    @Volatile override var error: String? = null; private set
    @Volatile var frames = 0L; private set
    @Volatile var loops = 0; private set
    /** the clock of the loop: position 0 of the file at this System.nanoTime */
    @Volatile var t0 = 0L; private set
    /** the length of one pass of the video, µs (0 for a picture) */
    @Volatile var periodUs = 0L; private set
    var info: Info? = null; private set

    private var still: Bitmap? = null
    private var yuv: YuvToBitmap? = null
    private var rotation = 0
    @Volatile private var running = false
    private var thread: Thread? = null
    private var readerThread: HandlerThread? = null
    private var reader: ImageReader? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()

    /** Opens the file; throws with the reason if it cannot be read. */
    fun start() {
        val i = probe(ctx, uri)
        info = i
        t0 = System.nanoTime()
        if (!i.video) {
            still = decodePicture(ctx, uri, 2 * maxWidth)
            return
        }
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            ?: throw IOException("no video track in ${i.name}")
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val vw = fmt.getInteger(MediaFormat.KEY_WIDTH); val vh = fmt.getInteger(MediaFormat.KEY_HEIGHT)
        rotation = if (fmt.containsKey(MediaFormat.KEY_ROTATION)) fmt.getInteger(MediaFormat.KEY_ROTATION) else i.rotation
        fmt.setInteger(MediaFormat.KEY_ROTATION, 0)          // an ImageReader gets the frames unturned anyway: drawn turned
        periodUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else i.durationUs
        if (periodUs <= 0) throw IOException("${i.name}: unknown length")
        val y = YuvToBitmap(maxOf(1, maxOf(vw, vh) / maxWidth))
        yuv = y
        val rt = HandlerThread("tx-file").apply { start() }
        readerThread = rt
        val r = ImageReader.newInstance(vw, vh, ImageFormat.YUV_420_888, 3)
        var last = 0L
        r.setOnImageAvailableListener({ ir ->
            val img = try { ir.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            try {
                val now = System.nanoTime()
                if (now - last >= 800_000_000L / fps) { y.convert(img); last = now; frames++ }
            } catch (e: Exception) { error = "video: ${e.message}" } finally { img.close() }
        }, Handler(rt.looper))
        reader = r
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        try {
            codec.configure(fmt, r.surface, null, 0)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw IOException("${i.name}: this phone cannot decode it (${e.message})")
        }
        running = true
        thread = Thread({ decode(ex, codec) }, "tx-file-dec").apply { start() }
    }

    override fun stop() {
        running = false
        thread?.join(2000)
        reader?.close(); reader = null
        readerThread?.quitSafely(); readerThread = null
    }

    /** decoding in an endless loop, every frame released at its time on the [t0] clock */
    private fun decode(ex: MediaExtractor, c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var base = t0
        var inputDone = false
        try {
            while (running) {
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(c.getInputBuffer(i)!!, 0)
                        if (n < 0) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { c.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o < 0) continue
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    // the next pass: one period later on the clock (the sound loops with the same period)
                    c.releaseOutputBuffer(o, false)
                    base += periodUs * 1000
                    ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    c.flush()
                    inputDone = false
                    loops++
                    continue
                }
                val due = base + info.presentationTimeUs * 1000
                while (running) {
                    val wait = due - System.nanoTime()
                    if (wait <= 0) break
                    Thread.sleep(minOf(wait / 1_000_000, 50), if (wait < 50_000_000) (wait % 1_000_000).toInt() else 0)
                }
                // a frame more than 0.25 s late is skipped (the decoder fell behind), except while there is none yet
                c.releaseOutputBuffer(o, System.nanoTime() - due < 250_000_000L || frames == 0L)
            }
        } catch (e: Exception) {
            if (running) error = "video: ${e.message}"
        } finally {
            try { c.stop() } catch (_: Exception) {}
            c.release()
            ex.release()
        }
    }

    @Synchronized
    override fun draw(cv: Canvas, w: Int, h: Int) {
        val s = still
        if (s != null) drawFit(cv, w, h, s, 0) else yuv?.draw { bm -> drawFit(cv, w, h, bm, rotation) } ?: drawFit(cv, w, h, null, 0)
        if (overlay) IdStrip.draw(cv, w, h, call, line, paint)
    }

    /** the whole picture, upright, as large as fits, black bars around */
    private fun drawFit(cv: Canvas, w: Int, h: Int, bm: Bitmap?, rot: Int) {
        cv.drawColor(Color.BLACK)
        if (bm == null) return
        val rw = if (rot % 180 == 0) bm.width else bm.height
        val rh = if (rot % 180 == 0) bm.height else bm.width
        val scale = minOf(w.toFloat() / rw, h.toFloat() / rh)
        matrix.reset()
        matrix.postTranslate(-bm.width / 2f, -bm.height / 2f)
        matrix.postRotate(rot.toFloat())
        matrix.postScale(scale, scale)
        matrix.postTranslate(w / 2f, h / 2f)
        cv.drawBitmap(bm, matrix, paint)
    }

    /** What the file is: a picture or a video, its size, length and whether it has sound. */
    data class Info(val name: String, val video: Boolean, val width: Int, val height: Int, val rotation: Int,
                    val durationUs: Long, val sound: Boolean) {
        override fun toString() = if (video) "%s: video %dx%d %.1f s, %s".format(name, width, height, durationUs / 1e6,
            if (sound) "with sound" else "no sound") else "$name: picture ${width}x$height"
    }

    companion object {
        /** the longest sound kept in memory (mono 16 bit: 48 kHz x 10 min = 58 MB) */
        const val MAX_SOUND_US = 600_000_000L

        fun probe(ctx: Context, uri: Uri): Info {
            val name = try {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            } catch (_: Exception) { null } ?: uri.lastPathSegment ?: "file"
            val type = try { ctx.contentResolver.getType(uri) } catch (e: Exception) { throw IOException("$name: ${e.message}") } ?: ""
            if (type.startsWith("video/")) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, uri)
                    fun int(k: Int) = r.extractMetadata(k)?.toLongOrNull() ?: 0L
                    if (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == null) throw IOException("no video track in $name")
                    return Info(name, true, int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt(),
                        int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt(),
                        int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION).toInt(),
                        int(MediaMetadataRetriever.METADATA_KEY_DURATION) * 1000,
                        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != null)
                } catch (e: IOException) { throw e } catch (e: Exception) { throw IOException("$name: ${e.message}") }
                finally { r.release() }
            }
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            try { ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) } }
            catch (e: Exception) { throw IOException("$name: ${e.message}") }
            if (o.outWidth <= 0) throw IOException("$name: neither a picture nor a video (${type.ifEmpty { "unknown type" }})")
            return Info(name, false, o.outWidth, o.outHeight, 0, 0, false)
        }

        /** a picture, upright (EXIF), its longer side at most [maxSide] pixels, in memory (drawable on any canvas) */
        fun decodePicture(ctx: Context, uri: Uri, maxSide: Int): Bitmap =
            try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, i, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val s = maxOf(i.size.width, i.size.height)
                    if (s > maxSide) d.setTargetSize(i.size.width * maxSide / s, i.size.height * maxSide / s)
                }
            } catch (e: Exception) { throw IOException("picture: ${e.message}") }

        /** The preview while TX is stopped: the picture, or the first frame of the video (upright already). */
        fun still(ctx: Context, uri: Uri, call: String, line: String, overlay: Boolean): Painter {
            val i = probe(ctx, uri)
            val bm = if (i.video) MediaMetadataRetriever().run {
                try { setDataSource(ctx, uri); getFrameAtTime(0) } finally { release() }
            } else decodePicture(ctx, uri, 1280)
            val f = FileSource(ctx, uri, 640, 1, call, line, overlay)
            f.still = bm
            return f
        }

        /**
         * The sound track of [uri] as mono 16-bit PCM at [rate] (channels mixed), exactly [n] samples long (cut, or silence
         * after the end): one loop period of the video.
         */
        fun decodeSound(ctx: Context, uri: Uri, rate: Int, n: Int): ShortArray {
            val ex = MediaExtractor()
            ex.setDataSource(ctx, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw IOException("no sound track")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            var mono = ShortArray(1 shl 16)
            var len = 0
            var srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var float = false
            try {
                c.configure(fmt, null, null, 0)
                c.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                // decode a little more than the samples needed (rate changes after resampling)
                while (true) {
                    if (!inputDone) {
                        val i = c.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val k = ex.readSampleData(c.getInputBuffer(i)!!, 0)
                            if (k < 0 || ex.sampleTime > MAX_SOUND_US) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { c.queueInputBuffer(i, 0, k, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val o = c.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = c.outputFormat
                        srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        continue
                    }
                    if (o < 0) continue
                    val b = c.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                    b.position(info.offset); b.limit(info.offset + info.size)
                    val frames = info.size / (channels * if (float) 4 else 2)
                    if (len + frames > mono.size) mono = mono.copyOf(maxOf(mono.size * 2, len + frames))
                    for (f in 0 until frames) {
                        var s = 0f
                        for (ch in 0 until channels) s += if (float) b.float * 32767f else b.short.toFloat()
                        mono[len++] = (s / channels).toInt().coerceIn(-32768, 32767).toShort()
                    }
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    if (len.toLong() * rate > n.toLong() * srcRate + srcRate) break       // enough for one period
                }
            } finally {
                try { c.stop() } catch (_: Exception) {}
                c.release()
                ex.release()
            }
            return resample(mono, len, srcRate, rate, n)
        }

        /** [len] samples of [src] at [srcRate] -> exactly [n] samples at [dstRate] (linear interpolation, silence after the end) */
        fun resample(src: ShortArray, len: Int, srcRate: Int, dstRate: Int, n: Int): ShortArray {
            val out = ShortArray(n)
            for (k in 0 until n) {
                val x = k.toDouble() * srcRate / dstRate
                val i = x.toInt()
                if (i >= len) break
                val a = src[i].toDouble()
                val b = if (i + 1 < len) src[i + 1].toDouble() else a
                out[k] = Math.round(a + (b - a) * (x - i)).toInt().toShort()
            }
            return out
        }
    }
}

/**
 * The sound of a video file for TX ([Transmitter.SOUND_FILE]): its track decoded into memory once, one loop period long,
 * played in a loop on the clock of [file]'s picture - sample k at the time of position (k / rate) mod period, so the
 * sound stays with the picture across every pass.
 */
class FilePcm(private val ctx: Context, private val uri: Uri, private val file: FileSource) : PcmSource {
    private var pcm = ShortArray(1)
    private var offset = 0L
    private val gen = GeneratedPcm { k, _ -> pcm[((offset + k) % pcm.size).toInt()] }

    override fun start(rate: Int) {
        val n = (file.periodUs * rate / 1_000_000).toInt()
        if (n <= 0) throw IOException("no video to take the sound from")
        if (file.periodUs > FileSource.MAX_SOUND_US) throw IOException("the video is too long for its sound (%d min at most)".format(FileSource.MAX_SOUND_US / 60_000_000))
        pcm = FileSource.decodeSound(ctx, uri, rate, n)
        offset = ((System.nanoTime() - file.t0) * rate / 1_000_000_000L).mod(n.toLong())
        gen.start(rate)
    }

    override fun read(buf: ShortArray, n: Int) = gen.read(buf, n)
    override fun stop() = gen.stop()
}
