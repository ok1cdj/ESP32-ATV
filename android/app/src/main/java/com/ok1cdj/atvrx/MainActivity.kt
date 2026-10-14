package com.ok1cdj.atvrx

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase F1: does the phone's USB keep up with the ESP32-S3 receiver? Pure USB bench (NB_BENCH) and real sample
 * streams (cs8 333 ksps, cs4 500 ksps, cs8 250 ksps) for 60 s: MB/s, lost samples, units dropped by the ESP, CPU;
 * optionally the raw packets to a file for checking on the PC (rx/raw2cf32.py -> leandvb).
 */
class MainActivity : Activity() {
    private data class Mode(val label: String, val decim: Int, val format: Int)

    private val modes = listOf(
        Mode("cs8 333k", 3, EspLink.FMT_CS8),
        Mode("cs4 500k", 2, EspLink.FMT_CS4),
        Mode("cs8 250k", 4, EspLink.FMT_CS8),
    )
    private val freqHz = 2_300_500_000L
    private val seconds = 60

    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var save: CheckBox
    private lateinit var buttons: List<Button>
    @Volatile private var busy = false

    private val usbManager by lazy { getSystemService(USB_SERVICE) as UsbManager }
    private val permissionAction by lazy { "$packageName.USB_PERMISSION" }
    private var pending: (() -> Unit)? = null
    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) pending?.invoke()
            else say("USB permission denied")
            pending = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // plain coloured views: the device theme drew the stock buttons invisible on Android 16
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(android.graphics.Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.rgb(0x1E, 0x5A, 0xA8)); cornerRadius = 16f
            }
            setOnClickListener { onClick() }
        }
        buttons = listOf(button("USB bench 10 s") { start { bench(it) } }) +
            modes.map { m -> button("${m.label} – 60 s") { start { stream(it, m) } } }
        buttons.forEach {
            row.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = 12 })
        }
        save = CheckBox(this).apply { text = "save raw packets (for the PC)" }
        val screen = CheckBox(this).apply {
            text = "keep screen on"
            setOnCheckedChangeListener { _, on ->
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        log = TextView(this).apply { textSize = 12f; setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE }
        scroll = ScrollView(this).apply { addView(log) }
        root.addView(row); root.addView(save); root.addView(screen)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        // Android 15+ draws edge to edge: keep the content clear of the status, title and navigation bars
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            v.setPadding(24 + bars.left, 24 + bars.top, 24 + bars.right, 24 + bars.bottom)
            insets
        }
        val filter = IntentFilter(permissionAction)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(permissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(permissionReceiver, filter)
        say("ATV RX ${BuildConfigInfo.version} – F1 USB test. ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        say(findDevice()?.let { "ESP32-S3 found: ${it.deviceName}" } ?: "ESP32-S3 (303a:1001) not connected")
    }

    override fun onDestroy() {
        unregisterReceiver(permissionReceiver)
        super.onDestroy()
    }

    private fun say(s: String) = runOnUiThread {
        log.append(s + "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun findDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { it.vendorId == 0x303A && it.productId == 0x1001 }

    /** Runs job on a worker thread once the USB permission is there. */
    private fun start(job: (EspLink) -> Unit) {
        if (busy) return
        val dev = findDevice() ?: return say("ESP32-S3 (303a:1001) not connected")
        if (!usbManager.hasPermission(dev)) {
            pending = { start(job) }
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            usbManager.requestPermission(dev, PendingIntent.getBroadcast(this, 0, Intent(permissionAction).setPackage(packageName), flags))
            return
        }
        busy = true
        buttons.forEach { it.isEnabled = false; it.alpha = 0.4f }
        Thread {
            val wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "atvrx:f1").apply { acquire(10 * 60 * 1000L) }
            var link: EspLink? = null
            try {
                val conn = usbManager.openDevice(dev) ?: throw java.io.IOException("openDevice failed")
                link = EspLink(UsbCdc(conn, dev))
                link.checkFirmware()
                job(link)
            } catch (t: Throwable) {
                say("ERROR: ${t.message}")
            } finally {
                link?.close()
                wake.release()
                busy = false
                runOnUiThread { buttons.forEach { it.isEnabled = true; it.alpha = 1f } }
            }
        }.start()
    }

    private fun bench(link: EspLink) {
        say("\n== USB bench 10 s (64-byte blocks, ESP sends as fast as USB takes them)")
        link.bench = true
        link.resetStats()
        val t0 = System.nanoTime()
        link.send(EspLink.NB_BENCH, 10)
        val r = link.await(EspLink.NB_BENCH, 20_000)
        val dt = (System.nanoTime() - t0) / 1e9
        link.bench = false
        val got = link.stats.benchBlocks * 64
        say("%d bytes in %.2f s = %.3f MB/s; ESP sent %d bytes; %d bad blocks".format(got, dt, got / dt / 1e6, r.value, link.stats.benchBad))
        say("=> 8-bit IQ up to %.0f ksps".format(got / dt / 2 / 1e3))
    }

    private fun stream(link: EspLink, m: Mode) {
        val rate = 1e6 / m.decim
        val need = rate * EspLink.BYTES_PER_SAMPLE.getValue(m.format) / 1e6
        say("\n== ${m.label}: %.0f ksps, %.2f MB/s needed, %d s, %.4f MHz".format(rate / 1e3, need, seconds, freqHz / 1e6))
        val lo = link.configure(freqHz, m.decim, m.format, EspLink.gainSelector(30.0))
        say("LO %.6f MHz".format(lo / 1e6))
        var out: BufferedOutputStream? = null
        var file: File? = null
        if (save.isChecked) {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            file = File(getExternalFilesDir(null), "f1-${m.label.replace(' ', '-')}-$stamp.raw")
            out = BufferedOutputStream(FileOutputStream(file), 1 shl 20)
        }
        link.onPacket = { p -> out?.write(p.buf, p.off, p.size) }
        link.resetStats()
        val cpu0 = Process.getElapsedCpuTime()
        val t0 = System.nanoTime()
        link.send(EspLink.ESP_RUN, seconds.toLong())
        // progress once a second until the run's answer arrives
        var last = 0L
        var r: EspLink.Response? = null
        while (r == null) {
            r = try { link.await(EspLink.ESP_RUN, 1000) } catch (_: java.io.IOException) {
                link.error?.let { throw it }
                if ((System.nanoTime() - t0) / 1e9 > seconds + 15) throw java.io.IOException("no end of run")
                null
            }
            val s = link.stats
            val dt = (System.nanoTime() - t0) / 1e9
            val rateNow = (s.bytes - last) / 1e6
            last = s.bytes
            if (r == null) say("%4.0f s  %.3f MB/s  lost %d  dropped %d".format(dt, rateNow, s.lost, s.dropped))
        }
        val dt = (System.nanoTime() - t0) / 1e9
        val cpu = (Process.getElapsedCpuTime() - cpu0) / 10.0 / dt
        link.onPacket = null
        out?.close()
        val s = link.stats
        say("%d samples in %d packets, %.1f s = %.3f MB/s (%.0f ksps)".format(s.samples, s.packets, dt, s.bytes / dt / 1e6, s.samples / dt / 1e3))
        say("lost %d samples, %d units dropped by the ESP, %d stray bytes, %d bad headers; app CPU %.0f %% of one core".format(s.lost, s.dropped, s.skipped, s.bad, cpu))
        if (r.value != 0L) say("RUN FAILED: code ${r.value}")
        try {
            say("ESP: dropped %d, FIFO peak %d bytes, worst service %d / %d cycles".format(
                link.stat(EspLink.NB_STAT_DROPPED), link.stat(EspLink.NB_STAT_FIFO_PEAK),
                link.stat(EspLink.ESP_STAT_SERVICE_MAX0), link.stat(EspLink.ESP_STAT_SERVICE_MAX1)))
        } catch (t: Throwable) { say("(stats: ${t.message})") }
        say(if (s.lost == 0L && s.dropped == 0) "RESULT ${m.label}: OK, no loss" else "RESULT ${m.label}: LOSS")
        file?.let { say("raw: ${it.absolutePath} (%.1f MB)".format(it.length() / 1e6)) }
    }
}

object BuildConfigInfo { const val version = "0.1-f1" }
