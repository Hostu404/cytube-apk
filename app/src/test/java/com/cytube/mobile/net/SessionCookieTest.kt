package com.cytube.mobile.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCookieTest {
    @Test fun readsTheExpiryTheServerWroteIn() {
        assertEquals(1_800_000_000_000L, SessionCookie.expiresAtMs("jon:1800000000000:abc:def:1"))
        assertEquals(1_800_000_000_000L, SessionCookie.expiresAtMs("jon%3A1800000000000%3Aabc%3Adef%3A1"))
    }

    @Test fun onlyAPastExpiryCountsAsExpired() {
        assertTrue(SessionCookie.isExpired("jon:1000:abc:def:1", nowMs = 2_000))
        assertFalse(SessionCookie.isExpired("jon:3000:abc:def:1", nowMs = 2_000))
        // Unreadable: not assumed expired (a second refusal decides instead).
        assertFalse(SessionCookie.isExpired("garbage", nowMs = 2_000))
        assertNull(SessionCookie.expiresAtMs("garbage"))
    }
}
