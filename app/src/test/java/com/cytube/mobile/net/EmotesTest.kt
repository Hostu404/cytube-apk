package com.cytube.mobile.net

import org.junit.Assert.assertEquals
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
}
