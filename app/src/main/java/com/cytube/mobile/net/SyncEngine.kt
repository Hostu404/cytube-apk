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
 * One deliberate departure from the original: CyTube's own web client can
 * afford to just seek on any drift because a browser's <video> element
 * re-buffers a scrub near-instantly on the same host that's already streaming
 * it. A large, high-bitrate file over a mobile connection does not — a hard
 * seek can take seconds to recover from, and a 1/s update tick landing mid-
 * recovery would fire another seek on top of it, which is what turned into
 * constant skipping/jitter on long high-quality videos. So small drift is now
 * closed with a slight, inaudible playback-speed nudge instead of a seek, a
 * seek is reserved for drift large enough that ramping alone would take too
 * long to matter, and no corrective action is taken at all while the player
 * is already mid-buffer from a previous one. This never changes what "in
 * sync" means, only how gently the client gets there.
 */
class SyncEngine {

    data class Result(val didSeek: Boolean = false, val waiting: Boolean = false)

    suspend fun apply(
        player: PlayerHandle,
        update: TimeUpdate,
        newMediaId: String? = null,
        isLeader: Boolean,
        syncEnabled: Boolean,
        accuracySeconds: Double,
        /** True for a short window right after this player was handed a new
         *  media source. A YouTube item resolves its stream URL over its own
         *  network round trip (NewPipe) before ExoPlayer ever starts
         *  fetching bytes, so by the time it does, the server's currentTime
         *  has already moved on — a real, large diff, but one a hard seek
         *  or speed ramp can't usefully close yet: the player has no
         *  steady buffer of its own at this point, so seeking it just
         *  restarts the fetch at a new offset and produces another
         *  under-run a moment later. That's what turned the first few
         *  seconds after switching to YouTube into visible skipping/jitter.
         *  Giving the player a moment to establish real playback before
         *  SyncEngine starts nudging it closes that loop; play/pause sync
         *  and the initial play() below are unaffected, so playback still
         *  starts immediately — only position correction waits. */
        withinGracePeriod: Boolean = false
    ): Result {
        var currentTime = update.currentTime

        val length = player.mediaLengthSeconds
        if (length > 0 && currentTime > length) {
            // Past the end of a finite item — the server is about to advance the
            // playlist. Applying this would cause a pointless seek to the tail.
            return Result()
        }

        // Lead-in: the server counts up from a negative value so clients can
        // buffer before the group actually starts.
        val waiting = currentTime < 0

        if (newMediaId != null && newMediaId != player.mediaId) {
            if (currentTime < 0) currentTime = 0.0
            player.play()
        }

        if (waiting) {
            player.seekTo(0.0)
            player.pause()
            return Result(waiting = true)
        }

        // A leader IS the clock; and a user who has turned sync off is opting
        // out entirely. In both cases we apply nothing.
        if (isLeader || !syncEnabled) return Result()

        if (update.paused && !player.isPaused) {
            player.seekTo(currentTime)
            player.pause()
        } else if (player.isPaused && !update.paused) {
            player.play()
        }

        // Live/indeterminate-length media (server reports seconds <= 0 — an
        // HLS live channel, a 24/7 loop, an RTMP feed) has no business being
        // position-corrected at all. `currentTime` here is CyTube's own
        // elapsed-seconds counter for the item, but a live ExoPlayer window's
        // currentTimeSeconds() is relative to the LIVE WINDOW, not to when
        // the item started — the two numbers are not the same clock, so
        // their difference is not real drift, it's noise. Treating it as
        // drift is exactly what produced jittery, self-resolving-then-
        // recurring corrections on channels streaming this kind of source:
        // whatever "diff" that noise happens to land on gets seeked/ramped
        // toward, which can coincidentally sit still for a while (looks
        // "fixed") until the live window shifts again and it's wrong again.
        // Play/pause sync above is still meaningful and is kept; position
        // sync is not, for this kind of media.
        if (player.mediaLengthSeconds <= 0) return Result()

        // Something (this apply(), a previous seek, or the user scrubbing) is
        // already mid-rebuffer. Piling a corrective seek on top is exactly
        // what produced the skip/jitter loop on large files — let it finish;
        // CyTube's own updates arrive about once a second, so drift gets
        // re-evaluated again almost immediately once buffering clears.
        if (player.isBuffering) return Result()

        // See the parameter doc above: a freshly-loaded item (YouTube most
        // of all, since resolving it costs real time before ExoPlayer ever
        // starts fetching) hasn't built up a buffer yet even once it
        // reports non-buffering, so a correction here would just be
        // reacting to normal startup lag rather than genuine drift.
        if (withinGracePeriod) return Result()

        val local = player.currentTimeSeconds()
        val diff = if (currentTime - local != 0.0) currentTime - local else 0.0

        return when {
            diff > HARD_SEEK_THRESHOLD -> {
                // Far behind — a speed ramp would take too long to matter.
                player.seekTo(currentTime)
                Result(didSeek = true)
            }
            diff < -HARD_SEEK_THRESHOLD -> {
                // Far ahead. Do not seek all the way back; the +1 absorbs the
                // buffering that follows the seek.
                player.seekTo(currentTime + 1.0)
                Result(didSeek = true)
            }
            diff > accuracySeconds -> {
                // Behind, but only a little: close the gap by playing
                // fractionally faster instead of seeking, so there's nothing
                // for the CDN/file to rebuffer. accuracySeconds still governs
                // how eagerly this kicks in, same as it always did.
                player.setSpeed(SPEED_CATCH_UP)
                Result()
            }
            diff < -accuracySeconds -> {
                player.setSpeed(SPEED_SLOW_DOWN)
                Result()
            }
            else -> {
                // Back within tolerance — drop any speed ramp a previous tick
                // may have applied so playback sounds normal again.
                player.setSpeed(1f)
                Result()
            }
        }
    }

    /** Drift magnitude for the UI's sync indicator. */
    fun drift(serverTime: Double, localTime: Double): Double = abs(serverTime - localTime)

    private companion object {
        /** Beyond this many seconds of drift, ramping speed would take too
         *  long to close the gap — a hard seek is the only sane fix. */
        const val HARD_SEEK_THRESHOLD = 8.0
        const val SPEED_CATCH_UP = 1.06f
        const val SPEED_SLOW_DOWN = 0.94f
    }
}
