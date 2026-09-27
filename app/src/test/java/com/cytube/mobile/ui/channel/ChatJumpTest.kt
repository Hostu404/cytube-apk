package com.cytube.mobile.ui.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatJumpTest {

    @Test fun aQuietChatNeedsTheButtonOnlyWhenFarUp() {
        // 10 rows on screen: a thumb covers ~30 a second, so 5 s ≈ 150 rows.
        assertFalse(ChatJump.shouldShow(itemsBelow = 40, visibleItems = 10, arrivalsPerSecond = 0f))
        assertFalse(ChatJump.shouldShow(itemsBelow = 150, visibleItems = 10, arrivalsPerSecond = 0f))
        assertTrue(ChatJump.shouldShow(itemsBelow = 200, visibleItems = 10, arrivalsPerSecond = 0f))
    }

    @Test fun aFloodShowsItAsSoonAsYouScrollUp() {
        // 50 a second outruns a thumb: you'd never reach the bottom.
        assertEquals(Float.POSITIVE_INFINITY, ChatJump.secondsToBottom(5, 10, 50f))
        assertTrue(ChatJump.shouldShow(itemsBelow = 5, visibleItems = 10, arrivalsPerSecond = 50f))
    }

    @Test fun aBusyButCatchableChatCountsTheMovingBottom() {
        // 20 a second: closing at 10 rows a second, so 60 rows is 6 s away.
        assertEquals(6.0, (ChatJump.secondsToBottom(60, 10, 20f)).toDouble(), 1e-4)
        assertTrue(ChatJump.shouldShow(60, 10, 20f))
        assertFalse(ChatJump.shouldShow(40, 10, 20f))
    }

    @Test fun atTheBottomItNeverShows() {
        assertEquals(0f, ChatJump.secondsToBottom(0, 10, 100f))
        assertFalse(ChatJump.shouldShow(0, 10, 100f))
    }

    @Test fun arrivalRateIsOverTheLastThreeSecondsAndFallsAway() {
        val a = ChatArrivals()
        a.record(1_000, 30)
        a.record(2_000, 30)
        a.record(3_000, 30)
        assertEquals(30.0, (a.perSecond(3_000)).toDouble(), 1e-4)
        assertEquals(20.0, (a.perSecond(4_500)).toDouble(), 1e-4)   // the first batch has aged out
        assertEquals(0.0, (a.perSecond(10_000)).toDouble(), 1e-4)
    }
}
