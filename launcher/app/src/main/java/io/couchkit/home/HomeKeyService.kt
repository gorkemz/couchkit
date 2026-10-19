// SPDX-License-Identifier: Apache-2.0
package io.couchkit.home

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Optional accessibility service ("CouchKit Home — Home button").
 *
 * On Google TV builds where the default home app cannot be changed, it redirects to CouchKit Home
 * whenever the stock launcher's home screen comes to the front (after leaving an app, after boot).
 * The stock launcher is never disabled; it always stays installed as a fallback. A rate limit stops
 * the redirect if CouchKit Home keeps crashing, so the TV can never get stuck in a loop.
 *
 * Where CouchKit Home is the default home app (`cmd package set-home-activity`), the Home key opens it
 * directly; key filtering is therefore not requested (it made the system handle volume keys twice).
 * The service also hosts the goal pill watcher ([GoalWatcher]), which only polls when football is
 * configured.
 */
class HomeKeyService : AccessibilityService() {
    private var goals: GoalWatcher? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Config.refresh(this)
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(dm)
        if (Ui.screenW == 1920 && dm.widthPixels > 0) { Ui.screenW = dm.widthPixels; Ui.screenH = dm.heightPixels; Ui.u = dm.widthPixels / 1920f }
        goals = GoalWatcher(this).also { it.start() }
    }

    override fun onDestroy() {
        goals?.stop(); goals = null
        super.onDestroy()
    }

    private var homeDownAt = 0L
    private val recentLaunches = LongArray(4)
    private var launchIdx = 0
    private var lastAuto = 0L

    /** Only called if key filtering is enabled in home_key_service.xml (off by default). */
    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode != KeyEvent.KEYCODE_HOME) return false
        when (e.action) {
            KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0) homeDownAt = e.eventTime
            KeyEvent.ACTION_UP -> {
                if (e.eventTime - homeDownAt >= LONG_PRESS_MS) performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                else openHome(force = true)
            }
        }
        return true
    }

    override fun onAccessibilityEvent(ev: AccessibilityEvent) {
        if (ev.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (ev.packageName?.toString() !in Launchers.PREVIOUS) return
        val cls = ev.className?.toString() ?: return
        if (cls.endsWith(".HomeActivity") || cls.endsWith(".MainActivity")) openHome(force = false)
    }

    private fun openHome(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force) {
            // The stock launcher sends several window events per launch: count those within 1.5 s once.
            if (now - lastAuto < 1500) return
            lastAuto = now
            // More than 4 automatic opens in 20 s: CouchKit Home is probably crashing -> stay on the stock home.
            val oldest = recentLaunches[launchIdx]
            if (oldest != 0L && now - oldest < 20_000) {
                Log.w(TAG, "auto-open rate limited; staying on the stock home screen")
                return
            }
            recentLaunches[launchIdx] = now
            launchIdx = (launchIdx + 1) % recentLaunches.size
        }
        try {
            startActivity(Intent(this, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        } catch (ex: Exception) {
            Log.w(TAG, "open home", ex)
        }
    }

    override fun onInterrupt() {}

    companion object {
        private const val LONG_PRESS_MS = 600L
    }
}
