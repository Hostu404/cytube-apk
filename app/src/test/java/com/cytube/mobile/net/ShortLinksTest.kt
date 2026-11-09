package com.cytube.mobile.net

import com.cytube.mobile.net.ShortLinks.overHttps
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShortLinksTest {
    /** Answers from a table, keeping every address asked for. */
    private class Fake(private val answers: Map<String, ShortLinks.Answer?>) : ShortLinks.Head {
        val asked = mutableListOf<String>()
        override fun request(url: okhttp3.HttpUrl, timeoutMs: Long): ShortLinks.Answer? {
            asked += url.toString()
            return answers[url.toString()]
        }
    }

    private fun redirect(to: String) = ShortLinks.Answer(301, to)
    private val ok = ShortLinks.Answer(200, null)

    @Test fun aRedirectToAPlainHttpYouTubeLinkIsTheAnswerWithoutFetchingIt() {
        // The case that used to fail: the client refused the http:// hop.
        val fake = Fake(mapOf("https://t.co/abc" to redirect("http://www.youtube.com/watch?v=dQw4w9WgXcQ")))
        assertEquals("http://www.youtube.com/watch?v=dQw4w9WgXcQ", ShortLinks.follow("https://t.co/abc", fake))
        assertEquals(listOf("https://t.co/abc"), fake.asked)
        assertEquals("yt", MediaLink.parse("http://www.youtube.com/watch?v=dQw4w9WgXcQ").getOrNull()?.type)
    }

    @Test fun anHttpHopIsAskedForOverHttps() {
        val fake = Fake(mapOf(
            "https://bit.ly/x" to redirect("http://example.com/landing"),
            "https://example.com/landing" to redirect("https://youtu.be/dQw4w9WgXcQ")
        ))
        assertEquals("https://youtu.be/dQw4w9WgXcQ", ShortLinks.follow("https://bit.ly/x", fake))
        assertEquals(listOf("https://bit.ly/x", "https://example.com/landing"), fake.asked)
    }

    @Test fun aShortLinkGivenAsHttpIsAskedForOverHttpsToo() {
        val fake = Fake(mapOf("https://bit.ly/x" to redirect("https://youtu.be/dQw4w9WgXcQ")))
        assertEquals("https://youtu.be/dQw4w9WgXcQ", ShortLinks.follow("http://bit.ly/x", fake))
        assertEquals(listOf("https://bit.ly/x"), fake.asked)
    }

    @Test fun theLastAddressIsTheAnswerWhenItStopsRedirecting() {
        val fake = Fake(mapOf(
            "https://short.example/a" to redirect("/b?c=1"),
            "https://short.example/b?c=1" to ok
        ))
        assertEquals("https://short.example/b?c=1", ShortLinks.follow("https://short.example/a", fake))
    }

    @Test fun aHopThatCantBeHadKeepsTheLastAddress() {
        // https isn't served there: the http address is still worth having.
        val fake = Fake(mapOf("https://bit.ly/x" to redirect("http://old.example/video")))
        assertEquals("http://old.example/video", ShortLinks.follow("https://bit.ly/x", fake))
        assertEquals(listOf("https://bit.ly/x", "https://old.example/video"), fake.asked)
    }

    @Test fun nothingWhenNotEvenTheFirstRequestCanBeMade() {
        assertNull(ShortLinks.follow("https://bit.ly/x", Fake(emptyMap())))
        assertNull(ShortLinks.follow("not a link", Fake(emptyMap())))
    }

    @Test fun anAddressThatIsNoWebPageIsNotFollowed() {
        val fake = Fake(mapOf("https://bit.ly/app" to redirect("intent://open#Intent;scheme=x;end")))
        assertEquals("https://bit.ly/app", ShortLinks.follow("https://bit.ly/app", fake))
        assertEquals(listOf("https://bit.ly/app"), fake.asked)
    }

    @Test fun aRedirectLoopEnds() {
        val fake = object : ShortLinks.Head {
            var n = 0
            override fun request(url: okhttp3.HttpUrl, timeoutMs: Long) = ShortLinks.Answer(302, "https://loop.example/${++n}")
        }
        assertEquals("https://loop.example/3", ShortLinks.follow("https://loop.example/0", fake, maxHops = 3))
        assertEquals(3, fake.n)
    }

    @Test fun itStopsWhenTimeRunsOut() {
        var now = 0L
        val fake = object : ShortLinks.Head {
            var n = 0
            val timeouts = mutableListOf<Long>()
            override fun request(url: okhttp3.HttpUrl, timeoutMs: Long): ShortLinks.Answer {
                timeouts += timeoutMs
                now += 3_000_000_000L   // each hop takes 3 s
                return ShortLinks.Answer(302, "https://slow.example/${++n}")
            }
        }
        // 8 s allowed: three hops are asked for, each given what's left of
        // it, and the fourth isn't started.
        assertEquals("https://slow.example/3", ShortLinks.follow("https://slow.example/0", fake, nanoTime = { now }))
        assertEquals(3, fake.n)
        assertEquals(listOf(8_000L, 5_000L, 2_000L), fake.timeouts)
    }

    @Test fun aRequestNeverGetsMoreThanTheTimeLeft() {
        // However slow the first hop was, all of it counts: the next one is
        // given only what's left, never a fresh allowance.
        var now = 0L
        val timeouts = mutableListOf<Long>()
        val slowFirst = ShortLinks.Head { url, timeoutMs ->
            timeouts += timeoutMs
            if (url.host == "bit.ly") {
                now += 7_500_000_000L
                ShortLinks.Answer(301, "http://example.com/landing")
            } else ShortLinks.Answer(301, "https://youtu.be/dQw4w9WgXcQ")
        }
        assertEquals("https://youtu.be/dQw4w9WgXcQ", ShortLinks.follow("https://bit.ly/x", slowFirst, nanoTime = { now }))
        assertEquals(listOf(8_000L, 500L), timeouts)
    }

    @Test fun overHttpsKeepsTheAddressButTheScheme() {
        assertEquals("https://example.com/x?y=1", "http://example.com/x?y=1".toHttpUrl().overHttps().toString())
        assertEquals("https://example.com:8080/x", "http://example.com:8080/x".toHttpUrl().overHttps().toString())
        assertEquals("https://example.com/x", "https://example.com/x".toHttpUrl().overHttps().toString())
    }
}
