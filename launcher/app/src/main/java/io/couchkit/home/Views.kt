// SPDX-License-Identifier: Apache-2.0
package io.couchkit.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator

/**
 * Featured-row state: current and previous scene + transition progress. Hero image, frosted glass
 * dock and grid dimming all read the same blurred copy; no live blur (no RenderEffect on API 30).
 */
class Backdrop {
    var cur: Scene? = null
        private set
    var prev: Scene? = null
        private set
    var t = 1f
        private set
    private val views = ArrayList<View>()
    private var anim: ValueAnimator? = null
    /** With a moving video behind, glass surfaces are painted smoky translucent instead of the blur copy. */
    var videoMode = false
        set(v) { if (field != v) { field = v; for (x in views) x.invalidate() } }

    fun attach(v: View) { views.add(v) }

    fun show(s: Scene) {
        if (s === cur) return
        anim?.cancel()
        prev = if (cur != null && t < 0.5f) prev else cur
        cur = s
        t = 0f
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** Show without transition (last image from disk on cold start). */
    fun showNow(s: Scene) {
        anim?.cancel(); prev = null; cur = s; t = 1f; invalidate()
    }

    fun clear() {
        anim?.cancel(); cur = null; prev = null; t = 1f; invalidate()
    }

    private fun invalidate() {
        if (t >= 1f) prev = null
        for (v in views) v.invalidate()
    }

    private val m = Matrix()

    /** Paints the blurred background in screen coordinates (shifted by offX, offY) into a rounded rect. */
    fun paintBlur(c: Canvas, rect: RectF, radius: Float, offX: Float, offY: Float, paint: Paint) {
        val p = prev
        val s = cur ?: return
        if (p != null && t < 1f) paintOne(c, p, rect, radius, offX, offY, paint, 255)
        paintOne(c, s, rect, radius, offX, offY, paint, if (p != null) (t * 255).toInt() else 255)
    }

    private fun paintOne(c: Canvas, s: Scene, rect: RectF, radius: Float, offX: Float, offY: Float, paint: Paint, a: Int) {
        val sh = s.blurShader ?: BitmapShader(s.blur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { s.blurShader = it }
        m.setScale(Ui.screenW.toFloat() / s.blur.width, Ui.screenH.toFloat() / s.blur.height)
        m.postTranslate(-offX, -offY)
        sh.setLocalMatrix(m)
        paint.shader = sh
        paint.alpha = a
        c.drawRoundRect(rect, radius, radius, paint)
    }
}

/** Edge-to-edge featured image + shade gradient. */
class HeroView(ctx: Context, private val bd: Backdrop) : View(ctx) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = android.graphics.Rect()

    init { bd.attach(this) }

    override fun onDraw(c: Canvas) {
        val s = bd.cur
        if (s == null) { c.drawColor(0xFF000000.toInt()); return }
        dst.set(0, 0, width, height)
        val p = bd.prev
        val a = if (p != null) (bd.t * 255).toInt() else 255
        // Same background (same wallpaper): do not redraw it; only the logo cross-fades
        if (p != null && p.baked !== s.baked) { paint.alpha = 255; c.drawBitmap(p.baked, null, dst, paint) }
        paint.alpha = if (p != null && p.baked !== s.baked) a else 255
        c.drawBitmap(s.baked, null, dst, paint)
        if (p != null) r.drawLogo(c, p, width, height, 255 - a)
        r.drawLogo(c, s, width, height, a)
    }

    private val r = SceneRenderer()
}

/** Layer that frosts and darkens the featured image when moving into the grid; only its alpha animates. */
class DimView(ctx: Context, private val bd: Backdrop) : View(ctx) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val rect = RectF()

    init { bd.attach(this); alpha = 0f }

    override fun onDraw(c: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        if (bd.videoMode) { c.drawColor(0xB8000000.toInt()); return }
        bd.paintBlur(c, rect, 0f, 0f, 0f, paint)
        c.drawColor(0x99000000.toInt())
    }
}

/**
 * Glass dock: blurred + saturated copy of the image behind, white frost tint, top highlight,
 * and a specular rim on the corners. Resamples while scrolling.
 */
class GlassView(ctx: Context, private val bd: Backdrop, private val radius: Float = Ui.px(Ui.DOCK_RADIUS)) : View(ctx) {
    private val blurPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
            setSaturation(1.6f)
            postConcat(ColorMatrix(floatArrayOf(
                1.08f, 0f, 0f, 0f, 6f,
                0f, 1.08f, 0f, 0f, 6f,
                0f, 0f, 1.08f, 0f, 6f,
                0f, 0f, 0f, 1f, 0f,
            )))
        })
    }
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FFFFFFF }
    private val videoFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8C1C1C1E.toInt() }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gloss = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val inner = RectF()
    var dim = 0f
        set(v) { field = v; invalidate() }

    init { bd.attach(this) }

    /**
     * The glass is only a background: inside a WRAP_CONTENT parent it must not ask for the full width
     * (the pill would span the screen). Ask for 0 unless exact; FrameLayout then stretches it to the content.
     */
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        fun size(spec: Int) = if (MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(spec) else 0
        setMeasuredDimension(size(wSpec), size(hSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        gloss.shader = LinearGradient(0f, 0f, 0f, h * 0.5f, 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        rim.strokeWidth = Ui.px(2.2f)
        rim.shader = LinearGradient(0f, 0f, w * 0.25f, h.toFloat(),
            intArrayOf(0xB3FFFFFF.toInt(), 0x26FFFFFF, 0x14FFFFFF, 0x4DFFFFFF),
            floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.MIRROR)
        val i = rim.strokeWidth / 2
        inner.set(i, i, w - i, h - i)
    }

    override fun onDraw(c: Canvas) {
        // Position on screen: sum of parent positions + translations (for nested use, e.g. score pill)
        var ox = 0f; var oy = 0f; var v: View = this
        while (true) {
            ox += v.left + v.translationX; oy += v.top + v.translationY
            v = v.parent as? View ?: break
        }
        if (bd.videoMode) c.drawRoundRect(rect, radius, radius, videoFill)
        else bd.paintBlur(c, rect, radius, ox, oy, blurPaint)
        c.drawRoundRect(rect, radius, radius, tint)
        if (dim > 0f) {
            dimPaint.color = ((dim * 0x99).toInt() shl 24)
            c.drawRoundRect(rect, radius, radius, dimPaint)
        }
        c.drawRoundRect(rect, radius, radius, gloss)
        c.drawRoundRect(inner, radius - rim.strokeWidth / 2, radius - rim.strokeWidth / 2, rim)
    }
}

/**
 * App tile: rounded corners (hardware outline clipping), grows + shadow (elevation) on focus,
 * glass gloss and specular rim.
 */
class AppTile(ctx: Context, val entry: AppEntry) : View(ctx) {
    var bitmap: Bitmap? = null
        set(v) { field = v; invalidate() }
    var focusAmt = 0f
        set(v) { field = v; invalidate() }

    private val radius = Ui.px(Ui.RADIUS)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val gloss = Paint(Paint.DITHER_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dst = RectF()
    private val inner = RectF()

    init {
        isFocusable = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, radius)
        }
        clipToOutline = true
        cameraDistance = 9000f * resources.displayMetrics.density
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        dst.set(0f, 0f, w.toFloat(), h.toFloat())
        gloss.shader = LinearGradient(0f, 0f, 0f, h * 0.55f, 0xFFFFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
        rim.strokeWidth = Ui.px(2.6f)
        rim.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(0xCCFFFFFF.toInt(), 0x1AFFFFFF, 0x1AFFFFFF, 0x80FFFFFF.toInt()),
            floatArrayOf(0f, 0.38f, 0.66f, 1f), Shader.TileMode.CLAMP)
        val i = rim.strokeWidth / 2
        inner.set(i, i, w - i, h - i)
    }

    override fun onDraw(c: Canvas) {
        val b = bitmap
        if (b != null) c.drawBitmap(b, null, dst, bmpPaint) else c.drawColor(0xFF2C2C30.toInt())
        gloss.alpha = (0x12 + 0x16 * focusAmt).toInt()
        c.drawRect(0f, 0f, dst.right, dst.bottom * 0.55f, gloss)
        rim.alpha = (0x40 + 0xBF * focusAmt).toInt()
        c.drawRoundRect(inner, radius - rim.strokeWidth / 2, radius - rim.strokeWidth / 2, rim)
    }
}

/** Page dots: the active one is a long pill, the others small translucent dots. */
class PageDots(ctx: Context) : View(ctx) {
    var count = 0
        set(v) { field = v; invalidate() }
    var index = 0
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        if (count < 2) return
        val d = Ui.px(10f)
        val gap = Ui.px(12f)
        val active = Ui.px(26f)
        var x = width - (count - 1) * (d + gap) - active
        val cy = height / 2f
        for (i in 0 until count) {
            val w = if (i == index) active else d
            r.set(x, cy - d / 2, x + w, cy + d / 2)
            p.alpha = if (i == index) 0xF0 else 0x66
            c.drawRoundRect(r, d / 2, d / 2, p)
            x += w + gap
        }
    }
}

/** While a video plays: shade + logo drawn on top (normally baked into the still image). */
class VideoOverlay(ctx: Context, private val bd: Backdrop) : View(ctx) {
    private val r = SceneRenderer()

    /** The logo fades out with the other top info when moving into the grid (0..1). */
    var fade = 1f
        set(v) { if (field != v) { field = v; invalidate() } }

    init { bd.attach(this); alpha = 0f }

    override fun onDraw(c: Canvas) {
        val s = bd.cur ?: return
        r.drawShade(c, width, height, s.leftShade)
        if (fade > 0f) r.drawLogo(c, s, width, height, (fade * 255).toInt())
    }
}
