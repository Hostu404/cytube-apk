package com.cytube.mobile.ui.channel

import androidx.compose.ui.input.key.Key
import com.cytube.mobile.net.MediaTypes
import com.cytube.mobile.net.PlaylistItem
import java.util.Locale
import kotlin.math.roundToLong

/** Where the video is, as TV's own playback controls see it (sync off). */
data class PlaybackStatus(val positionSeconds: Double, val lengthSeconds: Int, val paused: Boolean)

/**
 * TV's playback controls with sync off: OK plays or pauses, Left and Right
 * (and a remote's rewind and fast-forward) seek, and a bar shows where the
 * video is (see ChannelScreen). This is what the bar shows and where a seek
 * goes, apart from the screen for testing. Times are uptime in ms.
 *
 * The player is asked where it is a few times a second (see [onStatus]),
 * and in between the position is worked out from that. A seek is taken as
 * done straight away: the next press goes on from where the last one went,
 * so holding Right (the remote repeating it) keeps moving 10 seconds at a
 * time rather than starting each step from where the player has got to.
 */
internal class TvTransport {
    /** The last answer from the player, or null before the first. */
    var status: PlaybackStatus? = null
        private set
    private var statusAtMs = 0L
    /** Where the last seek went, and when: in charge for [SEEK_SETTLE_MS]. */
    private var seekTarget: Double? = null
    private var seekAtMs = 0L
    /** When a control was last used, for how long the bar stays up. */
    private var touchedAtMs: Long? = null

    fun onStatus(status: PlaybackStatus, nowMs: Long) {
        // An answer from before the player had got to the seek would undo it
        // on screen: kept off until the seek has had time to happen.
        val target = seekTarget
        if (target != null && nowMs - seekAtMs <= SEEK_SETTLE_MS) {
            this.status = status.copy(positionSeconds = target)
            return
        }
        seekTarget = null
        this.status = status
        statusAtMs = nowMs
    }

    /** Playing or paused just now by a control: shown at once, not at the
     *  player's next answer. */
    fun onToggled(playing: Boolean, nowMs: Long) {
        val s = status ?: return
        val at = positionAt(nowMs) ?: s.positionSeconds
        seekTarget?.let { seekTarget = at; seekAtMs = nowMs }
        status = s.copy(positionSeconds = at, paused = !playing)
        statusAtMs = nowMs
        touchedAtMs = nowMs
    }

    /** Where the video is at [nowMs]: from the last answer, or the last
     *  seek, moved on by the time since while it's playing. */
    fun positionAt(nowMs: Long): Double? {
        val s = status ?: return null
        val target = seekTarget
        val (from, fromMs) = if (target != null && nowMs - seekAtMs <= SEEK_SETTLE_MS) target to seekAtMs
            else s.positionSeconds to statusAtMs
        val moved = if (s.paused) 0.0 else (nowMs - fromMs).coerceAtLeast(0L) / 1000.0
        val at = from + moved
        return if (s.lengthSeconds > 0) at.coerceIn(0.0, s.lengthSeconds.toDouble()) else at
    }

    /**
     * Where a seek by [deltaSeconds] goes: from where the video is now (or
     * where the seek before it went), within the video, short of its very
     * end (which would end it). Null with no player, or for live media,
     * which has no length to move along.
     */
    fun seekBy(deltaSeconds: Double, nowMs: Long): Double? {
        val s = status ?: return null
        if (s.lengthSeconds <= 0) return null
        val from = positionAt(nowMs) ?: return null
        val target = (from + deltaSeconds).coerceIn(0.0, (s.lengthSeconds - 1).coerceAtLeast(0).toDouble())
        seekTarget = target
        seekAtMs = nowMs
        touchedAtMs = nowMs
        return target
    }

    /** A control was used: the bar comes up. */
    fun touch(nowMs: Long) {
        touchedAtMs = nowMs
    }

    /** Whether the bar shows: while paused, and for [SHOW_MS] after a
     *  control is used. */
    fun visibleAt(nowMs: Long): Boolean {
        if (status?.paused == true) return true
        val at = touchedAtMs ?: return false
        return nowMs - at < SHOW_MS
    }

    /** Back to nothing known (sync turned back on, say). */
    fun reset() {
        status = null
        seekTarget = null
        touchedAtMs = null
    }

    companion object {
        /** How far Left and Right move. */
        const val SEEK_STEP_SECONDS = 10.0
        /** How long the bar stays up after a control is used. */
        const val SHOW_MS = 4_000L
        /** How long a seek is taken as done before the player's own time is
         *  believed again. */
        const val SEEK_SETTLE_MS = 2_000L

        /** 0:42, 12:05, 1:02:09. */
        fun clock(seconds: Double): String {
            val total = if (seconds.isNaN() || seconds < 0) 0L else seconds.roundToLong()
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
                else String.format(Locale.ROOT, "%d:%02d", m, s)
        }
    }
}

/** What a remote key does on TV's video screen with sync off. */
internal enum class TvKeyAction { TOGGLE, PLAY, PAUSE, BACK, FORWARD, SHOW, NONE }

/** The remote's playback keys on TV's video screen, with sync off (see
 *  ChannelScreen's TV layout). Down isn't one: it opens the playlist. */
internal object TvKeys {
    /**
     * What [key] does, [pressed] (or released) and repeating [repeatCount]
     * times so far: play/pause and Up on the first press only, so holding
     * OK doesn't flip it back and forth; seeking on every press, repeats
     * included, so a held key keeps going; and NONE for the release, which
     * is still the video screen's (nothing underneath, an embedded page say,
     * gets half a key press). Null for a key that's none of these.
     */
    fun actionFor(key: Key, pressed: Boolean, repeatCount: Int): TvKeyAction? {
        val first = pressed && repeatCount == 0
        return when (key) {
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause ->
                if (first) TvKeyAction.TOGGLE else TvKeyAction.NONE
            Key.MediaPlay -> if (first) TvKeyAction.PLAY else TvKeyAction.NONE
            Key.MediaPause -> if (first) TvKeyAction.PAUSE else TvKeyAction.NONE
            Key.DirectionLeft, Key.MediaRewind -> if (pressed) TvKeyAction.BACK else TvKeyAction.NONE
            Key.DirectionRight, Key.MediaFastForward -> if (pressed) TvKeyAction.FORWARD else TvKeyAction.NONE
            Key.DirectionUp -> if (first) TvKeyAction.SHOW else TvKeyAction.NONE
            else -> null
        }
    }
}

/** Where TV's playlist (sync off) puts the remote's focus. */
internal object TvPlaylistFocus {
    /** Whether [item] can be picked to play on its own (see
     *  MediaTypes.canResolveIndependently): the rows that take focus. */
    fun pickable(item: PlaylistItem): Boolean = MediaTypes.canResolveIndependently(item.type)

    /**
     * The row the playlist opens on: the item playing for this pick, when
     * it's in the list, else the first one that can be picked; -1 when none
     * can (the search box then has the focus).
     */
    fun openingRow(items: List<PlaylistItem>, playingUid: Int): Int {
        val playing = items.indexOfFirst { it.uid == playingUid && pickable(it) }
        return if (playing >= 0) playing else items.indexOfFirst(::pickable)
    }
}
