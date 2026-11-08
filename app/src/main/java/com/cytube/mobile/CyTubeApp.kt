package com.cytube.mobile

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.intercept.Interceptor
import coil.memory.MemoryCache
import coil.request.ImageResult
import com.cytube.mobile.di.Graph
import com.cytube.mobile.di.separate
import com.cytube.mobile.player.BandwidthEstimate
import com.cytube.mobile.ui.isTvDevice
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Emotes are the same handful of small images repeated thousands of times in a
 * busy channel, so they get an explicit two-tier cache. Without this Coil's
 * defaults would refetch and re-decode them far more often than necessary.
 */
class CyTubeApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // Starts listening for real bandwidth samples straight away, so the
        // first video's downloads count towards the next item's starting
        // quality (see ChannelViewModel.resolveInitialQualityIndex).
        BandwidthEstimate.init(this)
        // Both of these in the background, so the first screen doesn't wait
        // for them. The HTTP client: building it sets up TLS (a moment on a
        // slow TV stick). The home screen only uses it off the main thread,
        // and a channel, opened after that, finds it ready. (NewPipe, which
        // used to be set up with it here, is now set up at the first YouTube
        // lookup: see YouTubeResolver.init.) And this run's media cache,
        // ready before the first video (see Graph.prepareMediaCache).
        Graph.prepareHttp()
        Graph.prepareMediaCache(this)
    }

    override fun newImageLoader(): ImageLoader {
        val isTv = isTvDevice(this)
        return ImageLoader.Builder(this)
            // Its own request slots and connections (see separate), so a busy
            // chat's burst of emote images can't delay the video loading. The
            // rest (timeouts, no cookie jar: see the User-Agent comment below)
            // still comes from Graph.http.
            .okHttpClient {
                Graph.http.separate(maxRequestsPerHost = 20, idleConnections = 20)
                    // This header is specific to fetching third-party emote
                    // images, not something the login flow, channel-index
                    // scrape, or socket handshake want touched. Some emote
                    // hosts (Discord's CDN and Imgur in particular) 403 a
                    // hotlinked request carrying OkHttp's default
                    // "okhttp/x.y.z" User-Agent; a plain browser-looking one
                    // is enough to pass their hotlink-protection check,
                    // which is all this is — the same request a browser's
                    // own <img> tag would have made.
                    .addNetworkInterceptor { chain ->
                        val response = chain.proceed(
                            chain.request().newBuilder()
                                .header(
                                    "User-Agent",
                                    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 " +
                                        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                                )
                                .build()
                        )
                        // A redirect to plain http is followed over https
                        // instead, as resolveMediaUrl does for the first
                        // request: cleartext is blocked, so it would fail.
                        val location = response.header("Location")
                        if (response.isRedirect && location != null && location.startsWith("http:", ignoreCase = true)) {
                            response.newBuilder().header("Location", "https:" + location.substring(5)).build()
                        } else {
                            response
                        }
                    }
                    .build()
            }
            .components {
                add(OneFetchPerImage())
                // GIF emotes: Android's own decoder where it animates them, the
                // old Movie one where it can't (see EmoteGifDecoder). Other
                // formats (PNG, WebP, animated WebP) go to ImageDecoder on 9+.
                add(EmoteGifDecoder.Factory())
                if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory())
            }
            .allowRgb565(true)
            .memoryCache {
                MemoryCache.Builder(this)
                    // Restrict TV memory cache to 10% of app heap (15% on mobile)
                    .maxSizePercent(if (isTv) 0.10 else 0.15)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("emote_cache"))
                    .maxSizeBytes(if (isTv) 64L * 1024 * 1024 else 128L * 1024 * 1024)
                    .build()
            }
            // No forced RGB_565 on TV: the GIF decoder draws see-through
            // animated emotes in whatever format is asked for, and 565 has no
            // transparency, so they got black boxes behind them.
            // allowRgb565(true) above already uses it where it's safe
            // (images with no transparency).
            .apply { if (isTv) allowHardware(false) }
            .respectCacheHeaders(false)   // emote URLs are effectively immutable
            .crossfade(false)             // no animation cost in a scrolling list
            .build()
    }
}

/**
 * One load at a time per image address. A message's emotes start loading as
 * it arrives (EmoteImages.prefetch), and its chat row and Niconico comment
 * ask for the same images a moment later. Coil doesn't notice a load for the
 * same address already under way, so each of those started its own download
 * and showed nothing until it finished. Here a later one waits for the
 * earlier to finish, then gets it from the cache: from memory at once when
 * it's the same size, else from disk, never downloaded twice.
 *
 * Runs before Coil's own memory cache lookup, but an image nothing else is
 * loading doesn't wait, so a cached emote still shows in the same frame.
 */
private class OneFetchPerImage : Interceptor {
    private class Lock {
        val mutex = Mutex()
        /** Loads holding or waiting for [mutex]; gone at 0. */
        var users = 0
    }

    private val locks = HashMap<String, Lock>()

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = chain.request.data as? String
        if (url == null || !url.startsWith("https://")) return chain.proceed(chain.request)
        val lock = synchronized(locks) { locks.getOrPut(url) { Lock() }.also { it.users++ } }
        try {
            return lock.mutex.withLock { chain.proceed(chain.request) }
        } finally {
            synchronized(locks) { if (--lock.users == 0) locks.remove(url) }
        }
    }
}
