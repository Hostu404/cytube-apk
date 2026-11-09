package com.cytube.mobile.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Where a short link from a share sheet (t.co, bit.ly, on.soundcloud.com…)
 * really goes, found by following its redirects one at a time (see
 * MediaLink.shouldFollowRedirects for when that's done).
 *
 * One at a time rather than letting the HTTP client follow them: the app
 * allows no plain http at all (network_security_config), and many short
 * links redirect to an http:// address — t.co to an http:// YouTube link,
 * say — which the client then refused, so the whole lookup failed and the
 * short link itself was added, for the server to reject. Here each hop is
 * asked for over https instead, as browsers do; and since only the address
 * matters, not the page, following stops as soon as one is a link MediaLink
 * recognises (that YouTube link is never fetched at all), at an address
 * that isn't a web page (intent:, market:…), or when a hop can't be had —
 * the last address known is the answer then.
 */
object ShortLinks {
    const val MAX_HOPS = 8
    /** Following them all takes no longer than this, each request getting
     *  what's left of it: as long as the one request that followed them
     *  all before was allowed. */
    const val BUDGET_MS = 8_000L

    /** A HEAD request's answer: its status and Location header. */
    data class Answer(val code: Int, val location: String?)

    /** Makes one HEAD request to [url] (always https), without following a
     *  redirect, taking no longer than [timeoutMs] all told; null if it
     *  couldn't be made (offline, timeout...). */
    fun interface Head {
        fun request(url: HttpUrl, timeoutMs: Long): Answer?
    }

    /**
     * Where [start] ends up, or null when not even the first request could
     * be made (the caller then uses the link as it is). [nanoTime] is only
     * there for tests.
     */
    fun follow(
        start: String,
        head: Head,
        maxHops: Int = MAX_HOPS,
        budgetMs: Long = BUDGET_MS,
        nanoTime: () -> Long = System::nanoTime
    ): String? {
        var current = start.toHttpUrlOrNull() ?: return null
        val deadline = nanoTime() + budgetMs * 1_000_000L
        var followed = false
        for (hop in 0 until maxHops) {
            val leftMs = (deadline - nanoTime()) / 1_000_000L
            if (followed && (!needsFollowing(current) || leftMs <= 0)) break
            val asked = current.overHttps()
            val answer = head.request(asked, leftMs.coerceAtLeast(1L)) ?: return if (followed) current.toString() else null
            val location = answer.location?.trim()?.takeIf { answer.code in 300..399 && it.isNotEmpty() } ?: break
            // Relative to where it was asked; null for anything but a web
            // address, which isn't followed.
            current = asked.resolve(location) ?: break
            followed = true
        }
        return current.toString()
    }

    /** Whether [url] is still just an address, nothing MediaLink can add. */
    private fun needsFollowing(url: HttpUrl): Boolean =
        MediaLink.parse(url.toString()).getOrNull()?.let(MediaLink::shouldFollowRedirects) ?: true

    /** The same address over https (port 80 becoming 443). */
    internal fun HttpUrl.overHttps(): HttpUrl =
        if (isHttps) this else newBuilder().scheme("https").port(if (port == 80) 443 else port).build()
}
