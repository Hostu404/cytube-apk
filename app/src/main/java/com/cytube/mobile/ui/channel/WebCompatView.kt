package com.cytube.mobile.ui.channel

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Compatibility View: the real CyTube channel page, whole.
 *
 * This is back to being what it originally was. Trying to make it a
 * player-only surface was the wrong idea — it broke both jobs at once. Its
 * purpose is compatibility for anything the native path genuinely cannot do,
 * and for that it needs the actual page: the channel's own layout, its
 * scripts, its chat, its player.
 *
 * Two rules keep it safe and non-conflicting:
 *
 *  1. NO JavaScript bridge. addJavascriptInterface is never called here, so
 *     whatever the page runs gets no reach into the app.
 *
 *  2. The native socket is disconnected before this mounts (see
 *     ChannelViewModel.setMode). CyTube kicks the older session with
 *     "Duplicate login" when one account joins a channel twice, so running both
 *     at once would make the app fight itself.
 *
 * The session cookie is shared in so the user does not log in twice.
 */
/**
 * No backgrounded-pause hook here, on purpose — same reasoning as
 * PlayerSurface's EmbedSurface. This is the whole CyTube page, not just a
 * player surface, so WebView.onPause()/onResume() looked like an even bigger
 * win than for a single embed: the page's own player, chat polling, any
 * animation, all stop too. It also means the page's own player goes fully
 * silent the instant the app is backgrounded — audio included, since a
 * WebView has no "keep audio, drop video" switch — unlike the native path,
 * which keeps playing audio via ExoSurface's track selection. Tried and
 * reverted for that reason: correct playback wins over a battery saving this
 * surface can't deliver without an audible side effect.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebCompatView(
    baseUrl: String,
    channel: String,
    authCookie: String?,
    modifier: Modifier = Modifier
) {
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    val context = LocalContext.current
    val homeHost = remember(baseUrl) { Uri.parse(baseUrl).host }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                // Secure: baseUrl is always https (network_security_config
                // blocks cleartext app-wide anyway, but this keeps the
                // cookie itself from ever being eligible to leave over a
                // plaintext connection, belt and suspenders). HttpOnly: this
                // cookie only needs to be sent back to the server on
                // requests — CyTube's own page JS has no legitimate reason
                // to read it — so keeping it out of `document.cookie`
                // narrows what a bug in the real CyTube page (out of this
                // app's control) or a same-process third-party embed could
                // get at. SameSite=Lax: normal top-level navigation within
                // this WebView still sends it; it's just not attached to
                // cross-site requests a page here didn't initiate itself.
                authCookie?.let { setCookie(baseUrl, "auth=$it; Path=/; Secure; HttpOnly; SameSite=Lax") }
            }

            WebView(ctx).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    // The desktop page in a phone-width viewport is unreadable
                    // without these; they are what makes it usable at all.
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    builtInZoomControls = true
                    displayZoomControls = false
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                // The page's own fullscreen buttons (CyTube's player controls,
                // or a channel's custom embed) are deliberately left doing
                // nothing here — no WebChromeClient.onShowCustomView override
                // means HTML5 fullscreen requests are just ignored, so the
                // video stays inline instead of taking over the whole app.
                // This used to be wired up so tapping fullscreen on the page
                // would hand the app a full-bleed view of it, but that's been
                // pulled back out.
                //
                // Keep navigation inside CyTube; anything else is a chat link
                // and belongs in the user's browser. shouldOverrideUrlLoading
                // is what actually enforces that — without it, the default
                // WebViewClient loads every link in place, including a link to
                // a completely different site, right inside this WebView.
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        val url = request.url
                        // Only a top-level navigation away from the channel's
                        // own host is a "chat link, open it externally" case.
                        // A subframe navigating to a different host is exactly
                        // what a custom video embed needs to do (e.g.
                        // the-kinoplex's 8chan.tv streams) — intercepting
                        // those meant CyTube's page would try to load the
                        // embed iframe, get yanked out to the external
                        // browser instead, and the video never rendered.
                        if (!request.isForMainFrame) return false
                        if (url.host == homeHost) return false
                        openInBrowser(context, url.toString())
                        return true
                    }
                }

                webViewRef.value = this
                loadUrl("$baseUrl/r/$channel")
            }
        }
    )

    DisposableEffect(Unit) {
        onDispose {
            webViewRef.value?.apply {
                stopLoading()
                loadUrl("about:blank")
                removeAllViews()
                destroy()
            }
            webViewRef.value = null
        }
    }
}
