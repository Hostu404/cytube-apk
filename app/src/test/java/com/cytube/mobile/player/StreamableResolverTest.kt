package com.cytube.mobile.player

import com.cytube.mobile.net.MediaTypes
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamableResolverTest {

    @Test
    fun playerFor_routesStreamableToNativeStreamablePlayer() {
        val player = MediaTypes.playerFor(
            type = "sb",
            hasDirect = false,
            embedPlayableSrc = "https://streamable.com/e/abc123"
        )
        assertEquals(MediaTypes.Player.STREAMABLE, player)
    }

    @Test
    fun canResolveIndependently_returnsTrueForStreamable() {
        assertTrue(MediaTypes.canResolveIndependently("sb"))
    }

    @Test
    fun resolvesStreamableJsonPayloadCorrectly() = runBlocking {
        val sampleJson = """
            {
              "status": 2,
              "title": "Test Video",
              "files": {
                "mp4": {
                  "status": 2,
                  "url": "//cdn-cf-east.streamable.com/video/mp4/test.mp4",
                  "width": 1920,
                  "height": 1080,
                  "bitrate": 3500000
                },
                "mp4-mobile": {
                  "status": 2,
                  "url": "//cdn-cf-east.streamable.com/video/mp4-mobile/test.mp4",
                  "width": 640,
                  "height": 360,
                  "bitrate": 800000
                }
              }
            }
        """.trimIndent()

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(sampleJson.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val result = StreamableResolver.resolve(mockClient, "abc123")
        assertTrue(result.isSuccess)
        val resolved = result.getOrNull()
        assertEquals("https://cdn-cf-east.streamable.com/video/mp4/test.mp4", resolved?.url)
        assertEquals("1080p", resolved?.label)
    }

    @Test
    fun rejectsInvalidStreamableId() = runBlocking {
        val mockClient = OkHttpClient()
        val result = StreamableResolver.resolve(mockClient, "invalid id with spaces")
        assertTrue(result.isFailure)
    }
}
