package com.cytube.mobile.ui.channel

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
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
                authCookie?.let { setCookie(baseUrl, "auth=$it; Path=/") }
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

                // Needed for the page's own fullscreen buttons to do anything.
                webChromeClient = object : WebChromeClient() {
                    override fun onShowCustomView(
                        view: android.view.View?, cb: CustomViewCallback?
                    ) = onWebFullscreen(true, view, cb)

                    override fun onHideCustomView() = onWebFullscreen(false, null, null)
                }
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
