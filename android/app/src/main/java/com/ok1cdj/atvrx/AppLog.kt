package com.ok1cdj.atvrx

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

/**
 * The app's log for problem reports ([Diagnostics]): every screen's and service's log lines, USB events, the
 * receiver's and transmitter's counters every few seconds and crashes, with a time and a source, in files that outlive
 * the app (filesDir/log/app.log, rotated at [MAX_BYTES] into app.1.log). With both boards on a hub the phone's USB port
 * is taken, so adb logcat is out of reach for most users: this is what they can send instead.
 */
object AppLog {
    private const val MAX_BYTES = 1_000_000L
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT)
    private val queue = LinkedBlockingQueue<String>()
    private lateinit var dir: File
    private val current get() = File(dir, "app.log")
    private val previous get() = File(dir, "app.1.log")

    /** From [AtvApp.onCreate]: the writer thread, and crashes into the log before the app goes. */
    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "log").apply { mkdirs() }
        Thread({ writeLoop() }, "app-log").apply { isDaemon = true; start() }
        val chained = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                synchronized(this) { append(line("CRASH", "thread ${t.name}: ${android.util.Log.getStackTraceString(e)}")) }
            } catch (_: Throwable) {}
            chained?.uncaughtException(t, e)
        }
    }

    /** A line from [tag] (RX, TX, USB, FW, …), also to logcat. Any thread, never blocks. */
    fun i(tag: String, s: String) {
        android.util.Log.i("atvrx-$tag", s)
        if (::dir.isInitialized) queue.offer(line(tag, s))
    }

    /** The log files, oldest first (after the lines waiting to be written are in). */
    fun files(): List<File> {
        if (!::dir.isInitialized) return emptyList()
        synchronized(this) { drain() }
        return listOf(previous, current).filter { it.exists() }
    }

    private fun line(tag: String, s: String) = synchronized(time) { "${time.format(Date())} $tag ${s.trimEnd()}\n" }

    private fun writeLoop() {
        while (true) {
            val first = queue.take()
            synchronized(this) { append(first); drain() }
        }
    }

    private fun drain() {
        while (true) append(queue.poll() ?: return)
    }

    private fun append(s: String) {
        try {
            if (current.length() > MAX_BYTES) { previous.delete(); current.renameTo(previous) }
            FileWriter(current, true).use { it.write(s) }
        } catch (_: Exception) {}
    }
}
