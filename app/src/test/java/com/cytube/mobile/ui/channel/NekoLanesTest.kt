package com.cytube.mobile.ui.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NekoLanesTest {
    private val screen = 1000f
    private val gap = 24f

    /** A comment that set off at 0 at [speed] px/ms, [width] px wide. */
    private fun occupant(width: Float, speed: Float) =
        LaneOccupant(spawnAtMs = 0, speedPxPerMs = speed, widthPx = width,
            clearAtMs = ((screen + width) / speed).toLong())

    @Test fun waitsUntilThePreviousCommentIsClearOfTheEdge() {
        val last = occupant(width = 176f, speed = 0.3f)          // needs 200 px: 667 ms
        assertEquals(667L, laneWaitMs(last, 0, 100f, 0.3f, gap, screen))
        assertEquals(0L, laneWaitMs(last, 700, 100f, 0.3f, gap, screen))
    }

    @Test fun aFasterCommentWaitsUntilItCantCatchUp() {
        // Long enough out of the edge, but a much faster comment would
        // still catch it before it leaves: the wait now says for how long,
        // instead of the lane being re-checked every frame.
        val last = occupant(width = 76f, speed = 0.25f)          // clears at 4304 ms
        val now = 1_000L                                          // entry fine by now
        val wait = laneWaitMs(last, now, candidateWidthPx = 100f, candidateSpeedPxPerMs = 0.8f, gapPx = gap, screenWidthPx = screen)
        assertTrue("wait $wait", wait > 0)
        // After that wait it's allowed, and not a moment before.
        assertEquals(0L, laneWaitMs(last, now + wait, 100f, 0.8f, gap, screen))
        assertTrue(laneWaitMs(last, now + wait - 2, 100f, 0.8f, gap, screen) > 0)
    }

    @Test fun aSlowerCommentOnlyNeedsTheEntryGap() {
        val last = occupant(width = 76f, speed = 0.5f)
        assertEquals(0L, laneWaitMs(last, 1_000, 100f, 0.3f, gap, screen))
    }
}
