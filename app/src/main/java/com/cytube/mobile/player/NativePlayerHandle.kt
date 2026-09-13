package com.cytube.mobile.player

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.cytube.mobile.di.Graph
import com.cytube.mobile.net.MediaFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Media3 backend. Covers the CyTube types that are genuine media URLs:
 * fi (raw file), hl (HLS), rt (RTMP/RTSP), cm (custom manifest) and Vimeo when
 * meta.direct is set.
 */
@OptIn(UnstableApi::class)
class NativePlayerHandle(val exo: ExoPlayer, context: Context) : PlayerHandle {

    // applicationContext, not the (likely Activity) context passed in — this
    // outlives any single ExoSurface composition and is only ever used to
    // reach the process-wide singleton cache (Graph.mediaCache).
    private val appContext = context.applicationContext

    @Volatile private var isReleased = false

    override var mediaId: String? = null; private set
    override var mediaType: String? = null; private set
    override var mediaLengthSeconds: Int = 0; private set

    override val isPaused: Boolean
        get() = if (isReleased) true else runCatching { !exo.playWhenReady }.getOrDefault(true)

    override val isBuffering: Boolean
        get() = if (isReleased) false else runCatching {
            exo.playbackState == Player.STATE_BUFFERING || exo.playbackState == Player.STATE_IDLE || (exo.isLoading && !exo.isPlaying)
        }.getOrDefault(false)

    override val isPlaying: Boolean
        get() = if (isReleased) false else runCatching { exo.isPlaying }.getOrDefault(false)

    override val isNative: Boolean get() = true

    /**
     * Every byte fetched for playback — whether the raw source straight off
     * CyTube's playlist (load()) or a resolver-supplied URL (loadUrl()) —
     * goes through this: Graph.mediaHttp instead of the default HTTP stack
     * (a longer read timeout tuned for large files, see Graph.kt), wrapped
     * in a disk cache so a SyncEngine hard-seek or a rejoin landing
     * somewhere already downloaded doesn't re-fetch it over the network.
     * FLAG_IGNORE_CACHE_ON_ERROR: a cache write failure (e.g. disk pressure)
     * should fall back to reading straight from upstream, not take playback
     * down with it.
     */
    private fun cachedDataSourceFactory(headers: Map<String, String> = emptyMap()): DataSource.Factory {
        val upstream = OkHttpDataSource.Factory(Graph.mediaHttp)
            .apply { if (headers.isNotEmpty()) setDefaultRequestProperties(headers) }
        return CacheDataSource.Factory()
            .setCache(Graph.mediaCache(appContext))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private fun createMediaSourceFactory(headers: Map<String, String> = emptyMap()): DefaultMediaSourceFactory {
        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
        return DefaultMediaSourceFactory(cachedDataSourceFactory(headers), extractorsFactory)
    }

    /**
     * Play a URL resolved elsewhere (NewPipe, GoogleDriveResolver), keeping the
     * frame's metadata.
     *
     * These are signed CDN URLs (Google's "gvs" video servers, the same infra
     * behind googlevideo.com) tied to the network path that resolved them.
     * Android's default data source (DefaultHttpDataSource, built on
     * HttpURLConnection) can take a different path than the OkHttpClient the
     * resolvers used to look the URL up in the first place — enough of a
     * mismatch for the CDN to 403 it even though the URL itself is valid. Using
     * an OkHttpClient built off the SAME one (Graph.mediaHttp, layered on
     * Graph.http — see cachedDataSourceFactory) for the actual byte fetch
     * keeps the path consistent. [headers] carries whatever the resolver says
     * the stream itself needs (e.g. Referer) on top of that.
     */
    fun loadUrl(media: MediaFrame, url: String, mimeType: String?, headers: Map<String, String> = emptyMap()) {
        if (isReleased) return
        mediaId = media.id
        mediaType = media.type
        mediaLengthSeconds = media.seconds
        Log.i("CyTubePlayer", "load type=${media.type} via=resolved mime=$mimeType headers=${headers.keys}")
        runCatching {
            val item = MediaItem.Builder().setUri(url)
                .apply { if (!mimeType.isNullOrBlank()) setMimeType(mimeType) }
                // Read by the MediaSession (see PlayerSurface's ExoSurface) to
                // populate whatever system Now Playing UI is showing — without
                // this, a hardware remote's transport overlay or Alexa's own
                // response just has a blank title to show for what's playing.
                .setMediaMetadata(MediaMetadata.Builder().setTitle(media.title).build())
                .build()
            val mediaSource = createMediaSourceFactory(headers).createMediaSource(item)
            exo.setPlaybackSpeed(1f)
            // Seed the real starting position instead of always beginning at 0 —
            // see the comment on startPositionMs() below for why this matters.
            if (media.isLivestream) {
                exo.setMediaSource(mediaSource)
            } else {
                exo.setMediaSource(mediaSource, startPositionMs(media))
            }
            exo.prepare()
            exo.playWhenReady = (!media.paused) && (media.currentTime >= 0)
        }.onFailure { e ->
            Log.w("CyTubePlayer", "loadUrl failed in NativePlayerHandle: ${e.message}", e)
        }
    }

    /**
     * Never throws. A player that dies on bad input takes the whole app with
     * it — an unhandled exception here runs on the main thread. Failures are
     * reported through the Media3 error listener instead, which lets the
     * channel offer Compatibility View rather than crashing.
     */
    override fun load(media: MediaFrame, qualityIndex: Int) {
        if (isReleased) return
        mediaId = media.id
        mediaType = media.type
        mediaLengthSeconds = media.seconds

        // For cm/vi the id is a manifest or a page URL; the playable stream comes
        // from meta.direct. Only fi/hl/rt have a directly playable id.
        //
        // qualityIndex is ChannelViewModel's own quality auto-adaptation
        // (0 = its default, matching bestSource exactly) — out of range for
        // THIS item (a stale index left over from a previous item that had
        // more quality options) or a media with no [direct] entries at all
        // both fall back to bestSource, same as if this parameter never
        // existed.
        runCatching {
            val source = media.direct.getOrNull(qualityIndex) ?: media.bestSource
            val metadata = MediaMetadata.Builder().setTitle(media.title).build()
            val item = if (source != null) {
                MediaItem.Builder()
                    .setUri(source.link)
                    .apply { if (source.contentType.isNotBlank()) setMimeType(source.contentType) }
                    .setMediaMetadata(metadata)
                    .build()
            } else {
                MediaItem.fromUri(media.id).buildUpon().setMediaMetadata(metadata).build()
            }
            Log.i("CyTubePlayer", "native load type=${media.type} " +
                "source=${source?.quality ?: "id"} mime=${source?.contentType.orEmpty()} qualityIndex=$qualityIndex")
            exo.setPlaybackSpeed(1f)
            // Routed through the same cached, longer-timeout data source as
            // loadUrl() below (see cachedDataSourceFactory) rather than
            // exo.setMediaItem()'s default HTTP stack — this is the main native
            // playback path (a straight "fi" file off CyTube's own playlist),
            // exactly where a large file's buffering has to hold up.
            val mediaSource = createMediaSourceFactory().createMediaSource(item)
            // Seed the real starting position instead of always beginning at 0 —
            // see the comment on startPositionMs() below for why this matters.
            // Livestreams are excluded: their currentTime is CyTube's own
            // elapsed-seconds counter for the item, not a position within
            // ExoPlayer's live window, so seeking to it can land outside the
            // window entirely. Leaving them on the no-arg overload keeps the
            // prior (correct) behaviour of joining at the live edge.
            if (media.isLivestream) {
                exo.setMediaSource(mediaSource)
            } else {
                exo.setMediaSource(mediaSource, startPositionMs(media))
            }
            exo.prepare()
            exo.playWhenReady = (!media.paused) && (media.currentTime >= 0)
        }.onFailure { e ->
            Log.w("CyTubePlayer", "load failed in NativePlayerHandle: ${e.message}", e)
        }
    }

    /**
     * Neither load() nor loadUrl() used to pass a start position at all, so
     * every fresh item — joining a channel already in progress, or a
     * playlist switch — began playing from 0:00 no matter where the room
     * actually was. SyncEngine then leaves a freshly-loaded item alone for
     * SYNC_GRACE_MS (see ChannelViewModel) so it can build up a real buffer
     * before position-correcting it, which meant up to six full seconds of
     * watching the wrong part of the video before the first correction —
     * usually a jarring hard seek once the grace window ended, since the
     * diff from 0:00 is almost always past HARD_SEEK_THRESHOLD. Seeding the
     * real position here means the grace window is spent near the right
     * spot to begin with, not at the start of the file.
     *
     * A negative currentTime is CyTube's own lead-in countdown (the group
     * hasn't started yet) rather than a real position, so that still starts
     * at 0 like before.
     */
    private fun startPositionMs(media: MediaFrame): Long {
        val cur = media.currentTime
        if (cur.isNaN() || cur.isInfinite() || cur <= 0.0) return 0L
        val length = media.seconds
        val clamped = if (length > 0 && cur > length) length.toDouble() else cur
        return (clamped * 1000).toLong().coerceAtLeast(0L)
    }

    override fun play() {
        if (isReleased) return
        runCatching { exo.playWhenReady = true }
    }

    override fun pause() {
        if (isReleased) return
        runCatching { exo.playWhenReady = false }
    }

    override fun seekTo(seconds: Double) {
        if (isReleased) return
        if (seconds.isNaN() || seconds.isInfinite()) return
        runCatching {
            val length = mediaLengthSeconds
            val targetSeconds = if (length > 0 && seconds > length) length.toDouble() else seconds
            val targetMs = (targetSeconds * 1000).toLong().coerceAtLeast(0L)
            exo.seekTo(targetMs)
        }
    }

    override suspend fun currentTimeSeconds(): Double = withContext(Dispatchers.Main) {
        if (isReleased) 0.0
        else runCatching { (exo.currentPosition / 1000.0).coerceAtLeast(0.0) }.getOrDefault(0.0)
    }

    override fun setVolume(volume: Float) {
        if (isReleased) return
        if (volume.isNaN() || volume.isInfinite()) return
        runCatching { exo.volume = volume.coerceIn(0f, 1f) }
    }

    override fun release() {
        if (isReleased) return
        isReleased = true
        runCatching { exo.release() }
    }
}
