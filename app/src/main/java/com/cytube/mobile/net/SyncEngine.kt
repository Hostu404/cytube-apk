package com.cytube.mobile.net

import com.cytube.mobile.player.PlayerHandle
import kotlin.math.abs

/**
 * Direct port of window.handleMediaUpdate from CyTube's player/update.coffee.
 *
 * The comments mark the non-obvious rules, all of which exist for a reason in
 * the original and all of which are easy to get subtly wrong:
 *
 *  - a negative currentTime is a deliberate lead-in, not an error
 *  - an update past the end of the media is discarded, except for livestreams
 *    (which report seconds == 0)
 *  - when correcting a player that is AHEAD, seek to serverTime + 1 rather than
 *    exactly serverTime, so buffering does not immediately put it behind again
 */
class SyncEngine {

    data class Result(val didSeek: Boolean = false, val waiting: Boolean = false)

    private var lastNonNativeCorrectionMs: Long = 0L
    private var lastNativeCorrectionMs: Long = 0L

    suspend fun apply(
        player: PlayerHandle,
        update: TimeUpdate,
        newMediaId: String? = null,
        isLeader: Boolean,
        syncEnabled: Boolean,
        accuracySeconds: Double,
        withinGracePeriod: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): Result {
        var currentTime = update.currentTime
        if (currentTime.isNaN() || currentTime.isInfinite()) return Result()
        val safeAccuracy = if (accuracySeconds.isNaN() || accuracySeconds <= 0.0) 2.0 else accuracySeconds

        val length = player.mediaLengthSeconds
        if (length > 0 && currentTime > length) {
            // Past the end of a finite item — the server is about to advance the
            // playlist. Applying this would cause a pointless seek to the tail.
            return Result()
        }

        // A leader IS the clock; and a user who has turned sync off is opting
        // out entirely. In both cases we apply nothing.
        if (isLeader || !syncEnabled) return Result()

        // Lead-in: the server counts up from a negative value so clients can
        // buffer before the group actually starts.
        val waiting = currentTime < 0

        if (newMediaId != null && newMediaId != player.mediaId) {
            if (currentTime < 0) currentTime = 0.0
            lastNonNativeCorrectionMs = 0L
            lastNativeCorrectionMs = 0L
            player.play()
        }

        if (waiting) {
            player.seekTo(0.0)
            player.pause()
            return Result(waiting = true)
        }

        if (update.paused) {
            if (!player.isPaused) {
                player.seekTo(currentTime)
                player.pause()
            }
            return Result()
        } else if (player.isPaused) {
            player.play()
        }

        // Live/indeterminate-length media (server reports seconds <= 0 — an
        // HLS live channel, a 24/7 loop, an RTMP feed) has no business being
        // position-corrected at all. `currentTime` here is CyTube's own
        // elapsed-seconds counter for the item, but a live ExoPlayer window's
        // currentTimeSeconds() is relative to the LIVE WINDOW, not to when
        // the item started — the two numbers are not the same clock, so
        // their difference is not real drift, it's noise.
        // Play/pause sync above is still meaningful and is kept; position
        // sync is not, for this kind of media.
        if (player.mediaLengthSeconds <= 0) return Result()

        // Something (this apply(), a previous seek, or the user scrubbing) is
        // already mid-rebuffer or loading. Piling a corrective seek on top is exactly
        // what produced the skip/jitter loop on large files — let it finish;
        // CyTube's own updates arrive about once a second, so drift gets
        // re-evaluated again almost immediately once buffering clears.
        if (player.isBuffering || !player.isPlaying) return Result()

        if (withinGracePeriod) return Result()

        val local = player.currentTimeSeconds()
        if (local.isNaN() || local.isInfinite() || local < 0.0) return Result()
        val diff = if (currentTime - local != 0.0) currentTime - local else 0.0

        // Non-native / external players (WebView embeds: YouTube IFrame API, Vimeo SDK,
        // Dailymotion SDK, PeerTube, Streamable, generic HTML5 embeds):
        //
        // 1. External player embeds run inside a WebView JS environment with async bridge latency.
        // 2. Repeated seeking in external iframes forces full pipeline stalls and rebuffering.
        // 3. Ignore drift under 10 seconds and let playback continue normally.
        // 4. At 10+ seconds drift (ahead or behind), allow a corrective seek with a cooldown.
        if (!player.isNative) {
            if (nowMs - lastNonNativeCorrectionMs < NON_NATIVE_CORRECTION_COOLDOWN_MS) return Result()

            return when {
                diff >= NON_NATIVE_DRIFT_THRESHOLD_SECONDS -> {
                    lastNonNativeCorrectionMs = nowMs
                    player.seekTo(currentTime)
                    Result(didSeek = true)
                }
                diff <= -NON_NATIVE_DRIFT_THRESHOLD_SECONDS -> {
                    lastNonNativeCorrectionMs = nowMs
                    player.seekTo(currentTime + 1.0)
                    Result(didSeek = true)
                }
                else -> Result()
            }
        }

        if (nowMs - lastNativeCorrectionMs < NATIVE_CORRECTION_COOLDOWN_MS) return Result()

        return when {
            diff >= safeAccuracy -> {
                lastNativeCorrectionMs = nowMs
                player.seekTo(currentTime)
                Result(didSeek = true)
            }
            diff <= -safeAccuracy -> {
                // When correcting a player that is ahead, seek to currentTime + 1
                // so buffering does not immediately put it behind again.
                lastNativeCorrectionMs = nowMs
                player.seekTo(currentTime + 1.0)
                Result(didSeek = true)
            }
            else -> Result()
        }
    }

    /** Drift magnitude for the UI's sync indicator. */
    fun drift(serverTime: Double, localTime: Double): Double = abs(serverTime - localTime)

    private companion object {
        const val NON_NATIVE_DRIFT_THRESHOLD_SECONDS = 10.0
        const val NON_NATIVE_CORRECTION_COOLDOWN_MS = 5000L
        const val NATIVE_CORRECTION_COOLDOWN_MS = 3000L
    }
}
