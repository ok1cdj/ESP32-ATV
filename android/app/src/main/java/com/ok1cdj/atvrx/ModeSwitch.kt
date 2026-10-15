package com.ok1cdj.atvrx

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout

/**
 * The RX | TX switch at the top of both screens: the active side coloured (RX blue, TX red), the other grey and
 * clickable. RX uses the ESP32-S3, TX the ESP32-C3; both are 303a:1001 on the one USB port.
 */
fun modeSwitch(ctx: Context, tx: Boolean, onRx: () -> Unit, onTx: () -> Unit) = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL
    fun side(text: String, active: Boolean, colour: Int, onClick: () -> Unit) = Button(ctx).apply {
        this.text = text; isAllCaps = false; textSize = 15f
        setTextColor(if (active) Color.WHITE else Color.LTGRAY)
        background = GradientDrawable().apply { setColor(if (active) colour else Color.rgb(0x3A, 0x3A, 0x3A)); cornerRadius = 16f }
        isEnabled = !active
        setOnClickListener { onClick() }
    }
    addView(side("RX  ·  receive (ESP32-S3)", !tx, Color.rgb(0x1E, 0x5A, 0xA8), onRx),
        LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = 8 })
    addView(side("TX  ·  transmit (ESP32-C3)", tx, Color.rgb(0xA8, 0x2E, 0x1E), onTx),
        LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
}
