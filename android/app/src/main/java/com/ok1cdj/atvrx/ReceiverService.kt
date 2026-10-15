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
import android.view.Surface

/**
 * Foreground service that owns the receiver, so that reception goes on with the screen off or the app in the
 * background. The activity binds to it, hands it the picture Surface while it is visible, and reads the status.
 * The notification shows lock / MER / service and has a Stop action.
 */
class ReceiverService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@ReceiverService }

    @Volatile var receiver: Receiver? = null; private set
    val video = VideoDecoder()
    @Volatile var startedAt = 0L; private set
    /** the last lines of the receiver log, and who shows new ones */
    val log = ArrayDeque<String>()
    @Volatile var onLog: ((String) -> Unit)? = null
    @Volatile var onConstellation: ((String) -> Unit)? = null
        set(v) { field = v; receiver?.onConstellation = v }

    private val binder = LocalBinder()
    private val ui = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private val tick = Runnable { updateNotification() }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopRx()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRx()
        super.onDestroy()
    }

    fun say(s: String) {
        android.util.Log.i("atvrx", s)
        synchronized(log) { log.addLast(s); while (log.size > 300) log.removeFirst() }
        ui.post { onLog?.invoke(s) }
    }

    fun setSurface(s: Surface?) = video.setSurface(s)

    /** Starts receiving from the ESP32-S3 [device] (USB permission granted), or a replay when cfg.replay is set. */
    fun startRx(cfg: Receiver.Config, device: UsbDevice?) {
        if (receiver != null) return
        val conn = device?.let { (getSystemService(USB_SERVICE) as UsbManager).openDevice(it) ?: return say("openDevice failed") }
        // started (not only bound), so that it lives on when the activity goes; foreground at once
        startForegroundService(Intent(this, ReceiverService::class.java))
        startForeground(NOTIFICATION_ID, notification("starting"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "atvrx:receiver").apply { acquire() }
        video.reset()
        val r = Receiver(conn, device, applicationInfo.nativeLibraryDir, cacheDir, cfg, video, ::say)
        r.onConstellation = onConstellation
        receiver = r
        startedAt = System.nanoTime()
        r.start()
        ui.postDelayed(tick, 2000)
    }

    fun stopRx() {
        val r = receiver ?: return
        receiver = null
        ui.removeCallbacks(tick)
        Thread { r.stop() }.start()
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        say("stopped")
    }

    private fun updateNotification() {
        val r = receiver ?: return
        val s = r.status
        val text = if (r.alive()) "MER %.1f dB · %s · %d frames".format(s.mer, r.demux.service ?: "–", s.framesOk + s.framesFixed)
                   else s.state
        notifications.notify(NOTIFICATION_ID, notification(text))
        ui.postDelayed(tick, 2000)
    }

    private fun notification(text: String): Notification {
        if (notifications.getNotificationChannel(CHANNEL) == null)
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Receiver", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ReceiverService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val cfg = receiver?.cfg
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rx)
            .setContentTitle(cfg?.let { "ATV RX %.3f MHz · %.1f kS/s".format(it.freqHz / 1e6, it.sr / 1e3) } ?: "ATV RX")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.ok1cdj.atvrx.STOP"
        private const val CHANNEL = "rx"
        private const val NOTIFICATION_ID = 1
    }
}
