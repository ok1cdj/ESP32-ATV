package com.ok1cdj.atvrx

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream

/**
 * Debug only: replays the newest .raw (rx/make_replay.py) at the symbol rate given, logs the sound once a second and
 * writes the decoded PCM (s16le, as played) to the files dir:
 *   adb shell am start -n com.ok1cdj.atvrx/.RxReplayTestActivity --es args "SR SECONDS NAME [normal|short]"
 *   -> NAME.log, NAME.pcm (first line of the log: channels and rate)
 */
class RxReplayTestActivity : Activity() {
    private var connection: ServiceConnection? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val text = TextView(this).apply { textSize = 12f }
        setContentView(text)
        val a = (intent.getStringExtra("args") ?: "66666 30 replay").trim().split(Regex("\\s+"))
        val sr = a[0].toInt(); val secs = a[1].toInt(); val name = a[2]
        val frames = if (a.getOrNull(3) == "short") Receiver.FRAMES_SHORT else Receiver.FRAMES_NORMAL
        val dir = getExternalFilesDir(null)!!
        val raw = dir.listFiles { x -> x.name.endsWith(".raw") }!!.maxByOrNull { it.lastModified() }!!
        val log = File(dir, "$name.log").apply { delete() }
        val pcm = FileOutputStream(File(dir, "$name.pcm"))
        fun out(s: String) { log.appendText(s + "\n"); runOnUiThread { text.append(s + "\n") } }
        val conn = object : ServiceConnection {
            override fun onServiceConnected(n: ComponentName, b: IBinder) {
                val svc = (b as ReceiverService.LocalBinder).service
                var fmt = ""
                svc.audio.tap = { p, k, ch, hz ->
                    if (fmt.isEmpty()) { fmt = "$ch $hz"; out("pcm $fmt") }
                    val bb = java.nio.ByteBuffer.allocate(2 * k).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    bb.asShortBuffer().put(p, 0, k); pcm.write(bb.array())
                }
                out("replay ${raw.name} at $sr S/s")
                svc.startRx(Receiver.Config(freqHz = 0, sr = sr, gainDb = 0.0, frameSizes = frames, replay = raw), null)
                Thread {
                    for (t in 1..secs) {
                        Thread.sleep(1000)
                        val r = svc.receiver ?: break
                        val d = r.demux; val au = svc.audio
                        out("%2d s  frames %d  TS %d  audio pid %d type %s  %s  pes %d drop %d%s  video %d".format(t,
                            r.status.framesOk + r.status.framesFixed, d.packets, d.audioPid, TsDemux.audioName(d.audioType),
                            au.description ?: "–", au.frames, au.dropped, au.error?.let { "  ERR $it" } ?: "", svc.video.frames))
                    }
                    svc.audio.tap = null
                    svc.stopRx()
                    pcm.close()
                    out("DONE")
                }.start()
            }
            override fun onServiceDisconnected(n: ComponentName) {}
        }
        connection = conn
        bindService(Intent(this, ReceiverService::class.java), conn, BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        connection?.let { unbindService(it) }
        super.onDestroy()
    }
}
