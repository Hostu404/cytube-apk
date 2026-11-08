package com.cytube.mobile.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Emote words split where the website's JavaScript splits them (`\s`). */
class EmoteSpacesTest {

    private val smile = Emote(name = ":smile:", image = "https://example.com/smile.png", source = "")
    private val smileTag = """<img class="channel-emote" title=":smile:" src="https://example.com/smile.png">"""
    private val set = EmoteSet.from(listOf(smile))

    @Test
    fun `the character test and the regex class agree on every character`() {
        val inClass = Regex("[$EMOTE_SPACES]")
        for (code in 0..0xFFFF) {
            val c = code.toChar()
            assertEquals("U+%04X".format(code), inClass.matches(c.toString()), isEmoteSpace(c))
        }
    }

    @Test
    fun `the spaces are exactly JavaScript's`() {
        // ECMAScript WhiteSpace and LineTerminator: TAB VT FF ZWNBSP, the
        // Unicode space separators (Zs), and LF CR LS PS.
        val javascript = (listOf(0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0xA0, 0x1680) + (0x2000..0x200A) +
            listOf(0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF)).toSet()
        val ours = (0..0xFFFF).filter { isEmoteSpace(it.toChar()) }.toSet()
        assertEquals(javascript, ours)
    }

    @Test
    fun `an emote followed by a non-breaking space is drawn`() {
        val message = "hi :smile:\u00A0there"
        assertTrue(set.mightMatch(message))
        assertEquals("hi $smileTag\u00A0there", set.apply(message))
    }

    @Test
    fun `emotes between other unicode spaces are drawn`() {
        for (space in listOf('\u2003', '\u3000', '\u202F', '\uFEFF')) {
            val message = "a$space:smile:${space}b"
            assertTrue("U+%04X".format(space.code), set.mightMatch(message))
            assertEquals("U+%04X".format(space.code), "a$space$smileTag${space}b", set.apply(message))
        }
    }

    @Test
    fun `characters JavaScript doesn't count as space stay part of the word`() {
        // A zero-width space, and Java's next-line character, which its
        // own Unicode \s counts but JavaScript's doesn't.
        for (notSpace in listOf('\u200B', '\u0085')) {
            val message = ":smile:${notSpace}x"
            assertFalse("U+%04X".format(notSpace.code), set.mightMatch(message))
            assertEquals(message, set.apply(message))
        }
    }

    @Test
    fun `a name with a non-breaking space in it is matched as a phrase`() {
        val spaced = Emote(name = "big\u00A0pog", image = "https://example.com/pog.png", source = "")
        val withSpaced = EmoteSet.from(listOf(spaced))
        val message = "look big\u00A0pog here"
        assertTrue(withSpaced.mightMatch(message))
        assertTrue(withSpaced.apply(message).contains("""src="https://example.com/pog.png""""))
    }

    @Test
    fun `ordinary spaces work as before`() {
        assertEquals("a $smileTag b", set.apply("a :smile: b"))
        assertTrue(set.mightMatch("a :smile: b"))
        assertFalse(set.mightMatch("a :smiled: b"))
    }
}
