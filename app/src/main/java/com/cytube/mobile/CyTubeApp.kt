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
            .okHttpClient { Graph.http }
            .components {
                // Animated GIF emotes in chat (CyTube channels use a lot of
                // them). Registered globally on the loader, but the emote
                // *picker* grid opts back out per-request — see Panels.kt —
                // since a whole grid of simultaneously-animating GIFs is real
                // decode/CPU cost for a picker that's only up for a second to
                // tap an emote.
                if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory())
                else add(GifDecoder.Factory())
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
