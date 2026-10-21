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
 * The notification shows lock / MER / service and has a Stop action. It also owns the recording ([TsRecorder]).
 */
class ReceiverService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@ReceiverService }

    @Volatile var receiver: Receiver? = null; private set
    val video = VideoDecoder()
    val audio = AudioPlayer().also { a -> video.clock = { a.clock() } }   // with sound the picture follows its clock
    @Volatile var startedAt = 0L; private set
    @Volatile var recorder: TsRecorder? = null; private set
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
        if (intent?.action == ACTION_STOP_REC) stopRec()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRx()
        audio.release()
        super.onDestroy()
    }

    fun say(s: String) {
        AppLog.i("RX", s)
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
        audio.reset()
        val r = Receiver(conn, device, applicationInfo.nativeLibraryDir, cacheDir, cfg, video, ::say)
        r.demux.onAudio = { type, es, pts -> audio.feed(type, es, pts) }
        r.onConstellation = onConstellation
        receiver = r
        startedAt = System.nanoTime()
        statsTicks = 0
        AppLog.i("RX", "start %s, %.3f MHz %.1f kS/s %s %s %s gain %.0f dB drift %s · %s · %s".format(java.util.Locale.ROOT,
            device?.let(AtvApp::device) ?: "replay ${cfg.replay?.name}", cfg.freqHz / 1e6, cfg.sr / 1e3, cfg.const, cfg.cr,
            Receiver.frameSizeName(cfg.frameSizes), cfg.gainDb, cfg.track,
            UsbBoards.describe(this, getSystemService(USB_SERVICE) as UsbManager), Diagnostics.power(this)))
        r.start()
        ui.postDelayed(tick, 2000)
    }

    /** Records the received TS (and a .srt with the measurements) until [stopRec] or the end of reception. */
    fun startRec() {
        val r = receiver ?: return
        if (recorder?.active == true) return
        val t = java.text.SimpleDateFormat("yyyy-MM-dd_HHmmss", java.util.Locale.ROOT).format(java.util.Date())
        val call = r.demux.service?.replace(Regex("[^A-Za-z0-9-]+"), "")?.take(16)?.takeIf { it.isNotEmpty() }
        val where = if (r.cfg.replay != null) "replay" else "%.3fMHz".format(java.util.Locale.ROOT, r.cfg.freqHz / 1e6)
        val base = "ATV_${t}_${where}_%dk".format(java.util.Locale.ROOT, r.cfg.sr / 1000) + (call?.let { "_$it" } ?: "")
        var frames = -1L
        val subtitle = {
            val s = r.status
            val done = s.framesOk + s.framesFixed
            val got = if (frames < 0) 0 else done - frames
            frames = done
            val clock = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
            if (!r.alive()) "$clock  no signal (${s.state})"
            else "$clock  MER %.1f dB%s\ncarrier %+.2f kHz  frames %d/s  bad %d".format(java.util.Locale.ROOT, s.mer,
                if (!r.cnrMeasured) "" else "  CNR %s dB".format(if (s.cnr.isNaN()) "–" else "%.1f".format(java.util.Locale.ROOT, s.cnr)), (s.loShiftHz + s.offsetHz + (if (s.freq.isNaN()) 0.0 else s.freq)) / 1e3,
                got, s.framesBad)
        }
        try {
            val rec = TsRecorder(this, base, r.demux, subtitle, ::say)
            recorder = rec
            r.onTs = rec::feed
            say("recording: ${rec.where}")
            updateNotification()
        } catch (e: Exception) { say("recording: ${e.message}") }
    }

    fun stopRec() {
        val rec = recorder ?: return
        recorder = null
        receiver?.onTs = null
        Thread {
            rec.stop()
            if (rec.bytes == 0L) say("recording: nothing decoded, only the .srt (MER log) kept: ${rec.where.removeSuffix(".ts")}.srt")
            else say("recording saved: ${rec.where}, %.1f MB%s".format(rec.bytes / 1e6, if (rec.dropped > 0) ", %d bytes lost (storage too slow)".format(rec.dropped) else ""))
        }.start()
        ui.post { updateNotification() }
    }

    fun stopRx() {
        val r = receiver ?: return
        stopRec()
        receiver = null
        ui.removeCallbacks(tick)
        logStats(r)
        Thread { r.stop() }.start()
        audio.reset()
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        say("stopped")
    }

    private fun updateNotification() {
        val r = receiver ?: return
        if (recorder?.active == false) stopRec()                          // it stopped itself (storage full, error)
        if (++statsTicks % STATS_TICKS == 0) logStats(r)
        val s = r.status
        val text = if (r.alive()) "MER %.1f dB · %s · %d frames".format(s.mer, r.demux.service ?: "–", s.framesOk + s.framesFixed)
                   else s.state
        notifications.notify(NOTIFICATION_ID, notification(recorder?.let { "● REC %.1f MB · ".format(it.bytes / 1e6) }.orEmpty() + text))
        ui.removeCallbacks(tick)
        ui.postDelayed(tick, 2000)
    }

    private var statsTicks = 0

    /** The counters into the app's log only (not the screen): what a problem report needs to tell a lossy hub from no signal. */
    private fun logStats(r: Receiver) {
        val s = r.status; val u = r.link?.stats; val d = r.demux
        AppLog.i("RX", ("stats %.0f s %s lock %s MER %.1f CNR %.1f frames ok %d fixed %d bad %d locks %d · USB packets %d lost %d " +
            "jumps %d ESP drop %d skipped %d bad %d queue %d · TS %d pkts TEI %d CC %d · video %d frames%s · %s").format(
            java.util.Locale.ROOT, (System.nanoTime() - startedAt) / 1e9, s.state, s.lock, s.mer, s.cnr, s.framesOk, s.framesFixed,
            s.framesBad, s.locks, u?.packets ?: 0, u?.lost ?: 0, u?.jumps ?: 0, u?.dropped ?: 0, u?.skipped ?: 0, u?.bad ?: 0,
            s.queueDrops, d.packets, d.errorPackets, d.ccErrors, video.frames, video.error?.let { " ERR $it" } ?: "",
            Diagnostics.power(this)))
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
            .apply {
                if (recorder != null) addAction(Notification.Action.Builder(null, "Stop REC",
                    PendingIntent.getService(this@ReceiverService, 2, Intent(this@ReceiverService, ReceiverService::class.java)
                        .setAction(ACTION_STOP_REC), PendingIntent.FLAG_IMMUTABLE)).build())
            }
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.ok1cdj.atvrx.STOP"
        const val ACTION_STOP_REC = "com.ok1cdj.atvrx.STOP_REC"
        private const val CHANNEL = "rx"
        private const val NOTIFICATION_ID = 1
        private const val STATS_TICKS = 5                                 // of the notification's 2 s: every 10 s
    }
}
