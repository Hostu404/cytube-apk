package com.cytube.mobile.ui.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings' Nico speed scales how long a comment takes to cross, longer comments still faster. */
class NicoSpeedTest {
    @Test fun normalSpeedIsTheWebsites() {
        assertEquals(nekoDurationMs(1_000f), nekoDurationMs(1_000f, 1f))
    }

    @Test fun twiceAsFastCrossesInHalfTheTimeHalfAsFastInTwice() {
        for (distance in listOf(800f, 3_000f, 20_000f)) {
            val normal = nekoDurationMs(distance)
            assertEquals(normal / 2.0, nekoDurationMs(distance, 2f).toDouble(), 1.0)
            assertEquals(normal * 2.0, nekoDurationMs(distance, 0.5f).toDouble(), 1.0)
        }
    }

    @Test fun aLongerCommentStillGoesFaster() {
        // Speed is distance over time: a long one covers more ground in no more than twice the time.
        val short = 1_000f / nekoDurationMs(1_000f, 0.5f)
        val long = 6_000f / nekoDurationMs(6_000f, 0.5f)
        assertTrue(long > short)
    }
}
