package com.cytube.mobile.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI

/**
 * CyTube is clustered: the Socket.IO host is frequently NOT the web host and it
 * varies per channel. www/js/callbacks.js fetches /socketconfig/<channel>.json
 * before connecting and picks a server from the returned list.
 *
 * Skipping this is the most common way third-party clients break, so it is a
 * hard requirement, not an optimisation.
 *
 * socketconfig is server/channel-controlled data, not a trusted constant — a
 * compromised or malicious CyTube instance can put anything at all in a
 * server entry's "url" field. That URL is used verbatim to open the actual
 * Socket.IO connection AND (for a cookie-authenticated session — see
 * CyTubeClient.connect) to decide who receives the user's auth cookie as an
 * HTTP header. Without validation here, a malicious response could downgrade
 * the connection to plaintext ws:// and/or exfiltrate the session cookie to
 * an attacker-controlled host by simply naming it in the response. Every
 * candidate is validated before it is even eligible to be chosen — scheme
 * must be https/wss (no cleartext downgrade) and the host must be the base
 * host itself or a subdomain of it (no handing the cookie to an unrelated
 * domain), matching how CyTube's own cluster actually names its socket
 * hosts (subdomains of the same instance).
 */
class SocketConfigResolver(
    private val http: OkHttpClient,
    private val baseUrl: String
) {
    class ChannelNotFound(name: String) : Exception("Channel \"$name\" does not exist.")

    /** Host of [baseUrl] ("cytu.be" for the default instance), used to decide
     *  which resolved socket hosts are actually allowed to receive the auth
     *  cookie / be connected to at all. */
    private val trustedHost: String? = baseUrl.toHttpUrlOrNull()?.host?.lowercase()

    /** True iff [url] is an https/wss URL whose host is the trusted base
     *  host or a subdomain of it. Anything else — a plaintext scheme, an
     *  unrelated domain, a malformed value — is rejected outright rather
     *  than "deprioritized", so a malicious entry can never win selection
     *  just because it's the only one present.
     *
     *  Deliberately parsed with java.net.URI, not OkHttp's HttpUrl: HttpUrl
     *  only understands http/https and returns null for a wss:// value,
     *  which would make every legitimate secure websocket entry fail
     *  validation. IO.socket() (the actual connect call in CyTubeClient)
     *  accepts both an http(s) URL and a ws(s) one, so both have to be
     *  recognised here — just never downgraded to their cleartext form. */
    private fun isTrustedServerUrl(url: String): Boolean {
        val base = trustedHost ?: return false
        val parsed = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = parsed.scheme?.lowercase() ?: return false
        if (scheme != "https" && scheme != "wss") return false
        val host = parsed.host?.lowercase() ?: return false
        return host == base || host.endsWith(".$base")
    }

    suspend fun resolve(channel: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/socketconfig/$channel.json")
            .header("Accept", "application/json")
            .build()

        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 404) throw ChannelNotFound(channel)
            if (!resp.isSuccessful) throw IllegalStateException("socketconfig failed: HTTP ${resp.code}")

            val json = JSONObject(body)
            json.optString("error").takeIf { it.isNotBlank() }?.let { throw IllegalStateException(it) }

            val servers = json.optJSONArray("servers")
                ?: throw IllegalStateException("socketconfig returned no servers")

            // Selection order copied from ioServerConnect(): prefer secure,
            // then prefer a server that is not IPv6-only. Only ever
            // considers entries that already passed isTrustedServerUrl —
            // an untrusted entry is never a candidate, regardless of its
            // secure/ipv6Only flags (those are themselves server-reported
            // and not something to trust for a security decision).
            var chosen: JSONObject? = null
            var chosenUrl: String? = null
            for (i in 0 until servers.length()) {
                val s = servers.optJSONObject(i) ?: continue
                val url = s.optString("url").takeIf { it.isNotBlank() } ?: continue
                if (!isTrustedServerUrl(url)) continue
                val c = chosen
                if (c == null || (s.optBoolean("secure") && !c.optBoolean("secure")) ||
                    (!s.optBoolean("ipv6Only") && c.optBoolean("ipv6Only"))
                ) {
                    chosen = s
                    chosenUrl = url
                }
            }

            chosenUrl ?: throw IllegalStateException("No trusted Socket.IO server for $channel")
        }
    }
}
