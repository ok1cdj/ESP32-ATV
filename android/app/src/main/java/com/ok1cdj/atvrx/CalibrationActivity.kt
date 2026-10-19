package com.ok1cdj.atvrx

import android.app.Activity
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
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.IOException
import kotlin.math.log10
import kotlin.math.pow

/** The app's own copy of the C3's DC corrections, by MAC and frequency (to restore the C3's table after a firmware upload). */
object DcStore {
    class Entry(val mhz: Double, val i: Double, val q: Double, val date: String, val suppressionDb: Double)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("dccal", Context.MODE_PRIVATE)

    fun put(ctx: Context, mac: String, e: Entry) = prefs(ctx).edit()
        .putString("$mac@${Math.round(e.mhz * 1000)}", "${e.i} ${e.q} ${e.date} ${e.suppressionDb}").apply()

    fun all(ctx: Context, mac: String): List<Entry> = prefs(ctx).all.mapNotNull { (k, v) ->
        if (!k.startsWith("$mac@")) return@mapNotNull null
        val p = (v as? String)?.split(' ') ?: return@mapNotNull null
        Entry(k.substringAfter('@').toLong() / 1000.0, p[0].toDouble(), p[1].toDouble(), p.getOrElse(2) { "" }, p.getOrElse(3) { "0" }.toDouble())
    }.sortedBy { it.mhz }

    /** The entry nearest to [mhz] within [withinMhz], or null. */
    fun near(ctx: Context, mac: String, mhz: Double, withinMhz: Double) =
        all(ctx, mac).filter { kotlin.math.abs(it.mhz - mhz) <= withinMhz }.minByOrNull { kotlin.math.abs(it.mhz - mhz) }
}

/**
 * Transmitter calibration: nulls the C3's carrier leakage with the S3, both on USB through a hub. The C3 transmits its
 * leakage alone (BDC TEST on f: no modulation, the PA off), the S3 measures the tone (ToneMeter) and DcCalibrator
 * searches the correction (rx/dccal/core.py's algorithm); Save stores it in the C3's table for f (BDC PUT) and in the app.
 * The leakage moves by about 2 codes from one start of the C3 to the next (docs/2026-10-16-lo-calibration.en.md), so a
 * stored value is typically worth about 9 dB, a calibration in the same session about 20 dB.
 */
class CalibrationActivity : Activity() {
    private val usbManager by lazy { getSystemService(USB_SERVICE) as UsbManager }
    private val permissionAction by lazy { "$packageName.USB_PERMISSION" }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) start() else log("USB permission denied")
        }
    }
    private lateinit var freq: EditText
    private lateinit var warm: EditText
    private lateinit var level: TextView
    private lateinit var phase: TextView
    private lateinit var logView: TextView
    private lateinit var startButton: Button
    private lateinit var saveButton: Button
    @Volatile private var busy = false
    @Volatile private var stopping = false
    private var result: Triple<Double, Double, Double>? = null     // i, q, suppression [dB]
    private var resultMhz = 0.0
    private var c3Mac: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(TextView(this).apply {
            text = "Transmitter calibration: nulls the carrier leakage of the ESP32-C3 (a tone in the middle of the signal that " +
                "limits reception at low levels), measured with the ESP32-S3.\n" +
                "• Both boards on USB through a hub (a powered one is better).\n" +
                "• The C3 transmits only its leakage on the frequency below (no modulation, PA off) – licensed radio amateurs only.\n" +
                "• Antennas some distance apart or an attenuator in between: the S3 must not be overloaded.\n" +
                "• The C3 warms up first (its frequency drifts for about a minute after it starts).\n" +
                "The leakage moves each time the C3 starts: a saved value typically takes off about 9 dB, " +
                "a calibration just before transmitting (without unplugging the C3) about 20 dB."
            textSize = 13f
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(this).apply { text = "MHz " })
        freq = EditText(this).apply {
            setText(getSharedPreferences("tx", MODE_PRIVATE).getString("freq", null) ?: MainActivity.DEFAULT_FREQ); isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        row.addView(freq, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply { text = "  warm-up s " })
        warm = EditText(this).apply { setText("60"); isSingleLine = true; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        row.addView(warm, LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.6f))
        root.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8 })
        startButton = button("Start") { if (busy) { stopping = true; log("stopping…") } else start() }
        root.addView(startButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8 })
        level = TextView(this).apply { textSize = 28f; typeface = Typeface.MONOSPACE; text = "–" }
        root.addView(level, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 12 })
        phase = TextView(this).apply { textSize = 15f }
        root.addView(phase)
        saveButton = button("Save in the C3") { save() }.apply { isEnabled = false; alpha = 0.4f }
        root.addView(saveButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8 })
        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
        root.addView(ScrollView(this).apply { addView(logView) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = 12 })
        setContentView(root)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            v.setPadding(24 + bars.left, 24 + bars.top, 24 + bars.right, 24 + bars.bottom)
            insets
        }
        val f = IntentFilter(permissionAction)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, RECEIVER_NOT_EXPORTED) else registerReceiver(receiver, f)
        log(UsbBoards.describe(this, usbManager))
    }

    override fun onDestroy() {
        stopping = true
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    /** Both boards, asking for permissions / identifying them first (start() is called again then). */
    private fun boards(): Pair<UsbDevice, UsbDevice>? {
        if (UsbBoards.all(usbManager).size < 2) {
            log("connect both the ESP32-S3 and the ESP32-C3 through a USB hub (${UsbBoards.describe(this, usbManager)})")
            return null
        }
        fun one(role: UsbBoards.Role): UsbDevice? = when (val p = UsbBoards.pick(this, role) { start() }) {
            is UsbBoards.Pick.Found -> p.dev
            is UsbBoards.Pick.Ask -> { UsbBoards.requestPermission(this, p.dev); null }
            is UsbBoards.Pick.Wait -> { log("identifying the boards…"); null }
            is UsbBoards.Pick.Missing -> { log(p.message); null }
        }
        val s3 = one(UsbBoards.Role.S3) ?: return null
        val c3 = one(UsbBoards.Role.C3) ?: return null
        return s3 to c3
    }

    private fun start() {
        if (busy) return
        val mhz = freq.text.toString().replace(',', '.').toDoubleOrNull()?.takeIf { it in 2300.0..2450.0 }
            ?: return log("frequency 2300..2450 MHz")
        val warmup = warm.text.toString().toIntOrNull()?.coerceIn(0, 600) ?: 60
        val (s3, c3) = boards() ?: return
        c3Mac = UsbBoards.serial(usbManager, c3)
        busy = true; stopping = false; result = null
        runOnUiThread { startButton.text = "Stop"; saveButton.isEnabled = false; saveButton.alpha = 0.4f }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Thread({
            try { calibrate(s3, c3, mhz, warmup) } catch (e: Exception) { log("error: ${e.message}"); show("Failed: ${e.message}") }
            busy = false
            runOnUiThread {
                startButton.text = "Start"
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (result != null) { saveButton.isEnabled = true; saveButton.alpha = 1f }
            }
        }, "calibration").start()
    }

    private class Stopped : Exception("stopped")

    private fun calibrate(s3: UsbDevice, c3: UsbDevice, mhz: Double, warmup: Int) {
        val conn = usbManager.openDevice(c3) ?: throw IOException("openDevice failed (C3)")
        val link = C3Link(UsbCdc(conn, c3))
        var stream: ToneStream? = null
        try {
            Thread.sleep(1200)                                          // opening the port may reset the C3
            link.info() ?: throw IOException("no ESP32-DATV answer from the C3")
            link.fw?.let { log("C3 $it") }
            log(if (link.booted) "the C3 was reset by opening its port" else "the C3 was not reset by opening its port")
            val st = link.dc(mhz) ?: (0.0 to 0.0)
            if (!bdc(link, "BDC TEST on %.3f".format(java.util.Locale.ROOT, mhz)).contains("TEST 1"))
                throw IOException("the C3's firmware has no BDC TEST: write the current one (menu → Firmware)")
            val s = ToneStream(usbManager, s3, Math.round(mhz * 1e6 + RX_OFFSET), GAIN)
            stream = s
            val meter = ToneMeter(s.fs)
            fun measure(i: Double, q: Double, blocks: Int = 1): Double {
                if (stopping) throw Stopped()
                bdc(link, "BDC %.3f %.3f".format(java.util.Locale.ROOT, i, q))
                var sum = 0.0
                for (b in 0 until blocks) {
                    var x = s.take(ToneMeter.N, if (b == 0) SETTLE_MS else 0)
                    repeat(2) { if (meter.gap(x)) x = s.take(ToneMeter.N, 0) }
                    val r = meter.measure(x)
                    if (r.clip > 1e-3) log("the S3 is overloaded (%.1f %% at full scale): antennas further apart".format(r.clip * 100))
                    sum += 10.0.pow(r.db / 10)
                }
                val db = 10 * log10(sum / blocks)
                show(null, "%+5.1f dB".format(db))
                return db
            }
            log("C3 now %.3f %.3f at %.3f MHz; S3 at +%.0f kHz".format(st.first, st.second, mhz, RX_OFFSET / 1e3))
            // the tone: the line that follows the correction (the S3 has a line of its own at the nominal frequency)
            fun locate() {
                show("Finding the tone…")
                val xs = listOf(8.0 to 0.0, 0.0 to 0.0, 0.0 to 8.0).map { (di, dq) ->
                    if (stopping) throw Stopped()
                    bdc(link, "BDC %.3f %.3f".format(java.util.Locale.ROOT, st.first + di, st.second + dq))
                    s.take(ToneMeter.N, SETTLE_MS)
                }
                if (!meter.locate(xs)) throw IOException("no leakage tone (no line follows the correction): is the S3's antenna on, the C3 near enough?")
                log("the leakage tone at %.5f MHz".format((mhz * 1e6 + RX_OFFSET + meter.f!!) / 1e6))
            }
            locate()
            val warmEnd = System.currentTimeMillis() + warmup * 1000L
            while (System.currentTimeMillis() < warmEnd) {
                measure(st.first + 8, st.second)
                show("Warming up: %d s · drift %+.0f Hz/s".format((warmEnd - System.currentTimeMillis()) / 1000, meter.rate))
                Thread.sleep(500)
            }
            if (warmup > 0) locate()
            val cal = DcCalibrator(st, 4.0)
            val t0 = System.currentTimeMillis()
            while (!cal.done) for ((i, q) in cal.nextPoints()) {
                show("Searching: round %d, step %.2f codes, %d measurements".format(cal.round + 1, cal.step, cal.points.size))
                cal.add(i, q, measure(i, q))
            }
            val r = cal.result()
            val secs = (System.currentTimeMillis() - t0) / 1000
            val ref = measure(0.0, 0.0, 4)
            val last = measure(r.i, r.q, 4)
            log("BDC %.3f %.3f%s, %d measurements in %d s; the tone %.1f → %.1f dB above the noise".format(
                r.i, r.q, if (r.converged) "" else " (not converged)", r.measurements, secs, ref, last))
            if (!r.converged) { show("Not converged: try again (antennas, warm-up)"); return }
            result = Triple(r.i, r.q, ref - last)
            resultMhz = mhz
            show("Done: %.1f dB less leakage · BDC %.3f %.3f\nSave stores it in the C3 for %.3f MHz.".format(ref - last, r.i, r.q, mhz),
                "−%.1f dB".format(ref - last))
        } catch (_: Stopped) {
            show("Stopped")
        } finally {
            stream?.close()
            try { bdc(link, "BDC TEST off"); link.command("INFO") } catch (_: Exception) {}  // then the C3 does not transmit
            link.close()
        }
    }

    /** A BDC command and its answer line; an ERR line throws. */
    private fun bdc(link: C3Link, cmd: String): String {
        link.command(cmd)
        val l = link.waitLine(2000) { it.startsWith("BDC ") || it.startsWith("ERR") } ?: throw IOException("$cmd: no answer")
        if (l.startsWith("ERR")) throw IOException("$cmd: $l")
        return l
    }

    private fun save() {
        val (i, q, sup) = result ?: return
        val mac = c3Mac
        val (_, c3) = boards() ?: return
        saveButton.isEnabled = false
        Thread({
            try {
                val conn = usbManager.openDevice(c3) ?: throw IOException("openDevice failed (C3)")
                val link = C3Link(UsbCdc(conn, c3))
                try {
                    Thread.sleep(1200)
                    link.info() ?: throw IOException("no answer from the C3")
                    if (!link.dcPut(resultMhz, i, q)) {
                        // firmware without BDC PUT: BDC SAVE stores for the frequency of BDC TEST (the leakage alone for a moment)
                        log("no BDC PUT in the C3's firmware: storing it with BDC TEST + SAVE")
                        bdc(link, "BDC TEST on %.3f".format(java.util.Locale.ROOT, resultMhz))
                        bdc(link, "BDC %.3f %.3f".format(java.util.Locale.ROOT, i, q))
                        bdc(link, "BDC SAVE")
                        try { bdc(link, "BDC TEST off") } catch (_: IOException) {}
                    }
                    link.dcTable()?.let { t -> log("C3 table: " + t.joinToString { "%.3f MHz %.3f %.3f".format(it.first, it.second, it.third) }) }
                    link.command("INFO")                                       // the beacon does not start
                } finally { link.close() }
                if (mac != null) DcStore.put(this, mac, DcStore.Entry(resultMhz, i, q,
                    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date()), sup))
                show("Saved: BDC %.3f %.3f for %.3f MHz".format(i, q, resultMhz))
            } catch (e: Exception) {
                log("error: ${e.message}")
                runOnUiThread { saveButton.isEnabled = true }
            }
        }, "calibration-save").start()
    }

    private fun show(text: String?, big: String? = null) = runOnUiThread {
        text?.let { phase.text = it }
        big?.let { level.text = it }
    }

    private fun log(s: String) { AppLog.i("CAL", s); runOnUiThread { logView.append(s + "\n") } }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setTextColor(Color.WHITE)
        background = GradientDrawable().apply { setColor(Color.rgb(0x1E, 0x5A, 0xA8)); cornerRadius = 16f }
        setOnClickListener { onClick() }
    }

    companion object {
        const val RX_OFFSET = 50_000.0       // the S3 above the frequency: its own DC outside
        const val GAIN = 60                  // espdr gain selector (rx/cal_dc.py's default)
        const val SETTLE_MS = 250L           // after a BDC command: the receiver's pipeline
    }
}
