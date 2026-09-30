package com.cytube.mobile.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmotesTest {

    @Test
    fun `replaces single-token emote with img tag`() {
        val emote = Emote(name = ":smile:", image = "https://example.com/smile.png", source = "")
        val emoteSet = EmoteSet.from(listOf(emote))

        val result = emoteSet.apply("Hello :smile: world")
        assertEquals(
            """Hello <img class="channel-emote" title=":smile:" src="https://example.com/smile.png"> world""",
            result
        )
    }

    @Test
    fun `does not corrupt dollar signs or backslashes in non-matching tokens`() {
        val emote = Emote(name = ":smile:", image = "https://example.com/smile.png", source = "")
        val emoteSet = EmoteSet.from(listOf(emote))

        val message = "It costs $100 or \$50 with \\path\\to\\file"
        val result = emoteSet.apply(message)
        assertEquals(message, result)
    }

    @Test
    fun `correctly handles multi-word spaced emotes`() {
        val emote = Emote(name = "kappa pride", image = "https://example.com/kappapride.png", source = "")
        val emoteSet = EmoteSet.from(listOf(emote))

        val result = emoteSet.apply("Look at kappa pride here")
        assertTrue(result.contains("""<img class="channel-emote" title="kappa pride""""))
    }

    // A pattern that can never match but tries every way of splitting the
    // text first: without the time limit these would run for far longer
    // than the test allows.
    private val runaway = Emote(name = "slow one", image = "https://example.com/slow.png", source = "(.*a){12}x")
    private val runawayInput = "a".repeat(40)

    @Test(timeout = 5_000)
    fun `a runaway channel pattern gives up and leaves the rest of the message alone`() {
        val smile = Emote(name = ":smile:", image = "https://example.com/smile.png", source = "")
        val emoteSet = EmoteSet.from(listOf(runaway, smile))

        val result = emoteSet.apply("$runawayInput :smile:")
        assertEquals(
            """$runawayInput <img class="channel-emote" title=":smile:" src="https://example.com/smile.png">""",
            result
        )
    }

    @Test(timeout = 5_000)
    fun `mightMatch gives up on a runaway channel pattern`() {
        val emoteSet = EmoteSet.from(listOf(runaway))
        assertFalse(emoteSet.mightMatch(runawayInput))
    }
}
