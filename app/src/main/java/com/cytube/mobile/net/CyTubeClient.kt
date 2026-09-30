package com.cytube.mobile.net

import io.socket.client.IO
import io.socket.client.Manager
import io.socket.client.Socket
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
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
 * login -> joinChannel (with the channel password, if there is one). That
 * replay lives here so callers cannot forget it.
 *
 * Socket.IO calls every handler below on its own thread; the fields they
 * write are @Volatile because the main thread reads them.
 */
class CyTubeClient(
    private val http: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://cytu.be"
    }

    private val resolver = SocketConfigResolver(http, baseUrl)

    /**
     * Two queues, because they can't be treated the same when the app falls
     * behind (a busy main thread during a chat flood):
     *
     *  - Chat ("chatMsg", "pm", "clearchat") can arrive at 100 a second and
     *    is only ever shown, so it's bounded and the oldest is dropped.
     *  - Everything else changes state (the current item, the playlist, the
     *    user list, the leader, polls...). Dropping one would leave the app
     *    wrong until the next reconnect, so none are dropped. They're few:
     *    a time update every few seconds, plus whatever people do.
     *
     * Ordering is kept within each queue, which is all that matters: chat
     * goes through its own pipeline in ChannelViewModel anyway.
     */
    private val chatEvents = MutableSharedFlow<CyTubeEvent>(
        replay = 0, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val stateEvents = Channel<CyTubeEvent>(Channel.UNLIMITED)

    /** Every parsed frame. Collect it once: the state events are a queue,
     *  so a second collector would take some of them away from the first. */
    val events: Flow<CyTubeEvent> = merge(stateEvents.receiveAsFlow(), chatEvents)

    @Volatile private var socket: Socket? = null

    /** Bumped by every connect() and disconnect(). A connect() that finds it
     *  changed after its (suspending) server lookup was overtaken — by a
     *  newer connect or a disconnect — and must not open a socket, or the
     *  app ends up with a second live connection nothing can close. */
    @Volatile private var generation = 0

    // Replayed on every reconnect.
    @Volatile private var channelName: String? = null
    @Volatile private var channelPassword: String? = null
    @Volatile private var credential: Credential? = null

    @Volatile private var localUsername: String? = null
    @Volatile private var leaderName: String? = null
    /** The server confirmed our login on this connection (see "rank"). */
    @Volatile private var loginConfirmed = false

    private val isLeader: Boolean get() = leaderName != null && leaderName == localUsername

    /**
     * A logged-in session cookie (from AuthRepository's web login — no
     * password is ever held here) or a guest name.
     */
    sealed interface Credential {
        data class Cookie(val authCookie: String, val name: String) : Credential
        data class Guest(val name: String) : Credential
    }

    suspend fun connect(channel: String, credential: Credential?, password: String? = null) {
        disconnect()
        val gen = generation
        channelName = channel
        channelPassword = password
        this.credential = credential

        val serverUrl = try {
            resolver.resolve(channel)
        } catch (e: Exception) {
            // Overtaken meanwhile: the newer connect decides what's shown,
            // not this one's failure.
            if (gen != generation) return
            throw e
        }
        if (gen != generation) return

        val opts = IO.Options().apply {
            transports = arrayOf("websocket", "polling")
            reconnection = true
            // Waits between retries double from 1 s but stop at 10 s: that's
            // how often CyTube gives an IP address a connection back once
            // it's used up its allowance (see ConnectionRefused). A longer
            // wait only left the app sitting idle after the server would
            // have accepted it again. 20 attempts at up to 10 s is still a
            // few minutes of trying before it gives up.
            reconnectionDelay = 1_000L
            reconnectionDelayMax = 10_000L
            randomizationFactor = 0.5
            reconnectionAttempts = 20
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
            // A leader is only ever announced, never un-announced to someone
            // who wasn't there: if the leader left while we were
            // disconnected, the rejoin simply doesn't mention one. So forget
            // the old one now; the server sends setLeader after the join if
            // there still is one.
            leaderName = null
            loginConfirmed = false
            emit(CyTubeEvent.Connected)
            replaySession()
        }
        s.on(Socket.EVENT_DISCONNECT) {
            emit(CyTubeEvent.Disconnected)
        }
        s.on(Socket.EVENT_CONNECT_ERROR) { args ->
            // Two different things arrive here. A network or transport
            // failure comes as an exception, and Socket.IO retries by itself.
            // The server turning us down (ioserver.js middleware: its
            // per-IP connection rate limit, a ban) comes as its message, and
            // Socket.IO destroys the socket without ever retrying.
            when (val err = args.firstOrNull()) {
                is JSONObject -> emit(CyTubeEvent.ConnectionRefused(
                    err.optString("message").ifBlank { "Connection refused" }
                ))
                is String -> emit(CyTubeEvent.ConnectionRefused(err.ifBlank { "Connection refused" }))
                else -> emit(CyTubeEvent.ConnectionFailed(err?.toString() ?: "connect error"))
            }
        }
        s.io().on(Manager.EVENT_RECONNECT_ATTEMPT) { args ->
            emit(CyTubeEvent.Reconnecting((args.firstOrNull() as? Int) ?: 0))
        }
        // After reconnectionAttempts failures (a few minutes offline) the
        // Manager stops for good; without this the app sat on
        // "Reconnecting…" forever.
        s.io().on(Manager.EVENT_RECONNECT_FAILED) {
            emit(CyTubeEvent.ReconnectGaveUp)
        }

        obj(s, "login") { o ->
            val ok = o.optBoolean("success", false)
            if (ok) {
                localUsername = o.optString("name").ifBlank { null }
                loginConfirmed = true
            }
            CyTubeEvent.LoginResult(ok, o.optString("name").ifBlank { null },
                o.optString("error").ifBlank { null })
        }
        s.on("rank") { args ->
            val rank = (args.firstOrNull() as? Number)?.toDouble() ?: 0.0
            // A valid login cookie gets "login" then "rank" as soon as the
            // socket connects (user.js); an expired or revoked one gets just
            // rank -1, i.e. treated as anonymous, with nothing else said.
            if (credential is Credential.Cookie && !loginConfirmed && rank < 0) {
                emit(CyTubeEvent.SessionExpired)
            }
            emit(CyTubeEvent.RankChanged(rank))
        }
        s.on("needPassword") { args ->
            emit(CyTubeEvent.NeedPassword(args.firstOrNull() as? Boolean ?: false))
        }
        s.on("cancelNeedPassword") { emit(CyTubeEvent.PasswordAccepted) }
        s.on("partitionChange") { emit(CyTubeEvent.PartitionChanged) }
        s.on("kick") { args ->
            val reason = (args.firstOrNull() as? JSONObject)?.optString("reason")
                ?: args.firstOrNull()?.toString() ?: "Kicked"
            emit(CyTubeEvent.Kicked(reason))
        }
        obj(s, "errorMsg") { CyTubeEvent.ErrorMessage(it.optString("msg", "Unknown error")) }
        obj(s, "validationError") { CyTubeEvent.ErrorMessage(it.optString("msg", "Validation error")) }
        obj(s, "queueFail") {
            CyTubeEvent.QueueFailed(it.optString("msg", "Couldn't add that video"), it.optString("id").ifBlank { null })
        }
        // Added, but with a caveat (e.g. blocked in some countries).
        obj(s, "queueWarn") { CyTubeEvent.ErrorMessage(it.optString("msg", "Added with a warning")) }
        obj(s, "announcement") {
            CyTubeEvent.Announcement(it.optString("title", ""), it.optString("text", ""))
        }

        obj(s, "changeMedia") {
            CyTubeEvent.MediaChanged(MediaFrame.from(it), android.os.SystemClock.elapsedRealtime())
        }
        obj(s, "mediaUpdate") {
            CyTubeEvent.MediaTimeUpdate(TimeUpdate.from(it), android.os.SystemClock.elapsedRealtime())
        }
        arr(s, "playlist") { CyTubeEvent.PlaylistReplaced(PlaylistItem.listFrom(it)) }
        s.on("setCurrent") { args ->
            (args.firstOrNull() as? Number)?.let { emit(CyTubeEvent.CurrentItemChanged(it.toInt())) }
        }
        obj(s, "queue") {
            val item = it.optJSONObject("item")
            if (item == null) null
            else CyTubeEvent.ItemQueued(PlaylistItem.from(item), playlistPosition(it))
        }
        obj(s, "moveVideo") {
            val uid = it.optInt("from", -1)
            if (uid < 0) null else CyTubeEvent.ItemMoved(uid, playlistPosition(it))
        }
        obj(s, "delete") { CyTubeEvent.ItemDeleted(it.optInt("uid", -1)) }
        s.on("setPlaylistLocked") { args ->
            emit(CyTubeEvent.PlaylistLocked(args.firstOrNull() as? Boolean ?: false))
        }

        obj(s, "chatMsg") { CyTubeEvent.Chat(ChatMessage.from(it)) }
        obj(s, "pm") { CyTubeEvent.Chat(ChatMessage.from(it, isPm = true)) }
        s.on("clearchat") { emit(CyTubeEvent.ChatCleared) }

        arr(s, "userlist") { CyTubeEvent.UserListReplaced(ChannelUser.listFrom(it)) }
        obj(s, "addUser") { CyTubeEvent.UserJoined(ChannelUser.from(it)) }
        obj(s, "setUserMeta") {
            val name = it.optString("name")
            val meta = it.optJSONObject("meta")
            if (name.isBlank() || meta == null) null
            else CyTubeEvent.UserAfkChanged(name, meta.optBoolean("afk", false))
        }
        obj(s, "setUserRank") {
            val name = it.optString("name")
            if (name.isBlank() || !it.has("rank")) null
            else CyTubeEvent.UserRankChanged(name, it.optDouble("rank", 0.0))
        }
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
        // The server withdrew our skip vote (we went AFK: user.js setAFK).
        s.on("clearVoteskipVote") { emit(CyTubeEvent.VoteskipVoteCleared) }
        // Sent on join and whenever a moderator changes the channel settings
        // (src/channel/opts.js). The server's own default is on.
        obj(s, "channelOpts") { CyTubeEvent.VoteskipAllowed(it.optBoolean("allow_voteskip", true)) }
        obj(s, "voteskip") {
            CyTubeEvent.VoteskipCount(it.optInt("count", 0), it.optInt("need", 0))
        }
        s.on("setMotd") { args ->
            emit(CyTubeEvent.MotdChanged(args.firstOrNull() as? String ?: ""))
        }

        obj(s, "newPoll") { CyTubeEvent.PollOpened(Poll.from(it)) }
        obj(s, "updatePoll") {
            val counts = it.optJSONArray("counts")
            CyTubeEvent.PollUpdated(
                Poll.parseCounts(counts, counts?.length() ?: 0),
                Poll.countsHiddenFromOthers(counts)
            )
        }
        s.on("closePoll") { emit(CyTubeEvent.PollClosed) }
    }

    /** Runs on every connect, first or otherwise. */
    private fun replaySession() {
        val s = socket ?: return
        // A cookie login needs no frame: ioserver.js authUserMiddleware
        // checks the cookie during the handshake, and the server then says
        // "login" itself (or, for a dead cookie, nothing — see "rank").
        (credential as? Credential.Guest)?.let { s.emit("login", JSONObject().put("name", it.name)) }
        // The password goes in the join itself (accesscontrol.js checks
        // data.pw). Sent as a separate "channelPassword" frame straight
        // after, it arrived before the server was listening for one and was
        // dropped, so every reconnect asked for the password again.
        channelName?.let { name ->
            s.emit("joinChannel", JSONObject().put("name", name).apply {
                channelPassword?.let { put("pw", it) }
            })
        }
    }

    // ---- outbound ----

    /**
     * Re-sends the guest login frame without tearing the socket down: with a
     * new [name] after the server rejects one (usually "already in use"), or
     * with the same name (null) after a wait. No-op if we are not actually a
     * guest.
     */
    fun retryGuestLogin(name: String? = null) {
        val current = credential as? Credential.Guest ?: return
        val next = name ?: current.name
        credential = Credential.Guest(next)
        socket?.emit("login", JSONObject().put("name", next))
    }

    fun sendChat(message: String) {
        socket?.emit("chatMsg", JSONObject().apply {
            put("msg", message)
            put("meta", JSONObject())
        })
    }

    /** A private message. CyTube echoes it back to us as a "pm" frame too
     *  (with `to` set), which is what puts it in our own chat. */
    fun sendPm(to: String, message: String) {
        socket?.emit("pm", JSONObject().apply {
            put("to", to)
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
    fun jumpTo(uid: Int) { socket?.emit("jumpTo", uid) }
    /** One vote to skip the current item. The server counts one vote per IP
     *  and quietly ignores it if voteskip is off or our rank can't vote. */
    fun voteSkip() { socket?.emit("voteskip") }
    fun deleteItem(uid: Int) { socket?.emit("delete", uid) }

    fun queue(id: String, type: String, atEnd: Boolean = true, temp: Boolean = false) {
        socket?.emit("queue", JSONObject().apply {
            put("id", id); put("type", type)
            put("pos", if (atEnd) "end" else "next")
            put("temp", temp)
        })
    }

    /**
     * Leader-only: push our clock upward, the same frame the website sends.
     * [id] (and [type]) name the item this time belongs to: the server
     * rejects the frame without an id, and ignores it when the id isn't the
     * channel's current item.
     */
    fun sendMediaUpdate(id: String, type: String, currentTime: Double, paused: Boolean) {
        if (!isLeader) return
        socket?.emit("mediaUpdate", JSONObject().apply {
            put("id", id)
            put("type", type)
            put("currentTime", currentTime)
            put("paused", paused)
        })
    }

    fun disconnect() {
        generation++
        socket?.let {
            it.off()
            it.disconnect()
            it.close()
        }
        socket = null
        leaderName = null
        channelName = null
        channelPassword = null
        credential = null
        localUsername = null
        loginConfirmed = false
    }

    // ---- helpers ----

    private fun playlistPosition(o: JSONObject): Int = PlaylistPosition.parse(o.opt("after"))

    private fun emit(e: CyTubeEvent) {
        if (e is CyTubeEvent.Chat || e is CyTubeEvent.ChatCleared) chatEvents.tryEmit(e)
        else stateEvents.trySend(e)
    }

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
