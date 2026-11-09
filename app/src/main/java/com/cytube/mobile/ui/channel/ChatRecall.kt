package com.cytube.mobile.ui.channel

import androidx.compose.runtime.Immutable
import com.cytube.mobile.net.EmoteSet

/** Which conversation the chat box is in: public chat (""), or private
 *  messages with [pmTarget] (their name, lower case). */
internal fun conversationOf(pmTarget: String?): String = pmTarget?.lowercase() ?: ""

/**
 * How long a message the chat box takes. CyTube cuts every chat message and
 * private message to its max-chat-message-length (320 on cytu.be, and in
 * CyTube's own default config) without saying so, so a longer one used to
 * arrive cut short. The website's chat box and private-message boxes stop
 * at the same length (their maxlength), and so does the app's now, so what's
 * in the box is what gets sent. Lengths are counted in UTF-16 units, as the
 * server (JavaScript) counts them.
 */
internal object ChatDraft {
    const val MAX_CHARS = 320

    /**
     * What fits of an edit from [old] to [new], as a browser's maxlength
     * does it: of what was typed or pasted in, as much as fits; the text
     * that was already there stays. Null when [new] fits as it is; otherwise
     * the text, and where the cursor goes (just after what went in).
     */
    fun fit(old: String, new: String, max: Int = MAX_CHARS): Pair<String, Int>? {
        if (new.length <= max) return null
        // The edit: the same text at both ends of old and new, and between
        // them what was put in. Never between the halves of a surrogate
        // pair (an emoji), so none is left in half.
        val shortest = minOf(old.length, new.length)
        var start = 0
        while (start < shortest && old[start] == new[start]) start++
        if (start > 0 && Character.isHighSurrogate(new[start - 1])) start--
        var end = 0
        while (end < shortest - start && old[old.length - 1 - end] == new[new.length - 1 - end]) end++
        if (end > 0 && Character.isLowSurrogate(new[new.length - end])) end--
        val room = (max - start - end).coerceAtLeast(0)
        val inserted = clip(new.substring(start, new.length - end), room)
        return (new.substring(0, start) + inserted + new.substring(new.length - end)) to (start + inserted.length)
    }

    /** [text] cut to at most [max] chars, never between the two halves of
     *  a surrogate pair. */
    fun clip(text: String, max: Int = MAX_CHARS): String {
        if (text.length <= max) return text
        val cut = if (max > 0 && Character.isHighSurrogate(text[max - 1])) max - 1 else max.coerceAtLeast(0)
        return text.substring(0, cut)
    }
}

/**
 * What you've sent in a channel this visit, oldest first, for the chat box's
 * ↑ and ↓ (phone only): as Up and Down do in the website's chat line, they
 * step back and forth through it. Public chat and each private conversation
 * are kept apart, so a PM never comes back up in public chat. Only the last
 * [KEEP], and only while you're in the channel. Settings can turn it off
 * (Settings.messageRecall).
 */
@Immutable
class SentChat private constructor(private val byConversation: Map<String, List<String>>) {
    /** What's been sent to [pmTarget] (null: public chat), oldest first. */
    fun of(pmTarget: String?): List<String> = byConversation[conversationOf(pmTarget)].orEmpty()

    /** With [line] sent to [pmTarget] (null: public chat). The same line
     *  sent twice running is kept once, and only the last [KEEP] are kept. */
    fun adding(pmTarget: String?, line: String): SentChat {
        val k = conversationOf(pmTarget)
        val was = byConversation[k].orEmpty()
        if (line.isBlank() || was.lastOrNull() == line) return this
        return SentChat(byConversation + (k to (was + line).takeLast(KEEP)))
    }

    companion object {
        val EMPTY = SentChat(emptyMap())
        const val KEEP = 4
    }
}

/**
 * The emotes you've used lately in a channel, newest first, for a row at the
 * top of the emote sheet (phone only): your usual ones a tap away. Taken from
 * what you send; kept between visits (see RecentEmotesStore).
 */
object RecentEmotes {
    const val KEEP = 16

    /** The names of the channel's emotes [line] uses, as chat will show
     *  them (see EmoteSet.usedIn). */
    fun usedIn(line: String, emotes: EmoteSet): List<String> = emotes.usedIn(line).map { it.name }

    /** [recent] with [used] at its front, each once, at most [KEEP]. */
    fun after(recent: List<String>, used: List<String>): List<String> = (used + recent).distinct().take(KEEP)
}
