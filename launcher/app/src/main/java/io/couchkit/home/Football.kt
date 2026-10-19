// SPDX-License-Identifier: Apache-2.0
package io.couchkit.home

import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Followed football matches from API-Football v3 (https://www.api-football.com), using the user's own
 * key from config.json "football". Without a key nothing is polled and the match card / goal pill stay off.
 *
 * Rate policy (free plan = 100 requests/day, counted per UTC day):
 *  - today's fixtures: one request per configured league, refreshed every 6 h (and on a new day);
 *  - live scores: one `fixtures?live=` request for all leagues, only while a followed match is in play
 *    (every 90 s, every 5 min at half time, slower when the daily budget runs low);
 *  - a local counter stops all requests at [DAILY_LIMIT].
 * The home screen, the screensaver and the goal watcher share this single cache and budget.
 * A match is shown from 15 min before kick-off, while it is live and for 10 min after the final whistle.
 */
object Football {
    class Team(val id: Int, val name: String, val logo: String, val score: String)

    class Match(
        val id: String, val league: String, val leagueId: Int, val start: Long,
        /** "pre", "in", "post" or "off" (postponed / cancelled: never shown, never polled). */
        val state: String,
        /** Match minute as text, e.g. "67'" or "90'+3'". */
        val clock: String,
        /** Short status code from the API, e.g. "1H", "HT", "FT". */
        val detail: String,
        val home: Team, val away: Team, val priority: Int,
        /** Last goal, e.g. "A. Player · 71'" (+ "(P)" penalty, "(OG)" own goal). */
        val lastScorer: String? = null,
    ) {
        val live get() = state == "in"
        val finished get() = state == "post"
    }

    private const val BASE = "https://v3.football.api-sports.io"
    const val DAILY_LIMIT = 90
    const val LEAD_MS = 15 * 60_000L
    private const val AFTER_MS = 10 * 60_000L
    private const val FIXTURES_TTL = 6 * 3_600_000L
    private const val RETRY_MS = 30 * 60_000L
    private const val LIVE_MS = 90_000L
    private const val HALF_TIME_MS = 5 * 60_000L

    private val PRE = setOf("TBD", "NS")
    private val IN = setOf("1H", "HT", "2H", "ET", "BT", "P", "SUSP", "INT", "LIVE")
    private val POST = setOf("FT", "AET", "PEN")

    val enabled get() = Config.football != null

    fun displayName(t: Team) = t.name

    private var list: List<Match> = emptyList()
    private var loadedKey: String? = null
    private var fixturesAt = 0L
    private var fixturesDay = ""
    private var liveAt = 0L
    private val missed = HashMap<String, Int>()

    private fun cfgKey(c: Config.Football) = "${c.leagues}|${c.season}|${c.teams}"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("football", 0)
    private fun localDay(t: Long = System.currentTimeMillis()) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(t))
    private fun utcDay() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    /**
     * Current matches. Hits the network only when the rate policy allows; otherwise returns the cache.
     * Background thread only.
     */
    @Synchronized
    fun update(ctx: Context): List<Match> {
        val c = Config.football ?: run { list = emptyList(); loadedKey = null; return list }
        val key = cfgKey(c)
        val p = prefs(ctx)
        if (loadedKey != key) {
            // Restore the last fixture list (survives process restarts, saves requests)
            loadedKey = key
            list = emptyList(); fixturesAt = 0; fixturesDay = ""; liveAt = 0; missed.clear()
            if (p.getString("listKey", null) == key) {
                list = try { fromJson(JSONArray(p.getString("list", "[]"))) } catch (e: Exception) { emptyList() }
                fixturesAt = p.getLong("listAt", 0); fixturesDay = p.getString("listDay", "").orEmpty()
            }
        }
        val now = System.currentTimeMillis()
        val today = localDay(now)
        if (fixturesDay != today || now - fixturesAt > FIXTURES_TTL) {
            val fresh = fetchFixtures(ctx, c, today)
            if (fresh != null) {
                list = fresh; fixturesDay = today; fixturesAt = now
                p.edit().putString("list", toJson(list).toString()).putString("listKey", key)
                    .putString("listDay", today).putLong("listAt", now).apply()
            } else {
                fixturesAt = now - FIXTURES_TTL + RETRY_MS   // try again in 30 min
                if (fixturesDay != today) fixturesDay = today
            }
        } else if (needsLivePoll(list, now) && now - liveAt >= liveInterval(ctx)) {
            liveAt = now
            fetchLive(ctx, c)?.let { live -> list = merge(list, live, now) }
        }
        return list
    }

    /** How soon a consumer should call [update] again (cheap when nothing needs fetching). */
    fun pollDelay(matches: List<Match>, now: Long = System.currentTimeMillis()) =
        if (needsLivePoll(matches, now)) 20_000L else 5 * 60_000L

    private fun needsLivePoll(matches: List<Match>, now: Long) = matches.any {
        it.priority > 0 && (it.live || (it.state == "pre" && now >= it.start - 60_000L && now - it.start < 3 * 3_600_000L))
    }

    private fun liveInterval(ctx: Context): Long {
        val left = DAILY_LIMIT - used(ctx)
        val base = if (list.filter { it.live && it.priority > 0 }.let { l -> l.isNotEmpty() && l.all { it.detail == "HT" || it.detail == "BT" } })
            HALF_TIME_MS else LIVE_MS
        return when {
            left < 10 -> maxOf(base, 10 * 60_000L)
            left < 30 -> maxOf(base, 3 * 60_000L)
            else -> base
        }
    }

    /** The single match to show now: highest priority first, a live one before an upcoming one; finished last. */
    fun pick(matches: List<Match>, now: Long = System.currentTimeMillis()): Match? = matches.filter {
        it.priority > 0 && (it.live || (it.state == "pre" && it.start - now in -60 * 60_000L..LEAD_MS) ||
            (it.finished && now - it.start < 2 * 60 * 60_000L + AFTER_MS))
    }.sortedWith(compareByDescending<Match> { if (it.finished) 0 else 1 }
        .thenByDescending { it.priority }
        .thenByDescending { if (it.live) 1 else 0 })
        .firstOrNull()

    // ---- requests ------------------------------------------------------------------------------

    private fun used(ctx: Context): Int {
        val p = prefs(ctx)
        return if (p.getString("quotaDay", null) == utcDay()) p.getInt("quotaUsed", 0) else 0
    }

    private fun request(ctx: Context, c: Config.Football, path: String): JSONArray? {
        val p = prefs(ctx)
        val day = utcDay()
        val used = used(ctx)
        if (used >= DAILY_LIMIT) { Log.i(TAG, "football: daily request budget used up"); return null }
        p.edit().putString("quotaDay", day).putInt("quotaUsed", used + 1).apply()
        val r = Net.get(BASE + path, mapOf("x-apisports-key" to c.apiKey))
        // Trust the server's own counter when it reports fewer remaining requests than ours
        r.headers["x-ratelimit-requests-remaining"]?.toIntOrNull()?.let { remaining ->
            if (remaining <= 100 - DAILY_LIMIT) p.edit().putInt("quotaUsed", DAILY_LIMIT).apply()
        }
        val body = r.text ?: return null
        return try {
            val j = JSONObject(body)
            val errors = j.opt("errors")
            if (errors is JSONObject && errors.length() > 0) {
                Log.w(TAG, "football: API error ${errors.keys().asSequence().toList()}")
                if (errors.has("requests")) p.edit().putInt("quotaUsed", DAILY_LIMIT).apply()
                null
            } else j.optJSONArray("response") ?: JSONArray()
        } catch (e: Exception) {
            Log.w(TAG, "football: ${e.message}"); null
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun fetchFixtures(ctx: Context, c: Config.Football, day: String): List<Match>? {
        val out = ArrayList<Match>()
        val tz = enc(TimeZone.getDefault().id)
        var ok = false
        for (league in c.leagues) {
            val a = request(ctx, c, "/fixtures?date=$day&league=$league&season=${c.season}&timezone=$tz") ?: continue
            ok = true
            for (i in 0 until a.length()) parse(a.optJSONObject(i), c)?.let(out::add)
        }
        return if (ok) out else null
    }

    private fun fetchLive(ctx: Context, c: Config.Football): List<Match>? {
        val a = request(ctx, c, "/fixtures?live=${c.leagues.joinToString("-")}") ?: return null
        return (0 until a.length()).mapNotNull { parse(a.optJSONObject(it), c) }
    }

    /**
     * Live results replace their fixtures. A match we had as live that is no longer in the live feed has
     * ended (kept as finished with the last score). A kicked-off match that never shows up as live
     * (postponed) is dropped from polling after a few tries.
     */
    private fun merge(old: List<Match>, live: List<Match>, now: Long): List<Match> {
        val byId = live.associateBy { it.id }
        val out = ArrayList<Match>()
        for (m in old) {
            val l = byId[m.id]
            out.add(when {
                l != null -> { missed.remove(m.id); l }
                m.live -> copy(m, "post", "FT")
                m.state == "pre" && now > m.start + 5 * 60_000L -> {
                    val n = (missed[m.id] ?: 0) + 1
                    missed[m.id] = n
                    if (n >= 3) copy(m, "off", m.detail) else m
                }
                else -> m
            })
        }
        for (l in live) if (old.none { it.id == l.id }) out.add(l)
        return out
    }

    private fun copy(m: Match, state: String, detail: String) =
        Match(m.id, m.league, m.leagueId, m.start, state, m.clock, detail, m.home, m.away, m.priority, m.lastScorer)

    private fun priority(c: Config.Football, leagueId: Int, home: Int, away: Int): Int {
        if (c.teams.isNotEmpty()) {
            val i = listOf(c.teams.indexOf(home), c.teams.indexOf(away)).filter { it >= 0 }
            if (i.isEmpty()) return 0
            return 100 - i.min() + if (i.size == 2) 5 else 0
        }
        val li = c.leagues.indexOf(leagueId)
        return if (li < 0) 0 else 50 - li
    }

    private fun parse(o: JSONObject?, c: Config.Football): Match? {
        if (o == null) return null
        return try {
            val f = o.getJSONObject("fixture")
            val st = f.getJSONObject("status")
            val short = st.optString("short")
            val state = when (short) { in PRE -> "pre"; in IN -> "in"; in POST -> "post"; else -> "off" }
            val lg = o.getJSONObject("league")
            val teams = o.getJSONObject("teams")
            val goals = o.optJSONObject("goals")
            fun team(side: String): Team {
                val t = teams.getJSONObject(side)
                val g = goals?.opt(side)?.takeIf { it != JSONObject.NULL }?.toString().orEmpty()
                return Team(t.optInt("id"), t.optString("name"), t.optString("logo"), g)
            }
            val home = team("home"); val away = team("away")
            val elapsed = st.optInt("elapsed", -1)
            val extra = st.optInt("extra", 0)
            val clock = if (elapsed < 0) "" else "$elapsed'" + if (extra > 0) "+$extra'" else ""
            Match(f.getInt("id").toString(), lg.optString("name"), lg.optInt("id"), f.optLong("timestamp") * 1000,
                state, clock, short, home, away, priority(c, lg.optInt("id"), home.id, away.id),
                lastScorer(o.optJSONArray("events"), home.id))
        } catch (e: Exception) {
            Log.w(TAG, "football parse: ${e.message}"); null
        }
    }

    private fun lastScorer(events: JSONArray?, homeId: Int): String? {
        if (events == null) return null
        for (i in events.length() - 1 downTo 0) {
            val e = events.optJSONObject(i) ?: continue
            if (e.optString("type") != "Goal") continue
            val detail = e.optString("detail")
            if (detail.contains("Missed", true)) continue
            val who = e.optJSONObject("player")?.optString("name").orEmpty()
            val t = e.optJSONObject("time")
            val min = t?.optInt("elapsed", -1) ?: -1
            val ex = t?.optInt("extra", 0) ?: 0
            val at = if (min < 0) null else "$min'" + if (ex > 0) "+$ex'" else ""
            val tag = when { detail.contains("Own", true) -> "  (OG)"; detail.contains("Penalty", true) -> "  (P)"; else -> "" }
            return listOfNotNull(who.ifEmpty { null }, at).joinToString("  ·  ").plus(tag).ifEmpty { null }
        }
        return null
    }

    private fun toJson(l: List<Match>) = JSONArray().apply {
        fun t(x: Team) = JSONObject().put("id", x.id).put("name", x.name).put("logo", x.logo).put("score", x.score)
        for (m in l) put(JSONObject().put("id", m.id).put("league", m.league).put("leagueId", m.leagueId)
            .put("start", m.start).put("state", m.state).put("clock", m.clock).put("detail", m.detail)
            .put("home", t(m.home)).put("away", t(m.away)).put("priority", m.priority).put("scorer", m.lastScorer ?: ""))
    }

    private fun fromJson(a: JSONArray) = (0 until a.length()).map {
        val o = a.getJSONObject(it)
        fun t(x: JSONObject) = Team(x.optInt("id"), x.optString("name"), x.optString("logo"), x.optString("score"))
        Match(o.getString("id"), o.optString("league"), o.optInt("leagueId"), o.optLong("start"), o.optString("state"),
            o.optString("clock"), o.optString("detail"), t(o.getJSONObject("home")), t(o.getJSONObject("away")),
            o.optInt("priority"), o.optString("scorer").ifEmpty { null })
    }

    // ---- debug preview ---------------------------------------------------------------------------

    /** Debug builds only: preview files (`match-demo`, `goal-demo`) in the external files dir are honoured. */
    fun isDebug(ctx: Context) = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Neutral preview match for testing the card and goal animation: minute 67 onwards, a goal after 30 s. */
    fun preview(startedAt: Long, now: Long = System.currentTimeMillis()): Match {
        val el = (now - startedAt) / 1000
        val minute = 67 + (el / 60).toInt()
        val a = if (el >= 30) "2" else "1"
        return Match("preview", "Preview League", 0, startedAt - 67 * 60_000L, "in", "$minute'", "2H",
            Team(1, "Team A", "", a), Team(2, "Team B", "", "1"), 1,
            if (el >= 30) "Player One  ·  $minute'" else "Player Two  ·  52'")
    }

    // ---- crests ----------------------------------------------------------------------------------

    /** Team logo from the URL the API returned (not bundled), cached on disk, decoded to ~96 px. */
    fun crest(ctx: Context, url: String): Bitmap? {
        if (!url.startsWith("https://")) return null
        val f = File(File(ctx.cacheDir, "crests").apply { mkdirs() }, url.hashCode().toUInt().toString(16) + ".img")
        if (!f.isFile) {
            val bytes = Net.bytes(url) ?: return null
            File(f.path + ".part").apply { writeBytes(bytes); renameTo(f) }
        }
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, b)
        var s = 1
        while (b.outWidth / (s * 2) >= 96) s *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    }
}

/**
 * Score band at the top centre (no background; text with shadows over the image):
 *   [crest] Home team      LEAGUE       Away team [crest]
 *                          2  :  1
 *                      (● LIVE · 67:12)
 * On a goal the band and score bounce and the status pill turns gold with "GOAL!". The live dot pulses.
 */
class MatchCard(ctx: Context, bd: Backdrop, semi: Typeface, regular: Typeface) : FrameLayout(ctx) {
    private val homeCrest = ImageView(ctx)
    private val awayCrest = ImageView(ctx)
    private val homeName = label(semi, 22f, 0xFFFFFFFF.toInt())
    private val awayName = label(semi, 22f, 0xFFFFFFFF.toInt())
    private val league = label(semi, 12f, 0xB3FFFFFF.toInt()).apply { letterSpacing = 0.12f; setPadding(Ui.pxi(6f), 0, Ui.pxi(6f), 0) }
    private val score = label(semi, 40f, 0xFFFFFFFF.toInt()).apply { fontFeatureSettings = "tnum"; letterSpacing = 0.02f }
    private val pillBg = android.graphics.drawable.GradientDrawable().apply { cornerRadius = Ui.px(13f) }
    private val pill = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = pillBg; setPadding(Ui.pxi(11f), Ui.pxi(4f), Ui.pxi(13f), Ui.pxi(5f))
    }
    private val dot = View(ctx).apply {
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFFFF453A.toInt())
        }
    }
    private val status = label(semi, 13f, 0xFFFFFFFF.toInt()).apply { letterSpacing = 0.06f; fontFeatureSettings = "tnum" }
    private var pulse: ValueAnimator? = null
    var shownId: String? = null
        private set
    private var lastScore: String? = null
    private var celebrating = false

    // Running clock (mm:ss): the API only gives the minute, the seconds tick locally. If a new poll's
    // minute differs from the local clock by more than a minute, it re-syncs to the start of that minute.
    private var clockSec = -1L      // match second at sync time
    private var clockAt = 0L        // sync time
    private var halfTime = false
    private val ticker: Runnable = object : Runnable {
        override fun run() {
            if (clockSec >= 0 && !celebrating && !halfTime) status.text = "LIVE  ·  ${clockText()}"
            if (clockSec >= 0) postDelayed(this, 1000)
        }
    }

    private fun nowSec() = clockSec + (System.currentTimeMillis() - clockAt) / 1000

    private fun clockText(): String {
        val t = nowSec()
        return String.format(Locale.US, "%02d:%02d", t / 60, t % 60)
    }

    /** "67'" or "90'+3'" -> match minute; re-sync the local clock if needed. */
    private fun setClock(clock: String) {
        val m = Regex("""(\d+)'?(?:\s*\+\s*(\d+))?""").find(clock) ?: run { clockSec = -1; return }
        val minute = m.groupValues[1].toInt() + (m.groupValues[2].toIntOrNull() ?: 0)
        val wasRunning = clockSec >= 0
        val local = nowSec() / 60
        // Local clock behind, or ahead by more than the feed's typical delay (2+ min): re-sync
        if (!wasRunning || local < minute || local - minute >= 2) {
            clockSec = minute * 60L
            clockAt = System.currentTimeMillis()
        }
        if (!wasRunning) post(ticker)
    }

    private fun label(tf: Typeface, size: Float, color: Int) = TextView(context).apply {
        typeface = tf; setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(size)); setTextColor(color)
        setShadowLayer(Ui.px(10f), 0f, Ui.px(1f), 0x55000000); maxLines = 1; includeFontPadding = false
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    init {
        clipChildren = false
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val c = Ui.pxi(48f)
        row.addView(homeCrest, LinearLayout.LayoutParams(c, c))
        row.addView(homeName, LinearLayout.LayoutParams(Ui.pxi(165f), -2).apply { leftMargin = Ui.pxi(14f) })
        homeName.gravity = Gravity.START
        val mid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        league.gravity = Gravity.CENTER
        mid.addView(league, LinearLayout.LayoutParams(-1, -2))
        score.gravity = Gravity.CENTER
        mid.addView(score, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.pxi(4f) })
        pill.addView(dot, LinearLayout.LayoutParams(Ui.pxi(8f), Ui.pxi(8f)).apply { rightMargin = Ui.pxi(7f) })
        pill.addView(status)
        mid.addView(pill, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.pxi(6f); gravity = Gravity.CENTER_HORIZONTAL })
        row.addView(mid, LinearLayout.LayoutParams(Ui.pxi(210f), -2))
        row.addView(awayName, LinearLayout.LayoutParams(Ui.pxi(165f), -2).apply { rightMargin = Ui.pxi(14f) })
        awayName.gravity = Gravity.END
        row.addView(awayCrest, LinearLayout.LayoutParams(c, c))
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            leftMargin = Ui.pxi(26f); rightMargin = Ui.pxi(26f)
        })
        alpha = 0f
        visibility = GONE
    }

    fun setCrests(home: Bitmap?, away: Bitmap?) {
        homeCrest.setImageBitmap(home); awayCrest.setImageBitmap(away)
    }

    fun show(m: Football.Match, now: Long = System.currentTimeMillis()) {
        val first = shownId != m.id
        shownId = m.id
        homeName.text = Football.displayName(m.home)
        awayName.text = Football.displayName(m.away)
        league.text = m.league.uppercase(Locale.getDefault())
        val sc = if (m.state == "pre") "–  :  –" else "${m.home.score}  :  ${m.away.score}"
        val goal = !first && lastScore != null && sc != lastScore && m.live
        lastScore = sc
        score.text = sc
        val tf = SimpleDateFormat("HH:mm", Locale.getDefault())
        if (!celebrating) setStatus(m, now, tf)
        if (visibility != VISIBLE) {
            visibility = VISIBLE
            translationY = -Ui.px(40f); scaleX = 0.92f; scaleY = 0.92f
            animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(700)
                .setInterpolator(OvershootInterpolator(1.2f)).start()
        }
        if (goal) celebrate(m, now, tf)
    }

    private fun setStatus(m: Football.Match, now: Long, tf: SimpleDateFormat) {
        when {
            m.live -> {
                halfTime = m.detail.equals("HT", true) || m.detail.equals("BT", true)
                if (!halfTime) setClock(m.clock)
                status.text = if (halfTime) "HALF TIME" else "LIVE  ·  ${clockText()}"
                pillBg.setColor(0x40FF3B30); pillBg.setStroke(Ui.pxi(1.5f), 0x80FF453A.toInt())
                dot.visibility = VISIBLE; startPulse()
            }
            m.finished -> {
                clockSec = -1
                status.text = "FULL TIME"
                pillBg.setColor(0x26FFFFFF); pillBg.setStroke(Ui.pxi(1.5f), 0x33FFFFFF)
                dot.visibility = GONE; stopPulse()
            }
            else -> {
                clockSec = -1
                val mins = ((m.start - now) / 60_000L).coerceAtLeast(0)
                status.text = if (mins > 0) "${tf.format(Date(m.start))}  ·  IN $mins MIN" else "STARTING"
                pillBg.setColor(0x26FFFFFF); pillBg.setStroke(Ui.pxi(1.5f), 0x33FFFFFF)
                dot.visibility = GONE; stopPulse()
            }
        }
        status.setTextColor(0xFFFFFFFF.toInt())
    }

    fun hide() {
        if (visibility != VISIBLE) return
        stopPulse(); clockSec = -1
        shownId = null; lastScore = null
        animate().alpha(0f).translationY(-Ui.px(40f)).setDuration(400).withEndAction { visibility = GONE }.start()
    }

    /** Goal: band and score bounce, the pill turns gold and says "GOAL!" (6 s). */
    private fun celebrate(m: Football.Match, now: Long, tf: SimpleDateFormat) {
        celebrating = true
        animate().scaleX(1.08f).scaleY(1.08f).setDuration(200).setInterpolator(DecelerateInterpolator())
            .withEndAction { animate().scaleX(1f).scaleY(1f).setDuration(800).setInterpolator(OvershootInterpolator(3f)).start() }
            .start()
        score.scaleX = 1.35f; score.scaleY = 1.35f
        score.animate().scaleX(1f).scaleY(1f).setDuration(900).setInterpolator(OvershootInterpolator(2.5f)).start()
        stopPulse(); dot.visibility = GONE
        status.text = "GOAL!"
        status.setTextColor(0xFF1C1C1E.toInt())
        pillBg.setColor(0xFFFFD60A.toInt()); pillBg.setStroke(0, 0)
        postDelayed({ celebrating = false; setStatus(m, System.currentTimeMillis(), tf) }, 6000)
    }

    private fun startPulse() {
        if (pulse != null) return
        pulse = ValueAnimator.ofFloat(1f, 0.2f).apply {
            duration = 850; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { dot.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun stopPulse() { pulse?.cancel(); pulse = null; dot.alpha = 1f }

    override fun onDetachedFromWindow() { stopPulse(); super.onDetachedFromWindow() }
}
