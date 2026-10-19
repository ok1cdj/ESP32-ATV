package com.ok1cdj.atvrx

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.text.InputType
import android.widget.EditText
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A problem report: a zip with info.txt (app, phone, the USB devices, the boards' firmware, settings, power), the app's
 * log ([AppLog]) and the app's own logcat, sent by e-mail (or any app of the share sheet) or attached to a new GitHub
 * issue. Made for users with both boards on a hub, whose phone has no USB port left for adb.
 */
object Diagnostics {
    /** where the e-mail goes (empty: the user fills it in) */
    const val EMAIL = "ondra@ok1cdj.com"
    private const val ISSUES = "https://github.com/ok1cdj/ESP32-ATV/issues/new"
    private const val FOLDER = "ATV RX"                  // as TsRecorder: Download/ATV RX/
    private const val ISSUE_INFO_CHARS = 5000            // of info.txt in the issue's URL (the browser's limit is ~8 kB)

    /** "battery 78 % charging (USB) 31.5 °C" for the log lines. */
    fun power(ctx: Context): String {
        val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return "battery ?"
        val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / b.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = when (b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "discharging"
            else -> "?"
        }
        val plug = when (b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_USB -> " (USB)"
            BatteryManager.BATTERY_PLUGGED_AC -> " (charger)"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> " (wireless)"
            else -> ""
        }
        return "battery %d %% %s%s %.1f °C".format(Locale.ROOT, level, status, plug, b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0)
    }

    /** What the log does not say: app, phone, USB, the boards the app knows, settings, power, storage. */
    fun info(ctx: Context): String = buildString {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date())
        appendLine("ATV RX/TX ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), report $now")
        appendLine("phone ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} SDK ${Build.VERSION.SDK_INT}, " +
            "kernel ${System.getProperty("os.version")}, ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine(power(ctx))
        try { appendLine("free storage %.1f GB".format(Locale.ROOT, StatFs(Environment.getExternalStorageDirectory().path).availableBytes / 1e9)) }
        catch (_: Exception) {}
        val m = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val devs = m.deviceList.values.sortedBy { it.deviceName }
        appendLine()
        appendLine("USB: ${devs.size} device(s), ${UsbBoards.all(m).size} ESP32 (303a:1001)" +
            if (devs.size > 1) " – more than one: a hub" else "")
        for (d in devs) {
            val serial = UsbBoards.serial(m, d)
            appendLine("  ${AtvApp.device(d)} · class ${d.deviceClass} · ${d.interfaceCount} interface(s) · " +
                (if (m.hasPermission(d)) "permission, serial ${serial ?: "?"}" else "no permission yet") +
                (serial?.let { UsbBoards.remembered(ctx, it) }?.let { " · ${it.label}" } ?: ""))
        }
        for (name in listOf("boards", "updates", "dccal", "rx", "tx")) {
            val all = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all
            if (all.isEmpty()) continue
            appendLine()
            appendLine("[$name]")
            all.toSortedMap().forEach { (k, v) -> appendLine("  $k = ${v.toString().replace("\n", " | ")}") }
        }
    }

    /** The app's own logcat (any app may read its own), the last lines. */
    private fun logcat(): String = try {
        val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "3000", "--pid=${android.os.Process.myPid()}")
            .redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread { try { p.inputStream.bufferedReader().use { r -> r.lineSequence().forEach { out.appendLine(it) } } } catch (_: IOException) {} }
            .apply { start() }
        reader.join(5000)
        p.destroy()
        out.toString()
    } catch (e: Exception) { "logcat: ${e.message}\n" }

    /** The zip (in cacheDir/diag, the older ones go) with [note], the user's words, at the top of info.txt. */
    fun build(ctx: Context, note: String): File {
        val dir = File(ctx.cacheDir, "diag").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.ROOT).format(Date())
        val zip = File(dir, "atv-diag_${BuildConfig.VERSION_NAME}_$stamp.zip")
        AppLog.i("APP", "diagnostics ${zip.name}")
        ZipOutputStream(zip.outputStream()).use { z ->
            fun put(name: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
            put("info.txt", ((if (note.isNotBlank()) "What happened:\n${note.trim()}\n\n" else "") + info(ctx)).toByteArray())
            for (f in AppLog.files()) put(f.name, f.readBytes())
            put("logcat.txt", logcat().toByteArray())
        }
        return zip
    }

    /** "Report a problem": what happened, then e-mail (share sheet) or a GitHub issue. */
    fun dialog(a: Activity) {
        val text = EditText(a).apply {
            hint = "What happened? (RX / TX, hub or direct, what you saw)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
        }
        val box = android.widget.FrameLayout(a).apply { setPadding(48, 16, 48, 0); addView(text) }
        AlertDialog.Builder(a)
            .setTitle("Report a problem")
            .setMessage("Sends the app's log, the phone model, the USB devices (the boards' MAC addresses) and the app's " +
                "settings, nothing else. Leave the boards connected as they were when it happened.")
            .setView(box)
            .setPositiveButton("E-mail…") { _, _ -> prepare(a, text.text.toString()) { zip -> email(a, zip, text.text.toString()) } }
            .setNeutralButton("GitHub issue") { _, _ -> prepare(a, text.text.toString()) { zip -> github(a, zip, text.text.toString()) } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun prepare(a: Activity, note: String, then: (File) -> Unit) = Thread {
        val zip = try { build(a, note) } catch (e: Exception) {
            a.runOnUiThread { android.widget.Toast.makeText(a, "diagnostics: ${e.message}", android.widget.Toast.LENGTH_LONG).show() }
            return@Thread
        }
        a.runOnUiThread { if (!a.isFinishing && !a.isDestroyed) then(zip) }
    }.start()

    private fun email(a: Activity, zip: File, note: String) {
        val uri = DiagProvider.uri(a, zip)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            if (EMAIL.isNotEmpty()) putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, "ATV RX/TX ${BuildConfig.VERSION_NAME}: problem report")
            putExtra(Intent.EXTRA_TEXT, (if (note.isNotBlank()) note.trim() + "\n\n" else "") +
                "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n(the log is in the attached ${zip.name})")
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(zip.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        a.startActivity(Intent.createChooser(send, "Send the report"))
    }

    /** A copy of the zip in Download/ATV RX/ (the browser's file picker finds it there) and a new issue with a summary. */
    private fun github(a: Activity, zip: File, note: String) {
        val where = try { saveToDownloads(a, zip) } catch (e: Exception) { "(could not save it: ${e.message})" }
        val info = info(a).let { if (it.length > ISSUE_INFO_CHARS) it.take(ISSUE_INFO_CHARS) + "\n…" else it }
        val body = "**What happened**\n\n${note.trim().ifEmpty { "…" }}\n\n" +
            "**Log**: please attach `$where` (drag it here or click below to attach).\n\n" +
            "<details><summary>info</summary>\n\n```\n$info```\n</details>\n"
        val url = Uri.parse(ISSUES).buildUpon()
            .appendQueryParameter("title", "${BuildConfig.VERSION_NAME}: ${note.trim().lineSequence().firstOrNull()?.take(60)?.ifEmpty { null } ?: "problem report"}")
            .appendQueryParameter("body", body)
            .build()
        android.widget.Toast.makeText(a, "The log is in $where: attach it to the issue", android.widget.Toast.LENGTH_LONG).show()
        a.startActivity(Intent(Intent.ACTION_VIEW, url))
    }

    /** Android 10+: Download/ATV RX/ through MediaStore (no permission); Android 9: the app's own Download folder. */
    private fun saveToDownloads(ctx: Context, zip: File): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, zip.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            }
            val r = ctx.contentResolver
            val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw IOException("cannot create ${zip.name}")
            (r.openOutputStream(uri) ?: throw IOException("cannot open ${zip.name}")).use { o -> zip.inputStream().use { it.copyTo(o) } }
            return "Download/$FOLDER/${zip.name}"
        }
        val f = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), zip.name)
        zip.copyTo(f, overwrite = true)
        return f.path
    }
}
