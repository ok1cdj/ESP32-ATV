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
                <p>DVB-S2 amateur television on 13 cm: an ESP32-S3 (XIAO) on USB-OTG as the receiver, an ESP32-C3
                (XIAO) as the transmitter, leandvb, ldpc_tool and our DVB-S2 encoder on the phone. Beta.</p>
                <p><b>⚠ Transmitting needs an amateur radio licence.</b> The TX mode transmits on the 13 cm amateur
                band (2.3–2.45 GHz): only with your own callsign, on frequencies and at power levels your licence and
                national band plan allow. Without a licence it is illegal and disturbs other users of the band
                (Wi-Fi …). Receiving needs no licence.</p>
                <p>Guide / návod: <a href="$GUIDE">$GUIDE</a><br>
                Source: <a href="$SOURCE">$SOURCE</a><br>
                Support the project: <a href="$COFFEE">☕ Buy Me a Coffee</a></p>
                <h3>Credits</h3>
                <p>Built on the work of:<br>
                <b>SP8ESA</b> – <a href="https://github.com/SP8ESA/ESP32-DATV">ESP32-DATV</a>, the DVB-S / DVB-S2
                modulator on the ESP32-C3's Wi-Fi RF hardware.<br>
                <b>Baris Dinc</b> – <a href="https://github.com/barisdinc/ESP32-DATV-SA">ESP32-DATV-SA</a> (stand-alone
                beacon, the C3 firmware) and <a href="https://github.com/barisdinc/ESP32-DATV-RX">ESP32-DATV-RX</a>.<br>
                <b>h0m3us3r</b> – <a href="https://github.com/h0m3us3r/eSpDR">eSpDR</a>, the ESP32-S3 as an SDR.<br>
                <b>Jochen Hammes</b> – <a href="https://github.com/jochenhammes/esp32-sdr-trx">esp32-sdr-trx</a>, the
                receiver firmware.<br>
                <b>pabr</b> – <a href="https://github.com/pabr/leansdr">leansdr / leandvb</a>, the DVB-S2 demodulator.<br>
                <b>Ahmet Inan</b> – <a href="https://github.com/xdsopl/LDPC">LDPC</a>, and <b>Blockstream</b> –
                <a href="https://github.com/Blockstream/LDPC">its fork</a>: ldpc_tool and the LDPC tables.<br>
                <b>lieff</b> – <a href="https://github.com/lieff/minimp3">minimp3</a>, MP2 / MP3 sound.</p>
                <h3>Licences</h3>
                <p><b>ATV RX/TX</b> – GNU GPL v3 or later.<br>
                <b>leansdr / leandvb</b> (pabr) – GNU GPL v3.<br>
                <b>LDPC / ldpc_tool</b> (Ahmet Inan, Blockstream) – 0BSD-style permissive licence.<br>
                <b>minimp3</b> (lieff) – CC0 (MP2 / MP3 sound).<br>
                <b>ESP32-DATV-RX</b> (Baris Dinc) – BSD-3-Clause; <b>esp32-sdr-trx / eSpDR</b> – 0BSD
                (the receiver firmware of the ESP32-S3, not part of this app).<br>
                <b>ESP32-DATV-SA</b> (SP8ESA, Baris Dinc) – PolyForm Noncommercial (the transmitter firmware of the
                ESP32-C3, downloaded by the Firmware screen, not part of this app).</p>
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
        const val COFFEE = "https://buymeacoffee.com/ok1cdj"
    }
}
