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
import com.cytube.mobile.player.YouTubeResolver
import okhttp3.ConnectionPool
import okhttp3.Dispatcher

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
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            // A client built off Graph.http, not Graph.http itself — but
            // deliberately NOT sharing its Dispatcher/ConnectionPool.
            // `newBuilder()` copies those by reference by default, which
            // would mean emote-image fetches compete for the exact same
            // connection slots as the video player's own byte-fetching —
            // NativePlayerHandle's OkHttpDataSource is built on this same
            // Graph.http instance. A chat-heavy channel's backlog can fire
            // a real burst of emote image requests right as a video is
            // loading, and OkHttp's default limits (64 total, 5/host) are
            // shared process-wide unless a client explicitly gets its own —
            // so giving this one its own keeps a busy chat from ever being
            // able to delay the player. Everything else (timeouts, no
            // cookie jar — see the User-Agent comment below) still comes
            // from Graph.http.
            .okHttpClient {
                Graph.http.newBuilder()
                    .dispatcher(Dispatcher())
                    .connectionPool(ConnectionPool())
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
                        chain.proceed(
                            chain.request().newBuilder()
                                .header(
                                    "User-Agent",
                                    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 " +
                                        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                                )
                                .build()
                        )
                    }
                    .build()
            }
            .components {
                // Animated GIF emotes in chat (CyTube channels use a lot of
                // them). Registered globally on the loader, but the emote
                // *picker* grid opts back out per-request — see Panels.kt —
                // since a whole grid of simultaneously-animating GIFs is real
                // decode/CPU cost for a picker that's only up for a second to
                // tap an emote.
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
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("emote_cache"))
                    .maxSizeBytes(48L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)   // emote URLs are effectively immutable
            .crossfade(false)             // no animation cost in a scrolling list
            .build()
}
