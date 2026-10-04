package com.cytube.mobile.net

import com.cytube.mobile.player.PlayerHandle
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A rough model of ExoPlayer streaming one big file over a slow server, for
 * testing how SyncEngine behaves in real time rather than one call at a time.
 *
 * What it models, because each of these is part of the long-load / seek /
 * stall pattern on large files:
 *  - opening the file costs extra (reading the index — for an MP4 whose
 *    index sits at the end, a whole extra request) before any video arrives;
 *  - every jump outside what's buffered is a new request that waits
 *    [firstByteSeconds] before data flows;
 *  - data then arrives at [throughputMbps] against a [bitrateMbps] video;
 *  - seeks land on keyframes the way NativePlayerHandle asks for them
 *    (NEXT_SYNC forward past the buffer, EXACT otherwise);
 *  - buffering rules match the app's LoadControl (2 s to start, 4 s after
 *    running dry, 50–120 s of look-ahead, 10 s kept behind).
 */
class SimulatedExoPlayer(
    val bitrateMbps: Double,
    /** Download speed, which can change over time (seconds since start). */
    val throughputMbps: (Double) -> Double,
    val firstByteSeconds: Double,
    val openExtraSeconds: Double,
    val keyframeSeconds: Double = 4.0,
    override val mediaLengthSeconds: Int = 7200,
    override val mediaId: String? = "big-movie",
) : PlayerHandle {

    private enum class State { IDLE, BUFFERING, READY }

    override val mediaType: String? = "fi"
    override var isReleased = false

    var now = 0.0; private set
    var position = 0.0; private set
    var rate = 1.0; private set
    private var playWhenReady = false
    private var state = State.IDLE
    private var needAhead = BUFFER_FOR_PLAYBACK
    private var bufStart = 0.0
    private var bufEnd = 0.0
    private var dataFlowsAt = Double.MAX_VALUE
    private var readyNoSoonerThan = 0.0
    private var downloading = false
    private var everReady = false

    val log = mutableListOf<String>()
    var seeks = 0; private set
    var requests = 0; private set
    var stalls = 0; private set
    var stalledSeconds = 0.0; private set
    var firstReadyAt = Double.NaN; private set

    override val isPaused get() = !playWhenReady
    override val isPlaying get() = state == State.READY && playWhenReady
    // NativePlayerHandle: BUFFERING || IDLE || (isLoading && !isPlaying)
    override val isBuffering get() =
        state != State.READY || (downloading && !isPlaying)
    override val isNative get() = true
    override val bufferedAheadSeconds get() = (bufEnd - position).coerceAtLeast(0.0)

    /** NativePlayerHandle.load: setMediaSource at the start position (the
     *  player's default CLOSEST_SYNC), prepare, playWhenReady. */
    fun open(startSeconds: Double, paused: Boolean) {
        val kf = nearestKeyframe(startSeconds)
        startRequest(kf, kf, extra = openExtraSeconds)
        playWhenReady = !paused
        rate = 1.0
        log += "%6.1fs open at %.1f (asked %.1f)".format(now, kf, startSeconds)
    }

    override fun seekTo(seconds: Double) {
        val target = seconds.coerceIn(0.0, mediaLengthSeconds.toDouble())
        seeks++
        val forwardPastBuffer = target > position && target > bufEnd - 1.0
        val from = position
        if (!forwardPastBuffer && target >= bufStart && target < bufEnd) {
            // EXACT inside the buffer: a short decoder flush, no download.
            position = target
            state = State.BUFFERING
            needAhead = minOf(BUFFER_FOR_PLAYBACK, bufEnd - target)
            readyNoSoonerThan = now + IN_BUFFER_SEEK_SECONDS
        } else if (forwardPastBuffer) {
            val kf = ceil(target / keyframeSeconds) * keyframeSeconds
            startRequest(kf, kf)
        } else {
            // EXACT outside the buffer: fetch from the keyframe before it.
            startRequest(floor(target / keyframeSeconds) * keyframeSeconds, target)
        }
        log += "%6.1fs seek %.1f -> %.1f (asked %.1f)%s".format(
            now, from, position, target, if (forwardPastBuffer) " new request" else "")
    }

    private fun startRequest(from: Double, playFrom: Double, extra: Double = 0.0) {
        requests++
        bufStart = from
        bufEnd = from
        position = playFrom
        dataFlowsAt = now + firstByteSeconds + extra
        downloading = true
        state = State.BUFFERING
        needAhead = BUFFER_FOR_PLAYBACK
        readyNoSoonerThan = 0.0
    }

    fun advance(dt: Double) {
        now += dt
        val ahead = bufEnd - position
        if (downloading && ahead >= MAX_BUFFER) downloading = false
        if (!downloading && ahead < MIN_BUFFER && bufEnd < mediaLengthSeconds) downloading = true
        if (downloading && now >= dataFlowsAt) {
            bufEnd = (bufEnd + dt * throughputMbps(now) / bitrateMbps).coerceAtMost(mediaLengthSeconds.toDouble())
        }
        when (state) {
            State.IDLE -> Unit
            State.BUFFERING -> {
                if (everReady && playWhenReady) stalledSeconds += dt
                val enough = bufEnd - position >= needAhead || bufEnd >= mediaLengthSeconds
                if (enough && now >= readyNoSoonerThan) {
                    state = State.READY
                    if (!everReady) {
                        everReady = true
                        firstReadyAt = now
                        log += "%6.1fs first frame at %.1f".format(now, position)
                    }
                }
            }
            State.READY -> if (playWhenReady) {
                position += dt * rate
                if (position >= bufEnd && bufEnd < mediaLengthSeconds) {
                    position = bufEnd
                    state = State.BUFFERING
                    needAhead = BUFFER_AFTER_REBUFFER
                    stalls++
                    log += "%6.1fs ran dry at %.1f".format(now, position)
                }
            }
        }
        bufStart = maxOf(bufStart, position - BACK_BUFFER)
    }

    private fun nearestKeyframe(t: Double) = Math.round(t / keyframeSeconds) * keyframeSeconds

    override fun setPlaybackRate(rate: Float) { this.rate = rate.toDouble() }
    override fun play() { playWhenReady = true }
    override fun pause() { playWhenReady = false }
    override suspend fun currentTimeSeconds(): Double = position
    override fun setVolume(volume: Float) {}
    override fun release() { isReleased = true }

    private companion object {
        // PlayerSurface's LoadControl settings.
        const val BUFFER_FOR_PLAYBACK = 2.0
        const val BUFFER_AFTER_REBUFFER = 4.0
        const val MIN_BUFFER = 50.0
        const val MAX_BUFFER = 120.0
        const val BACK_BUFFER = 10.0
        const val IN_BUFFER_SEEK_SECONDS = 0.3
    }
}

/**
 * Joining a channel partway through [player]'s file, driven the way
 * ChannelViewModel drives SyncEngine: the server's time every 5 s, the
 * app's own check every second in between, and the grace period after the
 * player is attached.
 */
class SimulatedJoin(
    val player: SimulatedExoPlayer,
    /** Where the room is when we join. */
    val joinAtSeconds: Double,
    val accuracySeconds: Double = 2.0,
    val serverUpdatePhaseSeconds: Double = 2.3,
    val engine: SyncEngine = SyncEngine(LeadMemory()),
    /** Open ahead of the room with SyncEngine.planStart, as the app does;
     *  false opens at the join frame's own time (the old behaviour). */
    val planStart: Boolean = true,
) {
    private var lastServerTime = 0.0
    private var lastServerAt = -1.0
    private var attachedAt = 0.0
    private var loaded = false

    /** Once a second after loading: how far behind the room the player is
     *  (negative = ahead) and whether it was actually playing. */
    data class Sample(val t: Double, val behind: Double, val playing: Boolean)
    val samples = mutableListOf<Sample>()

    private fun room(t: Double) = joinAtSeconds + t

    companion object {
        const val STREAM_URL = "https://files.example.org/movies/big-movie.mp4"
    }

    fun run(seconds: Double, dt: Double = 0.05) {
        var nextTick = 1.0
        var nextUpdate = serverUpdatePhaseSeconds
        val frameAt = 0.3        // changeMedia arrives just after joining
        val loadAt = 0.5         // the surface loads it on the next frames
        var t = 0.0
        while (t < seconds) {
            player.advance(dt)
            t = player.now
            if (lastServerAt < 0 && t >= frameAt) {
                lastServerTime = room(frameAt); lastServerAt = frameAt
                attachedAt = t
            }
            if (!loaded && t >= loadAt) {
                loaded = true
                val roomNow = lastServerTime + (t - lastServerAt)
                val start = if (planStart) {
                    engine.planStart(player.mediaId!!, STREAM_URL, roomNow, player.mediaLengthSeconds, roomPaused = false)
                } else lastServerTime
                player.open(start, paused = false)
                attachedAt = t
            }
            if (t >= nextUpdate) {
                nextUpdate += 5.0
                lastServerTime = room(t); lastServerAt = t
                evaluate(t)
            }
            if (t >= nextTick) {
                nextTick += 1.0
                evaluate(t)
                if (loaded) samples += Sample(t, room(t) - player.position, player.isPlaying)
            }
        }
    }

    private fun evaluate(t: Double) {
        if (!loaded || lastServerAt < 0) return
        val serverNow = lastServerTime + (t - lastServerAt)
        val grace = (t - attachedAt) * 1000 < 3_500
        val block: suspend () -> Unit = {
            engine.apply(player, TimeUpdate(serverNow, false), player.mediaId, accuracySeconds, grace,
                nowMs = 1_000_000L + (t * 1000).toLong())
        }
        var failure: Throwable? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { failure = it.exceptionOrNull() })
        failure?.let { throw it }
    }

    /** First time the player is within [accuracySeconds] of the room and
     *  stays there for [forSeconds]; NaN if never. */
    fun inSyncFrom(forSeconds: Double = 20.0): Double {
        var since = Double.NaN
        for ((t, d, playing) in samples) {
            if (abs(d) <= accuracySeconds && playing) {
                if (since.isNaN()) since = t
                if (t - since >= forSeconds) return since
            } else since = Double.NaN
        }
        return Double.NaN
    }

    fun summary(label: String): String {
        val tail = samples.filter { it.t >= samples.last().t - 60 }
        val worstLate = tail.maxOfOrNull { abs(it.behind) } ?: Double.NaN
        return "%-34s first frame %5.1fs | in sync from %6s | seeks %2d | requests %2d | ran dry %2d (%4.1fs) | worst drift last min %5.1fs"
            .format(label, player.firstReadyAt, inSyncFrom().let { if (it.isNaN()) "never" else "%.0fs".format(it) },
                player.seeks, player.requests, player.stalls, player.stalledSeconds, worstLate)
    }
}

