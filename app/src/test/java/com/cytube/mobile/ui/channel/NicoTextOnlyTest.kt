package com.cytube.mobile.ui.channel

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.sp
import com.cytube.mobile.net.Emote
import com.cytube.mobile.net.EmoteSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings' "Nico chat: text only": a flying comment keeps just its text
 *  (as CyTube Plus's "Text only"), and "Nico emote opacity". */
class NicoTextOnlyTest {

    private fun emote(code: String) =
        """<img class="channel-emote" title="$code" src="https://example.com/${code.trim(':')}.png">"""

    private fun render(html: String, emotes: EmoteSet = EmoteSet.EMPTY, greentext: Boolean = false) =
        ChatHtml.render(raw = html, greentext = greentext, linkColor = Color.Unspecified, showImages = true, emotes = emotes)

    private fun textOnly(html: String, emotes: EmoteSet = EmoteSet.EMPTY, greentext: Boolean = false) =
        ChatHtml.textOnly(render(html, emotes, greentext))

    @Test fun keepsJustTheText() {
        val r = textOnly("lol ${emote(":smile:")} that was ${emote(":wave:")} great")
        assertEquals("lol that was great", r.text.text)
        // No emote left anywhere: none to draw, none to tap, none sized big.
        assertTrue(r.text.getStringAnnotations(ChatHtml.EMOTE_TAG, 0, r.text.length).isEmpty())
        assertTrue(r.imageUrls.isEmpty())
        assertTrue(r.fx.isEmpty())
        assertEquals(0, r.soloEmoteCount)
    }

    // So it doesn't fly (the overlay flies only comments with something left).
    @Test fun aMessageThatsOnlyEmotesIsBlank() {
        assertEquals("", textOnly(emote(":smile:") + " " + emote(":wave:")).text.text)
        assertEquals("", textOnly(emote(":smile:")).text.text)
    }

    @Test fun emotesAtEitherEndLeaveNoStraySpaces() {
        assertEquals("hi there", textOnly("${emote(":wave:")} hi there ${emote(":smile:")}").text.text)
    }

    @Test fun aMessageWithoutEmotesIsUnchanged() {
        val rendered = render("just words")
        assertSame(rendered, ChatHtml.textOnly(rendered))
    }

    // Greentext and the like keep their colour, as they do on the website.
    @Test fun theTextKeepsItsStyle() {
        val r = textOnly("&gt;be me ${emote(":smile:")} reading", greentext = true)
        assertEquals(">be me reading", r.text.text)
        assertTrue(r.text.spanStyles.isNotEmpty())
        assertEquals(render("&gt;be me reading", greentext = true).text.spanStyles.map { it.item }.toSet(),
            r.text.spanStyles.map { it.item }.toSet())
    }

    // A picture from the channel's image folder (a rolled Pokémon, say) isn't
    // text either.
    @Test fun picturesGoToo() {
        val folder = EmoteSet.from(listOf(Emote(name = ":smile:", image = "https://img.example.com/emotes/smile.png", source = "")))
        val html = """rolled <img src="https://img.example.com/emotes/pikachu.png"> nice"""
        assertEquals(listOf("https://img.example.com/emotes/pikachu.png"), render(html, folder).imageUrls)
        val r = textOnly(html, folder)
        assertEquals("rolled nice", r.text.text)
        assertTrue(r.imageUrls.isEmpty())
    }

    private val placeholder = Placeholder(20.sp, 20.sp, PlaceholderVerticalAlign.TextCenter)
    private val inline = mapOf("https://example.com/smile.png" to InlineTextContent(placeholder) { })

    @Test fun solidEmotesAreDrawnAsBefore() {
        assertSame(inline, inline.withAlpha(1f))
        assertSame(inline, inline.withAlpha(Float.NaN))
    }

    // See-through: the same placeholders, so nothing in the comment moves.
    @Test fun seeThroughEmotesKeepTheirPlace() {
        val faded = inline.withAlpha(0.4f)
        assertEquals(inline.keys, faded.keys)
        assertEquals(placeholder, faded.getValue("https://example.com/smile.png").placeholder)
        assertNotEquals(inline.getValue("https://example.com/smile.png"), faded.getValue("https://example.com/smile.png"))
    }
}
