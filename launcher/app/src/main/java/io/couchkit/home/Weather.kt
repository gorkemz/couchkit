// SPDX-License-Identifier: Apache-2.0
package io.couchkit.home

import android.content.Context
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.TimeZone

/**
 * Current weather from Open-Meteo (free, no key; data CC BY 4.0, attribution shown next to it).
 * Location comes from config.json "weather"; without it the weather is hidden. Refreshed every 30 min.
 */
object Weather {
    class Now(val temp: Int, val code: Int, val isDay: Boolean, val hi: Int, val lo: Int) {
        val text get() = describe(code)
    }

    const val REFRESH_MS = 30 * 60_000L

    private fun key(c: Config.Weather) = "${c.lat},${c.lon}"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("weather", 0)

    val enabled get() = Config.weather != null
    val label get() = Config.weather?.label.orEmpty()

    /** Last good response for the configured location (shown immediately on start). */
    fun cached(ctx: Context): Now? {
        val c = Config.weather ?: return null
        val p = prefs(ctx)
        if (p.getString("key", null) != key(c)) return null
        return p.getString("json", null)?.let(::parse)
    }

    fun isStale(ctx: Context): Boolean {
        val c = Config.weather ?: return false
        val p = prefs(ctx)
        return p.getString("key", null) != key(c) || System.currentTimeMillis() - p.getLong("at", 0) > REFRESH_MS
    }

    /** Background thread only. */
    fun fetch(ctx: Context): Now? {
        val c = Config.weather ?: return null
        val tz = URLEncoder.encode(TimeZone.getDefault().id, "UTF-8")
        val url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f", c.lat, c.lon) +
            "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min" +
            "&forecast_days=1&timezone=$tz"
        val b = Net.text(url) ?: return null
        return parse(b)?.also {
            prefs(ctx).edit().putString("json", b).putString("key", key(c)).putLong("at", System.currentTimeMillis()).apply()
        }
    }

    private fun parse(s: String): Now? = try {
        val j = JSONObject(s)
        val cur = j.getJSONObject("current")
        val d = j.getJSONObject("daily")
        Now(
            Math.round(cur.getDouble("temperature_2m")).toInt(),
            cur.getInt("weather_code"),
            cur.optInt("is_day", 1) == 1,
            Math.round(d.getJSONArray("temperature_2m_max").getDouble(0)).toInt(),
            Math.round(d.getJSONArray("temperature_2m_min").getDouble(0)).toInt(),
        )
    } catch (e: Exception) {
        null
    }

    /** WMO code -> Meteocons icon (MIT, assets/licenses/Meteocons-MIT.txt). */
    fun icon(code: Int, day: Boolean) = when (code) {
        0 -> if (day) R.drawable.wx_clear_day else R.drawable.wx_clear_night
        1, 2 -> if (day) R.drawable.wx_partly_cloudy_day else R.drawable.wx_partly_cloudy_night
        3 -> R.drawable.wx_overcast_day
        45, 48 -> R.drawable.wx_fog
        in 51..57 -> R.drawable.wx_drizzle
        66, 67 -> R.drawable.wx_sleet
        in 61..65, in 80..82 -> R.drawable.wx_rain
        in 71..77, 85, 86 -> R.drawable.wx_snow
        in 95..99 -> R.drawable.wx_thunderstorms_rain
        else -> R.drawable.wx_cloudy
    }

    /** WMO weather code -> short description. */
    fun describe(code: Int) = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        in 51..57 -> "Drizzle"
        in 61..67 -> "Rain"
        in 71..77 -> "Snow"
        in 80..82 -> "Showers"
        85, 86 -> "Snow showers"
        in 95..99 -> "Thunderstorm"
        else -> ""
    }
}
