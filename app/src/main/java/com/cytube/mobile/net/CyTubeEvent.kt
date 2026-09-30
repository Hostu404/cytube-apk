package com.cytube.mobile.net

sealed interface CyTubeEvent {
    data object Connected : CyTubeEvent
    data object Disconnected : CyTubeEvent
    data class Reconnecting(val attempt: Int) : CyTubeEvent
    data class ConnectionFailed(val reason: String) : CyTubeEvent
    /** The server itself turned the connection down ([reason] is its own
     *  message: "Rate limit exceeded", "You are banned from the server"...).
     *  Unlike [ConnectionFailed], Socket.IO does NOT retry after this: the
     *  app has to connect again itself. */
    data class ConnectionRefused(val reason: String) : CyTubeEvent
    /** Socket.IO has used up its reconnection attempts and stopped trying. */
    data object ReconnectGaveUp : CyTubeEvent

    data class LoginResult(val success: Boolean, val name: String?, val error: String?) : CyTubeEvent
    data class RankChanged(val rank: Double) : CyTubeEvent
    /** Our saved login cookie was refused: the server treats us as anonymous. */
    data object SessionExpired : CyTubeEvent
    data class NeedPassword(val wrongPasswordTried: Boolean) : CyTubeEvent
    data object PasswordAccepted : CyTubeEvent
    /** partitionChange: the channel moved backend. Re-resolve socketconfig. */
    data object PartitionChanged : CyTubeEvent
    data class Kicked(val reason: String) : CyTubeEvent
    data class ErrorMessage(val message: String) : CyTubeEvent

    /** [receivedAtMs]: SystemClock.elapsedRealtime() when the frame came off
     *  the socket, so the room's clock stays right even if the app is slow
     *  to get round to it (a chat flood); 0 = unknown, use "now". */
    data class MediaChanged(val media: MediaFrame, val receivedAtMs: Long = 0L) : CyTubeEvent
    data class MediaTimeUpdate(val update: TimeUpdate, val receivedAtMs: Long = 0L) : CyTubeEvent
    data class PlaylistReplaced(val items: List<PlaylistItem>) : CyTubeEvent
    data class CurrentItemChanged(val uid: Int) : CyTubeEvent
    /** [afterUid]: the item it goes after — [PlaylistPosition.START] for the
     *  top of the list, [PlaylistPosition.END] (or an unknown uid) for the end. */
    data class ItemQueued(val item: PlaylistItem, val afterUid: Int) : CyTubeEvent
    /** A moderator moved item [uid] to after [afterUid] (same encoding as
     *  [ItemQueued]). */
    data class ItemMoved(val uid: Int, val afterUid: Int) : CyTubeEvent
    data class ItemDeleted(val uid: Int) : CyTubeEvent
    data class PlaylistLocked(val locked: Boolean) : CyTubeEvent

    data class Chat(val message: ChatMessage) : CyTubeEvent
    data object ChatCleared : CyTubeEvent

    data class UserListReplaced(val users: List<ChannelUser>) : CyTubeEvent
    data class UserJoined(val user: ChannelUser) : CyTubeEvent
    data class UserLeft(val name: String) : CyTubeEvent
    data class UserCount(val count: Int) : CyTubeEvent
    /** setUserMeta carries only a user's status flags — never their rank. */
    data class UserAfkChanged(val name: String, val afk: Boolean) : CyTubeEvent
    data class UserRankChanged(val name: String, val rank: Double) : CyTubeEvent
    data class LeaderChanged(val name: String?) : CyTubeEvent

    data class Emotes(val emotes: List<Emote>) : CyTubeEvent
    data class EmoteUpdated(val emote: Emote) : CyTubeEvent
    data class EmoteRenamed(val oldName: String, val emote: Emote) : CyTubeEvent
    data class EmoteRemoved(val name: String) : CyTubeEvent
    data class PermissionsChanged(val permissions: Permissions) : CyTubeEvent
    /** channelOpts — only the one option the app acts on so far. */
    data class VoteskipAllowed(val allowed: Boolean) : CyTubeEvent
    /** voteskip — the running tally, sent only to ranks allowed to see it
     *  ("viewvoteskip", moderators by default). [need] is 0 when no vote is
     *  running (the server resets it on every item change). */
    data class VoteskipCount(val count: Int, val need: Int) : CyTubeEvent
    /** The server dropped our skip vote (we went AFK); we may vote again. */
    data object VoteskipVoteCleared : CyTubeEvent
    /** The server refused a "queue" request (bad link, not allowed, rate
     *  limited...). [id] is the media id it was about, when given. */
    data class QueueFailed(val message: String, val id: String?) : CyTubeEvent
    data class MotdChanged(val html: String) : CyTubeEvent

    /** Sent both when a poll is actually created and to bring a joining
     *  client up to date on one already running. */
    data class PollOpened(val poll: Poll) : CyTubeEvent
    data class PollUpdated(val counts: List<Int>, val hiddenFromOthers: Boolean = false) : CyTubeEvent
    data object PollClosed : CyTubeEvent
    /** A site-wide announcement from the server's administrators. */
    data class Announcement(val title: String, val html: String) : CyTubeEvent
}

/** Positions used by the server's "queue" and "moveVideo" frames besides a
 *  real item uid ("prepend" / "append"). Uids are never negative. */
object PlaylistPosition {
    const val START = -2
    const val END = -1

    /** The "after" field of queue/moveVideo: an item uid (number, or a
     *  number in a string), "prepend", or "append"/missing. */
    fun parse(after: Any?): Int = when (after) {
        "prepend" -> START
        is Number -> after.toInt()
        is String -> after.toIntOrNull() ?: END
        else -> END
    }
}
