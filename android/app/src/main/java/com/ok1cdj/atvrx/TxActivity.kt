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
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

/**
 * TX: a XIAO ESP32-C3 with the ESP32-DATV(-SA) firmware on USB-OTG sends the test card or the camera live as DVB-S2
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
            synchronized(s.log) { s.log.toList() }.forEach { log(it) }
            s.onLog = { log(it) }
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
    private lateinit var videoKbps: EditText
    private lateinit var fps: EditText
    private lateinit var startStop: Button
    private lateinit var info: TextView
    private lateinit var logView: TextView
    private lateinit var preview: ImageView
    private val rates = listOf(40_000, 50_000, 66_666, 100_000, 125_000, 250_000, 333_000, 500_000)

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) start() else log("USB permission denied")
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
        info = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f; setPadding(0, 12, 0, 8) }
        root.addView(info)

        freq = edit(prefs.getString("freq", "2340.000")!!, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        rate = spinner(rates.map { "%.1f kS/s".format(it / 1e3) }, "%.1f kS/s".format(prefs.getInt("sr", 50_000) / 1e3))
        root.addView(row(label("MHz"), freq, label("SR"), rate))
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
        pilots = CheckBox(this).apply { text = "pilots"; isChecked = prefs.getBoolean("pilots", true) }
        root.addView(row(constel, fec, frame, pilots))
        amp = edit(prefs.getInt("amp", 300).toString(), InputType.TYPE_CLASS_NUMBER)
        call = edit(prefs.getString("call", "")!!, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS).apply { hint = "callsign" }
        root.addView(row(label("call"), call, label("AMP"), amp))
        source = spinner(listOf(Transmitter.SOURCE_CARD, Transmitter.SOURCE_BACK, Transmitter.SOURCE_FRONT),
            prefs.getString("source", Transmitter.SOURCE_CARD)!!)
        videoKbps = edit(prefs.getInt("videoKbps", 0).takeIf { it > 0 }?.toString() ?: "", InputType.TYPE_CLASS_NUMBER).apply { hint = "auto" }
        fps = edit(prefs.getInt("fps", 0).takeIf { it > 0 }?.toString() ?: "", InputType.TYPE_CLASS_NUMBER).apply { hint = "auto" }
        root.addView(row(label("picture"), source))
        root.addView(row(label("video kb/s"), videoKbps, label("fps"), fps))
        startStop = button("Start TX") { if (tx == null) start() else stop() }
        root.addView(startStop, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8 })
        root.addView(TextView(this).apply {
            text = "Licensed radio amateurs only. AMP 300 = 0 dB (a few mW), 13 = −27 dB; 8PSK up to 420. Keep the C3 and " +
                "its antenna away from the phone. TX goes on with the screen off (Stop in the notification)."
            textSize = 11f; setPadding(0, 8, 0, 0)
        })
        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
        root.addView(ScrollView(this).apply { addView(logView) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = 12 })

        setContentView(root)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            v.setPadding(24 + bars.left, 24 + bars.top, 24 + bars.right, 24 + bars.bottom)
            insets
        }
        val filter = IntentFilter(permissionAction)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(permissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(permissionReceiver, filter)
        log(device()?.let { "ESP32 (303a:1001) connected – the C3 is told apart from the S3 on Start" } ?: "no ESP32 (303a:1001) on USB")
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

    private fun device() = usbManager.deviceList.values.firstOrNull { it.vendorId == 0x303A && it.productId == 0x1001 }

    private fun start() {
        if (tx != null) return
        val svc = service ?: return log("service not ready")
        val dev = device() ?: return log("no ESP32 (303a:1001) on USB")
        if (!usbManager.hasPermission(dev)) {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            usbManager.requestPermission(dev, PendingIntent.getBroadcast(this, 0, Intent(permissionAction).setPackage(packageName), flags))
            return
        }
        val src = source.selectedItem as String
        if (src != Transmitter.SOURCE_CARD && checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        val mhz = freq.text.toString().replace(',', '.').toDoubleOrNull() ?: return log("frequency?")
        if (mhz < 2300.0 || mhz > 2450.0) return log("the C3 transmits only in 2300..2450 MHz")
        val a = amp.text.toString().toIntOrNull()?.takeIf { it in 1..480 } ?: return log("AMP 1..480")
        val cs = call.text.toString().trim().uppercase()
        if (cs.isEmpty()) return log("callsign?")
        val sr = rates[rate.selectedItemPosition]
        val c = constel.selectedItem as String
        val shortFrame = frame.selectedItem == "short"
        val r = fec.selectedItem as String
        if (shortFrame && r == "9/10") return log("no 9/10 with short frames")
        if (c == "8PSK" && sr < 125_000) log("8PSK below 125 kS/s: the C3 uses a slower DAC loop")
        val vk = videoKbps.text.toString().toIntOrNull() ?: 0
        val f = fps.text.toString().toIntOrNull() ?: 0
        prefs.edit().putString("freq", freq.text.toString()).putInt("sr", sr).putString("const", c).putString("fec", r)
            .putString("frame", frame.selectedItem as String).putBoolean("pilots", pilots.isChecked)
            .putInt("amp", a).putString("call", cs).putString("source", src).putInt("videoKbps", vk).putInt("fps", f).apply()
        val cfg = Transmitter.Config(Math.round(mhz * 1000), sr, r, shortFrame, pilots.isChecked, a, cs, cs, "$cs ESP32-ATV",
            src, c, vk, f)
        Receiver.leandvbWarning(c, r, if (shortFrame) Receiver.FRAMES_SHORT else Receiver.FRAMES_NORMAL)
            ?.let { log(it.replace("leandvb does not decode", "leandvb-based receivers (this app, rx_dvbs2.py) do not decode")) }
        svc.startTx(cfg, dev)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        if (code == REQ_CAMERA) {
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) start() else log("camera permission denied")
        }
    }

    private fun stop() = service?.stopTx()

    private var previewBitmap: Bitmap? = null
    private val tick = object : Runnable {
        override fun run() {
            val t = tx
            val s = t?.status
            startStop.text = if (t == null) "Start TX" else "Stop TX"
            info.text = if (s == null) "stopped" else buildString {
                val secs = if (s.since > 0) (System.nanoTime() - s.since) / 1e9 else 0.0
                append("${s.state}  %.0f s  LO ${s.lo}\n".format(secs))
                append("%.1f Bd  TS %.1f kb/s  video %d kb/s\n".format(s.baud, s.tsRate / 1e3, s.videoKbps))
                append("C3 buffer %d / 3000  underruns %d  PLFRAMEs %d\n".format(s.fill, s.underruns, s.plframes))
                t.mux?.let { m ->
                    val total = m.time * m.rate / 1504
                    append("TS: video %.1f kb/s  null %.0f %%  queued %d B  dropped %d\n".format(
                        if (m.time > 0) m.videoBytes * 8 / m.time / 1e3 else 0.0,
                        if (total > 0) 100.0 * m.nullPackets / total else 0.0, m.queuedBytes, m.droppedAus))
                }
                t.video?.let { append("H.264 ${it.width}x${it.height} ${it.fps} fps, frames ${it.frames}${it.error?.let { e -> "  $e" } ?: ""}") }
                t.camera?.let { append("\ncamera ${it.size}  frames ${it.frames}${it.error?.let { e -> "  $e" } ?: ""}") }
            }
            ui.postDelayed(this, 1000)
        }
    }

    /** preview: the picture source drawn again, 5 times a second */
    private val previewTick = object : Runnable {
        override fun run() {
            tx?.video?.painter?.let { p ->
                val b = previewBitmap ?: Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888).also { previewBitmap = it }
                p.draw(Canvas(b), 640, 360)
                preview.setImageBitmap(b)
                preview.invalidate()
            }
            ui.postDelayed(this, 200)
        }
    }

    private fun log(s: String) { logView.append(s + "\n") }

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
        private const val REQ_CAMERA = 2
        private const val REQ_NOTIFY = 3
    }
}
