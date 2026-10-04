package com.cytube.mobile

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.cytube.mobile.di.Graph
import com.cytube.mobile.player.BandwidthEstimate
import com.cytube.mobile.player.YouTubeResolver
import com.cytube.mobile.ui.isTvDevice
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import java.util.concurrent.TimeUnit

/**
 * Emotes are the same handful of small images repeated thousands of times in a
 * busy channel, so they get an explicit two-tier cache. Without this Coil's
 * defaults would refetch and re-decode them far more often than necessary.
 */
class CyTubeApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // NewPipeExtractor is a singleton and must be initialised once, before
        // any extraction, with an HTTP client it can use.
        YouTubeResolver.init(Graph.http)
        // Starts listening for real bandwidth samples straight away, so the
        // first video's downloads count towards the next item's starting
        // quality (see ChannelViewModel.resolveInitialQualityIndex).
        BandwidthEstimate.init(this)
    }

    override fun newImageLoader(): ImageLoader {
        val isTv = isTvDevice(this)
        return ImageLoader.Builder(this)
            // A client built off Graph.http, not Graph.http itself — but
            // deliberately NOT sharing its Dispatcher/ConnectionPool.
            // `newBuilder()` copies those by reference by default, which
            // would mean emote-image fetches compete for the same request
            // slots as the video player's own byte-fetching
            // (NativePlayerHandle's OkHttpDataSource uses Graph.mediaHttp,
            // which shares Graph.http's Dispatcher). A chat-heavy channel's
            // backlog can fire a burst of emote image requests right as a
            // video is loading, and a Dispatcher's limits (64 total, 5 per
            // host by default) apply across every client sharing it — so
            // this one gets its own, and a busy chat can't delay the
            // player. Everything else (timeouts, no cookie jar — see the
            // User-Agent comment below) still comes from Graph.http.
            .okHttpClient {
                Graph.http.newBuilder()
                    .dispatcher(Dispatcher().apply {
                        maxRequests = 64
                        maxRequestsPerHost = 20
                    })
                    .connectionPool(ConnectionPool(20, 5, TimeUnit.MINUTES))
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
                // Animated GIF emotes in chat and emote picker (CyTube channels
                // use a lot of them). Registered globally on the loader.
                //
                // GifDecoder is registered ahead of ImageDecoderDecoder on
                // every API level, not just <28. Coil tries factories in
                // registration order and uses the first one that claims the
                // source; platform ImageDecoder (API 28+) silently produces a
                // *static* first frame for a subset of real-world GIFs (odd
                // frame-disposal/timing metadata some emote packs use) rather
                // than failing, so it was winning the claim and quietly
                // de-animating them. GifDecoder's own Movie-based decoder is
                // the one CyTube's own web client effectively relies on and
                // handles that metadata correctly, so it goes first; that
                // also means it now handles GIFs on all API levels. Non-GIF
                // formats (PNG/WebP/etc.) aren't claimed by GifDecoder, so
                // ImageDecoderDecoder still decodes those, on API 28+.
                add(GifDecoder.Factory())
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
