package com.cytube.mobile.ui.channel

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hybrid chat's messages, as CyTube Plus shows them: "name: message" on
 *  one line, the name in its colour and tappable. */
class HybridChatNameTest {

    @Test fun theNameStartsTheMessage() {
        val prefix = chatNamePrefix("alice", isPm = false, color = Color.Red)
        assertEquals("alice: ", prefix.text)
        val name = prefix.getStringAnnotations(CHAT_NAME_TAG, 0, prefix.length).single()
        assertEquals(0, name.start)
        assertEquals(prefix.length, name.end)
        assertEquals("alice", name.item)
        assertTrue(prefix.spanStyles.any { it.item.color == Color.Red && it.start == 0 && it.end == prefix.length })
    }

    @Test fun aPmSaysSo() {
        assertEquals("alice (PM): ", chatNamePrefix("alice", isPm = true, color = Color.Red).text)
        assertEquals("You → bob (PM): ", chatNamePrefix("You → bob", isPm = true, color = Color.Red).text)
    }

    // What's in the message (emotes, links, spoilers) is found in the same
    // places after the name as before it, moved along by the name.
    @Test fun theMessagesOwnMarksMoveAlong() {
        val message = buildAnnotatedString {
            append("look ")
            pushStringAnnotation("emote", ":pepe:")
            append("x")
            pop()
        }
        val prefix = chatNamePrefix("alice", isPm = false, color = Color.Red)
        val hit: AnnotatedString = prefix + message
        val emote = hit.getStringAnnotations("emote", 0, hit.length).single()
        assertEquals(prefix.length + 5, emote.start)
        assertEquals(":pepe:", emote.item)
        // A tap in the message isn't a tap on the name.
        assertTrue(hit.getStringAnnotations(CHAT_NAME_TAG, emote.start, emote.start).isEmpty())
    }
}
