package com.ok1cdj.atvrx

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import java.io.File

/** Debug only: runs [MuxSelfTest] with the intent's "args" (see there); the TS and the log go to the app's files dir. */
class MuxSelfTestActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val text = TextView(this).apply { textSize = 14f }
        setContentView(text)
        val a = (intent.getStringExtra("args") ?: "484000 20 st.ts 64000").trim().split(Regex("\\s+")).toTypedArray()
        val dir = getExternalFilesDir(null)!!
        a[2] = File(dir, a[2]).path
        val log = File(a[2] + ".log")
        log.delete()
        Thread {
            val out: (String) -> Unit = { line -> log.appendText(line + "\n"); runOnUiThread { text.append(line + "\n") } }
            try { MuxSelfTest.run(a, out) } catch (e: Throwable) { out("ERROR $e") }
            out("DONE")
        }.start()
    }
}
