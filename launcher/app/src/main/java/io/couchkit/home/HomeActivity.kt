// SPDX-License-Identifier: Apache-2.0
package io.couchkit.home

import android.animation.ValueAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.media.tv.TvContract
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.DisplayMetrics
import android.util.Log
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.TextClock
import android.widget.TextView

/** Wallpaper rotation every 30 minutes; also the next one on every return to the home screen. */
private const val ROTATE_MS = 30 * 60_000L
/** All apps without featured content share one wallpaper sequence. */
private const val WALL = "@wallpaper"
private const val IDLE_MS = 5 * 60_000L
private const val VIDEO_IDLE_MS = 6_000L

class HomeActivity : Activity() {
    private val bd = Backdrop()
    private lateinit var hero: HeroView
    private lateinit var heroVideo: HeroVideo
    private lateinit var videoOverlay: VideoOverlay
    private lateinit var dim: DimView
    private lateinit var glass: GlassView
    private lateinit var content: FrameLayout
    private lateinit var title: TextView
    private lateinit var meta: TextView
    private lateinit var desc: TextView
    private lateinit var info: android.widget.LinearLayout
    private lateinit var dots: PageDots
    private lateinit var wxPill: WeatherClockPill
    /** Top band (weather/clock, rates, batteries, match): fades out together when moving into the grid. */
    private lateinit var topLayer: FrameLayout
    private lateinit var fxPill: FxPill
    private lateinit var batteriesPill: BatteriesPill
    private val batteries = BatterySources.active
    private lateinit var wxCredit: TextView
    private lateinit var tmdbCredit: TextView
    private var demoStart = 0L
    private lateinit var matchCard: MatchCard
    private lateinit var saver: ScreenSaver
    private var swallowKeyUp = false
    private val shelfIndex = HashMap<String, Int>()
    /** Ready scenes of the top-row apps ("package|n" -> scene): instant when moving left/right. */
    private val topScenes = HashMap<String, Scene>()
    private lateinit var images: Images
    private lateinit var fontRegular: Typeface
    private lateinit var fontSemi: Typeface

    private var entries = mutableListOf<AppEntry>()
    private val tiles = ArrayList<AppTile>()
    private val labels = ArrayList<TextView>()
    private var focus = 0
    private var scroll = 0f
    private var scrollAnim: ValueAnimator? = null
    private var titleFade = 1f
    private var tilesTrimmed = false

    private var moving = false
    private var wiggle: ValueAnimator? = null
    private var okDownAt = 0L
    private var okLongDone = false

    private val main = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread("glass-img", Process.THREAD_PRIORITY_DEFAULT).apply { start() }
    private val worker = Handler(workerThread.looper)
    /** Network queries (weather, rates, batteries, match) on their own queue, never blocking scene/logo work. */
    private val netThread = HandlerThread("glass-net", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val net = Handler(netThread.looper)
    private var heroGen = 0

    /** Bounded cache (bytes) of featured scenes. Fully emptied when an app is opened. */
    private val sceneCache = object : LruCache<String, Scene>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Scene) = value.bytes
    }

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { main.post { reloadApps() } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        Ui.screenW = dm.widthPixels
        Ui.screenH = dm.heightPixels
        Ui.u = dm.widthPixels / 1920f
        images = Images(applicationContext)
        fontRegular = resources.getFont(R.font.inter_regular)
        fontSemi = resources.getFont(R.font.inter_semibold)
        window.setBackgroundDrawable(null)

        buildViews()
        loadLastScene()
        reloadApps()

        registerReceiver(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        net.post(::probeWatchNext)
    }

    override fun onDestroy() {
        unregisterReceiver(pkgReceiver)
        workerThread.quitSafely()
        netThread.quitSafely()
        super.onDestroy()
    }

    private fun buildViews() {
        val root = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        // Bottom: optional moving video (SurfaceView behind the window); the still frame cross-fades above it
        heroVideo = HeroVideo(this, onFirstFrame = { main.post { videoStarted() } }, onFail = { main.post { videoStopped() } })
        root.addView(heroVideo, match())
        hero = HeroView(this, bd)
        dim = DimView(this, bd)
        videoOverlay = VideoOverlay(this, bd)
        root.addView(hero, match())
        root.addView(videoOverlay, match())
        root.addView(dim, match())

        // Bottom-left info block: title, type · year, two-line overview, attribution.
        info = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        title = TextView(this).apply {
            typeface = fontSemi
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(60f))
            setTextColor(0xFFFFFFFF.toInt())
            letterSpacing = -0.015f
            setShadowLayer(Ui.px(18f), 0f, Ui.px(2f), 0x80000000.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        meta = TextView(this).apply {
            typeface = fontSemi
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(24f))
            setTextColor(0xB3FFFFFF.toInt())
            setShadowLayer(Ui.px(10f), 0f, Ui.px(1f), 0x80000000.toInt())
            maxLines = 1
            setPadding(0, Ui.pxi(6f), 0, 0)
        }
        desc = TextView(this).apply {
            typeface = fontRegular
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(25f))
            setTextColor(0xA6FFFFFF.toInt())
            setShadowLayer(Ui.px(10f), 0f, Ui.px(1f), 0x80000000.toInt())
            setLineSpacing(Ui.px(4f), 1f)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, Ui.pxi(10f), 0, 0)
        }
        tmdbCredit = creditText(this, fontRegular, getString(R.string.tmdb_credit)).apply {
            setPadding(0, Ui.pxi(12f), 0, 0); visibility = View.GONE
        }
        info.addView(title); info.addView(meta); info.addView(desc); info.addView(tmdbCredit)
        root.addView(info, FrameLayout.LayoutParams(Ui.pxi(980f), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = Ui.pxi(Ui.SIDE); bottomMargin = Ui.pxi(1080f - 712f)
        })
        dots = PageDots(this)
        root.addView(dots, FrameLayout.LayoutParams(Ui.pxi(400f), Ui.pxi(24f), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = Ui.pxi(Ui.SIDE); bottomMargin = Ui.pxi(1080f - 712f)
        })

        topLayer = FrameLayout(this).apply { clipChildren = false }
        root.addView(topLayer, match())

        // Top corners: weather + clock on the right, exchange rates on the left
        wxPill = WeatherClockPill(this, bd, fontSemi, fontRegular)
        topLayer.addView(wxPill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.pxi(76f), Gravity.TOP or Gravity.END).apply {
            topMargin = Ui.pxi(40f); rightMargin = Ui.pxi(Ui.DOCK_SIDE)
        })
        wxCredit = creditText(this, fontRegular, getString(R.string.weather_credit)).apply { visibility = View.GONE }
        topLayer.addView(wxCredit, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = Ui.pxi(40f + 76f + 2f); rightMargin = Ui.pxi(Ui.DOCK_SIDE)
        })
        fxPill = FxPill(this, bd, fontSemi)
        topLayer.addView(fxPill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.pxi(76f), Gravity.TOP or Gravity.START).apply {
            topMargin = Ui.pxi(40f); leftMargin = Ui.pxi(Ui.DOCK_SIDE)
        })
        // Top left, under the rates: battery pill (experimental edition only; hidden while empty)
        batteriesPill = BatteriesPill(this, bd, fontSemi)
        topLayer.addView(batteriesPill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            // The ring's left edge = the view's left edge; +1 px matches the side bearing of the rate text above
            topMargin = Ui.pxi(40f + 76f + 14f); leftMargin = Ui.pxi(Ui.DOCK_SIDE + 1f)
        })

        glass = GlassView(this, bd)
        root.addView(glass, FrameLayout.LayoutParams(Ui.pxi(1920f - 2 * Ui.DOCK_SIDE), Ui.pxi(Ui.DOCK_H)).apply {
            leftMargin = Ui.pxi(Ui.DOCK_SIDE); topMargin = Ui.pxi(Ui.DOCK_TOP)
        })

        content = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        root.addView(content, match())

        // Top centre: followed match (only while one is about to start, live or just finished)
        matchCard = MatchCard(this, bd, fontSemi, fontRegular)
        topLayer.addView(matchCard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.pxi(104f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = Ui.pxi(26f)
        })

        // On top: screensaver
        saver = ScreenSaver(this, fontSemi, fontRegular)
        root.addView(saver, match())
        setContentView(root)
    }

    private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    // ---- App grid ----------------------------------------------------------------------------

    private fun reloadApps() {
        val focusedKey = entries.getOrNull(focus)?.key
        entries = AppRepo.load(this)
        topScenes.clear()
        content.removeAllViews()
        tiles.clear(); labels.clear()
        sceneCache.evictAll()
        for ((i, e) in entries.withIndex()) {
            val t = AppTile(this, e)
            content.addView(t, FrameLayout.LayoutParams(Ui.pxi(Ui.TILE_W), Ui.pxi(Ui.TILE_H)))
            val l = TextView(this).apply {
                text = e.label
                typeface = fontRegular
                setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(25f))
                setTextColor(0xF2FFFFFF.toInt())
                gravity = Gravity.CENTER_HORIZONTAL
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setShadowLayer(Ui.px(8f), 0f, Ui.px(1f), 0x99000000.toInt())
                alpha = 0f
            }
            content.addView(l, FrameLayout.LayoutParams(Ui.pxi(Ui.TILE_W * 1.3f), ViewGroup.LayoutParams.WRAP_CONTENT))
            tiles.add(t); labels.add(l)
            place(i, false)
        }
        focus = entries.indexOfFirst { it.key == focusedKey }.coerceAtLeast(0)
        loadTileBitmaps()
        if (tiles.isNotEmpty()) setFocus(focus, 0, 0)
    }

    private fun labelY(index: Int) = Ui.rowTop(index / Ui.COLS) + Ui.px(Ui.TILE_H + 26f)

    private fun place(i: Int, animate: Boolean) {
        val t = tiles[i]
        val l = labels[i]
        val x = Ui.tileX(i)
        val y = Ui.rowTop(i / Ui.COLS)
        val lx = x - Ui.px(Ui.TILE_W * 0.15f)
        val ly = labelY(i) + if (i == focus) Ui.px(6f) else 0f
        if (animate) {
            t.animate().translationX(x).translationY(y).setDuration(240).setInterpolator(DecelerateInterpolator(2f)).start()
            l.animate().translationX(lx).translationY(ly).setDuration(240).setInterpolator(DecelerateInterpolator(2f)).start()
        } else {
            t.translationX = x; t.translationY = y
            l.translationX = lx; l.translationY = ly
        }
    }

    private fun loadTileBitmaps() {
        tilesTrimmed = false
        // Focused row first, then the rest: the first screen fills quickly.
        val order = tiles.indices.sortedBy { if (it / Ui.COLS == focus / Ui.COLS) -1 else it }
        for (i in order) {
            val t = tiles[i]
            worker.post {
                val b = try { images.tile(t.entry) } catch (e: Throwable) { Log.w(TAG, "tile ${t.entry.key}", e); null }
                if (i == order.last()) {
                    // Let the (native) resource tables opened for banners be freed here.
                    System.gc(); System.runFinalization(); System.gc()
                    main.post { prewarmTop() }
                }
                main.post {
                    t.bitmap = b
                    if (tiles.indexOf(t) == focus && bd.cur == null) scheduleHero(0)
                }
            }
        }
    }

    // ---- Focus, navigation, scrolling --------------------------------------------------------

    private fun setFocus(i: Int, dx: Int, dy: Int) {
        if (i != focus) tiles.getOrNull(focus)?.let { unfocus(focus) }
        focus = i
        val t = tiles[i]
        t.requestFocus()
        val dec = DecelerateInterpolator(2f)
        t.animate().scaleX(Ui.FOCUS_SCALE).scaleY(Ui.FOCUS_SCALE).translationZ(Ui.px(34f))
            .setDuration(200).setInterpolator(dec).start()
        // Slight parallax tilt towards the direction of travel, springing back.
        if (dx != 0 || dy != 0) {
            t.rotationY = dx * 7f
            t.rotationX = -dy * 7f
            t.animate().rotationX(0f).rotationY(0f).setDuration(620).setInterpolator(OvershootInterpolator(2.2f)).start()
        }
        animateFloat(t, R.id.anim_focus, t.focusAmt, 1f, 200) { t.focusAmt = it }
        labels[i].animate().alpha(1f).translationY(labelY(i) + Ui.px(6f)).setDuration(200).setInterpolator(dec).start()
        updateScroll(i / Ui.COLS)
        // Only top-row apps change the featured area; in the grid the background stays put.
        if (i < Ui.COLS || bd.cur == null) {
            val e = entries[i]
            if (topScenes.containsKey("${e.key}|${rot(e.key)}")) { main.removeCallbacks(heroRun); heroRun.run() } else scheduleHero(30)
        }
    }

    private fun unfocus(i: Int) {
        val t = tiles[i]
        val dec = DecelerateInterpolator(2f)
        t.animate().scaleX(1f).scaleY(1f).translationZ(0f).rotationX(0f).rotationY(0f)
            .setDuration(240).setInterpolator(dec).start()
        animateFloat(t, R.id.anim_focus, t.focusAmt, 0f, 240) { t.focusAmt = it }
        labels[i].animate().alpha(0f).translationY(labelY(i)).setDuration(180).setInterpolator(dec).start()
    }

    /** At an edge: the tile tilts slightly in that direction and springs back. */
    private fun nudge(dx: Int, dy: Int) {
        val t = tiles.getOrNull(focus) ?: return
        t.animate().rotationY(dx * 9f).rotationX(-dy * 9f).setDuration(90).setInterpolator(DecelerateInterpolator())
            .withEndAction {
                t.animate().rotationX(0f).rotationY(0f).setDuration(420).setInterpolator(OvershootInterpolator(2.5f)).start()
            }.start()
    }

    private fun navigate(dx: Int, dy: Int) {
        val n = tiles.size
        if (n == 0) return
        val row = focus / Ui.COLS
        val target = when {
            dx != 0 -> (focus + dx).takeIf { it in 0 until n && it / Ui.COLS == row }
            dy > 0 -> when {
                focus + Ui.COLS < n -> focus + Ui.COLS
                (n - 1) / Ui.COLS > row -> n - 1
                else -> null
            }
            else -> (focus - Ui.COLS).takeIf { it >= 0 }
        }
        if (moving) {
            if (target == null) { nudge(dx, dy); return }
            swap(focus, target)
            return
        }
        if (target == null) nudge(dx, dy) else setFocus(target, dx, dy)
    }

    private fun updateScroll(row: Int) {
        val target = Ui.scrollFor(row)
        if (target == scroll && scrollAnim == null) return
        scrollAnim?.cancel()
        scrollAnim = ValueAnimator.ofFloat(scroll, target).apply {
            duration = 300
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { applyScroll(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { if (scrollAnim === a) scrollAnim = null }
            })
            start()
        }
    }

    private fun applyScroll(s: Float) {
        scroll = s
        content.translationY = -s
        glass.translationY = -s
        val d = (s / Ui.px(Ui.DIM_DISTANCE)).coerceIn(0f, 1f)
        dim.alpha = d
        glass.dim = d
        info.alpha = (1f - d * 1.6f).coerceAtLeast(0f) * titleFade
        dots.alpha = info.alpha
        videoOverlay.fade = (1f - d * 1.6f).coerceAtLeast(0f)
        // Top info fades quickly when moving into the grid; at zero alpha it is not drawn at all
        topLayer.alpha = (1f - d * 2.2f).coerceAtLeast(0f)
        topLayer.visibility = if (topLayer.alpha == 0f) View.INVISIBLE else View.VISIBLE
    }

    // ---- Featured row --------------------------------------------------------------------------

    private val heroRun: Runnable = Runnable {
        main.removeCallbacks(advanceRun)
        val e = entries.getOrNull(focus) ?: return@Runnable
        val gen = ++heroGen
        val n = rot(e.key)
        topScenes["${e.key}|$n"]?.let { s ->
            showScene(s, s.shelfCount, s.shelfIdx)
            if (s.shelfCount > 1) { main.postDelayed(advanceRun, 8000); prebuild(e, n + 1) } else scheduleRotate(s)
            return@Runnable
        }
        if (!Shelf.has(e.key)) {
            // Logo and background ready: show instantly without queueing (object only, no disk/drawing)
            (sceneCache.get("${e.key}@$n") ?: images.quickScene(e, n))?.let { showScene(it, 0, 0); scheduleRotate(it); return@Runnable }
        }
        val fallback = tiles.getOrNull(focus)?.bitmap
        worker.postAtFrontOfQueue {
            if (gen != heroGen) return@postAtFrontOfQueue
            fun base(): Scene? = sceneCache.get("${e.key}@$n") ?: try { images.scene(e, fallback, n) } catch (t: Throwable) {
                Log.w(TAG, "scene ${e.key}", t); null
            }?.also { sceneCache.put(it.key, it) }
            fun post(s: Scene, count: Int, i: Int) = main.post {
                if (gen == heroGen && !isFinishing) {
                    showScene(s, count, i)
                    if (count > 1) main.postDelayed(advanceRun, 8000) else scheduleRotate(s)
                }
            }
            if (Shelf.has(e.key)) {
                // First time: show the colour background until the list arrives, then switch to content.
                if (!Shelf.isLoaded(e.key) && bd.cur?.key != e.key) base()?.let { post(it, 0, 0) }
                val items = Shelf.items(applicationContext, e.key)
                if (items != null && gen == heroGen) {
                    val i = n % items.size
                    val item = items[i]
                    val key = "${e.key}#${item.image}"
                    val s = sceneCache.get(key) ?: try { images.shelfScene(e, item) } catch (t: Throwable) { null }
                    if (s != null) {
                        s.shelfCount = items.size; s.shelfIdx = i
                        main.post { if (entries.indexOf(e) in 0 until Ui.COLS) keepTop(e, n, s) }
                        post(s, items.size, i)
                        if (items.size > 1) Shelf.prefetch(applicationContext, items[(i + 1) % items.size])
                        return@postAtFrontOfQueue
                    }
                }
            }
            base()?.let { post(it, 0, 0) }
        }
    }

    /** Keep a ready scene; drop older ones of the same app (memory: at most 2 scenes per app). */
    private fun keepTop(e: AppEntry, n: Int, s: Scene) {
        topScenes.keys.filter { it.startsWith("${e.key}|") && it != "${e.key}|$n" && it != "${e.key}|${n + 1}" }
            .forEach { topScenes.remove(it) }
        topScenes["${e.key}|$n"] = s
    }

    /** Prepare scene [n] in the background (top row; for featured apps download + bake the image). */
    private fun prebuild(e: AppEntry, n: Int) {
        if (topScenes.containsKey("${e.key}|$n")) return
        val fallback = tiles.getOrNull(entries.indexOf(e))?.bitmap
        worker.post {
            val s = try {
                if (Shelf.has(e.key)) {
                    val items = Shelf.items(applicationContext, e.key)
                    if (items.isNullOrEmpty()) images.scene(e, fallback, n)
                    else { val i = n % items.size; images.shelfScene(e, items[i])?.also { it.shelfCount = items.size; it.shelfIdx = i } }
                } else images.scene(e, fallback, n)
            } catch (t: Throwable) { Log.w(TAG, "prebuild ${e.key}", t); null }
            if (s != null) main.post { if (entries.indexOf(e) in 0 until Ui.COLS) keepTop(e, n, s) }
        }
    }

    /** On start and after reordering: prepare the scenes of the top-row apps. */
    private fun prewarmTop() {
        for (i in 0 until minOf(Ui.COLS, entries.size)) {
            val e = entries[i]
            prebuild(e, rot(e.key))
        }
    }

    /** Featured carousel: advances every few seconds (top row only). */
    private val advanceRun: Runnable = Runnable {
        val e = entries.getOrNull(focus) ?: return@Runnable
        if (focus >= Ui.COLS || moving) return@Runnable
        bump(e.key)
        heroRun.run()
    }

    /** Rotation index: starts from the clock (a different image on every return), then +1 per step. */
    private fun rot(key: String) = shelfIndex.getOrPut(if (Shelf.has(key)) key else WALL) { (System.currentTimeMillis() / ROTATE_MS).toInt() }
    private fun bump(key: String) { val k = if (Shelf.has(key)) key else WALL; shelfIndex[k] = rot(key) + 1 }

    private fun scheduleRotate(s: Scene) {
        main.removeCallbacks(advanceRun)
        if (s.rotates) main.postDelayed(advanceRun, ROTATE_MS)
    }

    private fun scheduleHero(delay: Long) {
        main.removeCallbacks(heroRun)
        main.postDelayed(heroRun, delay)
    }

    /** Video drew its first frame: the still fades out, shade + logo move to the overlay layer. */
    private fun videoStarted() {
        if (heroVideo.playingUrl == null) return
        bd.videoMode = true
        hero.animate().alpha(0f).setDuration(1400).start()
        videoOverlay.animate().alpha(1f).setDuration(1400).start()
    }

    /** Back to the still frame; with [release] the player is fully released (app / screensaver). */
    private fun videoStopped(release: Boolean = true) {
        main.removeCallbacks(videoRun)
        hero.animate().cancel(); videoOverlay.animate().cancel()
        if (release) { hero.alpha = 1f; videoOverlay.alpha = 0f }
        else { hero.animate().alpha(1f).setDuration(350).start(); videoOverlay.animate().alpha(0f).setDuration(350).start() }
        bd.videoMode = false
        if (release) heroVideo.release() else heroVideo.pause()
    }

    private var videoUrlWanted: String? = null
    /** Starts after 6 s without key presses on the home screen (ambient); never while navigating. */
    private val videoRun = Runnable { videoUrlWanted?.let { if (!saver.active) heroVideo.play(it, 0.4f) } }

    private fun syncVideo(s: Scene?) {
        val url = s?.videoUrl
        main.removeCallbacks(videoRun)
        if (url == null || saver.active) {
            videoUrlWanted = null
            if (heroVideo.playingUrl != null || hero.alpha < 1f) videoStopped(release = true)
            return
        }
        videoUrlWanted = url
        if (heroVideo.playingUrl == url) return
        if (heroVideo.playingUrl != null) videoStopped(release = true)
        main.postDelayed(videoRun, VIDEO_IDLE_MS)
    }

    private fun showScene(s: Scene, count: Int, index: Int) {
        if (bd.cur === s) return
        bd.show(s)
        syncVideo(s)
        if (!moving) {
            title.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(if (s.isShelf) 68f else 60f))
            title.text = if (s.isShelf || s.needsTextTitle) s.title else ""
            tmdbCredit.visibility = if (s.isShelf) View.VISIBLE else View.GONE
            meta.text = s.meta
            desc.text = s.desc
            meta.visibility = if (s.meta.isEmpty()) View.GONE else View.VISIBLE
            desc.visibility = if (s.desc.isEmpty()) View.GONE else View.VISIBLE
        }
        dots.count = count
        dots.index = index
        animateFloat(info, R.id.anim_title, 0f, 1f, 360) {
            titleFade = it
            info.alpha = (1f - dim.alpha * 1.6f).coerceAtLeast(0f) * it
            dots.alpha = info.alpha
        }
    }

    // ---- Keys ----------------------------------------------------------------------------------

    /** After navigation stops (1.5 s without keys) the background video continues. */
    private val videoResume = Runnable { heroVideo.resume() }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        scheduleIdle()
        if (down) {
            // No video while navigating: the player is fully released; restarts after 6 s without keys
            if (heroVideo.playingUrl != null || hero.alpha < 1f) videoStopped(release = true)
            main.removeCallbacks(videoRun)
            if (videoUrlWanted != null) main.postDelayed(videoRun, VIDEO_IDLE_MS)
        }
        // While the screensaver is on, the first key only closes it
        if (saver.active) {
            if (down) { saver.stop(); swallowKeyUp = true; syncVideo(bd.cur) }
            return true
        }
        if (swallowKeyUp) {
            if (e.action == KeyEvent.ACTION_UP) swallowKeyUp = false
            return true
        }
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) navigate(-1, 0); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) navigate(1, 0); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { if (down) navigate(0, -1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) navigate(0, 1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                onOk(e); return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (e.action == KeyEvent.ACTION_UP) when {
                    moving -> endMove()
                    focus >= Ui.COLS -> setFocus(focus % Ui.COLS, 0, -1)
                }
                return true // Back never leaves the home screen
            }
        }
        return super.dispatchKeyEvent(e)
    }

    private fun onOk(e: KeyEvent) {
        val t = tiles.getOrNull(focus) ?: return
        if (e.action == KeyEvent.ACTION_DOWN) {
            if (e.repeatCount == 0) {
                okDownAt = e.eventTime; okLongDone = false
                if (!moving) t.animate().scaleX(1.05f).scaleY(1.05f).translationZ(Ui.px(14f))
                    .setDuration(110).setInterpolator(DecelerateInterpolator()).start()
            } else if (!okLongDone && e.eventTime - okDownAt >= 650) {
                okLongDone = true
                if (moving) hideFocused() else startMove()
            }
            return
        }
        if (e.action != KeyEvent.ACTION_UP || okLongDone) return
        if (moving) { endMove(); return }
        t.animate().scaleX(Ui.FOCUS_SCALE).scaleY(Ui.FOCUS_SCALE).translationZ(Ui.px(34f))
            .setDuration(160).setInterpolator(DecelerateInterpolator()).start()
        try {
            startActivity(t.entry.intent)
        } catch (ex: Exception) {
            Log.w(TAG, "launch ${t.entry.key}", ex)
        }
    }

    // ---- Move mode (long press -> tile wiggles -> move with the D-pad) -----------------------

    private fun startMove() {
        moving = true
        val t = tiles[focus]
        t.animate().scaleX(Ui.FOCUS_SCALE).scaleY(Ui.FOCUS_SCALE).translationZ(Ui.px(40f)).setDuration(160).start()
        startWiggle(t)
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(40f))
        title.text = getString(R.string.move_hint)
        meta.visibility = View.GONE; desc.visibility = View.GONE; tmdbCredit.visibility = View.GONE
    }

    private fun startWiggle(t: View) {
        wiggle?.cancel()
        wiggle = ValueAnimator.ofFloat(-1.6f, 1.6f).apply {
            duration = 130
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { t.rotation = it.animatedValue as Float }
            start()
        }
    }

    private fun endMove() {
        moving = false
        wiggle?.cancel(); wiggle = null
        tiles.forEach { it.rotation = 0f }
        AppRepo.saveOrder(this, entries)
        bd.cur?.let { val c = it; bd.clear(); showScene(c, dots.count, dots.index) }
        setFocus(focus, 0, 0)
    }

    private fun hideFocused() {
        val e = entries.getOrNull(focus) ?: return
        if (e.isSettings) return
        moving = false
        wiggle?.cancel(); wiggle = null
        AppRepo.hide(this, e.key)
        AppRepo.saveOrder(this, entries.filter { it !== e })
        focus = (focus - 1).coerceAtLeast(0)
        title.text = ""
        reloadApps()
    }

    private fun swap(a: Int, b: Int) {
        java.util.Collections.swap(entries, a, b)
        java.util.Collections.swap(tiles, a, b)
        java.util.Collections.swap(labels, a, b)
        focus = b
        labels[a].alpha = 0f
        place(a, true)
        place(b, true)
        labels[b].animate().alpha(1f).setDuration(120).start()
        startWiggle(tiles[b])
        tiles[a].rotation = 0f
        updateScroll(b / Ui.COLS)
    }

    // ---- Lifecycle and memory ------------------------------------------------------------------

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Home pressed while already home: go back to the top row.
        if (moving) endMove()
        if (focus >= Ui.COLS) setFocus(focus % Ui.COLS, 0, -1)
    }

    /**
     * When CouchKit Home is the home screen (default home app or Home-button service enabled), the
     * previous launcher ([Launchers.PREVIOUS]) can sit in RAM for nothing (~200 MB on some TVs). Its
     * package is never disabled; only its *background* processes are killed. The system does not kill
     * foreground/service-priority processes this way and restarts them if needed.
     */
    private val trimPreviousLauncher = Runnable {
        val serviceOn = android.provider.Settings.Secure.getString(contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains("$packageName/") == true
        val isHome = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == packageName
        if (!serviceOn && !isHome) return@Runnable
        lastTrim = android.os.SystemClock.elapsedRealtime()
        val am = getSystemService(android.app.ActivityManager::class.java)
        for (pkg in Launchers.PREVIOUS) am.killBackgroundProcesses(pkg)
        Log.i(TAG, "previous launcher background processes trimmed")
    }
    private var lastTrim = 0L

    /** Idle: screensaver after 5 minutes. */
    private val idleRun = Runnable { if (!moving) { videoStopped(); saver.start() } }
    private fun scheduleIdle() {
        main.removeCallbacks(idleRun)
        main.postDelayed(idleRun, IDLE_MS)
    }

    private fun showFx(r: List<Fx.Rate>?) { if (r == null) fxPill.clear() else fxPill.set(r) }

    private val fxRun: Runnable = Runnable {
        if (!Fx.enabled) return@Runnable
        net.post { Fx.fetch(applicationContext)?.let { r -> main.post { showFx(r) } } }
        main.postDelayed(fxRun, Fx.REFRESH_MS)
    }

    private val batteriesRun: Runnable = Runnable {
        net.post { batteries.fetch(applicationContext)?.let { l -> main.post { batteriesPill.set(l) } } }
        main.postDelayed(batteriesRun, batteries.refreshMs)
    }

    /**
     * Followed match: all requests go through the shared [Football] cache and daily budget; this only
     * decides how often to look at it. Debug builds: files/match-demo shows a neutral preview match.
     */
    private val footballRun: Runnable = Runnable {
        val now = System.currentTimeMillis()
        if (Football.isDebug(this) && java.io.File(getExternalFilesDir(null), "match-demo").exists()) {
            if (demoStart == 0L) demoStart = now
            matchCard.setCrests(null, null)
            matchCard.show(Football.preview(demoStart))
            main.removeCallbacks(footballRun)
            main.postDelayed(footballRun, 15_000L)
            return@Runnable
        }
        demoStart = 0L
        if (!Football.enabled) { matchCard.hide(); return@Runnable }
        net.post {
            val list = try { Football.update(applicationContext) } catch (e: Exception) { emptyList() }
            val m = Football.pick(list)
            val crests = if (m != null && m.id != matchCard.shownId) try {
                Football.crest(applicationContext, m.home.logo) to Football.crest(applicationContext, m.away.logo)
            } catch (e: Exception) { null } else null
            main.post {
                if (m == null) matchCard.hide() else {
                    crests?.let { matchCard.setCrests(it.first, it.second) }
                    matchCard.show(m)
                }
                main.removeCallbacks(footballRun)
                main.postDelayed(footballRun, Football.pollDelay(list))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        visible = true
        current = java.lang.ref.WeakReference(this)
        scheduleIdle()
        if (android.os.SystemClock.elapsedRealtime() - lastTrim > 60_000) main.postDelayed(trimPreviousLauncher, 5000)
    }

    companion object {
        /** The goal pill is not shown while the home screen is in front (the match card celebrates here). */
        @Volatile var visible = false
        private var current: java.lang.ref.WeakReference<HomeActivity>? = null

        /** If the goal pill opens over the home screen (preview), hide the score band meanwhile to avoid overlap. */
        fun hideMatchBandFor(ms: Long) {
            val a = current?.get() ?: return
            a.main.post {
                a.matchCard.animate().alpha(0f).setDuration(250).start()
                a.main.postDelayed({ a.matchCard.animate().alpha(1f).setDuration(400).start() }, ms)
            }
        }
    }

    override fun onPause() {
        visible = false
        main.removeCallbacks(trimPreviousLauncher)
        main.removeCallbacks(idleRun)
        saver.stop()
        super.onPause()
    }

    private fun showWeather(w: Weather.Now?) {
        if (w == null) { wxPill.clearWeather(); wxCredit.visibility = View.GONE; return }
        wxPill.setWeather(w); wxCredit.visibility = View.VISIBLE
    }

    private val weatherRun: Runnable = Runnable {
        if (!Weather.enabled) return@Runnable
        net.post { Weather.fetch(applicationContext)?.let { w -> main.post { showWeather(w) } } }
        main.postDelayed(weatherRun, Weather.REFRESH_MS)
    }

    override fun onStart() {
        super.onStart()
        if (Config.refresh(this)) {
            // Config changed: featured content / data sources may differ, rebuild scenes
            topScenes.clear(); sceneCache.evictAll()
            if (bd.cur != null) bd.clear()
        }
        showWeather(Weather.cached(this))
        main.removeCallbacks(weatherRun)
        if (Weather.isStale(this)) main.post(weatherRun) else main.postDelayed(weatherRun, Weather.REFRESH_MS)
        showFx(Fx.cached(this))
        main.removeCallbacks(fxRun)
        if (Fx.isStale(this)) main.post(fxRun) else main.postDelayed(fxRun, Fx.REFRESH_MS)
        batteriesPill.set(batteries.cached(this))
        main.removeCallbacks(batteriesRun)
        // A new hand-off file was pushed: import it right away
        if (batteries.isStale(this) || batteries.hasPendingImport(this)) main.post(batteriesRun)
        else main.postDelayed(batteriesRun, batteries.refreshMs)
        main.removeCallbacks(footballRun)
        main.post(footballRun)
        if (tilesTrimmed) loadTileBitmaps()
        syncVideo(bd.cur)
        if (bd.cur == null && tiles.isNotEmpty()) scheduleHero(0)
        else if (focus < Ui.COLS) {
            // Back on the home screen: random wallpaper (and video); featured apps show their next item
            entries.getOrNull(focus)?.let { if (Shelf.has(it.key)) bump(it.key) else shelfIndex[WALL] = kotlin.random.Random.nextInt(1, 1_000_000) }
            scheduleHero(0)
            main.postDelayed({ prewarmTop() }, 1500)
        }
    }

    override fun onStop() {
        super.onStop()
        // The current featured image (~4 MB) STAYS in memory, so returning from an app is never black.
        // Only other cached scenes are released; under real memory pressure onTrimMemory drops everything.
        videoStopped()
        main.removeCallbacks(heroRun)
        main.removeCallbacks(advanceRun)
        main.removeCallbacks(weatherRun)
        main.removeCallbacks(fxRun)
        main.removeCallbacks(batteriesRun)
        main.removeCallbacks(footballRun)
        heroGen++
        sceneCache.evictAll()
        topScenes.clear()
        bd.cur?.let { saveLastScene(it) }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            videoStopped()
            bd.clear()
            tiles.forEach { it.bitmap = null }
            tilesTrimmed = true
        }
    }

    private var savedKey: String? = null
    private fun lastSceneFile() = java.io.File(cacheDir, "last-shelf.jpg")

    /** Writes the last featured image to disk: the first frame after a process restart. */
    private fun saveLastScene(s: Scene) {
        if (s.key == savedKey) return
        savedKey = s.key
        val b = s.baked
        worker.post {
            try {
                val tmp = java.io.File(cacheDir, "last-shelf.tmp")
                tmp.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
                tmp.renameTo(lastSceneFile())
            } catch (e: Exception) { Log.w(TAG, "save last scene", e) }
        }
    }

    private fun loadLastScene() {
        val f = lastSceneFile()
        if (!f.isFile) return
        val b = android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply {
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }) ?: return
        val s = Scene("@last", "", null, null, null)
        s.baked = b
        s.blur = android.graphics.Bitmap.createScaledBitmap(b, Ui.BLUR_W, Ui.BLUR_H, true)
            .copy(android.graphics.Bitmap.Config.ARGB_8888, true).also { Blur.blurInPlace(it, 5) }
        bd.showNow(s)
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun animateFloat(
        v: View, tagId: Int, from: Float, to: Float, dur: Long,
        startDelay: Long = 0, end: (() -> Unit)? = null, set: (Float) -> Unit,
    ) {
        (v.getTag(tagId) as? ValueAnimator)?.cancel()
        val a = ValueAnimator.ofFloat(from, to).apply {
            duration = dur
            this.startDelay = startDelay
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { set(it.animatedValue as Float) }
            if (end != null) addListener(object : android.animation.AnimatorListenerAdapter() {
                var cancelled = false
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) { if (!cancelled) end() }
            })
        }
        v.setTag(tagId, a)
        a.start()
    }

    /**
     * Watch Next probe: other apps' rows need ACCESS_ALL_EPG_DATA (a system permission), so this is
     * only logged; the featured row works from banners / TMDB instead.
     */
    private fun probeWatchNext() {
        try {
            contentResolver.query(TvContract.WatchNextPrograms.CONTENT_URI, arrayOf(TvContract.WatchNextPrograms.COLUMN_TITLE), null, null, null)
                ?.use { Log.i(TAG, "watch next rows visible: ${it.count}") }
                ?: Log.i(TAG, "watch next: null cursor")
        } catch (e: Exception) {
            Log.i(TAG, "watch next not accessible: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
