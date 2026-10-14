package com.ok1cdj.atvrx

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

/**
 * Receiver screen: frequency, symbol rate, gain, DVB-S2 modulation / code rate / frame size, start / stop; the picture;
 * lock, MER / CNR, carrier offset with drift tracking, frames, TS, service and video.
 */
class MainActivity : Activity() {
    private val rates = listOf(40_000, 50_000, 66_666, 100_000, 125_000, 250_000, 333_000, 400_000, 500_000)

    private lateinit var freq: EditText
    private lateinit var rate: Spinner
    private lateinit var gain: EditText
    private lateinit var constel: Spinner
    private lateinit var codeRate: Spinner
    private lateinit var frames: Spinner
    private val consts = listOf("QPSK", "8PSK")
    private val frameChoices = listOf(Receiver.FRAMES_NORMAL, Receiver.FRAMES_SHORT, Receiver.FRAMES_BOTH)
    private lateinit var startStop: Button
    private lateinit var info: TextView
    private lateinit var constView: ConstellationView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var surface: SurfaceView
    private var surfaceReady = false

    private var receiver: Receiver? = null
    private var video: VideoDecoder? = null
    private var startedAt = 0L
    private val tsHistory = ArrayDeque<Pair<Long, Long>>()   // (time, TS bytes): rate over the last 10 s
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("rx", MODE_PRIVATE) }

    private val usbManager by lazy { getSystemService(USB_SERVICE) as UsbManager }
    private val permissionAction by lazy { "$packageName.USB_PERMISSION" }
    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) start() else say("USB permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        surface = SurfaceView(this)
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { surfaceReady = true }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) { surfaceReady = false; stop() }
        })
        val w = resources.displayMetrics.widthPixels - 48
        root.addView(surface, LinearLayout.LayoutParams(MATCH_PARENT, w * 9 / 16))

        info = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f; setPadding(0, 12, 12, 12) }
        constView = ConstellationView(this)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(info, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(constView, LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.3).toInt(), WRAP_CONTENT)
                .apply { topMargin = 12 })
        })

        freq = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(prefs.getString("freq", "2300.500")); hint = "MHz"
        }
        rate = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, rates.map { "%.1f kS/s".format(it / 1e3) })
            setSelection(rates.indexOf(prefs.getInt("sr", 50_000)).coerceAtLeast(0))
        }
        gain = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(prefs.getInt("gain", 30).toString()); hint = "dB"
        }
        root.addView(row(label("MHz"), freq, label("SR"), rate))
        constel = spinner(consts, prefs.getString("const", "QPSK")!!)
        codeRate = spinner(rateList(constel.selectedItem as String), prefs.getString("cr", "3/4")!!)
        constel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(a: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val keep = codeRate.selectedItem as String?
                val list = rateList(consts[pos])
                codeRate.adapter = adapter(list)
                val i = list.indexOf(keep)
                codeRate.setSelection(if (i >= 0) i else maxOf(0, list.indexOf("3/4")))
            }
            override fun onNothingSelected(a: android.widget.AdapterView<*>?) {}
        }
        frames = spinner(frameChoices.map { Receiver.frameSizeName(it) }, Receiver.frameSizeName(prefs.getInt("frames", Receiver.FRAMES_NORMAL)))
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(constel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(codeRate, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(frames, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.6f))
        })
        startStop = button("Start") { if (receiver == null) start() else stop() }
        root.addView(row(label("gain dB"), gain, startStop))
        root.addView(row(button("Replay recording") { startReplay() }, button("USB test (F1)") {
            stop(); startActivity(Intent(this, BenchActivity::class.java))
        }))

        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
        logScroll = ScrollView(this).apply { addView(logView) }
        root.addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = 12 })

        setContentView(root)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            v.setPadding(24 + bars.left, 24 + bars.top, 24 + bars.right, 24 + bars.bottom)
            insets
        }
        val filter = IntentFilter(permissionAction)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(permissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(permissionReceiver, filter)
        say("ATV RX 0.3-f4 · ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        say(findDevice()?.let { "ESP32-S3 connected" } ?: "ESP32-S3 (303a:1001) not connected")
        refresh()
    }

    override fun onDestroy() {
        stop()
        unregisterReceiver(permissionReceiver)
        super.onDestroy()
    }

    private fun rateList(c: String) = Receiver.MODCODS.keys.filter { it.first == c }.map { it.second }
    private fun adapter(items: List<String>) = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    private fun spinner(items: List<String>, sel: String) = Spinner(this).apply {
        adapter = adapter(items); setSelection(items.indexOf(sel).coerceAtLeast(0))
    }

    private fun label(t: String) = TextView(this).apply { text = t; setPadding(8, 0, 8, 0) }

    private fun row(vararg v: android.view.View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        v.forEach { addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, if (it is TextView && it !is EditText && it !is Button) 0f else 1f)) }
    }

    // plain coloured views: the device theme draws the stock buttons invisible on Android 16
    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setTextColor(Color.WHITE)
        background = GradientDrawable().apply { setColor(Color.rgb(0x1E, 0x5A, 0xA8)); cornerRadius = 16f }
        setOnClickListener { onClick() }
    }

    private fun say(s: String) = runOnUiThread {
        android.util.Log.i("atvrx", s)
        logView.append(s + "\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun findDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { it.vendorId == 0x303A && it.productId == 0x1001 }

    private fun start() {
        if (receiver != null) return
        val dev = findDevice() ?: return say("ESP32-S3 (303a:1001) not connected")
        if (!surfaceReady) return say("no picture surface yet")
        if (!usbManager.hasPermission(dev)) {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            usbManager.requestPermission(dev, PendingIntent.getBroadcast(this, 0, Intent(permissionAction).setPackage(packageName), flags))
            return
        }
        val mhz = freq.text.toString().replace(',', '.').toDoubleOrNull() ?: return say("frequency?")
        val g = gain.text.toString().toIntOrNull() ?: return say("gain?")
        val sr = rates[rate.selectedItemPosition]
        val c = constel.selectedItem as String
        val cr = codeRate.selectedItem as String
        val fr = frameChoices[frames.selectedItemPosition]
        prefs.edit().putString("freq", freq.text.toString()).putInt("sr", sr).putInt("gain", g)
            .putString("const", c).putString("cr", cr).putInt("frames", fr).apply()
        val conn = usbManager.openDevice(dev) ?: return say("openDevice failed")
        val v = VideoDecoder(surface.holder.surface)
        val r = Receiver(conn, dev, applicationInfo.nativeLibraryDir, cacheDir,
            Receiver.Config(freqHz = Math.round(mhz * 1e6), sr = sr, const = c, cr = cr, gainDb = g.toDouble(), frameSizes = fr), v, ::say)
        video = v; receiver = r
        constView.clear(); r.onConstellation = constView::line
        startedAt = System.nanoTime(); tsHistory.clear()
        r.start()
        startStop.text = "Stop"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** The newest raw recording of the USB test (F1) through the whole chain, without the ESP32-S3. */
    private fun startReplay() {
        if (receiver != null) return
        if (!surfaceReady) return say("no picture surface yet")
        val f = getExternalFilesDir(null)?.listFiles { x -> x.name.endsWith(".raw") }?.maxByOrNull { it.lastModified() }
            ?: return say("no recording: USB test (F1) with 'save raw packets'")
        val sr = rates[rate.selectedItemPosition]
        val v = VideoDecoder(surface.holder.surface)
        val r = Receiver(null, null, applicationInfo.nativeLibraryDir, cacheDir,
            Receiver.Config(freqHz = 0, sr = sr, const = constel.selectedItem as String, cr = codeRate.selectedItem as String,
                gainDb = 0.0, frameSizes = frameChoices[frames.selectedItemPosition], replay = f), v, ::say)
        video = v; receiver = r
        constView.clear(); r.onConstellation = constView::line
        startedAt = System.nanoTime(); tsHistory.clear()
        r.start()
        startStop.text = "Stop"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun stop() {
        val r = receiver ?: return
        receiver = null
        Thread { r.stop() }.start()
        startStop.text = "Start"
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun refresh() {
        val r = receiver
        info.text = if (r == null) "stopped" else {
            val s = r.status
            val now = System.nanoTime()
            tsHistory.addLast(now to s.tsBytes)
            while (now - tsHistory.first().first > 10_000_000_000L) tsHistory.removeFirst()
            val (t0, b0) = tsHistory.first()
            val tsRate = if (now > t0) (s.tsBytes - b0) * 8 / ((now - t0) / 1e9) / 1e3 else 0.0
            val d = r.demux; val v = video
            val usb = r.link?.stats
            // leandvb's FREQ means something only while frames come through (it reports noise as well)
            val live = r.alive() && s.mer > 3.0
            val carrier = s.offsetHz + (if (s.freq.isNaN() || !live) 0.0 else s.freq)
            buildString {
                append("%s   %.0f s\n".format(s.state, (now - startedAt) / 1e9))
                append("carrier %+.2f kHz  (NCO %+.2f, residual %s Hz)\n".format(carrier / 1e3, s.offsetHz / 1e3, if (live) num(s.freq, "%.0f") else "–"))
                append("drift steps %d  re-searches %d  search %.0f dB\n".format(s.driftSteps, s.researches, s.searchDb))
                append("LOCK %s  FRAME %s  MER %s dB  CNR %s dB\n".format(yn(s.lock), yn(s.frameLock), num(s.mer, "%.1f"), num(s.cnr, "%.1f")))
                append("frames ok %d  fixed %d  bad %d  locks %d\n".format(s.framesOk, s.framesFixed, s.framesBad, s.locks))
                append("TS %.0f kb/s (10 s)  %d pkts  TEI %d  CC %d\n".format(tsRate, d.packets, d.errorPackets, d.ccErrors))
                append("service %s / %s\n".format(d.service ?: "–", d.provider ?: "–"))
                append("video %s %dx%d  %d frames%s\n".format(v?.mime?.substringAfter('/') ?: "–", v?.width ?: 0, v?.height ?: 0,
                    v?.frames ?: 0, v?.error?.let { "  ERR $it" } ?: ""))
                append("USB lost %d  jumps %d  ESP dropped %d  queue drops %d".format(usb?.lost ?: 0, usb?.jumps ?: 0, usb?.dropped ?: 0, s.queueDrops))
            }
        }
        ui.postDelayed(::refresh, 500)
    }

    private fun yn(b: Boolean) = if (b) "yes" else "no"
    private fun num(x: Double, f: String) = if (x.isNaN()) "–" else f.format(x)
}
