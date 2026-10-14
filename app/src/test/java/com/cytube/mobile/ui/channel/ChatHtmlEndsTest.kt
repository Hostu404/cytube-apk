package com.cytube.mobile.ui.channel

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/** Line breaks and spaces at either end of a message, which the site never shows. */
class ChatHtmlEndsTest {

    private val url = "https://media1.tenor.com/m/0jqloXEbFKoAAAAd/fat-fated.gif"

    private fun render(html: String) =
        ChatHtml.render(raw = html, greentext = false, linkColor = Color.Unspecified, showImages = true)

    @Test fun aPictureOnItsOwnLineHasNoBlankLineAbove() {
        // A channel filter turning a posted picture into "<br><img>": shown
        // here as its link, which used to sit under an empty line.
        val r = render("<br><img src=\"$url\">")
        assertEquals(url, r.text.text)
        val link = r.text.getStringAnnotations(ChatHtml.LINK_TAG, 0, r.text.length).single()
        assertEquals(0, link.start)
        assertEquals(url.length, link.end)
    }

    @Test fun orBelow() {
        assertEquals(url, render("<img src=\"$url\"><br>").text.text)
        assertEquals(url, render("<br><a href=\"$url\" target=\"_blank\"><img src=\"$url\"></a><br>").text.text)
    }

    @Test fun breaksInTheMiddleStay() {
        assertEquals("look \n$url lol", render("look <br><img src=\"$url\"> lol").text.text)
        assertEquals("one\ntwo", render("one<br>two").text.text)
    }

    @Test fun spacesAtTheEndsGo() {
        assertEquals("i wonder why?", render("  i wonder why?  ").text.text)
        assertEquals("hi there", render("<br><br> <strong>hi</strong> there <br>").text.text)
    }
}
