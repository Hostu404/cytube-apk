package com.cytube.mobile.ui.channel

import androidx.compose.runtime.Immutable
import com.cytube.mobile.net.EmoteSet

/** Which conversation the chat box is in: public chat (""), or private
 *  messages with [pmTarget] (their name, lower case). */
internal fun conversationOf(pmTarget: String?): String = pmTarget?.lowercase() ?: ""

/**
 * What you've sent in a channel this visit, oldest first, for the chat box's
 * ↑ and ↓ (phone only): as Up and Down do in the website's chat line, they
 * step back and forth through it. Public chat and each private conversation
 * are kept apart, so a PM never comes back up in public chat. Kept only while
 * you're in the channel, as the website keeps its own.
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
        const val KEEP = 20
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
