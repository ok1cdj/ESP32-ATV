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
        override fun onReceive(c: Context, i: Intent) {
            val ok = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val role = pendingRole
            if (role != null) { pendingRole = null; if (ok) flashRole(role) else log("USB permission denied") }
            else granted.offer(ok)
        }
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
                "• ESP32-S3 → receiver\n• ESP32-C3 → transmitter (no built-in beacon: then store a loop with your callsign on the TX screen)\n" +
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
        val web = HashMap<String, String>()
        for (chip in listOf("ESP32-S3", "ESP32-C3")) {
            val v = store().onlineVersion(chip)
            if (v != null) web[chip] = v
            log("$chip on the web: ${v ?: "– (no network?)"}")
        }
        // the boards on USB: the C3 tells its firmware (INFO, nothing is transmitted; a running beacon stops), the S3 does not
        for (dev in UsbBoards.all(usbManager)) {
            val usb = open(dev)
            val mac = UsbBoards.serial(usbManager, dev)
            val link = C3Link(usb)
            try {
                Thread.sleep(1200)                     // opening the port may reset the C3; one busy with its beacon may miss an INFO
                if ((link.info() ?: link.info()) != null) { log("C3 $mac: ${link.fw ?: "no FW line (old firmware)"}"); UpdateCheck.rememberC3(this, mac, link.fw) }
            } finally { link.close() }
        }
        val old = UpdateCheck.outdated(this, web)
        if (old.isEmpty()) log("the boards this app knows have the web's firmware")
        old.forEach { log("${it.chip} ${it.mac}: ${it.has} → ${it.web} (write it: the button below)") }
    }

    /** With two boards on a hub: which one (by role, UsbBoards); one board: that one. */
    private fun flash() {
        val devs = UsbBoards.all(usbManager)
        if (devs.size <= 1) return work { flashDev(devs.firstOrNull() ?: throw IOException("no ESP32 (303a:1001) on USB")) }
        val roles = UsbBoards.Role.entries
        android.app.AlertDialog.Builder(this).setTitle("Which board?")
            .setItems(roles.map { it.label }.toTypedArray()) { _, k -> flashRole(roles[k]) }.show()
    }

    private fun flashRole(role: UsbBoards.Role) {
        when (val p = UsbBoards.pick(this, role) { flashRole(role) }) {
            is UsbBoards.Pick.Found -> work { flashDev(p.dev) }
            is UsbBoards.Pick.Ask -> { pendingRole = role; UsbBoards.requestPermission(this, p.dev, permissionAction) }
            is UsbBoards.Pick.Wait -> log("identifying the boards on USB…")
            is UsbBoards.Pick.Missing -> log(p.message)
        }
    }

    @Volatile private var pendingRole: UsbBoards.Role? = null

    private fun flashDev(dev: UsbDevice) {
        val others = UsbBoards.all(usbManager).map { it.deviceName }.filter { it != dev.deviceName }.toSet()
        var usb = open(dev)
        val mac = UsbBoards.serial(usbManager, dev)
        // the transmitter's firmware tells its version and its DC table (kept in the app, restored after the upload if
        // it is gone); the receiver's does not answer text
        var table = emptyList<Triple<Double, Double, Double>>()
        try {
            usb.write("\nINFO\nBDC LIST\n".toByteArray())
            val got = java.io.ByteArrayOutputStream()
            val r = ByteArray(1024)
            val until = System.currentTimeMillis() + 1200
            while (System.currentTimeMillis() < until) { val k = usb.read(r, 200); if (k > 0) got.write(r, 0, k) }
            val text = got.toString().lines()
            text.firstOrNull { it.startsWith("FW ") }?.let { log("C3 now: ${it.trim()}"); UpdateCheck.rememberC3(this, mac, it) }
            table = text.mapNotNull { l -> l.trim().split(' ').takeIf { it.size == 4 && it[0] == "BDCT" }?.let {
                Triple(it[1].toDouble() / 1000, it[2].toDouble(), it[3].toDouble()) } }
            if (table.isNotEmpty()) log("C3 DC table: " + table.joinToString { "%.3f MHz %.3f %.3f".format(it.first, it.second, it.third) })
            if (mac != null) for (t in table) if (DcStore.all(this, mac).none { kotlin.math.abs(it.mhz - t.first) < 0.001 })
                DcStore.put(this, mac, DcStore.Entry(t.first, t.second, t.third, "from the C3", 0.0))
        } catch (_: IOException) {} catch (_: NumberFormatException) {}
        progress("Into the ROM loader…", 0.0)
        log("into the ROM loader…")
        EspRom.enterLoader(usb)
        usb.close()
        Thread.sleep(1500)                                         // it leaves USB and comes back
        val again = waitDevice(others) ?: throw IOException("the board did not come back on USB")
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
            UpdateCheck.rememberBoard(this, mac, chip, fw.version)
            if (chip == "ESP32-C3" && mac != null) restoreDc(mac, others)
            if (chip == "ESP32-C3") log("Now store a loop with your callsign: TX screen → Save as beacon (or tx/c3_vidw.py).")
        } finally {
            usb.close()
        }
    }

    // ---- USB


    /** The board back on USB: the one that is not among [others] (the other board on a hub stays as it was). */
    private fun waitDevice(others: Set<String>): UsbDevice? {
        val end = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < end) {
            UsbBoards.all(usbManager).firstOrNull { it.deviceName !in others }?.let { return it }
            Thread.sleep(200)
        }
        return null
    }

    /**
     * After an upload to the C3: its DC table should still be there (the upload does not touch the NVS); if it is gone,
     * the app's copy goes back in (BDC PUT, nothing is transmitted).
     */
    private fun restoreDc(mac: String, others: Set<String>) {
        val saved = DcStore.all(this, mac)
        if (saved.isEmpty()) return
        try {
            Thread.sleep(1500)
            val dev = waitDevice(others) ?: return log("the C3 did not come back: its DC table not checked")
            val link = C3Link(open(dev))
            try {
                Thread.sleep(1200)
                link.info() ?: return log("no answer from the C3: its DC table not checked")
                val now = link.dcTable() ?: return log("this firmware has no DC table")
                if (now.isNotEmpty()) return log("C3 DC table kept (${now.size} frequencies)")
                var put = 0
                for (e in saved) if (link.dcPut(e.mhz, e.i, e.q)) put++
                log("C3 DC table was gone: $put of ${saved.size} frequencies restored from the app")
                link.command("INFO")                                       // the beacon does not start
            } finally { link.close() }
        } catch (e: IOException) { log("DC table: ${e.message}") }
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
