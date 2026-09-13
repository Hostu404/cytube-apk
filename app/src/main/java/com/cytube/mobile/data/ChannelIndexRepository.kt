package com.cytube.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * Public channel list.
 *
 * CyTube has no JSON API for this. The full HTTP route inventory is /, /login,
 * /logout, /register, /account/..., /acp/..., /socketconfig/..., and the channel
 * page — nothing machine-readable. The homepage is server-rendered Pug
 * (templates/index.pug) with one table row per public channel:
 *
 *   <td><a href="/r/<name>">PageTitle (name)</a></td>
 *   <td>usercount</td>
 *   <td>now playing</td>
 *
 * So we parse that table. It is a scrape and it is fragile by nature, which is
 * why it degrades rather than fails: if parsing yields nothing the UI keeps
 * working on favourites, recents and direct entry by name — which is exactly
 * what the CyTube homepage itself offers alongside the list.
 */
class ChannelIndexRepository(
    private val http: OkHttpClient,
    private val baseUrl: String
) {
    data class PublicChannel(
        val name: String,
        val pageTitle: String,
        val userCount: Int,
        val nowPlaying: String
    )

    private var cache: List<PublicChannel> = emptyList()
    val cachedChannels: List<PublicChannel> get() = cache
    private var cachedAt = 0L
    private var lastEtag: String? = null
    private var lastModified: String? = null

    suspend fun publicChannels(forceRefresh: Boolean = false): List<PublicChannel> =
        withContext(Dispatchers.IO) {
            val fresh = System.currentTimeMillis() - cachedAt < CACHE_MS
            if (!forceRefresh && fresh && cache.isNotEmpty()) return@withContext cache

            val reqBuilder = Request.Builder().url("$baseUrl/").get()
            if (!forceRefresh) {
                lastEtag?.let { reqBuilder.header("If-None-Match", it) }
                lastModified?.let { reqBuilder.header("If-Modified-Since", it) }
            }

            try {
                http.newCall(reqBuilder.build()).execute().use { response ->
                    if (response.code == 304 && cache.isNotEmpty()) {
                        cachedAt = System.currentTimeMillis()
                        return@withContext cache
                    }

                    if (!response.isSuccessful) return@withContext cache

                    val body = response.body ?: return@withContext cache
                    val doc = body.byteStream().use { stream ->
                        Jsoup.parse(stream, "UTF-8", baseUrl)
                    }

                    val rows = doc.select("table tbody tr")
                    val parsed = rows.mapNotNull { row ->
                        val cells = row.select("td")
                        if (cells.size < 2) return@mapNotNull null
                        val link = cells[0].selectFirst("a") ?: return@mapNotNull null

                        // href is /<channelPath>/<name>; channelPath is configurable
                        // (defaults to "r"), so take the last segment rather than
                        // assuming the prefix.
                        val name = link.attr("href").trimEnd('/').substringAfterLast('/')
                        if (name.isBlank() || !CHANNEL_NAME_REGEX.matches(name)) return@mapNotNull null

                        // Link text is "PageTitle (name)"; strip the trailing "(name)".
                        val raw = link.text().trim()
                        val title = raw.removeSuffix("($name)").trim().ifBlank { name }

                        PublicChannel(
                            name = name,
                            pageTitle = title,
                            userCount = cells[1].text().trim().toIntOrNull() ?: 0,
                            nowPlaying = cells.getOrNull(2)?.text()?.trim().orEmpty()
                        )
                    }

                    if (parsed.isNotEmpty()) {
                        cache = parsed.sortedByDescending { it.userCount }
                        cachedAt = System.currentTimeMillis()
                        lastEtag = response.header("ETag")
                        lastModified = response.header("Last-Modified")
                    }
                }
            } catch (_: Exception) {
                // Return whatever stale cache we have on network failure
            }

            cache
        }

    private companion object { const val CACHE_MS = 60_000L }
}
