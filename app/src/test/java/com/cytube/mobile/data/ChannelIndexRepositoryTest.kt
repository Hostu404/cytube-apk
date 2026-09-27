package com.cytube.mobile.data

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelIndexRepositoryTest {

    private val sampleHtml = """
        <!DOCTYPE html>
        <html>
        <head><title>CyTube</title></head>
        <body>
          <table class="table table-striped table-bordered">
            <thead><tr><th>Channel</th><th>Users</th><th>Now Playing</th></tr></thead>
            <tbody>
              <tr>
                <td><a href="/r/chillhop">Chillhop Radio (chillhop)</a></td>
                <td>42</td>
                <td>Lofi Beats to Relax/Study To</td>
              </tr>
              <tr>
                <td><a href="/r/anime_club">Anime Club (anime_club)</a></td>
                <td>15</td>
                <td>Episode 1</td>
              </tr>
            </tbody>
          </table>
        </body>
        </html>
    """.trimIndent()

    @Test
    fun `parses html channel table correctly`() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("ETag", "\"abc123etag\"")
                    .body(sampleHtml.toResponseBody("text/html".toMediaType()))
                    .build()
            }
            .build()

        val repo = ChannelIndexRepository(client, "https://cytu.be")
        val channels = repo.publicChannels(forceRefresh = true)

        assertEquals(2, channels.size)
        assertEquals("chillhop", channels[0].name)
        assertEquals("Chillhop Radio", channels[0].pageTitle)
        assertEquals(42, channels[0].userCount)
        assertEquals("Lofi Beats to Relax/Study To", channels[0].nowPlaying)

        assertEquals("anime_club", channels[1].name)
        assertEquals("Anime Club", channels[1].pageTitle)
        assertEquals(15, channels[1].userCount)
        assertEquals("Episode 1", channels[1].nowPlaying)
    }

    @Test
    fun `handles 304 Not Modified using cached channels`() = runBlocking {
        var callCount = 0
        var receivedIfNoneMatch: String?

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                callCount++
                val req = chain.request()
                receivedIfNoneMatch = req.header("If-None-Match")

                if (receivedIfNoneMatch == "\"abc123etag\"") {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(304)
                        .message("Not Modified")
                        .body("".toResponseBody("text/html".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("ETag", "\"abc123etag\"")
                        .body(sampleHtml.toResponseBody("text/html".toMediaType()))
                        .build()
                }
            }
            .build()

        val repo = ChannelIndexRepository(client, "https://cytu.be")

        // First call populates cache and saves ETag
        val firstResult = repo.publicChannels(forceRefresh = true)
        assertEquals(2, firstResult.size)
        assertEquals(1, callCount)

        // Second call with forceRefresh sends ETag and handles 304 gracefully
        val secondResult = repo.publicChannels(forceRefresh = false)
        assertEquals(2, secondResult.size)
    }

    @Test
    fun `gracefully returns empty list on network failure`() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Internal Server Error")
                    .body("Error".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()

        val repo = ChannelIndexRepository(client, "https://cytu.be")
        val channels = repo.publicChannels(forceRefresh = true)

        assertTrue(channels.isEmpty())
    }
}
