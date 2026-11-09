package com.cytube.mobile.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebEmbedHtmlTest {

    @Test
    fun safeEmbedIdRegex_acceptsValidIds() {
        assertTrue(SAFE_EMBED_ID_REGEX.matches("dQw4w9WgXcQ"))
        assertTrue(SAFE_EMBED_ID_REGEX.matches("x86_64-test"))
        assertTrue(SAFE_EMBED_ID_REGEX.matches("1234567890"))
    }

    @Test
    fun safeEmbedIdRegex_rejectsInjectionPayloads() {
        assertFalse(SAFE_EMBED_ID_REGEX.matches("</script><script>alert(1)</script>"))
        assertFalse(SAFE_EMBED_ID_REGEX.matches("id' OR '1'='1"))
        assertFalse(SAFE_EMBED_ID_REGEX.matches("id\" style=\"background:red\""))
        assertFalse(SAFE_EMBED_ID_REGEX.matches("invalid spaces"))
    }

    @Test
    fun htmlGenerators_refuseInvalidIds() {
        val badId = "bad</script>id"
        assertEquals(BLANK_EMBED_HTML, dailymotionSdkHtml(badId))
        assertEquals(BLANK_EMBED_HTML, youtubeIframeApiHtml(badId))
        assertEquals(BLANK_EMBED_HTML, vimeoSdkHtml(badId))
        assertEquals(BLANK_EMBED_HTML, streamableSdkHtml(badId))
    }

    @Test
    fun htmlGenerators_generateValidHtmlForGoodIds() {
        val ytHtml = youtubeIframeApiHtml("dQw4w9WgXcQ", initialTime = 10.0, initialPaused = true)
        assertTrue(ytHtml.contains("dQw4w9WgXcQ"))
        assertTrue(ytHtml.contains("https://www.youtube.com/iframe_api"))

        val dmHtml = dailymotionSdkHtml("x7tgad0", initialTime = 5.0, initialPaused = false)
        assertTrue(dmHtml.contains("x7tgad0"))
        assertTrue(dmHtml.contains("https://api.dmcdn.net/all.js"))

        val ptHtml = peertubeSdkHtml("https://peertube.example/videos/embed/12345", initialTime = 0.0, initialPaused = false)
        assertTrue(ptHtml.contains("https://peertube.example/videos/embed/12345"))
        assertTrue(ptHtml.contains("@peertube/embed-api"))
        assertFalse(ptHtml.contains("muted=1"))
    }

    @Test
    fun sameSite_matchesSubdomainsAndExactHosts() {
        assertTrue(sameSite("geo.dailymotion.com", "www.dailymotion.com"))
        assertTrue(sameSite("dailymotion.com", "dailymotion.com"))
        assertTrue(sameSite("8chan.tv", "8chan.tv"))

        assertFalse(sameSite("evil.example", "dailymotion.com"))
        assertFalse(sameSite("dailymotion.com.evil.example", "dailymotion.com"))
        assertFalse(sameSite(null, "dailymotion.com"))
        assertFalse(sameSite("dailymotion.com", null))
    }

    // By the public suffix list: two sites under one multi-part suffix
    // aren't one site, as comparing the last two parts made them.
    @Test
    fun sameSite_knowsMultiPartPublicSuffixes() {
        assertTrue(sameSite("www.bbc.co.uk", "news.bbc.co.uk"))
        assertFalse(sameSite("evil.co.uk", "bbc.co.uk"))
        assertFalse(sameSite("alice.github.io", "bob.github.io"))
        assertTrue(sameSite("WWW.Dailymotion.com", "geo.dailymotion.com"))
        // An IP address is only ever the same site as itself.
        assertTrue(sameSite("10.0.0.1", "10.0.0.1"))
        assertFalse(sameSite("10.0.0.1", "10.0.0.2"))
        assertFalse(sameSite("1.2.3.4", "5.6.3.4"))
    }
}
