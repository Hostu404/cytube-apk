package com.cytube.mobile.net

import java.net.URI
import java.net.URLDecoder

/**
 * Turns a pasted link into the (type, id) pair CyTube's "queue" frame wants.
 *
 * Port of parseMediaLink() from CyTube's www/js/util.js, so a link added
 * from the app becomes exactly the same item it would from the website. The
 * server does its own lookup and validation of whatever it's given, and
 * rejects anything it can't resolve (see CyTubeEvent.QueueFailed), so this
 * only has to get the type and id shape right.
 *
 * Built for links from a phone's share sheet, which are rarely the tidy
 * desktop form: the link is found inside whatever was pasted ("Title
 * https://youtu.be/…?si=…"), a missing https:// is added, and mobile/short
 * hosts (m.youtube.com, dai.ly, m.soundcloud.com…) are recognised. Short
 * links from redirect services (t.co, bit.ly, on.soundcloud.com…) can't be
 * read without following them; see [shouldFollowRedirects].
 *
 * Differences from the original, all where it produces a broken id:
 *  - m.youtube.com / music.youtube.com links, and youtube.com/live/ and
 *    /embed/ paths, are recognised as YouTube. The original treats the first
 *    two as raw files and turns the last two into ids like "live/abc123".
 *  - A host whose specific path doesn't match falls through to the generic
 *    PeerTube / raw-file checks, rather than into the next provider's case
 *    (JavaScript switch fall-through in the original).
 */
object MediaLink {

    data class Parsed(val type: String, val id: String)

    private val SHORTHAND = Regex("^[a-z]{2}:")
    private val PEERTUBE_PATH = Regex(
        "(?:/w/|/videos/watch/)(?:([a-zA-Z0-9]{22})|([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}))"
    )
    private val ODYSEE_PATH = Regex("/@([^:]+)(?::\\w)?/([^:]+)")
    private val LIVESTREAM_PATH = Regex("/accounts/([0-9]+)/events/([0-9]+)")

    private val WHOLE_SHORTHAND = Regex("^[a-z]{2}:\\S+$")
    private val URL_IN_TEXT = Regex("(?:https?|rtmp)://\\S+", RegexOption.IGNORE_CASE)
    /** A bare "youtu.be/abc" or "www.youtube.com/watch?v=…" with no scheme. */
    private val SCHEMELESS_URL = Regex("(?<![\\w@.])(?:[a-z0-9-]+\\.)+[a-z]{2,}/\\S*", RegexOption.IGNORE_CASE)
    private const val TRAILING_JUNK = ".,;:!?)]}>'\""

    /** Pulls the link out of whatever was pasted — share sheets often add
     *  the video title around it — and adds https:// if it's missing. */
    fun extractLink(input: String): String {
        val text = input.trim()
        if (WHOLE_SHORTHAND.matches(text)) return text
        URL_IN_TEXT.find(text)?.let { return it.value.trimEnd(*TRAILING_JUNK.toCharArray()) }
        SCHEMELESS_URL.find(text)?.let { return "https://" + it.value.trimEnd(*TRAILING_JUNK.toCharArray()) }
        return text
    }

    private val MEDIA_EXTENSION = Regex(
        "\\.(mp4|m4v|webm|mkv|mov|avi|flv|ogv|ogg|mp3|m4a|aac|flac|wav|opus|ts|m3u8|mpd|json)$",
        RegexOption.IGNORE_CASE
    )

    /**
     * True when [parsed] only came out as a raw file because the link wasn't
     * recognised, and the address doesn't look like a media file either — the
     * usual sign of a short/redirect link (t.co, bit.ly, on.soundcloud.com…).
     * Following its redirects and parsing the final address usually finds
     * the real video. Genuine file links (…/movie.mp4) are left alone.
     */
    fun shouldFollowRedirects(parsed: Parsed): Boolean {
        if (parsed.type != "fi") return false
        val path = runCatching { URI(parsed.id).rawPath }.getOrNull().orEmpty()
        return !MEDIA_EXTENSION.containsMatchIn(path)
    }

    fun parse(input: String): Result<Parsed> {
        val url = extractLink(input)
        if (url.isEmpty()) return Result.failure(IllegalArgumentException("Paste a link first."))

        // Shorthand like "yt:dQw4w9WgXcQ", which CyTube's own add box accepts.
        if (WHOLE_SHORTHAND.matches(url) && SHORTHAND.containsMatchIn(url)) {
            val type = url.substring(0, 2)
            val rest = url.substring(3)
            val id = when (type) {
                "fi", "cm", "yp" -> rest
                "dm" -> rest.takeWhile { it !in "?&#_" }
                else -> rest.takeWhile { it !in "?&#" }
            }
            return ok(type, id)
        }

        val uri = runCatching { URI(url) }.getOrNull()
            ?: return unknown()
        val scheme = uri.scheme?.lowercase() ?: return unknown()
        val path = uri.rawPath.orEmpty()

        if (scheme == "rtmp") return ok("rt", url)
        if (path.endsWith(".m3u8")) return ok("hl", url)
        if (path.endsWith(".json")) return ok("cm", url)

        // www. and m. (mobile) variants are the same site.
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return unknown()
        fun param(name: String): String? = uri.rawQuery?.split('&')
            ?.map { it.split('=', limit = 2) }
            ?.firstOrNull { it[0] == name }
            ?.getOrNull(1)
            ?.let { URLDecoder.decode(it, "UTF-8") }
        fun firstSegment(after: String): String = path.removePrefix(after).substringBefore('/')

        when (host) {
            "youtube.com", "music.youtube.com", "youtube-nocookie.com" -> {
                when {
                    path == "/watch" -> param("v")?.let { return ok("yt", it) }
                    path.startsWith("/shorts/") -> return ok("yt", firstSegment("/shorts/").take(11))
                    path.startsWith("/live/") -> return ok("yt", firstSegment("/live/"))
                    path.startsWith("/embed/") -> return ok("yt", firstSegment("/embed/"))
                    path.startsWith("/v/") -> return ok("yt", firstSegment("/v/"))
                    path.startsWith("/e/") -> return ok("yt", firstSegment("/e/"))
                    path == "/playlist" -> param("list")?.let { return ok("yp", it) }
                }
                // A channel page, search results, etc. — not something the
                // server can queue. (The original turns "/@name" into the
                // YouTube id "@name", which the server then rejects.)
                return Result.failure(IllegalArgumentException("That YouTube link isn't a video or playlist."))
            }
            "youtu.be" -> return ok("yt", path.removePrefix("/").substringBefore('/'))

            "twitch.tv" -> return when {
                path.contains("/clip/") -> ok("tc", path.substringAfterLast('/'))
                path.startsWith("/videos/") -> ok("tv", "v" + path.substringAfterLast('/'))
                else -> ok("tw", path.removePrefix("/").substringBefore('/'))
            }
            "clips.twitch.tv" -> return ok("tc", path.removePrefix("/"))

            "livestream.com" -> {
                LIVESTREAM_PATH.find(path)?.let { m ->
                    return ok("li", "${m.groupValues[1]};${m.groupValues[2]}")
                }
                return Result.failure(IllegalArgumentException(
                    "This Livestream.com link isn't supported. Use one with the numeric account ID."
                ))
            }

            // vimeo.com/123, vimeo.com/channels/staffpicks/123,
            // player.vimeo.com/video/123: the id is the numeric segment.
            "vimeo.com", "player.vimeo.com" ->
                path.split('/').firstOrNull { it.isNotEmpty() && it.all(Char::isDigit) }
                    ?.let { return ok("vi", it) }
            "dailymotion.com" -> return ok("dm", path.removePrefix("/video/").substringBefore('/'))
            "dai.ly" -> return ok("dm", path.removePrefix("/").substringBefore('/'))
            // The server looks the track up by its URL; give it the desktop one.
            "soundcloud.com" -> return ok("sc", "https://soundcloud.com$path")
            "streamable.com" -> return ok("sb", path.removePrefix("/"))

            "docs.google.com", "drive.google.com" -> {
                if (path.startsWith("/file/")) return ok("gd", path.removePrefix("/file/d/").substringBefore('/'))
                if (path == "/open" || path == "/uc") param("id")?.let { return ok("gd", it) }
            }

            "bitchute.com" ->
                if (path.startsWith("/video/")) return ok("bc", firstSegment("/video/"))
            "nicovideo.jp" ->
                if (path.startsWith("/watch/")) return ok("nv", firstSegment("/watch/"))
            "odysee.com" ->
                ODYSEE_PATH.find(path)?.let { m ->
                    return ok("od", "${m.groupValues[1]};${m.groupValues[2]}")
                }
        }

        if (host.endsWith(".bandcamp.com") && path.startsWith("/track/")) {
            return ok("bn", "${host.removeSuffix(".bandcamp.com")};${path.removePrefix("/track/")}")
        }

        PEERTUBE_PATH.find(path)?.let { m ->
            val shortOrLong = m.groupValues[1].ifEmpty { m.groupValues[2] }
            return ok("pt", "${uri.host};$shortOrLong")
        }

        // Anything else over http(s) is offered as a raw file; the server
        // checks it's actually a playable video before accepting it.
        if (scheme == "http" || scheme == "https") return ok("fi", url)

        return unknown()
    }

    private fun ok(type: String, id: String): Result<Parsed> =
        if (id.isBlank()) unknown() else Result.success(Parsed(type, id))

    private fun unknown(): Result<Parsed> = Result.failure(
        IllegalArgumentException("Couldn't tell what kind of video that link is.")
    )
}
