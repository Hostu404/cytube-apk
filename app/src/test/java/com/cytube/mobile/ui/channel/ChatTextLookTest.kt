package com.cytube.mobile.ui.channel

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import com.cytube.mobile.net.ChannelStyle
import com.cytube.mobile.net.Emote
import com.cytube.mobile.net.EmoteSet
import com.cytube.mobile.net.luminanceOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chat text drawn the channel's way: its filters' classes, and the style
 *  and colour written into a message. */
class ChatTextLookTest {

    private val css = """
        .redtext { color: red }
        .bigtext { font-size: 150%; font-weight: bold }
        .rainbow { animation: rainbow 2s linear infinite }
        @keyframes rainbow { 0% { color: red } 50% { color: blue } 100% { color: red } }
        .greentext { color: #00ff00 }
    """

    private fun emotes(styled: Boolean = true): EmoteSet {
        val effects = ChannelStyle.parse(css).effects
        return EmoteSet.EMPTY.withEffects(effects.withTextLooks(effects.textLooks, styled))
    }

    private fun render(html: String, emotes: EmoteSet = emotes(), greentext: Boolean = false) =
        ChatHtml.render(raw = html, greentext = greentext, linkColor = Color.Unspecified, showImages = true, emotes = emotes)

    /** The colour styles covering [word] in [text], outermost first. */
    private fun colorsOn(text: AnnotatedString, word: String): List<Color> {
        val at = text.text.indexOf(word)
        return text.spanStyles.filter { it.start <= at && it.end >= at + word.length && it.item.color != Color.Unspecified }
            .map { it.item.color }
    }

    @Test fun aFiltersClassGivesItsLook() {
        val r = render("""say <span class="redtext">hello</span> there""")
        assertEquals(listOf(Color.Red), colorsOn(r.text, "hello"))
        assertTrue(colorsOn(r.text, "say").isEmpty())
        val big = render("""<span class="bigtext">BIG</span>""").text.spanStyles.single()
        assertEquals(1.5f, big.item.fontSize.value, 0.001f)
    }

    @Test fun styleAndColourWrittenIn() {
        // Pure blue is too dark to read on the app's page: lightened, still blue.
        val blue = colorsOn(render("""<span style="color: #00f">x</span>""").text, "x").single()
        assertTrue(blue.blue == 1f && blue.red < 0.6f && blue.red > 0f)
        assertEquals(listOf(Color(0xFF00FF00)), colorsOn(render("""<font color="lime">x</font>""").text, "x"))
    }

    @Test fun darkColoursAreMadeReadable() {
        val page = 0xFF1F2226.toInt()
        fun contrast(a: Color, b: Int): Float {
            val x = luminanceOf(a.toArgb()); val y = luminanceOf(b)
            return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
        }
        val darkRed = colorsOn(render("""<span style="color: #8b0000">x</span>""").text, "x").single()
        assertTrue(contrast(darkRed, page) >= 3f)
        assertTrue(darkRed.red > darkRed.green)
        // Black on a yellow highlight reads: kept.
        assertEquals(listOf(Color.Black), colorsOn(render("""<span style="color: black; background: yellow">x</span>""").text, "x"))
        // A light highlight and no colour of its own: the chat's light text
        // wouldn't read on it, so it's darkened.
        val onYellow = colorsOn(render("""<span style="background: #ffff00">x</span>""").text, "x").single()
        assertTrue(contrast(onYellow, 0xFFFFFF00.toInt()) >= 3f)
    }

    @Test fun aRainbowIsMarkedToChangeColour() {
        val r = render("""<span class="rainbow">wow</span>""")
        val mark = r.text.getStringAnnotations(ChatHtml.TEXT_FX_TAG, 0, r.text.length).single()
        assertEquals("wow", r.text.text.substring(mark.start, mark.end))
        val look = r.textFx[mark.item.toInt()]
        assertEquals(0xFFFF0000.toInt(), look.colorAt(0))
        assertEquals(0xFF0000FF.toInt(), look.colorAt(1000))
    }

    @Test fun theChannelsGreentext() {
        assertEquals(listOf(Color(0xFF00FF00)), colorsOn(render(">implying", greentext = true).text, "implying"))
        assertEquals(listOf(Color(0xFF789922)), colorsOn(render(">implying", EmoteSet.EMPTY, greentext = true).text, "implying"))
    }

    @Test fun aHiddenSpoilerStaysHidden() {
        val r = render("""<span class="spoiler"><span class="redtext">secret</span></span>""")
        assertTrue(Color.Red !in colorsOn(r.text, "secret"))
    }

    @Test fun offMeansPlain() {
        val r = render("""<span class="redtext">hello</span> <span style="color:#00f">x</span>""", emotes(styled = false))
        assertTrue(r.text.spanStyles.isEmpty())
        assertTrue(r.textFx.isEmpty())
    }

    @Test fun aPollsEmotes() {
        // Poll text is plain, escaped as the poll panel escapes it.
        val set = EmoteSet.from(listOf(Emote(name = "pepe", image = "https://example.com/pepe.png", source = "")))
        val r = ChatHtml.render(raw = "vote pepe &amp; co", greentext = false, linkColor = Color.Unspecified, showImages = true, emotes = set)
        assertEquals(listOf("https://example.com/pepe.png"), r.imageUrls)
        assertTrue(r.text.text.startsWith("vote ") && r.text.text.endsWith(" & co"))
        // Emotes off: the code as written.
        assertEquals("vote pepe & co", ChatHtml.render(raw = "vote pepe &amp; co", greentext = false, linkColor = Color.Unspecified, showImages = false, emotes = set).text.text)
    }
}
