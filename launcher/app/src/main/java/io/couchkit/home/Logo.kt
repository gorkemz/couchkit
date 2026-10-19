// SPDX-License-Identifier: Apache-2.0
package io.couchkit.home

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Cuts a logo out of a banner: if the edge ring is a single colour (true for most TV banners) that
 * background is keyed out, the colour of soft edges is recovered and the logo is cropped.
 * Dark/grey lettering is turned white so it stays visible on the dark background.
 */
object Logo {
    class Result(val logo: Bitmap?, val bg: Int?)

    private const val NEAR = 22f   // this close to the background colour -> fully transparent

    /** [tolerant]: on light tiles, treat a thin border/shadow at the edge as background. */
    fun extract(src0: Bitmap, tolerant: Boolean = false): Result {
        val src = if (src0.config == Bitmap.Config.ARGB_8888) src0 else src0.copy(Bitmap.Config.ARGB_8888, false)
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        // Mean and deviation of the edge ring (3 px)
        var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
        fun ring(f: (Int) -> Unit) {
            val t = 3
            for (y in 0 until h) for (x in 0 until w) {
                if (x < t || y < t || x >= w - t || y >= h - t) f(px[y * w + x])
            }
        }
        ring { p -> sr += r(p); sg += g(p); sb += b(p); n++ }
        if (n == 0) return Result(null, null)
        val br = (sr / n).toInt(); val bgc = (sg / n).toInt(); val bb = (sb / n).toInt()
        var dev = 0L
        ring { p -> dev += max(abs(r(p) - br), max(abs(g(p) - bgc), abs(b(p) - bb))) }
        val bg = (0xFF shl 24) or (br shl 16) or (bgc shl 8) or bb
        if (dev / n > (if (tolerant) 42 else 16)) return extractBright(px, w, h)

        // Upper end of the opacity ramp follows the logo's own contrast: anti-aliasing pixels
        // (e.g. pink between a red box and white background) stay semi-transparent, no light halo.
        val hist = IntArray(256)
        for (p in px) hist[max(abs(r(p) - br), max(abs(g(p) - bgc), abs(b(p) - bb)))]++
        var acc = 0; var dmax = 255
        for (d in 255 downTo 0) { acc += hist[d]; if (acc >= px.size * 0.03f) { dmax = d; break } }
        val far = (dmax * 0.8f).coerceIn(NEAR + 20f, 150f)

        val out = IntArray(w * h)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        var opaque = 0
        for (i in px.indices) {
            val p = px[i]
            val d = max(abs(r(p) - br), max(abs(g(p) - bgc), abs(b(p) - bb))).toFloat()
            val a = ((d - NEAR) / (far - NEAR)).coerceIn(0f, 1f) * (((p ushr 24) and 0xFF) / 255f)
            if (a <= 0.02f) continue
            // Recover the true foreground colour of semi-transparent edges: c = a*fg + (1-a)*bg
            var fr = ((r(p) - br * (1 - a)) / a).toInt().coerceIn(0, 255)
            var fg = ((g(p) - bgc * (1 - a)) / a).toInt().coerceIn(0, 255)
            var fb = ((b(p) - bb * (1 - a)) / a).toInt().coerceIn(0, 255)
            val lum = 0.3f * fr + 0.59f * fg + 0.11f * fb
            val sat = max(fr, max(fg, fb)) - min(fr, min(fg, fb))
            if (lum < 120 && sat < 50) { fr = 245; fg = 245; fb = 247 }
            out[i] = ((a * 255).toInt() shl 24) or (fr shl 16) or (fg shl 8) or fb
            if (a > 0.3f) {
                opaque++
                val x = i % w; val y = i / w
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        return Result(crop(out, w, h, minX, minY, maxX, maxY, opaque), bg)
    }

    /**
     * Banners with a gradient background: treat whitish pixels brighter than the lightest edge
     * tone as the logo. Most TV logos are white on a dark/coloured background.
     */
    private fun extractBright(px: IntArray, w: Int, h: Int): Result {
        val ringMins = ArrayList<Int>()
        for (y in 0 until h) for (x in 0 until w) {
            if (x < 3 || y < 3 || x >= w - 3 || y >= h - 3) {
                val p = px[y * w + x]; ringMins.add(min(r(p), min(g(p), b(p))))
            }
        }
        ringMins.sort()
        val p95 = ringMins[(ringMins.size * 0.95f).toInt().coerceAtMost(ringMins.size - 1)]
        if (p95 > 170) return Result(null, null)
        val lo = p95 + 30f
        val hi = minOf(lo + 70f, 255f)
        val out = IntArray(w * h)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1; var opaque = 0
        for (i in px.indices) {
            val p = px[i]
            val m = min(r(p), min(g(p), b(p))).toFloat()
            val a = ((m - lo) / (hi - lo)).coerceIn(0f, 1f)
            if (a <= 0.02f) continue
            out[i] = ((a * 255).toInt() shl 24) or (p and 0xFFFFFF)
            if (a > 0.3f) {
                opaque++
                val x = i % w; val y = i / w
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        return Result(crop(out, w, h, minX, minY, maxX, maxY, opaque), null)
    }

    private fun crop(out: IntArray, w: Int, h: Int, minX: Int, minY: Int, maxX: Int, maxY: Int, opaque: Int): Bitmap? {
        if (maxX < 0 || opaque < 40) return null
        val bw = maxX - minX + 1
        val bh = maxY - minY + 1
        if (bw * bh > w * h * 0.85f) return null
        val pad = 2
        val x0 = (minX - pad).coerceAtLeast(0); val y0 = (minY - pad).coerceAtLeast(0)
        val x1 = (maxX + pad).coerceAtMost(w - 1); val y1 = (maxY + pad).coerceAtMost(h - 1)
        val logo = Bitmap.createBitmap(x1 - x0 + 1, y1 - y0 + 1, Bitmap.Config.ARGB_8888)
        logo.setPixels(out, y0 * w + x0, w, 0, 0, logo.width, logo.height)
        return logo
    }

    /** True if the edge ring is bright on average (white/light grey background). */
    fun isLight(b: Bitmap): Boolean {
        val w = b.width; val h = b.height
        var sum = 0L; var n = 0
        val step = maxOf(1, w / 64)
        for (x in 0 until w step step) for (y in intArrayOf(1, h - 2)) {
            val p = b.getPixel(x, y); sum += (0.3f * r(p) + 0.59f * g(p) + 0.11f * b(p)).toLong(); n++
        }
        for (y in 0 until h step step) for (x in intArrayOf(1, w - 2)) {
            val p = b.getPixel(x, y); sum += (0.3f * r(p) + 0.59f * g(p) + 0.11f * b(p)).toLong(); n++
        }
        return n > 0 && sum / n > 160
    }

    /**
     * Smooth edges for the large title logo from a low-res mask (distance-field-like):
     * 2x upscale -> slightly blur alpha (rounds jaggies) -> 2x upscale again ->
     * re-sharpen alpha with smoothstep -> scale to 3x. Flat logos end up looking vector-like.
     */
    fun smoothUpscale(src: Bitmap): Bitmap {
        val s2 = Bitmap.createScaledBitmap(src, src.width * 2, src.height * 2, true).let {
            if (it.isMutable && it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, true)
        }
        blurAlpha(s2, 1)
        val s4 = Bitmap.createScaledBitmap(s2, s2.width * 2, s2.height * 2, true).let {
            if (it.isMutable && it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, true)
        }
        s2.recycle()
        val w = s4.width; val h = s4.height
        val px = IntArray(w * h)
        s4.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val p = px[i]
            val a = ((p ushr 24) and 0xFF) / 255f
            if (a <= 0f || a >= 1f) continue
            val t = ((a - 0.28f) / (0.72f - 0.28f)).coerceIn(0f, 1f)
            px[i] = ((t * t * (3f - 2f * t) * 255).toInt() shl 24) or (p and 0xFFFFFF)
        }
        s4.setPixels(px, 0, w, 0, 0, w, h)
        val out = Bitmap.createScaledBitmap(s4, src.width * 3, src.height * 3, true)
        if (out !== s4) s4.recycle()
        return out
    }

    /** Box blur on the alpha channel only (colours unchanged). */
    private fun blurAlpha(b: Bitmap, r: Int) {
        val w = b.width; val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val a = IntArray(w * h) { (px[it] ushr 24) and 0xFF }
        val t = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0; var n = 0
            for (k in -r..r) { val xx = x + k; if (xx in 0 until w) { sum += a[y * w + xx]; n++ } }
            t[y * w + x] = sum / n
        }
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0; var n = 0
            for (k in -r..r) { val yy = y + k; if (yy in 0 until h) { sum += t[yy * w + x]; n++ } }
            px[y * w + x] = ((sum / n) shl 24) or (px[y * w + x] and 0xFFFFFF)
        }
        b.setPixels(px, 0, w, 0, 0, w, h)
    }

    fun darken(c: Int, f: Float): Int {
        val rr = (r(c) * f).toInt(); val gg = (g(c) * f).toInt(); val bb = (b(c) * f).toInt()
        return (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
    }

    private fun r(p: Int) = (p shr 16) and 0xFF
    private fun g(p: Int) = (p shr 8) and 0xFF
    private fun b(p: Int) = p and 0xFF
}
