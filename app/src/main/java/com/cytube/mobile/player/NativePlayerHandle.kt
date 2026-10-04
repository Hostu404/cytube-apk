package com.cytube.mobile.player

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.FilteringMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.cytube.mobile.di.Graph
import com.cytube.mobile.net.MediaFrame
import com.cytube.mobile.net.TextTrackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Media3 backend. Plays the CyTube types that are genuine media URLs — fi
 * (raw file), hl (HLS), and anything with meta.direct sources (cm custom
 * manifests, Drive with userscript metadata) — via [load], and the
 * streams the resolvers find for YouTube, Drive, Streamable and PeerTube via
 * [loadUrl].
 */
@OptIn(UnstableApi::class)
class NativePlayerHandle(val exo: ExoPlayer, context: Context) : PlayerHandle {

    // applicationContext, not the (likely Activity) context passed in — this
    // outlives any single ExoSurface composition and is only ever used to
    // reach the process-wide singleton cache (Graph.mediaCache).
    private val appContext = context.applicationContext

    init {
        // See AudioDiagnostics: why a video plays without sound.
        exo.addAnalyticsListener(AudioDiagnostics())
        exo.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                publishSubtitleOptions(tracks)
            }
        })
    }

    // ---- subtitles ----

    /**
     * Told whenever the subtitles on offer, or the one showing, change: when
     * an item with subtitle tracks loads, when one is switched on or off,
     * and when an item without any loads (empty, so the CC button goes).
     * Set by ChannelViewModel; called on the main thread.
     */
    var onSubtitlesChanged: ((SubtitleOptions) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(lastSubtitleOptions)
        }
    private var lastSubtitleOptions = SubtitleOptions.NONE

    private fun publishSubtitleOptions(tracks: Tracks) {
        val options = subtitleOptions(tracks)
        if (options != lastSubtitleOptions) {
            lastSubtitleOptions = options
            onSubtitlesChanged?.invoke(options)
        }
    }

    /**
     * Subtitle files fetched and drawn by the app rather than the player:
     * CyTube's (a manifest's textTracks, Google Drive's) and YouTube's
     * captions. Listed in the CC menu after the player's own tracks (ones
     * inside the stream). See ExternalCaptions for why.
     */
    private val externalCaptions = ExternalCaptions(exo) { publishSubtitleOptions(exo.currentTracks) }

    /** Where [externalCaptions] draws: set by the player surface to its
     *  PlayerView's subtitle view. */
    var captionOutput: ((List<Cue>) -> Unit)?
        get() = externalCaptions.output
        set(value) { externalCaptions.output = value }

    /**
     * What was last chosen with the CC button, kept from one item to the
     * next: null until then, then on or off. Subtitles never come on by
     * themselves: until CC is turned on, none show, even one a manifest or
     * stream marks as default.
     *
     * "On" carries over to the subtitle files the app fetches (CyTube's,
     * YouTube's): a later item with some starts with one showing (see
     * pickExternalOnLoad). A stream's own built-in subtitles are left to
     * the stream's own default, so turning CC on for one manifest doesn't
     * make an unrelated HLS stream start with its subtitles up. "Off"
     * switches every kind off.
     */
    private var subtitlesWanted: Boolean? = null

    /** Counts loads, so [SubtitleOptions.key] changes with every item. */
    private var loadGeneration = 0

    /**
     * Shows the [index]th subtitle track of [SubtitleOptions.names], or none
     * for null, if [key] still matches the tracks on offer (a choice made
     * from an item that has since been replaced is ignored). Remembered for
     * the items after this one.
     */
    fun selectSubtitle(index: Int?, key: String) {
        if (isReleased) return
        if (key != lastSubtitleOptions.key) {
            Log.i("CyTubePlayer", "subtitle choice ignored: made for an item that's no longer loaded")
            return
        }
        runCatching {
            val groups = subtitleGroups(exo.currentTracks)
            val builder = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
            val group = index?.let { groups.getOrNull(it) }
            // Past the player's own tracks come the app's (see subtitleOptions).
            val external = index?.minus(groups.size)?.takeIf { it in externalCaptions.tracks.indices }
            when {
                group != null -> {
                    subtitlesWanted = true
                    externalCaptions.select(null)
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                }
                external != null -> {
                    // The player's own off, so the two never show at once.
                    subtitlesWanted = true
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    externalCaptions.select(external)
                }
                else -> {
                    subtitlesWanted = false
                    externalCaptions.select(null)
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                }
            }
            exo.trackSelectionParameters = builder.build()
            // Picking one of the app's own changes no player track, so
            // nothing else would say so.
            publishSubtitleOptions(exo.currentTracks)
        }
    }

    /**
     * Before each load: no override left from the previous item's tracks,
     * and subtitles off unless CC is on (with it on, the player itself only
     * ever picks a stream track marked default). Then the subtitle files on
     * offer for it ([files]: CyTube's and the resolver's), one of which
     * starts showing straight away with CC on (see pickExternalOnLoad).
     */
    private fun applySubtitlePreference(files: List<TextTrackSource>, sameItem: Boolean) {
        loadGeneration++
        externalCaptions.reset(files, sameItem)
        val showFile = externalCaptions.selected < 0 && pickExternalOnLoad()
        val builder = exo.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setSelectUndeterminedTextLanguage(false)
        // With a file showing, the stream's own default stays off, so two
        // never show at once.
        val fileShowing = showFile || externalCaptions.selected >= 0
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subtitlesWanted != true || fileShowing)
        exo.trackSelectionParameters = builder.build()
    }

    /**
     * With CC on, starts one of the new item's subtitle files showing: the
     * one the manifest marks default, else the first (the resolver puts its
     * likeliest first). Fetched in the background, so the video doesn't
     * wait for it. Returns whether it started one.
     */
    private fun pickExternalOnLoad(): Boolean {
        if (subtitlesWanted != true) return false
        val files = externalCaptions.tracks
        if (files.isEmpty()) return false
        externalCaptions.select(files.indexOfFirst { it.isDefault }.takeIf { it >= 0 } ?: 0)
        return true
    }

    /**
     * The text tracks the CC button offers. Leaves out the closed-caption
     * track ExoPlayer adds to every MPEG-TS stream (plain HLS included) on
     * the chance its video carries CEA-608 captions: it's there whether or
     * not any exist, so it would show a CC button that does nothing. One an
     * HLS playlist actually declares has a name or language, and stays.
     */
    private fun subtitleGroups(tracks: Tracks): List<Tracks.Group> =
        tracks.groups.filter { g ->
            g.type == C.TRACK_TYPE_TEXT && g.isSupported && !isUndeclaredCaptions(g.getTrackFormat(0))
        }

    private fun isUndeclaredCaptions(f: Format): Boolean {
        val captions = listOf(f.sampleMimeType, f.codecs).any {
            it == MimeTypes.APPLICATION_CEA608 || it == MimeTypes.APPLICATION_CEA708
        }
        val language = f.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
        return captions && f.label.isNullOrBlank() && language == null
    }

    private fun subtitleOptions(tracks: Tracks): SubtitleOptions {
        val groups = subtitleGroups(tracks)
        val external = externalCaptions.tracks
        if (groups.isEmpty() && external.isEmpty()) return SubtitleOptions.NONE
        val names = groups.mapIndexed { i, g ->
            val f = g.getTrackFormat(0)
            f.label?.takeIf { it.isNotBlank() }
                ?: f.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
                    ?.let { java.util.Locale.forLanguageTag(it).displayName }
                ?: "Subtitles ${i + 1}"
        } + external.map { it.name }
        val selected = groups.indexOfFirst { it.isSelected }.takeIf { it >= 0 }
            ?: externalCaptions.selected.takeIf { it >= 0 }?.plus(groups.size)
            ?: -1
        return SubtitleOptions(
            names,
            selected,
            key = "$loadGeneration:${names.joinToString("\u001F")}"
        )
    }

    @Volatile override var isReleased = false
        private set

    override var mediaId: String? = null; private set
    override var mediaType: String? = null; private set
    override var mediaLengthSeconds: Int = 0; private set

    /**
     * Exactly what was last loaded (see [loadKey]), set only once a load has
     * gone through. Lets the player surface skip loading something this
     * handle already has: ChannelViewModel loads the next item itself while
     * the app is in the background (the UI can't), and when the UI catches
     * up it must not load that same item a second time and restart it.
     */
    var loadedKey: String? = null; private set

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
        val userAgent = if (headers.containsKey("User-Agent")) {
            headers["User-Agent"]?.ifBlank { null }
        } else {
            Graph.DEFAULT_USER_AGENT
        }
        val requestHeaders = headers.filterKeys { it != "User-Agent" && it.isNotBlank() }
        val bandwidthMeter = DefaultBandwidthMeter.getSingletonInstance(appContext)
        val upstream = OkHttpDataSource.Factory(Graph.mediaHttp)
            .setUserAgent(userAgent)
            .setTransferListener(MediaBytes.counting(bandwidthMeter))
            .apply { if (requestHeaders.isNotEmpty()) setDefaultRequestProperties(requestHeaders) }
        return CacheDataSource.Factory()
            .setCache(Graph.mediaCache(appContext))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(
                CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR or
                CacheDataSource.FLAG_IGNORE_CACHE_FOR_UNSET_LENGTH_REQUESTS
            )
    }

    private fun createMediaSourceFactory(
        headers: Map<String, String> = emptyMap()
    ): DefaultMediaSourceFactory {
        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
        return DefaultMediaSourceFactory(cachedDataSourceFactory(headers), extractorsFactory)
    }

    /** [media]'s own subtitle files (a manifest's textTracks, Google
     *  Drive's), with Drive's addresses, which come as a path on the CyTube
     *  server itself (see TextTrackSource.fromGoogleDrive), made whole. */
    private fun subtitleFiles(media: MediaFrame): List<TextTrackSource> =
        media.textTracks.map { t ->
            if (t.url.startsWith("/")) t.copy(url = Graph.BASE_URL.trimEnd('/') + t.url) else t
        }

    /**
     * Play a URL resolved elsewhere (StreamResolvers: YouTube, Google Drive,
     * Streamable, PeerTube), keeping the frame's metadata.
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
    fun loadUrl(
        media: MediaFrame,
        url: String,
        mimeType: String?,
        headers: Map<String, String> = emptyMap(),
        cacheVariant: String = "",
        /** Captions the resolver found (YouTube's), offered in the CC menu
         *  with the item's own (see ExternalCaptions). */
        resolvedTextTracks: List<TextTrackSource> = emptyList()
    ) {
        if (isReleased) return
        val previousId = mediaId
        val previousType = mediaType
        // Only after a failure (a fresh address for an expired one): any other
        // load of a looked-up stream starts where the room says.
        val keepPosition = if (runCatching { exo.playerError }.getOrNull() != null) positionToKeep(media) else null
        // Cleared first: if the load below throws, the handle must not go on
        // claiming (see hasLoaded) the PREVIOUS item's key for this new one.
        loadedKey = null
        mediaId = media.id
        mediaType = media.type
        mediaLengthSeconds = media.seconds
        Log.i("CyTubePlayer", "load type=${media.type} via=resolved mime=$mimeType headers=${headers.keys}")
        runCatching {
            val item = MediaItem.Builder().setUri(url)
                // Keyed on the item AND the format. Resolved URLs are signed
                // and change every time, so a stable key is what lets a
                // re-resolve reuse bytes already on disk; but "type:id" alone
                // meant a different format of the same video (another
                // resolution or container, if the resolver ever picks one)
                // would be read back from the same cache entry: mixed bytes
                // from two different files.
                .setCustomCacheKey("${media.type}:${media.id}:${mimeType.orEmpty()}:$cacheVariant")
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
                exo.setMediaSource(mediaSource, keepPosition ?: startPositionMs(media))
            }
            applySubtitlePreference(
                subtitleFiles(media) + resolvedTextTracks,
                sameItem = previousId == media.id && previousType == media.type
            )
            exo.prepare()
            publishSubtitleOptions(exo.currentTracks)
            exo.playWhenReady = !media.paused
            loadedKey = loadKey(media, url)
        }.onFailure { e ->
            Log.w("CyTubePlayer", "loadUrl failed in NativePlayerHandle: ${e.message}", e)
        }
    }

    /**
     * Plays [media]'s own URL (or its meta.direct source). Never throws: a
     * player that dies on bad input takes the whole app with it — an
     * unhandled exception here runs on the main thread. Failures are
     * reported through the Media3 error listener instead, which lets the
     * channel offer Compatibility View rather than crashing.
     *
     * [qualityIndex] indexes into media.direct (sorted highest-to-lowest —
     * see DirectSource.parse), for ChannelViewModel's quality adaptation;
     * out of range, or no direct sources at all, falls back to
     * [MediaFrame.bestSource].
     */
    fun load(media: MediaFrame, qualityIndex: Int = 0) {
        if (isReleased) return
        val previousId = mediaId
        val previousType = mediaType
        val keepPosition = positionToKeep(media)
        loadedKey = null   // see loadUrl
        mediaId = media.id
        mediaType = media.type
        mediaLengthSeconds = media.seconds

        // For cm/vi the id is a manifest or a page URL; the playable stream comes
        // from meta.direct. Only fi/hl have a directly playable id.
        //
        // qualityIndex is ChannelViewModel's own quality auto-adaptation
        // (0 = its default, matching bestSource exactly) — out of range for
        // THIS item (a stale index left over from a previous item that had
        // more quality options) or a media with no [direct] entries at all
        // both fall back to bestSource, same as if this parameter never
        // existed.
        runCatching {
            val source = sourceFor(media, qualityIndex)
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
                "source=${source?.quality ?: "id"} mime=${source?.contentType.orEmpty()} qualityIndex=$qualityIndex " +
                "separateAudio=${media.audioTracks.size} subtitles=${media.textTracks.size}")
            exo.setPlaybackSpeed(1f)
            // Routed through the same cached, longer-timeout data source as
            // loadUrl() below (see cachedDataSourceFactory) rather than
            // exo.setMediaItem()'s default HTTP stack — this is the main native
            // playback path (a straight "fi" file off CyTube's own playlist),
            // exactly where a large file's buffering has to hold up.
            val factory = createMediaSourceFactory()
            lastLoad = media to qualityIndex
            val mediaSource = withSeparateAudio(factory, factory.createMediaSource(item), media)
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
                val currentPosMs = keepPosition ?: startPositionMs(media)
                exo.setMediaSource(mediaSource, currentPosMs)
            }
            applySubtitlePreference(
                subtitleFiles(media),
                sameItem = previousId == media.id && previousType == media.type
            )
            exo.prepare()
            publishSubtitleOptions(exo.currentTracks)
            exo.playWhenReady = !media.paused
            loadedKey = loadKey(media, qualityIndex)
        }.onFailure { e ->
            Log.w("CyTubePlayer", "load failed in NativePlayerHandle: ${e.message}", e)
        }
    }

    /**
     * Tries the failed item again where it stopped: after an error ExoPlayer
     * keeps the item and position, and preparing it again reopens the same
     * file. For a connection that dropped (see PlaybackFailures.isTransient).
     * [rejoinLive] is for a live stream that fell so far behind that the
     * part it was playing is gone from the server: retrying there fails
     * every time, so it rejoins at the live edge instead.
     */
    fun retry(rejoinLive: Boolean = false) {
        if (isReleased) return
        runCatching {
            if (rejoinLive) exo.seekToDefaultPosition()
            exo.prepare()
        }
    }

    /**
     * Where the same item got to before a reload, or null for a different
     * item or one that never started. Includes an item stopped by an error
     * (STATE_IDLE with the error kept), so a reload after a failure picks up
     * where it failed rather than back at the item's starting position.
     */
    private fun positionToKeep(media: MediaFrame): Long? {
        if (mediaId != media.id || media.isLivestream) return null
        val state = exo.playbackState
        if (state == Player.STATE_ENDED) return null
        if (state == Player.STATE_IDLE && exo.playerError == null) return null
        return exo.currentPosition.takeIf { it > 0 }
    }

    /**
     * Makes [hasLoaded] false for the current item, so the next load of it
     * goes ahead: for a looked-up stream whose address expired, loaded again
     * from a fresh one (see PlayerSurface's Media3Surface).
     */
    fun forgetLoad() {
        loadedKey = null
    }

    /** What [load] last played, for reloading it after a failure. */
    private var lastLoad: Pair<MediaFrame, Int>? = null

    /** Whether the current item is playing a separate sound file. */
    private var separateAudioInUse = false

    /** The item whose separate sound file failed: played without it. */
    private var separateAudioFailedFor: String? = null

    /**
     * Called with a playback error before it's reported. If it came from a
     * separate sound file (see [withSeparateAudio]) — a dead link, a server
     * that's down — the item is loaded again without it, from where it got
     * to, and true is returned: the video plays on without its sound, as it
     * did before the app played these files at all, rather than failing
     * outright. An error from the video itself is left to be reported.
     */
    fun recoverFromSeparateAudioError(error: PlaybackException): Boolean {
        if (isReleased || !separateAudioInUse) return false
        val (media, quality) = lastLoad ?: return false
        // When the error says which address failed, it has to be one of the
        // sound files; when it doesn't (a file that couldn't be read), the
        // retry tells: if the video was at fault it fails again and is
        // reported then.
        val failedUris = generateSequence(error as Throwable) { it.cause }
            .filterIsInstance<HttpDataSource.HttpDataSourceException>()
            .map { it.dataSpec.uri.toString() }
            .toList()
        if (failedUris.isNotEmpty() && failedUris.none { uri -> media.audioTracks.any { it.url == uri } }) return false
        separateAudioFailedFor = media.id
        val positionMs = runCatching { exo.currentPosition }.getOrDefault(0L)
        val playing = runCatching { exo.playWhenReady }.getOrDefault(!media.paused)
        Log.w("CyTubePlayer", "separate audio failed (${error.errorCodeName}); playing the video without it")
        load(media.copy(currentTime = positionMs / 1000.0, paused = !playing), quality)
        return true
    }

    /**
     * A custom manifest can keep the sound in separate files (audioTracks)
     * for video streams that have none: the website plays one alongside the
     * video. Here the sound files are played in step with the video, and
     * any sound of the video stream's own is left out, since a stream that
     * comes with separate audio has at most a silent placeholder track —
     * left in, the player could pick it and play nothing.
     */
    private fun withSeparateAudio(
        factory: DefaultMediaSourceFactory,
        video: MediaSource,
        media: MediaFrame
    ): MediaSource {
        // Not for a live stream: a live video can't be played in step with
        // a fixed-length sound file. Nor after the sound file failed to
        // load for this item (see recoverFromSeparateAudioError).
        separateAudioInUse = media.audioTracks.isNotEmpty() && !media.isLivestream &&
            media.id != separateAudioFailedFor
        if (!separateAudioInUse) return video
        // The first one listed, as the website plays (it starts on the
        // first and only changes if the viewer picks another from its menu,
        // which the app doesn't have). Only that one is fetched.
        val track = media.audioTracks.first()
        val audio = factory.createMediaSource(
            MediaItem.Builder()
                .setUri(track.url)
                .apply { if (track.contentType.isNotBlank()) setMimeType(track.contentType) }
                .build()
        )
        val pictureOnly = FilteringMediaSource(video, setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_TEXT))
        // One player for both, on one clock: the sound can't drift from the
        // picture (the website's player nudges its separate audio back into
        // line every so often instead).
        return MergingMediaSource(pictureOnly, audio)
    }

    /**
     * Where a fresh item opens: [media]'s currentTime (ChannelViewModel.
     * plannedStart decides it). SyncEngine leaves a freshly-loaded item alone
     * for SYNC_GRACE_MS so it can build up a buffer, so opening at 0:00 and
     * waiting to be corrected would mean seconds of the wrong part of the
     * video followed by a hard seek.
     *
     * A negative currentTime is CyTube's own lead-in countdown (the group
     * hasn't started yet) rather than a real position, so that starts at 0.
     */
    private fun startPositionMs(media: MediaFrame): Long {
        val cur = media.currentTime
        if (cur.isNaN() || cur.isInfinite() || cur <= 0.0) return 0L
        val length = media.seconds
        val clamped = if (length > 0 && cur > length) length.toDouble() else cur
        return (clamped * 1000).toLong().coerceAtLeast(0L)
    }

    override val bufferedAheadSeconds: Double
        get() = if (isReleased) Double.NaN else runCatching {
            exo.totalBufferedDuration / 1000.0
        }.getOrDefault(Double.NaN)

    override fun setPlaybackRate(rate: Float) {
        if (isReleased) return
        if (rate.isNaN() || rate.isInfinite() || rate <= 0f) return
        runCatching {
            if (exo.playbackParameters.speed != rate) exo.setPlaybackSpeed(rate)
        }
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
            val params = seekParametersFor(targetMs)
            Log.i(
                "CyTubeSync",
                "seek to ${targetMs}ms from ${exo.currentPosition}ms, buffered to " +
                    "${exo.bufferedPosition}ms (${if (params == SeekParameters.NEXT_SYNC) "next keyframe" else "exact"})"
            )
            exo.setSeekParameters(params)
            exo.seekTo(targetMs)
        }
    }

    /**
     * Seeks through this handle are SyncEngine corrections (the phone's
     * on-screen scrubber talks to ExoPlayer directly and keeps the player's
     * default), and how they snap to keyframes matters a lot on movie-length
     * files, where keyframes are often 5–10s apart:
     *
     *  - Target already buffered: EXACT. The data (including the keyframe
     *    before the target) is already here, so decoding forward to the exact
     *    spot is cheap. CLOSEST_SYNC here would often snap a small forward
     *    correction BACKWARD, past the playhead, to the previous keyframe:
     *    the video visibly replays a few seconds and ends up further behind,
     *    so the next tick corrects again.
     *  - Forward, past the buffer: NEXT_SYNC. A new range request is needed
     *    anyway; starting it at the next keyframe means nothing before the
     *    target is downloaded or replayed, and it lands at or slightly ahead
     *    of the target. That small lead absorbs the rebuffer. EXACT would have to
     *    download from the previous keyframe first, making the rebuffer longer
     *    and landing further behind.
     *  - Backward: EXACT (normally inside the back buffer).
     */
    private fun seekParametersFor(targetMs: Long): SeekParameters {
        val currentMs = exo.currentPosition
        val bufferedMs = exo.bufferedPosition
        val forwardPastBuffer = targetMs > currentMs && targetMs > bufferedMs - SEEK_BUFFER_MARGIN_MS
        return if (forwardPastBuffer) SeekParameters.NEXT_SYNC else SeekParameters.EXACT
    }

    override suspend fun currentTimeSeconds(): Double = withContext(Dispatchers.Main) {
        if (isReleased) 0.0
        else runCatching { (exo.currentPosition / 1000.0).coerceAtLeast(0.0) }.getOrDefault(0.0)
    }

    /**
     * Turns video decoding off and on, leaving audio playing: off while the
     * app is in the background (outside picture-in-picture), where decoding
     * frames nobody can see just costs battery. Driven by ChannelViewModel
     * from AppVisibility. It saves decoding, not data: YouTube streams and
     * plain files carry sound and picture together, so the same bytes are
     * downloaded either way. When video comes back on, the picture may take
     * a moment to reappear (the sound carries on throughout).
     */
    fun setVideoEnabled(enabled: Boolean) {
        if (isReleased) return
        runCatching {
            val params = exo.trackSelectionParameters
            val disabled = C.TRACK_TYPE_VIDEO in params.disabledTrackTypes
            if (disabled == !enabled) return
            videoToggledAtMs = SystemClock.elapsedRealtime()
            exo.trackSelectionParameters = params.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
                .build()
        }
    }

    /**
     * When [setVideoEnabled] last actually switched video on or off. Turning
     * it back on can make ExoPlayer fetch the picture again from where it
     * is — a brief rebuffer that is the switch's own cost, not a slow
     * connection, so the stall tracking in PlayerSurface leaves it out
     * (otherwise coming back to the app could step the quality down).
     */
    @Volatile var videoToggledAtMs: Long = 0L
        private set

    override fun setVolume(volume: Float) {
        if (isReleased) return
        if (volume.isNaN() || volume.isInfinite()) return
        runCatching { exo.volume = volume.coerceIn(0f, 1f) }
    }

    /**
     * Stops and unloads the current item while the next one's stream is
     * still being looked up, so it doesn't carry on playing meanwhile. The
     * player itself stays, ready for the next load. The item is cleared out
     * of ExoPlayer too, not just stopped: a play command from the media
     * session (a headset, a TV remote) would otherwise prepare it again and
     * play the previous video under the spinner. With no [mediaId],
     * SyncEngine and the leader clock leave the player alone until the new
     * item is loaded.
     */
    fun stop() {
        if (isReleased) return
        loadedKey = null
        mediaId = null
        mediaType = null
        mediaLengthSeconds = 0
        runCatching {
            exo.stop()
            exo.clearMediaItems()
        }
    }

    /** Whether [media] is loaded (from whichever stream) and not stopped. */
    fun hasLoaded(media: MediaFrame): Boolean =
        loadedKey != null && mediaId == media.id && mediaType == media.type

    override fun release() {
        if (isReleased) return
        isReleased = true
        externalCaptions.release()
        runCatching { exo.release() }
    }

    companion object {
        /** A target this close to the end of the buffer is treated as
         *  unbuffered: EXACT still needs data past it before it can resume. */
        private const val SEEK_BUFFER_MARGIN_MS = 1_000L

        private fun sourceFor(media: MediaFrame, qualityIndex: Int) =
            media.direct.getOrNull(qualityIndex) ?: media.bestSource

        /** The address [load] plays for [media] at [qualityIndex]. */
        fun sourceUrl(media: MediaFrame, qualityIndex: Int): String =
            sourceFor(media, qualityIndex)?.link ?: media.id

        /** Identifies a [load] of [media] at [qualityIndex]. */
        fun loadKey(media: MediaFrame, qualityIndex: Int): String =
            "${media.type}:${media.id}:q$qualityIndex"

        /** Identifies a [loadUrl] of [media] from the resolved [url]. */
        fun loadKey(media: MediaFrame, url: String): String =
            "${media.type}:${media.id}:$url"
    }
}
