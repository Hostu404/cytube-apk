package com.cytube.mobile.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * CyTube's "ip-session" cookie: the visitor's IP address and when the server
 * first saw them, signed by the server (src/web/middleware/ipsessioncookie.js).
 * It hands one out on every channel page (/r/<channel>) that's asked for
 * without a good one, and its Socket.IO server reads it on connecting
 * (ioserver.js ipSessionCookieMiddleware) to tell how new a visitor is: a
 * channel can keep visitors newer than so many minutes from chatting, or from
 * posting links (chat.js restrictNewAccount; for an account, its age counts
 * too).
 *
 * The app never had one, so the server took every connection for a brand-new
 * visitor: on a channel with such a wait, a guest was held back again after
 * every reconnect, refresh or channel change. Now the app keeps the cookie
 * between runs, as a browser does, and shows it when connecting. It's tied to
 * the IP address, so before each connection the channel page is asked for
 * (HEAD: nothing is downloaded) with the cookie the app has: the server gives
 * a new one only when that's missing or no longer matches the address (a
 * phone moving from Wi-Fi to mobile data, say).
 */
interface IpSessionStore {
    fun load(): String?
    fun save(value: String)
}

/** Kept for as long as the app runs; the app's own one also keeps it
 *  between runs (see Graph.newClient). */
class InMemoryIpSessionStore : IpSessionStore {
    @Volatile private var value: String? = null
    override fun load(): String? = value
    override fun save(value: String) { this.value = value }
}

/** The cookie's name and form, apart for testing. */
internal object IpSessionCookie {
    const val NAME = "ip-session"

    /** The ip-session value among a response's Set-Cookie headers, as sent
     *  (still URL-encoded: it's sent back exactly so), or null for none. */
    fun fromSetCookie(headers: List<String>): String? = headers.firstNotNullOfOrNull { header ->
        val pair = header.substringBefore(';')
        val eq = pair.indexOf('=')
        if (eq <= 0 || pair.substring(0, eq).trim() != NAME) null
        else pair.substring(eq + 1).trim().takeIf { it.isNotEmpty() }
    }

    /** The Cookie header for the Socket.IO handshake: the login cookie, the
     *  ip-session cookie, both, or (null) neither. */
    fun forHandshake(authCookie: String?, ipSession: String?): String? =
        listOfNotNull(authCookie?.let { "auth=$it" }, ipSession?.let { "$NAME=$it" })
            .joinToString("; ")
            .ifEmpty { null }
}

/**
 * The ip-session cookie to connect to [channel] with: the one in [store],
 * checked against the server and replaced when it hands out a new one (see
 * the doc at the top). Never throws: offline, refused or slow, the one there
 * is (or none) is used, and the connection goes ahead as it always did.
 */
internal class IpSessionCookies(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val store: IpSessionStore,
    private val timeoutMs: Long = TIMEOUT_MS
) {
    suspend fun forChannel(channel: String): String? = withContext(Dispatchers.IO) {
        val kept = runCatching { store.load() }.getOrNull()
        val fresh = runCatching {
            val request = Request.Builder()
                .url("$baseUrl/r/$channel")
                .head()
                .apply { kept?.let { header("Cookie", "${IpSessionCookie.NAME}=$it") } }
                .build()
            http.newCall(request)
                .apply { timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS) }
                .execute()
                .use { IpSessionCookie.fromSetCookie(it.headers("Set-Cookie")) }
        }.getOrNull()
        if (fresh != null && fresh != kept) runCatching { store.save(fresh) }
        fresh ?: kept
    }

    companion object {
        /** The most the channel-page request may take, all told. Joining
         *  waits for it (alongside the server address), so a slow answer
         *  mustn't hold that up: without one, the cookie kept is used. */
        const val TIMEOUT_MS = 5_000L
    }
}
