package com.cytube.mobile.ui.channel

import com.cytube.mobile.net.Emote
import com.cytube.mobile.net.EmoteSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ChatRecallTest {
    @Test fun whatYouSentIsKeptOldestFirst() {
        val sent = SentChat.EMPTY.adding(null, "hello").adding(null, "anyone up?")
        assertEquals(listOf("hello", "anyone up?"), sent.of(null))
    }

    @Test fun privateChatsAreKeptApartFromPublicChat() {
        val sent = SentChat.EMPTY.adding(null, "hello all").adding("Amy", "psst").adding("amy", "you there?").adding("Bob", "yo")
        assertEquals(listOf("hello all"), sent.of(null))
        assertEquals(listOf("psst", "you there?"), sent.of("AMY"))
        assertEquals(listOf("yo"), sent.of("bob"))
        assertEquals(emptyList<String>(), sent.of("Cat"))
    }

    @Test fun theSameLineTwiceRunningIsKeptOnce() {
        val sent = SentChat.EMPTY.adding(null, "lol").adding(null, "lol").adding(null, "brb").adding(null, "lol")
        assertEquals(listOf("lol", "brb", "lol"), sent.of(null))
        assertSame(sent, sent.adding(null, "lol"))
        assertSame(sent, sent.adding(null, "  "))
    }

    @Test fun onlyTheLastFourAreKept() {
        var sent = SentChat.EMPTY
        repeat(10) { sent = sent.adding(null, "line $it") }
        assertEquals((6 until 10).map { "line $it" }, sent.of(null))
    }

    private val emotes = EmoteSet.from(listOf(
        Emote("KEKW", "https://e.example/kekw.png", ""),
        Emote("Pog", "https://e.example/pog.png", ""),
        Emote("LUL", "https://e.example/lul.png", ""),
        Emote("<3", "https://e.example/heart.png", ""),
        // A name with a space: matched by the channel's own pattern, as chat does.
        Emote("no u", "https://e.example/nou.png", "(^|\\s)no u(?!\\S)")
    ))

    @Test fun theEmotesALineUsesAsChatWillShowThem() {
        assertEquals(listOf("Pog", "KEKW"), RecentEmotes.usedIn("Pog that was KEKW Pog", emotes))
        // A whole word, exactly, as chat matches them.
        assertEquals(emptyList<String>(), RecentEmotes.usedIn("pog kekw KEKWait", emotes))
        // It reaches everyone escaped, as the names are looked up.
        assertEquals(listOf("<3"), RecentEmotes.usedIn("love you <3", emotes))
        // By the channel's pattern: not from inside other words.
        assertEquals(listOf("LUL", "no u"), RecentEmotes.usedIn("LUL no u", emotes))
        assertEquals(emptyList<String>(), RecentEmotes.usedIn("piano useful", emotes))
        assertEquals(emptyList<String>(), RecentEmotes.usedIn("KEKW", EmoteSet.EMPTY))
    }

    @Test fun theNewestComeFirstEachOnceAtMostSixteen() {
        assertEquals(listOf("Pog", "KEKW", "LUL"), RecentEmotes.after(listOf("KEKW", "Pog", "LUL"), listOf("Pog")))
        assertEquals(listOf("A", "B", "C"), RecentEmotes.after(listOf("C"), listOf("A", "B")))
        val many = (1..20).map { "e$it" }
        assertEquals(listOf("new") + many.take(15), RecentEmotes.after(many, listOf("new")))
    }
}
