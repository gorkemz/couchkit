// SPDX-License-Identifier: Apache-2.0
package io.couchkit.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import java.io.File

const val TAG = "CouchKitHome"

/** Launchers that CouchKit Home replaces. */
object Launchers {
    /**
     * Packages of the previous default launcher (here: the stock Google TV launcher). They are never
     * disabled; HomeKeyService redirects their home screen, and HomeActivity only asks the system to
     * kill their *background* processes to free RAM (ActivityManager.killBackgroundProcesses).
     * Keep in sync with res/xml/home_key_service.xml.
     */
    val PREVIOUS = listOf("com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher")
}

class AppEntry(
    val key: String,
    val label: String,
    val intent: Intent,
    val ai: ApplicationInfo?,
    val bannerRes: Int,
    val iconRes: Int,
) {
    val isSettings get() = key == SETTINGS_KEY

    companion object {
        const val SETTINGS_KEY = "@settings"
    }
}

object AppRepo {
    /**
     * Default order on first start (package names only, used to sort installed apps); the user then
     * reorders by long-pressing. Apps not listed follow alphabetically.
     */
    private val PRIORITY = listOf(
        "com.google.android.youtube.tv",
        "com.netflix.ninja",
        "com.amazon.amazonvideo.livingroom",
        "com.disney.disneyplus",
        "com.wbd.stream",
        "com.spotify.tv.android",
        "com.google.android.youtube.tvmusic",
        "tv.twitch.android.app",
        "com.android.vending",
    )

    private const val PREFS = "home"
    private const val KEY_ORDER = "order"
    private const val KEY_HIDDEN = "hidden"
    private val DEFAULT_HIDDEN = emptySet<String>()

    private fun hidden(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREFS, 0).getStringSet(KEY_HIDDEN, null) ?: DEFAULT_HIDDEN

    fun hide(ctx: Context, key: String) {
        ctx.getSharedPreferences(PREFS, 0).edit().putStringSet(KEY_HIDDEN, hidden(ctx) + key).apply()
    }

    fun load(ctx: Context): MutableList<AppEntry> {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val byKey = LinkedHashMap<String, AppEntry>()
        val hidden = hidden(ctx)
        for (ri in pm.queryIntentActivities(main, 0)) {
            val a = ri.activityInfo ?: continue
            if (a.packageName == ctx.packageName || byKey.containsKey(a.packageName) || a.packageName in hidden) continue
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(ComponentName(a.packageName, a.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            byKey[a.packageName] = AppEntry(
                key = a.packageName,
                label = ri.loadLabel(pm).toString(),
                intent = intent,
                ai = a.applicationInfo,
                bannerRes = if (a.banner != 0) a.banner else a.applicationInfo.banner,
                iconRes = if (a.icon != 0) a.icon else a.applicationInfo.icon,
            )
        }
        byKey[AppEntry.SETTINGS_KEY] = AppEntry(
            AppEntry.SETTINGS_KEY, ctx.getString(R.string.settings),
            Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), null, 0, 0,
        )

        val saved = ctx.getSharedPreferences(PREFS, 0).getString(KEY_ORDER, null)
            ?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()
        val out = ArrayList<AppEntry>(byKey.size)
        for (k in saved) byKey.remove(k)?.let(out::add)
        // New apps: known popular order first, then alphabetical; Settings last.
        val rest = byKey.values.sortedWith(compareBy<AppEntry>(
            { if (it.isSettings) 2 else if (PRIORITY.contains(it.key)) 0 else 1 },
            { PRIORITY.indexOf(it.key) },
            { it.label.lowercase() },
        ))
        out.addAll(rest)
        return out
    }

    fun saveOrder(ctx: Context, entries: List<AppEntry>) {
        ctx.getSharedPreferences(PREFS, 0).edit()
            .putString(KEY_ORDER, entries.joinToString("\n") { it.key }).apply()
    }
}

/**
 * Featured-row scene (edge to edge): either a real wide image (cover), or an ambient background made
 * from the banner's colours or a user wallpaper (field) + the logo cut out of the banner, bottom left.
 */
class Scene(
    val key: String,
    val title: String,
    var cover: Bitmap?,
    var field: Bitmap?,
    var logo: Bitmap?,
    val meta: String = "",
    val desc: String = "",
    /** Real content image (Shelf): title/overview shown as text, extra shade on the left. */
    val isShelf: Boolean = false,
) {
    /** No logo could be cut out and no cover image: the title is shown as text. */
    val needsTextTitle = logo == null && cover == null
    /** Extra shade on the left of photos so text/logo stay readable. */
    var leftShade = isShelf || (cover != null && logo != null)

    /**
     * ONE screen-sized image with shade and grain baked in. The screen draws only this per frame
     * (several full-screen gradient layers stuttered on weak TV GPUs).
     */
    lateinit var baked: Bitmap
    lateinit var blur: Bitmap
    /** Built from a user wallpaper: rotates to the next one over time. */
    var rotates = false
    /** Local video (file URI) played as moving background after a few idle seconds, if the user has any. */
    var videoUrl: String? = null
    /** Carousel size and this scene's position (for the page dots). */
    var shelfCount = 0
    var shelfIdx = 0
    var blurShader: android.graphics.BitmapShader? = null
    val bytes get() = baked.byteCount + blur.byteCount + (logo?.byteCount ?: 0)
}

/** Draws a scene at a given size. One instance per thread (Paints are not shared). */
class SceneRenderer {
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shadePaint = Paint(Paint.DITHER_FLAG)
    private val m = Matrix()
    private val rect = RectF()
    private var shadeW = -1
    private var shadeH = -1
    private var topShade: LinearGradient? = null
    private var bottomShade: LinearGradient? = null
    private var leftShade: LinearGradient? = null

    fun draw(c: Canvas, s: Scene, w: Int, h: Int, alpha: Int) {
        bmpPaint.alpha = alpha
        val img = s.cover ?: s.field ?: return
        centerCrop(img, w, h)
        c.drawBitmap(img, m, bmpPaint)
    }

    /** Title logo: bottom left, just above the dock. */
    fun drawLogo(c: Canvas, s: Scene, w: Int, h: Int, alpha: Int) {
        val logo = s.logo ?: return
        val k = w / 1920f
        // The logo was pre-upscaled 3x (Logo.smoothUpscale): it is only ever drawn scaled down
        val sc = minOf(560f * k / logo.width, 170f * k / logo.height, 2.6f / 3f * k)
        val lw = logo.width * sc
        val lh = logo.height * sc
        val left = Ui.SIDE * k
        val bottom = h * (708f / 1080f)
        rect.set(left, bottom - lh, left + lw, bottom)
        bmpPaint.alpha = alpha
        c.drawBitmap(logo, null, rect, bmpPaint)
    }

    /** Shade: light at the top for the clock, strong at the bottom for the dock. */
    fun drawShade(c: Canvas, w: Int, h: Int, left: Boolean = false) {
        if (w != shadeW || h != shadeH) {
            shadeW = w; shadeH = h
            topShade = LinearGradient(0f, 0f, 0f, h * 0.22f, 0x66000000, 0, Shader.TileMode.CLAMP)
            bottomShade = LinearGradient(0f, h * 0.34f, 0f, h.toFloat(),
                intArrayOf(0, 0x80000000.toInt(), 0xE0000000.toInt()), floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP)
            leftShade = LinearGradient(0f, 0f, w * 0.62f, 0f, 0x99000000.toInt(), 0, Shader.TileMode.CLAMP)
        }
        if (left) {
            shadePaint.shader = leftShade
            c.drawRect(0f, 0f, w * 0.62f, h.toFloat(), shadePaint)
        }
        shadePaint.shader = topShade
        c.drawRect(0f, 0f, w.toFloat(), h * 0.22f, shadePaint)
        shadePaint.shader = bottomShade
        c.drawRect(0f, h * 0.34f, w.toFloat(), h.toFloat(), shadePaint)
    }

    private fun centerCrop(b: Bitmap, w: Int, h: Int) {
        val sc = maxOf(w.toFloat() / b.width, h.toFloat() / b.height)
        m.setScale(sc, sc)
        m.postTranslate((w - b.width * sc) / 2f, (h - b.height * sc) / 2f)
    }
}

/** Banner/icon loading. Runs on a background thread. */
class Images(private val ctx: Context) {
    private val pm = ctx.packageManager
    private val renderer = SceneRenderer()
    val tileW = Ui.pxi(Ui.TILE_W * Ui.FOCUS_SCALE)
    val tileH = Ui.pxi(Ui.TILE_H * Ui.FOCUS_SCALE)

    /** Opens the app's resources at xxxhdpi (640) to get the largest banner variant. */
    private fun hiRes(ai: ApplicationInfo): Resources? = try {
        val base = pm.getResourcesForApplication(ai)
        val conf = Configuration(base.configuration).apply { densityDpi = 640 }
        val dm = DisplayMetrics().apply {
            setTo(base.displayMetrics); densityDpi = 640; density = 4f; scaledDensity = 4f
        }
        @Suppress("DEPRECATION")
        Resources(base.assets, dm, conf)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** Decodes a resource at its real pixel size (no scaling); subsamples by powers of two above maxW. */
    private fun decodeRes(res: Resources, id: Int, maxW: Int): Bitmap? = try {
        val tv = TypedValue()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        res.openRawResource(id, tv).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) null else {
            var s = 1
            while (bounds.outWidth / (s * 2) >= maxW) s *= 2
            val o = BitmapFactory.Options().apply { inSampleSize = s; inScaled = false }
            res.openRawResource(id, tv).use { BitmapFactory.decodeStream(it, null, o) }
        }
    } catch (e: Exception) {
        null
    }

    private fun drawable(res: Resources, id: Int): Drawable? = try {
        res.getDrawable(id, null)
    } catch (e: Exception) {
        null
    }

    /** Use the tile from disk if present (opening 25+ apps' resources at start is slow). */
    fun tile(e: AppEntry): Bitmap {
        val stamp = if (e.isSettings) "v1" else try { pm.getPackageInfo(e.key, 0).lastUpdateTime.toString() } catch (x: Exception) { "0" }
        // v4: full colour (ARGB) + lossless PNG; bump to regenerate cached tiles
        val f = File(File(ctx.cacheDir, "tiles").apply { mkdirs() }, "${e.key}_${stamp}_${tileW}x${tileH}_v4.png")
        if (f.isFile) BitmapFactory.decodeFile(f.path)?.let { return it }
        val out = renderTile(e)
        try {
            File(f.parentFile, "${e.key}_").let { prefix ->
                f.parentFile?.listFiles { x -> x.name.startsWith(prefix.name) }?.forEach { it.delete() }
            }
            f.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (x: Exception) { Log.w(TAG, "tile cache ${e.key}", x) }
        return out
    }

    private fun renderTile(e: AppEntry): Bitmap {
        val out = Bitmap.createBitmap(tileW, tileH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFF1C1C1E.toInt())
        if (e.isSettings) {
            drawSettingsIcon(c, tileW, tileH); return out
        }
        val res = e.ai?.let(::hiRes)
        if (res != null && e.bannerRes != 0) {
            val b = decodeRes(res, e.bannerRes, tileW)
            if (b != null) {
                // Light banners glare like white cards on the dark glass dock:
                // cut the logo out and put it on a dark tile.
                if (Logo.isLight(b)) Logo.extract(b, tolerant = true).logo?.let { drawLogoTile(c, it, tileW, tileH); return out }
                drawCover(c, b, tileW, tileH); return out
            }
            val d = drawable(res, e.bannerRes)
            if (d != null) { drawDrawableCover(c, d, tileW, tileH); return out }
        }
        drawIconTile(c, res, e, tileW, tileH)
        return out
    }

    fun scene(e: AppEntry, tileFallback: Bitmap?, n: Int = 0): Scene {
        val sw = Ui.screenW
        val sh = Ui.screenH
        val maxW = minOf(1280, sw)
        var cover: Bitmap? = null
        var card: Bitmap? = null

        // 1) Custom image supplied by the user: <external files dir>/shelf/<package>.jpg|png|webp
        val dir = ctx.getExternalFilesDir("shelf")
        if (dir != null) {
            for (ext in arrayOf("jpg", "png", "webp")) {
                val f = File(dir, "${e.key}.$ext")
                if (f.isFile) { cover = decodeFile(f, maxW); if (cover != null) break }
            }
        }
        // 2) Highest-resolution banner (skip if its logo was already extracted)
        if (cover == null && !e.isSettings && !logoCache.containsKey(e.key)) {
            val res = e.ai?.let(::hiRes)
            if (res != null && e.bannerRes != 0) {
                val b = decodeRes(res, e.bannerRes, maxW)
                if (b != null) {
                    Log.d(TAG, "banner ${e.key}: ${b.width}x${b.height}")
                    if (b.width >= 900) cover = b else card = b
                } else {
                    // Vector / layered banner: drawn sharp at the requested size
                    drawable(res, e.bannerRes)?.let { d ->
                        val bmp = Bitmap.createBitmap(maxW, maxW * 9 / 16, Bitmap.Config.ARGB_8888)
                        drawDrawableCover(Canvas(bmp), d, bmp.width, bmp.height)
                        cover = bmp
                    }
                }
            }
        }
        var field: Bitmap? = null
        var logo: Bitmap? = null
        if (cover == null) {
            // Logo + brand colours are extracted once per app
            val (lg, hues) = synchronized(logoCache) { logoCache[e.key] } ?: run {
                val src = card ?: tileFallback ?: tile(e)
                val k = Logo.extract(src)
                // Large title logo: smooth 3x upscale (palette from the small one)
                (k.logo?.let { Logo.smoothUpscale(it) } to Mesh.palette(k.logo ?: src, k.bg))
                    .also { synchronized(logoCache) { logoCache[e.key] = it } }
            }
            logo = lg
            // User wallpaper (rotating): baked once per wallpaper and shared by all apps; switching
            // apps only swaps the logo (instant).
            val wp = Wallpapers.pick(ctx, n)
            if (wp != null) {
                val bg = wallBg(wp)
                if (bg != null) {
                    return Scene("${e.key}@$n", e.label, null, null, logo).also {
                        it.baked = bg.first; it.blur = bg.second; it.leftShade = true
                        it.rotates = Wallpapers.count(ctx) > 1
                        it.videoUrl = LocalMedia.randomVideo(ctx)
                    }
                }
            }
            field = Mesh.render(hues, e.key, 480, 270)
        }
        return finish(Scene("${e.key}@$n", e.label, cover, field, logo).also {
            if (cover == null) it.videoUrl = LocalMedia.randomVideo(ctx)
        })
    }

    /** Featured content: wide series/movie image + title, type/year, short overview. */
    fun shelfScene(e: AppEntry, item: Shelf.Item): Scene? {
        val cover = Shelf.image(ctx, item, Ui.BAKE_W) ?: return null
        return finish(Scene("${e.key}#${item.image}", item.title, cover, null, null, item.meta, item.desc, true))
    }

    private val logoCache = HashMap<String, Pair<Bitmap?, FloatArray>>()
    private val bgCache = android.util.LruCache<String, Pair<Bitmap, Bitmap>>(1)

    /** Builds the scene instantly if logo and wallpaper background are ready (main-thread safe; no IO). */
    fun quickScene(e: AppEntry, n: Int): Scene? {
        if (e.isSettings) return null
        val lh = synchronized(logoCache) { logoCache[e.key] } ?: return null
        val wp = Wallpapers.pickCached(n) ?: return null
        val bg = bgCache.get(wp.name) ?: return null
        return Scene("${e.key}@$n", e.label, null, null, lh.first).also {
            it.baked = bg.first; it.blur = bg.second; it.leftShade = true
            it.rotates = true
            it.videoUrl = LocalMedia.randomVideoCached()
        }
    }

    /** Baked wallpaper background (shaded) + glass blur; only the latest is kept in memory. */
    private fun wallBg(wp: File): Pair<Bitmap, Bitmap>? {
        bgCache.get(wp.name)?.let { return it }
        val cover = Wallpapers.decode(wp) ?: return null
        val w = Ui.BAKE_W
        val h = Ui.BAKE_H
        val baked = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val c = Canvas(baked)
        val tmp = Scene("bg", "", cover, null, null)
        renderer.draw(c, tmp, w, h, 255)
        renderer.drawShade(c, w, h, true)
        cover.recycle()   // several MB: release without waiting for GC
        val small = Bitmap.createScaledBitmap(baked, Ui.BLUR_W, Ui.BLUR_H, true)
        val blur = small.copy(Bitmap.Config.ARGB_8888, true); small.recycle()
        Blur.blurInPlace(blur, 5)
        return (baked to blur).also { bgCache.put(wp.name, it) }
    }

    private val grainPaint = Paint().apply {
        shader = android.graphics.BitmapShader(Mesh.grain(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        color = 0xFFFFFFFF.toInt()
        alpha = 9
    }

    /** Bakes the scene once at screen size in the background; releases the source bitmaps. */
    private fun finish(s: Scene): Scene {
        val w = Ui.BAKE_W
        val h = Ui.BAKE_H
        val baked = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565) // gradients are dithered, no banding
        val c = Canvas(baked)
        c.drawColor(0xFF000000.toInt())
        renderer.draw(c, s, w, h, 255)
        if (s.cover == null) c.drawPaint(grainPaint)
        renderer.drawShade(c, w, h, s.leftShade)
        // logo is not baked: HeroView/VideoOverlay draw it separately (instant swap between apps)
        val blur = Bitmap.createScaledBitmap(baked, Ui.BLUR_W, Ui.BLUR_H, true).copy(Bitmap.Config.ARGB_8888, true)
        Blur.blurInPlace(blur, 5)
        s.baked = baked
        s.blur = blur
        s.cover?.recycle(); s.field?.recycle()   // baked; release sources right away
        s.cover = null; s.field = null   // logo stays: drawn on top while a video plays
        return s
    }

    private fun decodeFile(f: File, maxW: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0) return null
        var s = 1
        while (bounds.outWidth / (s * 2) >= maxW) s *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** Halves a large source repeatedly (good sampling), then centre-crops to fill the target. */
    private fun drawCover(c: Canvas, src: Bitmap, w: Int, h: Int) {
        var b = src
        while (b.width / 2 >= w * 1.5f && b.height / 2 >= h * 1.5f) {
            b = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
        }
        val sc = maxOf(w.toFloat() / b.width, h.toFloat() / b.height)
        val m = Matrix().apply {
            setScale(sc, sc); postTranslate((w - b.width * sc) / 2f, (h - b.height * sc) / 2f)
        }
        c.drawBitmap(b, m, drawPaint)
    }

    private fun drawDrawableCover(c: Canvas, d: Drawable, w: Int, h: Int) {
        if (d is BitmapDrawable && d.bitmap != null) { drawCover(c, d.bitmap, w, h); return }
        val iw = if (d.intrinsicWidth > 0) d.intrinsicWidth else w
        val ih = if (d.intrinsicHeight > 0) d.intrinsicHeight else h
        val sc = maxOf(w.toFloat() / iw, h.toFloat() / ih)
        val dw = (iw * sc).toInt()
        val dh = (ih * sc).toInt()
        d.setBounds((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh)
        d.draw(c)
    }

    /** Dark gradient tile + centred logo (for light banners). */
    private fun drawLogoTile(c: Canvas, logo: Bitmap, w: Int, h: Int) {
        val p = Paint(Paint.DITHER_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF2C2C30.toInt(), 0xFF141416.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        val sc = minOf(w * 0.78f / logo.width, h * 0.56f / logo.height)
        val lw = logo.width * sc
        val lh = logo.height * sc
        c.drawBitmap(logo, null, RectF((w - lw) / 2, (h - lh) / 2, (w + lw) / 2, (h + lh) / 2), drawPaint)
    }

    /** App without a banner: icon on a dark gradient card. */
    private fun drawIconTile(c: Canvas, res: Resources?, e: AppEntry, w: Int, h: Int) {
        val p = Paint(Paint.DITHER_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF3A3A40.toInt(), 0xFF1C1C20.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        val d = (if (res != null && e.iconRes != 0) drawable(res, e.iconRes) else null)
            ?: e.ai?.let { pm.getApplicationIcon(it) } ?: return
        val s = (h * 0.56f).toInt()
        d.setBounds((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2)
        d.draw(c)
    }

    /** Generic settings tile: grey background, light grey gear. */
    private fun drawSettingsIcon(c: Canvas, w: Int, h: Int) {
        val bg = Paint(Paint.DITHER_FLAG)
        bg.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF9A9AA0.toInt(), 0xFF5E5E64.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        val cx = w / 2f
        val cy = h / 2f
        val r = h * 0.27f
        val gear = Path()
        val teeth = 10
        val tooth = RectF()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, cy - r, 0f, cy + r, 0xFFF4F4F6.toInt(), 0xFFC4C4CA.toInt(), Shader.TileMode.CLAMP)
        p.setShadowLayer(h * 0.03f, 0f, h * 0.012f, 0x66000000)
        gear.addCircle(cx, cy, r * 0.84f, Path.Direction.CW)
        for (i in 0 until teeth) {
            val m = Matrix().apply { setRotate(i * 360f / teeth, cx, cy) }
            val t = Path()
            tooth.set(cx - r * 0.16f, cy - r * 1.08f, cx + r * 0.16f, cy - r * 0.6f)
            t.addRoundRect(tooth, r * 0.06f, r * 0.06f, Path.Direction.CW)
            t.transform(m)
            gear.op(t, Path.Op.UNION)
        }
        val hole = Path().apply { addCircle(cx, cy, r * 0.36f, Path.Direction.CW) }
        gear.op(hole, Path.Op.DIFFERENCE)
        c.drawPath(gear, p)
    }
}
