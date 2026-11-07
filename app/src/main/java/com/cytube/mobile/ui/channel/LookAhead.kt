package com.cytube.mobile.ui.channel

import com.cytube.mobile.net.PlaylistItem

/**
 * The next video's stream looked up before it's due: a minute before the
 * current item ends, the playlist's next item, once, so that when the channel
 * moves on the player finds its stream already in the resolvers' cache (kept 5
 * minutes) rather than waiting a few seconds on YouTube or Google Drive.
 */
internal object LookAhead {
    const val SECONDS = 60

    /** The item to look up now, if any: the one after [currentUid] in
     *  [playlist], once [timeSeconds] is within [SECONDS] of the end of the
     *  current item ([lengthSeconds] long, 0 for one with no set end), and
     *  not [alreadyUid], the one last looked up. */
    fun next(playlist: List<PlaylistItem>, currentUid: Int, lengthSeconds: Int, timeSeconds: Double, alreadyUid: Int): PlaylistItem? {
        if (lengthSeconds <= 0 || lengthSeconds - timeSeconds > SECONDS) return null
        val at = playlist.indexOfFirst { it.uid == currentUid }
        if (at < 0) return null
        return playlist.getOrNull(at + 1)?.takeIf { it.uid != alreadyUid }
    }
}
