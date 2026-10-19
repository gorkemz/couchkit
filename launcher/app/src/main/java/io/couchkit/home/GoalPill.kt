// SPDX-License-Identifier: Apache-2.0
package io.couchkit.home

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * Goal pill: when a followed team scores, a short overlay slides in on top of ANY app (crests, score,
 * scorer) and fades out after ~8 s. Drawn in the accessibility service's own layer
 * (TYPE_ACCESSIBILITY_OVERLAY): no extra permission, never takes focus or touches, does not interfere
 * with the remote.
 */
class GoalPill(private val svc: AccessibilityService) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: GoalPillView? = null

    fun show(m: Football.Match, homeScored: Boolean, homeCrest: Bitmap?, awayCrest: Bitmap?) {
        dismissNow()
        val v = GoalPillView(svc, m, homeScored, homeCrest, awayCrest)
        val lp = WindowManager.LayoutParams(
            Ui.pxi(1500f), Ui.pxi(300f),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = -Ui.pxi(50f) }
        try {
            wm.addView(v, lp)
            view = v
            v.open { main.postDelayed({ v.close { remove(v) } }, 6500) }
        } catch (e: Exception) {
            Log.w(TAG, "goal pill", e)
        }
    }

    private fun remove(v: View) {
        try { wm.removeViewImmediate(v) } catch (_: Exception) {}
        if (view === v) view = null
    }

    fun dismissNow() { main.removeCallbacksAndMessages(null); view?.let { remove(it) } }
}

/**
 * Text only, like the top score band: crest · name · score · name · crest, scorer underneath; a strong
 * shadow keeps it readable on any picture. Slides down, the scoring side's digit rolls from the old score
 * to the new one, then it fades out upwards.
 */
private class GoalPillView(
    ctx: Context, m: Football.Match, homeScored: Boolean, homeCrest: Bitmap?, awayCrest: Bitmap?,
) : FrameLayout(ctx) {
    private val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; alpha = 0f }
    private val roll = FrameLayout(ctx).apply { clipChildren = true; clipToPadding = true }
    private val oldDigit: TextView
    private val newDigit: TextView
    private val semi: Typeface = ctx.resources.getFont(R.font.inter_semibold)
    private val regular: Typeface = ctx.resources.getFont(R.font.inter_regular)
    private val white = 0xFFFFFFFF.toInt()
    private val silver = 0xFFE5E5EA.toInt()

    private fun label(tf: Typeface, size: Float, color: Int) = TextView(context).apply {
        typeface = tf; setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(size)); setTextColor(color)
        includeFontPadding = false; isSingleLine = true
        setShadowLayer(Ui.px(16f), 0f, Ui.px(2f), 0xE6000000.toInt())
    }

    // Soft dark halo: not a box, an elliptical shadow cloud with invisible edges behind the text
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val haloM = android.graphics.Matrix()
    private val haloShader = android.graphics.RadialGradient(0f, 0f, 1f,
        intArrayOf(0xC7000000.toInt(), 0x8C000000.toInt(), 0x00000000), floatArrayOf(0f, 0.55f, 1f),
        android.graphics.Shader.TileMode.CLAMP)

    override fun onDraw(c: Canvas) {
        if (content.width == 0) return
        haloM.setScale(content.width / 2f + Ui.px(170f), content.height / 2f + Ui.px(70f))
        haloM.postTranslate(width / 2f, height / 2f)
        haloShader.setLocalMatrix(haloM)
        halo.shader = haloShader
        halo.alpha = (content.alpha * 255).toInt()
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), halo)
    }

    init {
        clipChildren = false
        setWillNotDraw(false)
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        val c = Ui.pxi(46f)
        // The scoring side is fully bright, the other side dimmed; the scorer line sits under the scoring side
        val dim = 0.42f
        row.addView(ImageView(ctx).apply { setImageBitmap(homeCrest); alpha = if (homeScored) 1f else dim }, LinearLayout.LayoutParams(c, c))
        row.addView(label(semi, 25f, if (homeScored) white else silver).apply { text = Football.displayName(m.home); alpha = if (homeScored) 1f else dim },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.pxi(14f); rightMargin = Ui.pxi(28f) })
        val hNew = m.home.score; val aNew = m.away.score
        val hOld = if (homeScored) ((hNew.toIntOrNull() ?: 1) - 1).toString() else hNew
        val aOld = if (!homeScored) ((aNew.toIntOrNull() ?: 1) - 1).toString() else aNew
        oldDigit = label(semi, 46f, white).apply { text = if (homeScored) hOld else aOld; fontFeatureSettings = "tnum" }
        newDigit = label(semi, 46f, white).apply { text = if (homeScored) hNew else aNew; fontFeatureSettings = "tnum"; alpha = 0f }
        roll.addView(oldDigit, LayoutParams(-2, -2, Gravity.CENTER))
        roll.addView(newDigit, LayoutParams(-2, -2, Gravity.CENTER))
        val fixed = label(semi, 46f, white).apply { text = if (homeScored) aNew else hNew; fontFeatureSettings = "tnum" }
        val colon = label(regular, 38f, 0xB3FFFFFF.toInt()).apply { text = "  :  " }
        if (homeScored) { row.addView(roll); row.addView(colon); row.addView(fixed) }
        else { row.addView(fixed); row.addView(colon); row.addView(roll) }
        row.addView(label(semi, 25f, if (!homeScored) white else silver).apply { text = Football.displayName(m.away); alpha = if (!homeScored) 1f else dim },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = Ui.pxi(28f); rightMargin = Ui.pxi(14f) })
        row.addView(ImageView(ctx).apply { setImageBitmap(awayCrest); alpha = if (!homeScored) 1f else dim }, LinearLayout.LayoutParams(c, c))
        content.addView(row, LinearLayout.LayoutParams(-2, -2))
        m.lastScorer?.let { who ->
            // Aligned with the scoring team name: left for home (inset by crest + gap), right for away
            val inset = c + Ui.pxi(14f)
            content.addView(label(semi, 20f, 0xF2EBEBF5.toInt()).apply {
                text = who
                gravity = if (homeScored) Gravity.START else Gravity.END
                setPadding(if (homeScored) inset else 0, 0, if (homeScored) 0 else inset, 0)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.pxi(8f) })
        }
        addView(content, LayoutParams(-2, -2, Gravity.CENTER))
    }

    fun open(then: () -> Unit) {
        content.translationY = -Ui.px(36f)
        content.animate().alpha(1f).translationY(0f).setDuration(650).setInterpolator(DecelerateInterpolator(2f))
            .setUpdateListener { invalidate() }
            .withEndAction {
                // the scoring side's digit rolls
                val d = Ui.px(50f)
                newDigit.translationY = d
                oldDigit.animate().translationY(-d).alpha(0f).setDuration(450).setInterpolator(DecelerateInterpolator(1.6f)).start()
                newDigit.animate().translationY(0f).alpha(1f).setDuration(560).setInterpolator(OvershootInterpolator(1.3f))
                    .withEndAction(then).start()
            }.start()
    }

    fun close(then: () -> Unit) {
        content.animate().alpha(0f).translationY(-Ui.px(30f)).setDuration(500)
            .setInterpolator(DecelerateInterpolator()).setUpdateListener { invalidate() }.withEndAction(then).start()
    }
}

/**
 * Background goal watcher (runs in the accessibility service, which stays alive). Uses the shared
 * [Football] cache and request budget; polls only while a followed match is live. When a followed
 * team's score goes UP, the goal pill opens. Not shown while the home screen is in front (the match card
 * celebrates there itself).
 */
class GoalWatcher(private val svc: AccessibilityService) {
    private val thread = HandlerThread("goal-watch").apply { start() }
    private val bg = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val pill = GoalPill(svc)
    private val scores = HashMap<String, Pair<Int, Int>>()
    private var primed = false

    fun start() { bg.post(::poll); if (Football.isDebug(svc)) bg.postDelayed(::demoCheck, 5000) }

    /** Debug builds only: creating files/goal-demo shows a preview goal pill (even over the home screen). */
    private fun demoCheck() {
        val demo = File(svc.getExternalFilesDir(null), "goal-demo")
        if (demo.exists()) {
            demo.delete()
            celebrate(Football.preview(System.currentTimeMillis() - 31_000L), homeScored = true, force = true)
        }
        bg.postDelayed(::demoCheck, 5000)
    }

    fun stop() {
        bg.removeCallbacksAndMessages(null)
        thread.quitSafely()
        main.post { pill.dismissNow() }
    }

    private fun poll() {
        Config.refresh(svc)
        var list: List<Football.Match> = emptyList()
        try {
            if (Football.enabled) {
                list = Football.update(svc)
                for (m in list) {
                    if (m.state == "pre" || m.priority <= 0) continue
                    val h = m.home.score.toIntOrNull() ?: continue
                    val a = m.away.score.toIntOrNull() ?: continue
                    val old = scores.put(m.id, h to a)
                    if (primed && old != null && m.live && h + a > old.first + old.second) celebrate(m, homeScored = h > old.first)
                }
                primed = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "goal watch", e)
        }
        bg.postDelayed(::poll, Football.pollDelay(list))
    }

    private fun celebrate(m: Football.Match, homeScored: Boolean, force: Boolean = false) {
        if (HomeActivity.visible && !force) return
        val hc = try { Football.crest(svc, m.home.logo) } catch (e: Exception) { null }
        val ac = try { Football.crest(svc, m.away.logo) } catch (e: Exception) { null }
        Log.i(TAG, "goal ${m.home.name} ${m.home.score}-${m.away.score} ${m.away.name}")
        main.post {
            if (HomeActivity.visible) HomeActivity.hideMatchBandFor(8_500L)
            pill.show(m, homeScored, hc, ac)
        }
    }
}
