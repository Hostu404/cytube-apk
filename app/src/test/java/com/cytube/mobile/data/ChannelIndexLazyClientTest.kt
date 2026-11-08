package com.cytube.mobile.data

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** The home screen makes the repository as it's first drawn, on the main
 *  thread: the HTTP client must only be asked for when the list is fetched,
 *  which is off it (see Graph.channelIndex). */
class ChannelIndexLazyClientTest {

    @Test
    fun `the client is only asked for when the list is fetched, off the caller's thread`() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """<table><tbody><tr><td><a href="/r/foo">Foo (foo)</a></td><td>3</td><td>x</td></tr></tbody></table>"""
                            .toResponseBody("text/html".toMediaType())
                    )
                    .build()
            }
            .build()
        val asked = AtomicInteger()
        val askedOn = AtomicReference<Thread>()
        val repo = ChannelIndexRepository({ asked.incrementAndGet(); askedOn.set(Thread.currentThread()); client }, "https://cytu.be")

        assertEquals(0, asked.get())
        assertEquals(emptyList<ChannelIndexRepository.PublicChannel>(), repo.cachedChannels)

        assertEquals(listOf("foo"), repo.publicChannels(forceRefresh = true).map { it.name })
        assertEquals(1, asked.get())
        assertNotSame(Thread.currentThread(), askedOn.get())
    }
}
