package com.cytube.mobile.ui.channel

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.TextureView
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.cytube.mobile.di.Graph
import com.cytube.mobile.net.MediaFrame
import com.cytube.mobile.net.MediaTypes
import com.cytube.mobile.player.GoogleDriveResolver
import com.cytube.mobile.player.NativePlayerHandle
import com.cytube.mobile.player.PlayerHandle
import com.cytube.mobile.player.YouTubeResolver
import com.cytube.mobile.webview.BLANK_EMBED_HTML
import com.cytube.mobile.webview.EMBED_DEFENSIVE_SHIM_JS
import com.cytube.mobile.webview.EMBED_ENDED_SENTINEL
import com.cytube.mobile.webview.EMBED_PAGE_ORIGIN
import com.cytube.mobile.webview.EMBED_STATE_SENTINEL
import com.cytube.mobile.webview.dailymotionSdkHtml
import com.cytube.mobile.webview.peertubeSdkHtml
import com.cytube.mobile.webview.sameSite
import com.cytube.mobile.webview.streamableSdkHtml
import com.cytube.mobile.webview.vimeoSdkHtml
import com.cytube.mobile.webview.youtubeIframeApiHtml
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Layer 4: player implementations.
 *
 * NATIVE and NEWPIPE are the same ExoPlayer surface — the only difference is
 * where the URL comes from. That is deliberate: it means both go through the
 * same PlayerHandle and therefore the same SyncEngine, so switching between a
 * YouTube item and a film mid-playlist changes the source and nothing else.
 */

/** Ceiling ExoPlayer will buffer toward under good network — see ExoSurface's
 *  LoadControl. Media3's own default (DefaultLoadControl.DEFAULT_MAX_BUFFER_MS)
 *  is 50s; raised here to give a large, high-bitrate file more runway to
 *  absorb a bandwidth dip before it ever has to enter STATE_BUFFERING. */
private const val LOAD_CONTROL_MAX_BUFFER_MS = 90_000

/** How much ExoPlayer buffers before initially starting playback. Lowered to
 *  500ms so channel joins and seeks start playback almost instantly without
 *  waiting for a large initial buffer to fill. Background loader immediately
 *  fills toward DEFAULT_MIN_BUFFER_MS / LOAD_CONTROL_MAX_BUFFER_MS once
 *  playback begins. */
private const val LOAD_CONTROL_BUFFER_FOR_PLAYBACK_MS = 500

/** How much ExoPlayer gathers before resuming playback after an actual
 *  stall — lowered to 2,000ms so stalls recover quickly instead of pausing
 *  playback for extended periods. */
private const val LOAD_CONTROL_BUFFER_AFTER_REBUFFER_MS = 2_000

/** Retain 30s of decoded/buffered media behind the playback position.
 *  Enables instant backwards seeks and smooth backward SyncEngine speed
 *  adjustments from buffer memory without re-requesting upstream chunks. */
private const val LOAD_CONTROL_BACK_BUFFER_MS = 30_000

/** Rolling window for tracking frequent short rebuffers so repeated stalls
 *  under 1.5s accumulate toward quality adaptation rather than being lost. */
private const val RECENT_STALL_WINDOW_MS = 10_000L

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
    audioOnly: Boolean = false,
    /** Fires once, the moment this item finishes playing on its own (not a
     *  seek, not a manual stop) — NATIVE/NEWPIPE/GDRIVE via ExoPlayer's own
     *  STATE_ENDED, EMBED via a sentinel console message the yt/dm pages log
     *  from their own "ended" event (see youtubeIframeApiHtml/
     *  dailymotionSdkHtml). Used to drive personal/unsynced playlist
     *  auto-advance — see ChannelViewModel.onPlaybackEnded — which is the
     *  only reason this exists; normal synced playback ignores it entirely
     *  (the server drives advancement for everyone in that mode). Never
     *  fires for WEB, which has no player here to watch. */
    onEnded: (() -> Unit)? = null,
    /** Fires with the wall-clock length of a mid-playback rebuffer — one
     *  that happened AFTER this item already reached its first STATE_READY,
     *  never the item's own initial buffer-up (see ExoSurface's own comment
     *  on why that distinction matters: a large file's cold seek-point
     *  discovery can itself take several seconds on a fine connection, and
     *  that is not a bandwidth problem). Only ExoSurface (the NATIVE
     *  backend) reports this — NEWPIPE/GDRIVE/EMBED never call it. Drives
     *  ChannelViewModel.onPlaybackStall's quality step-down; see
     *  [qualityIndex] for the way back up. */
    onStall: ((Long) -> Unit)? = null,
    /** Which entry of the current item's MediaFrame.direct the NATIVE
     *  backend should load — see PlayerHandle.load's own doc. Meaningless
     *  for every other player type, which never reads CyTube's own quality
     *  list to begin with. */
    qualityIndex: Int = 0
) {
    // embedSrc/scuri fallback logic lives in MediaFrame.embedPlayableSrc —
    // see its own comment for what this covers now beyond cu/bc/bn.
    val embedSrc = media?.embedPlayableSrc
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        when {
            media == null -> Message("Nothing is playing")
            player == MediaTypes.Player.NATIVE ->
                ExoSurface(
                    media, null, showControls, onHandle, onFailed, epoch, onFrameSnapshot, audioOnly, onEnded,
                    onStall = onStall, qualityIndex = qualityIndex
                )
            player == MediaTypes.Player.NEWPIPE ->
                NewPipeSurface(media, showControls, onHandle, onFailed, epoch, onFrameSnapshot, audioOnly, onEnded)
            player == MediaTypes.Player.GDRIVE ->
                GDriveSurface(media, showControls, onHandle, onFailed, epoch, onFrameSnapshot, audioOnly, onEnded)
            player == MediaTypes.Player.EMBED && embedSrc != null ->
                // Deliberately NOT wired to the backgrounded signal
                // ExoSurface uses for audioOnly — see EmbedSurface's own
                // comment on why there's no safe way to reuse it here.
                //
                // key() here is load-bearing, not decorative: EmbedSurface's
                // AndroidView `factory` only ever runs once for a given
                // WebView instance, and its own `update` block never
                // reloads new content into an existing one (see its own
                // onRelease comment). Without this key, the playlist
                // advancing from one EMBED-routed item straight to another
                // (e.g. a Vimeo item to a PeerTube one, or two Vimeo items
                // back to back — CyTube classifies both as
                // MediaTypes.Player.EMBED) hit the same
                // `when` branch twice in a row, so Compose treated it as
                // "the same call site, just new parameters" and reused the
                // OLD WebView untouched — the new item's changeMedia was
                // silently ignored and the previous video just kept
                // playing forever. Keying on the identity of what should be
                // on screen forces Compose to actually dispose the old
                // node and mount a fresh one — the exact same mechanism
                // that already made switching AWAY from EMBED (to NATIVE/
                // NEWPIPE/GDRIVE, a different `when` branch) work correctly
                // by accident. embedSrc is included alongside type/id since
                // MediaFrame.embedPlayableSrc's own cu/bc/bn fallback can
                // change independently of those two.
                key(media.type, media.id, embedSrc) {
                    EmbedSurface(media, embedSrc, onHandle, onFailed, onEnded)
                }
            // WEB is handled by the channel screen, which swaps in the whole
            // CyTube page rather than a player.
            else -> Message("${MediaTypes.label(media.type)} needs Compatibility View.")
        }
    }
}

/**
 * A provider's own embeddable iframe (cu/bc/bn — meta.embed.src), hosted in a
 * WebView that's just the video surface.
 */
@Composable
private fun EmbedSurface(
    media: MediaFrame,
    embedSrc: String,
    onHandle: (PlayerHandle?) -> Unit,
    onFailed: ((String) -> Unit)? = null,
    onEnded: (() -> Unit)? = null
) {
    InProcessEmbedSurface(
        media = media,
        embedSrc = embedSrc,
        onHandle = onHandle,
        onEnded = onEnded
    )
}

private class InProcessEmbedPlayerHandle(
    private var webView: WebView?,
    initialMedia: MediaFrame
) : PlayerHandle {
    override var mediaId: String? = initialMedia.id
        private set
    override var mediaType: String? = initialMedia.type
        private set
    override var mediaLengthSeconds: Int = initialMedia.seconds
        private set

    @Volatile
    override var isPaused: Boolean = initialMedia.paused

    @Volatile
    override var isBuffering: Boolean = false

    @Volatile
    var lastCurrentTime: Double = if (initialMedia.currentTime > 0) initialMedia.currentTime else 0.0

    override val isNative: Boolean
        get() = false

    override fun load(media: MediaFrame, qualityIndex: Int) {
        mediaId = media.id
        mediaType = media.type
        mediaLengthSeconds = media.seconds
        isPaused = media.paused
        lastCurrentTime = if (media.currentTime > 0) media.currentTime else 0.0
    }

    override fun play() {
        webView?.evaluateJavascript(
            "if (window.__cytubeEmbed && window.__cytubeEmbed.play) window.__cytubeEmbed.play();",
            null
        )
    }

    override fun pause() {
        webView?.evaluateJavascript(
            "if (window.__cytubeEmbed && window.__cytubeEmbed.pause) window.__cytubeEmbed.pause();",
            null
        )
    }

    override fun seekTo(seconds: Double) {
        webView?.evaluateJavascript(
            "if (window.__cytubeEmbed && window.__cytubeEmbed.seekTo) window.__cytubeEmbed.seekTo($seconds);",
            null
        )
    }

    override suspend fun currentTimeSeconds(): Double = lastCurrentTime

    override fun setVolume(volume: Float) {
        webView?.evaluateJavascript(
            "if (window.__cytubeEmbed && window.__cytubeEmbed.setVolume) window.__cytubeEmbed.setVolume($volume);",
            null
        )
    }

    override fun release() {
        webView = null
    }
}

@Composable
private fun InProcessEmbedSurface(
    media: MediaFrame,
    embedSrc: String,
    onHandle: (PlayerHandle?) -> Unit,
    onEnded: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val currentOnHandle by rememberUpdatedState(onHandle)
    val currentOnEnded by rememberUpdatedState(onEnded)
    val embedHost = remember(embedSrc) { Uri.parse(embedSrc).host }
    val type = media.type
    val id = media.id
    var isReady by remember(media.id, media.type, embedSrc) { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (isReady) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "embedAlpha"
    )

    var handleRef by remember(media.id, media.type, embedSrc) { mutableStateOf<InProcessEmbedPlayerHandle?>(null) }

    DisposableEffect(media.id, media.type, embedSrc) {
        onDispose {
            currentOnHandle(null)
            handleRef?.release()
            handleRef = null
        }
    }

    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha },
        factory = { ctx ->
            CookieManager.getInstance().setAcceptCookie(true)
            WebView(ctx).apply {
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                setBackgroundColor(android.graphics.Color.BLACK)

                val handle = InProcessEmbedPlayerHandle(this, media)
                handleRef = handle
                currentOnHandle(handle)

                isFocusable = true
                isFocusableInTouchMode = true
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
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    if (type == "dm") {
                        userAgentString = userAgentString.replace("; wv", "")
                    }
                }
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    WebViewCompat.addDocumentStartJavaScript(
                        this,
                        EMBED_DEFENSIVE_SHIM_JS,
                        setOf("*")
                    )
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        if (!request.isForMainFrame) return false
                        if (sameSite(request.url.host, embedHost)) return false
                        openInBrowser(context, request.url.toString())
                        return true
                    }

                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): android.webkit.WebResourceResponse? {
                        if (type == "yt") {
                            Log.d(
                                "CyTubePlayer",
                                "yt embed request method=${request.method} host=${request.url.host}"
                            )
                        }
                        return null
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        isReady = true
                        view.evaluateJavascript(
                            "document.documentElement.style.background='#000';" +
                                "document.body.style.background='#000';" +
                                "document.body.style.margin='0';",
                            null
                        )
                        val curTime = if (media.currentTime > 0) media.currentTime else 0.0
                        val isPaused = media.paused
                        val genericWatcherJs = """
                            (function() {
                              if (window.__cytubeGenericWatched) return;
                              window.__cytubeGenericWatched = true;
                              function setup(v) {
                                if (!v || v.__cytubeBound) return;
                                v.__cytubeBound = true;
                                if ($curTime > 0 && v.currentTime < 1) {
                                  try { v.currentTime = $curTime; } catch(e){}
                                }
                                if ($isPaused) {
                                  try { v.pause(); } catch(e){}
                                }
                                var report = function() {
                                  try {
                                    console.log('$EMBED_STATE_SENTINEL' + JSON.stringify({
                                      paused: v.paused,
                                      currentTime: v.currentTime || 0.0,
                                      buffering: v.readyState < 3 && !v.paused
                                    }));
                                  } catch(e){}
                                };
                                v.addEventListener('play', report);
                                v.addEventListener('playing', report);
                                v.addEventListener('pause', report);
                                v.addEventListener('timeupdate', report);
                                v.addEventListener('waiting', report);
                                v.addEventListener('seeking', report);
                                v.addEventListener('seeked', report);
                                v.addEventListener('ended', function() {
                                  try { console.log('$EMBED_ENDED_SENTINEL'); } catch(e){}
                                });
                                if (!window.__cytubeEmbed) {
                                  window.__cytubeEmbed = {
                                    play: function() { var el = document.querySelector('video'); if (el) el.play().catch(function(){}); },
                                    pause: function() { var el = document.querySelector('video'); if (el) el.pause(); },
                                    seekTo: function(s) { var el = document.querySelector('video'); if (el) el.currentTime = s; },
                                    setVolume: function(vol) { var el = document.querySelector('video'); if (el) el.volume = Math.max(0, Math.min(1, vol)); }
                                  };
                                }
                                report();
                                setInterval(report, 1000);
                              }
                              var vid = document.querySelector('video');
                              if (vid) setup(vid);
                              else {
                                var obs = new MutationObserver(function() {
                                  var el = document.querySelector('video');
                                  if (el) {
                                    setup(el);
                                    try { obs.disconnect(); } catch(e){}
                                  }
                                });
                                try {
                                  obs.observe(document.documentElement || document.body, { childList: true, subtree: true });
                                } catch(e){}
                              }
                            })();
                        """.trimIndent()
                        view.evaluateJavascript(genericWatcherJs, null)
                    }

                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: RenderProcessGoneDetail?
                    ): Boolean {
                        view?.let {
                            (it.parent as? ViewGroup)?.removeView(it)
                            it.destroy()
                        }
                        return true
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest) {
                        val grantable = request.resources.filter {
                            it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
                        }
                        if (grantable.isNotEmpty()) {
                            request.grant(grantable.toTypedArray())
                        } else {
                            request.deny()
                        }
                    }

                    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                        val msg = consoleMessage.message()
                        if (msg == EMBED_ENDED_SENTINEL) {
                            currentOnEnded?.invoke()
                            return true
                        }
                        if (msg.startsWith(EMBED_STATE_SENTINEL)) {
                            val jsonStr = msg.removePrefix(EMBED_STATE_SENTINEL)
                            try {
                                val json = JSONObject(jsonStr)
                                val paused = json.optBoolean("paused", true)
                                val currentTime = json.optDouble("currentTime", 0.0)
                                val buffering = json.optBoolean("buffering", false)
                                handle.isPaused = paused
                                handle.lastCurrentTime = currentTime
                                handle.isBuffering = buffering
                            } catch (ignored: Throwable) {}
                            isReady = true
                            return true
                        }

                        Log.w(
                            "CyTubePlayer",
                            "embed console [${consoleMessage.messageLevel()}] " +
                                "${consoleMessage.message()} " +
                                "(${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                        )
                        return true
                    }
                }
                val initTime = if (media.currentTime > 0) media.currentTime else 0.0
                val initPaused = media.paused
                if (type == "dm") {
                    loadDataWithBaseURL(
                        "$EMBED_PAGE_ORIGIN/",
                        dailymotionSdkHtml(id, initTime, initPaused),
                        "text/html",
                        "utf-8",
                        null
                    )
                } else if (type == "yt") {
                    loadDataWithBaseURL(
                        "$EMBED_PAGE_ORIGIN/",
                        youtubeIframeApiHtml(id, initTime, initPaused),
                        "text/html",
                        "utf-8",
                        null
                    )
                } else if (type == "vi") {
                    loadDataWithBaseURL(
                        "$EMBED_PAGE_ORIGIN/",
                        vimeoSdkHtml(id, initTime, initPaused),
                        "text/html",
                        "utf-8",
                        null
                    )
                } else if (type == "pt") {
                    val ptEmbedUrl = MediaTypes.knownEmbedUrl("pt", id)
                    loadDataWithBaseURL(
                        "$EMBED_PAGE_ORIGIN/",
                        peertubeSdkHtml(ptEmbedUrl, initTime, initPaused),
                        "text/html",
                        "utf-8",
                        null
                    )
                } else if (type == "sb") {
                    loadDataWithBaseURL(
                        "$EMBED_PAGE_ORIGIN/",
                        streamableSdkHtml(id, initTime, initPaused),
                        "text/html",
                        "utf-8",
                        null
                    )
                } else if (embedSrc.startsWith("https://", ignoreCase = true)) {
                    loadUrl(embedSrc)
                } else {
                    Log.w("CyTubePlayer", "refusing to load embed with untrusted scheme: type=$type")
                    loadDataWithBaseURL("$EMBED_PAGE_ORIGIN/", BLANK_EMBED_HTML, "text/html", "utf-8", null)
                }
            }
        },
        update = { it.requestFocus() },
        onRelease = {
            currentOnHandle(null)
            handleRef?.release()
            handleRef = null
            (it.parent as? ViewGroup)?.removeView(it)
            it.stopLoading()
            it.webViewClient = object : WebViewClient() {
                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?
                ): Boolean = true
            }
            it.webChromeClient = null
            it.removeAllViews()
            it.destroy()
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
    audioOnly: Boolean = false,
    onEnded: (() -> Unit)? = null
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
            showControls, onHandle, onFailed, epoch, onFrameSnapshot, audioOnly, onEnded
        )
    }
}

/**
 * A previous pass spoofed Referer/Origin/a desktop Chrome User-Agent on the
 * actual video-byte request, on the theory that Google's CDN hotlink-checks
 * it the same way the metadata lookup is checked. Live testing across two
 * different Drive file ids showed that was wrong — both still came back
 * HTTP 403 with those headers attached. Checked against the actual
 * reference implementation this resolver is modeled on (yt-dlp's
 * GoogleDriveIE, same content-workspacevideo-pa.googleapis.com endpoint and
 * API key): it sends no Referer/Origin/User-Agent override at all for these
 * formatStreamingData URLs — only the metadata lookup itself carries a
 * Referer. Matching that exactly (i.e., sending nothing) is what actually
 * fixed it — confirmed live, playback works.
 */
private val GDRIVE_STREAM_HEADERS = emptyMap<String, String>()

/** Resolves a Google Drive file id to a stream URL, then hands over to the normal player. */
@Composable
private fun GDriveSurface(
    media: MediaFrame,
    showControls: Boolean,
    onHandle: (PlayerHandle?) -> Unit,
    onFailed: (String) -> Unit,
    epoch: Int,
    onFrameSnapshot: ((Bitmap) -> Unit)? = null,
    audioOnly: Boolean = false,
    onEnded: (() -> Unit)? = null
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
            showControls, onHandle, onFailed, epoch, onFrameSnapshot, audioOnly, onEnded
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
    audioOnly: Boolean = false,
    onEnded: (() -> Unit)? = null,
    onStall: ((Long) -> Unit)? = null,
    qualityIndex: Int = 0
) {
    val context = LocalContext.current
    val exo = remember(epoch) {
        val renderersFactory = DefaultRenderersFactory(context)
            .forceEnableMediaCodecAsynchronousQueueing()
            .setEnableDecoderFallback(true)

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .setUsage(C.USAGE_MEDIA)
            .build()

        ExoPlayer.Builder(context, renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            .setLoadControl(
                // Steady-state target (min) is left at Media3's default (50s);
                // bufferForPlayback is tuned down to 500ms to start playback
                // faster on network-resolved items (Google Drive, NewPipe);
                // max buffer is raised to give more runway on high-bitrate files;
                // rebuffer target demands enough to resume safely after a stall.
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                        LOAD_CONTROL_MAX_BUFFER_MS,
                        LOAD_CONTROL_BUFFER_FOR_PLAYBACK_MS,
                        LOAD_CONTROL_BUFFER_AFTER_REBUFFER_MS
                    )
                    .setBackBuffer(LOAD_CONTROL_BACK_BUFFER_MS, true)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
            .build()
    }
    val handle = remember(exo) { NativePlayerHandle(exo, context) }

    // Backgrounded-but-not-PiP: nothing is actually on screen, so decoding
    // and rendering video frames the user can't see is pure waste — this
    // disables just the video track and lets ExoPlayer keep decoding audio
    // only, same idea as a music app playing with the screen off. Re-enabled
    // the moment audioOnly goes false (foregrounded again, or PiP started —
    // see ChannelScreen, which never passes audioOnly=true while in PiP).
    // trackSelectionParameters is cheap to rebuild and safe to set mid-playback;
    // ExoPlayer just reselects tracks on the next internal cycle.
    LaunchedEffect(exo, audioOnly) {
        runCatching {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly)
                .build()
        }
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

    val scope = rememberCoroutineScope()
    DisposableEffect(exo) {
        // Scoped to this exo/listener's own lifetime (a fresh pair per
        // epoch — see the LaunchedEffect above), so these reset naturally
        // for every new item AND for every quality-adaptation reload, never
        // needing an explicit reset of their own.
        var reachedReadyOnce = false
        var stallStartedAtMs = 0L
        var stallJob: Job? = null
        val recentStalls = mutableListOf<Pair<Long, Long>>()
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

            // STATE_ENDED is ExoPlayer's own terminal state for "ran off the
            // end of the media on its own" — distinct from a seek (which
            // never leaves STATE_READY) or a manual stop/release (which
            // tears the listener down via onDispose below before this could
            // fire). Drives personal/unsynced playlist auto-advance — see
            // PlayerSurface's onEnded doc comment and
            // ChannelViewModel.onPlaybackEnded, the only place that acts on
            // it; normal synced playback never reads it at all.
            //
            // Also tracks mid-playback stalls for onStall, deliberately
            // excluding the item's own FIRST buffer-up (reachedReadyOnce):
            // confirmed live that a large, non-faststart file can cost many
            // real seconds just discovering its own seek/duration index on a
            // perfectly good connection, before a single byte of the actual
            // stream downloads — that is not a bandwidth problem and
            // shouldn't be treated as one. A stall that happens AFTER this
            // item already played at least one frame is the real signal:
            // playback that had already started has since run its buffer dry.
            //
            // If buffering exceeds threshold or repeated rebuffers accumulate
            // across RECENT_STALL_WINDOW_MS, stallJob or STATE_READY fires
            // onStall so ChannelViewModel can step down quality without
            // waiting for the slow, high-bitrate stream to finish gathering
            // seconds of data that will just be discarded on reload.
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onEnded?.invoke()
                when (playbackState) {
                    Player.STATE_READY -> {
                        stallJob?.cancel()
                        stallJob = null
                        if (!reachedReadyOnce) {
                            reachedReadyOnce = true
                        } else if (stallStartedAtMs != 0L) {
                            val now = SystemClock.elapsedRealtime()
                            val stalledMs = now - stallStartedAtMs
                            stallStartedAtMs = 0L
                            recentStalls.add(now to stalledMs)
                            recentStalls.removeAll { now - it.first > RECENT_STALL_WINDOW_MS }
                            val totalStalledMs = recentStalls.sumOf { it.second }
                            if (totalStalledMs >= 1_500L || recentStalls.size >= 2) {
                                recentStalls.clear()
                                onStall?.invoke(totalStalledMs.coerceAtLeast(1_500L))
                            }
                        }
                    }
                    Player.STATE_BUFFERING -> {
                        if (stallStartedAtMs == 0L) {
                            val start = SystemClock.elapsedRealtime()
                            stallStartedAtMs = start
                            stallJob?.cancel()
                            stallJob = scope.launch {
                                recentStalls.removeAll { start - it.first > RECENT_STALL_WINDOW_MS }
                                val priorStalled = recentStalls.sumOf { it.second }
                                val threshold = if (reachedReadyOnce) {
                                    (1_500L - priorStalled).coerceIn(300L, 1_500L)
                                } else {
                                    3_500L
                                }
                                delay(threshold)
                                if (stallStartedAtMs == start) {
                                    recentStalls.clear()
                                    onStall?.invoke(1_500L)
                                }
                            }
                        }
                    }
                }
            }
        }
        runCatching { exo.addListener(listener) }
        onDispose {
            stallJob?.cancel()
            runCatching { exo.removeListener(listener) }
        }
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
    LaunchedEffect(exo, onFrameSnapshot) {
        val snapshot = onFrameSnapshot ?: return@LaunchedEffect
        var sampleBitmap: Bitmap? = null
        try {
            while (true) {
                delay(AMBIENT_RESAMPLE_INTERVAL_MS)
                val isPlaying = runCatching { exo.isPlaying }.getOrDefault(false)
                if (!isPlaying) continue
                val textureView = playerViewRef[0]?.videoSurfaceView as? TextureView ?: continue
                if (sampleBitmap == null || sampleBitmap.isRecycled) {
                    sampleBitmap = Bitmap.createBitmap(
                        AMBIENT_SAMPLE_SIZE, AMBIENT_SAMPLE_SIZE, Bitmap.Config.ARGB_8888
                    )
                }
                val bitmap = runCatching { textureView.getBitmap(sampleBitmap) }.getOrNull() ?: continue
                snapshot(bitmap)
            }
        } finally {
            sampleBitmap?.recycle()
        }
    }

    DisposableEffect(handle) {
        onDispose {
            playerViewRef[0] = null
            onHandle(null)
            // Session first, then the player it wraps — releasing in the
            // other order would leave the session momentarily pointing at
            // an already-released player.
            runCatching { mediaSession.release() }
            handle.release()
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
    //
    // epoch is also a key, not just media.id/type/resolved: `exo`/`handle`
    // above are remember(epoch)'d, so a playerEpoch bump with the same
    // media.id (ChannelViewModel's own quality-adaptation reload, the only
    // thing that currently bumps it — see its own doc comment) builds a
    // brand new ExoPlayer/handle pair, but without epoch as a key here this
    // effect's coroutine wouldn't restart to ever call load()/loadUrl() on
    // it — the old effect just keeps running, bound to the disposed handle.
    LaunchedEffect(media.id, media.type, resolved, epoch) {
        if (resolved != null) {
            handle.loadUrl(media, resolved.url, resolved.mimeType, resolved.headers)
        } else {
            handle.load(media, qualityIndex)
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
                // Media3's own default is 3s — shares ChannelScreen's
                // CONTROLS_AUTO_HIDE_MS instead so this controller's
                // scrubber/play-pause bar fades on the same schedule as
                // ChannelScreen's own overlay icons rather than lingering
                // noticeably longer than everything else on screen.
                controllerShowTimeoutMs = CONTROLS_AUTO_HIDE_MS.toInt()
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            }.also { playerViewRef[0] = it }
        },
        update = { view ->
            if (view.player !== exo) {
                view.player = exo
            }
            view.useController = showControls
            playerViewRef[0] = view
        },
        onRelease = { view ->
            view.player = null
        }
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
    val n = w * h
    if (w <= 0 || h <= 0 || n <= 0) return Color.Black
    val pixels = IntArray(n)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    var r = 0L; var g = 0L; var b = 0L
    for (i in 0 until n) {
        val p = pixels[i]
        r += (p shr 16) and 0xFF
        g += (p shr 8) and 0xFF
        b += p and 0xFF
    }
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
