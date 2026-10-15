package com.ok1cdj.atvrx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Foreground service that owns the [Transmitter], so that TX goes on with the screen off or the app in the
 * background (type connectedDevice, plus camera / microphone when they are used: they may only be used in the
 * background by a service of that type started while the app was in front). The notification shows the state and has
 * a Stop action; [TxActivity] binds to it for the status and the log.
 */
class TxService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@TxService }

    @Volatile var tx: Transmitter? = null; private set
    val log = ArrayDeque<String>()
    @Volatile var onLog: ((String) -> Unit)? = null

    private val binder = LocalBinder()
    private val ui = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private val tick = Runnable { updateNotification() }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopTx()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopTx()
        super.onDestroy()
    }

    fun say(s: String) {
        android.util.Log.i("atvrx-tx", s)
        synchronized(log) { log.addLast(s); while (log.size > 300) log.removeFirst() }
        ui.post { onLog?.invoke(s) }
    }

    /** Starts TX on [device] (USB permission granted; CAMERA too when the camera is the picture). */
    fun startTx(cfg: Transmitter.Config, device: UsbDevice) {
        if (tx != null) return
        startForegroundService(Intent(this, TxService::class.java))
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            (if (cfg.source != Transmitter.SOURCE_CARD) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0) or
            (if (cfg.sound == Transmitter.SOUND_MIC || cfg.sound == Transmitter.SOUND_MIC_CW) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        startForeground(NOTIFICATION_ID, notification("starting", cfg), type)
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "atvrx:transmitter").apply { acquire() }
        val t = Transmitter(this, getSystemService(USB_SERVICE) as UsbManager, device, cfg, ::say)
        tx = t
        t.start()
        ui.postDelayed(tick, 2000)
    }

    fun stopTx() {
        val t = tx ?: return
        tx = null
        ui.removeCallbacks(tick)
        Thread { t.stop() }.start()
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        say("TX stopped")
    }

    private fun updateNotification() {
        val t = tx ?: return
        val s = t.status
        if (s.state.startsWith("error") || s.state == "stopped") { stopTx(); return }
        val secs = if (s.since > 0) (System.nanoTime() - s.since) / 1_000_000_000 else 0
        notifications.notify(NOTIFICATION_ID, notification("${s.state} · ${secs} s · C3 buffer ${s.fill}", t.cfg))
        ui.postDelayed(tick, 2000)
    }

    private fun notification(text: String, cfg: Transmitter.Config): Notification {
        if (notifications.getNotificationChannel(CHANNEL) == null)
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Transmitter", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, TxActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TxService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rx)
            .setContentTitle("ATV TX %.3f MHz · %s %s · %.1f kS/s".format(cfg.freqKhz / 1000.0, cfg.const, cfg.rate, cfg.baud / 1e3))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop TX", stop).build())
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.ok1cdj.atvrx.TX_STOP"
        private const val CHANNEL = "tx"
        private const val NOTIFICATION_ID = 2
    }
}
