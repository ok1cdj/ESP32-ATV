package com.ok1cdj.atvrx

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

/** Starts the app's log ([AppLog]) and writes USB devices coming and going into it (a board dropping off a hub). */
class AtvApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.i("APP", "start ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${Build.MANUFACTURER} ${Build.MODEL}, " +
            "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        val usb = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                val what = if (i.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) "attached" else "detached"
                val m = getSystemService(USB_SERVICE) as UsbManager
                AppLog.i("USB", "$what ${d?.let(::device) ?: "?"}; now ${m.deviceList.size} device(s): ${UsbBoards.describe(c, m)}")
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usb, filter, RECEIVER_EXPORTED)    // sent by the system
        else registerReceiver(usb, filter)
    }

    companion object {
        /** "303a:1001 Espressif USB JTAG/serial debug unit /dev/bus/usb/001/003" */
        fun device(d: UsbDevice) = "%04x:%04x %s %s %s".format(d.vendorId, d.productId, d.manufacturerName ?: "", d.productName ?: "",
            d.deviceName).replace(Regex(" +"), " ")
    }
}
