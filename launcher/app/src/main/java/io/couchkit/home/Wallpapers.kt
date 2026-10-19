// SPDX-License-Identifier: Apache-2.0
package io.couchkit.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * User-supplied background images: `<external files dir>/wallpapers/` (jpg/png/webp, ideally 16:9).
 * Nothing is bundled in the APK. Apps without featured content rotate through them; if the folder is
 * empty a mesh gradient in the app's brand colours is drawn instead. Large images are decoded with
 * power-of-two subsampling down to the bake size.
 */
object Wallpapers {
    private var cache: List<File>? = null
    private val EXT = setOf("jpg", "jpeg", "png", "webp")

    @Synchronized
    private fun all(ctx: Context): List<File> {
        cache?.let { return it }
        val dir = ctx.getExternalFilesDir("wallpapers") ?: return emptyList()
        return (dir.listFiles { f -> f.isFile && f.extension.lowercase() in EXT } ?: emptyArray()).sortedBy { it.name }
            .also { cache = it }
    }

    fun count(ctx: Context) = all(ctx).size

    /**
     * Cycles through all wallpapers (no colour matching). The order is shuffled with a fixed seed so
     * consecutive images do not look alike. n = position in the cycle. [pickCached] only works once the
     * list has been loaded (safe on the main thread, no disk access).
     */
    fun pickCached(n: Int): File? {
        val list = cache ?: return null
        if (list.isEmpty()) return null
        val order = list.indices.shuffled(java.util.Random(26))
        return list[order[Math.floorMod(n, list.size)]]
    }

    fun pick(ctx: Context, n: Int): File? {
        val list = all(ctx)
        if (list.isEmpty()) return null
        val order = list.indices.shuffled(java.util.Random(26))
        return list[order[Math.floorMod(n, list.size)]]
    }

    fun decode(f: File, maxW: Int = Ui.BAKE_W): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, b)
        if (b.outWidth <= 0) return null
        var s = 1
        while (b.outWidth / (s * 2) >= maxW) s *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply {
            inSampleSize = s; inPreferredConfig = Bitmap.Config.RGB_565
        })
    }
}

/**
 * The user's own screensaver media: `<external files dir>/screensaver/` — videos (mp4/mkv/webm) and
 * images (jpg/png). Used by the screensaver and, for videos, as the moving background of the
 * featured row. The caption is the file name without extension.
 */
object LocalMedia {
    class Item(val file: File, val isVideo: Boolean) {
        val uri: String get() = Uri.fromFile(file).toString()
        val caption: String get() = file.nameWithoutExtension.replace('_', ' ')
    }

    private val VIDEO = setOf("mp4", "mkv", "webm")
    private val IMAGE = setOf("jpg", "jpeg", "png")

    /** Video URIs seen by the last [all] call (main-thread safe, no disk access). */
    @Volatile private var lastVideos: List<String> = emptyList()

    fun all(ctx: Context): List<Item> {
        val dir = ctx.getExternalFilesDir("screensaver") ?: return emptyList()
        return (dir.listFiles() ?: emptyArray()).filter { it.isFile && it.length() > 0 }.mapNotNull { f ->
            when (f.extension.lowercase()) {
                in VIDEO -> Item(f, true)
                in IMAGE -> Item(f, false)
                else -> null
            }
        }.also { l -> lastVideos = l.filter { it.isVideo }.map { it.uri } }
    }

    fun randomVideoCached(except: String? = null): String? =
        lastVideos.filter { it != except }.ifEmpty { lastVideos }.randomOrNull()

    fun videos(ctx: Context) = all(ctx).filter { it.isVideo }

    /** A random local video URI other than [except] (if there is a choice), or null. */
    fun randomVideo(ctx: Context, except: String? = null): String? {
        val v = videos(ctx).map { it.uri }
        if (v.isEmpty()) return null
        return v.filter { it != except }.ifEmpty { v }.random()
    }
}
