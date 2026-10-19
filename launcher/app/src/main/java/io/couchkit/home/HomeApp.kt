// SPDX-License-Identifier: Apache-2.0
package io.couchkit.home

import android.app.Application
import android.os.Looper
import android.util.Log

/**
 * When this is the only home screen, a crashed process means a blank TV. Unexpected errors on
 * background threads (network, images, scores, batteries) are logged and swallowed; only main-thread
 * crashes reach the system, which then restarts the home activity.
 */
class HomeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val system = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            if (Looper.getMainLooper().thread === t) system?.uncaughtException(t, e)
            else Log.e(TAG, "background thread ${t.name} failed; ignored", e)
        }
        Config.refresh(this)
    }
}
