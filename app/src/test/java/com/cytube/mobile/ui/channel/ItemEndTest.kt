package com.cytube.mobile.ui.channel

import com.cytube.mobile.net.MediaFrame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemEndTest {
    private fun item(id: String = "abc", type: String = "yt", seconds: Int = 600) = MediaFrame(
        id = id, title = "t", seconds = seconds, duration = "10:00", type = type,
        currentTime = 0.0, paused = false, direct = emptyList(), embedSrc = null, scuri = null
    )

    private fun leader(
        isLeader: Boolean = true,
        personalPick: Boolean = false,
        webMode: Boolean = false,
        connected: Boolean = true,
        playerHasEnded: Boolean = true,
        playerId: String? = "abc",
        playerType: String? = "yt",
        channelItem: MediaFrame? = item()
    ) = ItemEnd.leaderShouldPlayNext(isLeader, personalPick, webMode, connected, playerHasEnded, playerId, playerType, channelItem)

    // With a leader the server waits for the leader's player to say the
    // video ended (playlist.js; the website's videojs/youtube players send
    // "playNext"): the leader moves the channel on.
    @Test fun theLeaderMovesTheChannelOnWhenItsVideoEnds() {
        assertTrue(leader())
    }

    @Test fun nobodyElseDoesTheServerMovesOnByItself() {
        assertFalse(leader(isLeader = false))
    }

    @Test fun notForAPersonalPickCompatibilityViewOrWhileDisconnected() {
        assertFalse(leader(personalPick = true))
        assertFalse(leader(webMode = true))
        assertFalse(leader(connected = false))
    }

    // A moderator skipped as it ended: the channel is already on the next
    // item, which mustn't be skipped too.
    @Test fun notForAnItemTheChannelHasAlreadyLeft() {
        assertFalse(leader(playerId = "old"))
        assertFalse(leader(playerType = "fi"))
        assertFalse(leader(channelItem = null))
        assertFalse(leader(playerId = null, playerType = null))
    }

    // A late "ended" from a player being taken down, while the one now
    // showing the channel's item plays on: not that item's end.
    @Test fun onlyWhenThePlayerShowingTheItemHasItselfEnded() {
        assertFalse(leader(playerHasEnded = false))
    }

    @Test fun aOneItemPlaylistGoingRoundAgainStartsThePlayerOver() {
        // The server restarts it at 0 with a leader, at -3 (its lead-in) without.
        assertTrue(ItemEnd.isSameItemRestart(playerHasEnded = true, playerHoldsItem = true, itemTime = 0.0, itemSeconds = 600))
        assertTrue(ItemEnd.isSameItemRestart(playerHasEnded = true, playerHoldsItem = true, itemTime = -3.0, itemSeconds = 600))
        assertTrue(ItemEnd.isSameItemRestart(playerHasEnded = true, playerHoldsItem = true, itemTime = 4.0, itemSeconds = 600))
    }

    // The server also sends the current item again as it is (after
    // "playerReady"), which at the end of a finished item is no restart.
    @Test fun theItemSentAgainAtItsEndIsNoRestart() {
        assertFalse(ItemEnd.isSameItemRestart(playerHasEnded = true, playerHoldsItem = true, itemTime = 601.0, itemSeconds = 600))
        assertFalse(ItemEnd.isSameItemRestart(playerHasEnded = true, playerHoldsItem = true, itemTime = 30.0, itemSeconds = 600))
        // A 5 s clip sent again at its end: past half of it.
        assertFalse(ItemEnd.isSameItemRestart(playerHasEnded = true, playerHoldsItem = true, itemTime = 6.0, itemSeconds = 5))
        assertTrue(ItemEnd.isSameItemRestart(playerHasEnded = true, playerHoldsItem = true, itemTime = 1.0, itemSeconds = 5))
    }

    @Test fun onlyForAPlayerThatFinishedThatItem() {
        assertFalse(ItemEnd.isSameItemRestart(playerHasEnded = false, playerHoldsItem = true, itemTime = 0.0, itemSeconds = 600))
        assertFalse(ItemEnd.isSameItemRestart(playerHasEnded = true, playerHoldsItem = false, itemTime = 0.0, itemSeconds = 600))
        // Live media never "ends" and restarts like this.
        assertFalse(ItemEnd.isSameItemRestart(playerHasEnded = true, playerHoldsItem = true, itemTime = 0.0, itemSeconds = 0))
    }
}
