// SPDX-License-Identifier: Apache-2.0
package io.couchkit.home

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * User configuration: `<external files dir>/config.json`, written by the installer with `adb push`.
 * Every section is optional; a missing section (or empty API key) turns that feature off.
 * The file is re-read whenever its modification time changes ([refresh] is cheap: one stat call).
 */
object Config {
    class Weather(val lat: Double, val lon: Double, val label: String)
    class Fx(val base: String, val symbols: List<String>)
    class Tmdb(val apiKey: String, val region: String, val language: String)
    class Football(val apiKey: String, val leagues: List<Int>, val season: Int, val teams: List<Int>)

    const val FILE = "config.json"

    @Volatile var weather: Weather? = null; private set
    @Volatile var fx: Fx? = null; private set
    @Volatile var tmdb: Tmdb? = null; private set
    @Volatile var football: Football? = null; private set
    @Volatile var screensaverOverlay = false; private set
    /** Incremented on every change, so caches keyed on config can tell they are outdated. */
    @Volatile var version = 0; private set

    private var stamp = Long.MIN_VALUE

    /** Re-reads the file if it changed. Returns true when the configuration changed. */
    @Synchronized
    fun refresh(ctx: Context): Boolean {
        val f = ctx.getExternalFilesDir(null)?.let { File(it, FILE) }
        val now = if (f != null && f.isFile) f.lastModified() xor f.length() else -1L
        if (now == stamp) return false
        stamp = now
        val j = try {
            if (now == -1L) JSONObject() else JSONObject(f!!.readText())
        } catch (e: Exception) {
            Log.w(TAG, "config.json is not valid JSON: ${e.message}"); JSONObject()
        }
        weather = j.optJSONObject("weather")?.let { w ->
            if (!w.has("lat") || !w.has("lon")) null
            else Weather(w.optDouble("lat"), w.optDouble("lon"), w.optString("label"))
        }
        fx = j.optJSONObject("fx")?.let { x ->
            val symbols = strings(x.optJSONArray("symbols")).map { it.uppercase() }
            if (symbols.isEmpty()) null else Fx(x.optString("base", "USD").uppercase(), symbols)
        }
        tmdb = j.optJSONObject("tmdb")?.let { t ->
            val key = t.optString("apiKey").trim()
            if (key.isEmpty()) null
            else Tmdb(key, t.optString("region", "US").uppercase(), t.optString("language", "en-US"))
        }
        football = j.optJSONObject("football")?.let { b ->
            val key = b.optString("apiKey").trim()
            val leagues = ints(b.optJSONArray("leagues"))
            if (key.isEmpty() || leagues.isEmpty()) null
            else Football(key, leagues, b.optInt("season", java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)),
                ints(b.optJSONArray("teams")))
        }
        screensaverOverlay = j.optJSONObject("screensaver")?.optBoolean("overlay", false) ?: false
        version++
        Log.i(TAG, "config: weather=${weather != null} fx=${fx != null} tmdb=${tmdb != null} " +
            "football=${football != null} overlay=$screensaverOverlay")
        return true
    }

    private fun strings(a: JSONArray?) = if (a == null) emptyList() else
        (0 until a.length()).mapNotNull { a.optString(it).trim().takeIf { s -> s.isNotEmpty() } }

    private fun ints(a: JSONArray?) = if (a == null) emptyList() else
        (0 until a.length()).mapNotNull { i -> a.opt(i)?.toString()?.trim()?.toIntOrNull() }
}
