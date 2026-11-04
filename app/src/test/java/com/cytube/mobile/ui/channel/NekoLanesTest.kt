package com.cytube.mobile.ui.channel

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NekoLanesTest {
    private val screen = 1000f
    private val gap = 24f
    private val stagger = 75L

    /** A comment that set off at [at] at [speed] px/ms, [width] px wide. */
    private fun occupant(width: Float, speed: Float, at: Long = 0) =
        LaneOccupant(spawnAtMs = at, speedPxPerMs = speed, widthPx = width,
            clearAtMs = at + ((screen + width) / speed).toLong())

    /** A comment [width] px wide waiting to fly. Every comment crosses in
     *  the same time, so a longer one is faster. */
    private fun candidate(width: Float) = LaneCandidate(width, (screen + width) / CROSSING_MS)

    /** [c] setting off at [at], as the overlay records it. */
    private fun flying(c: LaneCandidate, at: Long) =
        LaneOccupant(spawnAtMs = at, speedPxPerMs = c.speedPxPerMs, widthPx = c.widthPx, clearAtMs = at + CROSSING_MS)

    @Test fun waitsUntilThePreviousCommentIsClearOfTheEdge() {
        val last = occupant(width = 176f, speed = 0.3f)          // needs 200 px: 667 ms
        assertEquals(667L, laneWaitMs(last, 0, 0.3f, gap, screen))
        assertEquals(0L, laneWaitMs(last, 700, 0.3f, gap, screen))
    }

    @Test fun aFasterCommentWaitsUntilItCantCatchUp() {
        // Long enough out of the edge, but a much faster comment would
        // still catch it before it leaves: the wait says for how long,
        // instead of the lane being re-checked every frame.
        val last = occupant(width = 76f, speed = 0.25f)          // clears at 4304 ms
        val now = 1_000L                                          // entry fine by now
        val wait = laneWaitMs(last, now, candidateSpeedPxPerMs = 0.8f, gapPx = gap, screenWidthPx = screen)
        assertTrue("wait $wait", wait > 0)
        // After that wait it's allowed, and not a moment before.
        assertEquals(0L, laneWaitMs(last, now + wait, 0.8f, gap, screen))
        assertTrue(laneWaitMs(last, now + wait - 2, 0.8f, gap, screen) > 0)
    }

    @Test fun aFasterCommentNeverRunsIntoTheOneAhead() {
        // Set off as soon as the rule allows, the faster comment's front is
        // still at least the gap behind the slower one's back by the time
        // that one leaves, and so all the way across. (The rule used to let a
        // 100 px comment here start 125 ms sooner, and catch up with it
        // before it left.)
        val last = occupant(width = 76f, speed = 0.25f)
        val speed = 0.8f
        val start = 1_000L + laneWaitMs(last, 1_000L, speed, gap, screen)
        var t = start
        while (t <= last.clearAtMs) {
            val back = screen - last.speedPxPerMs * (t - last.spawnAtMs) + last.widthPx
            val front = screen - speed * (t - start)
            assertTrue("at $t ms only ${front - back} px apart", front - back >= gap - 0.5f)
            t += 5
        }
    }

    @Test fun aSlowerCommentOnlyNeedsTheEntryGap() {
        val last = occupant(width = 76f, speed = 0.5f)
        assertEquals(0L, laneWaitMs(last, 1_000, 0.3f, gap, screen))
    }

    @Test fun lanesArePickedAtRandomNotInOrder() {
        val lanes = NekoLanes(Random(42))
        val picked = ArrayList<Int>()
        var now = 0L
        repeat(200) {
            val c = candidate(150f)
            val pick = lanes.pick(now, listOf(c), LANES, gap, screen, stagger) as LanePick.Fly
            lanes.add(pick.lane, flying(c, now))
            picked += pick.lane
            now += 5_000                 // each long gone before the next: every lane empty
        }
        // Every lane gets used, but not one after another down the screen
        // (by chance, about 1 in 11 would go to the next lane down)...
        assertEquals((0 until LANES).toSet(), picked.toSet())
        val nextDown = picked.zipWithNext().count { (a, b) -> b == (a + 1) % LANES }
        assertTrue("$nextDown of 199 went to the next lane down", nextDown < 50)
        // ...and never the same lane twice in a row.
        assertTrue(picked.zipWithNext().none { (a, b) -> a == b })
    }

    @Test fun aShortCommentGoesAheadOfALongOneThatHasToWait() {
        val lanes = NekoLanes(Random(1))
        // Two lanes, each with a short comment just clear of the edge. A long
        // one (fast) would catch up with either, so it has to wait, and lane
        // 0 is the one it'll get first. A short one could go in either now.
        val short = candidate(76f)
        val inLane0 = flying(short, 0)
        lanes.add(0, inLane0)
        lanes.add(1, flying(short, 200))
        val long = candidate(900f)
        val now = 700L
        val longWait = laneWaitMs(inLane0, now, long.speedPxPerMs, gap, screen)
        assertTrue(longWait > stagger)

        // The short one goes first, in lane 1: lane 0 is kept for the long one...
        assertEquals(LanePick.Fly(index = 1, lane = 1), lanes.pick(now, listOf(long, short), 2, gap, screen, stagger))
        lanes.add(1, flying(short, now))
        // ...which isn't kept waiting any longer for it.
        assertTrue(lanes.pick(now + longWait - 1, listOf(long), 2, gap, screen, stagger) is LanePick.Wait)
        assertEquals(LanePick.Fly(index = 0, lane = 0), lanes.pick(now + longWait, listOf(long), 2, gap, screen, stagger))
    }

    @Test fun nothingGoesAheadWhenTheOldestIsAboutToGo() {
        val lanes = NekoLanes(Random(1))
        val medium = candidate(300f)
        val short = candidate(76f)
        // Lane 0 takes the medium one in 50 ms or so; lane 1 would take the
        // short one now, but the medium one only much later.
        val inLane0 = flying(medium, 0)
        lanes.add(0, inLane0)
        lanes.add(1, flying(short, 500))
        val now = 886L
        val wait = laneWaitMs(inLane0, now, medium.speedPxPerMs, gap, screen)
        assertTrue("wait $wait", wait in 1..stagger)
        // The short one going first would hold the medium one up by the
        // stagger, so nothing goes: it waits for the medium one.
        assertEquals(LanePick.Wait(maxOf(16L, wait)), lanes.pick(now, listOf(medium, short), 2, gap, screen, stagger))
    }

    @Test fun aFloodNeverOverlapsAndUsesEveryLane() {
        // Short and long messages arriving faster than they can all fly, run
        // the way the overlay's loop runs them: no comment ever runs into
        // another in its lane.
        val traffic = Random(7)
        val lanes = NekoLanes(Random(8))
        val waiting = ArrayList<LaneCandidate>()
        val flown = ArrayList<Pair<Int, LaneOccupant>>()
        var now = 0L
        var nextArrival = 0L
        while (now < 60_000L) {
            while (nextArrival <= now) {
                val width = if (traffic.nextInt(3) == 0) 400f + traffic.nextInt(900) else 40f + traffic.nextInt(300)
                waiting += candidate(width)
                while (waiting.size > 30) waiting.removeAt(0)        // NEKO_MAX_QUEUE
                nextArrival += traffic.nextLong(20, 250)              // about 7 a second
            }
            if (waiting.isEmpty()) { now = nextArrival; continue }
            when (val pick = lanes.pick(now, waiting, LANES, gap, screen, stagger)) {
                is LanePick.Fly -> {
                    val o = flying(waiting.removeAt(pick.index), now)
                    lanes.add(pick.lane, o)
                    flown += pick.lane to o
                    now += stagger
                }
                is LanePick.Wait -> now = minOf(now + pick.ms, nextArrival)
            }
        }
        assertTrue("only ${flown.size} flew", flown.size > 200)
        assertEquals((0 until LANES).toSet(), flown.map { it.first }.toSet())
        for ((i, ahead) in flown.withIndex()) {
            for ((lane, behind) in flown.subList(i + 1, flown.size)) {
                if (lane != ahead.first || behind.spawnAtMs >= ahead.second.clearAtMs) continue
                val a = ahead.second
                var t = behind.spawnAtMs
                while (t <= a.clearAtMs) {
                    val back = screen - a.speedPxPerMs * (t - a.spawnAtMs) + a.widthPx
                    val front = screen - behind.speedPxPerMs * (t - behind.spawnAtMs)
                    assertTrue("lane $lane at $t ms: only ${front - back} px apart", front - back >= gap - 0.5f)
                    t += 5
                }
            }
        }
    }

    private companion object {
        const val LANES = 12
        const val CROSSING_MS = 3_755L
    }
}
