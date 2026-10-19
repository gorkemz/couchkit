// SPDX-License-Identifier: Apache-2.0
package io.couchkit.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import kotlin.math.asin
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Optional source of device battery levels shown in a small pill. The `standard` edition (what releases
 * ship) uses a no-op implementation, so the pill never appears. See BatterySources in each flavor.
 */
interface BatterySource {
    /** kind: "phone", "tablet", "watch", "earbuds", "laptop" or "other" (picks the glyph). */
    class Device(val name: String, val kind: String, val level: Float, val charging: Boolean)

    val refreshMs: Long
    /** Last good list (no network); empty when unknown or too old. */
    fun cached(ctx: Context): List<Device>
    fun isStale(ctx: Context): Boolean
    /** A new hand-off file is waiting to be imported: fetch right away. */
    fun hasPendingImport(ctx: Context): Boolean
    /** Network call, background thread: list on success, empty when signed out, null on a transient error. */
    fun fetch(ctx: Context): List<Device>?
}

/** One ring per device + device glyph + "82%" underneath. No background; hidden while empty. */
class BatteriesPill(ctx: Context, bd: Backdrop, private val semi: Typeface) : GlassPill(ctx, bd, 76f) {
    init {
        row.clipChildren = false   // keep the ring shadow
        visibility = GONE
    }

    fun set(list: List<BatterySource.Device>) {
        row.removeAllViews()
        // Large ring, small percentage below; each column is ring-wide (text centred) so everything aligns.
        val ring = Ui.pxi(RING)
        val ringH = ring + BatteryRing.topRoom(ring)
        for ((i, d) in list.take(5).withIndex()) {
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false
            }
            col.addView(BatteryRing(context, d), LinearLayout.LayoutParams(ring, ringH))
            col.addView(text(semi, 14f, 0xE6FFFFFF.toInt()).apply {
                text = "${(d.level * 100).roundToInt()}%"; fontFeatureSettings = "tnum"; gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(ring, -2).apply { topMargin = Ui.pxi(7f) })
            row.addView(col, LinearLayout.LayoutParams(ring, -2).apply { if (i > 0) leftMargin = Ui.pxi(22f) })
        }
        // No data -> not shown at all
        visibility = if (list.isEmpty()) GONE else VISIBLE
    }

    private companion object {
        const val RING = 64f
    }
}

/** Ring (progress arc) + device glyph in the middle; when charging, a bolt cuts the arc at the top. */
private class BatteryRing(ctx: Context, private val d: BatterySource.Device) : View(ctx) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x52FFFFFF }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        color = if (d.charging || d.level >= 0.2f) 0xFF30D158.toInt() else 0xFFFF453A.toInt()
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.3f; color = 0xFFFFFFFF.toInt()
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFFFFFFFF.toInt() }
    private val oval = RectF()
    private val strokeGlyph = Path()
    private val fillGlyph = Path()
    private val bolt = Path()

    init {
        buildGlyph(d.kind)
        // Same shadow as the other top widgets: no glass background, readable on light pictures too
        for (p in arrayOf(track, arc, line, fill)) p.setShadowLayer(Ui.px(8f), 0f, Ui.px(1.5f), 0x80000000.toInt())
    }

    /** Simple generic device outlines (rounded rectangles and circles) in a 24x24 unit box. */
    private fun buildGlyph(kind: String) {
        val cw = Path.Direction.CW
        when (kind) {
            "phone" -> strokeGlyph.addRoundRect(RectF(7f, 2f, 17f, 22f), 2.6f, 2.6f, cw)
            "tablet" -> strokeGlyph.addRoundRect(RectF(4f, 2.5f, 20f, 21.5f), 2.2f, 2.2f, cw)
            "watch" -> {
                fillGlyph.addRect(RectF(8.6f, 1f, 15.4f, 5f), cw)
                fillGlyph.addRect(RectF(8.6f, 19f, 15.4f, 23f), cw)
                strokeGlyph.addRoundRect(RectF(6.2f, 4.6f, 17.8f, 19.4f), 3.2f, 3.2f, cw)
            }
            "laptop" -> {
                strokeGlyph.addRoundRect(RectF(4.2f, 5f, 19.8f, 15.6f), 1.6f, 1.6f, cw)
                fillGlyph.addRoundRect(RectF(1.5f, 17.4f, 22.5f, 19.6f), 1.1f, 1.1f, cw)
            }
            "earbuds" -> {
                for (x in floatArrayOf(8f, 16f)) fillGlyph.addCircle(x, 12f, 3.4f, cw)
            }
            else -> strokeGlyph.addCircle(12f, 12f, 7f, cw)
        }
        // Bolt: 7x11 units, apex centred
        bolt.moveTo(4.6f, 0f); bolt.lineTo(0f, 6.4f); bolt.lineTo(3.2f, 6.4f); bolt.lineTo(2.4f, 11f)
        bolt.lineTo(7f, 4.6f); bolt.lineTo(3.8f, 4.6f); bolt.close()
    }

    override fun onDraw(canvas: Canvas) {
        // The ring's outer edge touches the view's left/right edges (alignment = view edge);
        // room for the bolt only at the top (topRoom).
        val w = width.toFloat()
        val stroke = w * STROKE
        val boltH = w * BOLT
        val r = w / 2f - stroke / 2f
        val cx = w / 2f
        val cy = height - w / 2f
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        oval.set(cx - r, cy - r, cx + r, cy + r)

        val gap = if (d.charging) Math.toDegrees(2.0 * asin(((boltH * 7f / 11f) / 2f + stroke * 0.7f) / r).toDouble()).toFloat() else 0f
        val start = -90f + gap / 2f
        val full = 360f - gap
        canvas.drawArc(oval, start, full, false, track)
        if (d.level > 0f) canvas.drawArc(oval, start, max(full * d.level, 2f), false, arc)

        if (d.charging) {
            val s = boltH / 11f
            canvas.save()
            canvas.translate(cx - 3.5f * s, cy - r - 5.5f * s)
            canvas.scale(s, s)
            canvas.drawPath(bolt, fill)
            canvas.restore()
        }

        val g = (r - stroke) * 1.25f
        canvas.save()
        canvas.translate(cx - g / 2f, cy - g / 2f)
        canvas.scale(g / 24f, g / 24f)
        canvas.drawPath(strokeGlyph, line)
        canvas.drawPath(fillGlyph, fill)
        canvas.restore()
    }

    companion object {
        const val STROKE = 0.075f
        const val BOLT = 0.26f
        /** The bolt sits centred on the ring's top line; room for the part above it. */
        fun topRoom(w: Int) = (w * (BOLT / 2f - STROKE / 2f)).toInt() + 1
    }
}
