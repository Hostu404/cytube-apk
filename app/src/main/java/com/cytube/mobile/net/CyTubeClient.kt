package com.cytube.mobile.net

import io.socket.client.IO
import io.socket.client.Manager
import io.socket.client.Socket
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * The CyTube protocol client. Owns the socket and nothing else — it knows no
 * Android types and no UI types, and emits parsed frames as a flow.
 *
 * Design note on rejoining: Socket.IO reconnects by itself, but CyTube does NOT
 * restore session or channel membership on its own. Every "connect" must replay
 * login -> joinChannel -> channelPassword. That replay lives here so callers
 * cannot forget it.
 */
class CyTubeClient(
    private val http: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://cytu.be"
    }

    private val resolver = SocketConfigResolver(http, baseUrl)

    private val _events = MutableSharedFlow<CyTubeEvent>(
        replay = 0, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<CyTubeEvent> = _events.asSharedFlow()

    private var socket: Socket? = null

    // Replayed on every reconnect.
    private var channelName: String? = null
    private var channelPassword: String? = null
    private var credential: Credential? = null

    var localUsername: String? = null
        private set
    var localRank: Double = 0.0
        private set
    var leaderName: String? = null
        private set

    val isLeader: Boolean get() = leaderName != null && leaderName == localUsername

    /**
     * Either a session cookie (preferred — no password held in memory) or a
     * name/password pair for the socket login frame.
     */
    sealed interface Credential {
        data class Cookie(val authCookie: String, val name: String) : Credential
        data class Password(val name: String, val password: String) : Credential
        data class Guest(val name: String) : Credential
    }

    suspend fun connect(channel: String, credential: Credential?, password: String? = null) {
        disconnect()
        channelName = channel
        channelPassword = password
        this.credential = credential

        val serverUrl = resolver.resolve(channel)

        val opts = IO.Options().apply {
            transports = arrayOf("websocket", "polling")
            reconnection = true
            reconnectionDelay = 1_000
            reconnectionDelayMax = 15_000
            // Unbounded: a channel is a long-lived session and users expect it
            // to come back after a tunnel or a lift.
            reconnectionAttempts = Int.MAX_VALUE
            timeout = 20_000
            (credential as? Credential.Cookie)?.let {
                extraHeaders = mapOf("Cookie" to listOf("auth=${it.authCookie}"))
            }
        }

        val s = IO.socket(URI.create(serverUrl), opts)
        socket = s
        wire(s)
        s.connect()
    }

    private fun wire(s: Socket) {
        s.on(Socket.EVENT_CONNECT) {
            emit(CyTubeEvent.Connected)
            replaySession()
        }
        s.on(Socket.EVENT_DISCONNECT) { emit(CyTubeEvent.Disconnected) }
        s.on(Socket.EVENT_CONNECT_ERROR) { args ->
            emit(CyTubeEvent.ConnectionFailed(args.firstOrNull()?.toString() ?: "connect error"))
        }
        s.io().on(Manager.EVENT_RECONNECT_ATTEMPT) { args ->
            emit(CyTubeEvent.Reconnecting((args.firstOrNull() as? Int) ?: 0))
        }

        obj(s, "login") { o ->
            val ok = o.optBoolean("success", false)
            if (ok) localUsername = o.optString("name").ifBlank { null }
            CyTubeEvent.LoginResult(ok, o.optString("name").ifBlank { null },
                o.optString("error").ifBlank { null })
        }
        s.on("rank") { args ->
            val r = (args.firstOrNull() as? Number)?.toDouble() ?: 0.0
            localRank = r
            emit(CyTubeEvent.RankChanged(r))
        }
        s.on("needPassword") { args ->
            emit(CyTubeEvent.NeedPassword(args.firstOrNull() as? Boolean ?: false))
        }
        s.on("cancelNeedPassword") { emit(CyTubeEvent.PasswordAccepted) }
        s.on("channelNotRegistered") { emit(CyTubeEvent.ChannelNotRegistered) }
        s.on("partitionChange") { emit(CyTubeEvent.PartitionChanged) }
        s.on("kick") { args ->
            val reason = (args.firstOrNull() as? JSONObject)?.optString("reason")
                ?: args.firstOrNull()?.toString() ?: "Kicked"
            emit(CyTubeEvent.Kicked(reason))
        }
        obj(s, "errorMsg") { CyTubeEvent.ErrorMessage(it.optString("msg", "Unknown error")) }
        obj(s, "validationError") { CyTubeEvent.ErrorMessage(it.optString("msg", "Validation error")) }
        obj(s, "queueFail") { CyTubeEvent.ErrorMessage(it.optString("msg", "Queue failed")) }
        obj(s, "announcement") {
            CyTubeEvent.Announcement(it.optString("title", ""), it.optString("text", ""))
        }

        obj(s, "changeMedia") { CyTubeEvent.MediaChanged(MediaFrame.from(it)) }
        obj(s, "mediaUpdate") { CyTubeEvent.MediaTimeUpdate(TimeUpdate.from(it)) }
        arr(s, "playlist") { CyTubeEvent.PlaylistReplaced(PlaylistItem.listFrom(it)) }
        s.on("setCurrent") { args ->
            (args.firstOrNull() as? Number)?.let { emit(CyTubeEvent.CurrentItemChanged(it.toInt())) }
        }
        obj(s, "queue") {
            val item = it.optJSONObject("item")
            if (item == null) null
            else CyTubeEvent.ItemQueued(PlaylistItem.from(item), it.optInt("after", -1))
        }
        obj(s, "delete") { CyTubeEvent.ItemDeleted(it.optInt("uid", -1)) }
        s.on("setPlaylistLocked") { args ->
            emit(CyTubeEvent.PlaylistLocked(args.firstOrNull() as? Boolean ?: false))
        }
        obj(s, "setPlaylistMeta") {
            CyTubeEvent.PlaylistMeta(it.optInt("count", 0), it.optString("time", ""))
        }

        obj(s, "chatMsg") { CyTubeEvent.Chat(ChatMessage.from(it)) }
        obj(s, "pm") { CyTubeEvent.Chat(ChatMessage.from(it, isPm = true)) }
        s.on("clearchat") { emit(CyTubeEvent.ChatCleared) }

        arr(s, "userlist") { CyTubeEvent.UserListReplaced(ChannelUser.listFrom(it)) }
        obj(s, "addUser") { CyTubeEvent.UserJoined(ChannelUser.from(it)) }
        obj(s, "setUserMeta") { CyTubeEvent.UserMetaChanged(ChannelUser.from(it)) }
        s.on("userLeave") { args ->
            (args.firstOrNull() as? JSONObject)?.optString("name")
                ?.let { emit(CyTubeEvent.UserLeft(it)) }
        }
        s.on("usercount") { args ->
            (args.firstOrNull() as? Number)?.let { emit(CyTubeEvent.UserCount(it.toInt())) }
        }
        s.on("setLeader") { args ->
            val n = (args.firstOrNull() as? String)?.ifBlank { null }
            leaderName = n
            emit(CyTubeEvent.LeaderChanged(n))
        }

        arr(s, "emoteList") { CyTubeEvent.Emotes(Emote.listFrom(it)) }
        // A channel can add, rename or drop an emote mid-session; the official
        // client patches CHANNEL.emotes in place rather than refetching.
        obj(s, "updateEmote") { CyTubeEvent.EmoteUpdated(Emote.from(it)) }
        obj(s, "renameEmote") {
            CyTubeEvent.EmoteRenamed(it.optString("old", ""), Emote.from(it))
        }
        obj(s, "removeEmote") { CyTubeEvent.EmoteRemoved(it.optString("name", "")) }
        obj(s, "setPermissions") { CyTubeEvent.PermissionsChanged(Permissions(it)) }
        obj(s, "channelOpts") { CyTubeEvent.ChannelOptions(it) }
        s.on("setMotd") { args ->
            emit(CyTubeEvent.MotdChanged(args.firstOrNull() as? String ?: ""))
        }

        obj(s, "newPoll") { CyTubeEvent.PollOpened(Poll.from(it)) }
        obj(s, "updatePoll") {
            val counts = it.optJSONArray("counts")
            CyTubeEvent.PollUpdated(Poll.parseCounts(counts, counts?.length() ?: 0))
        }
        s.on("closePoll") { emit(CyTubeEvent.PollClosed) }
    }

    /** Runs on every connect, first or otherwise. */
    private fun replaySession() {
        val s = socket ?: return
        when (val c = credential) {
            is Credential.Password -> s.emit("login", JSONObject().apply {
                put("name", c.name); put("pw", c.password)
            })
            is Credential.Guest -> s.emit("login", JSONObject().put("name", c.name))
            is Credential.Cookie -> {
                // The auth cookie was verified during the handshake by
                // ioserver.js authUserMiddleware; no login frame is needed.
                localUsername = c.name
            }
            null -> Unit
        }
        channelName?.let { s.emit("joinChannel", JSONObject().put("name", it)) }
        channelPassword?.let { s.emit("channelPassword", it) }
    }

    // ---- outbound ----

    /**
     * Re-sends the login frame with a new name after the server rejects a
     * guest name (usually "already in use"), without tearing the socket down.
     * No-op if we are not actually a guest — an account login failing is a bad
     * password, which a new name can't fix.
     */
    fun retryGuestLogin(name: String) {
        if (credential !is Credential.Guest) return
        credential = Credential.Guest(name)
        socket?.emit("login", JSONObject().put("name", name))
    }

    fun sendChat(message: String) {
        socket?.emit("chatMsg", JSONObject().apply {
            put("msg", message)
            put("meta", JSONObject())
        })
    }

    fun sendPassword(password: String) {
        channelPassword = password
        socket?.emit("channelPassword", password)
    }

    /**
     * Emitted once a player backend is ready. The server replies with a fresh
     * media update immediately, so we resync at once instead of waiting out the
     * remainder of the ~5s broadcast interval.
     */
    fun signalPlayerReady() { socket?.emit("playerReady") }

    fun requestPlaylist() { socket?.emit("requestPlaylist") }
    fun vote(option: Int) { socket?.emit("vote", JSONObject().put("option", option)) }
    fun voteSkip() { socket?.emit("voteskip") }
    fun setAfk() { socket?.emit("setAFK") }
    fun jumpTo(uid: Int) { socket?.emit("jumpTo", uid) }
    fun deleteItem(uid: Int) { socket?.emit("delete", uid) }
    fun playNext() { socket?.emit("playNext") }

    fun queue(id: String, type: String, atEnd: Boolean = true, temp: Boolean = false) {
        socket?.emit("queue", JSONObject().apply {
            put("id", id); put("type", type)
            put("pos", if (atEnd) "end" else "next")
            put("temp", temp)
        })
    }

    /** Leader-only: push our clock upward. Mirrors the desktop client. */
    fun sendMediaUpdate(currentTime: Double, paused: Boolean) {
        if (!isLeader) return
        socket?.emit("mediaUpdate", JSONObject().apply {
            put("currentTime", currentTime); put("paused", paused)
        })
    }

    fun disconnect() {
        socket?.let {
            it.off()
            it.disconnect()
            it.close()
        }
        socket = null
        leaderName = null
    }

    // ---- helpers ----

    private fun emit(e: CyTubeEvent) { _events.tryEmit(e) }

    private inline fun obj(s: Socket, name: String, crossinline map: (JSONObject) -> CyTubeEvent?) {
        s.on(name) { args ->
            val o = args.firstOrNull() as? JSONObject ?: return@on
            runCatching { map(o) }.getOrNull()?.let { emit(it) }
        }
    }

    private inline fun arr(s: Socket, name: String, crossinline map: (JSONArray) -> CyTubeEvent?) {
        s.on(name) { args ->
            val a = args.firstOrNull() as? JSONArray ?: return@on
            runCatching { map(a) }.getOrNull()?.let { emit(it) }
        }
    }
}
