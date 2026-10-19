// SPDX-License-Identifier: Apache-2.0
package io.couchkit.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/**
 * Featured row content: wide images of titles currently popular on a streaming service, from TMDB
 * (https://www.themoviedb.org) with the user's own API key (config.json "tmdb"). Android TV apps do not
 * expose this to other launchers. Without a key, every app falls back to wallpapers / mesh gradient.
 *
 * Uses /3/discover/tv and /3/discover/movie filtered by watch provider + region, sorted by popularity.
 * Watch-provider data is supplied to TMDB by a third party; the UI shows the attribution TMDB requires
 * for both (R.string.tmdb_credit) whenever this content is on screen. Lists are refreshed every 12 h, images
 * are kept in a ~25 MB disk cache.
 */
object Shelf {
    class Item(val title: String, val meta: String, val desc: String, val image: String)

    /**
     * App package -> TMDB watch provider ids ("|" = OR). Editable; ids from
     * https://developer.themoviedb.org/reference/watch-providers-tv-list (check for your region).
     */
    private val PROVIDERS = mapOf(
        "com.netflix.ninja" to "8",                          // Netflix
        "com.amazon.amazonvideo.livingroom" to "9|119",      // Amazon Prime Video
        "com.disney.disneyplus" to "337",                    // Disney Plus
        "com.wbd.stream" to "1899|384",                      // Max / HBO Max
    )

    private const val MAX_ITEMS = 6
    private const val TTL_MS = 12 * 60 * 60 * 1000L
    private const val API = "https://api.themoviedb.org/3"
    private const val IMG = "https://image.tmdb.org/t/p/w1280"

    val enabled get() = Config.tmdb != null

    fun has(pkg: String) = enabled && PROVIDERS.containsKey(pkg)

    private val memo = HashMap<String, List<Item>>()
    private var memoVersion = -1

    @Synchronized
    fun isLoaded(pkg: String) = memoVersion == Config.version && memo.containsKey(pkg)

    /** Background thread. Without network: the old cache, otherwise null. */
    @Synchronized
    fun items(ctx: Context, pkg: String): List<Item>? {
        val c = Config.tmdb ?: return null
        if (memoVersion != Config.version) { memo.clear(); memoVersion = Config.version }
        memo[pkg]?.let { return it }
        val providers = PROVIDERS[pkg] ?: return null
        val f = File(dir(ctx), "$pkg-${c.region}-${c.language}.json")
        val fresh = f.isFile && System.currentTimeMillis() - f.lastModified() < TTL_MS
        val json = if (fresh) f.readText()
            else (fetch(c, providers)?.also { f.writeText(it) } ?: f.takeIf { it.isFile }?.readText())
        val list = json?.let { parse(it) }?.takeIf { it.isNotEmpty() } ?: return null
        memo[pkg] = list
        return list
    }

    /** Downloads the image (if needed) and decodes it as RGB_565, at most maxW wide. */
    fun image(ctx: Context, item: Item, maxW: Int): Bitmap? {
        val f = File(dir(ctx), item.image.hashCode().toUInt().toString(16) + ".img")
        if (!f.isFile) {
            val bytes = Net.bytes(item.image) ?: return null
            val tmp = File(f.path + ".part")
            tmp.writeBytes(bytes)
            tmp.renameTo(f)
            trim(ctx)
        }
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, b)
        if (b.outWidth <= 0) { f.delete(); return null }
        var s = 1
        while (b.outWidth / (s * 2) >= maxW) s *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply {
            inSampleSize = s; inPreferredConfig = Bitmap.Config.RGB_565
        })
    }

    /** Prefetch the next image (no decode) so the carousel does not wait. */
    fun prefetch(ctx: Context, item: Item) {
        val f = File(dir(ctx), item.image.hashCode().toUInt().toString(16) + ".img")
        if (f.isFile) return
        Net.bytes(item.image)?.let { File(f.path + ".part").apply { writeBytes(it); renameTo(f) } }
    }

    private fun dir(ctx: Context) = File(ctx.cacheDir, "shelf").apply { mkdirs() }

    /** Disk cache above ~25 MB: delete the oldest images. */
    private fun trim(ctx: Context) {
        val files = dir(ctx).listFiles { f -> f.name.endsWith(".img") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total < 25L * 1024 * 1024) break
            total -= f.length(); f.delete()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** A v4 read access token (long JWT) goes in the Authorization header, a v3 key in the query. */
    private fun call(c: Config.Tmdb, path: String): JSONObject? {
        val v4 = c.apiKey.length > 40
        val url = API + path + if (v4) "" else "&api_key=${enc(c.apiKey)}"
        val headers = if (v4) mapOf("Authorization" to "Bearer ${c.apiKey}") else emptyMap()
        val body = Net.text(url, headers) ?: return null
        return try { JSONObject(body) } catch (e: Exception) { null }
    }

    /** Fetches popular series + movies, interleaves them and stores our own compact JSON. */
    private fun fetch(c: Config.Tmdb, providers: String): String? {
        val q = "?with_watch_providers=${enc(providers)}&watch_region=${enc(c.region)}" +
            "&language=${enc(c.language)}&sort_by=popularity.desc&include_adult=false&page=1"
        val tv = call(c, "/discover/tv$q")?.optJSONArray("results")
        val movie = call(c, "/discover/movie$q")?.optJSONArray("results")
        if (tv == null && movie == null) { Log.w(TAG, "shelf: TMDB request failed"); return null }
        val a = items(tv, "Series", "name", "first_air_date")
        val b = items(movie, "Movie", "title", "release_date")
        val out = JSONArray()
        var i = 0
        while (out.length() < MAX_ITEMS && (i < a.size || i < b.size)) {
            a.getOrNull(i)?.let { if (out.length() < MAX_ITEMS) out.put(it) }
            b.getOrNull(i)?.let { if (out.length() < MAX_ITEMS) out.put(it) }
            i++
        }
        return out.toString()
    }

    private fun items(a: JSONArray?, type: String, titleKey: String, dateKey: String): List<JSONObject> {
        if (a == null) return emptyList()
        val out = ArrayList<JSONObject>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val path = o.optString("backdrop_path").takeIf { it.startsWith("/") } ?: continue
            val year = o.optString(dateKey).take(4).takeIf { it.length == 4 }
            out.add(JSONObject().put("title", o.optString(titleKey)).put("desc", o.optString("overview"))
                .put("meta", listOfNotNull(type, year).joinToString("  ·  ")).put("image", IMG + path))
        }
        return out
    }

    private fun parse(json: String): List<Item> = try {
        val a = JSONArray(json)
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Item(o.optString("title"), o.optString("meta"), o.optString("desc"), o.getString("image"))
        }
    } catch (e: Exception) {
        Log.w(TAG, "shelf parse: ${e.message}"); emptyList()
    }
}
