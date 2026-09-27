package com.cytube.mobile.player

import androidx.media3.common.MimeTypes
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

class PeerTubeResolverTest {

    @Test
    fun playerFor_routesPeerTubeToNativePeerTubePlayer() {
        val player = MediaTypes.playerFor(
            type = "pt",
            hasDirect = false,
            embedPlayableSrc = "https://peertube.example.com/videos/embed/abc12345"
        )
        assertEquals(MediaTypes.Player.PEERTUBE, player)
    }

    @Test
    fun canResolveIndependently_returnsTrueForPeerTube() {
        assertTrue(MediaTypes.canResolveIndependently("pt"))
    }

    @Test
    fun resolvesHlsStreamingPlaylistCorrectly() = runBlocking {
        val sampleJson = """
            {
              "id": 101,
              "name": "HLS Video",
              "streamingPlaylists": [
                {
                  "id": 1,
                  "type": 0,
                  "playlistUrl": "https://peertube.example.com/static/streaming-playlists/hls/uuid/master.m3u8",
                  "files": [
                    {
                      "resolution": { "id": 1080, "label": "1080p" },
                      "size": 50000000,
                      "fps": 60,
                      "fileUrl": "https://peertube.example.com/static/streaming-playlists/hls/uuid/1080.mp4"
                    },
                    {
                      "resolution": { "id": 720, "label": "720p" },
                      "size": 25000000,
                      "fps": 30,
                      "fileUrl": "https://peertube.example.com/static/streaming-playlists/hls/uuid/720.mp4"
                    }
                  ]
                }
              ],
              "files": []
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

        val result = PeerTubeResolver.resolve(mockClient, "peertube.example.com;hls12345")
        assertTrue(result.isSuccess)
        val resolved = result.getOrNull()
        assertEquals("https://peertube.example.com/static/streaming-playlists/hls/uuid/master.m3u8", resolved?.url)
        assertEquals(MimeTypes.APPLICATION_M3U8, resolved?.mimeType)
        assertEquals("HLS (1080p)", resolved?.label)
    }

    @Test
    fun resolvesDirectFilesFallbackCorrectly() = runBlocking {
        val sampleJson = """
            {
              "id": 102,
              "name": "Direct File Video",
              "streamingPlaylists": [],
              "files": [
                {
                  "resolution": { "id": 480, "label": "480p" },
                  "size": 10000000,
                  "fps": 30,
                  "bitrate": 800000,
                  "fileUrl": "https://peertube.example.com/static/web-videos/uuid-480.mp4"
                },
                {
                  "resolution": { "id": 1080, "label": "1080p" },
                  "size": 40000000,
                  "fps": 60,
                  "bitrate": 3000000,
                  "fileUrl": "/static/web-videos/uuid-1080.mp4"
                }
              ]
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

        val result = PeerTubeResolver.resolve(mockClient, "peertube.example.com;direct12345")
        assertTrue(result.isSuccess)
        val resolved = result.getOrNull()
        assertEquals("https://peertube.example.com/static/web-videos/uuid-1080.mp4", resolved?.url)
        assertEquals(MimeTypes.VIDEO_MP4, resolved?.mimeType)
        assertEquals("1080p", resolved?.label)
    }

    @Test
    fun rejectsInvalidPeerTubeId() = runBlocking {
        val mockClient = OkHttpClient()
        // Missing semicolon
        assertTrue(PeerTubeResolver.resolve(mockClient, "peertube.example.com").isFailure)
        // Invalid domain
        assertTrue(PeerTubeResolver.resolve(mockClient, "peertube@example.com;abc").isFailure)
        // Invalid video ID
        assertTrue(PeerTubeResolver.resolve(mockClient, "peertube.example.com;abc/../../def").isFailure)
    }
}
