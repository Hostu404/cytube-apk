package com.cytube.mobile.ui.channel

import androidx.compose.ui.unit.Density
import com.cytube.mobile.net.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Test

/** Hybrid chat's "Show chat" pill: how many people's messages came in since
 *  chat was put away; and its column's width, which the fullscreen controls'
 *  row keeps clear of (clearOfHybridChat). */
class HybridChatTest {

    private fun message(seq: Long, from: String = "someone") =
        ChatMessage(username = from, html = "hi", timestamp = 0L, addClass = null, shadow = false, seq = seq)

    @Test fun countsWhatCameAfter() {
        val chat = (1L..10L).map { message(it) }
        assertEquals(3, unreadSince(chat, since = 7))
        assertEquals(0, unreadSince(chat, since = 10))
        assertEquals(10, unreadSince(chat, since = 0))
    }

    // The server's own lines ("[server]", or no name) aren't anyone's.
    @Test fun leavesOutTheServersOwnLines() {
        val chat = listOf(message(1), message(2, from = "[server]"), message(3, from = ""), message(4))
        assertEquals(1, unreadSince(chat, since = 2))
        assertEquals(2, unreadSince(chat, since = 0))
    }

    @Test fun nothingInAnEmptyChat() {
        assertEquals(0, unreadSince(emptyList(), since = Long.MIN_VALUE))
    }

    // A third of the width (0.32), never under 240dp, never more than all.
    @Test fun columnWidth() {
        with(Density(1f)) {
            assertEquals(256, hybridColumnPx(800))
            assertEquals(240, hybridColumnPx(600))
            assertEquals(200, hybridColumnPx(200))
        }
        with(Density(2f)) {
            assertEquals(480, hybridColumnPx(1200))
            assertEquals(576, hybridColumnPx(1800))
        }
    }
}
