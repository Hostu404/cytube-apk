package com.cytube.mobile.player

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Layer 4b: the Google Drive player implementation.
 *
 * CyTube itself has no server-side way to play `gd` items — on the desktop
 * site this is entirely the job of a browser userscript
 * (cytube-google-drive.user.js), which calls Google's old get_video_info
 * endpoint and hands the resulting direct URLs back to the page as
 * meta.direct. That endpoint is effectively dead now (Google phased it out
 * years ago), and even when it worked it only exists as a page-side script —
 * nothing an Android app can run.
 *
 * So this talks to the endpoint Google Drive's own web player actually calls
 * today instead: a JSON playback API, keyed by an API key that ships in
 * Drive's own web client (also the one yt-dlp's Google Drive extractor uses,
 * which is how this was found and verified — that extractor is actively
 * maintained against Google's current behaviour, unlike the old userscript).
 * No login/cookies needed for a file that's shared "anyone with the link".
 *
 * Once resolved it's an ordinary progressive URL, so like YouTube it goes
 * through the same Media3 handle and therefore the same SyncEngine — no
 * second synchronisation path.
 */
object GoogleDriveResolver {

    private const val TAG = "CyTubeGDrive"
    private const val PLAYBACK_API = "https://content-workspacevideo-pa.googleapis.com/v1/drive/media"
    // Public key embedded in Google Drive's own web player; not a secret, and
    // not tied to any account. Same one yt-dlp's extractor uses.
    private const val API_KEY = "AIzaSyDVQw45DwoYh632gvsP5vPDqEKvb-Ywnb8"

    data class Resolved(val url: String, val mimeType: String?, val label: String)

    // Same reasoning as YouTubeResolver's cache: these URLs are signed and
    // time-limited, so this only needs to survive a player-surface rebuild.
    private val cache = ConcurrentHashMap<String, Pair<Long, Resolved>>()
    private const val CACHE_MS = 5 * 60 * 1000L

    suspend fun resolve(http: OkHttpClient, fileId: String): Result<Resolved> = withContext(Dispatchers.IO) {
        cache[fileId]?.let { (at, r) ->
            if (System.currentTimeMillis() - at < CACHE_MS) return@withContext Result.success(r)
        }
        runCatching {
            val req = Request.Builder()
                .url("$PLAYBACK_API/$fileId/playback?key=$API_KEY")
                // The API checks these rather than an auth header; Drive's own
                // player sends the same pair for a file that isn't private. The
                // CDN that serves the actual bytes replies "Vary: Origin", so
                // the resolved stream URL likely inherits whatever Origin this
                // lookup used — matching Referer alone wasn't enough.
                .header("Referer", "https://drive.google.com/")
                .header("Origin", "https://drive.google.com")
                .build()

            val body = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Google Drive playback lookup failed (${resp.code})")
                }
                resp.body?.string().orEmpty()
            }

            val streaming = JSONObject(body)
                .optJSONObject("mediaStreamingData")
                ?.optJSONObject("formatStreamingData")
                ?: throw IllegalStateException("No playable streams for this Google Drive video")

            // Progressive (muxed audio+video) only — same constraint as
            // YouTubeResolver: adaptive tracks are separate video-only and
            // audio-only streams that would need a MergingMediaSource, which
            // this does not do today.
            val resolved = bestProgressive(streaming.optJSONArray("progressiveTranscodes"))
                ?: throw IllegalStateException("No muxed stream for this Google Drive video")

            Log.i(TAG, "resolved $fileId -> ${resolved.label} ${resolved.mimeType}")
            cache[fileId] = System.currentTimeMillis() to resolved
            resolved
        }.onFailure {
            Log.w(TAG, "resolve failed for $fileId: ${it.javaClass.simpleName} - ${it.message}", it)
        }
    }

    private fun bestProgressive(formats: JSONArray?): Resolved? {
        if (formats == null) return null
        var best: Resolved? = null
        var bestPixels = -1
        for (i in 0 until formats.length()) {
            val fmt = formats.optJSONObject(i) ?: continue
            val url = fmt.optString("url").takeIf { it.isNotBlank() } ?: continue
            val meta = fmt.optJSONObject("transcodeMetadata")
            val width = meta?.optInt("width", 0) ?: 0
            val height = meta?.optInt("height", 0) ?: 0
            val pixels = width * height
            if (pixels < bestPixels) continue
            bestPixels = pixels
            best = Resolved(
                url = url,
                mimeType = meta?.optString("mimeType")?.ifBlank { null },
                label = if (height > 0) "${height}p" else "unknown"
            )
        }
        return best
    }
}
