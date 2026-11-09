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

    // What the server actually sends: Express's signed cookie,
    // "s:" + value + "." + signature, URL-encoded (encodeURIComponent, so the
    // Base64 salt, hash and signature have their +, / and = escaped too).
    private fun signed(name: String, expiry: Long, rank: Int? = 1) =
        "s%3A$name%3A$expiry%3AsA1t%2Bx%2Fy%3D%3AhAsH%2B%3D" + (rank?.let { "%3A$it" } ?: "") + ".sIgNaTuRe%2Bab%2Fcd"

    @Test fun readsTheSignedCookieTheServerSends() {
        assertEquals(1_800_000_000_000L, SessionCookie.expiresAtMs(signed("jon", 1_800_000_000_000L)))
        // Decoded, as a cookie store might hand it over.
        assertEquals(1_800_000_000_000L, SessionCookie.expiresAtMs("s:jon:1800000000000:salt:hash:1.signature"))
        // The older value without the rank (verifySession takes both).
        assertEquals(1_800_000_000_000L, SessionCookie.expiresAtMs(signed("jon", 1_800_000_000_000L, rank = null)))
        assertEquals(1_800_000_000_000L, SessionCookie.expiresAtMs(signed("jon", 1_800_000_000_000L, rank = 255)))
    }

    @Test fun anAllDigitUsernameIsNotTakenForTheExpiry() {
        // The name used to be read as the expiry: 1234 ms, always "expired".
        assertEquals(1_800_000_000_000L, SessionCookie.expiresAtMs(signed("1234", 1_800_000_000_000L)))
        assertFalse(SessionCookie.isExpired(signed("1234", 1_800_000_000_000L), nowMs = 1_700_000_000_000L))
        assertTrue(SessionCookie.isExpired(signed("1234", 1_600_000_000_000L), nowMs = 1_700_000_000_000L))
    }

    @Test fun aUserCalledSIsNotTakenForTheSignedMark() {
        // Unsigned, name "s": the rank has no signature after it.
        assertEquals(1_800_000_000_000L, SessionCookie.expiresAtMs("s:1800000000000:salt:hash:1"))
        // Signed, name "s".
        assertEquals(1_800_000_000_000L, SessionCookie.expiresAtMs(signed("s", 1_800_000_000_000L)))
    }

    @Test fun aSignedCookieWithTheWrongShapeIsUnreadable() {
        assertNull(SessionCookie.expiresAtMs("s%3Ajon.sig"))
        assertNull(SessionCookie.expiresAtMs("s%3Ajon%3Anotanumber%3Asalt%3Ahash%3A1.sig"))
        assertNull(SessionCookie.expiresAtMs(""))
        assertFalse(SessionCookie.isExpired("s%3Ajon.sig", nowMs = 2_000))
    }
}
