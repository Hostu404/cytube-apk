package com.cytube.mobile.di

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.cytube.mobile.data.AuthRepository
import com.cytube.mobile.data.ChannelIndexRepository
import com.cytube.mobile.net.CyTubeClient
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Deliberately a plain object rather than a DI framework. The graph is four
 * objects deep; Hilt would be more ceremony than the app has complexity.
 *
 * @OptIn(UnstableApi::class) at the object level: Media3's whole
 * datasource.cache package (SimpleCache, Cache, LeastRecentlyUsedCacheEvictor)
 * and StandaloneDatabaseProvider are marked @UnstableApi upstream — same
 * mechanism NativePlayerHandle already opts into for CacheDataSource.
 */
@OptIn(UnstableApi::class)
object Graph {

    const val BASE_URL = CyTubeClient.DEFAULT_BASE_URL

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // AuthRepository's login POST needs to read Set-Cookie off the 302 that
    // CyTube's /login returns, which OkHttp would otherwise follow and discard
    // before that header is ever seen. Scoped to its own client — built off
    // `http` so it still shares its connection pool and dispatcher — rather
    // than disabling redirects on `http` itself, which every other consumer
    // (media byte fetches, the channel-index scrape, Drive/YouTube resolving)
    // shares and none of which want redirects suppressed.
    private val authHttp: OkHttpClient by lazy {
        http.newBuilder().followRedirects(false).build()
    }

    // Dedicated to media byte fetches (NativePlayerHandle's data source), not
    // shared with `http`. `http`'s 30s read timeout is plenty for chat/API
    // calls, but a slow patch of network mid-download on a large video file
    // can go quiet for longer than that on a single read without the stream
    // actually being dead — hitting that timeout used to kill playback
    // outright (an ExoPlayer error, forcing the WebView-fallback prompt)
    // rather than just being a stutter. Built off `http` so it still shares
    // its connection pool/dispatcher.
    val mediaHttp: OkHttpClient by lazy {
        http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
    }

    private var authRepo: AuthRepository? = null

    fun auth(context: Context): AuthRepository =
        authRepo ?: AuthRepository(context.applicationContext, authHttp, BASE_URL).also { authRepo = it }

    val channelIndex: ChannelIndexRepository by lazy {
        ChannelIndexRepository(http, BASE_URL)
    }

    fun newClient(context: Context): CyTubeClient = CyTubeClient(http, BASE_URL)

    private var mediaCacheInstance: Cache? = null

    /** On-disk cache for played-back media bytes (see NativePlayerHandle),
     *  bounded by [MEDIA_CACHE_MAX_BYTES] so a long-running app can't let
     *  this grow without limit — the least-recently-used content is evicted
     *  to make room, same idea as every other bounded buffer in this app.
     *  Lives under the app's own cache dir (no storage permission needed,
     *  and Android itself is free to reclaim it under storage pressure on
     *  top of the cap here). Media3 only allows one SimpleCache instance per
     *  cache directory for the life of the process, so this must stay a
     *  true singleton rather than being built fresh per player instance. */
    fun mediaCache(context: Context): Cache =
        mediaCacheInstance ?: run {
            val appContext = context.applicationContext
            SimpleCache(
                File(appContext.cacheDir, "media"),
                LeastRecentlyUsedCacheEvictor(MEDIA_CACHE_MAX_BYTES),
                StandaloneDatabaseProvider(appContext)
            ).also { mediaCacheInstance = it }
        }

    private const val MEDIA_CACHE_MAX_BYTES = 1_024L * 1024L * 1024L // 1GB
}
