package com.cytube.mobile.ui.channel

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.TextureView
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import com.cytube.mobile.di.Graph
import com.cytube.mobile.net.MediaFrame
import com.cytube.mobile.net.MediaTypes
import com.cytube.mobile.net.SYNC_HARD_SEEK_THRESHOLD_SECONDS
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

/**
 * Reported document origin (no trailing slash) for the local pages
 * [dailymotionSdkHtml] and [youtubeIframeApiHtml] load via
 * loadDataWithBaseURL — see the yt call site's own comment for the
 * live-tested reasoning behind picking a plain, uninvolved third-party
 * origin here rather than the provider's own domain. example.com is
 * IANA-reserved specifically as a placeholder for exactly this: a real,
 * well-formed https origin guaranteed not to be any real site. No network
 * fetch is ever made against it — loadDataWithBaseURL only uses it as the
 * page's reported origin for cookies/CORS/postMessage/Referer purposes.
 * WebView's own network stack attaches a real Referer matching this origin
 * to every request on its own (confirmed live — see the yt
 * shouldInterceptRequest override's history comment for why an earlier,
 * hand-rolled attempt at forcing this manually was removed rather than
 * kept: it couldn't replay POST bodies and silently broke YouTube's
 * InnerTube API calls once this origin fix made it otherwise unnecessary).
 */
private const val EMBED_PAGE_ORIGIN = "https://example.com"

/** Logged verbatim (console.log, nothing else on the line) by
 *  youtubeIframeApiHtml/dailymotionSdkHtml the instant their own player
 *  fires an "ended" event — see EmbedSurface's onConsoleMessage override,
 *  which is the only thing that ever reads it. Exists because a WebView
 *  page has no other channel back to this app's onEnded callback; a
 *  literal sentinel string is simpler and harder to false-trigger than
 *  trying to parse the DIAG poll lines already flowing through the same
 *  console hook. */
private const val EMBED_ENDED_SENTINEL = "__CYTUBE_EMBED_ENDED__"

/** What [dailymotionSdkHtml]/[youtubeIframeApiHtml] load instead of building
 *  their real player page when the media id fails SAFE_EMBED_ID_REGEX — a
 *  plain black page, no script, nothing server-controlled in it at all. Same
 *  visible result (a black surface) as any other embed that never starts,
 *  rather than interpolating an untrusted id unsafely. */
private const val BLANK_EMBED_HTML =
    "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head>" +
        "<body style=\"margin:0;background:#000;\"></body></html>"

/** Ceiling ExoPlayer will buffer toward under good network — see ExoSurface's
 *  LoadControl. Media3's own default (DefaultLoadControl.DEFAULT_MAX_BUFFER_MS)
 *  is 50s; raised here to give a large, high-bitrate file more runway to
 *  absorb a bandwidth dip before it ever has to enter STATE_BUFFERING. */
private const val LOAD_CONTROL_MAX_BUFFER_MS = 90_000

/** Margin kept between [LOAD_CONTROL_BUFFER_AFTER_REBUFFER_MS] and
 *  SyncEngine's hard-seek threshold — see that constant's own comment for
 *  why closing this margin to zero (or crossing it) would be a real bug,
 *  not just a tuning nit. */
private const val REBUFFER_MARGIN_BELOW_HARD_SEEK_SECONDS = 2.0

/** How much ExoPlayer gathers before resuming playback after an actual
 *  stall — Media3's default is 5s, which was letting a file that stalled
 *  once on a slow connection immediately run dry and stall again a moment
 *  later. Raised so one stall has a real chance to be the last one for a
 *  while — but deliberately kept BELOW SyncEngine's own
 *  SYNC_HARD_SEEK_THRESHOLD_SECONDS (8s), not just an arbitrary bigger
 *  number: a rebuffer wait at or beyond that threshold would mean the
 *  drift built up by the wait itself is already enough to guarantee a hard
 *  seek (a visible jump) the instant playback resumes, on every single
 *  stall, rather than the gentle speed-ramp catch-up SyncEngine otherwise
 *  prefers. Deriving this from the shared constant (rather than a second
 *  hardcoded number) means the two can't quietly drift apart again if
 *  either one changes later. */
private val LOAD_CONTROL_BUFFER_AFTER_REBUFFER_MS: Int =
    ((SYNC_HARD_SEEK_THRESHOLD_SECONDS - REBUFFER_MARGIN_BELOW_HARD_SEEK_SECONDS) * 1000).toInt()

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
    /** Fires once, right after EMBED creates its WebView, with a handle the
     *  caller can use to drive play/pause/seek from outside it — see
     *  [EmbedPlayerController]'s own doc comment for why this exists at all.
     *  Never fires for any other player type. */
    onEmbedController: ((EmbedPlayerController) -> Unit)? = null,
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
                EmbedSurface(media.type, media.id, embedSrc, onEmbedController, onEnded)
            // WEB is handled by the channel screen, which swaps in the whole
            // CyTube page rather than a player.
            else -> Message("${MediaTypes.label(media.type)} needs Compatibility View.")
        }
    }
}

/**
 * A one-way remote for an [EmbedSurface]'s WebView, handed out once via
 * [PlayerSurface]'s onEmbedController when the WebView is created.
 *
 * Why this exists: EMBED loads a provider's own page, whose own on-screen
 * play/pause/seek bar is normally reachable the same way it is in a real
 * mobile browser tab — tap the video, the provider's chrome appears, use it.
 * In practice that chrome did not reliably show up/respond inside this
 * WebView (confirmed live: tapping the video does nothing), and EMBED items
 * are never synced to the channel's own clock in the first place (see
 * MediaTypes.Player.EMBED's doc comment — no PlayerHandle, SyncEngine leaves
 * them alone entirely), so once a viewer's copy drifts there was previously
 * no way to fix it short of reloading the item. This reaches straight into
 * the page's own <video> element instead of depending on the provider's UI.
 *
 * No state comes back out on purpose — play/pause is a blind toggle and seek
 * is a blind relative offset, not a read-modify-write, since there is no
 * reliable, cheap way to get playback state back out of an arbitrary
 * provider's page (a JavascriptInterface round-trip for something this
 * minor is more moving parts than the feature is worth). That matches what
 * tapping the provider's own controls would do anyway.
 */
class EmbedPlayerController(private val webView: WebView) {
    /** window.__cytubeEmbed, when the loaded page defines it (currently only
     *  [dailymotionSdkHtml]), is that provider's own SDK player instance
     *  wrapped the same way — needed there because the SDK's actual <video>
     *  lives inside a same-site-but-cross-origin child iframe DM.player()
     *  creates, which JS on this top page can't reach directly the way it
     *  can on every provider that's just a top-level navigation to its own
     *  page (loadUrl(embedSrc) below — those are the plain
     *  `document.querySelector('video')` fallback this always tries second). */
    fun togglePlayPause() {
        webView.evaluateJavascript(
            "(function(){" +
                "if(window.__cytubeEmbed){window.__cytubeEmbed.toggle();return;}" +
                "var v=document.querySelector('video');" +
                "if(v){if(v.paused){v.play();}else{v.pause();}}" +
                "})();",
            null
        )
    }

    /** Negative to rewind. Clamped to 0 on the low end; the page's own
     *  player clamps the high end against its own duration. */
    fun seekBy(deltaSeconds: Int) {
        webView.evaluateJavascript(
            "(function(){" +
                "if(window.__cytubeEmbed){window.__cytubeEmbed.seek($deltaSeconds);return;}" +
                "var v=document.querySelector('video');" +
                "if(v){v.currentTime=Math.max(0,v.currentTime+($deltaSeconds));}" +
                "})();",
            null
        )
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
 *
 * No backgrounded-pause hook, unlike ExoSurface's audioOnly. There is no way
 * to tell an arbitrary provider's embedded page to stop decoding video while
 * leaving its audio running — that would mean reaching into whatever player
 * JS the embed itself runs, which varies per provider and isn't something
 * this WebView controls. The only lever this WebView actually has,
 * WebView.onPause()/onResume(), stops everything, audio included — that was
 * tried and reverted: it meant an embed went fully silent the instant the app
 * was backgrounded, a real behavior difference from the native path (which
 * keeps playing audio-only via ExoSurface's track selection instead). So an
 * embed just keeps running, full cost, while backgrounded — correct playback
 * over a battery win this surface can't deliver safely.
 */
@Composable
private fun EmbedSurface(
    type: String,
    id: String,
    embedSrc: String,
    onController: ((EmbedPlayerController) -> Unit)? = null,
    onEnded: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val embedHost = remember(embedSrc) { Uri.parse(embedSrc).host }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            // Same as WebCompatView's own setup — an embed player commonly
            // needs a cookie for a consent choice, an ad slot, or a session
            // the site's JS checks before it will start decoding at all.
            // Without this, WebView's per-app default can still leave
            // third-party cookies (ad/DRM-license subdomains) blocked even
            // with first-party cookies on.
            android.webkit.CookieManager.getInstance().setAcceptCookie(true)
            WebView(ctx).apply {
                android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                // A prior theory here was that Compose's AndroidView breaks
                // Chromium's hardware video hole-punch compositing, and
                // forced this WebView onto LAYER_TYPE_SOFTWARE to work
                // around it. That didn't fix the black screen, and
                // WebCompatView — the same kind of WebView, in the same kind
                // of Compose host, with none of this — plays other
                // providers' video (confirmed: a yt live stream) just fine.
                // So the compositing theory was wrong; the real cause was
                // specific to how the dm item's URL got loaded (see the
                // type == "dm" branch below), not a general WebView/Compose
                // problem, and forcing software rendering here would only
                // have cost every OTHER embed provider its GPU compositing
                // for nothing.
                // The default WebView canvas is white, and it paints that
                // white before the page's own CSS ever gets a chance to load
                // — the flash (and often a lingering white margin around
                // whatever the page doesn't fill) is what was "ruining the
                // immersion" here. Black is the one background that's always
                // right for a video surface sitting in an otherwise-black
                // player area.
                setBackgroundColor(android.graphics.Color.BLACK)
                // Confirmed live, for the yt/IFrame-API case specifically:
                // DIAG logging showed document.hasFocus() === false the
                // entire time this WebView sat on screen, even with
                // visibilityState === 'visible' and onLine === true —
                // while state stayed stuck at BUFFERING and
                // loadedFraction never left 0, for minutes. An embedded
                // WebView living alongside other focusable views in this
                // app's own Compose UI (chat's text field, buttons) does
                // NOT automatically receive Android input focus the way a
                // real standalone browser tab does — nothing here was ever
                // asking for it. Chromium throttles background/unfocused
                // documents' network activity fairly aggressively (exactly
                // the kind of stall this matches: a live stream's manifest
                // fetch that never progresses). isFocusable is on by
                // default for WebView already; isFocusableInTouchMode is
                // what's actually needed for a touch-driven Android UI to
                // let this view take focus at all — see requestFocus() in
                // this AndroidView's update block below, which is what
                // actually claims it once attached.
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
                    // A popup/new-window is how some embed players try to
                    // "open in a new tab" rather than navigate the iframe in
                    // place; there is nowhere for that to go here, so it's
                    // refused outright rather than silently doing nothing.
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    // dm-only: live testing showed Dailymotion's page
                    // serving a Cloudflare Turnstile challenge that never
                    // resolves here — DIAG logging found <video> elements
                    // that stay at w=0 h=0 paused=true indefinitely, well
                    // past the point the same diagnostic script confirmed
                    // real playback on the yt path. Android WebView's
                    // default User-Agent carries a "; wv)" token identifying
                    // it as an embedded WebView rather than a full browser
                    // tab — a well-documented signal bot-management vendors
                    // (Cloudflare included) treat as suspicious even though
                    // the underlying engine is identical to Chrome itself.
                    // CyTube's real web client (player/dailymotion.coffee)
                    // never carries this marker because it only ever runs
                    // inside an actual browser tab. Stripping the token
                    // makes this WebView's fingerprint match what a genuine
                    // browser — and CyTube's own client — presents; nothing
                    // else about the request changes. Scoped to dm only so
                    // the already-confirmed-working yt path (and every other
                    // provider) keeps the exact User-Agent it had.
                    if (type == "dm") {
                        userAgentString = userAgentString.replace("; wv", "")
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        // Only a top-level navigation away from the embed's
                        // own site counts as "this isn't the video anymore" —
                        // a subframe the embed itself creates (its own
                        // player chrome, an ad/asset host, etc.) needs to load
                        // in place same as in WebCompatView.
                        //
                        // "own site" is the registrable domain, not an exact
                        // host match: an embed page's own consent/session/geo
                        // redirect commonly lands on a sibling subdomain
                        // (dailymotion.com's embed flow was observed doing
                        // exactly this) before settling back on the player.
                        // An exact-host check treated that hop as "left the
                        // video" and kicked the whole thing out to an
                        // external browser mid-load — see sameSite's own doc
                        // comment for what this does and doesn't relax.
                        if (!request.isForMainFrame) return false
                        if (sameSite(request.url.host, embedHost)) return false
                        openInBrowser(context, request.url.toString())
                        return true
                    }

                    // HISTORY, in order, because the real cause turned out
                    // to be the opposite of what this override was built
                    // for — worth keeping straight rather than rewriting
                    // away: (1) a <meta name="referrer"> tag alone did NOT
                    // stop error 152-4. (2) This override was added to force
                    // a real Referer at the network layer instead
                    // (forceYouTubeReferer, matching a real-world fix for
                    // the identical error code) — tested alone, ALSO made
                    // no difference at all, same error, same instant
                    // timing. (3) Separately, youtubeIframeApiHtml's own
                    // page origin was changed from youtube.com itself to a
                    // neutral third party (example.com) — THAT was the
                    // actual fix for 152, confirmed live (the error
                    // vanished the run this shipped). So by the time 152
                    // was gone, this override had already been shown
                    // useless on its own and was just still sitting here.
                    // (4) With 152 gone, playback stalled at BUFFERING
                    // forever instead — and logging every request's host
                    // (kept below) caught the actual cause: this override
                    // was forcing EVERY request to (www.)youtube.com
                    // through forceYouTubeReferer's HttpURLConnection,
                    // which never read request.method and defaults to GET
                    // — silently downgrading YouTube's InnerTube API POSTs
                    // (youtubei/v1/player, the exact call that returns the
                    // live stream's actual playability/manifest data) into
                    // GETs, which the server correctly rejected with 405.
                    // That 405 on /player is why the video never had
                    // anything to load: loadedFraction stayed at 0 because
                    // the player never got a working player response at
                    // all. WebResourceRequest has no API to read the
                    // original POST body either, so there's no way to
                    // proxy these faithfully even by fixing the method.
                    // The origin fix in (3) means WebView's own normal
                    // network stack already sends a real, consistent
                    // Referer matching this page's genuine origin for
                    // every request — GET and POST alike — without any of
                    // this. So the fix is to stop intercepting entirely:
                    // this override now only logs (still valuable — it's
                    // what caught the 405) and never touches the request.
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): android.webkit.WebResourceResponse? {
                        if (type == "yt") {
                            // host only, never the full URL — these are real
                            // requests to YouTube's InnerTube API and can
                            // carry session/auth query parameters that don't
                            // belong in Logcat.
                            Log.d(
                                "CyTubePlayer",
                                "yt embed request method=${request.method} host=${request.url.host}"
                            )
                        }
                        return null
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
                        // The <video>-element poller that used to run here
                        // (setInterval logging "DIAG videos=...") was scoped
                        // to the original dm black-screen investigation and
                        // was meant to come out with the rest of that
                        // diagnostic instrumentation — it stayed behind by
                        // mistake, running unconditionally on every embed
                        // type including yt/vi, where it never found anything
                        // (the real <video> element for an SDK-embedded
                        // provider lives inside a cross-origin iframe this
                        // top page's DOM can't see, so it always logged
                        // videos=0 there — pure noise, not a real check).
                        // Removed outright rather than re-scoped to dm only:
                        // the dm bug it was built for is fixed and confirmed
                        // working (see dailymotionSdkHtml).
                    }
                }
                // With no WebChromeClient at all, WebView's default behavior
                // is to silently DENY every onPermissionRequest — including
                // RESOURCE_PROTECTED_MEDIA_ID, the handshake EME/Widevine-
                // gated playback needs before it will decode a single frame.
                // Most ad-supported commercial video platforms (Dailymotion
                // included) gate at least some of their delivery through
                // this, so without it the embed loaded and ran, but played
                // fully black (and silent) with no visible error anywhere —
                // the request was refused before decoding ever started, not
                // a rendering problem. Grants ONLY protected-media, same as
                // a normal browser tab does automatically for this specific
                // permission; camera/mic/MIDI stay denied, same as before.
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

                    // The EME grant above turned out not to be the whole
                    // story — still black after it. Rather than guess again,
                    // this surfaces whatever the embed page's own JS is
                    // actually saying (a codec/CORS/license error, a blocked
                    // request, etc.) in Logcat under the same tag ExoSurface
                    // already logs to, so the real cause can be read instead
                    // of guessed a third time.
                    override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage): Boolean {
                        Log.w(
                            "CyTubePlayer",
                            "embed console [${consoleMessage.messageLevel()}] " +
                                "${consoleMessage.message()} " +
                                "(${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                        )
                        // Both youtubeIframeApiHtml and dailymotionSdkHtml log
                        // this exact string (see EMBED_ENDED_SENTINEL) the
                        // moment their own page thinks this item finished —
                        // there is no other channel out of an arbitrary
                        // provider's WebView page back to Kotlin here, so
                        // piggybacking on the same console hook DIAG logging
                        // already uses is the cheapest way to get a real
                        // signal out. The dm side of this is unverified by
                        // live testing (see dailymotionSdkHtml's own comment)
                        // — kept deliberately simple (a literal string match,
                        // not a prefix/parse) so a page that never sends it
                        // just never auto-advances, rather than risking a
                        // false match on something else.
                        if (consoleMessage.message() == EMBED_ENDED_SENTINEL) {
                            onEnded?.invoke()
                        }
                        return true
                    }
                }
                if (type == "dm") {
                    // Loading dailymotion.com/embed/video/ID directly (what
                    // knownEmbedUrl builds and every other provider here
                    // just loadUrl()s) played fully black with audio, and
                    // forcing software rendering above didn't change that —
                    // so the cause isn't a general "WebView can't composite
                    // video" problem after all (WebCompatView, the SAME kind
                    // of WebView, plays other providers' video fine). It's
                    // specific to that bare iframe URL. CyTube's own client
                    // never loads it either: its dailymotion.coffee player
                    // loads Dailymotion's own Player SDK script
                    // (api.dmcdn.net/all.js) and calls DM.player() against an
                    // element on ITS page, rather than navigating to
                    // Dailymotion's embed URL as a top-level page the way
                    // knownEmbedUrl's link does. This does the same thing:
                    // a tiny local page that pulls in the real SDK and lets
                    // it build the player itself. baseUrl was originally set
                    // to a real dailymotion.com origin on the theory that
                    // the SDK needed to see "the same origin it would on
                    // Dailymotion's own site" — but that theory turned out
                    // to be backwards, confirmed on the yt case just below
                    // (identical pattern, identical player family): CyTube's
                    // real client's own page origin is never youtube.com or
                    // dailymotion.com, it's whatever domain the CyTube
                    // instance itself runs on — a normal THIRD-PARTY site
                    // embedding the provider's video, the ordinary case
                    // every provider's SDK is built to expect. Claiming to
                    // BE the provider's own domain is the unusual,
                    // effectively self-embedding case, and is what actually
                    // explains yt's error 152 surviving two independent
                    // Referer fixes untouched (see the yt branch's own
                    // comment for the live-tested evidence). example.com
                    // (no network fetch happens against it — loadDataWithBaseURL
                    // just uses it as the page's reported origin for
                    // cookies/CORS/postMessage checks) is IANA-reserved
                    // specifically as a placeholder for exactly this: a
                    // real, well-formed https origin that is definitely not
                    // any real site, still non-null/non-about:blank (which
                    // video platforms commonly refuse to serve to at all).
                    loadDataWithBaseURL(
                        "$EMBED_PAGE_ORIGIN/",
                        dailymotionSdkHtml(id),
                        "text/html",
                        "utf-8",
                        null
                    )
                } else if (type == "yt") {
                    // Same root cause as dm, different symptom: navigating a
                    // WebView straight to youtube.com/embed/ID — what
                    // knownEmbedUrl builds, and what this loaded before —
                    // gets YouTube's own player to show "Error 153" instead
                    // of the video, a well-documented YouTube-in-WebView
                    // failure. It shows up here on any yt item that reaches
                    // EMBED at all: a live stream routed here up front (see
                    // MediaTypes.playerFor's isLive branch — NEWPIPE can't
                    // reliably play live), or a VOD item NEWPIPE started and
                    // then failed on (reportPlaybackFailure's silent
                    // fallback). CyTube's own client never navigates to that
                    // URL either — it loads the real YouTube IFrame Player
                    // API (youtube.com/iframe_api) and constructs a
                    // YT.Player against an element on ITS OWN page, the same
                    // shape of fix as dailymotionSdkHtml just above.
                    //
                    // baseUrl was originally set to https://www.youtube.com/
                    // itself, on the theory that the SDK needed a real
                    // https origin rather than null/about:blank. That got
                    // past "Error 153" (confirmed: www-widgetapi.js loaded
                    // and ran), but every rebuild after that hit a NEW,
                    // different failure — onError UNKNOWN(152) firing in
                    // the exact same millisecond as onReady, every single
                    // time — and it survived two independent, targeted
                    // fixes for the most likely explanation (a missing
                    // Referer header): a <meta name="referrer"> tag, then a
                    // shouldInterceptRequest network-layer override forcing
                    // a real Referer HTTP header onto every request to
                    // (www.)youtube.com directly. Neither changed the
                    // outcome at all — same code, same instant timing, on
                    // every rebuild. A real network-dependent check
                    // failing would be expected to show SOME variance
                    // (network latency, or at least a different code once
                    // the actual header changed); getting the identical
                    // result regardless points away from Referer entirely
                    // and toward something evaluated locally and
                    // synchronously the moment playVideo() runs.
                    //
                    // The one thing that WAS still true in every failing
                    // attempt: this page's own reported origin was
                    // youtube.com itself — CyTube's real client never does
                    // this (its page origin is always the CyTube instance's
                    // own domain, a normal third-party site embedding
                    // someone else's video, which is the ordinary case
                    // every provider's player is built to expect). A page
                    // that claims to BE youtube.com while asking youtube.com
                    // to embed one of its own videos is an unusual,
                    // effectively self-embedding configuration, and is the
                    // remaining, untested variable. example.com (no network
                    // fetch happens against it — loadDataWithBaseURL just
                    // uses it as the page's reported origin for
                    // cookies/CORS/postMessage checks) is IANA-reserved
                    // specifically as a placeholder for exactly this: a
                    // real, well-formed https origin that is definitely not
                    // any real site — still a genuine https origin, not
                    // null/about:blank, same reasoning as before, just no
                    // longer self-referential.
                    loadDataWithBaseURL(
                        "$EMBED_PAGE_ORIGIN/",
                        youtubeIframeApiHtml(id),
                        "text/html",
                        "utf-8",
                        null
                    )
                } else if (type == "vi") {
                    // Same family of problem as yt/dm, checked the same way
                    // before assuming it: CyTube's real client
                    // (player/vimeo.coffee, calzoneman/sync) never navigates
                    // a page straight to player.vimeo.com/video/ID — what
                    // knownEmbedUrl builds and what every other provider
                    // here just loadUrl()s. It builds an <iframe> pointing
                    // there as an element on ITS OWN page, loads Vimeo's own
                    // Player SDK (player.vimeo.com/api/player.js), and
                    // wraps that iframe with `new Vimeo.Player(iframeEl)`
                    // for control/events — the same shape as the dm/yt
                    // fixes just above (SDK loaded on our own page, real
                    // https origin, provider's script builds/controls the
                    // actual player) rather than a bare top-level
                    // navigation. Applying the same fix preemptively here
                    // rather than waiting to hit the same failure live,
                    // since the pattern (and its fix) is now proven twice.
                    loadDataWithBaseURL(
                        "$EMBED_PAGE_ORIGIN/",
                        vimeoSdkHtml(id),
                        "text/html",
                        "utf-8",
                        null
                    )
                } else if (embedSrc.startsWith("https://", ignoreCase = true)) {
                    // embedSrc here is meta.embed.src, scuri, or
                    // MediaTypes.knownEmbedUrl's own output — all either
                    // server-supplied strings (the first two) or built from
                    // one (the third, now itself validated for the "pt"
                    // case — see MediaTypes.knownEmbedUrl). None of that is
                    // a trusted constant, and this is the one WebView load
                    // call in the app that would otherwise hand an
                    // unvalidated scheme straight to loadUrl(): a doctored
                    // media frame could set embedSrc to a javascript:/
                    // file:/content: URI and have it navigated as the
                    // "video". mixedContentMode/allowFileAccess/
                    // allowContentAccess above already narrow a lot of that,
                    // but this is the actual scheme gate — only a real
                    // https page is ever loaded here; anything else falls
                    // through to the same blank surface a failed embed
                    // already shows. This is also the path Twitch (tw/tv/tc)
                    // would hit if it ever reached EMBED — it never does
                    // (MediaTypes.playerFor has no branch for it, and
                    // knownEmbedUrl returns null for those types on purpose,
                    // see that function's own comment), so Twitch always
                    // falls through to the whole-page WEB offer instead,
                    // where the real CyTube page's own origin — not a faked
                    // one — is what its parent-domain check actually needs.
                    // Untouched here; not yet live-tested this session.
                    loadUrl(embedSrc)
                } else {
                    Log.w("CyTubePlayer", "refusing to load embed with untrusted scheme: type=$type")
                    loadDataWithBaseURL("$EMBED_PAGE_ORIGIN/", BLANK_EMBED_HTML, "text/html", "utf-8", null)
                }
                // factory runs exactly once per WebView (see the load
                // branch just above — there's nothing in `update` below
                // that would ever reload a second item into this same
                // instance), so this fires once with a controller that
                // stays valid for this item's whole time on screen.
                onController?.invoke(EmbedPlayerController(this))
            }
        },
        // requestFocus() here rather than in `factory`: this view is not
        // guaranteed to be attached to the window yet when factory returns
        // (Compose attaches it as part of composing this call), and
        // View.requestFocus() on an unattached view can silently no-op.
        // `update` runs after Compose has actually placed it, and again on
        // any later recomposition — calling this repeatedly is harmless,
        // and it's the only reliable point to claim focus from. See the
        // isFocusableInTouchMode comment above for why this is needed at
        // all: without it the yt IFrame API case sat with
        // document.hasFocus() permanently false, which is what this fixes.
        update = { it.requestFocus() }
    )
}

/**
 * CyTube's own media id for a dm/yt item is expected to be a provider's bare
 * video id — alphanumeric, occasionally with `-`/`_` (YouTube's 11-character
 * ids use both). [dailymotionSdkHtml]/[youtubeIframeApiHtml] splice it
 * straight into a `<script>` block as a JS string literal, so this is the
 * actual security boundary for that: a value that matches gets interpolated
 * (still backslash/quote-escaped below, defense in depth); a value that
 * doesn't is refused outright rather than patched up, because quote/
 * backslash escaping alone does not stop every injection shape here — an id
 * containing the literal substring "</script>" closes the surrounding
 * `<script>` tag regardless of how the JS-string content inside it was
 * escaped, letting a doctored media id (a malicious or compromised channel's
 * changeMedia frame — this is server data, not a value the app generated)
 * inject arbitrary HTML/JS into a page this WebView then loads and runs.
 */
private val SAFE_EMBED_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")

/**
 * A minimal page that does exactly what CyTube's own dailymotion.coffee
 * player does: load Dailymotion's Player SDK (api.dmcdn.net/all.js) and let
 * it build the player against a plain element, rather than treating
 * Dailymotion's embed URL as an ordinary page to navigate to. See the
 * EmbedSurface call site for why this exists — the ordinary knownEmbedUrl
 * iframe played fully black.
 *
 * [id] is CyTube's media id for a dm item, which is always Dailymotion's own
 * bare video id (see get-info.js/mediaquery upstream — nothing else is ever
 * packed into it the way, say, PeerTube's id has a domain baked in) — but it
 * is still server data, not a trusted constant, so it's validated against
 * [SAFE_EMBED_ID_REGEX] before it ever reaches the template; an id that
 * fails renders a blank black page instead (same visible failure mode as any
 * other embed that never starts) rather than being interpolated unsafely.
 */
private fun dailymotionSdkHtml(id: String): String {
    if (!SAFE_EMBED_ID_REGEX.matches(id)) return BLANK_EMBED_HTML
    // Not a full JS-string escaper, but no longer the only line of defense
    // either — id is already known to match SAFE_EMBED_ID_REGEX (no quotes,
    // backslashes, angle brackets, or anything else meaningful to HTML/JS
    // can reach here), so this is pure defense in depth against this regex
    // itself ever being loosened carelessly later.
    val safeId = id.replace("\\", "\\\\").replace("'", "\\'")
    return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
        <!-- Confirmed on the yt page (see youtubeIframeApiHtml's own
             comment) that WebView doesn't reliably attach a Referer to
             requests a loadDataWithBaseURL page makes, which broke
             YouTube's embed verification outright. Applied here too,
             defensively: Dailymotion's own Player SDK does similar
             origin/referer-based checks before it'll serve a stream, and
             this is a one-line, harmless-if-unneeded fix rather than
             waiting to hit the identical failure mode on this provider
             separately. -->
        <meta name="referrer" content="strict-origin">
        <style>
          /* height:100% (still used below by the player SDK's own width/
             height options, which is fine — that's a different, later
             resolution against #dmplayer's now-fixed size) cascading through
             html -> body -> #dmplayer collapsed to a real height in a normal
             browser tab, but reports 0 in this WebView (confirmed for the
             identical pattern in youtubeIframeApiHtml — see that function's
             own comment for the diagnostic evidence). position:fixed with
             all four insets anchors directly to the WebView's own layout
             viewport instead of depending on that percentage chain
             resolving, which is the standard fix for this exact WebView
             quirk. */
          html, body { margin: 0; padding: 0; background: #000; overflow: hidden; }
          html, body, #dmplayer { position: fixed; top: 0; right: 0; bottom: 0; left: 0; }
        </style>
        </head>
        <body>
        <div id="dmplayer"></div>
        <script>
          window.dmAsyncInit = function() {
            // The previous fix (calling .create() on DM.player()'s return
            // value) was itself still a guess dressed up as a lead, and
            // there was a better answer sitting in CyTube's own real web
            // client the whole time: player/dailymotion.coffee (github.com/
            // calzoneman/sync, the actual proven-working CyTube source, not
            // an assumption about it) calls DM.Player.create(element,
            // options) — capital "Player", a static factory method — never
            // DM.player(element, options) lowercase. This code called the
            // lowercase form from the start, which live testing proved
            // returns a limited controller (create/destroy/play/pause/
            // seek/etc, no paused/currentTime/duration properties, no
            // .on()) rather than the real player DM.Player.create() hands
            // back. That explains every symptom seen so far in one shot —
            // wrong entry point the whole time, not a Promise, not a
            // missing create() call on the wrong object. Matching the real
            // client's call exactly now instead of guessing at this
            // object's own internals further.
            var el = document.getElementById('dmplayer');
            // Same defensive check the real client makes before creating
            // (DM.Player._INSTANCES[@element.id] / DM.Player.destroy): a
            // stale instance already bound to this element id would
            // otherwise make create() below misbehave. Unlikely to ever
            // fire here (this WebView loads fresh HTML per changeMedia,
            // never reuses a #dmplayer element across loads) but matching
            // the real client exactly costs nothing.
            if (window.DM && DM.Player && DM.Player._INSTANCES && DM.Player._INSTANCES[el.id]) {
              DM.Player.destroy(el.id);
            }
            var player = DM.Player.create(el, {
              video: '$safeId',
              width: '100%',
              height: '100%',
              params: {
                autoplay: 1,
                logo: 0,
                'queue-enable': false,
                'sharing-enable': false,
                'ui-logo': false,
                'ui-start-screen-info': false
              }
            });
            // The real client guards every play()/pause()/seek()/load()
            // call with "if @dm and @dmReady", only ever set true inside
            // the 'apiready' handler — calling these before that fires is
            // apparently unsafe on this SDK. Mirrored here as `ready`.
            var ready = false;
            var lastKnownPaused;
            // See EmbedPlayerController's own comment on why this exists —
            // the SDK's real <video> is inside a child iframe this top
            // page's JS can't reach directly, so the controller goes
            // through the SDK's own player object instead.
            window.__cytubeEmbed = {
              toggle: function() {
                if (!player || !ready) return;
                if (lastKnownPaused) { player.play(); } else { player.pause(); }
              },
              seek: function(delta) {
                if (!player || !ready) return;
                var t = (typeof player.currentTime === 'number' ? player.currentTime : 0) + delta;
                player.seek(Math.max(0, t));
              }
            };
            if (player && player.addEventListener) {
              player.addEventListener('apiready', function() { ready = true; });
              player.addEventListener('pause', function() { lastKnownPaused = true; });
              player.addEventListener('playing', function() { lastKnownPaused = false; });
              // CyTube's own client advances to the next video specifically
              // on 'ended' — 'video_end' has a different, narrower meaning
              // there (briefly marks the player not-ready, unrelated to
              // auto-advance). Matching that real distinction rather than
              // firing the sentinel on every end-ish name blind.
              player.addEventListener('ended', function() {
                console.log('$EMBED_ENDED_SENTINEL');
              });
            }
          };
        </script>
        <script src="https://api.dmcdn.net/all.js"></script>
        </body>
        </html>
    """.trimIndent()
}

/**
 * Same idea as [dailymotionSdkHtml], for the other provider confirmed to
 * need it: navigating straight to youtube.com/embed/ID (what this loaded
 * before, same as knownEmbedUrl still hands out for the fallback-offer
 * dialog's own link) gets YouTube's player to show "Error 153" instead of
 * playing, inside this WebView specifically. This instead loads the real
 * YouTube IFrame Player API (youtube.com/iframe_api) and builds a YT.Player
 * against an element on this page — the same shape of embed CyTube's own
 * client uses, and a real https origin (see the loadDataWithBaseURL call
 * site) rather than null/about:blank, which is the specific part of this
 * that actually avoids the 153.
 */
private fun youtubeIframeApiHtml(id: String): String {
    // See dailymotionSdkHtml's own comment: the real boundary is the
    // SAFE_EMBED_ID_REGEX gate, not the quote/backslash escaping below,
    // which alone can't stop a "</script>"-shaped id from breaking out of
    // this page's own <script> block.
    if (!SAFE_EMBED_ID_REGEX.matches(id)) return BLANK_EMBED_HTML
    val safeId = id.replace("\\", "\\\\").replace("'", "\\'")
    return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
        <!-- The 0-height fix below cleared the black screen's own cause
             (confirmed: iframeRect/bodySize went from ...x0 to real
             non-zero values), but onError UNKNOWN(152) still fires right
             after playVideo() and state never leaves UNSTARTED — a
             DIFFERENT, separate problem: YouTube error 152-4 ("Video not
             available") is documented as a Referer-header verification
             failure, specifically reported for WebView content loaded from
             a local source (file:///, data:, or — this app's case —
             loadDataWithBaseURL) rather than a genuine network navigation:
             the browser doesn't reliably attach a Referer to subresource
             requests a locally-loaded page makes, even with a baseUrl set,
             and YouTube's embed endpoint rejects the request when it can't
             read one. Forcing a referrer policy explicitly, rather than
             relying on WebView's default behavior for local content, is
             the documented fix. -->
        <meta name="referrer" content="strict-origin">
        <style>
          /* Root cause of the black screen, confirmed live: DIAG poll logged
             iframeRect=<realwidth>x0 and bodySize=<realwidth>x0 — width
             resolved correctly but the html -> body -> #ytplayer
             height:100% cascade collapsed to 0 in this WebView (a
             documented WebView quirk: percentage height depends on the
             ancestor chain resolving to a real size, which can silently
             fail here even when width resolves fine). A YT.Player built
             against a genuinely 0-height container is invisible even while
             "playing". This was NOT what was also causing onError
             UNKNOWN(152) — see the referrer meta tag above for that,
             a separate cause. position:fixed with all four insets anchors
             directly to the WebView's own layout viewport instead of
             depending on that percentage chain, which is the standard
             fix. */
          html, body { margin: 0; padding: 0; background: #000; overflow: hidden; }
          html, body, #ytplayer { position: fixed; top: 0; right: 0; bottom: 0; left: 0; }
        </style>
        </head>
        <body>
        <div id="ytplayer"></div>
        <script>
          var tag = document.createElement('script');
          tag.src = 'https://www.youtube.com/iframe_api';
          document.getElementsByTagName('script')[0].parentNode.insertBefore(tag, document.getElementsByTagName('script')[0]);

          // "Error 153" is gone (confirmed: www-widgetapi.js loads and runs
          // now), but the screen went black instead — and the ORIGINAL
          // top-level DIAG poller (document.querySelectorAll('video'), still
          // running via onPageFinished in EmbedSurface) is blind to this:
          // the API's real <video> lives inside a cross-origin child iframe
          // this page's own JS can't reach (same reasoning as
          // EmbedPlayerController's window.__cytubeEmbed comment), so it
          // will report videos=0 regardless of whether the nested player is
          // actually working. This logs the PLAYER OBJECT's own view of
          // reality instead — state transitions, IFrame API error codes (the
          // real 2/5/100/101/150 codes this API defines, distinct from the
          // "Error 153" that only showed up when loading the bare embed URL
          // directly), current time, and the iframe element's own rendered
          // size — all piped through console.log to the same onConsoleMessage
          // hook already wired to Logcat (tag CyTubePlayer). A state that
          // reaches PLAYING (1) with currentTime advancing and a non-zero
          // iframe rect would mean this is purely a compositing bug specific
          // to a video inside a nested cross-origin iframe reached this way;
          // a state stuck at UNSTARTED/CUED/BUFFERING, or an onError firing,
          // points back at the player itself never actually starting.
          function stateName(code) {
            switch (code) {
              case -1: return 'UNSTARTED';
              case 0: return 'ENDED';
              case 1: return 'PLAYING';
              case 2: return 'PAUSED';
              case 3: return 'BUFFERING';
              case 5: return 'CUED';
              default: return 'UNKNOWN(' + code + ')';
            }
          }
          function errorName(code) {
            switch (code) {
              case 2: return 'INVALID_PARAM';
              case 5: return 'HTML5_ERROR';
              case 100: return 'NOT_FOUND';
              case 101: return 'NOT_EMBEDDABLE';
              case 150: return 'NOT_EMBEDDABLE';
              default: return 'UNKNOWN(' + code + ')';
            }
          }

          // onError 152 is GONE now (confirmed live, after switching this
          // page's own origin away from youtube.com itself — see the
          // loadDataWithBaseURL call site's comment) — progress, but state
          // is now stuck at BUFFERING indefinitely instead, t never leaving
          // 0.0, with no error at all. That's a different shape of problem:
          // not a rejection, a stall. YouTube's IFrame Player is documented
          // to observe the Page Visibility API and throttle/defer actual
          // loading while it believes its host document isn't visible or
          // focused — a real risk for a WebView hosted inside a Compose
          // AndroidView, which this app has no independent confirmation
          // reports itself as visible/focused the same way a normal
          // Activity-owned WebView would. Logging these directly, plus a
          // catch-all for any JS error/promise rejection the widget's own
          // internals might be swallowing silently, rather than guessing
          // this is the cause without checking.
          window.addEventListener('error', function(e) {
            console.log('DIAG window error: ' + (e.message || e) + ' @ ' + (e.filename || '?') + ':' + (e.lineno || '?'));
          });
          window.addEventListener('unhandledrejection', function(e) {
            console.log('DIAG unhandledrejection: ' + (e.reason && e.reason.message ? e.reason.message : e.reason));
          });
          document.addEventListener('visibilitychange', function() {
            console.log('DIAG visibilitychange -> ' + document.visibilityState);
          });

          var player = null;
          window.onYouTubeIframeAPIReady = function() {
            console.log('DIAG onYouTubeIframeAPIReady fired, creating player for $safeId' +
              ' visibilityState=' + document.visibilityState +
              ' hidden=' + document.hidden +
              ' hasFocus=' + document.hasFocus() +
              ' onLine=' + navigator.onLine);
            // No error, state just never left BUFFERING and currentTime
            // never left 0.0 — confirmed live, for minutes at a time, after
            // the previous (origin) fix cleared error 152 entirely. That
            // silent-stall shape, specifically for a PROGRAMMATIC
            // playVideo() call with no real user tap behind it, is a
            // documented browser autoplay-policy behavior: unmuted
            // autoplay is broadly blocked (Chrome/Chromium policy, which
            // WebView's engine shares), and the IFrame API doesn't surface
            // that block as onError — it just never progresses, which is
            // exactly this. mediaPlaybackRequiresUserGesture=false on this
            // WebView's own settings governs content THIS page directly
            // controls; it does not reach into policy decisions the
            // separate, cross-origin YouTube iframe's own engine makes for
            // itself. Starting muted is the standard, documented way
            // around this — then unmuting the instant onStateChange
            // actually confirms PLAYING, rather than staying muted forever.
            var unmuted = false;
            player = new YT.Player('ytplayer', {
              videoId: '$safeId',
              width: '100%',
              height: '100%',
              playerVars: {
                autoplay: 1,
                mute: 1,
                playsinline: 1,
                modestbranding: 1,
                rel: 0
              },
              events: {
                onReady: function(e) {
                  console.log('DIAG onReady, calling mute()+playVideo()');
                  e.target.mute();
                  e.target.playVideo();
                },
                onStateChange: function(e) {
                  console.log('DIAG onStateChange ' + stateName(e.data));
                  if (e.data === 1 && !unmuted) {
                    unmuted = true;
                    console.log('DIAG state reached PLAYING, calling unMute()');
                    e.target.unMute();
                  }
                  // ENDED (0) is the IFrame API's own documented state for
                  // "this video finished" — confirmed present in the same
                  // stateName() mapping already live-tested against real
                  // onStateChange events above (UNSTARTED/PLAYING/etc all
                  // showed up correctly during the error-152 debugging).
                  // See EMBED_ENDED_SENTINEL's own comment for why this is a
                  // plain console.log rather than any richer channel back to
                  // Kotlin.
                  if (e.data === 0) {
                    console.log('$EMBED_ENDED_SENTINEL');
                  }
                },
                onError: function(e) {
                  console.log('DIAG onError ' + errorName(e.data));
                }
              }
            });
            // See EmbedPlayerController's own comment on why this exists —
            // same reason as dm's window.__cytubeEmbed: the real <video> is
            // inside a child iframe this top page's JS can't reach, so the
            // controller goes through the API's own player object instead.
            window.__cytubeEmbed = {
              toggle: function() {
                if (!player || !player.getPlayerState) return;
                if (player.getPlayerState() === 1) { player.pauseVideo(); } else { player.playVideo(); }
              },
              seek: function(delta) {
                if (!player || !player.getCurrentTime) return;
                player.seekTo(Math.max(0, player.getCurrentTime() + delta), true);
              }
            };
            setInterval(function() {
              if (!player || !player.getPlayerState) return;
              var iframe = player.getIframe ? player.getIframe() : null;
              var rect = iframe ? iframe.getBoundingClientRect() : null;
              var t = player.getCurrentTime ? player.getCurrentTime() : -1;
              var loaded = player.getVideoLoadedFraction ? player.getVideoLoadedFraction() : -1;
              console.log(
                'DIAG poll state=' + stateName(player.getPlayerState()) +
                ' t=' + (typeof t === 'number' ? t.toFixed(1) : t) +
                ' loadedFraction=' + loaded +
                ' iframeRect=' + (rect ? (rect.width + 'x' + rect.height) : 'null') +
                ' bodySize=' + document.body.clientWidth + 'x' + document.body.clientHeight +
                ' visibilityState=' + document.visibilityState +
                ' hasFocus=' + document.hasFocus() +
                ' onLine=' + navigator.onLine
              );
            }, 2000);
          };
        </script>
        </body>
        </html>
    """.trimIndent()
}

/**
 * Same idea as [dailymotionSdkHtml]/[youtubeIframeApiHtml], for Vimeo.
 * CyTube's real client (player/vimeo.coffee, calzoneman/sync) builds an
 * `<iframe src="https://player.vimeo.com/video/ID">` as an element on ITS
 * OWN page — never navigates a page straight to that URL, which is what
 * knownEmbedUrl builds and every other provider without a dedicated
 * function here just loadUrl()s — then loads Vimeo's own Player SDK
 * (player.vimeo.com/api/player.js) and wraps that iframe with
 * `new Vimeo.Player(iframeEl)` for control and events. Built preemptively
 * from that real source rather than waiting to hit the same class of
 * failure live and diagnosing it from scratch the way yt/dm needed.
 */
private fun vimeoSdkHtml(id: String): String {
    if (!SAFE_EMBED_ID_REGEX.matches(id)) return BLANK_EMBED_HTML
    val safeId = id.replace("\\", "\\\\").replace("'", "\\'")
    return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
        <!-- Same reasoning as dailymotionSdkHtml/youtubeIframeApiHtml's own
             referrer tags: WebView doesn't reliably attach a Referer to
             requests a loadDataWithBaseURL page makes, and Vimeo's player
             does its own origin/referer-based checks before serving a
             stream — a harmless-if-unneeded precaution against hitting the
             identical failure mode a third time. -->
        <meta name="referrer" content="strict-origin">
        <style>
          /* Diagnosed with real measurements (not guessed): #vimeowrap fills
             the viewport correctly via position:fixed/inset-0, but the
             iframe itself was rendering at its untouched browser-default box
             regardless of the external #vimeoplayer{...} stylesheet rule —
             that rule was never actually being applied to it. Inline style=""
             directly on the <iframe> (below) is the highest-precedence fix
             and matches every real Vimeo-published responsive embed's own
             convention. Still doesn't fill the frame correctly on-device as
             of 1.6.9.0 — tracked as a known limitation (see README) rather
             than guessed at further; left in place since it's harmless and
             is the closest match to Vimeo's own reference embed code. -->
          html, body { margin: 0; padding: 0; background: #000; overflow: hidden; }
          html, body, #vimeowrap { position: fixed; top: 0; right: 0; bottom: 0; left: 0; }
        </style>
        </head>
        <body>
        <div id="vimeowrap">
        <iframe id="vimeoplayer" src="https://player.vimeo.com/video/$safeId" allow="autoplay; fullscreen" allowfullscreen style="display:block;width:100%;height:100%;border:0;margin:0;padding:0;"></iframe>
        </div>
        <script src="https://player.vimeo.com/api/player.js"></script>
        <script>
          var player = new Vimeo.Player(document.getElementById('vimeoplayer'));
          var lastKnownPaused;
          // See EmbedPlayerController's own comment on why this exists —
          // Vimeo's real <video> is inside that iframe's own document,
          // which this page's JS can't reach directly (cross-origin, same
          // reasoning as the yt/dm cases), so the controller goes through
          // the SDK's own player object instead. play()/pause()/
          // setCurrentTime() all return Promises on this SDK — the .catch
          // handlers below match CyTube's own real client exactly (see
          // vimeo.coffee), which swallows/logs rejections rather than
          // letting them go unhandled.
          window.__cytubeEmbed = {
            toggle: function() {
              if (!player) return;
              if (lastKnownPaused) {
                player.play().catch(function(e) { console.log('vimeo play() rejected: ' + (e && e.message ? e.message : e)); });
              } else {
                player.pause().catch(function(e) { console.log('vimeo pause() rejected: ' + (e && e.message ? e.message : e)); });
              }
            },
            seek: function(delta) {
              if (!player) return;
              player.getCurrentTime().then(function(t) {
                player.setCurrentTime(Math.max(0, t + delta)).catch(function() {});
              }).catch(function() {});
            }
          };
          player.on('pause', function() { lastKnownPaused = true; });
          player.on('play', function() { lastKnownPaused = false; });
          // CyTube's own client only advances to the next item on 'ended'
          // (see vimeo.coffee's own play-next-on-CLIENT.leader call) —
          // matching that exactly rather than guessing at other event names.
          player.on('ended', function() { console.log('$EMBED_ENDED_SENTINEL'); });
          // CyTube's own client calls play() itself right after
          // construction rather than relying on an autoplay query param —
          // matched here exactly rather than assumed to be equivalent.
          player.play().catch(function(e) { console.log('vimeo play() rejected: ' + (e && e.message ? e.message : e)); });
        </script>
        </body>
        </html>
    """.trimIndent()
}

/**
 * True when [navigatingHost] is the same site as [embedHost] — an exact
 * match, or a subdomain of the same registrable domain (last two labels:
 * "geo.dailymotion.com" and "www.dailymotion.com" are the same site;
 * "dailymotion.com.evil.example" is not, since its last two labels are
 * "evil.example"). Deliberately simple rather than a full public-suffix-list
 * lookup: every provider knownEmbedUrl/embedSrc actually hands this a plain
 * .com/.jp/.tv-style host, never a multi-part TLD like .co.uk, so "last two
 * labels" is exact for the domains this app actually deals with rather than
 * an approximation carrying edge cases nothing here will ever hit.
 */
private fun sameSite(navigatingHost: String?, embedHost: String?): Boolean {
    if (navigatingHost == null || embedHost == null) return false
    if (navigatingHost.equals(embedHost, ignoreCase = true)) return true
    fun registrableDomain(host: String): String {
        val labels = host.split(".")
        return if (labels.size >= 2) labels.takeLast(2).joinToString(".") else host
    }
    return registrableDomain(navigatingHost).equals(registrableDomain(embedHost), ignoreCase = true)
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
        ExoPlayer.Builder(context)
            .setLoadControl(
                // Steady-state target (min) and startup latency
                // (bufferForPlayback) are left at Media3's own defaults —
                // this only raises the ceiling ExoPlayer will build toward
                // under good network (more runway to absorb a large file's
                // bandwidth dips before it ever has to stall) and how much
                // it demands before resuming after an actual stall, so one
                // stall doesn't immediately repeat. Doesn't change anything
                // for a short/small file — it finishes buffering long
                // before either number is reached either way.
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                        LOAD_CONTROL_MAX_BUFFER_MS,
                        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                        LOAD_CONTROL_BUFFER_AFTER_REBUFFER_MS
                    )
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
        // Scoped to this exo/listener's own lifetime (a fresh pair per
        // epoch — see the LaunchedEffect above), so these reset naturally
        // for every new item AND for every quality-adaptation reload, never
        // needing an explicit reset of their own.
        var reachedReadyOnce = false
        var stallStartedAtMs = 0L
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
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onEnded?.invoke()
                when (playbackState) {
                    Player.STATE_READY -> {
                        if (!reachedReadyOnce) {
                            reachedReadyOnce = true
                        } else if (stallStartedAtMs != 0L) {
                            val stalledMs = android.os.SystemClock.elapsedRealtime() - stallStartedAtMs
                            stallStartedAtMs = 0L
                            onStall?.invoke(stalledMs)
                        }
                    }
                    Player.STATE_BUFFERING -> {
                        if (reachedReadyOnce && stallStartedAtMs == 0L) {
                            stallStartedAtMs = android.os.SystemClock.elapsedRealtime()
                        }
                    }
                }
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
