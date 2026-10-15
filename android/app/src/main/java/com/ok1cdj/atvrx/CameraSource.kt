package com.ok1cdj.atvrx

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Typeface
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import java.util.concurrent.Executor

/**
 * Camera pictures for TX (Camera2, no extra libraries): YUV frames from an ImageReader, converted to a Bitmap, drawn
 * centre-cropped to the video frame with the callsign in a strip at the bottom (the station's identification). The
 * picture is turned upright for the way the phone is held ([deviceDegrees], from an OrientationEventListener):
 * landscape fills the frame, portrait shows a band of it.
 */
class CameraSource(private val ctx: Context, private val back: Boolean, private val maxWidth: Int,
                   @Volatile var call: String, @Volatile var line: String) : Painter {
    private val thread = HandlerThread("tx-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    @Volatile private var bitmap: Bitmap? = null        // the newest frame (swapped whole, never drawn into while shown)
    private var spare: Bitmap? = null
    private var argb = IntArray(0)
    @Volatile var error: String? = null; private set
    @Volatile var size = ""; private set
    @Volatile var frames = 0L; private set
    /** how the phone is held: 0 natural (portrait), 90, 180, 270 degrees clockwise */
    @Volatile var deviceDegrees = 0
    private var sensorDegrees = 90

    @SuppressLint("MissingPermission")                  // TxActivity asks for CAMERA before it starts this
    fun start() {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val facing = if (back) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing }
            ?: throw IllegalStateException("no ${if (back) "back" else "front"} camera")
        val chars = cm.getCameraCharacteristics(id)
        sensorDegrees = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        // the smallest YUV size at least maxWidth wide (4:3 or 16:9 both fine: the picture is centre-cropped)
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888).sortedBy { it.width * it.height }
        val sz = sizes.firstOrNull { it.width >= maxWidth && it.height * 16 >= it.width * 9 - 16 } ?: sizes.last()
        size = "${sz.width}x${sz.height}"
        val r = ImageReader.newInstance(sz.width, sz.height, ImageFormat.YUV_420_888, 3)
        r.setOnImageAvailableListener({ ir ->
            val img = try { ir.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            try { convert(img) } catch (e: Exception) { error = "camera: ${e.message}" } finally { img.close() }
        }, handler)
        reader = r
        cm.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                device = d
                val exec = Executor { handler.post(it) }
                d.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                    listOf(OutputConfiguration(r.surface)), exec, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session = s
                            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(r.surface)
                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(15, 30))
                            }.build()
                            try { s.setRepeatingRequest(req, null, handler) } catch (e: Exception) { error = "camera: ${e.message}" }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { error = "camera: session failed" }
                    }))
            }
            override fun onDisconnected(d: CameraDevice) { error = "camera disconnected"; d.close() }
            override fun onError(d: CameraDevice, e: Int) { error = "camera error $e"; d.close() }
        }, handler)
    }

    fun stop() {
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        reader?.close()
        thread.quitSafely()
        session = null; device = null; reader = null
    }

    /** YUV_420_888 -> ARGB (BT.601, limited range), into the spare bitmap, then swapped in. */
    private fun convert(img: Image) {
        val w = img.width; val h = img.height
        if (argb.size != w * h) argb = IntArray(w * h)
        val yp = img.planes[0]; val up = img.planes[1]; val vp = img.planes[2]
        val yb = yp.buffer; val ub = up.buffer; val vb = vp.buffer
        val yrs = yp.rowStride; val urs = up.rowStride; val ups = up.pixelStride; val vrs = vp.rowStride; val vps = vp.pixelStride
        var o = 0
        for (y in 0 until h) {
            val yo = y * yrs; val uo = (y shr 1) * urs; val vo = (y shr 1) * vrs
            for (x in 0 until w) {
                val Y = ((yb.get(yo + x).toInt() and 0xFF) - 16).coerceAtLeast(0) * 1192
                val U = (ub.get(uo + (x shr 1) * ups).toInt() and 0xFF) - 128
                val V = (vb.get(vo + (x shr 1) * vps).toInt() and 0xFF) - 128
                val r = (Y + 1634 * V) shr 10
                val g = (Y - 833 * V - 400 * U) shr 10
                val b = (Y + 2066 * U) shr 10
                argb[o++] = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
            }
        }
        val bm = spare?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bm.setPixels(argb, 0, w, 0, 0, w, h)
        synchronized(this) { spare = bitmap; bitmap = bm }
        frames++
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = android.graphics.Matrix()

    @Synchronized
    override fun draw(cv: Canvas, w: Int, h: Int) {
        val bm = bitmap
        if (bm == null) {
            cv.drawColor(Color.DKGRAY)
        } else {
            // upright: the sensor's mounting and the way the phone is held (the JPEG orientation rule); centre crop
            val rot = if (back) (sensorDegrees + deviceDegrees) % 360 else (sensorDegrees - deviceDegrees + 360) % 360
            val rw = if (rot % 180 == 0) bm.width else bm.height
            val rh = if (rot % 180 == 0) bm.height else bm.width
            val scale = maxOf(w.toFloat() / rw, h.toFloat() / rh)
            matrix.reset()
            matrix.postTranslate(-bm.width / 2f, -bm.height / 2f)
            matrix.postRotate(rot.toFloat())
            matrix.postScale(scale, scale)
            matrix.postTranslate(w / 2f, h / 2f)
            cv.drawColor(Color.BLACK)
            cv.drawBitmap(bm, matrix, paint)
        }
        // identification strip
        val sh = h * 0.13f
        paint.color = Color.argb(170, 0, 0, 0)
        cv.drawRect(0f, h - sh, w.toFloat(), h.toFloat(), paint)
        paint.color = Color.WHITE
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = sh * 0.72f
        paint.textAlign = Paint.Align.LEFT
        cv.drawText(call, h * 0.03f, h - sh * 0.24f, paint)
        paint.typeface = Typeface.DEFAULT
        paint.textSize = sh * 0.45f
        paint.textAlign = Paint.Align.RIGHT
        cv.drawText(line, w - h * 0.03f, h - sh * 0.3f, paint)
    }
}
