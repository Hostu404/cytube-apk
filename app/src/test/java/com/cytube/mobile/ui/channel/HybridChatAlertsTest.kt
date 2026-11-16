package com.cytube.mobile.ui.channel

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.cytube.mobile.net.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hybrid chat's Show chat alerts (a mention, a PM), Fade old messages'
 *  timing, and a physical keyboard's typing going into the message box. */
class HybridChatAlertsTest {

    private fun message(seq: Long, from: String = "someone", html: String = "hi", pm: Boolean = false, receivedAt: Long = 0L) =
        ChatMessage(username = from, html = html, timestamp = 0L, addClass = null, shadow = false, isPm = pm, seq = seq, receivedAt = receivedAt)

    private val me = buildMentionRegex("Me")

    @Test fun aMentionAfterChatWentAway() {
        val chat = listOf(message(1, html = "me first"), message(2), message(3, html = "hey me, look"))
        assertEquals(ChatAlerts(mentioned = true, pm = false), alertsSince(chat, since = 2, localName = "Me", mention = me))
        // Only what came after counts.
        assertEquals(ChatAlerts(mentioned = false, pm = false), alertsSince(chat.take(2), since = 1, localName = "Me", mention = me))
    }

    // A word that only has the name in it, or the name in a link's address,
    // isn't a mention, as chat's own highlight has it.
    @Test fun aNameInAWordOrALinksAddressIsNoMention() {
        val chat = listOf(
            message(1, html = "meme time"),
            message(2, html = "<a href=\"https://example.com/me\">a link</a>")
        )
        assertFalse(alertsSince(chat, since = 0, localName = "Me", mention = me).mentioned)
    }

    @Test fun yourOwnAndTheServersArentNews() {
        val chat = listOf(
            message(1, from = "me", html = "me again"),
            message(2, from = "Me", html = "to them", pm = true),
            message(3, from = "[server]", html = "me joined"),
            message(4, from = "", html = "me")
        )
        assertEquals(ChatAlerts(mentioned = false, pm = false), alertsSince(chat, since = 0, localName = "Me", mention = me))
    }

    @Test fun aPmFromSomeoneElse() {
        val chat = listOf(message(1), message(2, from = "alice", html = "psst", pm = true))
        assertEquals(ChatAlerts(mentioned = false, pm = true), alertsSince(chat, since = 1, localName = "Me", mention = me))
        // A PM saying your name is a PM, not a mention as well.
        val named = listOf(message(1, from = "alice", html = "me?", pm = true))
        assertEquals(ChatAlerts(mentioned = false, pm = true), alertsSince(named, since = 0, localName = "Me", mention = me))
    }

    @Test fun noNameNoMention() {
        val chat = listOf(message(1, html = "me"))
        assertFalse(alertsSince(chat, since = 0, localName = null, mention = null).mentioned)
    }

    // 15 s from when each came in.
    @Test fun oldOnceItsHadItsTime() {
        assertFalse(isOld(message(1, receivedAt = 100_000L), now = 114_999L))
        assertTrue(isOld(message(1, receivedAt = 100_000L), now = 115_000L))
        // Never added to chat: old.
        assertTrue(isOld(message(1, receivedAt = 0L), now = 5_000L))
    }

    @Test fun nextToFadeIsTheOldestStillBright() {
        val chat = listOf(
            message(1, receivedAt = 10_000L),
            message(2, receivedAt = 50_000L),
            message(3, receivedAt = 52_000L),
            message(4, receivedAt = 60_000L)
        )
        assertEquals(65_000L, nextFadeDue(chat, now = 60_000L))
        assertEquals(67_000L, nextFadeDue(chat, now = 65_000L))
        assertEquals(75_000L, nextFadeDue(chat, now = 70_000L))
        assertNull(nextFadeDue(chat, now = 75_000L))
        assertNull(nextFadeDue(emptyList(), now = 1L))
    }

    @Test fun keysThatTypeSomething() {
        assertEquals("a", typedText('a'.code, shortcut = false))
        assertEquals("A", typedText('A'.code, shortcut = false))
        assertEquals("@", typedText('@'.code, shortcut = false))
        assertEquals("é", typedText('é'.code, shortcut = false))
        assertEquals("😀", typedText(0x1F600, shortcut = false))
    }

    @Test fun keysThatDont() {
        assertNull(typedText('a'.code, shortcut = true))       // Ctrl+A and the like
        assertNull(typedText(0, shortcut = false))              // nothing to type
        assertNull(typedText(' '.code, shortcut = false))       // Space is the video's
        assertNull(typedText('\t'.code, shortcut = false))
        assertNull(typedText('\n'.code, shortcut = false))
        assertNull(typedText(0x7F, shortcut = false))           // Delete
        assertNull(typedText(Int.MIN_VALUE or '`'.code, shortcut = false))   // a dead key
    }

    @Test fun typedWhereTheCursorIs() {
        assertEquals(TextFieldValue("a", TextRange(1)), TextFieldValue("").typedIn("a"))
        assertEquals(TextFieldValue("hello!", TextRange(6)), TextFieldValue("hello", TextRange(5)).typedIn("!"))
        assertEquals(TextFieldValue("hXo", TextRange(2)), TextFieldValue("hello", TextRange(1, 4)).typedIn("X"))
        assertEquals(TextFieldValue("Xhello", TextRange(1)), TextFieldValue("hello", TextRange(0)).typedIn("X"))
    }
}
