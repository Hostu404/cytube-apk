package com.cytube.mobile.ui.channel

import org.junit.Assert.assertEquals
import org.junit.Test

/** Hybrid chat on TV, with the remote: what ↓ and Back do on the video. */
class TvHybridChatTest {

    @Test fun downWithoutHybridChatIsAsEver() {
        assertEquals(TvDown.FULL_CHAT, tvDown(synced = true, hybridOn = false, hybridHidden = false))
        assertEquals(TvDown.PLAYLIST, tvDown(synced = false, hybridOn = false, hybridHidden = false))
    }

    // With hybrid chat on, ↓ is only ever hybrid chat's: its box, or put
    // away, bringing it back. Never the chat screen or the playlist.
    @Test fun downIsOnlyHybridChats() {
        assertEquals(TvDown.MESSAGE_BOX, tvDown(synced = true, hybridOn = true, hybridHidden = false))
        assertEquals(TvDown.SHOW_CHAT, tvDown(synced = true, hybridOn = true, hybridHidden = true))
    }

    // With sync off, hybrid chat is off (ChannelScreen: TV has no chat then),
    // so ↓ is the playlist's as ever, whatever's in Settings.
    @Test fun syncOffIsThePlaylist() {
        assertEquals(TvDown.PLAYLIST, tvDown(synced = false, hybridOn = false, hybridHidden = false))
        assertEquals(TvDown.PLAYLIST, tvDown(synced = false, hybridOn = false, hybridHidden = true))
    }

    @Test fun backOneStepAtATime() {
        assertEquals(TvBack.CLOSE_FULL_CHAT, tvBack(fullChat = true, playlist = false, inBox = false, hybridShowing = true))
        assertEquals(TvBack.CLOSE_PLAYLIST, tvBack(fullChat = false, playlist = true, inBox = false, hybridShowing = true))
        assertEquals(TvBack.LEAVE_BOX, tvBack(fullChat = false, playlist = false, inBox = true, hybridShowing = true))
        assertEquals(TvBack.HIDE_CHAT, tvBack(fullChat = false, playlist = false, inBox = false, hybridShowing = true))
        assertEquals(TvBack.LEAVE_CHANNEL, tvBack(fullChat = false, playlist = false, inBox = false, hybridShowing = false))
    }
}
