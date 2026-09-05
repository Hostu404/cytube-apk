package com.cytube.mobile.player

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * Layer 4a: the YouTube player implementation, in the only part that differs
 * from a plain file — turning a video id into a stream URL.
 *
 * Once resolved it is an ordinary progressive URL, so it goes through the same
 * Media3 handle and therefore the same SyncEngine as everything else. There is
 * deliberately no second synchronisation path.
 */
object YouTubeResolver {

    private const val TAG = "CyTubeYouTube"

    data class Resolved(val url: String, val mimeType: String?, val label: String)

    // Stream URLs are signed and time-limited, so this is a short-lived cache
    // to survive a rebuild of the player surface, not a long-term store.
    private val cache = ConcurrentHashMap<String, Pair<Long, Resolved>>()
    private const val CACHE_MS = 5 * 60 * 1000L

    @Volatile private var initialised = false

    fun init(http: OkHttpClient) {
        if (initialised) return
        val newPipeHttp = http.newBuilder()
            .followRedirects(true)
            .cookieJar(SessionCookieJar())
            .build()
        // Use an explicit localization to bypass region-specific consent walls
        // and ensure a consistent response format for the extractor.
        NewPipe.init(OkHttpDownloader(newPipeHttp), Localization("en", "US"), ContentCountry("US"))
        initialised = true
    }

    suspend fun resolve(videoId: String): Result<Resolved> = withContext(Dispatchers.IO) {
        cache[videoId]?.let { (at, r) ->
            if (System.currentTimeMillis() - at < CACHE_MS) return@withContext Result.success(r)
        }
        runCatching {
            val info = StreamInfo.getInfo(
                ServiceList.YouTube,
                "https://www.youtube.com/watch?v=$videoId"
            )

            // Muxed progressive streams only. Higher resolutions are served as
            // separate video-only and audio tracks that would have to be merged
            // with a MergingMediaSource; that is a worthwhile follow-up but it
            // is not what this does today, so quality caps at the best muxed
            // stream YouTube offers (usually 360p).
            val stream = info.videoStreams
                .filter { !it.isVideoOnly && !it.url.isNullOrBlank() }
                .maxByOrNull { it.resolution?.filter(Char::isDigit)?.toIntOrNull() ?: 0 }
                ?: throw IllegalStateException("No muxed stream for $videoId")

            // getUrl() is @Nullable, and the isNullOrBlank() filter above does
            // not smart-cast across the lambda, so re-check it here.
            val url = stream.url
                ?: throw IllegalStateException("Chosen stream has no URL for $videoId")

            val resolved = Resolved(
                url = url,
                mimeType = stream.format?.mimeType,
                label = stream.resolution ?: "unknown"
            )
            Log.i(TAG, "resolved $videoId -> ${resolved.label} ${resolved.mimeType}")
            cache[videoId] = System.currentTimeMillis() to resolved
            resolved
        }.onFailure { Log.w(TAG, "resolve failed for $videoId: ${it.javaClass.simpleName} - ${it.message}", it) }
    }

    /** NewPipe wants its own HTTP abstraction; reuse the app's OkHttp client. */
    private class OkHttpDownloader(private val client: OkHttpClient) : Downloader() {
        override fun execute(request: Request): Response {
            val builder = okhttp3.Request.Builder().url(request.url())
            request.headers().forEach { (name, values) ->
                builder.removeHeader(name)
                values.forEach { builder.addHeader(name, it) }
            }

            // Ensure a modern User-Agent if NewPipe didn't provide one.
            // Using a Desktop UA as a fallback often avoids mobile-specific
            // bot detection and consent walls during initial metadata extraction.
            if (request.headers()["User-Agent"].isNullOrEmpty()) {
                builder.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36")
            }

            val body = request.dataToSend()?.toRequestBody()
            builder.method(request.httpMethod(), body)

            client.newCall(builder.build()).execute().use { resp ->
                return Response(
                    resp.code,
                    resp.message,
                    resp.headers.toMultimap(),
                    resp.body?.string(),
                    resp.request.url.toString()
                )
            }
        }
    }

    /** Simple in-memory cookie storage to persist YouTube session data (like visitorData). */
    private class SessionCookieJar : CookieJar {
        private val cookieStore = ConcurrentHashMap<String, List<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore[url.host] = cookies
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val cookies = cookieStore[url.host] ?: emptyList()
            // Seed with a consent cookie if missing to bypass common YouTube walls
            if (cookies.none { it.name == "CONSENT" }) {
                val consentCookie = Cookie.Builder()
                    .name("CONSENT")
                    .value("YES+cb.20210328-17-p0.en+FX+417")
                    .domain(url.host)
                    .path("/")
                    .build()
                return cookies + consentCookie
            }
            return cookies
        }
    }
}
