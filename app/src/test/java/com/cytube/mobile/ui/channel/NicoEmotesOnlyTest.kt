package com.cytube.mobile.ui.channel

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/** Settings' "Nico chat: emotes only": a flying comment keeps just its emotes. */
class NicoEmotesOnlyTest {

    private fun emote(code: String) =
        """<img class="channel-emote" title="$code" src="https://example.com/${code.trim(':')}.png">"""

    private fun emotesOnly(html: String) = ChatHtml.emotesOnly(
        ChatHtml.render(raw = html, greentext = false, linkColor = Color.Unspecified, showImages = true)
    )

    @Test fun keepsJustTheEmotes() {
        val r = emotesOnly("lol ${emote(":smile:")} that was ${emote(":wave:")} great")
        assertEquals(":smile: :wave:", r.text.text)
        val codes = r.text.getStringAnnotations(ChatHtml.EMOTE_TAG, 0, r.text.length).map { it.item }
        assertEquals(listOf(":smile:", ":wave:"), codes)
        // Nothing but emotes now, so they fly at the bigger size.
        assertEquals(2, r.soloEmoteCount)
    }

    @Test fun aMessageWithoutEmotesIsBlank() {
        assertEquals("", emotesOnly("just words and https://example.com/a.gif").text.text)
    }

    @Test fun emotesSideBySideStayApart() {
        assertEquals(":smile: :smile:", emotesOnly(emote(":smile:") + emote(":smile:")).text.text)
    }
}
