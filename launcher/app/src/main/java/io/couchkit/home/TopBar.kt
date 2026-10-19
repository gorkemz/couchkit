// SPDX-License-Identifier: Apache-2.0
package io.couchkit.home

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Top-corner widget: a horizontal row with thin dividers, no background (text shadows keep it readable). */
open class GlassPill(ctx: Context, bd: Backdrop, height: Float) : FrameLayout(ctx) {
    protected val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, 0)
    }

    init {
        clipChildren = false
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
    }

    protected fun text(tf: Typeface, size: Float, color: Int) = TextView(context).apply {
        typeface = tf; setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(size)); setTextColor(color)
        includeFontPadding = false; isSingleLine = true
        // Readable on light pictures too: darker, tighter shadow
        setShadowLayer(Ui.px(10f), 0f, Ui.px(1.5f), 0xD9000000.toInt())
    }

    protected fun divider() = View(context).apply { setBackgroundColor(0x38FFFFFF) }.also {
        row.addView(it, LinearLayout.LayoutParams(Ui.pxi(2f), Ui.pxi(32f)).apply {
            leftMargin = Ui.pxi(22f); rightMargin = Ui.pxi(22f)
        })
    }
}

/** Small attribution line (data-source credits), shown only while that data is on screen. */
fun creditText(ctx: Context, tf: Typeface, text: CharSequence) = TextView(ctx).apply {
    typeface = tf; setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(14f)); setTextColor(0x99FFFFFF.toInt())
    includeFontPadding = false; isSingleLine = true; this.text = text
    setShadowLayer(Ui.px(6f), 0f, Ui.px(1f), 0xB3000000.toInt())
}

/** Top right: [icon] 11°  Partly cloudy  │  21:00 (weather part only when configured). */
class WeatherClockPill(ctx: Context, bd: Backdrop, semi: Typeface, regular: Typeface) : GlassPill(ctx, bd, 76f) {
    private val icon = ImageView(ctx)
    private val temp = text(semi, 30f, 0xFFFFFFFF.toInt())
    private val cond = text(regular, 22f, 0xE6FFFFFF.toInt())
    private val weatherViews = ArrayList<View>()

    init {
        row.addView(icon, LinearLayout.LayoutParams(Ui.pxi(50f), Ui.pxi(50f)))
        row.addView(temp, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.pxi(8f) })
        row.addView(cond, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.pxi(12f) })
        val div = divider()
        weatherViews.addAll(listOf(icon, temp, cond, div))
        weatherViews.forEach { it.visibility = GONE }
        val clock = TextClock(ctx).apply {
            format24Hour = "HH:mm"; format12Hour = "h:mm"
            typeface = semi; setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(30f)); setTextColor(0xFFFFFFFF.toInt())
            includeFontPadding = false; fontFeatureSettings = "tnum"
            setShadowLayer(Ui.px(12f), 0f, Ui.px(1.5f), 0x99000000.toInt())
        }
        row.addView(clock)
    }

    val hasWeather get() = icon.visibility == VISIBLE

    fun setWeather(w: Weather.Now) {
        icon.setImageResource(Weather.icon(w.code, w.isDay))
        temp.text = "${w.temp}°"
        cond.text = listOf(Weather.label, w.text).filter { it.isNotEmpty() }.joinToString("  ·  ")
        weatherViews.forEach { it.visibility = VISIBLE }
    }

    fun clearWeather() { weatherViews.forEach { it.visibility = GONE } }
}

/** Top left: USD/EUR 0.92 ▲ │ USD/TRY 41.80 ▼ — small grey label, white value. Hidden until configured. */
class FxPill(ctx: Context, bd: Backdrop, private val semi: Typeface) : GlassPill(ctx, bd, 76f) {
    private val sym = DecimalFormatSymbols(Locale.getDefault())

    init { visibility = INVISIBLE }

    fun set(rates: List<Fx.Rate>) {
        row.removeAllViews()
        for ((i, r) in rates.take(4).withIndex()) {
            if (i > 0) divider()
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            // Letter-spaced single-line text loses its last letter on Android 11: small right padding.
            // Vertical LinearLayout defaults to MATCH_PARENT width, which squeezed the label: use WRAP_CONTENT.
            col.addView(text(semi, 15f, 0xE6FFFFFF.toInt()).apply {
                text = r.label; letterSpacing = 0.14f; setPadding(0, 0, Ui.pxi(4f), 0)
            }, LinearLayout.LayoutParams(-2, -2))
            val vr = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val v = text(semi, 25f, 0xFFFFFFFF.toInt()).apply {
                fontFeatureSettings = "tnum"
                text = DecimalFormat(when { r.value >= 1000 -> "#,##0"; r.value >= 10 -> "#,##0.00"; else -> "0.0000" }, sym).format(r.value)
            }
            val a = text(semi, 13f, if (r.change > 0) 0xFF4CD964.toInt() else 0xFFFF6B5E.toInt()).apply {
                text = when { r.change > 0 -> "▲"; r.change < 0 -> "▼"; else -> "" }
            }
            vr.addView(v); vr.addView(a, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.pxi(7f) })
            col.addView(vr, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.pxi(4f) })
            row.addView(col)
        }
        visibility = if (rates.isEmpty()) INVISIBLE else VISIBLE
    }

    fun clear() { row.removeAllViews(); visibility = INVISIBLE }
}
