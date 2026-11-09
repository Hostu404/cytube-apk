package com.cytube.mobile.net

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

class IpSessionTest {
    // As cytu.be sends it on a channel page: signed, URL-encoded, no Domain.
    private val issued = "s%3A203.0.113.7%3A1794197649106.SiGnAtUrE"

    @Test fun readsTheCookieAmongTheOthersAsSent() {
        val headers = listOf(
            "_csrf=abc123; Domain=.cytu.be; Path=/; HttpOnly",
            "ip-session=$issued; Path=/; Expires=Fri, 31 Dec 9999 23:59:59 GMT; HttpOnly"
        )
        assertEquals(issued, IpSessionCookie.fromSetCookie(headers))
        assertNull(IpSessionCookie.fromSetCookie(listOf("_csrf=abc123; Path=/")))
        assertNull(IpSessionCookie.fromSetCookie(listOf("ip-session=; Path=/")))
        assertNull(IpSessionCookie.fromSetCookie(listOf("ip-sessions=x; Path=/")))
        assertNull(IpSessionCookie.fromSetCookie(emptyList()))
    }

    @Test fun theHandshakeCarriesWhicheverCookiesThereAre() {
        assertEquals("auth=A; ip-session=B", IpSessionCookie.forHandshake("A", "B"))
        assertEquals("ip-session=B", IpSessionCookie.forHandshake(null, "B"))
        assertEquals("auth=A", IpSessionCookie.forHandshake("A", null))
        assertNull(IpSessionCookie.forHandshake(null, null))
    }

    /** A server that answers every request with [setCookie] (or no cookie),
     *  or fails it, keeping each request it was sent. */
    private class FakeServer(var setCookie: String? = null, var fail: Boolean = false) {
        val requests = mutableListOf<Request>()
        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            if (fail) throw IOException("offline")
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Set-Cookie", "_csrf=x; Domain=.cytu.be; Path=/; HttpOnly")
                .apply { setCookie?.let { addHeader("Set-Cookie", "ip-session=$it; Path=/; HttpOnly") } }
                .body("".toResponseBody(null))
                .build()
        }.build()
    }

    @Test fun theFirstVisitGetsOneAndKeepsIt() = runBlocking {
        val server = FakeServer(setCookie = issued)
        val store = InMemoryIpSessionStore()
        assertEquals(issued, IpSessionCookies(server.client, "https://cytu.be", store).forChannel("test"))
        assertEquals(issued, store.load())
        // Asked for the channel page with HEAD (nothing downloaded), no cookie yet.
        val request = server.requests.single()
        assertEquals("HEAD", request.method)
        assertEquals("https://cytu.be/r/test", request.url.toString())
        assertNull(request.header("Cookie"))
    }

    @Test fun oneStillGoodIsShownAndKept() = runBlocking {
        val server = FakeServer(setCookie = null)    // accepted: nothing new handed out
        val store = InMemoryIpSessionStore().apply { save(issued) }
        assertEquals(issued, IpSessionCookies(server.client, "https://cytu.be", store).forChannel("test"))
        assertEquals("ip-session=$issued", server.requests.single().header("Cookie"))
        assertEquals(issued, store.load())
    }

    @Test fun aNewOneForANewAddressReplacesIt() = runBlocking {
        // Moved from Wi-Fi to mobile data: the server hands out another.
        val renewed = "s%3A198.51.100.9%3A1794200000000.OtHeR"
        val server = FakeServer(setCookie = renewed)
        val store = InMemoryIpSessionStore().apply { save(issued) }
        assertEquals(renewed, IpSessionCookies(server.client, "https://cytu.be", store).forChannel("test"))
        assertEquals(renewed, store.load())
    }

    @Test fun offlineTheOneKeptIsUsedAndNothingFails() = runBlocking {
        val server = FakeServer(fail = true)
        val kept = InMemoryIpSessionStore().apply { save(issued) }
        assertEquals(issued, IpSessionCookies(server.client, "https://cytu.be", kept).forChannel("test"))
        assertNull(IpSessionCookies(server.client, "https://cytu.be", InMemoryIpSessionStore()).forChannel("test"))
    }

    // Joining waits for this request: a slow channel page mustn't hold it up.
    @Test fun theRequestMayTakeOnlyAFewSecondsAllTold() = runBlocking {
        var callTimeoutNanos = 0L
        val slow = OkHttpClient.Builder().addInterceptor { chain ->
            callTimeoutNanos = chain.call().timeout().timeoutNanos()
            throw InterruptedIOException("timeout")
        }.build()
        val kept = InMemoryIpSessionStore().apply { save(issued) }
        assertEquals(issued, IpSessionCookies(slow, "https://cytu.be", kept).forChannel("test"))
        assertEquals(TimeUnit.MILLISECONDS.toNanos(IpSessionCookies.TIMEOUT_MS), callTimeoutNanos)
    }

    @Test fun aStoreThatFailsDoesNotStopTheConnection() = runBlocking {
        val broken = object : IpSessionStore {
            override fun load(): String? = throw IllegalStateException("disk")
            override fun save(value: String) = throw IllegalStateException("disk")
        }
        assertEquals(issued, IpSessionCookies(FakeServer(setCookie = issued).client, "https://cytu.be", broken).forChannel("test"))
    }
}
