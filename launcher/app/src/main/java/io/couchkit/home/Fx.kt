// SPDX-License-Identifier: Apache-2.0
package io.couchkit.home

import android.content.Context
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exchange rates from Frankfurter (ECB reference rates, no key). Pairs come from config.json "fx";
 * without it the pill is hidden. One request returns the last few business days, so the arrow
 * compares the latest rate with the previous one. Refreshed every hour (ECB publishes once a day).
 */
object Fx {
    class Rate(val label: String, val value: Double, val change: Double)

    const val REFRESH_MS = 60 * 60_000L

    private fun key(c: Config.Fx) = c.base + ":" + c.symbols.joinToString(",")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("fx", 0)

    val enabled get() = Config.fx != null

    fun cached(ctx: Context): List<Rate>? {
        val c = Config.fx ?: return null
        val p = prefs(ctx)
        if (p.getString("key", null) != key(c)) return null
        return p.getString("json", null)?.let { parse(it, c) }
    }

    fun isStale(ctx: Context): Boolean {
        val c = Config.fx ?: return false
        val p = prefs(ctx)
        return p.getString("key", null) != key(c) || System.currentTimeMillis() - p.getLong("at", 0) > REFRESH_MS
    }

    fun fetch(ctx: Context): List<Rate>? {
        val c = Config.fx ?: return null
        // Last ~10 calendar days: always covers at least two business days, even around holidays.
        val from = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis() - 10 * 86_400_000L))
        val url = "https://api.frankfurter.app/$from..?from=${enc(c.base)}&to=${enc(c.symbols.joinToString(","))}"
        val b = Net.text(url) ?: return null
        return parse(b, c)?.also {
            prefs(ctx).edit().putString("json", b).putString("key", key(c)).putLong("at", System.currentTimeMillis()).apply()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun parse(s: String, c: Config.Fx): List<Rate>? = try {
        val rates = JSONObject(s).getJSONObject("rates")
        val days = rates.keys().asSequence().sorted().toList()
        val last = rates.getJSONObject(days.last())
        val prev = days.getOrNull(days.size - 2)?.let { rates.getJSONObject(it) }
        c.symbols.filter { last.has(it) }.map { sym ->
            val v = last.getDouble(sym)
            Rate("${c.base}/$sym", v, prev?.optDouble(sym)?.takeIf { !it.isNaN() }?.let { v - it } ?: 0.0)
        }.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }
}
