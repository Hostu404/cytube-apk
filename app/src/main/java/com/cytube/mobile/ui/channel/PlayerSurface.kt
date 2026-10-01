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
import android.webkit.WebResourceError
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
import android.app.ActivityManager
import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import androidx.webkit.WebViewCompat
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import androidx.webkit.WebViewFeature
import com.cytube.mobile.AppVisibility
import com.cytube.mobile.net.MediaFrame
import com.cytube.mobile.net.MediaTypes
import com.cytube.mobile.player.DownloadWatch
import com.cytube.mobile.player.MediaBytes
import com.cytube.mobile.player.NativePlayerHandle
import com.cytube.mobile.player.PlayerHandle
import com.cytube.mobile.player.ResolvedStream
import com.cytube.mobile.player.StreamResolvers
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.cytube.mobile.R
import com.cytube.mobile.ui.isTvDevice
import com.cytube.mobile.webview.BLANK_EMBED_HTML
import com.cytube.mobile.webview.EMBED_DEFENSIVE_SHIM_JS
import com.cytube.mobile.webview.EMBED_ENDED_SENTINEL
import com.cytube.mobile.webview.EMBED_ERROR_SENTINEL
import com.cytube.mobile.webview.EMBED_PAGE_ORIGIN
import com.cytube.mobile.webview.EMBED_STATE_SENTINEL
import com.cytube.mobile.webview.PROVIDER_EMBED_TYPES
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
private const val LOAD_CONTROL_MAX_BUFFER_MS = 120_000

/** How much ExoPlayer buffers before initially starting playback. Set to
 *  2,000ms so playback starts reliably with enough initial runway. */
private const val LOAD_CONTROL_BUFFER_FOR_PLAYBACK_MS = 2_000

/** How much ExoPlayer gathers before resuming playback after an actual
 *  stall — set to 4,000ms so the player has a stable cushion before resuming
 *  and avoids immediate repeat rebuffers on struggling connections. */
private const val LOAD_CONTROL_BUFFER_AFTER_REBUFFER_MS = 4_000

/** Retain 10s of buffered media behind the playback position, so a short
 *  backwards seek (e.g. SyncEngine's "ahead" correction) needs no re-fetch.
 *  No more than that: the back buffer counts against the byte budget
 *  below, and at a high bitrate 30s of it (~150MB at 40 Mbps) would leave
 *  little room for the forward buffer that actually prevents stalls.
 *  SyncEngine closes most "ahead" drift by slowing playback anyway. */
private const val LOAD_CONTROL_BACK_BUFFER_MS = 10_000

/**
 * Byte budget for ExoPlayer's buffer, sized to this device's own Java heap
 * limit (ActivityManager.memoryClass), and used as a hard cap on both phone
 * and TV (prioritizeTimeOverSizeThresholds = false).
 *
 * ExoPlayer's buffer lives on the Java heap, so a time-based budget alone
 * isn't safe: at 40 Mbps the 50s phone minimum is ~250MB, enough to crash
 * with OutOfMemoryError on a phone with a 256MB heap limit, while a flat
 * byte cap that suits a small heap wastes a large one.
 *
 * So: 40% of the heap limit, never less than 64MB (enough for a full 50s at
 * ~10 Mbps). Ceilings: 160MB on TV, 256MB on phone. A 256MB-class device
 * gets ~102MB (~20s of 40 Mbps video in total; on phone that includes the
 * 10s back buffer), a 512MB-class phone ~205MB (~40s).
 */
private fun bufferBudgetBytes(context: Context, isTv: Boolean): Int {
    val memoryClassMb = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
        ?.memoryClass ?: 0
    val maxMb = if (isTv) TV_BUFFER_MAX_MB else PHONE_BUFFER_MAX_MB
    val budgetMb = (memoryClassMb * 0.4).toInt().coerceIn(BUFFER_MIN_MB, maxMb)
    return budgetMb * 1024 * 1024
}
private const val BUFFER_MIN_MB = 64
private const val TV_BUFFER_MAX_MB = 160
private const val PHONE_BUFFER_MAX_MB = 256

/** Rolling window for tracking frequent short rebuffers so repeated stalls
 *  under 3.0s accumulate toward quality adaptation rather than being lost. */
private const val RECENT_STALL_WINDOW_MS = 10_000L
private const val STALL_TRIGGER_MS = 3_000L

/** Buffering that begins this soon after a seek is attributed to the seek,
 *  not counted as a bandwidth stall — see ExoSurface's listener. */
private const val SEEK_BUFFERING_WINDOW_MS = 1_000L

/** Buffering that begins this soon after video is switched on or off (the
 *  app leaving or returning from the background) is that switch's cost, not
 *  a bandwidth stall — see NativePlayerHandle.videoToggledAtMs. */
private const val VIDEO_TOGGLE_BUFFERING_WINDOW_MS = 2_000L

@Composable
fun PlayerSurface(
    media: MediaFrame?,
    player: MediaTypes.Player,
    showControls: Boolean,
    onHandle: (PlayerHandle) -> Unit,
    onFailed: (String) -> Unit,
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
    /** Fires once, the moment this item finishes playing on its own (not a
     *  seek, not a manual stop) — Media3-played types via ExoPlayer's own
     *  STATE_ENDED, EMBED via a sentinel console message each embed page
     *  logs from its player's "ended" event (see WebEmbedHtml). Used to drive personal/unsynced playlist
     *  auto-advance — see ChannelViewModel.onPlaybackEnded — which is the
     *  only reason this exists; normal synced playback ignores it entirely
     *  (the server drives advancement for everyone in that mode). Never
     *  fires for WEB, which has no player here to watch. */
    onEnded: (() -> Unit)? = null,
    /** Fires with the measured length of mid-playback rebuffering — after
     *  this item already reached its first STATE_READY, never the item's
     *  own initial buffer-up (see ExoSurface's own comment on why that
     *  distinction matters: a large file's cold seek-point discovery can
     *  itself take several seconds on a fine connection, and that is not a
     *  bandwidth problem). Reported by every Media3-played type; drives
     *  ChannelViewModel.onPlaybackStall's quality step-down, which only
     *  acts on NATIVE items (the only ones with a quality list). */
    onStall: ((Long) -> Unit)? = null,
    /** Which entry of the current item's MediaFrame.direct the NATIVE
     *  backend should load — see NativePlayerHandle.load. Meaningless
     *  for every other player type, which never reads CyTube's own quality
     *  list to begin with. */
    qualityIndex: Int = 0,
    /** Where to open a fresh item, given the item and the address it will
     *  stream from — ChannelViewModel.plannedStart: the channel's item where
     *  the room is by the time it opens (a synced one joined partway through
     *  a little ahead of it), a personal pick from its own start. */
    planStart: (MediaFrame, String) -> MediaFrame = { m, _ -> m }
) {
    // embedSrc/scuri fallback logic lives in MediaFrame.embedPlayableSrc —
    // see its own comment for what this covers now beyond cu/bc/bn.
    val embedSrc = media?.embedPlayableSrc
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        when {
            media == null -> Message("Nothing is playing")
            player == MediaTypes.Player.NATIVE || StreamResolvers.handles(player) ->
                Media3Surface(
                    media, player, showControls, onHandle, onFailed, onFrameSnapshot, onEnded,
                    onStall, qualityIndex, planStart
                )
            player == MediaTypes.Player.EMBED && embedSrc != null ->
                // key() here is load-bearing: EmbedSurface's AndroidView
                // `factory` runs once per WebView and its `update` never
                // loads new content into an existing one. Without the key,
                // one EMBED item followed by another (a Vimeo item then a
                // Dailymotion one, say) would reuse the old WebView and
                // keep playing the previous video. Keying on what should be
                // on screen makes Compose dispose it and mount a fresh one.
                // embedSrc is included because MediaFrame.embedPlayableSrc
                // can change independently of type/id.
                key(media.type, media.id, embedSrc) {
                    EmbedSurface(media, embedSrc, onHandle, onFailed, onEnded)
                }
            // WEB is handled by the channel screen, which swaps in the whole
            // CyTube page rather than a player.
            else -> Message("${MediaTypes.label(media.type)} needs Compatibility View.")
        }
    }
}

/** A provider's player running in the embed WebView, driven through the
 *  `window.__cytubeEmbed` object each embed page defines (see WebEmbedHtml). */
private class EmbedPlayerHandle(
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

    override val isReleased: Boolean
        get() = webView == null

    /** Last volume asked for (the user's mute setting). A page's player
     *  doesn't exist until the page has loaded, and several pages force the
     *  sound on when playback starts (they start muted so autoplay is
     *  allowed), so this is applied again once the page reports it's
     *  playing — see [onStateReport]. */
    private var volume: Float = 1f
    private var volumeAppliedWhilePlaying = false

    /** Called with each state report from the page. */
    fun onStateReport(paused: Boolean, currentTime: Double, buffering: Boolean) {
        isPaused = paused
        lastCurrentTime = currentTime
        isBuffering = buffering
        if (!paused && !volumeAppliedWhilePlaying) {
            volumeAppliedWhilePlaying = true
            applyVolume()
        }
    }

    override fun play() {
        // Set immediately rather than waiting for the JS report() tick (see
        // the EMBED_STATE_SENTINEL watcher below) to echo it back — that's a
        // once-a-second poll, so SyncEngine/ChannelViewModel reading isPaused
        // right after calling play() could see up to ~1s of stale "still
        // paused" state. The JS side will confirm this on its next report()
        // regardless; this just stops this handle from lying in the meantime.
        isPaused = false
        webView?.evaluateJavascript(
            "if (window.__cytubeEmbed && window.__cytubeEmbed.play) window.__cytubeEmbed.play();",
            null
        )
    }

    override fun pause() {
        isPaused = true
        webView?.evaluateJavascript(
            "if (window.__cytubeEmbed && window.__cytubeEmbed.pause) window.__cytubeEmbed.pause();",
            null
        )
    }

    override fun seekTo(seconds: Double) {
        lastCurrentTime = seconds
        webView?.evaluateJavascript(
            "if (window.__cytubeEmbed && window.__cytubeEmbed.seekTo) window.__cytubeEmbed.seekTo($seconds);",
            null
        )
    }

    override suspend fun currentTimeSeconds(): Double = lastCurrentTime

    override fun setVolume(volume: Float) {
        this.volume = volume
        applyVolume()
    }

    private fun applyVolume() {
        webView?.evaluateJavascript(
            "if (window.__cytubeEmbed && window.__cytubeEmbed.setVolume) window.__cytubeEmbed.setVolume($volume);",
            null
        )
    }

    override fun release() {
        webView = null
    }
}

/**
 * A single-video WebView: a provider's own player (YouTube, Dailymotion,
 * Vimeo, PeerTube, Streamable pages built in WebEmbedHtml), or for anything
 * else the item's embeddable link loaded directly. Always called inside a
 * key() on the item (see PlayerSurface), so one instance only ever shows one
 * item.
 */
@Composable
private fun EmbedSurface(
    media: MediaFrame,
    embedSrc: String,
    onHandle: (PlayerHandle) -> Unit,
    onFailed: ((String) -> Unit)? = null,
    onEnded: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val currentOnHandle by rememberUpdatedState(onHandle)
    val currentOnFailed by rememberUpdatedState(onFailed)
    val currentOnEnded by rememberUpdatedState(onEnded)
    val embedHost = remember(embedSrc) { Uri.parse(embedSrc).host }
    val type = media.type
    val id = media.id
    // The provider pages report their own failures (EMBED_ERROR_SENTINEL);
    // for them, WebView's main-frame errors below can't fire, because the
    // main frame is inline HTML. A directly loaded link is the opposite:
    // only the main-frame errors, and the generic <video> watcher injected
    // in onPageFinished (a provider page's video is inside a cross-origin
    // iframe that script can't reach).
    val isProviderPage = type in PROVIDER_EMBED_TYPES
    val isTv = remember { isTvDevice(context) }
    var isReady by remember { mutableStateOf(false) }
    var failureReported by remember { mutableStateOf(false) }
    fun reportFailure(reason: String) {
        if (failureReported) return
        failureReported = true
        currentOnFailed?.invoke(reason)
    }
    val alpha by animateFloatAsState(
        targetValue = if (isReady) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "embedAlpha"
    )

    var handleRef by remember { mutableStateOf<EmbedPlayerHandle?>(null) }

    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha },
        factory = { ctx ->
            CookieManager.getInstance().setAcceptCookie(true)
            WebView(ctx).apply {
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                // Without this AndroidView leaves the WebView at WRAP_CONTENT,
                // and a directly loaded page sized with height:100% (Odysee's
                // player) collapses to nothing, with its play button half off
                // the top. Our own provider pages use position:fixed and were
                // unaffected.
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(android.graphics.Color.BLACK)

                val handle = EmbedPlayerHandle(this, media)
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
                        // A provider page is our own inline HTML, whose main
                        // frame never navigates by itself: this is a link
                        // tapped inside the provider's player (its title or
                        // logo). Followed here it replaced the player with
                        // the provider's website, and sync with it. A
                        // directly loaded page may move around its own site.
                        if (!isProviderPage && sameSite(request.url.host, embedHost)) return false
                        // A directly loaded link that redirects (a short link,
                        // a host moving its player) is still the video.
                        if (!isProviderPage && request.isRedirect) return false
                        // Only for a tap: a page redirecting by itself must
                        // not keep throwing the user out to the browser.
                        if (request.hasGesture()) openInBrowser(context, request.url.toString())
                        return true
                    }

                    // A directly loaded link that fails to load at all (dead
                    // link, offline instance, DNS failure...) is reported so
                    // ChannelViewModel can offer Compatibility View. Only the
                    // main frame counts — sub-resource errors (ads, trackers
                    // the page loads) are not this surface failing.
                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError
                    ) {
                        if (request.isForMainFrame) {
                            reportFailure("Embed failed to load (${error.description})")
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        errorResponse: android.webkit.WebResourceResponse
                    ) {
                        if (request.isForMainFrame) {
                            reportFailure("Embed failed to load (HTTP ${errorResponse.statusCode})")
                        }
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        isReady = true
                        view.evaluateJavascript(
                            "document.documentElement.style.background='#000';" +
                                "document.body.style.background='#000';" +
                                "document.body.style.margin='0';",
                            null
                        )
                        if (isProviderPage) return
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
                                // Sites start their <video> muted (browsers only
                                // autoplay muted ones) and some mute it again
                                // when playback (re)starts. Whenever it plays,
                                // put back the volume the app last asked for.
                                v.addEventListener('playing', function() {
                                  var want = window.__cytubeWantVol;
                                  if (typeof want === 'number' && want > 0 && v.muted) {
                                    try { v.muted = false; v.volume = want; } catch(e){}
                                  }
                                });
                                if (!window.__cytubeEmbed) {
                                  window.__cytubeEmbed = {
                                    play: function() { var el = document.querySelector('video'); if (el) el.play().catch(function(){}); },
                                    pause: function() { var el = document.querySelector('video'); if (el) el.pause(); },
                                    seekTo: function(s) { var el = document.querySelector('video'); if (el) el.currentTime = s; },
                                    // `muted` as well as `volume`: raising the
                                    // volume of a muted <video> stays silent.
                                    setVolume: function(vol) {
                                      var want = Math.max(0, Math.min(1, vol));
                                      window.__cytubeWantVol = want;
                                      var el = document.querySelector('video');
                                      if (el) { el.muted = want <= 0; el.volume = want; }
                                    }
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
                        // Nothing is left on screen, so say so rather than
                        // sit on a black area (ChannelViewModel offers
                        // Compatibility View), and stop the ViewModel
                        // driving a WebView that no longer exists.
                        handleRef?.release()
                        reportFailure("Embed page crashed")
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
                        if (msg.startsWith(EMBED_ERROR_SENTINEL)) {
                            val detail = msg.removePrefix(EMBED_ERROR_SENTINEL)
                            Log.w("CyTubePlayer", "embed player error type=$type: $detail")
                            reportFailure("${MediaTypes.label(type)} player error ($detail)")
                            return true
                        }
                        if (msg.startsWith(EMBED_STATE_SENTINEL)) {
                            val jsonStr = msg.removePrefix(EMBED_STATE_SENTINEL)
                            try {
                                val json = JSONObject(jsonStr)
                                handle.onStateReport(
                                    paused = json.optBoolean("paused", true),
                                    currentTime = json.optDouble("currentTime", 0.0),
                                    buffering = json.optBoolean("buffering", false)
                                )
                            } catch (ignored: Throwable) {}
                            isReady = true
                            return true
                        }

                        // The provider page's own console, for debugging an
                        // embed that won't play; not a warning in itself.
                        Log.d(
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
                val providerHtml: String? = when (type) {
                    "dm" -> dailymotionSdkHtml(id, initTime, initPaused)
                    "yt" -> youtubeIframeApiHtml(id, initTime, initPaused)
                    "vi" -> vimeoSdkHtml(id, initTime, initPaused)
                    "pt" -> peertubeSdkHtml(MediaTypes.knownEmbedUrl("pt", id), initTime, initPaused)
                    "sb" -> streamableSdkHtml(id, initTime, initPaused)
                    else -> null
                }
                when {
                    providerHtml != null && providerHtml != BLANK_EMBED_HTML ->
                        loadDataWithBaseURL("$EMBED_PAGE_ORIGIN/", providerHtml, "text/html", "utf-8", null)
                    providerHtml == null && embedSrc.startsWith("https://", ignoreCase = true) ->
                        loadUrl(embedSrc)
                    else -> {
                        // An id that fails the page builder's safety check,
                        // or a link that isn't https: show nothing — and say
                        // so, rather than leave a black screen with no offer.
                        Log.w("CyTubePlayer", "refusing to load embed type=$type (unsafe id or link)")
                        loadDataWithBaseURL("$EMBED_PAGE_ORIGIN/", BLANK_EMBED_HTML, "text/html", "utf-8", null)
                        reportFailure("This ${MediaTypes.label(type)} link can't be played here")
                    }
                }
            }
        },
        // TV only, for the remote. On a phone this took focus from the chat
        // box, closing the keyboard mid-message, whenever the playlist moved
        // to an embedded video.
        update = { if (isTv) it.requestFocus() },
        onRelease = {
            // Releasing is how the ViewModel learns this player is gone —
            // see ChannelViewModel.player.
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

/**
 * Every type Media3 plays. NATIVE plays the item's own URL; the others look
 * up a stream first (StreamResolvers). There is one ExoSurface call site
 * whatever the type, so when the next item's stream is already known (a
 * cache hit — always the case for an item ChannelViewModel loaded while the
 * app was in the background) the same ExoPlayer carries on, even across
 * types, instead of being thrown away and reloaded. A lookup that has to go
 * to the network shows a spinner over the player meanwhile; the previous item
 * is stopped, but the player (and its media session) stays for the new one.
 */
@Composable
private fun Media3Surface(
    media: MediaFrame,
    player: MediaTypes.Player,
    showControls: Boolean,
    onHandle: (PlayerHandle) -> Unit,
    onFailed: (String) -> Unit,
    onFrameSnapshot: ((Bitmap) -> Unit)?,
    onEnded: (() -> Unit)?,
    onStall: ((Long) -> Unit)?,
    qualityIndex: Int,
    planStart: (MediaFrame, String) -> MediaFrame
) {
    val needsResolve = player != MediaTypes.Player.NATIVE
    // Already resolved (typically: loaded while the app was in the
    // background) — start from it rather than a spinner, so the existing
    // player stays in place and ExoSurface sees the item is already loaded.
    var resolved by remember(media.id, player) {
        mutableStateOf(if (needsResolve) StreamResolvers.cached(player, media.id) else null)
    }
    var error by remember(media.id, player) { mutableStateOf<String?>(null) }

    // The time the lookup takes is accounted for when the item is opened:
    // planStart (ChannelViewModel.plannedStart) starts the channel's item
    // where the room is by then, and a personal pick from its own start.
    LaunchedEffect(media.id, player) {
        if (!needsResolve || resolved != null) return@LaunchedEffect
        StreamResolvers.resolve(player, media.id)
            .onSuccess { resolved = it }
            .onFailure {
                val why = "${StreamResolvers.providerName(player)} lookup failed: ${it.message ?: it.javaClass.simpleName}"
                Log.w("CyTubePlayer", "stream lookup failed type=${media.type} id=${media.id}", it)
                error = why
                onFailed(why)
            }
    }

    val waiting = needsResolve && resolved == null
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ExoSurface(
            media = media,
            resolved = resolved,
            waitingForStream = waiting,
            showControls = showControls,
            onHandle = onHandle,
            onFailed = onFailed,
            onFrameSnapshot = onFrameSnapshot,
            onEnded = onEnded,
            onStall = onStall,
            qualityIndex = qualityIndex,
            planStart = planStart
        )
        when {
            error != null -> Message(error!!)
            waiting -> CircularProgressIndicator()
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ExoSurface(
    media: MediaFrame,
    resolved: ResolvedStream?,
    /** [media]'s stream is still being looked up (or the lookup failed):
     *  stop the previous item and load nothing yet. */
    waitingForStream: Boolean,
    showControls: Boolean,
    onHandle: (PlayerHandle) -> Unit,
    onFailed: (String) -> Unit,
    onFrameSnapshot: ((Bitmap) -> Unit)?,
    onEnded: (() -> Unit)?,
    onStall: ((Long) -> Unit)?,
    qualityIndex: Int,
    planStart: (MediaFrame, String) -> MediaFrame
) {
    val context = LocalContext.current
    val isTv = remember { isTvDevice(context) }
    // The ExoPlayer listener below is registered once for the player's
    // whole life, which spans many items; read the callbacks through these
    // so it always calls the current ones, not the first composition's.
    val currentOnFailed by rememberUpdatedState(onFailed)
    val currentOnEnded by rememberUpdatedState(onEnded)
    val currentOnStall by rememberUpdatedState(onStall)
    val currentOnFrameSnapshot by rememberUpdatedState(onFrameSnapshot)
    val currentPlanStart by rememberUpdatedState(planStart)
    val exo = remember(isTv) {
        val bandwidthMeter = DefaultBandwidthMeter.getSingletonInstance(context)
        // NextRenderersFactory is DefaultRenderersFactory plus FFmpeg
        // decoders. EXTENSION_RENDERER_MODE_ON puts them after the device's
        // own decoders, so they're only used for a format the device can't
        // decode itself (DTS, TrueHD, AC-3 on some phones) — without them
        // such a video plays with no sound. Both kinds of audio renderer use
        // the sink built here.
        val renderersFactory = object : NextRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink? {
                val bufferSizeProvider = DefaultAudioTrackBufferSizeProvider.Builder()
                    .setMinPcmBufferDurationUs(500_000)
                    .setMaxPcmBufferDurationUs(2_000_000)
                    .setPcmBufferMultiplicationFactor(6)
                    .build()
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioTrackBufferSizeProvider(bufferSizeProvider)
                    .build()
            }
        }.apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            if (isTv) {
                setEnableDecoderFallback(true)
            }
        }

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val trackSelector = DefaultTrackSelector(context).apply {
            if (isTv) {
                parameters = buildUponParameters()
                    .setPreferredVideoMimeType(MimeTypes.VIDEO_H264)
                    .setMaxVideoSize(1920, 1080)
                    .setMaxVideoFrameRate(60)
                    .build()
            }
        }

        val loadControl = DefaultLoadControl.Builder().run {
            if (isTv) {
                setAllocator(DefaultAllocator(true, 64 * 1024))
                    .setTargetBufferBytes(bufferBudgetBytes(context, isTv = true))
                    .setBufferDurationsMs(
                        /* minBufferMs = */ 15_000,
                        /* maxBufferMs = */ 30_000,
                        /* bufferForPlaybackMs = */ 1_500,
                        /* bufferForPlaybackAfterRebufferMs = */ 3_000
                    )
                    .setBackBuffer(
                        /* backBufferDurationMs = */ 0,
                        /* retainBackBufferFromKeyframe = */ false
                    )
                    .setPrioritizeTimeOverSizeThresholds(false)
            } else {
                setBufferDurationsMs(
                    DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                    LOAD_CONTROL_MAX_BUFFER_MS,
                    LOAD_CONTROL_BUFFER_FOR_PLAYBACK_MS,
                    LOAD_CONTROL_BUFFER_AFTER_REBUFFER_MS
                )
                    .setBackBuffer(LOAD_CONTROL_BACK_BUFFER_MS, true)
                    .setTargetBufferBytes(bufferBudgetBytes(context, isTv = false))
                    .setPrioritizeTimeOverSizeThresholds(false)
            }
            build()
        }

        ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setBandwidthMeter(bandwidthMeter)
            // handleAudioFocus=false: don't request AUDIOFOCUS_GAIN. With it
            // true (the old behavior), starting a video silences/pauses
            // whatever else is playing (Spotify, etc). false means CyTube
            // just plays without asking for exclusive audio rights, so it
            // coexists instead — at the cost of not auto-pausing itself for
            // interruptions like a phone call ringtone.
            .setAudioAttributes(audioAttributes, false)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // Only a default: NativePlayerHandle.seekTo picks EXACT or
            // NEXT_SYNC per seek, depending on whether the target is buffered.
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            .setLoadControl(loadControl)
            .build()
    }
    val handle = remember(exo) { NativePlayerHandle(exo, context) }

    // Exposes this ExoPlayer to the system: a Fire TV remote's dedicated
    // media keys, Alexa's "pause"/"resume" voice commands, and any system
    // Now Playing surface all reach whichever app currently holds the
    // active session — Media3 keeps this session's playback state and
    // metadata in sync with `exo` on its own, so there is nothing else to
    // wire up here beyond creating and releasing it alongside the player.
    // Scoped to the player's own lifetime (same as the error/frame listener
    // below), not a standalone service — playback keeps running via
    // ExoPlayer's own lifecycle when backgrounded outside of PiP (only the
    // video track gets dropped — see NativePlayerHandle.setVideoEnabled),
    // so there is no "still playing but the session's gone" gap to cover.
    //
    // The id MUST be unique across every session live in the process at
    // once: Compose creates a new ExoSurface's player and session before
    // the old one's DisposableEffect cleanup runs, so for a moment both
    // exist (e.g. one channel screen crossfading into another). Two
    // sessions with Media3's default empty-string id crash with "Session ID
    // must be unique"; a process-wide counter means they never collide.
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
    var ambientBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var transitionFreezeFrame by remember { mutableStateOf<Bitmap?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            ambientBitmap?.recycle()
            ambientBitmap = null
            transitionFreezeFrame?.recycle()
            transitionFreezeFrame = null
        }
    }

    val scope = rememberCoroutineScope()
    // Download speed against what the video needs, for the log: sampled
    // every second, reported with every buffering spell.
    val downloadWatch = remember(exo) { DownloadWatch() }
    fun knownBitrate(): Long {
        val video = runCatching { exo.videoFormat?.bitrate }.getOrNull() ?: -1
        if (video <= 0) return 0L
        val audio = runCatching { exo.audioFormat?.bitrate }.getOrNull() ?: -1
        return video.toLong() + audio.coerceAtLeast(0)
    }
    LaunchedEffect(exo) {
        while (true) {
            delay(1_000L)
            val buffered = runCatching { exo.bufferedPosition }.getOrDefault(0L)
            downloadWatch.sample(SystemClock.elapsedRealtime(), MediaBytes.total(), buffered)
        }
    }
    DisposableEffect(exo) {
        // Per-item stall tracking. The same ExoPlayer plays one item after
        // another (and quality changes reload in place), so these are reset
        // by onMediaItemTransition below at each new media source.
        var reachedReadyOnce = false
        var stallStartedAtMs = 0L
        var stallJob: Job? = null
        val recentStalls = mutableListOf<Pair<Long, Long>>()
        // When the last seek (in practice, a SyncEngine correction) happened.
        // ExoPlayer reports a seek's STATE_BUFFERING in the same callback
        // batch as the seek's discontinuity, so buffering that starts within
        // SEEK_BUFFERING_WINDOW_MS of one is the seek's own cost, not a
        // bandwidth shortfall, and must not feed onStall → quality step-down
        // → reload → more drift → more seeks.
        var lastSeekAtMs = 0L
        // Every buffering spell and what started it, for the log.
        var bufferingSinceMs = 0L
        var bufferingCause = ""
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                reachedReadyOnce = false
                lastSeekAtMs = 0L
                stallJob?.cancel()
                stallJob = null
                stallStartedAtMs = 0L
                recentStalls.clear()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                    reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
                ) {
                    lastSeekAtMs = SystemClock.elapsedRealtime()
                }
            }

            // The immediate snapshot: taken the moment a new item's first
            // frame actually renders, so the glow doesn't sit on the
            // PREVIOUS item's color for the first few seconds of a new one.
            // Media3 also calls this on a media-source swap within the same
            // ExoPlayer instance (a playlist advance), which is exactly when
            // a fresh snapshot is wanted too. The
            // periodic resample loop below (see the LaunchedEffect right
            // after this listener) is what keeps the color moving with the
            // video for the rest of that item's runtime, rather than this
            // one-shot being the only update it ever gets.
            override fun onRenderedFirstFrame() {
                transitionFreezeFrame?.recycle()
                transitionFreezeFrame = null
                val snapshot = currentOnFrameSnapshot ?: return
                val textureView = playerViewRef[0]?.videoSurfaceView as? TextureView ?: return
                if (ambientBitmap == null || ambientBitmap?.isRecycled == true) {
                    ambientBitmap = Bitmap.createBitmap(
                        AMBIENT_SAMPLE_SIZE, AMBIENT_SAMPLE_SIZE, Bitmap.Config.ARGB_8888
                    )
                }
                val target = ambientBitmap ?: return
                runCatching { textureView.getBitmap(target) }
                    .getOrNull()
                    ?.let(snapshot)
            }

            override fun onPlayerError(error: PlaybackException) {
                transitionFreezeFrame?.recycle()
                transitionFreezeFrame = null
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
                Log.w("CyTubePlayer", "Media3 error type=${handle.mediaType} code=$detail id=${handle.mediaId} " +
                    "responseHeaderNames=${http?.headerFields?.keys}")
                currentOnFailed(detail)
            }

            // STATE_ENDED is ExoPlayer's own terminal state for "ran off the
            // end of the media on its own" — distinct from a seek (which
            // never leaves STATE_READY), a release (the listener is removed
            // on dispose first), or the empty player NativePlayerHandle.stop
            // leaves while a stream is looked up (loadedKey is null then; a
            // play command "ends" its empty playlist at once). Drives
            // personal/unsynced playlist auto-advance — see
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
                if (playbackState == Player.STATE_ENDED && handle.loadedKey != null) {
                    currentOnEnded?.invoke()
                }
                when (playbackState) {
                    Player.STATE_READY -> {
                        if (bufferingSinceMs != 0L) {
                            Log.i(
                                "CyTubeSync",
                                "buffered ${SystemClock.elapsedRealtime() - bufferingSinceMs}ms ($bufferingCause); " +
                                    downloadWatch.report(knownBitrate())
                            )
                            bufferingSinceMs = 0L
                        }
                        stallJob?.cancel()
                        stallJob = null
                        if (!reachedReadyOnce) {
                            reachedReadyOnce = true
                            stallStartedAtMs = 0L
                        } else if (stallStartedAtMs != 0L) {
                            val now = SystemClock.elapsedRealtime()
                            val stalledMs = now - stallStartedAtMs
                            stallStartedAtMs = 0L
                            recentStalls.add(now to stalledMs)
                            recentStalls.removeAll { now - it.first > RECENT_STALL_WINDOW_MS }
                            val totalStalledMs = recentStalls.sumOf { it.second }
                            if (totalStalledMs >= STALL_TRIGGER_MS || (recentStalls.size >= 2 && totalStalledMs >= 2_000L)) {
                                recentStalls.clear()
                                // The measured total, which can be under
                                // STALL_TRIGGER_MS (the 2-small-stalls case);
                                // see QUALITY_DOWNGRADE_STALL_THRESHOLD_MS.
                                currentOnStall?.invoke(totalStalledMs)
                            }
                        }
                    }
                    Player.STATE_BUFFERING -> {
                        // Only mid-playback rebuffers count as stalls. The initial buffer-up
                        // (discovering container index/moov atom and seeking to start position on
                        // large files) can take several seconds and is NOT a bandwidth stall.
                        val nowMs = SystemClock.elapsedRealtime()
                        val seekInduced = nowMs - lastSeekAtMs < SEEK_BUFFERING_WINDOW_MS
                        // Likewise video being switched back on when the app
                        // returns from the background (see videoToggledAtMs).
                        val videoToggleInduced = nowMs - handle.videoToggledAtMs < VIDEO_TOGGLE_BUFFERING_WINDOW_MS
                        if (bufferingSinceMs == 0L) {
                            bufferingSinceMs = nowMs
                            bufferingCause = when {
                                !reachedReadyOnce -> "opening"
                                seekInduced -> "after a seek"
                                videoToggleInduced -> "video back on"
                                else -> "stall"
                            }
                            if (bufferingCause == "stall") {
                                Log.i("CyTubeSync", "stall started; ${downloadWatch.report(knownBitrate())}")
                            }
                        }
                        if (reachedReadyOnce && stallStartedAtMs == 0L && !seekInduced && !videoToggleInduced) {
                            val start = SystemClock.elapsedRealtime()
                            stallStartedAtMs = start
                            stallJob?.cancel()
                            stallJob = scope.launch {
                                recentStalls.removeAll { start - it.first > RECENT_STALL_WINDOW_MS }
                                val priorStalled = recentStalls.sumOf { it.second }
                                val threshold = (STALL_TRIGGER_MS - priorStalled).coerceIn(500L, STALL_TRIGGER_MS)
                                delay(threshold)
                                if (stallStartedAtMs == start) {
                                    recentStalls.clear()
                                    // Still stalled: report what has actually
                                    // accumulated, same as the READY path.
                                    currentOnStall?.invoke(SystemClock.elapsedRealtime() - start + priorStalled)
                                    // Reported: don't count it again when it ends.
                                    stallStartedAtMs = 0L
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
    // cost, and ChannelScreen already crossfades every new color in over a
    // few seconds, so infrequent sampling still reads as smooth rather than
    // a visible jump. Skipped while paused (nothing new to sample) and
    // whenever onFrameSnapshot is null (glow off, or on TV where it's never
    // shown).
    LaunchedEffect(exo) {
        while (true) {
            delay(AMBIENT_RESAMPLE_INTERVAL_MS)
            val snapshot = currentOnFrameSnapshot ?: continue
            val isPlaying = runCatching { exo.isPlaying }.getOrDefault(false)
            if (!isPlaying) continue
            // Nothing on screen (and no video decoded) while in the
            // background, so nothing worth sampling. This loop keeps running
            // then — it's a coroutine already under way, not a recomposition.
            if (AppVisibility.inBackground.value) continue
            val textureView = playerViewRef[0]?.videoSurfaceView as? TextureView ?: continue
            if (ambientBitmap == null || ambientBitmap?.isRecycled == true) {
                ambientBitmap = Bitmap.createBitmap(
                    AMBIENT_SAMPLE_SIZE, AMBIENT_SAMPLE_SIZE, Bitmap.Config.ARGB_8888
                )
            }
            val target = ambientBitmap ?: continue
            val bitmap = runCatching { textureView.getBitmap(target) }.getOrNull() ?: continue
            snapshot(bitmap)
        }
    }

    DisposableEffect(handle) {
        onDispose {
            playerViewRef[0] = null
            // Releasing is how the ViewModel learns this player is gone —
            // see ChannelViewModel.player. Session first, then the player it wraps — releasing in the
            // other order would leave the session momentarily pointing at
            // an already-released player.
            runCatching { mediaSession.release() }
            handle.release()
        }
    }

    // onHandle (which flows straight into ChannelViewModel.attachPlayer and
    // client.signalPlayerReady()) fires only after load()/loadUrl() below
    // has handed the media source to ExoPlayer. Any earlier (e.g. from the
    // DisposableEffect above, as soon as this enters composition) and the
    // ViewModel would start correcting, and the server would count us as
    // ready, while the player had nothing loaded — worst for YouTube, where
    // the stream lookup delays the load by a second or more, so SyncEngine
    // would hard-seek a player that had only just started loading.
    //
    // qualityIndex is a key too, so automatic quality adaptation switches
    // streams directly on the existing handle without tearing down the
    // player.
    LaunchedEffect(media.id, media.type, resolved, qualityIndex, waitingForStream) {
        if (waitingForStream) {
            // Nothing to load yet. Stop the previous item rather than let it
            // play on under the spinner; the ViewModel keeps this handle,
            // and SyncEngine leaves it alone while it holds no item.
            if (!handle.hasLoaded(media)) handle.stop()
            return@LaunchedEffect
        }
        // Already loaded into this player — ChannelViewModel does that itself
        // when the playlist moves on (or quality steps down) while the app
        // is in the background, because this effect can't run then. Loading
        // it again here would restart it from the (now old) start position.
        // A looked-up stream counts as loaded whichever URL it came from: the
        // ViewModel may have resolved the same item separately (the app went
        // to the background mid-lookup) and got a different signed URL.
        val loaded = if (resolved != null) handle.hasLoaded(media)
            else handle.loadedKey == NativePlayerHandle.loadKey(media, qualityIndex)
        if (!loaded) {
            // When adapting quality in place for the same media item, capture the last
            // rendered video frame so it stays displayed as an overlay while the decoder
            // flushes and buffers the new stream, preventing a black screen flash.
            if (handle.mediaId == media.id) {
                val textureView = playerViewRef[0]?.videoSurfaceView as? TextureView
                if (textureView != null && textureView.isAvailable) {
                    runCatching {
                        // Half size (a quarter of the pixels): it's only on
                        // screen for the moment the new stream takes to start,
                        // and a full-size copy is a big main-thread read.
                        val bmp = textureView.getBitmap(
                            (textureView.width / 2).coerceAtLeast(1),
                            (textureView.height / 2).coerceAtLeast(1)
                        )
                        if (bmp != null) {
                            transitionFreezeFrame?.recycle()
                            transitionFreezeFrame = bmp
                        }
                    }
                }
            }
            // A fresh open (not a quality switch, which keeps its place)
            // starts where the ViewModel says: for a synced item joined
            // partway through, a little ahead of the room, because opening
            // a big file there takes a while — see SyncEngine.planStart.
            val fresh = handle.mediaId != media.id
            if (resolved != null) {
                val start = if (fresh) currentPlanStart(media, resolved.url) else media
                handle.loadUrl(start, resolved.url, resolved.mimeType, resolved.headers, resolved.variant)
            } else {
                val url = NativePlayerHandle.sourceUrl(media, qualityIndex)
                val start = if (fresh) currentPlanStart(media, url) else media
                handle.load(start, qualityIndex)
            }
        }
        onHandle(handle)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                // Inflated from XML (player_view.xml) because a TextureView
                // surface can only be chosen there; it's what frame capture
                // (ambient glow, the quality-switch freeze frame) needs.
                val view = LayoutInflater.from(ctx)
                    .inflate(R.layout.player_view, null, false) as PlayerView
                view.apply {
                    this.player = exo
                    useController = showControls && !waitingForStream
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
                // No controls over the spinner: they'd only offer to play
                // the stopped previous item.
                view.useController = showControls && !waitingForStream
                playerViewRef[0] = view
            },
            onRelease = { view ->
                view.player = null
            }
        )
        val freezeFrame = transitionFreezeFrame
        if (freezeFrame != null && !freezeFrame.isRecycled) {
            Image(
                bitmap = freezeFrame.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
    }
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
