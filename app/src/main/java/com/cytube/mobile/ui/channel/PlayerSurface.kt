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
import androidx.media3.common.MediaItem
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
    /** Fires once per video item, right as its first frame renders — not on
     *  a timer, not per frame. See ExoSurface for why a one-time TextureView
     *  snapshot is cheap enough to not worry about, unlike sampling every
     *  frame would be. Used to color the ambient glow behind the windowed
     *  player; null for EMBED/WEB, which have no ExoPlayer to snapshot. */
    onFrameSnapshot: ((Bitmap) -> Unit)? = null
) {
    val embedSrc = media?.embedSrc
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        when {
            media == null -> Message("Nothing is playing")
            player == MediaTypes.Player.NATIVE ->
                ExoSurface(media, null, showControls, onHandle, onFailed, epoch, onFrameSnapshot)
            player == MediaTypes.Player.NEWPIPE ->
                NewPipeSurface(media, showControls, onHandle, onFailed, epoch, onFrameSnapshot)
            player == MediaTypes.Player.GDRIVE ->
                GDriveSurface(media, showControls, onHandle, onFailed, epoch, onFrameSnapshot)
            player == MediaTypes.Player.EMBED && embedSrc != null ->
                EmbedSurface(embedSrc)
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
private fun EmbedSurface(embedSrc: String) {
    val context = LocalContext.current
    val embedHost = remember(embedSrc) { Uri.parse(embedSrc).host }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
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
    onFrameSnapshot: ((Bitmap) -> Unit)? = null
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
            showControls, onHandle, onFailed, epoch, onFrameSnapshot
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
    onFrameSnapshot: ((Bitmap) -> Unit)? = null
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
            showControls, onHandle, onFailed, epoch, onFrameSnapshot
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
    onFrameSnapshot: ((Bitmap) -> Unit)? = null
) {
    val context = LocalContext.current
    val exo = remember(epoch) { ExoPlayer.Builder(context).build() }
    val handle = remember(exo) { NativePlayerHandle(exo) }
    // Exposes this ExoPlayer to the system: a Fire TV remote's dedicated
    // media keys, Alexa's "pause"/"resume" voice commands, and any system
    // Now Playing surface all reach whichever app currently holds the
    // active session — Media3 keeps this session's playback state and
    // metadata in sync with `exo` on its own, so there is nothing else to
    // wire up here beyond creating and releasing it alongside the player.
    // Scoped to the player's own lifetime (same as the error/frame listener
    // below), not a standalone service — this app already pauses playback
    // when backgrounded outside of PiP, so there is no "still playing but
    // the session's gone" gap to cover.
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
            // A one-time snapshot per item, taken the moment its first frame
            // actually renders — not a timer, not sampled per frame. Media3
            // calls this again on later transitions within the same
            // ExoPlayer instance too (e.g. epoch stays put but the media
            // source is swapped), which is exactly when a fresh snapshot is
            // wanted anyway: a new video means a new dominant color.
            override fun onRenderedFirstFrame() {
                val snapshot = onFrameSnapshot ?: return
                val textureView = playerViewRef[0]?.videoSurfaceView as? TextureView ?: return
                // TextureView.getBitmap(w, h) downsamples internally rather
                // than copying the full-resolution frame out first — this is
                // already about as cheap as a frame grab gets, and it only
                // ever runs once per item.
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

/** Process-wide, ever-increasing — see the doc comment on ExoSurface's
 *  mediaSession for why every MediaSession this app ever creates needs a
 *  genuinely unique id, not just one that's unique per ExoSurface call. */
private val mediaSessionIdCounter = java.util.concurrent.atomic.AtomicInteger(0)
private fun nextMediaSessionId(): Int = mediaSessionIdCounter.getAndIncrement()

/**
 * Cheap, good-enough dominant-color extraction for the ambient glow: average
 * every pixel of the tiny [AMBIENT_SAMPLE_SIZE] snapshot rather than running
 * a real palette/quantization pass. This runs once per video item (see
 * ExoSurface's onRenderedFirstFrame above), never per frame, so even a naive
 * full-bitmap average costs nothing measurable — the snapshot itself is
 * already tiny by the time this sees it.
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
