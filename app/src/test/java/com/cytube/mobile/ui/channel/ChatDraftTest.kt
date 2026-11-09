package com.cytube.mobile.ui.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatDraftTest {
    @Test fun theServersOwnLimit() {
        // cytu.be's max-chat-message-length, and its chat box's maxlength.
        assertEquals(320, ChatDraft.MAX_CHARS)
    }

    @Test fun anEditThatFitsIsLeftAlone() {
        assertNull(ChatDraft.fit("hello", "hello!", max = 10))
        assertNull(ChatDraft.fit("", "x".repeat(10), max = 10))
    }

    @Test fun typingPastTheLimitPutsNothingIn() {
        assertEquals("0123456789" to 10, ChatDraft.fit("0123456789", "0123456789x", max = 10))
    }

    @Test fun aPasteIsCutToFitAndWhatWasThereStays() {
        // "XYZW" pasted after "abcd": as much of it as fits, and "efgh" kept.
        assertEquals("abcdXYefgh" to 6, ChatDraft.fit("abcdefgh", "abcdXYZWefgh", max = 10))
    }

    @Test fun replacingASelectionKeepsTheRest() {
        // "world" selected and a longer word pasted over it.
        assertEquals("hello everyo" to 12, ChatDraft.fit("hello world", "hello everyone!!", max = 12))
    }

    @Test fun aLongPasteIntoAnEmptyBoxKeepsTheFirstPart() {
        val pasted = "a".repeat(400)
        assertEquals("a".repeat(320) to 320, ChatDraft.fit("", pasted))
    }

    @Test fun anEmojiIsNeverLeftInHalf() {
        // Each of these is two UTF-16 units.
        assertEquals("abc😀" to 5, ChatDraft.fit("abc", "abc😀😀", max = 5))
        assertEquals("abc" to 3, ChatDraft.fit("abc", "abc😀😀", max = 4))
        // One emoji swapped for another with the same first half.
        assertEquals("a😃" to 3, ChatDraft.fit("a😀", "a😃b", max = 3))
    }

    @Test fun textAlreadyTooLongCanStillBeShortenedButNotLengthened() {
        assertEquals("abcdef" to 6, ChatDraft.fit("abcdefg", "abcdef", max = 5))
        assertEquals("abcdefg" to 7, ChatDraft.fit("abcdefg", "abcdefgX", max = 5))
    }

    @Test fun clipCutsAtTheLimitButNotThroughAnEmoji() {
        assertEquals("hel", ChatDraft.clip("hello", 3))
        assertEquals("hi", ChatDraft.clip("hi", 3))
        assertEquals("ab", ChatDraft.clip("ab😀", 3))
        assertEquals("", ChatDraft.clip("x", 0))
        assertEquals("x".repeat(320), ChatDraft.clip("x".repeat(500)))
    }
}
