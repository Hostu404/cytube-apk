package com.cytube.mobile.player

import android.util.Log
import androidx.media3.common.MimeTypes
import com.cytube.mobile.net.MediaTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native PeerTube player resolver.
 *
 * PeerTube exposes a public video retrieval REST endpoint:
 * https://{domain}/api/v1/videos/{shortId}
 *
 * This provides HLS manifests (`streamingPlaylists`) and direct progressive MP4/WebM files (`files`),
 * allowing PeerTube videos to play natively in ExoPlayer with instant unmuted autoplay,
 * hardware acceleration, background audio, PiP support, and millisecond-precision sync.
 */
object PeerTubeResolver {

    private const val TAG = "CyTubePeerTube"

    // Shared with MediaTypes.knownEmbedUrl's own pt-id validation — this used
    // to be its own byte-for-byte duplicate of that regex.
    private val SAFE_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")

    data class Resolved(val url: String, val mimeType: String?, val label: String)

    private val cache = TimedCache<String, Resolved>(ttlMs = 10 * 60 * 1000L, evictAboveSize = 50)

    suspend fun resolve(http: OkHttpClient, id: String): Result<Resolved> = withContext(Dispatchers.IO) {
        val parts = id.split(";", limit = 2)
        if (parts.size != 2) {
            Log.w(TAG, "rejected malformed PeerTube video id (missing semicolon: $id)")
            return@withContext Result.failure(IllegalArgumentException("Invalid PeerTube video id format"))
        }
        val domain = parts[0]
        val videoId = parts[1]
        if (!MediaTypes.HOSTNAME_REGEX.matches(domain) || !SAFE_ID_REGEX.matches(videoId)) {
            Log.w(TAG, "rejected malformed PeerTube video id ($id)")
            return@withContext Result.failure(IllegalArgumentException("Invalid PeerTube domain or video id"))
        }

        cache.get(id)?.let { return@withContext Result.success(it) }

        runCatching {
            val req = Request.Builder()
                .url("https://$domain/api/v1/videos/$videoId")
                .build()

            val body = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("PeerTube API returned HTTP ${resp.code}")
                }
                resp.body?.string().orEmpty()
            }

            val json = JSONObject(body)
            val resolved = bestStream(json, domain)
                ?: throw IllegalStateException("No playable streams found for PeerTube video")

            Log.i(TAG, "resolved $id -> ${resolved.label} (${resolved.url})")
            cache.put(id, resolved)
            resolved
        }.onFailure {
            Log.w(TAG, "resolve failed for $id: ${it.javaClass.simpleName} - ${it.message}", it)
        }
    }

    private fun bestStream(json: JSONObject, domain: String): Resolved? {
        val streamingPlaylists = json.optJSONArray("streamingPlaylists")
        val hlsStream = bestHls(streamingPlaylists, domain)
        if (hlsStream != null) {
            return hlsStream
        }
        val files = json.optJSONArray("files")
        return bestDirectFile(files, domain)
    }

    private fun bestHls(playlists: JSONArray?, domain: String): Resolved? {
        if (playlists == null) return null
        // Was: return on the first non-blank playlistUrl. A PeerTube instance
        // can list multiple streamingPlaylists (e.g. one per resolution
        // ladder variant); taking the first meant we could hand ExoPlayer a
        // lower-resolution playlist even when a better one was later in the
        // array. Now compares maxResolution across all of them.
        var best: Resolved? = null
        var bestResolution = -1
        for (i in 0 until playlists.length()) {
            val playlist = playlists.optJSONObject(i) ?: continue
            val rawUrl = playlist.optString("playlistUrl").trim()
            if (rawUrl.isBlank()) continue
            val url = normalizeUrl(rawUrl, domain)

            var maxResolution = 0
            val files = playlist.optJSONArray("files")
            if (files != null) {
                for (j in 0 until files.length()) {
                    val fileObj = files.optJSONObject(j) ?: continue
                    val res = parseResolution(fileObj)
                    if (res > maxResolution) maxResolution = res
                }
            }

            if (maxResolution > bestResolution || best == null) {
                bestResolution = maxResolution
                val label = if (maxResolution > 0) "HLS (${maxResolution}p)" else "HLS"
                best = Resolved(
                    url = url,
                    mimeType = MimeTypes.APPLICATION_M3U8,
                    label = label
                )
            }
        }
        return best
    }

    private fun bestDirectFile(files: JSONArray?, domain: String): Resolved? {
        if (files == null) return null
        var best: Resolved? = null
        var bestScore = -1L

        for (i in 0 until files.length()) {
            val fmt = files.optJSONObject(i) ?: continue
            val rawUrl = fmt.optString("fileUrl").ifBlank { fmt.optString("fileDownloadUrl") }.trim()
            if (rawUrl.isBlank()) continue
            val url = normalizeUrl(rawUrl, domain)

            val height = parseResolution(fmt)
            val bitrate = fmt.optLong("bitrate", 0L)
            val size = fmt.optLong("size", 0L)

            val score = if (height > 0) {
                (height.toLong() * 1000L) + bitrate + (size / 1000L)
            } else {
                bitrate + (size / 1000L)
            }

            if (score > bestScore || best == null) {
                bestScore = score
                val label = if (height > 0) "${height}p" else "Direct MP4"
                val mimeType = fmt.optString("mimeType").takeIf { it.isNotBlank() } ?: MimeTypes.VIDEO_MP4
                best = Resolved(
                    url = url,
                    mimeType = mimeType,
                    label = label
                )
            }
        }
        return best
    }

    private fun parseResolution(obj: JSONObject): Int {
        val resObj = obj.optJSONObject("resolution")
        if (resObj != null) {
            val id = resObj.optInt("id", 0)
            if (id > 0) return id
            val label = resObj.optString("label")
            val parsed = label.filter { it.isDigit() }.toIntOrNull()
            if (parsed != null && parsed > 0) return parsed
        }
        return obj.optInt("resolution", 0)
    }

    private fun normalizeUrl(rawUrl: String, domain: String): String {
        return when {
            rawUrl.startsWith("//") -> "https:$rawUrl"
            rawUrl.startsWith("/") -> "https://$domain$rawUrl"
            else -> rawUrl
        }
    }
}
