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
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Writes the current firmware from the web (FirmwareStore) into the connected board from the phone, no PC: the ESP32-S3
 * gets the receiver, the ESP32-C3 the transmitter (ESP32-ATV's fork of ESP32-DATV-SA, without a built-in beacon: store a
 * loop with the callsign from the TX screen afterwards). The ROM loader picks the chip, so the two cannot be mixed up.
 */
class FirmwareActivity : Activity() {
    private val usbManager by lazy { getSystemService(USB_SERVICE) as UsbManager }
    private val permissionAction by lazy { "$packageName.FW_USB_PERMISSION" }
    private val granted = ArrayBlockingQueue<Boolean>(1)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { granted.offer(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) }
    }
    private lateinit var logView: TextView
    private lateinit var base: EditText
    private lateinit var flashButton: Button
    private lateinit var phase: TextView
    private lateinit var bar: ProgressBar
    @Volatile private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(TextView(this).apply {
            text = "Firmware for the board on USB, the current version from the web:\n" +
                "• ESP32-S3 → ATV RX receiver\n• ESP32-C3 → transmitter (no built-in beacon: then store a loop with your callsign on the TX screen)\n" +
                "The board is put into its ROM loader, which tells the chip; it then appears on USB again (allow access if asked)."
            textSize = 13f
        })
        base = EditText(this).apply { setText(FirmwareStore.DEFAULT_BASE); textSize = 12f; isSingleLine = true }
        root.addView(base, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8 })
        root.addView(button("Check versions on the web") { if (!busy) check() }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8 })
        flashButton = button("Write firmware into the connected board") { if (!busy) flash() }
        root.addView(flashButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8 })
        phase = TextView(this).apply { textSize = 15f; setPadding(0, 16, 0, 4) }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progress = 0 }
        root.addView(phase)
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
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
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    private fun store() = FirmwareStore(this, base.text.toString().trim().trimEnd('/'))

    private fun check() = work {
        for (chip in listOf("ESP32-S3", "ESP32-C3"))
            log("$chip: ${store().onlineVersion(chip) ?: "– (no network?)"}")
    }

    private fun flash() = work {
        val dev = device() ?: throw IOException("no ESP32 (303a:1001) on USB")
        var usb = open(dev)
        // the transmitter's firmware tells its version; the receiver's does not answer text
        try {
            usb.write("\nINFO\n".toByteArray())
            val b = ByteArray(1024); val n = usb.read(b, 800)
            String(b, 0, n).lines().firstOrNull { it.startsWith("FW ") }?.let { log("C3 now: ${it.trim()}") }
        } catch (_: IOException) {}
        progress("Into the ROM loader…", 0.0)
        log("into the ROM loader…")
        EspRom.enterLoader(usb)
        usb.close()
        Thread.sleep(1500)                                         // it leaves USB and comes back
        val again = waitDevice() ?: throw IOException("the board did not come back on USB")
        usb = open(again)
        try {
            val rom = EspRom(usb) { log(it) }
            rom.sync()
            val chip = when (val id = rom.chipId()) {
                EspRom.CHIP_C3 -> "ESP32-C3"; EspRom.CHIP_S3 -> "ESP32-S3"
                else -> throw IOException("chip id $id: not an ESP32-C3 or ESP32-S3")
            }
            log("chip: $chip (${if (chip == "ESP32-S3") "receiver" else "transmitter"})")
            progress("$chip: getting the firmware…", 0.0)
            val fw = store().get(chip) { log(it) }
            rom.attachFlash()
            val total = fw.parts.sumOf { it.data.size }.toDouble()
            var before = 0
            for ((i, p) in fw.parts.withIndex()) {
                log("writing %d bytes at 0x%x".format(p.data.size, p.offset))
                val label = "$chip ${fw.version}: writing ${i + 1}/${fw.parts.size}"
                rom.flash(p.data, p.offset) { done, _ -> progress(label, (before + done) / total) }
                before += p.data.size
            }
            rom.finish()
            log("verified (MD5); restarting into ${fw.name} ${fw.version}")
            EspRom.hardReset(usb)
            progress("Done: $chip ${fw.version} written and verified", 1.0)
            if (chip == "ESP32-C3") log("Now store a loop with your callsign: TX screen → Save as beacon (or tx/c3_vidw.py).")
        } finally {
            usb.close()
        }
    }

    // ---- USB

    private fun device(): UsbDevice? = usbManager.deviceList.values.firstOrNull { it.vendorId == 0x303A && it.productId == 0x1001 }

    private fun waitDevice(): UsbDevice? {
        val end = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < end) { device()?.let { return it }; Thread.sleep(200) }
        return null
    }

    /** Opens the device, asking for permission first if needed (the board is a new device after entering the loader). */
    private fun open(dev: UsbDevice): UsbCdc {
        if (!usbManager.hasPermission(dev)) {
            granted.clear()
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            runOnUiThread { usbManager.requestPermission(dev, PendingIntent.getBroadcast(this, 0, Intent(permissionAction).setPackage(packageName), flags)) }
            if (granted.poll(60, TimeUnit.SECONDS) != true) throw IOException("USB permission denied")
        }
        val conn = usbManager.openDevice(dev) ?: throw IOException("openDevice failed")
        return UsbCdc(conn, dev)
    }

    // ---- UI

    private fun work(f: () -> Unit) {
        busy = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Thread {
            try { f() } catch (e: Exception) { log("error: ${e.message}"); runOnUiThread { phase.text = "Failed: ${e.message}" } }
            busy = false
            runOnUiThread { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }.start()
    }

    private fun log(s: String) = runOnUiThread { logView.append(s + "\n") }

    private fun progress(text: String, fraction: Double) = runOnUiThread {
        phase.text = text
        bar.progress = (fraction * 1000).toInt().coerceIn(0, 1000)
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setTextColor(Color.WHITE)
        background = GradientDrawable().apply { setColor(Color.rgb(0x1E, 0x5A, 0xA8)); cornerRadius = 16f }
        setOnClickListener { onClick() }
    }
}
