package com.cytube.mobile.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuestLoginTest {
    @Test fun theOneLoginAMinuteLimitMeansWaitNotRename() {
        // Exact text from the server's user.js guestLogin.
        assertEquals(60, GuestLogin.cooldownSeconds(
            "Guest logins are restricted to one per IP address per 60 seconds."))
    }

    @Test fun otherRefusalsAreAboutTheName() {
        assertNull(GuestLogin.cooldownSeconds("That name is already in use on this channel."))
        assertNull(GuestLogin.cooldownSeconds("That username is registered."))
        assertNull(GuestLogin.cooldownSeconds(null))
    }
}
