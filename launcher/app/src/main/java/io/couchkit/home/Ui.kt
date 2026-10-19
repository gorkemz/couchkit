// SPDX-License-Identifier: Apache-2.0
package io.couchkit.home

import android.graphics.Bitmap

/**
 * All sizes are written in 1920x1080 design pixels and scaled to the screen width.
 * Typical TV: 1920x1080 @ 320 dpi.
 */
object Ui {
    var u = 1f
    var screenW = 1920
    var screenH = 1080

    fun px(v: Float) = v * u
    fun pxi(v: Float) = (v * u + 0.5f).toInt()

    const val COLS = 5
    const val TILE_W = 316f
    const val TILE_H = 190f          // 5:3 tile ratio
    const val GAP = 36f
    const val ROW_PITCH = 300f        // tile + label + gap
    const val RADIUS = 26f
    const val FOCUS_SCALE = 1.12f

    const val DOCK_TOP = 742f
    const val DOCK_H = 296f
    const val DOCK_SIDE = 60f
    const val DOCK_RADIUS = 58f
    const val ROW0_TOP = DOCK_TOP + 34f
    const val SIDE = (1920f - (COLS * TILE_W + (COLS - 1) * GAP)) / 2f

    /** Top edge of the focused row on screen (once in the grid). */
    const val GRID_ANCHOR = 420f
    /** Extra gap between dock and grid so the second row does not peek in on the featured screen. */
    const val GRID_GAP = 60f
    const val DIM_DISTANCE = 520f

    fun tileX(index: Int) = px(SIDE + (index % COLS) * (TILE_W + GAP))
    fun rowTop(row: Int) = px(ROW0_TOP + row * ROW_PITCH + if (row > 0) GRID_GAP else 0f)
    fun scrollFor(row: Int) = if (row == 0) 0f else rowTop(row) - px(GRID_ANCHOR)

    /** Bake size of featured backgrounds (filtered up to the screen; logo/text stay full resolution). */
    const val BAKE_W = 1280
    const val BAKE_H = 720

    /** Small copy used for background blur (1/10 of the screen). */
    const val BLUR_W = 192
    const val BLUR_H = 108
}

/** Three-pass box blur ~ gaussian. About 1 ms on small bitmaps like 192x108. */
object Blur {
    fun blurInPlace(bmp: Bitmap, radius: Int, passes: Int = 3) {
        val w = bmp.width
        val h = bmp.height
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        bmp.getPixels(a, 0, w, 0, 0, w, h)
        repeat(passes) {
            boxH(a, b, w, h, radius)
            boxV(b, a, w, h, radius)
        }
        bmp.setPixels(a, 0, w, 0, 0, w, h)
    }

    private fun boxH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val p = src[row + i.coerceIn(0, w - 1)]
                sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
            }
            for (x in 0 until w) {
                dst[row + x] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val pOut = src[row + (x - r).coerceIn(0, w - 1)]
                val pIn = src[row + (x + r + 1).coerceIn(0, w - 1)]
                sr += ((pIn shr 16) and 0xFF) - ((pOut shr 16) and 0xFF)
                sg += ((pIn shr 8) and 0xFF) - ((pOut shr 8) and 0xFF)
                sb += (pIn and 0xFF) - (pOut and 0xFF)
            }
        }
    }

    private fun boxV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val p = src[i.coerceIn(0, h - 1) * w + x]
                sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val pOut = src[(y - r).coerceIn(0, h - 1) * w + x]
                val pIn = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
                sr += ((pIn shr 16) and 0xFF) - ((pOut shr 16) and 0xFF)
                sg += ((pIn shr 8) and 0xFF) - ((pOut shr 8) and 0xFF)
                sb += (pIn and 0xFF) - (pOut and 0xFF)
            }
        }
    }
}
