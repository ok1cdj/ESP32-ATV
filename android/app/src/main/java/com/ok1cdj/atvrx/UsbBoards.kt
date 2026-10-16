package com.ok1cdj.atvrx

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

/**
 * The boards on USB: both the XIAO ESP32-S3 (receiver) and the XIAO ESP32-C3 (transmitter) are 303a:1001, so with a hub
 * they are told apart by their USB serial number, which is their MAC address (as boards.sh does on the PC). The role of a
 * MAC is found once by asking the board (the C3 answers INFO, the S3 the receiver's protocol) and remembered.
 * Android lets the app read a serial number only with the permission for that device, so [pick] asks for it device by
 * device. With a single board connected nothing changes: it is used for either role, as before.
 */
object UsbBoards {
    enum class Role(val label: String) { S3("ESP32-S3 receiver"), C3("ESP32-C3 transmitter") }

    sealed interface Pick {
        class Found(val dev: UsbDevice) : Pick
        /** Ask for the permission for this device (the activity's receiver calls the retry when granted). */
        class Ask(val dev: UsbDevice) : Pick
        /** A board is being identified in the background; retry runs on the UI thread when it is done. */
        object Wait : Pick
        class Missing(val message: String) : Pick
    }

    private const val PREFS = "boards"

    fun all(m: UsbManager): List<UsbDevice> =
        m.deviceList.values.filter { it.vendorId == 0x303A && it.productId == 0x1001 }.sortedBy { it.deviceName }

    fun serial(m: UsbManager, dev: UsbDevice): String? =
        if (m.hasPermission(dev)) try { dev.serialNumber } catch (_: SecurityException) { null } else null

    fun remembered(ctx: Context, mac: String): Role? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(mac, null)?.let { r -> Role.entries.firstOrNull { it.name == r } }

    fun remember(ctx: Context, mac: String, role: Role) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(mac, role.name).apply()

    @Volatile private var identifying = false

    /**
     * The device for [role]. [retry] is called on the UI thread after a background identification ([Pick.Wait]);
     * after [Pick.Ask] the caller requests the permission ([requestPermission]) and tries again when it is granted.
     */
    fun pick(activity: Activity, role: Role, retry: () -> Unit): Pick {
        val m = activity.getSystemService(Context.USB_SERVICE) as UsbManager
        val devs = all(m)
        if (devs.isEmpty()) return Pick.Missing("no ESP32 (303a:1001) on USB")
        if (devs.size == 1) return if (m.hasPermission(devs[0])) Pick.Found(devs[0]) else Pick.Ask(devs[0])
        devs.firstOrNull { !m.hasPermission(it) }?.let { return Pick.Ask(it) }
        val known = devs.map { d -> d to serial(m, d)?.let { remembered(activity, it) } }
        known.firstOrNull { it.second == role }?.let { return Pick.Found(it.first) }
        val unknown = known.filter { it.second == null }.map { it.first }
        if (unknown.isEmpty()) {
            val others = known.joinToString { "${it.second?.label} ${serial(m, it.first)}" }
            return Pick.Missing("the ${role.label} is not connected (on USB: $others)")
        }
        if (!identifying) {
            identifying = true
            Thread({
                try { for (d in unknown) identify(activity, m, d) } finally { identifying = false }
                activity.runOnUiThread(retry)
            }, "usb-identify").start()
        }
        return Pick.Wait
    }

    /** Asks for the permission for [dev]; the answer is broadcast to [action] (the activity's receiver). */
    fun requestPermission(activity: Activity, dev: UsbDevice, action: String = "${activity.packageName}.USB_PERMISSION") {
        val m = activity.getSystemService(Context.USB_SERVICE) as UsbManager
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        m.requestPermission(dev, PendingIntent.getBroadcast(activity, 0, Intent(action).setPackage(activity.packageName), flags))
    }

    /** Asks the board what it is (blocking, about 2 s) and remembers it; null if neither answers. */
    fun identify(ctx: Context, m: UsbManager, dev: UsbDevice): Role? {
        val mac = serial(m, dev) ?: return null
        val role = try {
            val conn = m.openDevice(dev) ?: return null
            val c3 = C3Link(UsbCdc(conn, dev))
            val isC3 = try { Thread.sleep(1200); c3.info() != null } finally { c3.close() }      // opening may reset the C3
            if (isC3) Role.C3 else {
                val conn2 = m.openDevice(dev) ?: return null
                val l = EspLink(UsbCdc(conn2, dev))
                try { l.checkFirmware(); Role.S3 } catch (_: Exception) { null } finally { l.close() }
            }
        } catch (_: Exception) { null }
        if (role != null) remember(ctx, mac, role)
        return role
    }

    /** A line for the log: what is connected. */
    fun describe(ctx: Context, m: UsbManager): String {
        val devs = all(m)
        if (devs.isEmpty()) return "no ESP32 (303a:1001) on USB"
        return devs.joinToString("; ") { d ->
            val s = serial(m, d)
            "${s?.let { remembered(ctx, it)?.label } ?: "ESP32"} ${s ?: "(no permission yet)"}"
        }
    }
}
