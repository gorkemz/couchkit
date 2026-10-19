// SPDX-License-Identifier: Apache-2.0
package io.couchkit.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Abstract mesh-gradient background: large soft light fields in 2-3 dominant brand colours (screen
 * blending) over a dark base. Only the colours of the banner are used, not a blurred copy of it.
 * Rendered small (480x270) and scaled up; the gradients stay smooth without any blur.
 */
object Mesh {
    /** Indigo / violet / cyan: used for colourless logos (e.g. plain white text). */
    val DEFAULT_HUES = floatArrayOf(232f, 272f, 192f)

    /** True if palette() found no brand colour and returned the defaults. */
    fun isDefault(h: FloatArray) = h === DEFAULT_HUES

    fun palette(src: Bitmap, bg: Int?): FloatArray {
        val bins = FloatArray(36)
        val hueSum = FloatArray(36)
        val hsv = FloatArray(3)
        val stepX = maxOf(1, src.width / 80)
        val stepY = maxOf(1, src.height / 45)
        for (y in 0 until src.height step stepY) for (x in 0 until src.width step stepX) {
            val p = src.getPixel(x, y)
            val a = (p ushr 24) / 255f
            if (a < 0.3f) continue
            Color.colorToHSV(p, hsv)
            if (hsv[1] < 0.28f || hsv[2] < 0.22f) continue
            if (bg != null && near(p, bg)) continue
            val i = (hsv[0] / 10f).toInt().coerceIn(0, 35)
            val wgt = hsv[1] * hsv[2] * a
            bins[i] += wgt
            hueSum[i] += hsv[0] * wgt
        }
        val hues = ArrayList<Float>()
        // A saturated banner background is the brand colour itself.
        if (bg != null) {
            Color.colorToHSV(bg, hsv)
            if (hsv[1] > 0.35f && hsv[2] > 0.25f) hues.add(hsv[0])
        }
        val order = bins.indices.sortedByDescending { bins[it] }
        val total = bins.sum()
        for (i in order) {
            if (hues.size >= 3 || bins[i] <= 0f || bins[i] < total * 0.06f) break
            val h = hueSum[i] / bins[i]
            if (hues.none { hueDist(it, h) < 28f }) hues.add(h)
        }
        when (hues.size) {
            0 -> return DEFAULT_HUES
            1 -> { hues.add((hues[0] + 34f) % 360f); hues.add((hues[0] + 326f) % 360f) }
            2 -> hues.add((hues[0] + 300f + 30f * if (hueDist(hues[0], hues[1]) > 90f) 0 else 1) % 360f)
        }
        return hues.toFloatArray()
    }

    fun render(hues: FloatArray, key: String, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        renderInto(out, hues, Random(key.hashCode()), 0f)
        return out
    }

    /**
     * Draws the mesh into [out]. [phase] (radians, grows over time) slowly moves the light fields;
     * used by the animated screensaver fallback. Thread-safe (no shared state).
     */
    fun renderInto(out: Bitmap, hues: FloatArray, rnd: Random, phase: Float) {
        val w = out.width; val h = out.height
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        }
        val c = Canvas(out)
        fun j(s: Float) = (rnd.nextFloat() - 0.5f) * 2f * s
        c.drawColor(Color.HSVToColor(floatArrayOf(hues[0], 0.75f, 0.075f)))
        val m = maxOf(w, h).toFloat()
        // (x, y, radius, hue index, saturation, value, alpha)
        val blobs = arrayOf(
            floatArrayOf(0.74f + j(0.08f) + 0.10f * sin(phase), 0.30f + j(0.08f) + 0.08f * cos(phase * 0.8f), 0.62f, 0f, 0.80f, 0.62f, 0.95f),
            floatArrayOf(0.16f + j(0.06f) + 0.08f * cos(phase * 0.7f), 0.10f + j(0.06f) + 0.10f * sin(phase * 0.9f), 0.48f, 1f, 0.75f, 0.48f, 0.80f),
            floatArrayOf(0.46f + j(0.10f) + 0.12f * sin(phase * 0.6f + 1f), 0.92f + j(0.04f) - 0.06f * cos(phase), 0.55f, 2f, 0.80f, 0.42f, 0.70f),
            floatArrayOf(0.94f + j(0.04f), 0.02f, 0.30f, 0f, 0.45f, 0.85f, 0.55f), // top-right highlight
        )
        for (b in blobs) {
            val col = Color.HSVToColor((b[6] * 255).toInt(), floatArrayOf(hues[b[3].toInt()], b[4], b[5]))
            val mid = (col and 0x00FFFFFF) or (((b[6] * 255 * 0.42f).toInt()) shl 24)
            paint.shader = RadialGradient(b[0] * w, b[1] * h, b[2] * m,
                intArrayOf(col, mid, col and 0x00FFFFFF), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        }
    }

    /** Fine film grain: hides banding in large gradients. */
    fun grain(size: Int = 128): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val px = ByteArray(size * size)
        val r = Random(7)
        for (i in px.indices) px[i] = r.nextInt(256).toByte()
        b.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(px))
        return b
    }

    private fun near(a: Int, b: Int): Boolean =
        Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b)) + Math.abs(Color.blue(a) - Color.blue(b)) < 60

    private fun hueDist(a: Float, b: Float): Float {
        val d = Math.abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }
}
