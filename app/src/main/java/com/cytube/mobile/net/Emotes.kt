package com.cytube.mobile.net

import androidx.compose.runtime.Immutable

/**
 * Emote substitution, ported from the official client.
 *
 * CyTube does NOT substitute emotes server-side: src/channel/chat.js has no
 * emote code at all. The server sends the message text as-is and every
 * client turns emote names into <img> itself, in www/js/util.js
 * execEmotes(), called from the chatMsg handler at util.js:1508. So a
 * message arrives with no emote <img> tags in it; this adds them.
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
// Every property is a val set once in the private constructor, and every
// "mutation" (withUpdated/withRenamed/withRemoved) returns a new instance —
// genuinely immutable, but the compiler can't prove it from List/Map fields.
// @Immutable lets composables taking an EmoteSet (ChatPanel, ChatRow,
// NekoChatOverlay, the emote picker) skip recomposition when it's unchanged.
@Immutable
class EmoteSet private constructor(
    val all: List<Emote>,
    private val hashed: Map<String, Emote>,
    private val spaced: List<Pair<Regex, Emote>>,
    /** The channel's emote modifiers ("/reverse pepe"), from its CSS — see
     *  ChannelStyle. Kept with the emotes they act on, so everything that
     *  draws emotes gets them, and a change to either re-renders chat. */
    val effects: EmoteEffects = EmoteEffects.NONE
) {
    /** Different for every set ever built (sets are never changed in place),
     *  so a cache can tell them apart without holding on to old ones. */
    val id: Long = NEXT_ID.getAndIncrement()

    /** Substitution is skipped entirely when there is nothing to substitute. */
    fun apply(message: String): String {
        if (all.isEmpty()) return message

        var out = message

        // Emotes with spaces in the name: regex path, replacement keeps group 1.
        // The patterns are the channel's own, so they run against a time
        // limit (see Deadline); one that runs out is left unsubstituted.
        val deadline = Deadline()
        for ((regex, emote) in spaced) {
            if (deadline.passed) break
            out = try {
                regex.replace(deadline.guard(out)) { m ->
                    (m.groupValues.getOrNull(1).orEmpty()) + imgTag(emote)
                }
            } catch (_: Deadline.Passed) {
                out
            }
        }

        if (hashed.isEmpty()) return out

        return TOKEN.replace(out) { m ->
            val emote = hashed[m.value]
            if (emote == null) m.value else imgTag(emote)
        }
    }

    /**
     * Does this message contain anything we would substitute? Cheap pre-check,
     * called on every incoming chat message. Scans tokens by index rather
     * than with a regex: a busy channel's messages are mostly a handful of
     * tokens with no emote in them, and this stops at the first hit.
     *
     * It really checks, spaced emotes included, rather than answering "yes"
     * whenever the channel has any spaced emote: a false "yes" costs a full
     * apply() on every message, which on a busy channel with thousands of
     * emotes is a steady load.
     */
    fun mightMatch(message: String): Boolean {
        if (all.isEmpty()) return false
        if (spaced.isNotEmpty()) {
            val deadline = Deadline()
            val hit = spaced.any { (regex, _) ->
                !deadline.passed && try {
                    regex.containsMatchIn(deadline.guard(message))
                } catch (_: Deadline.Passed) {
                    false
                }
            }
            if (hit) return true
        }
        if (hashed.isEmpty()) return false
        var start = 0
        val len = message.length
        while (start < len) {
            while (start < len && message[start].isWhitespace()) start++
            if (start >= len) break
            var end = start
            while (end < len && !message[end].isWhitespace()) end++
            if (hashed.containsKey(message.substring(start, end))) return true
            start = end
        }
        return false
    }

    fun withUpdated(emote: Emote): EmoteSet =
        from(all.filterNot { it.name == emote.name } + emote, effects)

    fun withRenamed(oldName: String, emote: Emote): EmoteSet =
        from(all.filterNot { it.name == oldName || it.name == emote.name } + emote, effects)

    fun withRemoved(name: String): EmoteSet =
        from(all.filterNot { it.name == name }, effects)

    /** The same emotes with the channel's modifiers [effects]. */
    fun withEffects(effects: EmoteEffects): EmoteSet =
        if (effects === this.effects) this else EmoteSet(all, hashed, spaced, effects)

    companion object {
        private val NEXT_ID = java.util.concurrent.atomic.AtomicLong(1)
        val EMPTY = EmoteSet(emptyList(), emptyMap(), emptyList())

        private val TOKEN = Regex("[^\\s]+")
        private val WHITESPACE = Regex("\\s+")

        fun from(emotes: List<Emote>, effects: EmoteEffects = EmoteEffects.NONE): EmoteSet {
            if (emotes.isEmpty()) return if (effects === EmoteEffects.NONE) EMPTY else EmoteSet(emptyList(), emptyMap(), emptyList(), effects)

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
            return EmoteSet(emotes, hashed, spaced, effects)
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

/**
 * A time limit for running the channel's own emote patterns. They're
 * JavaScript regexes a channel's moderators wrote, run here with Java's
 * engine, and a pathological one ("(a+)+b" and the like) can backtrack for
 * seconds on an ordinary message — on the main thread when a chat row isn't
 * already cached, long enough for "App isn't responding". Java's regex can't
 * be interrupted, but it reads its input through CharSequence.get, so
 * [guard] wraps the input in one that throws once [LIMIT_NS] has passed.
 * One deadline covers every pattern for one message.
 */
private class Deadline {
    private val endNs = System.nanoTime() + LIMIT_NS

    val passed: Boolean get() = System.nanoTime() > endNs

    fun guard(text: CharSequence): CharSequence = Guarded(text)

    class Passed : RuntimeException() {
        // Thrown as control flow; no stack trace needed.
        override fun fillInStackTrace(): Throwable = this
    }

    private inner class Guarded(private val inner: CharSequence) : CharSequence {
        private var reads = 0

        override val length: Int get() = inner.length

        override fun get(index: Int): Char {
            // nanoTime() on every character read would slow ordinary
            // patterns down; every 1024th is plenty to stop a runaway one.
            if ((++reads and 1023) == 0 && passed) throw Passed()
            return inner[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            inner.subSequence(startIndex, endIndex)

        override fun toString(): String = inner.toString()
    }

    private companion object {
        const val LIMIT_NS = 50_000_000L // 50 ms
    }
}
