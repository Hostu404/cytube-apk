package com.cytube.mobile.player

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCookieJarTest {

    private val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ".toHttpUrl()

    private fun cookie(setCookie: String): Cookie = Cookie.parse(url, setCookie)!!

    @Test
    fun `a response's cookies are added to the ones kept, not put in their place`() {
        val jar = SessionCookieJar()
        jar.saveFromResponse(url, listOf(cookie("VISITOR_INFO1_LIVE=abc; Path=/"), cookie("YSC=one; Path=/")))
        jar.saveFromResponse(url, listOf(cookie("YSC=two; Path=/")))

        val sent = jar.loadForRequest(url).associate { it.name to it.value }
        assertEquals("abc", sent["VISITOR_INFO1_LIVE"])
        assertEquals("two", sent["YSC"])
    }

    @Test
    fun `a cookie the server expires is forgotten`() {
        val jar = SessionCookieJar()
        jar.saveFromResponse(url, listOf(cookie("YSC=one; Path=/")))
        jar.saveFromResponse(url, listOf(cookie("YSC=; Path=/; Max-Age=0")))

        assertTrue(jar.loadForRequest(url).none { it.name == "YSC" })
    }

    @Test
    fun `a cookie past its expiry isn't sent`() {
        var now = 1_000_000_000_000L
        val jar = SessionCookieJar { now }
        jar.saveFromResponse(
            url,
            listOf(Cookie.Builder().name("A").value("1").domain("www.youtube.com").path("/").expiresAt(now + 1_000).build())
        )
        assertTrue(jar.loadForRequest(url).any { it.name == "A" })

        now += 2_000
        assertTrue(jar.loadForRequest(url).none { it.name == "A" })
    }

    @Test
    fun `the consent cookie is still sent when none is kept`() {
        assertTrue(SessionCookieJar().loadForRequest(url).any { it.name == "CONSENT" })
    }
}
