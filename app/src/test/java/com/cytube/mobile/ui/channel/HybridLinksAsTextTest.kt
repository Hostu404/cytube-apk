package com.cytube.mobile.ui.channel

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import com.cytube.mobile.net.EmoteSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A link's look (colour and underline) comes only with a link colour: the
 * parse itself just marks where links are. Hybrid chat asks for no colour,
 * so its links are plain text like the rest; normal chat looks as before.
 */
class HybridLinksAsTextTest {

    private val html = """<u>hi</u> see <a href="https://example.com/x">https://example.com/x</a> and https://example.org ok"""

    private fun render(linkColor: Color) =
        ChatHtml.render(raw = html, greentext = false, linkColor = linkColor, showImages = true, emotes = EmoteSet.EMPTY)

    private fun ChatHtml.Rendered.underlined(): List<String> =
        text.spanStyles.filter { it.item.textDecoration == TextDecoration.Underline }
            .map { text.text.substring(it.start, it.end) }

    @Test fun withoutALinkColourLinksArePlainText() {
        val r = render(Color.Unspecified)
        // Only what the message underlined itself.
        val underlined = r.underlined()
        assertTrue(underlined.isNotEmpty() && underlined.all { it == "hi" })
        assertTrue(r.text.spanStyles.none { it.item.color != Color.Unspecified })
    }

    @Test fun withALinkColourLinksLookAsBefore() {
        val r = render(Color.Red)
        val underlined = r.underlined()
        assertTrue("https://example.com/x" in underlined)
        assertTrue("https://example.org" in underlined)
        assertTrue("hi" in underlined)
        assertTrue(r.text.spanStyles.count { it.item.color == Color.Red } >= 2)
    }

    // Still marked, either way: Nico takes them out, the poll and normal
    // chat open them.
    @Test fun linksAreStillMarked() {
        val links = render(Color.Unspecified).text.let { it.getStringAnnotations(ChatHtml.LINK_TAG, 0, it.length) }
        val urls = links.map { it.item }.toSet()
        assertEquals(setOf("https://example.com/x", "https://example.org"), urls)
    }
}
