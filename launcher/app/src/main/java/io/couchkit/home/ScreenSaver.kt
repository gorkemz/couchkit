// SPDX-License-Identifier: Apache-2.0
package io.couchkit.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.random.Random

/**
 * Screensaver: plays the user's own media from `<external files dir>/screensaver/` in random order.
 * Videos play once each on a SurfaceView (hardware decoder, TV video plane; the UI stays 1080p); images
 * are shown for [IMAGE_MS] with a slow Ken Burns pan/zoom. The file name (without extension) is shown as
 * a caption for a few seconds. With an empty folder an animated mesh gradient is shown instead.
 * Optional widget overlay (clock, weather, rates, match) when config.json "screensaver.overlay" is true.
 */
class ScreenSaver(ctx: Context, semi: Typeface, regular: Typeface) : FrameLayout(ctx) {
    private val surface = SurfaceView(ctx)
    private val photo = KenBurnsView(ctx)
    private val mesh = MeshAnimView(ctx)
    private var player: ExoPlayer? = null
    private val curtain = View(ctx).apply { setBackgroundColor(0xFF000000.toInt()) }
    private val caption = TextView(ctx).apply {
        typeface = semi; setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(34f)); setTextColor(0xF2FFFFFF.toInt())
        setShadowLayer(Ui.px(14f), 0f, Ui.px(2f), 0x99000000.toInt()); maxLines = 2; alpha = 0f
    }
    private val widgets = SaverOverlay(ctx, semi, regular)
    private val handler = Handler(Looper.getMainLooper())
    private var decodeThread: HandlerThread? = null
    private var items: List<LocalMedia.Item> = emptyList()
    private var pos = 0
    private var current: LocalMedia.Item? = null
    private var failures = 0
    private var gen = 0
    var active = false
        private set

    init {
        setBackgroundColor(0xFF000000.toInt())
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        addView(photo, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(mesh, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(curtain, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(widgets, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(caption, LayoutParams(Ui.pxi(1100f), -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = Ui.pxi(Ui.SIDE); bottomMargin = Ui.pxi(90f)
        })
        photo.visibility = GONE
        mesh.visibility = GONE
        visibility = GONE
    }

    fun start() {
        if (active) return
        active = true
        gen++
        visibility = VISIBLE
        alpha = 0f
        curtain.alpha = 1f
        animate().alpha(1f).setDuration(1500).start()
        keepScreenOn = true
        Config.refresh(context)
        if (Config.screensaverOverlay) { widgets.visibility = VISIBLE; widgets.begin() } else widgets.visibility = GONE
        items = LocalMedia.all(context).shuffled()
        pos = 0; failures = 0
        if (items.isEmpty()) showMesh() else next()
    }

    fun stop() {
        if (!active) return
        active = false
        gen++
        keepScreenOn = false
        handler.removeCallbacksAndMessages(null)
        widgets.end()
        mesh.stop()
        player?.release(); player = null
        decodeThread?.quitSafely(); decodeThread = null
        animate().alpha(0f).setDuration(300).withEndAction {
            visibility = GONE; photo.clear(); photo.visibility = GONE; mesh.visibility = GONE
        }.start()
    }

    private fun showMesh() {
        mesh.visibility = VISIBLE
        mesh.start()
        curtain.animate().alpha(0f).setDuration(1200).start()
    }

    /** Fades to black, then shows the next item. */
    private fun next() {
        if (!active) return
        if (failures >= items.size) { photo.visibility = GONE; showMesh(); return }
        val g = gen
        curtain.animate().cancel()
        curtain.animate().alpha(1f).setDuration(if (curtain.alpha >= 1f) 0 else 700).withEndAction {
            if (g != gen || !active) return@withEndAction
            val item = items[pos % items.size]
            pos++
            current = item
            if (item.isVideo) playVideo(item) else showImage(item, g)
        }.start()
    }

    private fun reveal(item: LocalMedia.Item) {
        curtain.animate().alpha(0f).setDuration(1200).start()
        caption.text = item.caption
        caption.animate().cancel()
        caption.animate().alpha(1f).setStartDelay(600).setDuration(800).withEndAction {
            caption.animate().alpha(0f).setStartDelay(8_000).setDuration(1200).start()
        }.start()
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val p = ExoPlayer.Builder(context).build()
        p.setVideoSurfaceView(surface)
        p.videoScalingMode = androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        p.volume = 0f
        p.repeatMode = Player.REPEAT_MODE_OFF
        p.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                failures = 0
                current?.let(::reveal)
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) next()
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "screensaver video error ${error.errorCodeName}")
                failures++
                next()
            }
        })
        player = p
        return p
    }

    private fun playVideo(item: LocalMedia.Item) {
        photo.visibility = GONE; photo.clear()
        val p = ensurePlayer()
        p.setMediaItem(MediaItem.fromUri(item.uri))
        p.playWhenReady = true
        p.prepare()
    }

    private fun showImage(item: LocalMedia.Item, g: Int) {
        player?.stop()
        val t = decodeThread ?: HandlerThread("saver-img").apply { start() }.also { decodeThread = it }
        Handler(t.looper).post {
            val b = try { Wallpapers.decode(item.file, Ui.screenW) } catch (e: Throwable) { null }
            handler.post {
                if (g != gen || !active) { b?.recycle(); return@post }
                if (b == null) { failures++; next(); return@post }
                failures = 0
                photo.visibility = VISIBLE
                photo.show(b, IMAGE_MS + 1500)
                reveal(item)
                handler.postDelayed({ if (g == gen) next() }, IMAGE_MS)
            }
        }
    }

    companion object {
        const val IMAGE_MS = 20_000L
    }
}

/** Shows one bitmap with a slow pan + zoom (Ken Burns), direction randomised per image. */
private class KenBurnsView(ctx: Context) : View(ctx) {
    private var bmp: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val m = Matrix()
    private var t = 0f
    private var anim: ValueAnimator? = null
    private var zoomIn = true
    private var dx = 0f
    private var dy = 0f

    fun show(b: Bitmap, durationMs: Long) {
        anim?.cancel()
        bmp?.recycle()
        bmp = b
        zoomIn = Random.nextBoolean()
        dx = Random.nextFloat() * 2f - 1f
        dy = Random.nextFloat() * 2f - 1f
        t = 0f
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun clear() {
        anim?.cancel(); anim = null
        bmp?.recycle(); bmp = null
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val b = bmp ?: return
        val w = width.toFloat(); val h = height.toFloat()
        val base = maxOf(w / b.width, h / b.height)
        val z = if (zoomIn) 1f + 0.12f * t else 1.12f - 0.12f * t
        val sc = base * z
        val extraX = b.width * sc - w
        val extraY = b.height * sc - h
        m.setScale(sc, sc)
        m.postTranslate(-extraX / 2f + dx * extraX / 2f * (t - 0.5f), -extraY / 2f + dy * extraY / 2f * (t - 0.5f))
        c.drawBitmap(b, m, paint)
    }
}

/** Animated mesh gradient (empty screensaver folder): small bitmap re-rendered ~20 times a second. */
private class MeshAnimView(ctx: Context) : View(ctx) {
    private val bmp = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val dst = Rect()
    private val hues = FloatArray(3)
    private var startAt = 0L
    private var running = false
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val phase = (System.currentTimeMillis() - startAt) / 9000f
            val shift = (phase * 6f) % 360f
            for (i in 0..2) hues[i] = (Mesh.DEFAULT_HUES[i] + shift) % 360f
            Mesh.renderInto(bmp, hues, Random(42), phase)
            invalidate()
            postDelayed(this, 50)
        }
    }

    fun start() { if (running) return; running = true; startAt = System.currentTimeMillis(); post(tick) }
    fun stop() { running = false; removeCallbacks(tick) }

    override fun onDraw(c: Canvas) {
        dst.set(0, 0, width, height)
        c.drawBitmap(bmp, null, dst, paint)
    }

    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
}
