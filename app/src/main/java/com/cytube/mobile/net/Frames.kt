package com.cytube.mobile.net

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * Payload shapes are transcribed from the CyTube server source, not guessed:
 *   media frames      -> src/media.js  (pack / getTimeUpdate / getFullUpdate)
 *   chat frames       -> src/channel/chat.js  (formatMessage)
 *   userlist frames   -> src/channel/channel.js  (packUserData / sendUserlist)
 *
 * Every accessor is defensive. A channel can and will send frames with missing
 * or unexpected fields; nothing here is allowed to throw.
 */

/** changeMedia — full frame. Passed to PlayerSurface/etc. as a single object,
 *  so @Immutable here matters less than the List-typed state fields, but it's
 *  free — `direct` is the only field that would otherwise make the compiler's
 *  automatic stability inference give up on this class. */
@Immutable
data class MediaFrame(
    val id: String,
    val title: String,
    val seconds: Int,
    val duration: String,
    val type: String,
    val currentTime: Double,
    val paused: Boolean,
    val direct: List<DirectSource>,
    val embedSrc: String?,
    val scuri: String?,
    val thumbnail: String?
) {
    val isLivestream: Boolean get() = seconds <= 0
    val hasDirect: Boolean get() = direct.isNotEmpty()
    /** Best available source, honouring the quality order in sortSources(). */
    val bestSource: DirectSource? get() = direct.firstOrNull()

    /** The URL this item's own single-video WebView surface should load — see
     *  MediaTypes.Player.EMBED. Three sources, in order:
     *
     *  1. meta.embed.src — a provider's pre-built embeddable link (e.g. an
     *     "?embedded=True" view link), when CyTube's server supplied one.
     *  2. scuri — CyTube's record of the original URL the item was added
     *     from. Never a dedicated embed link, but for a page that's mostly-
     *     just-a-video-player anyway it does the same job.
     *  3. MediaTypes.knownEmbedUrl(type, id) — a handful of providers CyTube
     *     resolves with no embeddable link in meta AT ALL (Dailymotion,
     *     Niconico, Streamable, PeerTube — checked directly against
     *     CyTube's own get-info.js/mediaquery source, not assumed), where
     *     the provider itself still has a stable, publicly documented,
     *     no-API-key embed page. This is what makes Dailymotion (etc.) not
     *     fall straight to the whole-page WEB offer.
     *
     *  Google Drive is deliberately excluded from all three: it already gets
     *  its own native resolution (GoogleDriveResolver — see
     *  MediaTypes.playerFor) and a raw Drive link opened in a stripped-down
     *  WebView lands on Google's own sign-in/UI chrome, not a clean video —
     *  see the README's known Google Drive limitation. Its failures fall
     *  straight through to null here, so they still go to the full
     *  Compatibility View offer, same as before this existed. */
    val embedPlayableSrc: String? get() = if (type == "gd") null else
        embedSrc?.takeIf { it.isNotBlank() }
            ?: scuri
            ?: MediaTypes.knownEmbedUrl(type, id)

    companion object {
        fun from(o: JSONObject): MediaFrame {
            val meta = o.optJSONObject("meta") ?: JSONObject()
            val embed = meta.optJSONObject("embed")
            return MediaFrame(
                id = o.optString("id", ""),
                title = o.optString("title", "Untitled"),
                seconds = o.optInt("seconds", 0),
                duration = o.optString("duration", ""),
                type = o.optString("type", ""),
                currentTime = o.optDouble("currentTime", 0.0),
                paused = o.optBoolean("paused", false),
                direct = DirectSource.parse(meta.optJSONObject("direct")),
                embedSrc = embed?.optString("src")?.ifBlank { null },
                scuri = meta.optString("scuri").ifBlank { null },
                thumbnail = meta.optString("thumbnail").ifBlank { null }
            )
        }

        /**
         * Builds a playable frame straight from a playlist listing, for
         * personal/unsynced picking (see ChannelViewModel.pickPersonal) — the
         * server only ever sends the richer meta (direct sources, embed.src)
         * alongside the channel's actual current item via changeMedia, so a
         * personally-picked item that ISN'T the current one has to make do
         * with exactly what PlaylistItem carries: type + id. That's the same
         * constraint MediaTypes.canResolveIndependently checks before this is
         * ever called — direct is always empty and embedSrc/scuri are always
         * null here, leaving embedPlayableSrc to fall through to
         * MediaTypes.knownEmbedUrl(type, id), same as playerFor expects.
         *
         * seconds comes from a best-effort parse of the playlist's formatted
         * duration string (PlaylistItem never carries a raw seconds count).
         * Anything that doesn't parse cleanly — including CyTube's own
         * "??:??" placeholder for streams with no fixed length — becomes 0,
         * which is exactly MediaFrame.isLivestream's existing sentinel, so an
         * unparseable duration degrades to "treat as live" rather than a
         * crash or a wrong fixed length.
         */
        fun fromPlaylistItem(item: PlaylistItem): MediaFrame = MediaFrame(
            id = item.mediaId,
            title = item.title,
            seconds = parseDurationSeconds(item.duration),
            duration = item.duration,
            type = item.type,
            currentTime = 0.0,
            paused = false,
            direct = emptyList(),
            embedSrc = null,
            scuri = null,
            thumbnail = null
        )

        /** Parses "H:MM:SS" or "MM:SS" (CyTube's formatTime output) back into
         *  a whole seconds count; anything else (blank, "??:??", garbage) is 0. */
        private fun parseDurationSeconds(duration: String): Int {
            val parts = duration.trim().split(":")
            if (parts.isEmpty() || parts.size > 3) return 0
            val nums = parts.map { it.toIntOrNull() ?: return 0 }
            return nums.fold(0) { acc, n -> acc * 60 + n }
        }
    }
}

/**
 * meta.direct is NOT a boolean — it is an object of quality -> [{link, contentType}],
 * consumed by sortSources() in player/videojs.coffee. For `cm` (custom manifest)
 * the media id is a .json manifest URL, so the playable URLs live here and
 * nowhere else. Handing the manifest URL to a player is guaranteed to fail.
 */
data class DirectSource(val link: String, val contentType: String, val quality: String) {
    companion object {
        // Quality order copied from sortSources(); we default to the highest
        // rather than the site's 480p default because phones are small but
        // networks are usually fine, and Media3 has no ABR here to fall back on.
        private val ORDER = listOf("2160", "1440", "1080", "720", "540", "480", "360", "240")

        fun parse(o: JSONObject?): List<DirectSource> {
            if (o == null) return emptyList()
            val out = mutableListOf<DirectSource>()
            val keys = o.keys()
            while (keys.hasNext()) {
                // JSONObject.keys() is an untyped Iterator on Android, so this
                // arrives as Any? rather than String.
                val quality = keys.next() as? String ?: continue
                val arr = o.optJSONArray(quality) ?: continue
                for (i in 0 until arr.length()) {
                    val src = arr.optJSONObject(i) ?: continue
                    val link = src.optString("link")
                    if (link.isBlank()) continue
                    out.add(DirectSource(link, src.optString("contentType", ""), quality))
                }
            }
            // FLV last — nothing on Android can play it.
            return out.sortedWith(
                compareBy<DirectSource> { it.contentType == "video/flv" }
                    .thenBy { ORDER.indexOf(it.quality).let { i -> if (i < 0) ORDER.size else i } }
            )
        }
    }
}

/** mediaUpdate — time-only frame, broadcast roughly every 5s. */
data class TimeUpdate(val currentTime: Double, val paused: Boolean) {
    companion object {
        fun from(o: JSONObject) = TimeUpdate(
            currentTime = o.optDouble("currentTime", 0.0),
            paused = o.optBoolean("paused", false)
        )
    }
}

// All-primitive/String fields, so the compiler would likely infer this stable
// on its own — explicit anyway since it's the item type of ChannelUiState's
// hottest list (see ChannelViewModel's PersistentList doc comment) and a
// silent regression here (e.g. a future List-typed field) would be easy to
// miss without this asserting the contract directly.
@Immutable
data class ChatMessage(
    val username: String,
    /** Sanitized HTML, not plain text — filters and emotes rewrite it server-side. */
    val html: String,
    val timestamp: Long,
    val addClass: String?,
    val shadow: Boolean,
    val isPm: Boolean = false,
    /** Assigned by the ViewModel; a stable LazyColumn key so chat rows are not
        rebuilt every time the buffer trims from the front. */
    val seq: Long = 0L
) {
    val isServerMessage: Boolean get() = username.isBlank() || username == "[server]"

    companion object {
        fun from(o: JSONObject, isPm: Boolean = false): ChatMessage {
            val meta = o.optJSONObject("meta") ?: JSONObject()
            return ChatMessage(
                username = o.optString("username", ""),
                html = o.optString("msg", ""),
                timestamp = o.optLong("time", System.currentTimeMillis()),
                addClass = meta.optString("addClass").ifBlank { null },
                shadow = meta.optBoolean("shadow", false),
                isPm = isPm
            )
        }
    }
}

@Immutable
data class ChannelUser(
    val name: String,
    val rank: Double,
    val afk: Boolean,
    val muted: Boolean,
    val profileImage: String?,
    val profileText: String?
) {
    companion object {
        fun from(o: JSONObject): ChannelUser {
            val meta = o.optJSONObject("meta") ?: JSONObject()
            val profile = o.optJSONObject("profile")
            return ChannelUser(
                name = o.optString("name", ""),
                rank = o.optDouble("rank", 0.0),
                afk = meta.optBoolean("afk", false),
                muted = meta.optBoolean("muted", false) || meta.optBoolean("smuted", false),
                profileImage = profile?.optString("image")?.ifBlank { null },
                profileText = profile?.optString("text")?.ifBlank { null }
            )
        }

        fun listFrom(a: JSONArray): List<ChannelUser> =
            (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { from(it) }
            }.filter { it.name.isNotBlank() }
    }
}

@Immutable
data class PlaylistItem(
    val uid: Int,
    val title: String,
    val duration: String,
    val type: String,
    val mediaId: String,
    val queueby: String,
    val temp: Boolean
) {
    companion object {
        fun from(o: JSONObject): PlaylistItem {
            val media = o.optJSONObject("media") ?: JSONObject()
            return PlaylistItem(
                uid = o.optInt("uid", -1),
                title = media.optString("title", "Untitled"),
                duration = media.optString("duration", ""),
                type = media.optString("type", ""),
                mediaId = media.optString("id", ""),
                queueby = o.optString("queueby", ""),
                temp = o.optBoolean("temp", false)
            )
        }

        fun listFrom(a: JSONArray): List<PlaylistItem> =
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { from(it) } }
    }
}

/**
 * `source` is the JavaScript regex the channel saved for this emote. The
 * official client compiles it with "gi" and uses it for substitution, so we
 * have to carry it rather than matching on the name alone.
 */
data class Emote(val name: String, val image: String, val source: String) {
    companion object {
        fun from(o: JSONObject) = Emote(
            name = o.optString("name", ""),
            // Some channels save emotes as protocol-relative ("//host/x.gif")
            // or root-relative ("/x.gif") URLs — valid in a browser's <img>,
            // meaningless to Coil/OkHttp on their own. See resolveMediaUrl.
            image = resolveMediaUrl(o.optString("image", "")),
            source = o.optString("source", "")
        )

        fun listFrom(a: JSONArray): List<Emote> =
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { from(it) } }
                .filter { it.name.isNotBlank() && it.image.isNotBlank() }
    }
}

data class Permissions(val raw: JSONObject) {
    fun requiredRank(key: String): Double = raw.optDouble(key, Double.MAX_VALUE)
    fun allows(key: String, rank: Double): Boolean = rank >= requiredRank(key)
}

/**
 * newPoll / updatePoll — src/poll.js packUpdate(). counts is parallel to
 * options. A channel owner can create an "obscured" poll, which server-side
 * replaces each count with "?" (or omits it) instead of a number until it
 * closes; those come through as -1 here rather than 0, so the UI can tell
 * "no votes yet" apart from "hidden".
 */
@Immutable
data class Poll(
    val initiator: String,
    val title: String,
    val options: List<String>,
    val counts: List<Int>,
    val timestamp: Long
) {
    val totalVotes: Int get() = counts.filter { it >= 0 }.sum()
    val isObscured: Boolean get() = counts.any { it < 0 }

    companion object {
        fun from(o: JSONObject): Poll {
            val options = o.optJSONArray("options")
            val optList = (0 until (options?.length() ?: 0))
                .map { plainText(options!!.optString(it, "")) }
            return Poll(
                initiator = o.optString("initiator", ""),
                title = plainText(o.optString("title", "")),
                options = optList,
                counts = parseCounts(o.optJSONArray("counts"), optList.size),
                timestamp = o.optLong("timestamp", System.currentTimeMillis())
            )
        }

        /**
         * Like chat, a poll's title/options can arrive as CyTube-linkified HTML
         * (e.g. a pasted URL wrapped in `<a href>`) rather than plain text. The
         * poll UI is plain buttons and progress bars, not the rich chat
         * renderer, so this strips markup down to its text content instead of
         * showing raw tags.
         */
        private fun plainText(raw: String): String =
            if (raw.indexOf('<') < 0) raw else Jsoup.parse(raw).text()

        /** Always returns exactly [size] entries so it can be zipped with
         *  options positionally even if the server sent a mismatched array. */
        fun parseCounts(a: JSONArray?, size: Int): List<Int> =
            (0 until size).map { i ->
                val raw = a?.opt(i)
                (raw as? Number)?.toInt() ?: raw?.toString()?.toIntOrNull() ?: -1
            }
    }
}
