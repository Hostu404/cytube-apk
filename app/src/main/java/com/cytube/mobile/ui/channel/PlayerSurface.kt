package com.cytube.mobile.ui.channel

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.TextureView
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import com.cytube.mobile.di.Graph
import com.cytube.mobile.net.MediaFrame
import com.cytube.mobile.net.MediaTypes
import com.cytube.mobile.player.GoogleDriveResolver
import com.cytube.mobile.player.NativePlayerHandle
import com.cytube.mobile.player.PlayerHandle
import com.cytube.mobile.player.YouTubeResolver
import kotlinx.coroutines.delay

/**
 * Layer 4: player implementations.
 *
 * NATIVE and NEWPIPE are the same ExoPlayer surface — the only difference is
 * where the URL comes from. That is deliberate: it means both go through the
 * same PlayerHandle and therefore the same SyncEngine, so switching between a
 * YouTube item and a film mid-playlist changes the source and nothing else.
 */
@Composable
fun PlayerSurface(
    media: MediaFrame?,
    player: MediaTypes.Player,
    showControls: Boolean,
    onHandle: (PlayerHandle?) -> Unit,
    onFailed: (String) -> Unit,
    epoch: Int = 0,
    modifier: Modifier = Modifier,
    /** Fires right as each item's first frame renders, and then again on a
     *  slow, fixed interval for as long as that item keeps playing — see
     *  ExoSurface for why a tiny downsampled TextureView grab is cheap
     *  enough to repeat every few seconds without it costing anything
     *  worth worrying about, unlike sampling every frame would be. Used to
     *  color the ambient glow behind the windowed player, which crossfades
     *  between whatever colors arrive here rather than snapping; null for
     *  EMBED/WEB, which have no ExoPlayer to snapshot. */
    onFrameSnapshot: ((Bitmap) -> Unit)? = null,
    /** True while the app is backgrounded and not floating in PiP — i.e.
     *  there's definitely no video actually on screen right now. ExoSurface
     *  uses this to disable the video track and keep decoding audio only,
     *  which is real GPU/decoder work saved rather than a cosmetic switch
     *  (see ExoSurface's own comment). Has no effect on EMBED/WEB, which
     *  don't own an ExoPlayer to begin with. */
    audioOnly: Boolean = false
) {
    val embedSrc = media?.embedSrc
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        when {
            media == null -> Message("Nothing is playing")
            player == MediaTypes.Player.NATIVE ->
                ExoSurface(media, null, showControls, onHandle, onFailed, epoch, onFrameSnapshot, audioOnly)
            player == MediaTypes.Player.NEWPIPE ->
                NewPipeSurface(media, showControls, onHandle, onFailed, epoch, onFrameSnapshot, audioOnly)
            player == MediaTypes.Player.GDRIVE ->
                GDriveSurface(media, showControls, onHandle, onFailed, epoch, onFrameSnapshot, audioOnly)
            player == MediaTypes.Player.EMBED && embedSrc != null ->
                // Reuses the same backgrounded signal ExoSurface uses for
                // audioOnly, but a WebView has no "drop video, keep audio"
                // switch the way ExoPlayer's track selection does — see
                // EmbedSurface's own comment on why this means a real pause,
                // audio included, rather than video-only.
                EmbedSurface(embedSrc, paused = audioOnly)
            // WEB is handled by the channel screen, which swaps in the whole
            // CyTube page rather than a player.
            else -> Message("${MediaTypes.label(media.type)} needs Compatibility View.")
        }
    }
}

/**
 * A provider's own embeddable iframe (cu/bc/bn — meta.embed.src), hosted in a
 * WebView that's just the video surface. This is deliberately NOT the whole
 * CyTube page: chat, playlist, users and sync all stay native around it, and
 * the WebView here only ever has to render one already-built embed URL
 * (e.g. an "?embedded=True" view link) rather than run the channel's own
 * page scripts inside a stripped-down WebView.
 *
 * No PlayerHandle comes out of this — there is no ExoPlayer to hand over, so
 * SyncEngine leaves this item alone entirely (ChannelViewModel.onTimeUpdate
 * bails out whenever `player` is null), exactly like Compatibility View does
 * for WEB. The embed manages its own playback pace.
 */
@Composable
private fun EmbedSurface(
    embedSrc: String,
    /** True while backgrounded-and-not-PiP (see PlayerSurface's own doc on
     *  this param). Unlike ExoSurface's audioOnly, there is no way to tell
     *  an arbitrary provider's embedded page to stop decoding video while
     *  leaving its audio running — that would mean reaching into whatever
     *  player JS the embed itself runs, which varies per provider and isn't
     *  something this WebView controls. WebView.onPause()/onResume() is the
     *  closest available lever, and it stops everything, audio included —
     *  an embed genuinely goes silent while the app is backgrounded, unlike
     *  a native/NewPipe/GDrive item playing alongside it would. */
    paused: Boolean = false
) {
    val context = LocalContext.current
    val embedHost = remember(embedSrc) { Uri.parse(embedSrc).host }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        update = { view -> if (paused) view.onPause() else view.onResume() },
        factory = { ctx ->
            WebView(ctx).apply {
                // The default WebView canvas is white, and it paints that
                // white before the page's own CSS ever gets a chance to load
                // — the flash (and often a lingering white margin around
                // whatever the page doesn't fill) is what was "ruining the
                // immersion" here. Black is the one background that's always
                // right for a video surface sitting in an otherwise-black
                // player area.
                setBackgroundColor(android.graphics.Color.BLACK)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = android.view.View.OVER_SCROLL_NEVER
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    allowFileAccess = false
                    allowContentAccess = false
                    // A popup/new-window is how some embed players try to
                    // "open in a new tab" rather than navigate the iframe in
                    // place; there is nowhere for that to go here, so it's
                    // refused outright rather than silently doing nothing.
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        // Only a top-level navigation away from the embed's
                        // own host counts as "this isn't the video anymore" —
                        // a subframe the embed itself creates (its own
                        // player chrome, an ad/asset host, etc.) needs to load
                        // in place same as in WebCompatView.
                        if (!request.isForMainFrame) return false
                        if (request.url.host == embedHost) return false
                        openInBrowser(context, request.url.toString())
                        return true
                    }

                    // setBackgroundColor above only covers the WebView's own
                    // canvas — it does nothing about a white background the
                    // page's own CSS paints on top of it, which is exactly
                    // what most bare video-embed pages do (a plain <body>
                    // with no background rule at all defaults to white).
                    // Forcing it dark here, once the page has actually
                    // loaded, is the only way to reach that.
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript(
                            "document.documentElement.style.background='#000';" +
                                "document.body.style.background='#000';" +
                                "document.body.style.margin='0';",
                            null
                        )
                    }
                }
                loadUrl(embedSrc)
            }
        }
    )
}

/** Resolves a YouTube id to a stream URL, then hands over to the normal player. */
@Composable
private fun NewPipeSurface(
    media: MediaFrame,
    showControls: Boolean,
    onHandle: (PlayerHandle?) -> Unit,
    onFailed: (String) -> Unit,
    epoch: Int,
    onFrameSnapshot: ((Bitmap) -> Unit)? = null,
    audioOnly: Boolean = false
) {
    var resolved by remember(media.id, epoch) {
        mutableStateOf<YouTubeResolver.Resolved?>(null)
    }
    var error by remember(media.id, epoch) { mutableStateOf<String?>(null) }

    LaunchedEffect(media.id, epoch) {
        resolved = null
        error = null
        YouTubeResolver.resolve(media.id)
            .onSuccess { resolved = it }
            .onFailure {
                val why = "YouTube extraction failed (${it.javaClass.simpleName})"
                error = why
                onFailed(why)
            }
    }

    when {
        error != null -> Message(error!!)
        resolved == null -> CircularProgressIndicator()
        else -> ExoSurface(
            media, ResolvedSource(resolved!!.url, resolved!!.mimeType),
            showControls, onHandle, onFailed, epoch, onFrameSnapshot, audioOnly
        )
    }
}

/**
 * Google's CDN hotlink-checks the actual video request the same way the
 * lookup itself is checked (see GoogleDriveResolver) — without this the
 * stream URL resolves fine but ExoPlayer's request for the video bytes comes
 * back a bad HTTP status, because plain setMediaItem sends no Referer at all.
 * The CDN's own 403 replies "Vary: Origin", so Origin is sent alongside
 * Referer here too — a browser would send both automatically.
 */
private val GDRIVE_STREAM_HEADERS = mapOf(
    "Referer" to "https://drive.google.com/",
    "Origin" to "https://drive.google.com",
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"
)

/** Resolves a Google Drive file id to a stream URL, then hands over to the normal player. */
@Composable
private fun GDriveSurface(
    media: MediaFrame,
    showControls: Boolean,
    onHandle: (PlayerHandle?) -> Unit,
    onFailed: (String) -> Unit,
    epoch: Int,
    onFrameSnapshot: ((Bitmap) -> Unit)? = null,
    audioOnly: Boolean = false
) {
    var resolved by remember(media.id, epoch) {
        mutableStateOf<GoogleDriveResolver.Resolved?>(null)
    }
    var error by remember(media.id, epoch) { mutableStateOf<String?>(null) }

    LaunchedEffect(media.id, epoch) {
        resolved = null
        error = null
        GoogleDriveResolver.resolve(Graph.http, media.id)
            .onSuccess { resolved = it }
            .onFailure {
                val why = "Google Drive extraction failed: ${it.message ?: it.javaClass.simpleName}"
                Log.w("CyTubePlayer", "GDrive lookup failed id=${media.id}", it)
                error = why
                onFailed(why)
            }
    }

    when {
        error != null -> Message(error!!)
        resolved == null -> CircularProgressIndicator()
        else -> ExoSurface(
            media, ResolvedSource(resolved!!.url, resolved!!.mimeType, GDRIVE_STREAM_HEADERS),
            showControls, onHandle, onFailed, epoch, onFrameSnapshot, audioOnly
        )
    }
}

/**
 * Just what ExoSurface actually needs from a resolved stream. YouTubeResolver
 * and GoogleDriveResolver each keep their own richer `Resolved` (with a
 * quality label used only for logging) — this is the common shape they both
 * boil down to before playback. `headers` is empty for YouTube; NewPipe's
 * resolved googlevideo.com URLs don't need any.
 */
private data class ResolvedSource(
    val url: String,
    val mimeType: String?,
    val headers: Map<String, String> = emptyMap()
)

@OptIn(UnstableApi::class)
@Composable
private fun ExoSurface(
    media: MediaFrame,
    resolved: ResolvedSource?,
    showControls: Boolean,
    onHandle: (PlayerHandle?) -> Unit,
    onFailed: (String) -> Unit,
    epoch: Int,
    onFrameSnapshot: ((Bitmap) -> Unit)? = null,
    audioOnly: Boolean = false
) {
    val context = LocalContext.current
    val exo = remember(epoch) { ExoPlayer.Builder(context).build() }
    val handle = remember(exo) { NativePlayerHandle(exo) }

    // Backgrounded-but-not-PiP: nothing is actually on screen, so decoding
    // and rendering video frames the user can't see is pure waste — this
    // disables just the video track and lets ExoPlayer keep decoding audio
    // only, same idea as a music app playing with the screen off. Re-enabled
    // the moment audioOnly goes false (foregrounded again, or PiP started —
    // see ChannelScreen, which never passes audioOnly=true while in PiP).
    // trackSelectionParameters is cheap to rebuild and safe to set mid-playback;
    // ExoPlayer just reselects tracks on the next internal cycle.
    LaunchedEffect(exo, audioOnly) {
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly)
            .build()
    }

    // Exposes this ExoPlayer to the system: a Fire TV remote's dedicated
    // media keys, Alexa's "pause"/"resume" voice commands, and any system
    // Now Playing surface all reach whichever app currently holds the
    // active session — Media3 keeps this session's playback state and
    // metadata in sync with `exo` on its own, so there is nothing else to
    // wire up here beyond creating and releasing it alongside the player.
    // Scoped to the player's own lifetime (same as the error/frame listener
    // below), not a standalone service — playback keeps running via
    // ExoPlayer's own lifecycle when backgrounded outside of PiP (only the
    // video track gets dropped, see audioOnly above), so there is no "still
    // playing but the session's gone" gap to cover.
    //
    // The id MUST be unique across every session live in the process at
    // once, not just "one per ExoSurface" — Compose creates the new
    // ExoPlayer/MediaSession pair for a bumped epoch (e.g. the manual
    // refresh button) as part of composing this recomposition, but the OLD
    // pair's DisposableEffect cleanup below doesn't run until the effects
    // phase right after, so for one brief moment both the old and new
    // session exist together. Two sessions both using Media3's default
    // empty-string id crashed on exactly that overlap with "Session ID
    // must be unique" the moment the button was tapped. A process-wide
    // counter guarantees they never collide, regardless of epoch, and
    // regardless of two different channels' ExoSurfaces overlapping too.
    val mediaSession = remember(exo) {
        MediaSession.Builder(context, exo)
            .setId("cytube-${nextMediaSessionId()}")
            .build()
    }
    // Set from the AndroidView factory below once the PlayerView actually
    // exists, and read from the listener's onRenderedFirstFrame — a plain
    // mutable holder rather than Compose state, since nothing here needs to
    // recompose when it changes; the listener just wants whatever the
    // current view is at the moment a frame renders.
    val playerViewRef = remember { arrayOfNulls<PlayerView>(1) }

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            // The immediate snapshot: taken the moment a new item's first
            // frame actually renders, so the glow doesn't sit on the
            // PREVIOUS item's color for the first few seconds of a new one.
            // Media3 also calls this on a media-source swap within the same
            // ExoPlayer instance (epoch unchanged, e.g. a playlist advance),
            // which is exactly when a fresh snapshot is wanted too. The
            // periodic resample loop below (see the LaunchedEffect right
            // after this listener) is what keeps the color moving with the
            // video for the rest of that item's runtime, rather than this
            // one-shot being the only update it ever gets.
            override fun onRenderedFirstFrame() {
                val snapshot = onFrameSnapshot ?: return
                val textureView = playerViewRef[0]?.videoSurfaceView as? TextureView ?: return
                // TextureView.getBitmap(w, h) downsamples internally rather
                // than copying the full-resolution frame out first — this is
                // already about as cheap as a frame grab gets. This call site
                // only fires once per item (right as it starts); the
                // periodic resample loop below is the separate, ongoing one.
                runCatching { textureView.getBitmap(AMBIENT_SAMPLE_SIZE, AMBIENT_SAMPLE_SIZE) }
                    .getOrNull()
                    ?.let(snapshot)
            }

            override fun onPlayerError(error: PlaybackException) {
                // ERROR_CODE_IO_BAD_HTTP_STATUS alone doesn't say WHICH status,
                // and that's the difference between "fixable" (wrong header)
                // and "not fixable from here" (403 on a private file). Walk the
                // cause chain for the underlying HTTP exception so the reason
                // shown to the user — and logged — actually says which.
                val http = generateSequence(error as Throwable) { it.cause }
                    .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()
                    .firstOrNull()
                val detail = if (http != null) {
                    "${error.errorCodeName} (HTTP ${http.responseCode})"
                } else {
                    error.errorCodeName
                }
                // Header/body VALUES are deliberately never logged here — a
                // CDN error response could carry a Set-Cookie or other
                // identifying header, and every other call site in this app
                // is careful never to put that kind of thing in Logcat.
                // Names alone are enough to tell what came back.
                Log.w("CyTubePlayer", "Media3 error type=${media.type} code=$detail id=${media.id} " +
                    "responseHeaderNames=${http?.headerFields?.keys}")
                onFailed(detail)
            }
        }
        exo.addListener(listener)
        onDispose { exo.removeListener(listener) }
    }

    // Keeps the ambient glow actually tracking the video instead of freezing
    // on whatever color the first frame happened to be — the gap the one-shot
    // snapshot above left. Deliberately a slow poll rather than a frame
    // callback: AMBIENT_RESAMPLE_INTERVAL_MS is long enough that this is a
    // handful of tiny 16x16 TextureView grabs per minute, not a per-frame
    // cost, and ChannelScreen already crossfades every new color in over half
    // a second, so infrequent sampling still reads as smooth rather than a
    // visible jump. Skipped entirely while paused — nothing new to sample,
    // and a paused screen is exactly the "avoid processing when paused"
    // case — and it costs nothing at all when onFrameSnapshot is null
    // (ambient glow can't be shown for this surface, e.g. EMBED/WEB).
    LaunchedEffect(exo) {
        val snapshot = onFrameSnapshot ?: return@LaunchedEffect
        while (true) {
            delay(AMBIENT_RESAMPLE_INTERVAL_MS)
            if (!exo.isPlaying) continue
            val textureView = playerViewRef[0]?.videoSurfaceView as? TextureView ?: continue
            runCatching { textureView.getBitmap(AMBIENT_SAMPLE_SIZE, AMBIENT_SAMPLE_SIZE) }
                .getOrNull()
                ?.let(snapshot)
        }
    }

    DisposableEffect(handle) {
        onDispose {
            onHandle(null)
            // Session first, then the player it wraps — releasing in the
            // other order would leave the session momentarily pointing at
            // an already-released player.
            mediaSession.release()
            exo.release()
        }
    }

    // onHandle (which flows straight into ChannelViewModel.attachPlayer and
    // client.signalPlayerReady()) used to fire from the DisposableEffect
    // above, which runs as soon as this composable enters composition —
    // before this LaunchedEffect's coroutine had actually called load()/
    // loadUrl() on the ExoPlayer at all. That told both the ViewModel and
    // the server "the player is ready" while `exo` still had no media
    // source, no prepare() call, nothing. The very first MediaTimeUpdate
    // that arrived in that window (routine on a busy channel; only a
    // matter of timing) reached SyncEngine.apply() with a player whose
    // mediaLengthSeconds was still 0, which happened to be guarded, but
    // signalPlayerReady() had no such guard — the server could already be
    // counting this client as caught up before it had loaded a single
    // frame. Switching to a YouTube item is exactly where this mattered
    // most: NewPipe's resolve step (its own network round trip) delays
    // load()/loadUrl() by a second or more compared to a direct file, so
    // the premature "ready" signal and the premature attach both had much
    // more time to be acted on before the player actually had anything to
    // play — which is what turned the first few seconds of a movie-to-
    // YouTube switch into a hard-seek/rebuffer loop as SyncEngine kept
    // correcting a "player" that had only just started actually loading.
    // Calling onHandle after the load/loadUrl call below closes that gap:
    // the ViewModel never sees this handle, and the server is never told
    // we're ready, until the media source has genuinely been handed to
    // ExoPlayer.
    LaunchedEffect(media.id, media.type, resolved) {
        if (resolved != null) {
            handle.loadUrl(media, resolved.url, resolved.mimeType, resolved.headers)
        } else {
            handle.load(media)
        }
        onHandle(handle)
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            // Inflated from res/layout/player_view.xml rather than
            // `PlayerView(ctx)` so it gets a TextureView instead of the
            // default SurfaceView — see that file for why: a SurfaceView's
            // Surface is destroyed and recreated whenever this view is
            // reparented, which is exactly what happens on every fullscreen
            // and PiP transition now that the player is hoisted with
            // movableContentOf. That teardown/rebuild is what showed up as a
            // stutter right at the moment fullscreen or PiP toggled.
            val view = LayoutInflater.from(ctx)
                .inflate(com.cytube.mobile.R.layout.player_view, null, false) as PlayerView
            view.apply {
                this.player = exo
                useController = showControls
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            }.also { playerViewRef[0] = it }
        },
        update = { it.useController = showControls }
    )
}

/** Side length (px) of the TextureView snapshot used for the ambient glow —
 *  tiny on purpose, since it's only ever averaged into one color. */
private const val AMBIENT_SAMPLE_SIZE = 16

/** How often the ambient glow resamples the video while it's playing — see
 *  the LaunchedEffect in ExoSurface. Still infrequent (a poll, not a frame
 *  hook) but shorter than it once was: ChannelScreen's crossfade now runs
 *  nearly this whole interval on purpose, so the glow is close to always in
 *  motion rather than easing in and then sitting still — a shorter interval
 *  is what keeps that continuous feel from also meaning a longer crossfade
 *  per step, since each sample only nudges the color (see
 *  AMBIENT_SAMPLE_BLEND in ChannelScreen) rather than setting it outright. */
private const val AMBIENT_RESAMPLE_INTERVAL_MS = 3_000L

/** Process-wide, ever-increasing — see the doc comment on ExoSurface's
 *  mediaSession for why every MediaSession this app ever creates needs a
 *  genuinely unique id, not just one that's unique per ExoSurface call. */
private val mediaSessionIdCounter = java.util.concurrent.atomic.AtomicInteger(0)
private fun nextMediaSessionId(): Int = mediaSessionIdCounter.getAndIncrement()

/**
 * Cheap, good-enough dominant-color extraction for the ambient glow: average
 * every pixel of the tiny [AMBIENT_SAMPLE_SIZE] snapshot rather than running
 * a real palette/quantization pass. Called on each item's first frame and
 * then on ExoSurface's slow [AMBIENT_RESAMPLE_INTERVAL_MS] poll while it
 * keeps playing — never per frame — so even a naive full-bitmap average
 * costs nothing measurable at either the snapshot's tiny size or this rate.
 */
internal fun averageColor(bitmap: Bitmap): Color {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return Color.Black
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    var r = 0L; var g = 0L; var b = 0L
    for (p in pixels) {
        r += (p shr 16) and 0xFF
        g += (p shr 8) and 0xFF
        b += p and 0xFF
    }
    val n = pixels.size
    return Color(red = (r / n) / 255f, green = (g / n) / 255f, blue = (b / n) / 255f)
}

@Composable
private fun Message(text: String) {
    Text(
        text,
        color = Color(0xFFBBBBBB),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(24.dp)
    )
}
