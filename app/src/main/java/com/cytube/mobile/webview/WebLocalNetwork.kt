package com.cytube.mobile.webview

import android.os.SystemClock
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.cytube.mobile.net.LocalNetwork
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * The WebViews' side of LocalNetwork: which of a page's requests go to the
 * user's own network, for WebViewClient.shouldInterceptRequest to refuse
 * (embedded players and Compatibility View). See
 * LocalNetwork.blocksWebRequest for what's refused, and what this can't see.
 */
internal object WebLocalNetwork {
    /** How long a host's answer is kept, so a page's many requests to one
     *  host look its name up once. */
    private const val REMEMBER_MS = 60_000L
    private const val MAX_REMEMBERED = 256

    private class Decision(val refuses: Boolean, val atMs: Long)

    private val decided = ConcurrentHashMap<String, Decision>()

    /** Whether [request] goes to the local network. Called on the WebView's
     *  own threads, never the main one, so looking a name up here is fine. */
    fun refuses(request: WebResourceRequest): Boolean {
        val url = request.url ?: return false
        val host = url.host?.lowercase() ?: return false
        val now = SystemClock.elapsedRealtime()
        decided[host]?.let { if (now - it.atMs < REMEMBER_MS) return it.refuses }
        val refuses = LocalNetwork.blocksWebRequest(url.toString()) { InetAddress.getAllByName(it).toList() }
        if (decided.size >= MAX_REMEMBERED) decided.clear()
        decided[host] = Decision(refuses, now)
        return refuses
    }

    /** What a refused request gets instead: an empty 403. */
    fun refusal(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
}
