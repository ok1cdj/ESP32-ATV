package com.ok1cdj.atvrx

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

/**
 * TX: a XIAO ESP32-C3 with the ESP32-DATV(-SA) firmware on USB-OTG sends the test card, the camera or a picture / video
 * file live as DVB-S2
 * QPSK or 8PSK ([Transmitter], owned by [TxService] so that it goes on with the screen off). Licensed radio amateurs
 * only; the firmware accepts only 2300..2450 MHz.
 */
class TxActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("tx", MODE_PRIVATE) }
    private val usbManager by lazy { getSystemService(USB_SERVICE) as UsbManager }
    private val permissionAction by lazy { "$packageName.TX_USB_PERMISSION" }
    private val ui = Handler(Looper.getMainLooper())
    private var service: TxService? = null
    private val tx get() = service?.tx
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            val s = (b as TxService.LocalBinder).service
            service = s
            synchronized(s.log) { s.log.toList() }.forEach { show(it) }
            s.onLog = { show(it) }
        }
        override fun onServiceDisconnected(n: ComponentName) { service = null }
    }
    /** how the phone is held, for turning the camera picture upright */
    private val orientation by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(deg: Int) {
                if (deg != ORIENTATION_UNKNOWN) tx?.camera?.deviceDegrees = (deg + 45) / 90 * 90 % 360
            }
        }
    }

    private lateinit var freq: EditText
    private lateinit var rate: Spinner
    private lateinit var constel: Spinner
    private lateinit var fec: Spinner
    private lateinit var frame: Spinner
    private lateinit var pilots: CheckBox
    private lateinit var source: Spinner
    private lateinit var amp: EditText
    private lateinit var call: EditText
    private lateinit var videoKbps: Spinner
    private lateinit var fps: Spinner
    private lateinit var sound: Spinner
    private lateinit var audioKbps: Spinner
    private val audioChoices = listOf(0, 8, 12, 16, 24, 32, 48, 64)    // 0 = auto
    private val kbpsChoices = listOf(0, 50, 75, 100, 150, 200, 300, 400)       // 0 = auto
    private val loopChoices = listOf(10, 20, 30, 60, 0)                 // 0 = the length of the video file
    private lateinit var overlay: CheckBox
    private lateinit var fileLabel: TextView
    private var fileUri: String = ""
    /** the preview of the chosen file while TX is stopped (decoded off the UI thread) */
    @Volatile private var filePreview: Painter? = null
    private var filePreviewKey = ""
    private lateinit var loopSeconds: Spinner
    private lateinit var savePhase: TextView
    private lateinit var saveBar: ProgressBar
    private val fpsChoices = listOf(0, 2, 5, 10, 15, 20, 25, 30)
    private lateinit var startStop: Button
    private lateinit var info: TextView
    private lateinit var logView: TextView
    private lateinit var preview: ImageView
    private val rates = listOf(40_000, 50_000, 66_666, 100_000, 125_000, 250_000, 333_000, 500_000)

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) afterPermission() else log("USB permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // back to RX: a running TX stays in its service (the notification stops it); RX needs the S3 on the port
        root.addView(modeSwitch(this, tx = true, onRx = {
            if (tx != null) log("TX still running – stop it before receiving (the port holds the C3)")
            startActivity(Intent(this, MainActivity::class.java)); finish()
        }, onTx = {}), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 12 })
        val w = resources.displayMetrics.widthPixels - 48
        preview = ImageView(this)
        root.addView(preview, LinearLayout.LayoutParams(MATCH_PARENT, w * 9 / 16))
        // below the preview everything scrolls: nothing is cut off on a small screen
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        info = TextView(this).apply { typeface = Typeface.MONOSPACE; setPadding(0, 12, 0, 8); fitStatusText(this) }
        body.addView(info)

        freq = edit(prefs.getString("freq", MainActivity.DEFAULT_FREQ)!!, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        rate = spinner(rates.map { "%.1f kS/s".format(it / 1e3) }, "%.1f kS/s".format(prefs.getInt("sr", 50_000) / 1e3))
        body.addView(row(label("MHz"), freq, label("SR"), rate))
        constel = spinner(listOf("QPSK", "8PSK"), prefs.getString("const", "QPSK")!!)
        fec = spinner(rateList(constel.selectedItem as String), prefs.getString("fec", "3/4")!!)
        constel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(a: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val keep = fec.selectedItem as String?
                val list = rateList(constel.selectedItem as String)
                fec.adapter = adapter(list)
                fec.setSelection(list.indexOf(keep).takeIf { it >= 0 } ?: list.indexOf("3/4").coerceAtLeast(0))
            }
            override fun onNothingSelected(a: AdapterView<*>?) {}
        }
        frame = spinner(listOf("normal", "short"), prefs.getString("frame", "short")!!)
        pilots = CheckBox(this).apply { text = "pilots"; isSingleLine = true; isChecked = prefs.getBoolean("pilots", true) }
        body.addView(row(constel, fec, frame))
        amp = edit(prefs.getInt("amp", 300).toString(), InputType.TYPE_CLASS_NUMBER)
        call = edit(prefs.getString("call", "")!!, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS).apply { hint = "callsign" }
        body.addView(row(label("call"), call, label("AMP"), amp))
        source = spinner(Transmitter.SOURCES, prefs.getString("source", Transmitter.SOURCE_CARD)!!)
        // auto (from the TS rate) or a fixed value, chosen from a list so that "auto" is always one tap away
        fun choice(v: Int) = if (v == 0) "auto" else v.toString()
        videoKbps = spinner(kbpsChoices.map(::choice), choice(prefs.getInt("videoKbps", 0)))
        fps = spinner(fpsChoices.map(::choice), choice(prefs.getInt("fps", 0)))
        body.addView(row(label("picture"), source, pilots))
        // the file for the picture: the system's picker (no storage permission), kept across restarts
        fileUri = prefs.getString("fileUri", "")!!
        fileLabel = TextView(this).apply { textSize = 13f; isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }
        overlay = CheckBox(this).apply { text = "callsign"; isSingleLine = true; isChecked = prefs.getBoolean("overlay", true) }
        body.addView(row(button("File…") { chooseFile() }, fileLabel, overlay))
        showFile()
        body.addView(row(label("video kb/s"), videoKbps, label("fps"), fps))
        // sound: auto sends none below 80 kb/s of TS (the picture needs the bits), a value by hand always
        sound = spinner(Transmitter.SOUNDS, prefs.getString("sound", Transmitter.SOUND_OFF)!!)
        audioKbps = spinner(audioChoices.map(::choice), choice(prefs.getInt("audioKbps", 0)))
        body.addView(row(label("sound"), sound, label("kb/s"), audioKbps))
        startStop = button("Start TX") { if (tx == null) start() else stop() }
        body.addView(startStop, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8 })
        // a stand-alone beacon: the C3 keeps transmitting this loop by itself (QPSK, up to ~333 kS/s)
        fun loopName(v: Int) = if (v == 0) "video" else "$v s"
        loopSeconds = spinner(loopChoices.map(::loopName), loopName(prefs.getInt("loopSeconds", 20)))
        val save = button("Save as beacon in the C3") { saveBeacon() }.apply {
            background = GradientDrawable().apply { setColor(Color.rgb(0x5A, 0x3A, 0x8A)); cornerRadius = 16f }
        }
        body.addView(row(save, loopSeconds), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8 })
        savePhase = TextView(this).apply { textSize = 14f; visibility = android.view.View.GONE }
        saveBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; visibility = android.view.View.GONE }
        body.addView(savePhase)
        body.addView(saveBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        body.addView(TextView(this).apply {
            text = "Licensed radio amateurs only. AMP 300 = 0 dB (a few mW), 13 = −27 dB; 8PSK up to 420. Keep the C3 and " +
                "its antenna away from the phone. TX goes on with the screen off (Stop in the notification)."
            textSize = 11f; setPadding(0, 8, 0, 0)
        })
        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
        body.addView(logView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 12 })

        setContentView(root)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            v.setPadding(24 + bars.left, 24 + bars.top, 24 + bars.right, 24 + bars.bottom)
            insets
        }
        val filter = IntentFilter(permissionAction)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(permissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(permissionReceiver, filter)
        log(UsbBoards.describe(this, usbManager))
        bindService(Intent(this, TxService::class.java), connection, BIND_AUTO_CREATE)
        ui.post(tick)
        ui.post(previewTick)
        if (orientation.canDetectOrientation()) orientation.enable()
    }

    override fun onDestroy() {
        // TX stays with the service; only the view goes
        service?.onLog = null
        unbindService(connection)
        ui.removeCallbacks(tick)
        ui.removeCallbacks(previewTick)
        orientation.disable()
        unregisterReceiver(permissionReceiver)
        super.onDestroy()
    }

    private fun rateList(c: String) = if (c == "8PSK") S2Encoder.PSK8_RATES else S2Encoder.QPSK_RATES

    /** The settings of the form with USB / camera permission in place; null (and a log line or a permission request) if not. */
    private fun config(onGranted: () -> Unit): Pair<Transmitter.Config, android.hardware.usb.UsbDevice>? {
        afterPermission = onGranted
        // the C3: the only board on USB, or the one of the two on a hub that is the transmitter
        val dev = when (val p = UsbBoards.pick(this, UsbBoards.Role.C3) { afterPermission() }) {
            is UsbBoards.Pick.Found -> p.dev
            is UsbBoards.Pick.Ask -> { UsbBoards.requestPermission(this, p.dev, permissionAction); return null }
            is UsbBoards.Pick.Wait -> return null.also { log("identifying the boards on USB…") }
            is UsbBoards.Pick.Missing -> return null.also { log(p.message) }
        }
        val src = source.selectedItem as String
        if (isCamera(src) && checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), REQ_CAMERA)
            return null
        }
        var snd = sound.selectedItem as String
        if (src == Transmitter.SOURCE_FILE) {
            if (fileUri.isEmpty()) return null.also { log("choose the picture or video: File…") }
            val i = try { FileSource.probe(this, android.net.Uri.parse(fileUri)) } catch (e: Exception) { return null.also { log("file: ${e.message}") } }
            log("file ${i}")
            if (snd == Transmitter.SOUND_FILE && !i.sound) { log("no sound in ${i.name}: sending none"); snd = Transmitter.SOUND_OFF }
        } else if (snd == Transmitter.SOUND_FILE) return null.also { log("sound from the video: choose picture → ${Transmitter.SOURCE_FILE}") }
        if ((snd == Transmitter.SOUND_MIC || snd == Transmitter.SOUND_MIC_CW) &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return null
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        val mhz = freq.text.toString().replace(',', '.').toDoubleOrNull() ?: return null.also { log("frequency?") }
        if (mhz < 2300.0 || mhz > 2450.0) return null.also { log("the C3 transmits only in 2300..2450 MHz") }
        UsbBoards.serial(usbManager, dev)?.let { mac ->
            if (DcStore.all(this, mac).isNotEmpty() && DcStore.near(this, mac, mhz, CAL_NEAR_MHZ) == null)
                log("no calibration of the C3 within %.0f MHz of %.3f: RX screen, menu → Transmitter calibration".format(CAL_NEAR_MHZ, mhz))
        }
        val a = amp.text.toString().toIntOrNull()?.takeIf { it in 1..480 } ?: return null.also { log("AMP 1..480") }
        val cs = call.text.toString().trim().uppercase()
        if (cs.isEmpty()) return null.also { log("callsign?") }
        val sr = rates[rate.selectedItemPosition]
        val c = constel.selectedItem as String
        val shortFrame = frame.selectedItem == "short"
        val r = fec.selectedItem as String
        if (shortFrame && r == "9/10") return null.also { log("no 9/10 with short frames") }
        if (c == "8PSK" && sr < 125_000) log("8PSK below 125 kS/s: the C3 uses a slower DAC loop")
        val vk = kbpsChoices[videoKbps.selectedItemPosition]
        val f = fpsChoices[fps.selectedItemPosition]
        val ak = audioChoices[audioKbps.selectedItemPosition]
        prefs.edit().putString("freq", freq.text.toString()).putInt("sr", sr).putString("const", c).putString("fec", r)
            .putString("frame", frame.selectedItem as String).putBoolean("pilots", pilots.isChecked)
            .putInt("amp", a).putString("call", cs).putString("source", src).putInt("videoKbps", vk).putInt("fps", f)
            .putString("sound", sound.selectedItem as String).putInt("audioKbps", ak).putBoolean("overlay", overlay.isChecked).apply()
        val cfg = Transmitter.Config(Math.round(mhz * 1000), sr, r, shortFrame, pilots.isChecked, a, cs, cs, "$cs ESP32-ATV",
            src, c, vk, f, snd, ak, fileUri, overlay.isChecked)
        return cfg to dev
    }

    private var afterPermission: () -> Unit = {}

    private fun start() {
        if (tx != null || saving) return
        val svc = service ?: return log("service not ready")
        val (cfg, dev) = config(::start) ?: return
        svc.startTx(cfg, dev)
    }

    @Volatile private var saving = false

    /** Save as beacon: record the picture with these settings as a loop and store it in the C3 (LoopMaker). */
    private fun saveBeacon() {
        if (tx != null) return log("stop TX first")
        if (saving) return
        val (cfg, dev) = config(::saveBeacon) ?: return
        if (cfg.const != "QPSK") return log("the stand-alone loop is QPSK only")
        val choice = loopChoices[loopSeconds.selectedItemPosition]
        prefs.edit().putInt("loopSeconds", choice).apply()
        // "video": as long as the video file (none of it cut)
        val video = if (choice == 0) {
            val i = if (cfg.source != Transmitter.SOURCE_FILE) null
                else try { FileSource.probe(this, android.net.Uri.parse(cfg.fileUri)) } catch (e: Exception) { return log("file: ${e.message}") }
            if (i == null || !i.video) return log("loop \"video\": the picture must be a video file")
            i
        } else null
        val secs = video?.let { it.durationUs / 1e6 } ?: choice.toDouble()
        saving = true
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        savePhase.visibility = android.view.View.VISIBLE; saveBar.visibility = android.view.View.VISIBLE
        savePhase.text = "Starting…"; saveBar.progress = 0
        val maker = LoopMaker(this, usbManager, dev, cfg, secs, { s -> runOnUiThread { log(s) } }, { text, f ->
            runOnUiThread { savePhase.text = text; saveBar.progress = (f * 1000).toInt().coerceIn(0, 1000) }
        }, wholeVideo = video != null)
        loopMaker = maker
        Thread {
            try { maker.run() } catch (e: Exception) { runOnUiThread { log("beacon: ${e.message}"); savePhase.text = "Failed: ${e.message}" } }
            saving = false
            loopMaker = null
            runOnUiThread { window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }.start()
    }
    @Volatile private var loopMaker: LoopMaker? = null

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        if (code == REQ_CAMERA) {
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) afterPermission() else log("camera permission denied")
        }
        if (code == REQ_AUDIO) {
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) afterPermission() else log("microphone permission denied")
        }
    }

    private fun stop() = service?.stopTx()

    private fun isCamera(src: String) = src == Transmitter.SOURCE_BACK || src == Transmitter.SOURCE_FRONT

    private fun chooseFile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
        }, REQ_FILE)
    }

    @Deprecated("the platform Activity has no Activity Result API")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        val uri = data?.data
        if (code != REQ_FILE || result != RESULT_OK || uri == null) return
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (e: Exception) { log("file: cannot keep the permission (${e.message}); choose it again after a restart") }
        fileUri = uri.toString()
        prefs.edit().putString("fileUri", fileUri).putString("source", Transmitter.SOURCE_FILE).apply()
        source.setSelection(Transmitter.SOURCES.indexOf(Transmitter.SOURCE_FILE))
        showFile()
        try { log("file ${FileSource.probe(this, uri)}") } catch (e: Exception) { log("file: ${e.message}") }
    }

    /** the chosen file's name next to the button */
    private fun showFile() {
        fileLabel.text = if (fileUri.isEmpty()) "no file" else try { FileSource.probe(this, android.net.Uri.parse(fileUri)).name }
            catch (_: Exception) { "file not readable" }
    }

    /** the preview of the file with the current callsign and switch, decoded once in the background per change */
    private fun filePreview(): Painter? {
        val cs = call.text.toString().trim().uppercase().ifEmpty { "CALLSIGN" }
        val line = "${freq.text} MHz  DVB-S2 ${constel.selectedItem} ${fec.selectedItem}"
        val key = "$fileUri|$cs|$line|${overlay.isChecked}"
        if (key != filePreviewKey && fileUri.isNotEmpty()) {
            filePreviewKey = key
            val uri = android.net.Uri.parse(fileUri); val ov = overlay.isChecked
            Thread {
                filePreview = try { FileSource.still(this, uri, cs, line, ov) } catch (_: Exception) { null }
            }.start()
        }
        return filePreview.takeIf { fileUri.isNotEmpty() }
    }

    private var previewBitmap: Bitmap? = null
    private val tick = object : Runnable {
        override fun run() {
            val t = tx
            val s = t?.status
            startStop.text = if (t == null) "Start TX" else "Stop TX"
            info.text = if (s == null) "stopped" else buildString {
                val secs = if (s.since > 0) (System.nanoTime() - s.since) / 1e9 else 0.0
                // lines of at most STATUS_CHARS characters (fitStatusText)
                append("%s %.0f s  LO %s\n".format(s.state, secs, s.lo.toDoubleOrNull()?.let { "%.3f MHz".format(it / 1e6) } ?: "–"))
                append("%.0f Bd  TS %.1f kb/s  video %d%s\n".format(s.baud, s.tsRate / 1e3, s.videoKbps,
                    if (s.audioKbps > 0) " + ${s.audioKbps}" else ""))
                append("C3 buf %d/3000  under %d  frames %d\n".format(s.fill, s.underruns, s.plframes))
                t.mux?.let { m ->
                    val total = m.time * m.rate / 1504
                    append("video %.0f kb/s null %.0f%% q %d drop %d\n".format(
                        if (m.time > 0) m.videoBytes * 8 / m.time / 1e3 else 0.0,
                        if (total > 0) 100.0 * m.nullPackets / total else 0.0, m.queuedBytes, m.droppedAus))
                }
                t.video?.let { append("H.264 ${it.width}x${it.height} ${it.fps} fps  ${it.frames}${it.error?.let { e -> "  $e" } ?: ""}") }
                t.audio?.let { append("\n${it.description}  ${it.frames}${it.error?.let { e -> "  $e" } ?: ""}") }
                t.camera?.let { append("\ncamera ${it.size}  ${it.frames}${it.error?.let { e -> "  $e" } ?: ""}") }
                t.file?.let { append("\nfile ${it.frames} frames  pass ${it.loops + 1}${it.error?.let { e -> "  $e" } ?: ""}") }
            }
            ui.postDelayed(this, 1000)
        }
    }

    /** preview: the picture source drawn again, 5 times a second */
    private val previewTick = object : Runnable {
        override fun run() {
            // while TX runs the picture being sent; while stopped the chosen file or the test card with the current settings
            val p = tx?.video?.painter ?: loopMaker?.painter ?: (if (source.selectedItem == Transmitter.SOURCE_FILE) filePreview() else null) ?: TestCard(call.text.toString().trim().uppercase().ifEmpty { "CALLSIGN" },
                "ESP32-ATV TX  ${freq.text} MHz", "DVB-S2 ${constel.selectedItem} ${fec.selectedItem}" +
                    "${if (frame.selectedItem == "short") " short" else ""}  ${rate.selectedItem}")
            val b = previewBitmap ?: Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888).also { previewBitmap = it }
            p.draw(Canvas(b), 640, 360)
            preview.setImageBitmap(b)
            preview.invalidate()
            ui.postDelayed(this, 200)
        }
    }

    /** A line of this screen: into the app's log and on the page. */
    private fun log(s: String) {
        AppLog.i("TX", s)
        show(s)
    }

    /** The log at the bottom of the page: the last 60 lines. */
    private fun show(s: String) {
        logLines.addLast(s)
        while (logLines.size > 60) logLines.removeFirst()
        logView.text = logLines.joinToString("\n")
    }
    private val logLines = ArrayDeque<String>()

    private fun edit(v: String, type: Int) = EditText(this).apply { inputType = type; setText(v) }
    private fun label(t: String) = TextView(this).apply { text = t; setPadding(8, 0, 8, 0) }
    // the closed spinner with little padding and one line, as on the RX screen, so that "QPSK" or "normal" fits
    private fun adapter(items: List<String>) = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
        override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup) =
            (super.getView(position, convertView, parent) as TextView).apply { setPadding(4, 0, 0, 0); isSingleLine = true }
    }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    private fun spinner(items: List<String>, sel: String) = Spinner(this).apply {
        adapter = adapter(items)
        setSelection(items.indexOf(sel).coerceAtLeast(0))
    }
    private fun row(vararg v: android.view.View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        v.forEach { addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, if (it is TextView && it !is EditText && it !is Button && it !is CheckBox) 0f else 1f)) }
    }
    private fun button(t: String, onClick: (android.view.View) -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setTextColor(Color.WHITE)
        background = GradientDrawable().apply { setColor(Color.rgb(0xA8, 0x2E, 0x1E)); cornerRadius = 16f }
        setOnClickListener(onClick)
    }

    companion object {
        const val CAL_NEAR_MHZ = 10.0           // a calibration this near counts (the leakage changes little with frequency)
        private const val REQ_CAMERA = 2
        private const val REQ_NOTIFY = 3
        private const val REQ_AUDIO = 4
        private const val REQ_FILE = 5
    }
}
