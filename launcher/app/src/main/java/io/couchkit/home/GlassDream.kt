// SPDX-License-Identifier: Apache-2.0
package io.couchkit.home

import android.service.dreams.DreamService

/**
 * System screensaver: the same media screensaver the home screen uses (+ optional widget overlay).
 * Select:  adb shell settings put secure screensaver_components io.couchkit.home/.GlassDream
 * Revert:  set screensaver_components back to the value it had before (read it first with `settings get`).
 */
class GlassDream : DreamService() {
    private var saver: ScreenSaver? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false   // any key closes the screensaver
        isFullscreen = true
        isScreenBright = true
        Config.refresh(this)
        val dm = resources.displayMetrics
        if (dm.widthPixels > 0) { Ui.screenW = dm.widthPixels; Ui.screenH = dm.heightPixels; Ui.u = dm.widthPixels / 1920f }
        val s = ScreenSaver(this, resources.getFont(R.font.inter_semibold), resources.getFont(R.font.inter_regular))
        setContentView(s)
        saver = s
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        saver?.start()
    }

    override fun onDreamingStopped() {
        saver?.stop()
        super.onDreamingStopped()
    }
}
