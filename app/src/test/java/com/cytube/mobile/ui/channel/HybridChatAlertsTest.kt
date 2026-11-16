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

    // The channel's recent chat, come with the join: old already, faded at
    // once as in CyTube Plus, but for what was only just posted. The rest:
    // from when it came in, whatever its time.
    @Test fun joiningsChatIsOldAlready() {
        val now = 500_000L
        val wall = 1_700_000_000_000L
        fun joins(posted: Long) = arrivedAt(posted, fromJoin = true, now = now, wallNow = wall)
        assertEquals(0L, joins(wall - 10_000L))          // 10 s before
        assertEquals(0L, joins(wall - 3_600_000L))       // an hour before
        assertEquals(now, joins(wall - 2_000L))          // only just posted
        assertEquals(now, joins(wall + 2_000L))          // the clocks a little apart
        assertEquals(0L, joins(wall + 60_000L))          // far apart: old, all of it
        assertEquals(now, arrivedAt(wall - 3_600_000L, fromJoin = false, now = now, wallNow = wall))
        assertTrue(isOld(message(1, receivedAt = joins(wall - 10_000L)), now))
    }

    // The join's chat: what comes in the first JOIN_CHAT_MS of chat after
    // joining, counted from the first of it however long the join took; and
    // again after the next join.
    @Test fun theJoinsChatIsWhatComesFirst() {
        val join = JoinChat()
        assertTrue(join.isFromJoin(100_000L))
        assertTrue(join.isFromJoin(100_000L + JOIN_CHAT_MS))
        assertFalse(join.isFromJoin(100_001L + JOIN_CHAT_MS))
        assertFalse(join.isFromJoin(500_000L))
        join.joined()
        assertTrue(join.isFromJoin(900_000L))
        assertFalse(join.isFromJoin(900_001L + JOIN_CHAT_MS))
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
        // A join's, old already, after one still bright (missed while
        // reconnecting): that one still fades in its time.
        val rejoined = listOf(message(1, receivedAt = 50_000L), message(2, receivedAt = 0L))
        assertEquals(65_000L, nextFadeDue(rejoined, now = 60_000L))
        assertNull(nextFadeDue(rejoined, now = 65_000L))
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
