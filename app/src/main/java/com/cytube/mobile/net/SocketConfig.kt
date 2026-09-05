package com.cytube.mobile.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * CyTube is clustered: the Socket.IO host is frequently NOT the web host and it
 * varies per channel. www/js/callbacks.js fetches /socketconfig/<channel>.json
 * before connecting and picks a server from the returned list.
 *
 * Skipping this is the most common way third-party clients break, so it is a
 * hard requirement, not an optimisation.
 */
class SocketConfigResolver(
    private val http: OkHttpClient,
    private val baseUrl: String
) {
    class ChannelNotFound(name: String) : Exception("Channel \"$name\" does not exist.")

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
            // then prefer a server that is not IPv6-only.
            var chosen: JSONObject? = null
            for (i in 0 until servers.length()) {
                val s = servers.optJSONObject(i) ?: continue
                val c = chosen
                chosen = when {
                    c == null -> s
                    s.optBoolean("secure") && !c.optBoolean("secure") -> s
                    !s.optBoolean("ipv6Only") && c.optBoolean("ipv6Only") -> s
                    else -> c
                }
            }

            chosen?.optString("url")?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("No suitable Socket.IO server for $channel")
        }
    }
}
