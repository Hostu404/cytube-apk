package com.cytube.mobile.player

import android.util.Log
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Native Streamable player resolver.
 *
 * Streamable exposes a public video retrieval endpoint:
 * https://api.streamable.com/videos/{id}
 *
 * This provides direct progressive MP4 URLs, allowing Streamable videos
 * to play natively in ExoPlayer with instant unmuted autoplay, full audio
 * decoding, hardware acceleration, PiP support, and SyncEngine precision.
 */
object StreamableResolver {

    private const val TAG = "CyTubeStreamable"
    private const val API_BASE = "https://api.streamable.com/videos"
    private val SAFE_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")

    data class Resolved(val url: String, val mimeType: String?, val label: String)

    private val cache = ConcurrentHashMap<String, Pair<Long, Resolved>>()
    private const val CACHE_MS = 10 * 60 * 1000L

    suspend fun resolve(http: OkHttpClient, id: String): Result<Resolved> = withContext(Dispatchers.IO) {
        if (!SAFE_ID_REGEX.matches(id)) {
            Log.w(TAG, "rejected malformed Streamable video id ($id)")
            return@withContext Result.failure(IllegalArgumentException("Invalid Streamable video id"))
        }
        val now = System.currentTimeMillis()
        if (cache.size > 50) {
            cache.entries.removeIf { now - it.value.first >= CACHE_MS }
        }
        cache[id]?.let { (at, r) ->
            if (now - at < CACHE_MS) return@withContext Result.success(r)
        }
        runCatching {
            val req = Request.Builder()
                .url("$API_BASE/$id")
                .build()

            val body = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Streamable API returned HTTP ${resp.code}")
                }
                resp.body?.string().orEmpty()
            }

            val json = JSONObject(body)
            val status = json.optInt("status", 2)
            if (status != 2) {
                val msg = json.optString("message", "Video not ready (status=$status)")
                throw IllegalStateException("Streamable video unavailable: $msg")
            }

            val files = json.optJSONObject("files")
                ?: throw IllegalStateException("No video streams found for Streamable item")

            val resolved = bestStream(files)
                ?: throw IllegalStateException("No playable MP4 found for Streamable video")

            Log.i(TAG, "resolved $id -> ${resolved.label} (${resolved.url})")
            cache[id] = System.currentTimeMillis() to resolved
            resolved
        }.onFailure {
            Log.w(TAG, "resolve failed for $id: ${it.javaClass.simpleName} - ${it.message}", it)
        }
    }

    private fun bestStream(files: JSONObject): Resolved? {
        var best: Resolved? = null
        var bestScore = -1L

        val keys = files.keys()
        while (keys.hasNext()) {
            val key = keys.next() as String
            val format = files.optJSONObject(key) ?: continue
            val rawUrl = format.optString("url").trim()
            if (rawUrl.isBlank()) continue
            val url = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl

            val width = format.optInt("width", 0)
            val height = format.optInt("height", 0)
            val bitrate = format.optLong("bitrate", 0L)

            val score = if (width > 0 && height > 0) {
                (width.toLong() * height.toLong()) * 1000L + bitrate
            } else {
                bitrate
            }

            if (score > bestScore || best == null) {
                bestScore = score
                val label = if (height > 0) "${height}p" else key
                best = Resolved(
                    url = url,
                    mimeType = MimeTypes.VIDEO_MP4,
                    label = label
                )
            }
        }
        return best
    }
}
