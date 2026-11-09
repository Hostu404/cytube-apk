package com.cytube.mobile.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRefusalTest {
    // chat.js restrictNewAccount: the channel's new_user_chat_delay and
    // new_user_chat_link_delay, for guests (by connection age) and accounts.
    @Test fun tooNewToChatOrToPostLinks() {
        val chat = ChatRefusal.spamFiltered("NEW_USER_CHAT")
        assertTrue(chat, "too new to chat" in chat && "wasn't sent" in chat)
        val links = ChatRefusal.spamFiltered("NEW_USER_CHAT_LINK")
        assertTrue(links, "too new to post links" in links)
        assertEquals("The channel's spam filter stopped that message.", ChatRefusal.spamFiltered("SOMETHING_NEW"))
        assertEquals("The channel's spam filter stopped that message.", ChatRefusal.spamFiltered(null))
    }

    // chat.js: the one noflood the server sends, to a muted user.
    @Test fun mutedSaysWhatTheServerSays() {
        assertEquals("You have been muted on this channel.", ChatRefusal.noflood("You have been muted on this channel."))
        assertEquals("That message wasn't sent.", ChatRefusal.noflood(null))
        assertEquals("That message wasn't sent.", ChatRefusal.noflood("  "))
        assertEquals("That message wasn't sent.", ChatRefusal.noflood("null"))
    }

    // chat.js: cooldown is 1000 / the allowed rate, in ms; the website
    // waits 200 ms more than that.
    @Test fun cooldownWaitsAsLongAsTheServerSaysAndALittleMore() {
        assertEquals(1_200L, ChatRefusal.cooldownMs(1000))
        assertEquals(866L, ChatRefusal.cooldownMs(1000 / 1.5))
        assertEquals(10_000L, ChatRefusal.cooldownMs(1e9))     // never stuck for long
        assertEquals(200L, ChatRefusal.cooldownMs(-5))
        assertEquals(1_200L, ChatRefusal.cooldownMs(null))
        assertEquals(1_200L, ChatRefusal.cooldownMs("soon"))
        assertEquals(1_200L, ChatRefusal.cooldownMs(Double.NaN))
    }
}
