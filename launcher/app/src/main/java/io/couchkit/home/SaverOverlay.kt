// SPDX-License-Identifier: Apache-2.0
package io.couchkit.home

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import kotlin.random.Random

/**
 * Screensaver widgets (home-screen screensaver and the system screensaver GlassDream), only when
 * config.json "screensaver.overlay" is true: large clock + weather top right, rates top left, batteries
 * bottom right (experimental edition only), followed match top centre. Each part appears only if it is
 * configured. Refreshes its own data (the home screen may not be running) and drifts a few pixels every
 * few minutes against burn-in. No backgrounds, shadowed text (same look as the home screen).
 */
class SaverOverlay(ctx: Context, semi: Typeface, regular: Typeface) : FrameLayout(ctx) {
    private val bd = Backdrop()   // the widgets have no background; required by their constructors only
    private val main = Handler(Looper.getMainLooper())
    private var netThread: HandlerThread? = null
    private var net: Handler? = null
    private val batteries = BatterySources.active

    private val clock = TextClock(ctx).apply {
        format24Hour = "HH:mm"; format12Hour = "h:mm"
        typeface = semi; setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(104f)); setTextColor(0xF2FFFFFF.toInt())
        includeFontPadding = false; fontFeatureSettings = "tnum"; letterSpacing = -0.02f
        setShadowLayer(Ui.px(18f), 0f, Ui.px(2f), 0x99000000.toInt())
    }
    private val wxIcon = ImageView(ctx)
    private val wxTemp = text(semi, 32f, 0xFFFFFFFF.toInt())
    private val wxCond = text(regular, 24f, 0xCCFFFFFF.toInt())
    private val wxRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; alpha = 0f }
    private val wxCredit = creditText(ctx, regular, ctx.getString(R.string.weather_credit)).apply { alpha = 0f }
    private val fx = FxPill(ctx, bd, semi)
    private val bat = BatteriesPill(ctx, bd, semi)
    private val match = MatchCard(ctx, bd, semi, regular)

    private fun text(tf: Typeface, size: Float, color: Int) = TextView(context).apply {
        typeface = tf; setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(size)); setTextColor(color)
        includeFontPadding = false; isSingleLine = true
        setShadowLayer(Ui.px(12f), 0f, Ui.px(1.5f), 0x99000000.toInt())
    }

    init {
        clipChildren = false
        val topRight = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        topRight.addView(clock, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })
        wxRow.addView(wxIcon, LinearLayout.LayoutParams(Ui.pxi(46f), Ui.pxi(46f)))
        wxRow.addView(wxTemp, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.pxi(8f) })
        wxRow.addView(wxCond, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.pxi(12f) })
        topRight.addView(wxRow, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END; topMargin = Ui.pxi(6f) })
        topRight.addView(wxCredit, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END; topMargin = Ui.pxi(8f) })
        addView(topRight, LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = Ui.pxi(50f); rightMargin = Ui.pxi(Ui.DOCK_SIDE + 10f)
        })
        addView(fx, LayoutParams(-2, Ui.pxi(76f), Gravity.TOP or Gravity.START).apply {
            topMargin = Ui.pxi(60f); leftMargin = Ui.pxi(Ui.DOCK_SIDE + 10f)
        })
        addView(bat, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = Ui.pxi(80f); rightMargin = Ui.pxi(Ui.DOCK_SIDE + 10f)
        })
        addView(match, LayoutParams(-2, Ui.pxi(104f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = Ui.pxi(40f)
        })
    }

    private fun showCached() {
        Weather.cached(context)?.let(::showWeather) ?: hideWeather()
        Fx.cached(context)?.let { fx.set(it) } ?: fx.clear()
        bat.set(batteries.cached(context))
    }

    private fun showWeather(w: Weather.Now) {
        wxIcon.setImageResource(Weather.icon(w.code, w.isDay))
        wxTemp.text = "${w.temp}°"
        wxCond.text = listOf(Weather.label, w.text).filter { it.isNotEmpty() }.joinToString("  ·  ")
        wxRow.alpha = 1f; wxCredit.alpha = 1f
    }

    private fun hideWeather() { wxRow.alpha = 0f; wxCredit.alpha = 0f }

    /** Weather 30 min, rates 1 h, batteries 10 min, match per the shared football policy; on a network thread. */
    private val refresh: Runnable = object : Runnable {
        override fun run() {
            val n = net ?: return
            val ctx = context.applicationContext
            n.post {
                if (Weather.isStale(ctx)) Weather.fetch(ctx)?.let { w -> main.post { showWeather(w) } }
                if (Fx.isStale(ctx)) Fx.fetch(ctx)?.let { r -> main.post { fx.set(r) } }
                if (batteries.isStale(ctx)) batteries.fetch(ctx)?.let { l -> main.post { bat.set(l) } }
            }
            main.postDelayed(this, 5 * 60_000L)
        }
    }

    private val matchRun: Runnable = object : Runnable {
        override fun run() {
            val n = net ?: return
            if (!Football.enabled) { match.hide(); return }
            val ctx = context.applicationContext
            n.post {
                val list = try { Football.update(ctx) } catch (e: Exception) { emptyList() }
                val m = Football.pick(list)
                val crests = if (m != null && m.id != match.shownId) try {
                    Football.crest(ctx, m.home.logo) to Football.crest(ctx, m.away.logo)
                } catch (e: Exception) { null } else null
                main.post {
                    if (m == null) match.hide() else { crests?.let { match.setCrests(it.first, it.second) }; match.show(m) }
                    if (net != null) main.postDelayed(this, Football.pollDelay(list))
                }
            }
        }
    }

    /** Against burn-in: all widgets slowly drift within ±14 px every few minutes. */
    private val drift: Runnable = object : Runnable {
        override fun run() {
            animate().translationX(Ui.px(Random.nextInt(-14, 15).toFloat()))
                .translationY(Ui.px(Random.nextInt(-10, 11).toFloat())).setDuration(4000).start()
            main.postDelayed(this, 3 * 60_000L)
        }
    }

    /** When the screensaver becomes visible (ScreenSaver.start); nothing is requested while hidden. */
    fun begin() {
        if (netThread != null) return
        netThread = HandlerThread("saver-net", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        net = Handler(netThread!!.looper)
        showCached()
        main.post(refresh)
        main.post(matchRun)
        main.postDelayed(drift, 3 * 60_000L)
    }

    fun end() {
        main.removeCallbacks(refresh); main.removeCallbacks(matchRun); main.removeCallbacks(drift)
        netThread?.quitSafely(); netThread = null; net = null
    }

    override fun onDetachedFromWindow() { end(); super.onDetachedFromWindow() }
}
