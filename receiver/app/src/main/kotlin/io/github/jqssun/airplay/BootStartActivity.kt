// Added by the CouchKit project (2026-10); licensed under GPL-3.0 like the rest of this app.
package io.github.jqssun.airplay

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import io.github.jqssun.airplay.service.AirPlayService

// TCL's TclAppBoot blocks startForeground from a background boot receiver ("default_borbid");
// a start issued while one of our activities is on screen is allowed, so this invisible
// activity starts the server and closes in the same frame
class BootStartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val serviceIntent = Intent(this, AirPlayService::class.java)
            .setAction(AirPlayService.ACTION_START_SERVER)
        runCatching { ContextCompat.startForegroundService(this, serviceIntent) }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
