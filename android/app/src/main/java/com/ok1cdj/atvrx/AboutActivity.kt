package com.ok1cdj.atvrx

import android.app.Activity
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.WindowInsets
import android.widget.ScrollView
import android.widget.TextView

/** Version, licences of the parts, where the guide and the source are. */
class AboutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            textSize = 14f
            movementMethod = LinkMovementMethod.getInstance()
            text = android.text.Html.fromHtml("""
                <h2>ATV RX/TX ${BuildConfig.VERSION_NAME}</h2>
                <p>DVB-S2 receiver for amateur television on 13 cm: an ESP32-S3 (XIAO) on USB-OTG as the radio,
                leandvb and ldpc_tool on the phone. Beta – licensed radio amateurs only.</p>
                <p>Guide / návod: <a href="$GUIDE">$GUIDE</a><br>
                Source: <a href="$SOURCE">$SOURCE</a></p>
                <h3>Licences</h3>
                <p><b>ATV RX/TX</b> – GNU GPL v3 or later.<br>
                <b>leansdr / leandvb</b> (pabr) – GNU GPL v3.<br>
                <b>LDPC / ldpc_tool</b> (Ahmet Inan, Blockstream) – 0BSD-style permissive licence.<br>
                <b>ESP32-DATV-RX</b> (Baris Dinc) – BSD-3-Clause; <b>esp32-sdr-trx / eSpDR</b> – 0BSD
                (the receiver firmware of the ESP32-S3, not part of this app).</p>
                <p>This program comes with ABSOLUTELY NO WARRANTY. It is free software, and you are welcome to
                redistribute it under the conditions of the GPL v3: <a href="https://www.gnu.org/licenses/gpl-3.0.html">gnu.org/licenses/gpl-3.0</a>.</p>
            """.trimIndent(), android.text.Html.FROM_HTML_MODE_COMPACT)
        }
        val scroll = ScrollView(this).apply { addView(text) }
        setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(32 + bars.left, 32 + bars.top, 32 + bars.right, 32 + bars.bottom)
            insets
        }
    }

    companion object {
        const val GUIDE = "https://ok1cdj.github.io/ESP32-ATV/"
        const val SOURCE = "https://github.com/ok1cdj/ESP32-ATV"
    }
}
