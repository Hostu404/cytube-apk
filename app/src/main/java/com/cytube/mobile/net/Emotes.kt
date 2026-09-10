package com.cytube.mobile.net

import androidx.compose.runtime.Immutable

/**
 * Emote substitution, ported from the official client.
 *
 * The important discovery: CyTube does NOT substitute emotes server-side.
 * src/channel/chat.js contains no emote code whatsoever. The server sends the
 * message text as-is and every client turns emote names into <img> itself, in
 * www/js/util.js execEmotes(), called from the chatMsg handler at util.js:1508.
 *
 * That is why custom room emotes never appeared: we were rendering the <img>
 * tags in the message, and for emotes there aren't any.
 *
 * Faithful port of loadEmotes() / execEmotesEfficient() / emoteToImg():
 *
 *  - each emote is {name, image, source}; `source` is a JavaScript regex string
 *    compiled with the "gi" flags, and the replacement is '$1' + img, so the
 *    pattern is expected to capture a leading boundary in group 1
 *  - emotes whose name contains whitespace cannot be hashmapped, so they keep
 *    the regex path ("badEmotes" in the original)
 *  - everything else is matched by splitting on non-whitespace runs and looking
 *    the token up in a map keyed by the HTML-sanitized name
 *
 * Tokens that match nothing are left alone, which is what makes unknown emotes
 * fall through as ordinary text.
 */
// Every property here is a val set once in the private constructor, and every
// "mutation" (withUpdated/withRenamed/withRemoved) returns a brand-new
// instance rather than touching this one — genuinely immutable, just not
// provably so to the compiler on its own, since `all`/`hashed`/`spaced` are
// List/Map (interfaces a MutableList/MutableMap could hide behind). Without
// this, every composable taking an EmoteSet parameter (ChatPanel, ChatRow,
// NekoChatOverlay, the emote picker) is forced non-skippable, same issue as
// ChannelUiState's own List fields — see their PersistentList doc comment.
@Immutable
class EmoteSet private constructor(
    val all: List<Emote>,
    private val hashed: Map<String, Emote>,
    private val spaced: List<Pair<Regex, Emote>>
) {
    val isEmpty: Boolean get() = all.isEmpty()

    /** Substitution is skipped entirely when there is nothing to substitute. */
    fun apply(message: String): String {
        if (all.isEmpty()) return message

        var out = message

        // Emotes with spaces in the name: regex path, replacement keeps group 1.
        for ((regex, emote) in spaced) {
            out = regex.replace(out) { m ->
                (m.groupValues.getOrNull(1).orEmpty()) + imgTag(emote)
            }
        }

        if (hashed.isEmpty()) return out

        return TOKEN.replace(out) { m ->
            val emote = hashed[m.value]
            if (emote == null) Regex.escapeReplacement(m.value) else imgTag(emote)
        }
    }

    /**
     * Does this message contain anything we would substitute? Cheap pre-check,
     * called on every incoming chat message. splitToSequence rather than
     * split(): a busy channel's messages are mostly a handful of tokens with no
     * emote in them, so a lazy sequence that can bail on the first hit (or the
     * first mismatch, without ever materialising a List<String>) beats eagerly
     * allocating the whole split up front.
     *
     * This used to return `true` outright whenever the channel had ANY
     * whitespace-named emote at all, regardless of whether the message
     * actually contained it — which meant `apply()`'s full substitution pass
     * (including a regex scan per spaced emote, then a whole-message
     * TOKEN.replace) ran on every single chat message the moment a channel
     * had even one multi-word emote name, whether or not that message had
     * any emote in it at all. On a channel with thousands of emotes and heavy
     * chat traffic, that's real, continuous main-thread cost landing on every
     * message — this checks for real instead of assuming the worst case.
     */
    fun mightMatch(message: String): Boolean {
        if (all.isEmpty()) return false
        if (spaced.isNotEmpty() && spaced.any { (regex, _) -> regex.containsMatchIn(message) }) {
            return true
        }
        return WHITESPACE.splitToSequence(message).any { hashed.containsKey(it) }
    }

    fun withUpdated(emote: Emote): EmoteSet =
        from(all.filterNot { it.name == emote.name } + emote)

    fun withRenamed(oldName: String, emote: Emote): EmoteSet =
        from(all.filterNot { it.name == oldName || it.name == emote.name } + emote)

    fun withRemoved(name: String): EmoteSet =
        from(all.filterNot { it.name == name })

    companion object {
        val EMPTY = EmoteSet(emptyList(), emptyMap(), emptyList())

        private val TOKEN = Regex("[^\\s]+")
        private val WHITESPACE = Regex("\\s+")

        fun from(emotes: List<Emote>): EmoteSet {
            if (emotes.isEmpty()) return EMPTY

            val hashed = HashMap<String, Emote>(emotes.size)
            val spaced = ArrayList<Pair<Regex, Emote>>()

            for (e in emotes) {
                if (e.name.isBlank() || e.image.isBlank()) continue
                if (e.name.contains(WHITESPACE)) {
                    // Cannot be hashmapped; compile its source pattern instead.
                    val compiled = runCatching {
                        Regex(e.source.ifBlank { Regex.escape(e.name) }, RegexOption.IGNORE_CASE)
                    }.getOrNull()
                    // A channel can save a pattern that is valid in JavaScript but
                    // not in Java. Dropping that one emote is much better than
                    // throwing away the whole set.
                    if (compiled != null) spaced.add(compiled to e)
                } else {
                    hashed[sanitize(e.name)] = e
                }
            }
            return EmoteSet(emotes, hashed, spaced)
        }

        /** Matches loadEmotes()'s sanitizeText, so map keys line up with the
            already-escaped message text the server sends. */
        private fun sanitize(s: String): String = s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

        private fun imgTag(e: Emote): String =
            """<img class="channel-emote" title="${sanitize(e.name)}" src="${sanitize(e.image)}">"""
    }
}
