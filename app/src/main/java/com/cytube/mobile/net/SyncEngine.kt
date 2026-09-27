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
 *  4. JOIN AHEAD, THEN WAIT: joining an item partway through opens it ahead
 *     of the room by what opening files has been costing ([planStart],
 *     learned in [LeadMemory]); and a player that lands AHEAD after an open
 *     or a forward seek is held on that frame until the room catches up.
 *     Opening a big file mid-way can take 10 s or more, so starting at the
 *     room's time meant always arriving that far behind and then having to
 *     jump forward — a second request, a second wait, on a buffer too thin
 *     to ride out a slow patch. Waiting costs no network at all, and the
 *     buffer keeps filling meanwhile (CyTube's own lead-in works the same way).
 */
class SyncEngine(private val memory: LeadMemory = LeadMemory.shared) {

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

    /** The item [planStart] opened ahead of the room, from which server and
     *  by how much, until its first frame shows where it actually landed. */
    private var openLandingFor: String? = null
    private var openLandingServer = ""
    private var openLandingLead = 0.0

    /** A forward correction was issued and hasn't reached its first frame. */
    private var seekLanding = false

    /** Held (paused) at this position until the room reaches it; see 4.
     *  [holdRoomTime] is where the room was when the hold began. */
    private var holdAt: Double? = null
    private var holdRoomTime = 0.0

    /** The item the per-item state above belongs to. */
    private var lastSeenMediaId: String? = null

    /** True while the player is paused waiting for the room (see 4). */
    val isHolding: Boolean get() = holdAt != null

    /**
     * ChannelViewModel stops calling [apply] (leading, sync turned off, a
     * pause of the user's own, a personal pick): end the nudge and any hold.
     * With [resume] a held player is started again — otherwise it would sit
     * paused with nothing left to release it (and a leader's paused player
     * pauses the whole room).
     */
    fun standDown(player: PlayerHandle, resume: Boolean) {
        stopNudge(player)
        seekLanding = false
        openLandingFor = null
        if (holdAt != null) {
            holdAt = null
            if (resume) player.play()
        }
    }

    /**
     * Where to open [mediaId], streamed from [streamUrl], when joining it at
     * [roomTime]: that far in plus what opening from that server has been
     * costing, so it's there when the first frame shows. Near the start (the
     * server's lead-in already gives time to load), while the room is
     * paused, or for live media, just [roomTime].
     */
    fun planStart(
        mediaId: String,
        streamUrl: String,
        roomTime: Double,
        lengthSeconds: Int,
        roomPaused: Boolean
    ): Double {
        openLandingFor = null
        if (roomPaused || lengthSeconds <= 0 || roomTime < MIN_JOIN_POSITION_SECONDS) return roomTime
        val server = LeadMemory.serverOf(streamUrl)
        val start = (roomTime + memory.openLead(server)).coerceAtMost(lengthSeconds - 1.0)
        // Near the end the lead gets cut short; learn from what was used.
        if (start > roomTime) {
            openLandingFor = mediaId
            openLandingServer = server
            openLandingLead = start - roomTime
        }
        return maxOf(start, roomTime)
    }

    /**
     * Brings [player] in line with the server's [update]. Only called for
     * synced, non-leader playback: ChannelViewModel.evaluateSync handles
     * leaders and sync-off itself. [withinGracePeriod] is true for a few
     * seconds after a new item or player is attached (SYNC_GRACE_MS), and
     * holds off all position correction — seeks and nudges — while it lasts.
     */
    suspend fun apply(
        player: PlayerHandle,
        update: TimeUpdate,
        newMediaId: String?,
        accuracySeconds: Double,
        withinGracePeriod: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val currentTime = update.currentTime
        if (currentTime.isNaN() || currentTime.isInfinite()) return
        val safeAccuracy = if (accuracySeconds.isNaN() || accuracySeconds <= 0.0) 2.0 else accuracySeconds

        val length = player.mediaLengthSeconds
        if (length > 0 && currentTime > length) {
            // Past the end of a finite item — the server is about to advance the
            // playlist. Applying this would cause a pointless seek to the tail.
            return
        }

        // Lead-in: the server counts up from a negative value so clients can
        // buffer before the group actually starts.
        val waiting = currentTime < 0

        // The channel has moved on to a new item, but this player still holds
        // the previous one (the new one hasn't been loaded into it yet). Do
        // nothing to it: the server's time belongs to the NEW item, so
        // "correcting" the old video to it seeks the old video back near its
        // start, and the lead-in/paused handling below would then play it
        // again. That was the "video replays instead of moving on" seen when
        // an item changed while the app was in the background. Syncing
        // resumes once the new item is loaded and this player reports its id.
        if (newMediaId != null && newMediaId != player.mediaId) {
            lastNonNativeCorrectionMs = 0L
            lastNativeCorrectionMs = 0L
            playingSinceMs = 0L
            pendingLeadCheck = false
            seekLanding = false
            holdAt = null
            if (openLandingFor != newMediaId) openLandingFor = null
            stopNudge(player)
            return
        }
        // A new item can also arrive between two calls (loaded while the app
        // was in the background) without the check above ever seeing the
        // mismatch: the previous item's hold, landing and lead check belong
        // to it, not this one.
        if (player.mediaId != lastSeenMediaId) {
            lastSeenMediaId = player.mediaId
            pendingLeadCheck = false
            seekLanding = false
            holdAt = null
        }
        if (!player.isNative) openLandingFor = null

        if (waiting) {
            // Same "already there, don't re-correct" guard as the update.paused
            // branch just below — without it this fired the seek+pause on every
            // ~1s sync tick for the whole lead-in countdown, which is exactly
            // the kind of redundant correction the comment above isBuffering
            // warns causes skip/jitter.
            stopNudge(player)
            if (!player.isPaused) {
                player.seekTo(0.0)
                player.pause()
            }
            return
        }

        if (update.paused) {
            stopNudge(player)
            // A landing measured across a pause says nothing about how long
            // the open or the seek took.
            openLandingFor = null
            seekLanding = false
            if (!player.isPaused) {
                player.seekTo(currentTime)
                player.pause()
            }
            return
        }
        holdAt?.let { at ->
            // Waiting for the room to reach where we landed — unless the
            // player was moved meanwhile (scrubbed), the room jumped back, or
            // it's absurdly far off; then drop the hold and sync normally.
            val here = player.currentTimeSeconds()
            val moved = !here.isNaN() && abs(here - at) > HOLD_SLACK_SECONDS
            val roomWentBack = currentTime < holdRoomTime - HOLD_SLACK_SECONDS
            if (moved || roomWentBack || at - currentTime > MAX_HOLD_SECONDS) {
                holdAt = null
            } else {
                // Released a touch early: the next check is up to a second away.
                if (currentTime >= at - HOLD_RELEASE_EARLY_SECONDS) {
                    holdAt = null
                    playingSinceMs = 0L
                    player.play()
                }
                return
            }
        }
        if (player.isPaused) player.play()

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
            return
        }

        // Something (this apply(), a previous seek, or the user scrubbing) is
        // already mid-rebuffer or loading. Piling a corrective seek on top is exactly
        // what produced the skip/jitter loop on large files — let it finish;
        // ChannelViewModel re-evaluates every second (its own sync ticker —
        // the server itself only sends a time every 5s), so drift gets
        // looked at again almost immediately once buffering clears.
        if (player.isBuffering || !player.isPlaying) {
            playingSinceMs = 0L
            return
        }
        if (playingSinceMs == 0L) playingSinceMs = nowMs

        // First frame after opening ahead (planStart) or a forward
        // correction: see where it landed. Ahead → wait there for the room.
        if (player.isNative && (seekLanding || openLandingFor == player.mediaId)) {
            val opened = openLandingFor == player.mediaId
            seekLanding = false
            openLandingFor = null
            val landed = player.currentTimeSeconds()
            if (!landed.isNaN() && !landed.isInfinite() && landed > 0.0) {
                val behind = currentTime - landed
                if (opened) {
                    memory.learnOpenLead(
                        openLandingServer,
                        (openLandingLead + LEAD_LEARN_GAIN * behind).coerceIn(0.0, LeadMemory.MAX_OPEN_LEAD_SECONDS)
                    )
                }
                if (-behind >= HOLD_MIN_SECONDS && -behind <= MAX_HOLD_SECONDS) {
                    if (!opened && pendingLeadCheck) {
                        // Overshot: forward seeks cost less than the lead.
                        pendingLeadCheck = false
                        seekLeadSeconds = (seekLeadSeconds + LEAD_LEARN_GAIN * behind)
                            .coerceIn(0.0, MAX_SEEK_LEAD_SECONDS)
                    }
                    stopNudge(player)
                    holdAt = landed
                    holdRoomTime = currentTime
                    playingSinceMs = 0L
                    player.pause()
                    return
                }
            }
        }

        if (withinGracePeriod) return

        val local = player.currentTimeSeconds()
        // Ignore uninitialized (0.0) or invalid local player positions while player is spinning up
        if (local.isNaN() || local.isInfinite() || local <= 0.0) return
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
            if (nowMs - lastNonNativeCorrectionMs < NON_NATIVE_CORRECTION_COOLDOWN_MS) return
            when {
                diff >= NON_NATIVE_DRIFT_THRESHOLD_SECONDS -> {
                    lastNonNativeCorrectionMs = nowMs
                    player.seekTo(currentTime)
                }
                diff <= -NON_NATIVE_DRIFT_THRESHOLD_SECONDS -> {
                    lastNonNativeCorrectionMs = nowMs
                    player.seekTo(currentTime + 1.0)
                }
            }
            return
        }

        // ---- native (ExoPlayer) ----

        // SETTLE: only judge drift once playback has been running cleanly for
        // a moment. Right after a rebuffer, the position is exactly as far
        // behind as the rebuffer was long — correcting that immediately (with
        // another seek, into another unbuffered range) is the loop.
        if (nowMs - playingSinceMs < SETTLE_MS) return

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
            if (nowMs - lastNativeCorrectionMs < NATIVE_CORRECTION_COOLDOWN_MS) return
            lastNativeCorrectionMs = nowMs
            stopNudge(player)
            playingSinceMs = 0L
            if (diff > 0) {
                // Behind: land ahead of the server by what a forward seek is
                // currently costing, so we're on time when it resumes.
                pendingLeadCheck = true
                seekLanding = true
                player.seekTo(currentTime + seekLeadSeconds)
            } else {
                // Ahead: a backward seek normally lands in the back buffer and
                // is near-free; keep upstream's +1.
                player.seekTo(currentTime + 1.0)
            }
            return
        }

        // Moderate drift: nudge playback rate rather than seek. Starts at the
        // user's accuracy setting, keeps going until nearly exact (hysteresis),
        // so it doesn't flap on and off around the threshold.
        val shouldNudge = if (nudging) absDiff > NUDGE_STOP_SECONDS else absDiff >= safeAccuracy
        if (!shouldNudge) {
            stopNudge(player)
            return
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
                return
            }
            player.setPlaybackRate((1.0 + magnitude).toFloat())
        } else {
            player.setPlaybackRate((1.0 - magnitude).toFloat())
        }
        nudging = true
    }

    /** Ends any rate nudge in progress. Also called by ChannelViewModel when it
     *  stops calling apply() (leader, sync turned off). */
    fun stopNudge(player: PlayerHandle) {
        if (!nudging) return
        nudging = false
        player.setPlaybackRate(1f)
    }

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

        /** Below this the server's lead-in covers the load; no open lead. */
        const val MIN_JOIN_POSITION_SECONDS = 10.0
        /** Landing less than this far ahead is left to the rate nudge. */
        const val HOLD_MIN_SECONDS = 1.5
        /** Further ahead than this, something else is wrong: correct normally. */
        const val MAX_HOLD_SECONDS = 20.0
        const val HOLD_RELEASE_EARLY_SECONDS = 0.4
        /** A held player further than this from where it was held, or a
         *  room that went back further than this, ends the hold. */
        const val HOLD_SLACK_SECONDS = 1.0
    }
}

/**
 * What SyncEngine learns that should outlive one channel visit: how long
 * opening a file partway through takes, per server (a busy home server and
 * YouTube's are nothing alike). ChannelViewModel saves it between runs, so
 * joining a channel again starts from what opening from its server actually
 * cost last time instead of a guess.
 */
class LeadMemory(private val defaultOpenLeadSeconds: Double = DEFAULT_OPEN_LEAD_SECONDS) {
    private val openLeads = LinkedHashMap<String, Double>()

    /** How far past the room's time to open an item from [server] that's
     *  joined partway through; see SyncEngine.planStart. */
    @Synchronized fun openLead(server: String): Double = openLeads[server] ?: defaultOpenLeadSeconds

    @Synchronized fun learnOpenLead(server: String, seconds: Double) {
        openLeads.remove(server)
        openLeads[server] = seconds
        while (openLeads.size > MAX_SERVERS) openLeads.remove(openLeads.keys.first())
    }

    /** For saving: server → seconds, oldest first. */
    @Synchronized fun snapshot(): Map<String, Double> = LinkedHashMap(openLeads)

    @Synchronized fun restore(saved: Map<String, Double>) {
        for ((server, seconds) in saved) {
            if (server.isNotBlank() && seconds.isFinite()) learnOpenLead(server, seconds.coerceIn(0.0, MAX_OPEN_LEAD_SECONDS))
        }
    }

    companion object {
        /** A first guess, before any open from a server has been measured.
         *  Landing early only means waiting on the first frame for a moment;
         *  landing late means a second request, and on a slow server a
         *  second long wait — so this errs on the generous side. */
        const val DEFAULT_OPEN_LEAD_SECONDS = 6.0
        const val MAX_OPEN_LEAD_SECONDS = 20.0
        private const val MAX_SERVERS = 50
        val shared = LeadMemory()

        /** "rr3---sn-abc.googlevideo.com" and "rr5---sn-xyz.googlevideo.com"
         *  are the same service: keyed on the last two labels of the host. */
        fun serverOf(url: String): String {
            val host = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#:@]+@)?([^/?#:]+)")
                .find(url)?.groupValues?.get(2)?.lowercase() ?: return ""
            if (Regex("^[0-9.]+$").matches(host)) return host
            return host.split('.').takeLast(2).joinToString(".")
        }
    }
}
