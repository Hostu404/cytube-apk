package com.cytube.mobile.ui.channel

import com.cytube.mobile.data.isIgnoredName
import com.cytube.mobile.net.ChatMessage
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IgnoreTest {

    private fun msg(from: String, text: String, isPm: Boolean = false, to: String? = null) =
        ChatMessage(username = from, html = text, timestamp = 0L, addClass = null, shadow = false, isPm = isPm, to = to)

    @Test fun namesMatchIgnoringCase() {
        val ignored = setOf("spammer")
        assertTrue(isIgnoredName("Spammer", ignored))
        assertTrue(isIgnoredName("SPAMMER", ignored))
        assertFalse(isIgnoredName("Spammer2", ignored))
    }

    @Test fun serverMessagesAreNeverIgnored() {
        // A server notice has no username; it must not vanish because of an
        // odd entry in the list.
        assertFalse(isIgnoredName("", setOf("")))
        assertFalse(isIgnoredName("anyone", emptySet()))
    }

    @Test fun ignoringSomeoneTakesAwayWhatTheyAlreadySaid() {
        val state = ChannelUiState(
            localUser = "Me",
            messages = persistentListOf(
                msg("Pinkie", "hi"),
                msg("Spammer", "buy now"),
                msg("", "Spammer joined"),
                msg("Spammer", "psst", isPm = true, to = "Me"),
                msg("Me", "stop it", isPm = true, to = "Spammer"),
                msg("Pinkie", "bye")
            ),
            unreadPm = UnreadPm.of("Spammer")
        )
        val after = state.withIgnored(persistentSetOf("spammer"))
        assertEquals(
            listOf("hi", "Spammer joined", "stop it", "bye"),
            after.messages.map { it.html }
        )
        assertNull(after.unreadPm)
        assertEquals(persistentSetOf("spammer"), after.ignored)
    }

    @Test fun someoneElsesUnreadPmStays() {
        val state = ChannelUiState(unreadPm = UnreadPm.of("Pinkie", 2))
        assertEquals(UnreadPm.of("Pinkie", 2), state.withIgnored(persistentSetOf("spammer")).unreadPm)
    }

    @Test fun ignoringTheLatestSenderKeepsEveryoneElsesUnreadPms() {
        // Pinkie sent 2, then Spammer 1: only Spammer's goes.
        val unread = UnreadPm.of("Pinkie").plus("Pinkie").plus("Spammer")
        assertEquals("Spammer", unread.from)
        assertEquals(3, unread.count)
        val after = ChannelUiState(unreadPm = unread).withIgnored(persistentSetOf("spammer")).unreadPm!!
        assertEquals("Pinkie", after.from)
        assertEquals(2, after.count)
    }

    @Test fun ignoringAnEarlierSenderTakesTheirsOutOfTheCount() {
        val unread = UnreadPm.of("Spammer").plus("Spammer").plus("Pinkie")
        val after = ChannelUiState(unreadPm = unread).withIgnored(persistentSetOf("spammer")).unreadPm!!
        assertEquals(UnreadPm.of("Pinkie"), after)
    }

    @Test fun unreadPmsCountPerSender() {
        val unread = UnreadPm.of("Pinkie").plus("Rarity").plus("pinkie")
        // The latest sender moves to the end, keeping their count.
        assertEquals(listOf("Rarity" to 1, "pinkie" to 2), unread.senders)
        // Opening a PM to one person reads theirs only (ChannelViewModel.startPm).
        assertEquals(UnreadPm.of("Rarity"), unread.without { it.equals("Pinkie", ignoreCase = true) })
        assertNull(UnreadPm.of("Rarity").without { it == "Rarity" })
    }

    @Test fun ourOwnMessagesAreNeverHidden() {
        // A name ignored once (a guest's, say) can later be our own.
        val state = ChannelUiState(
            localUser = "Bob",
            messages = persistentListOf(msg("Bob", "hello"), msg("Spammer", "buy now"))
        )
        val after = state.withIgnored(persistentSetOf("bob", "spammer"))
        assertEquals(listOf("hello"), after.messages.map { it.html })
        assertFalse(after.hidesMessagesFrom("Bob"))
        assertTrue(after.hidesMessagesFrom("Spammer"))
    }

    @Test fun unignoringKeepsWhatsThere() {
        val state = ChannelUiState(
            messages = persistentListOf(msg("Pinkie", "hi")),
            ignored = persistentSetOf("spammer")
        )
        val after = state.withIgnored(persistentSetOf())
        assertEquals(listOf("hi"), after.messages.map { it.html })
        assertTrue(after.ignored.isEmpty())
    }
}
