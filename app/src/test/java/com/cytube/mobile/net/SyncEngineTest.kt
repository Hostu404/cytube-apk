package com.cytube.mobile.net

import com.cytube.mobile.player.PlayerHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/** A player the test controls completely: position, state and what it was told to do. */
private class FakePlayer : PlayerHandle {
    override var mediaId: String? = "movie"
    override var mediaType: String? = "fi"
    override var mediaLengthSeconds: Int = 3600
    override var isPaused: Boolean = false
    override var isBuffering: Boolean = false
    var native: Boolean = true
    override val isNative: Boolean get() = native
    override var isReleased: Boolean = false
    override var bufferedAheadSeconds: Double = 30.0

    var position = 0.0
    var rate = 1f
    val seeks = mutableListOf<Double>()
    var plays = 0
    var pauses = 0

    override fun setPlaybackRate(rate: Float) { this.rate = rate }
    override fun play() { isPaused = false; plays++ }
    override fun pause() { isPaused = true; pauses++ }
    override fun seekTo(seconds: Double) { seeks += seconds; position = seconds }
    override suspend fun currentTimeSeconds(): Double = position
    override fun setVolume(volume: Float) {}
    override fun release() { isReleased = true }
}

class SyncEngineTest {

    /** A realistic clock value: 0 means "not started" inside the engine. */
    private val T0 = 1_000_000L

    private lateinit var engine: SyncEngine
    private lateinit var player: FakePlayer

    @Before fun setUp() {
        engine = SyncEngine()
        player = FakePlayer()
    }

    /** apply() is suspend only because currentTimeSeconds() is; the fake
     *  never actually suspends, so this runs it to completion in place. */
    private fun apply(
        serverTime: Double,
        paused: Boolean = false,
        nowMs: Long,
        newMediaId: String? = player.mediaId,
        accuracy: Double = 2.0,
        grace: Boolean = false
    ) {
        var failure: Throwable? = null
        val block: suspend () -> Unit = {
            engine.apply(player, TimeUpdate(serverTime, paused), newMediaId, accuracy, grace, nowMs)
        }
        block.startCoroutine(Continuation(EmptyCoroutineContext) { result -> failure = result.exceptionOrNull() })
        failure?.let { throw it }
    }

    /** Playing cleanly long enough for the settle window to pass. */
    private fun settle(serverTime: Double, startMs: Long): Long {
        apply(serverTime, nowMs = startMs)   // starts the settle clock
        return startMs + 2_500
    }

    @Test fun leavesAPlayerStillHoldingThePreviousItemAlone() {
        // The replay bug: the channel moved on but the player still has the
        // old video; the new item's lead-in / time must not touch it.
        player.position = 3590.0
        apply(serverTime = -3.0, nowMs = T0, newMediaId = "next")
        apply(serverTime = 5.0, nowMs = T0 + 10_000, newMediaId = "next")
        assertEquals(emptyList<Double>(), player.seeks)
        assertEquals(0, player.plays)
        assertEquals(0, player.pauses)
    }

    @Test fun leadInHoldsAtStartOnce() {
        player.position = 12.0
        apply(serverTime = -3.0, nowMs = T0)
        assertEquals(listOf(0.0), player.seeks)
        assertEquals(1, player.pauses)
        apply(serverTime = -2.0, nowMs = T0 + 1_000)
        assertEquals("already held: no second seek", listOf(0.0), player.seeks)
    }

    @Test fun followsServerPause() {
        player.position = 50.0
        apply(serverTime = 48.0, paused = true, nowMs = T0)
        assertEquals(listOf(48.0), player.seeks)
        assertTrue(player.isPaused)
    }

    @Test fun resumesWhenServerPlays() {
        player.isPaused = true
        player.position = 10.0
        apply(serverTime = 10.0, nowMs = T0)
        assertEquals(1, player.plays)
    }

    @Test fun pastTheEndIsIgnored() {
        player.position = 3599.0
        apply(serverTime = 3700.0, nowMs = T0)
        assertEquals(emptyList<Double>(), player.seeks)
    }

    @Test fun liveStreamsAreNeverPositionCorrected() {
        player.mediaLengthSeconds = 0
        player.position = 5.0
        val t = settle(500.0, T0)
        apply(serverTime = 500.0, nowMs = t)
        assertEquals(emptyList<Double>(), player.seeks)
    }

    @Test fun noCorrectionDuringGracePeriod() {
        player.position = 10.0
        apply(serverTime = 100.0, nowMs = T0, grace = true)
        apply(serverTime = 100.0, nowMs = T0 + 10_000, grace = true)
        assertEquals(emptyList<Double>(), player.seeks)
    }

    @Test fun noCorrectionWhileBuffering() {
        player.position = 10.0
        player.isBuffering = true
        apply(serverTime = 100.0, nowMs = T0)
        apply(serverTime = 100.0, nowMs = T0 + 10_000)
        assertEquals(emptyList<Double>(), player.seeks)
    }

    @Test fun waitsForPlaybackToSettleBeforeJudgingDrift() {
        player.position = 80.0
        apply(serverTime = 100.0, nowMs = T0)
        apply(serverTime = 100.0, nowMs = T0 + 1_000)
        assertEquals("inside the settle window", emptyList<Double>(), player.seeks)
        apply(serverTime = 100.0, nowMs = T0 + 2_500)
        assertEquals(1, player.seeks.size)
    }

    @Test fun farBehindSeeksAheadByTheLead() {
        player.position = 80.0
        val t = settle(100.0, T0)
        apply(serverTime = 100.0, nowMs = t)
        assertEquals(listOf(101.0), player.seeks)   // default lead 1s
    }

    @Test fun farAheadSeeksToServerPlusOne() {
        player.position = 120.0
        val t = settle(100.0, T0)
        apply(serverTime = 100.0, nowMs = t)
        assertEquals(listOf(101.0), player.seeks)
    }

    @Test fun hardSeeksHaveACooldown() {
        player.position = 80.0
        val t = settle(100.0, T0)
        apply(serverTime = 100.0, nowMs = t)             // correction at t
        assertEquals(1, player.seeks.size)
        player.position = 50.0
        apply(serverTime = 100.0, nowMs = t + 100)       // restarts the settle clock
        apply(serverTime = 100.0, nowMs = t + 2_200)     // settled, but only 2.2s after the last seek
        assertEquals("inside the 3s cooldown", 1, player.seeks.size)
        apply(serverTime = 100.0, nowMs = t + 3_100)
        assertEquals(2, player.seeks.size)
    }

    @Test fun learnsHowFarAheadToSeek() {
        player.position = 80.0
        var t = settle(100.0, T0)
        apply(serverTime = 100.0, nowMs = t)            // seek to 101, lead check pending
        player.position = 101.0
        t = settle(103.0, t + 100)                      // settles 2s behind the server
        apply(serverTime = 103.0, nowMs = t)            // folds 2s in: lead = 1 + 0.75 * 2 = 2.5
        player.position = 90.0
        t = settle(110.0, t + 3_000)
        apply(serverTime = 110.0, nowMs = t)
        assertEquals(110.0 + 2.5, player.seeks.last(), 1e-9)
    }

    @Test fun moderateDriftIsNudgedNotSeeked() {
        player.position = 97.0
        val t = settle(100.0, T0)
        apply(serverTime = 100.0, nowMs = t)            // 3s behind
        assertEquals(emptyList<Double>(), player.seeks)
        assertEquals(1.0 + 0.04 + 0.015 * 3, player.rate.toDouble(), 1e-6)

        val ahead = SyncEngine()
        val p2 = FakePlayer().apply { position = 103.0 }
        player = p2; engine = ahead
        val t2 = settle(100.0, T0)
        apply(serverTime = 100.0, nowMs = t2)
        assertTrue("ahead: slowed down", p2.rate < 1f)
    }

    @Test fun noSpeedUpWhenTheBufferIsNearlyEmpty() {
        player.position = 97.0
        player.bufferedAheadSeconds = 2.0
        val t = settle(100.0, T0)
        apply(serverTime = 100.0, nowMs = t)
        assertEquals(1f, player.rate)
        assertEquals(emptyList<Double>(), player.seeks)
    }

    @Test fun nudgeKeepsGoingUntilNearlyExact() {
        player.position = 97.0
        var t = settle(100.0, T0)
        apply(serverTime = 100.0, nowMs = t)
        assertTrue(player.rate > 1f)
        player.position = 99.0                           // 1s behind: under the 2s start threshold
        t += 1_000
        apply(serverTime = 100.0, nowMs = t)
        assertTrue("still nudging", player.rate > 1f)
        player.position = 99.9                           // within 0.3s
        t += 1_000
        apply(serverTime = 100.0, nowMs = t)
        assertEquals(1f, player.rate)
    }

    @Test fun embedsOnlyCorrectBigDriftWithTheirOwnCooldown() {
        player.native = false
        player.position = 95.0
        apply(serverTime = 100.0, nowMs = T0)            // 5s: left alone
        assertEquals(emptyList<Double>(), player.seeks)
        player.position = 85.0
        apply(serverTime = 100.0, nowMs = T0 + 1_000)        // 15s: corrected
        assertEquals(listOf(100.0), player.seeks)
        player.position = 85.0
        apply(serverTime = 100.0, nowMs = T0 + 3_000)        // within 5s cooldown
        assertEquals(1, player.seeks.size)
    }
}
