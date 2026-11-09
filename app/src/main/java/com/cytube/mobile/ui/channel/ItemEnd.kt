package com.cytube.mobile.ui.channel

import com.cytube.mobile.net.MediaFrame

/**
 * What the app does when the channel's item ends, apart from
 * ChannelViewModel for testing.
 */
internal object ItemEnd {
    /** How soon after its start an item announced again counts as started
     *  over; see [isSameItemRestart]. */
    const val RESTART_WINDOW_SECONDS = 10.0

    /**
     * Whether this client should tell the server to move the channel on
     * (CyTubeClient.playNext) now that its player's item has ended. Only as
     * the channel's leader: without a leader the server moves on by itself,
     * with one it waits for the leader's player, as the website's does. And
     * only while showing the channel's own item, connected: not a personal
     * pick, nor in Compatibility View (whose page does it itself), and not
     * for an item the channel has already left — a moderator skipping just as
     * it ended mustn't make it skip the next one too. And only when the
     * player showing the item says it has ended (PlayerHandle.hasEnded): an
     * "ended" coming late from a player being taken down, for what it held
     * before, isn't the end of the item now showing.
     */
    fun leaderShouldPlayNext(
        isLeader: Boolean,
        personalPick: Boolean,
        webMode: Boolean,
        connected: Boolean,
        playerHasEnded: Boolean,
        playerMediaId: String?,
        playerMediaType: String?,
        channelItem: MediaFrame?
    ): Boolean = isLeader && !personalPick && !webMode && connected && playerHasEnded && channelItem != null &&
        playerMediaId == channelItem.id && playerMediaType == channelItem.type

    /**
     * Whether the channel announcing the item the player already holds means
     * it started that item over (a one-item playlist going round again):
     * the player stopped at its end, and the item's time is back near the
     * start. The server also sends the current item again as it is (after
     * "playerReady", say), and near the end of an item that has finished,
     * that mustn't start it over: hence "near the start", within
     * [RESTART_WINDOW_SECONDS] and the first half of the item. A negative
     * time (the server's lead-in) is near the start too.
     */
    fun isSameItemRestart(playerHasEnded: Boolean, playerHoldsItem: Boolean, itemTime: Double, itemSeconds: Int): Boolean =
        playerHasEnded && playerHoldsItem && itemSeconds > 0 &&
            itemTime < minOf(RESTART_WINDOW_SECONDS, itemSeconds / 2.0)
}
