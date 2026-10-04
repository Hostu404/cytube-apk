package com.cytube.mobile.player

import android.util.Log
import com.cytube.mobile.net.TextTrackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.Locale
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
    private const val MAX_CAPTION_TRACKS = 4

    data class Resolved(
        val url: String,
        val mimeType: String?,
        val label: String,
        /** The video's captions, for the CC button (see captionTracks). */
        val textTracks: List<TextTrackSource> = emptyList()
    )

    // Stream URLs are signed and time-limited, so this is a short-lived cache
    // to survive a rebuild of the player surface, not a long-term store.
    private val cache = TimedCache<String, Resolved>(ttlMs = 5 * 60 * 1000L, evictAboveSize = 20)

    @Volatile private var initialised = false

    fun init(http: OkHttpClient) {
        if (initialised) return
        // Built off Graph.http, but deliberately NOT sharing its
        // Dispatcher/ConnectionPool — same reasoning as the emote image
        // loader in CyTubeApp.kt. `newBuilder()` copies those by reference
        // by default, and NewPipeExtractor's StreamInfo.getInfo() fires a
        // whole burst of requests (player page, config, cipher fetches...)
        // for a single YouTube resolution, exactly when the player is
        // trying to start up. Without its own dispatcher that burst would
        // compete with the player's own byte-fetching (NativePlayerHandle's
        // OkHttpDataSource on Graph.mediaHttp, which shares Graph.http's
        // dispatcher) for the same limited request slots, on every YouTube
        // load and playlist advance.
        val newPipeHttp = http.newBuilder()
            .dispatcher(Dispatcher())
            .connectionPool(ConnectionPool())
            .followRedirects(true)
            .cookieJar(SessionCookieJar())
            .build()
        // Use an explicit localization to bypass region-specific consent walls
        // and ensure a consistent response format for the extractor.
        NewPipe.init(OkHttpDownloader(newPipeHttp), Localization("en", "US"), ContentCountry("US"))
        initialised = true
    }

    // YouTube video IDs are typically base64url-like (letters, digits,
    // underscore, hyphen, e.g. 11 characters) — untrusted input from a
    // room's playlist/server spliced into the StreamInfo URL. Enforce strict
    // charset validation before hitting NewPipeExtractor.
    private val SAFE_VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")

    /** Drops a cached URL for [videoId] after playback of it failed, so the
     *  next attempt (rejoining, or the item coming round again) resolves a
     *  fresh one instead of reusing the dead link until the cache expires.
     *  Called from ChannelViewModel.reportPlaybackFailure. */
    fun invalidate(videoId: String) = cache.remove(videoId)

    /** The cached result for [videoId] if there's a fresh one, without any
     *  network work — lets the player surface show an item straight away
     *  (and keep its existing player) when it was already resolved, e.g.
     *  loaded while the app was in the background. */
    fun cached(videoId: String): Resolved? = cache.get(videoId)

    suspend fun resolve(videoId: String): Result<Resolved> = withContext(Dispatchers.IO) {
        if (!SAFE_VIDEO_ID_REGEX.matches(videoId)) {
            Log.w(TAG, "rejected malformed YouTube video id: $videoId")
            return@withContext Result.failure(IllegalArgumentException("Invalid YouTube video id"))
        }
        cache.get(videoId)?.let { return@withContext Result.success(it) }
        runCatching {
            val info = StreamInfo.getInfo(
                ServiceList.YouTube,
                "https://www.youtube.com/watch?v=$videoId"
            )

            // Muxed progressive streams only. Higher resolutions are served as
            // separate video-only and audio tracks that would have to be merged
            // with a MergingMediaSource; that is a worthwhile follow-up but it
            // is not what this does today, so quality caps at the best muxed
            // stream YouTube offers (usually 360p). Prefer mp4 (H.264) over webm
            // for hardware-accelerated playback on Android TV / low-end devices.
            // isVideoOnly()/getResolution() called as methods on purpose:
            // VideoStream also has deprecated public fields of the same names,
            // which Kotlin's property syntax would pick.
            val stream = info.videoStreams
                .filter { !it.isVideoOnly() && it.playableUrl() != null }
                .maxWithOrNull(
                    compareBy<VideoStream> {
                        it.getResolution().filter(Char::isDigit).toIntOrNull() ?: 0
                    }.thenBy {
                        if (it.format?.mimeType?.contains("mp4", ignoreCase = true) == true) 1 else 0
                    }
                ) ?: throw IllegalStateException("No muxed stream for $videoId")

            // The filter above doesn't smart-cast across the lambda, so re-check.
            val url = stream.playableUrl()
                ?: throw IllegalStateException("Chosen stream has no URL for $videoId")

            val resolved = Resolved(
                url = url,
                mimeType = stream.format?.mimeType,
                label = stream.getResolution().ifEmpty { "unknown" },
                // Captions never stop the video itself resolving.
                textTracks = runCatching { captionTracks(info.subtitles) }
                    .onFailure { Log.w(TAG, "captions skipped for $videoId: ${it.message}") }
                    .getOrDefault(emptyList())
            )
            Log.i(TAG, "resolved $videoId -> ${resolved.label} ${resolved.mimeType} captions=${resolved.textTracks.size}")
            cache.put(videoId, resolved)
            resolved
        }.onFailure { Log.w(TAG, "resolve failed for $videoId: ${it.javaClass.simpleName} - ${it.message}", it) }
    }

    /**
     * The video's caption tracks, as the CC button offers them: ones people
     * wrote before YouTube's automatic ones, and the phone's own language
     * first within each, so turning CC on picks the most likely one.
     *
     * Only the phone's language and English (or, with neither, the first
     * couple listed): the player fetches every track it's given before the
     * video starts, and a popular video can have dozens, which would slow
     * every start and invite YouTube's rate limiting.
     *
     * NewPipe lists them as TTML; the same signed address with fmt=vtt
     * gives WebVTT (fmt isn't one of the signed parameters), which is what
     * the player's subtitle path expects, as it is for CyTube's own.
     */
    private fun captionTracks(subtitles: List<SubtitlesStream>): List<TextTrackSource> {
        val phoneLanguage = Locale.getDefault().language
        fun language(s: SubtitlesStream) = Locale.forLanguageTag(s.getLanguageTag()).language
        val usable = subtitles
            .filter { it.isUrl() && it.getContent().startsWith("https://") }
            .distinctBy { it.getLanguageTag() to it.isAutoGenerated() }
            .sortedWith(
                compareBy<SubtitlesStream> { it.isAutoGenerated() }
                    .thenBy { language(it) != phoneLanguage }
            )
        val wanted = usable.filter { language(it) == phoneLanguage || language(it) == "en" }
            .ifEmpty { usable.take(2) }
            .take(MAX_CAPTION_TRACKS)
        return wanted
            .mapNotNull { s ->
                val url = s.getContent().toHttpUrlOrNull()?.newBuilder()
                    ?.setQueryParameter("fmt", "vtt")?.build()?.toString() ?: return@mapNotNull null
                val language = Locale.forLanguageTag(s.getLanguageTag()).getDisplayName(Locale.getDefault())
                    .ifBlank { s.getDisplayLanguageName() }
                    .replaceFirstChar { it.titlecase(Locale.getDefault()) }
                TextTrackSource(
                    url = url,
                    contentType = "text/vtt",
                    name = if (s.isAutoGenerated()) "$language (auto-generated)" else language,
                    isDefault = false
                )
            }
    }

    /** The stream's address. NewPipe's content is either a URL or, for some
     *  delivery methods, a manifest's text itself; only a URL is playable here. */
    private fun VideoStream.playableUrl(): String? =
        if (isUrl()) getContent().takeIf { it.isNotBlank() } else null

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
