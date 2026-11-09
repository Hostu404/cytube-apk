package com.cytube.mobile.ui.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatCookiesTest {
    private val cookie = "s%3Ajon%3A1800000000000%3Asalt%3Ahash%3A1.sig"

    @Test fun theLoginIsForTheWholeSiteSoTheChatConnectionGetsItToo() {
        val set = CompatCookies.forLogin("https://cytu.be", cookie)
        assertEquals(2, set.size)
        // cytu.be's socket servers are other hosts (zip.cytu.be, bigapple.cytu.be…):
        // only a Domain cookie goes there.
        assertEquals("auth=$cookie; Domain=cytu.be; Path=/; Secure; HttpOnly; SameSite=Lax", set[1])
    }

    @Test fun anOldHostOnlyCopyIsTakenAwayFirst() {
        val expire = CompatCookies.forLogin("https://cytu.be", cookie)[0]
        assertTrue(expire.startsWith("auth=;"))
        assertTrue("Max-Age=0" in expire)
        assertFalse("host-only: no Domain", "Domain=" in expire)
        assertTrue("same path as the one it replaces", "Path=/" in expire)
    }

    @Test fun logoutStillTakesItAway() {
        // AuthRepository.logout expires "auth" with Domain=<host> and Path=/,
        // which is exactly the cookie set here.
        val set = CompatCookies.forLogin("https://cytu.be", cookie)[1]
        assertTrue("Domain=cytu.be" in set && "Path=/" in set)
    }
}
