package com.ok1cdj.atvrx

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
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
 * The receiver itself lives in [ReceiverService] (foreground), so it keeps going with the screen off.
 */
class MainActivity : Activity() {
    private val rates = listOf(40_000, 50_000, 66_666, 100_000, 125_000, 250_000, 333_000, 400_000, 500_000)

    /** Receiver settings by name; the gain stays as it is (it depends on the distance, not on the transmission). */
    data class Preset(val name: String, val freq: String, val sr: Int, val const: String, val cr: String, val frames: Int) {
        fun encode() = listOf(name, freq, sr, const, cr, frames).joinToString("|")
        companion object {
            fun decode(l: String) = l.split('|').takeIf { it.size == 6 }?.let {
                Preset(it[0], it[1], it[2].toIntOrNull() ?: return null, it[3], it[4], it[5].toIntOrNull() ?: return null)
            }
        }
    }
    private val builtIn = listOf(40_000 to "40k", 50_000 to "50k", 66_666 to "66.7k", 125_000 to "125k", 333_000 to "333k")
        .map { (sr, n) -> Preset("Beacon $n QPSK 3/4", DEFAULT_FREQ, sr, "QPSK", "3/4", Receiver.FRAMES_NORMAL) } +
        Preset("PC 8PSK 3/5 333k", DEFAULT_FREQ, 333_000, "8PSK", "3/5", Receiver.FRAMES_NORMAL)
    private lateinit var presetSpinner: Spinner
    private fun userPresets() = (prefs.getString("presets", "") ?: "").lines().mapNotNull { Preset.decode(it) }
    private fun allPresets() = builtIn + userPresets()

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
    private lateinit var specView: SpectrumView
    private lateinit var track: android.widget.CheckBox
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var surface: SurfaceView
    private var surfaceReady = false

    private var service: ReceiverService? = null
    private val receiver get() = service?.receiver
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            val s = (b as ReceiverService.LocalBinder).service
            service = s
            synchronized(s.log) { s.log.toList() }.forEach { appendLog(it) }
            s.onLog = ::appendLog
            s.onConstellation = constView::line
            if (surfaceReady) s.setSurface(surface.holder.surface)
            pendingStart?.let { pendingStart = null; it() }
        }
        override fun onServiceDisconnected(n: ComponentName) { service = null }
    }
    private var pendingStart: (() -> Unit)? = null
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
            override fun surfaceCreated(h: SurfaceHolder) { surfaceReady = true; service?.setSurface(h.surface) }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
            // the picture goes, reception goes on (in the service)
            override fun surfaceDestroyed(h: SurfaceHolder) { surfaceReady = false; service?.setSurface(null) }
        })
        val w = resources.displayMetrics.widthPixels - 48
        root.addView(surface, LinearLayout.LayoutParams(MATCH_PARENT, w * 9 / 16))

        info = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f; setPadding(0, 12, 0, 8) }
        root.addView(info)
        // spectrum (wide) and constellation (square) side by side
        constView = ConstellationView(this)
        specView = SpectrumView(this)
        val side = (resources.displayMetrics.widthPixels * 0.3).toInt()
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(specView, LinearLayout.LayoutParams(0, side, 1f).apply { rightMargin = 8 })
            addView(constView, LinearLayout.LayoutParams(side, side))
        })

        freq = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(prefs.getString("freq", DEFAULT_FREQ)); hint = "MHz"
        }
        rate = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, rates.map { "%.1f kS/s".format(it / 1e3) })
            setSelection(rates.indexOf(prefs.getInt("sr", 50_000)).coerceAtLeast(0))
        }
        gain = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(prefs.getInt("gain", 30).toString()); hint = "dB"
        }
        presetSpinner = Spinner(this)
        refreshPresets()
        presetSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(a: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                if (pos > 0) { applyPreset(allPresets()[pos - 1]); presetSpinner.setSelection(0) }
            }
            override fun onNothingSelected(a: android.widget.AdapterView<*>?) {}
        }
        // preset and frequency share a row; Save / Delete live in the ⋮ menu
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(presetSpinner, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        })
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
        track = android.widget.CheckBox(this).apply { text = "drift"; isChecked = prefs.getBoolean("track", true) }
        val more = button("⋮") { v -> showMenu(v) }.apply { minWidth = 0; minimumWidth = 0; setPadding(36, 0, 36, 0) }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(label("gain dB"))
            addView(gain, LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.8f))
            addView(track)
            addView(startStop, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.4f))
            addView(more, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = 8 })
        })

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
        appendLog("ATV RX ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        appendLog(findDevice()?.let { "ESP32-S3 connected" } ?: "ESP32-S3 (303a:1001) not connected")
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        bindService(Intent(this, ReceiverService::class.java), connection, BIND_AUTO_CREATE)
        refresh()
    }

    override fun onDestroy() {
        service?.let { it.onLog = null; it.onConstellation = null; it.setSurface(null) }
        unbindService(connection)
        unregisterReceiver(permissionReceiver)
        super.onDestroy()
    }

    /** Everything that is not needed while receiving: presets, replay, USB test, about. */
    private fun showMenu(anchor: android.view.View) {
        val m = android.widget.PopupMenu(this, anchor)
        val items = listOf<Pair<String, () -> Unit>>(
            "Save preset" to ::savePreset,
            "Delete preset" to ::deletePreset,
            "Replay recording" to ::startReplay,
            "USB test" to { stop(); startActivity(Intent(this, BenchActivity::class.java)) },
            "About" to { startActivity(Intent(this, AboutActivity::class.java)) },
        )
        items.forEachIndexed { i, (t, _) -> m.menu.add(0, i, i, t) }
        m.setOnMenuItemClickListener { items[it.itemId].second(); true }
        m.show()
    }

    private fun refreshPresets() {
        presetSpinner.adapter = adapter(listOf("Preset…") + allPresets().map { it.name })
    }

    /** Puts a preset into the fields; a running receiver restarts with it. */
    private fun applyPreset(p: Preset) {
        freq.setText(p.freq)
        rate.setSelection(rates.indexOf(p.sr).coerceAtLeast(0))
        constel.setSelection(consts.indexOf(p.const).coerceAtLeast(0))
        // the code rates of the new constellation now, so that its listener keeps this one
        val list = rateList(p.const)
        codeRate.adapter = adapter(list)
        codeRate.setSelection(list.indexOf(p.cr).coerceAtLeast(0))
        frames.setSelection(frameChoices.indexOf(p.frames).coerceAtLeast(0))
        say("preset: ${p.name}")
        if (receiver != null) { stop(); ui.postDelayed({ start() }, 1500) }
    }

    private fun currentPreset(): Preset? {
        val f = freq.text.toString().replace(',', '.').toDoubleOrNull() ?: return null
        val sr = rates[rate.selectedItemPosition]
        val c = constel.selectedItem as String; val cr = codeRate.selectedItem as String
        val fr = frameChoices[frames.selectedItemPosition]
        val name = "%.3f %s %s %s%s".format(f, if (sr % 1000 == 0) "${sr / 1000}k" else "%.1fk".format(sr / 1e3), c, cr,
            if (fr == Receiver.FRAMES_NORMAL) "" else " " + Receiver.frameSizeName(fr))
        return Preset(name, "%.3f".format(java.util.Locale.US, f), sr, c, cr, fr)
    }

    private fun savePreset() {
        val p = currentPreset() ?: return say("frequency?")
        if (allPresets().any { it.name == p.name }) return say("preset exists: ${p.name}")
        prefs.edit().putString("presets", (userPresets() + p).joinToString("\n") { it.encode() }).apply()
        refreshPresets()
        say("preset saved: ${p.name}")
    }

    /** Deletes the user preset that matches the current settings (the built-in ones stay). */
    private fun deletePreset() {
        val p = currentPreset() ?: return
        val left = userPresets().filter { it.name != p.name }
        if (left.size == userPresets().size) return say("no saved preset ${p.name}")
        prefs.edit().putString("presets", left.joinToString("\n") { it.encode() }).apply()
        refreshPresets()
        say("preset deleted: ${p.name}")
    }

    private fun rateList(c: String) = Receiver.MODCODS.keys.filter { it.first == c }.map { it.second }
    // the closed spinner with little padding and one line, so that "QPSK" or "normal+short" fits
    private fun adapter(items: List<String>) = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
        override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup) =
            (super.getView(position, convertView, parent) as TextView).apply { setPadding(4, 0, 0, 0); isSingleLine = true }
    }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
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
    private fun button(t: String, onClick: (android.view.View) -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setTextColor(Color.WHITE)
        background = GradientDrawable().apply { setColor(Color.rgb(0x1E, 0x5A, 0xA8)); cornerRadius = 16f }
        setOnClickListener(onClick)
    }

    private fun say(s: String) = service?.say(s) ?: runOnUiThread { appendLog(s) }

    private fun appendLog(s: String) {
        logView.append(s + "\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun findDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { it.vendorId == 0x303A && it.productId == 0x1001 }

    private fun start() {
        if (receiver != null) return
        val dev = findDevice() ?: return say("ESP32-S3 (303a:1001) not connected")
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
            .putString("const", c).putString("cr", cr).putInt("frames", fr).putBoolean("track", track.isChecked).apply()
        val cfg = Receiver.Config(freqHz = Math.round(mhz * 1e6), sr = sr, const = c, cr = cr, gainDb = g.toDouble(), frameSizes = fr,
            track = track.isChecked)
        val svc = service ?: return run { pendingStart = ::start }
        constView.clear(); specView.clear(); tsHistory.clear()
        svc.startRx(cfg, dev)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** The newest raw recording of the USB test (F1) through the whole chain, without the ESP32-S3. */
    private fun startReplay() {
        if (receiver != null) return
        val f = getExternalFilesDir(null)?.listFiles { x -> x.name.endsWith(".raw") }?.maxByOrNull { it.lastModified() }
            ?: return say("no recording: USB test (F1) with 'save raw packets'")
        val sr = rates[rate.selectedItemPosition]
        val svc = service ?: return
        constView.clear(); specView.clear(); tsHistory.clear()
        svc.startRx(Receiver.Config(freqHz = 0, sr = sr, const = constel.selectedItem as String, cr = codeRate.selectedItem as String,
            gainDb = 0.0, frameSizes = frameChoices[frames.selectedItemPosition], replay = f, track = track.isChecked), null)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun stop() {
        service?.stopRx()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun refresh() {
        val r = receiver
        startStop.text = if (r == null) "Start" else "Stop"
        if (r == null) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        info.text = if (r == null) "stopped" else {
            val s = r.status
            val now = System.nanoTime()
            tsHistory.addLast(now to s.tsBytes)
            while (now - tsHistory.first().first > 10_000_000_000L) tsHistory.removeFirst()
            val (t0, b0) = tsHistory.first()
            val tsRate = if (now > t0) (s.tsBytes - b0) * 8 / ((now - t0) / 1e9) / 1e3 else 0.0
            specView.update(r.spectrum, r.fs, if (s.searchDb >= 6) s.offsetHz else Double.NaN, r.cfg.sr)
            val d = r.demux; val v = service?.video
            val usb = r.link?.stats
            // leandvb's FREQ means something only while frames come through (it reports noise as well)
            val live = r.alive() && s.mer > 3.0
            val carrier = s.offsetHz + (if (s.freq.isNaN() || !live) 0.0 else s.freq)
            buildString {
                append("%s   %.0f s\n".format(s.state, (now - (service?.startedAt ?: now)) / 1e9))
                append("carrier %+.2f kHz  (NCO %+.2f, residual %s Hz)\n".format(carrier / 1e3, s.offsetHz / 1e3, if (live) num(s.freq, "%.0f") else "–"))
                append("drift %s  steps %d  re-searches %d  search %.0f dB\n".format(if (r.cfg.track) "on" else "off",
                    s.driftSteps, s.researches, s.searchDb))
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

    companion object {
        /** IARU R1 13 cm ATV segment 2322-2355 MHz; ATV_FREQ of boards.sh on the PC side */
        const val DEFAULT_FREQ = "2340.000"
    }
}
