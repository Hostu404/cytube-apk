package com.cytube.mobile.net

import com.cytube.mobile.player.PlayerHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Joining a busy channel 1 h 40 min into a 2-hour film: a big file (~4 GB at
 * 4.5 Mbps) on a server a hundred people are streaming from at once, so every
 * new request is slow to start and the download is only a little faster than
 * the film — and sometimes slower.
 *
 * Reported on device as a long load followed by skipping, seeking and
 * stopping. The simulation (SimulatedPlayback.kt) shows why: the file took
 * 10 s+ to open at the room's time, so it arrived that far behind, jumped
 * forward (a second request and a second wait), and then played from a
 * buffer only a couple of seconds deep, which the next slow patch drained.
 */
class LargeFileJoinTest {

    private val bitrate = 4.5
    private val joinAt = 100 * 60.0

    private fun busyServer() = SimulatedExoPlayer(
        bitrateMbps = bitrate,
        throughputMbps = { bitrate * 1.25 },
        firstByteSeconds = 2.0,
        openExtraSeconds = 6.0
    )

    /** 20 s at 1.4x the film's bitrate, then 10 s at 0.8x, over and over. */
    private fun saggingServer() = SimulatedExoPlayer(
        bitrateMbps = bitrate,
        throughputMbps = { t -> bitrate * if ((t.toInt() % 30) >= 20) 0.8 else 1.4 },
        firstByteSeconds = 3.0,
        openExtraSeconds = 8.0
    )

    private fun fastServer() = SimulatedExoPlayer(
        bitrateMbps = bitrate,
        throughputMbps = { bitrate * 3 },
        firstByteSeconds = 0.5,
        openExtraSeconds = 1.0
    )

    private fun join(player: SimulatedExoPlayer, memory: LeadMemory, planStart: Boolean = true, seconds: Double = 300.0) =
        SimulatedJoin(player, joinAt, engine = SyncEngine(memory), planStart = planStart).also { it.run(seconds) }

    @Test fun theOldWayArrivesBehindAndHasToJumpAgain() {
        // What was seen on device, reproduced: opened at the room's time,
        // then a catch-up jump — a second request and a second wait.
        val j = join(busyServer(), LeadMemory(), planStart = false)
        assertTrue(j.player.firstReadyAt > 9.0)
        assertEquals(2, j.player.requests)
        assertEquals(1, j.player.seeks)
    }

    @Test fun joiningTheSameServerAgainLandsInTimeWithOneRequest() {
        val memory = LeadMemory()
        join(busyServer(), memory)            // measures what opening costs here
        val again = join(busyServer(), memory)
        assertEquals(1, again.player.requests)
        assertEquals(0, again.player.seeks)
        assertEquals(0, again.player.stalls)
        assertTrue("in sync from ${again.inSyncFrom()}", again.inSyncFrom() <= 25.0)
    }

    @Test fun aServerThatSlowsDownNowAndThenDoesNotRunDryAfterJoining() {
        val memory = LeadMemory()
        val first = join(saggingServer(), memory)
        val again = join(saggingServer(), memory)
        // The old way, for comparison: a jump, then running dry soon after.
        val old = join(saggingServer(), LeadMemory(), planStart = false)
        assertTrue(old.player.requests >= 2)

        assertTrue(first.player.requests <= 2)
        assertEquals(1, again.player.requests)
        assertEquals(0, again.player.stalls)
        assertTrue("in sync from ${again.inSyncFrom()}", again.inSyncFrom() <= 20.0)
    }

    @Test fun aFastServerIsInSyncWithinSecondsWithoutJumping() {
        val memory = LeadMemory()
        val first = join(fastServer(), memory)
        assertEquals(0, first.player.seeks)
        assertTrue("in sync from ${first.inSyncFrom()}", first.inSyncFrom() <= 10.0)
        // ...and learns it needn't wait as long next time.
        assertTrue(memory.openLead(LeadMemory.serverOf(SimulatedJoin.STREAM_URL)) < LeadMemory.DEFAULT_OPEN_LEAD_SECONDS)
        val again = join(fastServer(), memory)
        assertTrue("in sync from ${again.inSyncFrom()}", again.inSyncFrom() <= first.inSyncFrom())
    }

    @Test fun aServerSlowerThanTheFilmDoesNotSeekInALoop() {
        // Can't be kept in sync at all (it downloads slower than it plays),
        // but corrections must stay spaced out rather than chain one into
        // the next.
        val p = SimulatedExoPlayer(bitrate, { bitrate * 0.9 }, 3.0, 8.0)
        join(p, LeadMemory(), seconds = 600.0)
        val seekTimes = p.log.filter { " seek " in it }.map { it.trim().substringBefore("s").toDouble() }
        assertTrue("${seekTimes.size} seeks", seekTimes.size <= 10)
        assertTrue(seekTimes.zipWithNext().all { (a, b) -> b - a >= 10.0 })
    }
}

/** The hold on its own, one call at a time. */
class SyncEngineHoldTest {

    private class Player : PlayerHandle {
        override var mediaId: String? = "movie"
        override val mediaType: String? = "fi"
        override val mediaLengthSeconds: Int = 7200
        override var isPaused = false
        override var isBuffering = false
        override var isReleased = false
        override val bufferedAheadSeconds: Double = 30.0
        var position = 0.0
        val seeks = mutableListOf<Double>()
        override fun play() { isPaused = false }
        override fun pause() { isPaused = true }
        override fun seekTo(seconds: Double) { seeks += seconds; position = seconds }
        override suspend fun currentTimeSeconds() = position
        override fun setVolume(volume: Float) {}
        override fun release() {}
    }

    private val t0 = 1_000_000L
    private val memory = LeadMemory()
    private val engine = SyncEngine(memory)
    private val player = Player()

    private fun apply(server: Double, nowMs: Long, grace: Boolean = false) {
        val block: suspend () -> Unit = {
            engine.apply(player, TimeUpdate(server, false), player.mediaId, 2.0, grace, nowMs)
        }
        block.startCoroutine(Continuation(EmptyCoroutineContext) { it.getOrThrow() })
    }

    @Test fun planStartOpensAheadOnlyWhenJoiningPartway() {
        val url = "https://cdn.example.com/a.mp4"
        assertEquals(6006.0, engine.planStart("movie", url, 6000.0, 7200, roomPaused = false), 1e-9)
        assertEquals(3.0, engine.planStart("movie", url, 3.0, 7200, roomPaused = false), 1e-9)
        assertEquals(6000.0, engine.planStart("movie", url, 6000.0, 7200, roomPaused = true), 1e-9)
        assertEquals(6000.0, engine.planStart("live", url, 6000.0, 0, roomPaused = false), 1e-9)
        assertEquals(7199.0, engine.planStart("movie", url, 7196.0, 7200, roomPaused = false), 1e-9)
    }

    @Test fun landingAheadWaitsForTheRoomThenPlays() {
        player.position = engine.planStart("movie", "https://x.org/a.mp4", 6000.0, 7200, false)  // 6006
        apply(server = 6002.0, nowMs = t0, grace = true)   // first frame: 4 s early
        assertTrue(player.isPaused)
        apply(server = 6004.0, nowMs = t0 + 2_000)
        assertTrue(player.isPaused)
        apply(server = 6005.7, nowMs = t0 + 3_700)          // close enough: go
        assertFalse(player.isPaused)
        assertTrue(player.seeks.isEmpty())
        // Opening took 2 s less than the lead: learned (6 + 0.75 × -4 = 3).
        assertEquals(3.0, memory.openLead("x.org"), 1e-9)
    }

    @Test fun landingBehindLearnsALongerLeadAndDoesNotWait() {
        player.position = engine.planStart("movie", "https://x.org/a.mp4", 6000.0, 7200, false)  // 6006
        apply(server = 6010.0, nowMs = t0, grace = true)    // 4 s late
        assertFalse(player.isPaused)
        assertEquals(9.0, memory.openLead("x.org"), 1e-9)
    }

    @Test fun anOvershootingCatchUpJumpWaitsInsteadOfJumpingBack() {
        player.position = 5990.0
        apply(server = 5990.0, nowMs = t0)
        apply(server = 6000.0, nowMs = t0 + 2_500)          // 10 s behind: jump
        assertEquals(listOf(6001.0), player.seeks)
        player.position = 6008.0                            // landed on a keyframe well past it
        apply(server = 6002.0, nowMs = t0 + 4_000)
        assertTrue(player.isPaused)
        assertEquals(1, player.seeks.size)
        apply(server = 6008.0, nowMs = t0 + 10_000)
        assertFalse(player.isPaused)
    }

    private fun holdAhead() {
        player.position = engine.planStart("movie", "https://x.org/a.mp4", 6000.0, 7200, false)  // 6006
        apply(server = 6002.0, nowMs = t0)
        assertTrue(engine.isHolding && player.isPaused)
    }

    @Test fun standingDownReleasesAHeldPlayer() {
        // Becoming leader or turning sync off mid-hold must not leave it
        // paused (a leader's paused player pauses the whole room).
        holdAhead()
        engine.standDown(player, resume = true)
        assertFalse(engine.isHolding)
        assertFalse(player.isPaused)
    }

    @Test fun standingDownForTheUsersOwnPauseKeepsItPaused() {
        holdAhead()
        engine.standDown(player, resume = false)
        assertFalse(engine.isHolding)
        assertTrue(player.isPaused)
    }

    @Test fun scrubbingDuringAHoldEndsIt() {
        holdAhead()
        player.position = 5990.0                  // moved by hand
        apply(server = 6003.0, nowMs = t0 + 1_000)
        assertFalse(engine.isHolding)
        assertFalse(player.isPaused)
    }

    @Test fun theRoomJumpingBackEndsAHold() {
        holdAhead()
        apply(server = 5995.0, nowMs = t0 + 1_000) // the leader went back
        assertFalse(engine.isHolding)
        assertFalse(player.isPaused)
    }

    @Test fun aNewItemDropsTheOldItemsHold() {
        holdAhead()
        player.mediaId = "next"                   // loaded while nothing was checking
        player.position = 30.0
        apply(server = 30.0, nowMs = t0 + 1_000)
        assertFalse(engine.isHolding)
        assertFalse(player.isPaused)
    }

    @Test fun aLandingAcrossAServerPauseTeachesNothing() {
        player.position = engine.planStart("movie", "https://x.org/a.mp4", 6000.0, 7200, false)
        val block: suspend () -> Unit = {
            engine.apply(player, TimeUpdate(6001.0, true), player.mediaId, 2.0, false, t0)
        }
        block.startCoroutine(Continuation(EmptyCoroutineContext) { it.getOrThrow() })
        apply(server = 6001.0, nowMs = t0 + 30_000)
        assertEquals(LeadMemory.DEFAULT_OPEN_LEAD_SECONDS, memory.openLead("x.org"), 1e-9)
    }

    @Test fun nearTheEndTheLeadIsCutShortAndLearnedAsSuch() {
        player.position = engine.planStart("movie", "https://x.org/a.mp4", 7196.0, 7200, false)  // 7199: 3 s lead
        apply(server = 7197.0, nowMs = t0)          // landed 2 s ahead
        assertEquals(3.0 + 0.75 * -2.0, memory.openLead("x.org"), 1e-9)
        // Past length - 1 there is no room for any lead: open at the room's time.
        assertEquals(7199.5, SyncEngine(LeadMemory()).planStart("m", "https://x.org/a", 7199.5, 7200, false), 1e-9)
    }

    @Test fun serverKeysGroupAServicesHosts() {
        assertEquals("googlevideo.com", LeadMemory.serverOf("https://rr3---sn-abc.googlevideo.com/videoplayback?x=1"))
        assertEquals("example.org", LeadMemory.serverOf("http://user@files.example.org:8080/a.mp4"))
        assertEquals("10.0.0.2", LeadMemory.serverOf("http://10.0.0.2/a.mp4"))
        assertEquals("", LeadMemory.serverOf("not a url"))
    }

    @Test fun memoryRoundTripsAndStaysBounded() {
        val m = LeadMemory()
        repeat(60) { m.learnOpenLead("s$it.com", it / 10.0) }
        assertEquals(50, m.snapshot().size)
        val copy = LeadMemory().apply { restore(m.snapshot()) }
        assertEquals(5.9, copy.openLead("s59.com"), 1e-9)
        assertEquals(LeadMemory.DEFAULT_OPEN_LEAD_SECONDS, copy.openLead("s0.com"), 1e-9)
    }
}
