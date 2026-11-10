package com.cytube.mobile.ui.channel

import androidx.compose.ui.input.key.Key
import com.cytube.mobile.net.PlaylistItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TV's own playback controls with sync off (TvTransport), and where its
 *  playlist puts the remote's focus (TvPlaylistFocus). */
class TvTransportTest {

    private fun transport(position: Double = 100.0, length: Int = 600, paused: Boolean = false, atMs: Long = 0L) =
        TvTransport().apply { onStatus(PlaybackStatus(position, length, paused), atMs) }

    @Test fun thePositionMovesOnWhilePlaying() {
        val t = transport(position = 100.0, atMs = 1_000)
        assertEquals(102.5, t.positionAt(3_500)!!, 0.001)
    }

    @Test fun andStaysPutWhilePaused() {
        val t = transport(position = 100.0, paused = true, atMs = 1_000)
        assertEquals(100.0, t.positionAt(9_000)!!, 0.001)
    }

    @Test fun rightAndLeftSeekTenSeconds() {
        val t = transport(position = 100.0, paused = true)
        assertEquals(110.0, t.seekBy(TvTransport.SEEK_STEP_SECONDS, 10)!!, 0.001)
        assertEquals(100.0, t.seekBy(-TvTransport.SEEK_STEP_SECONDS, 20)!!, 0.001)
    }

    // The remote repeats a held key: each step goes on from the last one,
    // not from wherever the player has got to by then.
    @Test fun holdingAKeyKeepsGoing() {
        val t = transport(position = 100.0, paused = true)
        var at = 0.0
        for (i in 1..5) at = t.seekBy(TvTransport.SEEK_STEP_SECONDS, i * 50L)!!
        assertEquals(150.0, at, 0.001)
        // The player's answer from before it got there doesn't undo it.
        t.onStatus(PlaybackStatus(101.0, 600, paused = true), 400)
        assertEquals(150.0, t.positionAt(450)!!, 0.001)
        // Later, the player's own time again.
        t.onStatus(PlaybackStatus(150.2, 600, paused = true), 5_000)
        assertEquals(150.2, t.positionAt(5_000)!!, 0.001)
    }

    @Test fun seeksStayWithinTheVideo() {
        val start = transport(position = 4.0, paused = true)
        assertEquals(0.0, start.seekBy(-10.0, 10)!!, 0.001)
        // Short of the very end, which would end it.
        val end = transport(position = 595.0, paused = true)
        assertEquals(599.0, end.seekBy(10.0, 10)!!, 0.001)
    }

    @Test fun liveMediaAndNoPlayerHaveNothingToSeek() {
        assertNull(transport(length = 0).seekBy(10.0, 10))
        assertNull(TvTransport().seekBy(10.0, 10))
        assertNull(TvTransport().positionAt(10))
    }

    // Up while paused shows the bar; playing, it goes a few seconds after the
    // last control.
    @Test fun theBarShowsWhilePausedAndForAWhileAfterAControl() {
        val playing = transport(paused = false)
        assertFalse(playing.visibleAt(0))
        playing.touch(1_000)
        assertTrue(playing.visibleAt(1_000 + TvTransport.SHOW_MS - 1))
        assertFalse(playing.visibleAt(1_000 + TvTransport.SHOW_MS))
        assertTrue(transport(paused = true).visibleAt(99_999))
    }

    // A pause shows at once, where the video stopped.
    @Test fun aToggleShowsAtOnce() {
        val t = transport(position = 100.0, paused = false, atMs = 0)
        t.onToggled(playing = false, nowMs = 2_000)
        assertTrue(t.status!!.paused)
        assertEquals(102.0, t.positionAt(9_000)!!, 0.001)
        assertTrue(t.visibleAt(9_000))
        t.onToggled(playing = true, nowMs = 9_000)
        assertEquals(103.0, t.positionAt(10_000)!!, 0.001)
    }

    @Test fun resetForgetsEverything() {
        val t = transport(paused = true)
        t.reset()
        assertNull(t.status)
        assertFalse(t.visibleAt(0))
    }

    @Test fun clockReadsAsUsual() {
        assertEquals("0:00", TvTransport.clock(0.0))
        assertEquals("0:42", TvTransport.clock(42.4))
        assertEquals("12:05", TvTransport.clock(725.0))
        assertEquals("1:02:09", TvTransport.clock(3729.0))
        assertEquals("0:00", TvTransport.clock(Double.NaN))
        assertEquals("0:00", TvTransport.clock(-5.0))
    }

    // The remote's keys on the video screen (TvKeys).

    @Test fun okAndPlayPauseToggleOncePerPress() {
        for (key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause)) {
            assertEquals(TvKeyAction.TOGGLE, TvKeys.actionFor(key, pressed = true, repeatCount = 0))
            // Held: no flipping back and forth.
            assertEquals(TvKeyAction.NONE, TvKeys.actionFor(key, pressed = true, repeatCount = 3))
            assertEquals(TvKeyAction.NONE, TvKeys.actionFor(key, pressed = false, repeatCount = 0))
        }
        assertEquals(TvKeyAction.PLAY, TvKeys.actionFor(Key.MediaPlay, pressed = true, repeatCount = 0))
        assertEquals(TvKeyAction.PAUSE, TvKeys.actionFor(Key.MediaPause, pressed = true, repeatCount = 0))
    }

    @Test fun leftAndRightSeekOnEveryPressRepeatsIncluded() {
        for (repeat in listOf(0, 1, 7)) {
            assertEquals(TvKeyAction.BACK, TvKeys.actionFor(Key.DirectionLeft, pressed = true, repeatCount = repeat))
            assertEquals(TvKeyAction.FORWARD, TvKeys.actionFor(Key.DirectionRight, pressed = true, repeatCount = repeat))
        }
        assertEquals(TvKeyAction.BACK, TvKeys.actionFor(Key.MediaRewind, pressed = true, repeatCount = 0))
        assertEquals(TvKeyAction.FORWARD, TvKeys.actionFor(Key.MediaFastForward, pressed = true, repeatCount = 0))
        // The release does nothing, but is still the video screen's.
        assertEquals(TvKeyAction.NONE, TvKeys.actionFor(Key.DirectionRight, pressed = false, repeatCount = 0))
    }

    @Test fun upShowsTheBar() {
        assertEquals(TvKeyAction.SHOW, TvKeys.actionFor(Key.DirectionUp, pressed = true, repeatCount = 0))
    }

    // Down opens the playlist (handled before these), and Back leaves: not
    // playback keys.
    @Test fun otherKeysAreNotPlaybackKeys() {
        assertNull(TvKeys.actionFor(Key.DirectionDown, pressed = true, repeatCount = 0))
        assertNull(TvKeys.actionFor(Key.Back, pressed = true, repeatCount = 0))
        assertNull(TvKeys.actionFor(Key.A, pressed = true, repeatCount = 0))
    }

    private fun item(uid: Int, type: String = "yt") = PlaylistItem(uid, "Item $uid", "3:00", type, "id$uid", "someone")

    // The playlist opens on what's playing, so OK plays on from there.
    @Test fun thePlaylistOpensOnTheItemPlaying() {
        val items = listOf(item(1), item(2), item(3))
        assertEquals(2, TvPlaylistFocus.openingRow(items, playingUid = 3))
    }

    @Test fun elseOnTheFirstItemThatCanBePicked() {
        // A custom embed can't play on its own (see canResolveIndependently).
        val items = listOf(item(1, type = "cu"), item(2), item(3))
        assertEquals(1, TvPlaylistFocus.openingRow(items, playingUid = -1))
        assertEquals(1, TvPlaylistFocus.openingRow(items, playingUid = 99))
        assertEquals(1, TvPlaylistFocus.openingRow(items, playingUid = 1))
    }

    // Nothing to pick: the search box has the focus instead.
    @Test fun nothingToPickMeansNoRow() {
        assertEquals(-1, TvPlaylistFocus.openingRow(emptyList(), playingUid = 1))
        assertEquals(-1, TvPlaylistFocus.openingRow(listOf(item(1, type = "cu")), playingUid = 1))
    }
}
