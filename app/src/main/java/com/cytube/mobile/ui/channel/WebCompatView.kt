package com.cytube.mobile.ui.channel

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
/**
 * Compatibility View: the real CyTube channel page, whole.
 *
 * This is back to being what it originally was. Its purpose is compatibility
 * for anything the native path genuinely cannot do, and for that it needs the
 * actual page: the channel's own layout, its scripts, its chat, its player.
 */
@SuppressLint("SetJavaScriptEnabled", "MissingOnRenderProcessGone") // both its WebViewClients do handle
// onRenderProcessGone, which lint doesn't see in an object expression
@Composable
fun WebCompatView(
    baseUrl: String,
    channel: String,
    authCookie: String?,
    /** A change loads the page afresh (tapping the channel name, which
     *  outside this view rejoins the channel). */
    reloadKey: Int = 0,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val homeHost = remember(baseUrl) { baseUrl.toUri().host }
    var isLoaded by remember(baseUrl, channel, reloadKey) { mutableStateOf(false) }
    // Bumped when the WebView's renderer process dies, so key() below
    // builds a fresh WebView and reloads the page — before, the dead one
    // was just removed and nothing replaced it, leaving a black area until
    // the user switched mode or channel.
    var rendererGeneration by remember { mutableIntStateOf(0) }
    val alpha by animateFloatAsState(
        targetValue = if (isLoaded) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "webCompatAlpha"
    )

    key(baseUrl, channel, rendererGeneration, reloadKey) {
        AndroidView(
            modifier = modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = alpha },
            factory = { ctx ->
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    authCookie?.let { setCookie(baseUrl, "auth=$it; Path=/; Secure; HttpOnly; SameSite=Lax") }
                }

                WebView(ctx).apply {
                    setBackgroundColor(Color.BLACK)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
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

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean {
                            val url = request.url
                            if (!request.isForMainFrame) return false
                            if (url.host == homeHost) return false
                            // Only for a tap (a link in the page's chat, say),
                            // as the embedded players do: the channel's own
                            // script runs in this page, and a navigation it
                            // made by itself threw the user out to the
                            // browser, again and again if it kept at it (on
                            // TV, closing the app). Either way the page stays.
                            if (request.hasGesture()) openInBrowser(context, url.toString())
                            return true
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            isLoaded = true
                        }

                        // Handled (true) so the app doesn't crash with it. The
                        // dead WebView comes off screen now and is destroyed
                        // by onRelease below once a new one replaces it — up
                        // to MAX_RENDERER_RESTARTS times, so a page that
                        // keeps crashing its renderer can't loop forever.
                        override fun onRenderProcessGone(
                            view: WebView?,
                            detail: RenderProcessGoneDetail?
                        ): Boolean {
                            view?.let { (it.parent as? ViewGroup)?.removeView(it) }
                            isLoaded = false
                            if (rendererGeneration < MAX_RENDERER_RESTARTS) rendererGeneration++
                            return true
                        }
                    }

                    loadUrl("$baseUrl/r/$channel")
                }
            },
            onRelease = { webView ->
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.stopLoading()
                webView.webViewClient = object : WebViewClient() {
                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: RenderProcessGoneDetail?
                    ): Boolean = true
                }
                webView.webChromeClient = null
                webView.removeAllViews()
                webView.destroy()
            }
        )
    }
}

private const val MAX_RENDERER_RESTARTS = 3
