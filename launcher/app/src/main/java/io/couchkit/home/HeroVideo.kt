// SPDX-License-Identifier: Apache-2.0
package io.couchkit.home

import android.content.Context
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * Moving background behind the featured row: the user's own videos (LocalMedia). SurfaceView: the
 * hardware decoder renders straight into the TV's video plane, the UI GPU is not involved. Playback can
 * start at a fraction of the clip ([play] frac). When a clip ends the next random one continues seamlessly.
 */
class HeroVideo(ctx: Context, private val onFirstFrame: () -> Unit, private val onFail: () -> Unit) :
    SurfaceView(ctx), SurfaceHolder.Callback {
    private var player: androidx.media3.exoplayer.ExoPlayer? = null
    private var surfaceReady = false
    private var pending: Pair<String, Float>? = null
    private var loadedUrl: String? = null
    private var seeked = false
    private var armed = false
    private var startFrac = 0.4f
    /** The video currently playing (not paused). */
    var playingUrl: String? = null
        private set

    init { holder.addCallback(this) }

    /** One player, small buffer: 3-8 s / ~3 MB, no back buffer (the default 50 s buffer cost ~140 MB RAM). */
    private fun ensurePlayer(): androidx.media3.exoplayer.ExoPlayer {
        player?.let { return it }
        val load = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(3_000, 8_000, 1_000, 1_500)
            .setTargetBufferBytes(3 * 1024 * 1024)
            .setBackBuffer(0, false)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val p = androidx.media3.exoplayer.ExoPlayer.Builder(context).setLoadControl(load).build()
        p.setVideoSurfaceHolder(holder)
        p.videoScalingMode = androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        p.volume = 0f
        // No loop: when a clip ends a random next one follows (queued in advance, seamless)
        p.repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF
        p.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state != androidx.media3.common.Player.STATE_READY) return
                if (!seeked) {
                    seeked = true
                    val d = p.duration
                    if (d > 0) { p.seekTo((d * startFrac).toLong()); return }
                }
                if (playingUrl != null) { armed = true; p.playWhenReady = true }
            }
            // The clip's first frame (position 0) is drawn before the seek: cross-fade only once it PLAYS at
            // the right position and a few frames are on screen (otherwise an unrelated frame flashed).
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && armed && playingUrl != null) {
                    armed = false
                    postDelayed({ if (playingUrl != null) onFirstFrame() }, 250)
                }
            }
            override fun onMediaItemTransition(item: androidx.media3.common.MediaItem?, reason: Int) {
                // New clip started: queue the next one now
                if (p.mediaItemCount - p.currentMediaItemIndex <= 1) queueNext(p)
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.w(TAG, "hero video error ${error.errorCodeName} $loadedUrl")
                loadedUrl = null; playingUrl = null
                try { p.stop(); p.clearMediaItems() } catch (_: Exception) {}
                onFail()
            }
        })
        player = p
        return p
    }

    fun play(url: String, frac: Float) {
        if (url == playingUrl) return
        pending = url to frac
        if (surfaceReady) start()
    }

    private fun start() {
        val (url, frac) = pending ?: return
        pending = null
        val p = ensurePlayer()
        playingUrl = url
        if (url == loadedUrl) {
            // Same video was paused: continue, the frame is already there
            p.playWhenReady = true
            onFirstFrame()
            return
        }
        loadedUrl = url
        seeked = false
        armed = false
        startFrac = frac
        p.playWhenReady = false
        p.setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
        queueNext(p)
        p.prepare()
    }

    private fun queueNext(p: androidx.media3.exoplayer.ExoPlayer) {
        val last = if (p.mediaItemCount > 0) p.getMediaItemAt(p.mediaItemCount - 1).localConfiguration?.uri?.toString() else null
        LocalMedia.randomVideoCached(last)?.let { p.addMediaItem(androidx.media3.common.MediaItem.fromUri(it)) }
        // Drop old clips from the playlist (memory)
        while (p.currentMediaItemIndex > 0) p.removeMediaItem(0)
    }

    /** Freeze while navigating (decoder rests, focus animations stay smooth); [resume] continues. */
    fun hold() { if (playingUrl != null) player?.playWhenReady = false }
    fun resume() { if (playingUrl != null && seeked) player?.playWhenReady = true }

    /** An app with featured content was selected: video stops but stays loaded (instant resume). */
    fun pause() {
        pending = null
        playingUrl = null
        player?.playWhenReady = false
    }

    /** App went to background / screensaver: decoder and buffers are fully released. */
    fun release() {
        pending = null; playingUrl = null; loadedUrl = null
        player?.let { try { it.release() } catch (_: Exception) {} }
        player = null
    }

    override fun surfaceCreated(h: SurfaceHolder) { surfaceReady = true; if (pending != null) start() }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
    override fun surfaceDestroyed(h: SurfaceHolder) {
        surfaceReady = false
        release()
    }
}
