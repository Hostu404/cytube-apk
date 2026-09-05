package com.cytube.mobile.net

import org.json.JSONObject

sealed interface CyTubeEvent {
    data object Connected : CyTubeEvent
    data object Disconnected : CyTubeEvent
    data class Reconnecting(val attempt: Int) : CyTubeEvent
    data class ConnectionFailed(val reason: String) : CyTubeEvent

    data class LoginResult(val success: Boolean, val name: String?, val error: String?) : CyTubeEvent
    data class RankChanged(val rank: Double) : CyTubeEvent
    data class NeedPassword(val wrongPasswordTried: Boolean) : CyTubeEvent
    data object PasswordAccepted : CyTubeEvent
    data object ChannelNotRegistered : CyTubeEvent
    /** partitionChange: the channel moved backend. Re-resolve socketconfig. */
    data object PartitionChanged : CyTubeEvent
    data class Kicked(val reason: String) : CyTubeEvent
    data class ErrorMessage(val message: String) : CyTubeEvent

    data class MediaChanged(val media: MediaFrame) : CyTubeEvent
    data class MediaTimeUpdate(val update: TimeUpdate) : CyTubeEvent
    data class PlaylistReplaced(val items: List<PlaylistItem>) : CyTubeEvent
    data class CurrentItemChanged(val uid: Int) : CyTubeEvent
    data class ItemQueued(val item: PlaylistItem, val afterUid: Int) : CyTubeEvent
    data class ItemDeleted(val uid: Int) : CyTubeEvent
    data class PlaylistLocked(val locked: Boolean) : CyTubeEvent
    data class PlaylistMeta(val count: Int, val totalTime: String) : CyTubeEvent

    data class Chat(val message: ChatMessage) : CyTubeEvent
    data object ChatCleared : CyTubeEvent

    data class UserListReplaced(val users: List<ChannelUser>) : CyTubeEvent
    data class UserJoined(val user: ChannelUser) : CyTubeEvent
    data class UserLeft(val name: String) : CyTubeEvent
    data class UserCount(val count: Int) : CyTubeEvent
    data class UserMetaChanged(val user: ChannelUser) : CyTubeEvent
    data class LeaderChanged(val name: String?) : CyTubeEvent

    data class Emotes(val emotes: List<Emote>) : CyTubeEvent
    data class EmoteUpdated(val emote: Emote) : CyTubeEvent
    data class EmoteRenamed(val oldName: String, val emote: Emote) : CyTubeEvent
    data class EmoteRemoved(val name: String) : CyTubeEvent
    data class PermissionsChanged(val permissions: Permissions) : CyTubeEvent
    data class MotdChanged(val html: String) : CyTubeEvent

    /** Sent both when a poll is actually created and to bring a joining
     *  client up to date on one already running. */
    data class PollOpened(val poll: Poll) : CyTubeEvent
    data class PollUpdated(val counts: List<Int>) : CyTubeEvent
    data object PollClosed : CyTubeEvent
    data class ChannelOptions(val raw: JSONObject) : CyTubeEvent
    data class Announcement(val title: String, val html: String) : CyTubeEvent
}
