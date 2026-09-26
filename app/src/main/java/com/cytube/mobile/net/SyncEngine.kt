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
 *
 * Native (ExoPlayer) playback deviates from the original in three ways, all to
 * stop a rebuffer from turning into a seek → rebuffer → seek loop on large
 * files:
 *
 *  1. SETTLE: no correction is evaluated until the player has been playing
 *     uninterrupted for [SETTLE_MS]. The old cooldown was measured from when
 *     the seek was *issued*, so a seek whose rebuffer took longer than the
 *     cooldown was re-corrected the instant it resumed — by exactly the drift
 *     its own rebuffer had just created.
 *  2. NUDGE: moderate drift is closed by running slightly fast/slow instead of
 *     seeking, so the buffer is never thrown away for it.
 *  3. LEAD: a forward hard seek lands ahead of the server by a learned amount
 *     ([seekLeadSeconds]) equal to what forward seeks have actually been
 *     costing, instead of landing exactly on serverTime and being behind again
 *     by the time the rebuffer finishes.
 */
class SyncEngine {

    data class Result(val didSeek: Boolean = false, val waiting: Boolean = false)

    private var lastNonNativeCorrectionMs: Long = 0L
    private var lastNativeCorrectionMs: Long = 0L

    /** When the native player last went from not-playing (buffering, loading,
     *  paused) to playing. 0 while it is not playing. */
    private var playingSinceMs: Long = 0L

    /** How far past serverTime a forward correction seeks, to cover the time
     *  the resulting rebuffer takes. Learned from where previous forward seeks
     *  actually ended up once playback settled. */
    private var seekLeadSeconds: Double = DEFAULT_SEEK_LEAD_SECONDS

    /** A forward correction was issued and we have not yet measured where it
     *  settled. */
    private var pendingLeadCheck = false

    /** True while a playback-rate nudge is in effect. */
    private var nudging = false

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
        // out entirely. In both cases we apply nothing (other than making sure
        // a nudge in progress doesn't leave the player running fast/slow).
        if (isLeader || !syncEnabled) {
            stopNudge(player)
            return Result()
        }

        // Lead-in: the server counts up from a negative value so clients can
        // buffer before the group actually starts.
        val waiting = currentTime < 0

        if (newMediaId != null && newMediaId != player.mediaId) {
            if (currentTime < 0) currentTime = 0.0
            lastNonNativeCorrectionMs = 0L
            lastNativeCorrectionMs = 0L
            playingSinceMs = 0L
            pendingLeadCheck = false
            stopNudge(player)
            player.play()
        }

        if (waiting) {
            // Same "already there, don't re-correct" guard as the update.paused
            // branch just below — without it this fired the seek+pause on every
            // ~1s server tick for the whole lead-in countdown, which is exactly
            // the kind of redundant correction the comment above isBuffering
            // warns causes skip/jitter.
            stopNudge(player)
            if (!player.isPaused) {
                player.seekTo(0.0)
                player.pause()
            }
            return Result(waiting = true)
        }

        if (update.paused) {
            stopNudge(player)
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
        if (player.mediaLengthSeconds <= 0) {
            stopNudge(player)
            return Result()
        }

        // Something (this apply(), a previous seek, or the user scrubbing) is
        // already mid-rebuffer or loading. Piling a corrective seek on top is exactly
        // what produced the skip/jitter loop on large files — let it finish;
        // CyTube's own updates arrive about once a second, so drift gets
        // re-evaluated again almost immediately once buffering clears.
        if (player.isBuffering || !player.isPlaying) {
            playingSinceMs = 0L
            return Result()
        }
        if (playingSinceMs == 0L) playingSinceMs = nowMs

        if (withinGracePeriod) return Result()

        val local = player.currentTimeSeconds()
        // Ignore uninitialized (0.0) or invalid local player positions while player is spinning up
        if (local.isNaN() || local.isInfinite() || local <= 0.0) return Result()
        // Positive = player is BEHIND the server; negative = AHEAD.
        val diff = currentTime - local

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

        // ---- native (ExoPlayer) ----

        // SETTLE: only judge drift once playback has been running cleanly for
        // a moment. Right after a rebuffer, the position is exactly as far
        // behind as the rebuffer was long — correcting that immediately (with
        // another seek, into another unbuffered range) is the loop.
        if (nowMs - playingSinceMs < SETTLE_MS) return Result()

        // LEAD learning: a forward correction has now settled; whatever drift
        // is left over is what that seek's rebuffer cost beyond the lead we
        // gave it. Fold it in so the next forward seek lands closer.
        if (pendingLeadCheck) {
            pendingLeadCheck = false
            seekLeadSeconds = (seekLeadSeconds + LEAD_LEARN_GAIN * diff)
                .coerceIn(0.0, MAX_SEEK_LEAD_SECONDS)
        }

        val absDiff = abs(diff)
        val hardSeekThreshold = maxOf(HARD_SEEK_THRESHOLD_SECONDS, safeAccuracy * 2)

        // Big drift: a seek is the only sensible fix.
        if (absDiff >= hardSeekThreshold) {
            if (nowMs - lastNativeCorrectionMs < NATIVE_CORRECTION_COOLDOWN_MS) return Result()
            lastNativeCorrectionMs = nowMs
            stopNudge(player)
            playingSinceMs = 0L
            return if (diff > 0) {
                // Behind: land ahead of the server by what a forward seek is
                // currently costing, so we're on time when it resumes.
                pendingLeadCheck = true
                player.seekTo(currentTime + seekLeadSeconds)
                Result(didSeek = true)
            } else {
                // Ahead: a backward seek normally lands in the back buffer and
                // is near-free; keep upstream's +1.
                player.seekTo(currentTime + 1.0)
                Result(didSeek = true)
            }
        }

        // Moderate drift: nudge playback rate rather than seek. Starts at the
        // user's accuracy setting, keeps going until nearly exact (hysteresis),
        // so it doesn't flap on and off around the threshold.
        val shouldNudge = if (nudging) absDiff > NUDGE_STOP_SECONDS else absDiff >= safeAccuracy
        if (!shouldNudge) {
            stopNudge(player)
            return Result()
        }

        val magnitude = (NUDGE_BASE + NUDGE_PER_SECOND * absDiff).coerceAtMost(NUDGE_MAX)
        if (diff > 0) {
            // Speeding up drains the buffer faster than real time. If there
            // isn't much buffered, hold at 1x rather than cause a stall — the
            // drift is still under the hard-seek threshold and will be picked
            // up once the buffer has grown.
            val ahead = player.bufferedAheadSeconds
            if (!ahead.isNaN() && ahead < MIN_BUFFER_FOR_SPEEDUP_SECONDS) {
                stopNudge(player)
                return Result()
            }
            player.setPlaybackRate((1.0 + magnitude).toFloat())
        } else {
            player.setPlaybackRate((1.0 - magnitude).toFloat())
        }
        nudging = true
        return Result()
    }

    /** Ends any rate nudge in progress. Also called by ChannelViewModel when it
     *  stops calling apply() (leader, sync turned off). */
    fun stopNudge(player: PlayerHandle) {
        if (!nudging) return
        nudging = false
        player.setPlaybackRate(1f)
    }

    /** Drift magnitude for the UI's sync indicator. */
    fun drift(serverTime: Double, localTime: Double): Double = abs(serverTime - localTime)

    private companion object {
        const val NON_NATIVE_DRIFT_THRESHOLD_SECONDS = 10.0
        const val NON_NATIVE_CORRECTION_COOLDOWN_MS = 5000L
        const val NATIVE_CORRECTION_COOLDOWN_MS = 3000L

        /** Uninterrupted playback required before native drift is judged. */
        const val SETTLE_MS = 2_000L

        /** Drift at/above which native playback hard-seeks instead of nudging
         *  (or 2x the accuracy setting, whichever is larger). */
        const val HARD_SEEK_THRESHOLD_SECONDS = 6.0

        const val DEFAULT_SEEK_LEAD_SECONDS = 1.0
        const val MAX_SEEK_LEAD_SECONDS = 8.0
        const val LEAD_LEARN_GAIN = 0.75

        /** Rate offset = BASE + PER_SECOND * |drift|, capped at MAX.
         *  2s drift → 7%, 4s → 10%, 6s → 12% (the cap). Pitch-corrected by
         *  ExoPlayer, so it's barely audible at these values. */
        const val NUDGE_BASE = 0.04
        const val NUDGE_PER_SECOND = 0.015
        const val NUDGE_MAX = 0.12
        const val NUDGE_STOP_SECONDS = 0.3
        const val MIN_BUFFER_FOR_SPEEDUP_SECONDS = 4.0
    }
}
