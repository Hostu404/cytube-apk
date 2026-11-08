package com.cytube.mobile.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class KickReasonTest {

    @Test
    fun `a reason the server gives is shown as it is`() {
        assertEquals("Duplicate login", KickReason.of(JSONObject().put("reason", "Duplicate login")))
        assertEquals("spamming", KickReason.of(JSONObject().put("reason", "  spamming ")))
    }

    // kickban.js: a moderator's "/kick name" with nothing after the name
    // sends {reason: ""}, which used to leave the notice empty.
    @Test
    fun `an empty reason gets a sentence of its own`() {
        assertEquals(KickReason.DEFAULT, KickReason.of(JSONObject().put("reason", "")))
        assertEquals(KickReason.DEFAULT, KickReason.of(JSONObject().put("reason", "   ")))
    }

    @Test
    fun `a missing or null reason gets the same sentence`() {
        assertEquals(KickReason.DEFAULT, KickReason.of(JSONObject()))
        assertEquals(KickReason.DEFAULT, KickReason.of(JSONObject().put("reason", JSONObject.NULL)))
        assertEquals(KickReason.DEFAULT, KickReason.of(null))
    }

    @Test
    fun `a plain string frame is used as the reason`() {
        assertEquals("Restarting", KickReason.of("Restarting"))
        assertEquals(KickReason.DEFAULT, KickReason.of(""))
    }
}
