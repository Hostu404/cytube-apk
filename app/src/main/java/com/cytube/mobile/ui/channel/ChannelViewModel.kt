package com.cytube.mobile.ui.channel

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.cytube.mobile.AppVisibility
import com.cytube.mobile.data.CHANNEL_NAME_REGEX
import com.cytube.mobile.data.CompatMode
import com.cytube.mobile.data.Settings
import com.cytube.mobile.data.SettingsStore
import com.cytube.mobile.di.Graph
import com.cytube.mobile.net.*
import com.cytube.mobile.player.BandwidthEstimate
import com.cytube.mobile.player.NativePlayerHandle
import com.cytube.mobile.player.PlaybackFailures
import com.cytube.mobile.player.SubtitleOptions
import com.cytube.mobile.player.PlayerHandle
import com.cytube.mobile.player.StreamResolvers
import com.cytube.mobile.ui.defaultSyncAccuracy
import com.cytube.mobile.ui.isTvDevice
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

enum class ConnectionState { CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, FAILED }

/**
 * Poll votes cast from this app, kept for the rest of the app session, so
 * leaving a channel (or dropping connection) and coming back doesn't lose them.
 *
 * CyTube removes a user's vote when they leave the channel, unless the poll
 * was created with "keep votes" — a setting the server never sends to
 * viewers. So when the same poll is sent again on rejoin, the app casts the
 * same vote again (see ChannelViewModel's PollOpened handling). If the server
 * did keep it, re-casting the same choice changes nothing.
 */
private object PollVoteMemory {
    private val votes = LinkedHashMap<String, Int>()
    private val announced = LinkedHashSet<String>()
    private const val MAX = 50

    /** True the first time a poll is seen this app session. The server
     *  re-sends the open poll every time you join a channel, and the
     *  "opened a poll" chat notice was repeating on every rejoin. */
    @Synchronized fun firstSighting(key: String): Boolean {
        if (!announced.add(key)) return false
        while (announced.size > MAX) announced.remove(announced.first())
        return true
    }

    @Synchronized fun get(key: String): Int? = votes[key]

    @Synchronized fun put(key: String, option: Int) {
        votes.remove(key)
        votes[key] = option
        while (votes.size > MAX) votes.remove(votes.keys.first())
    }
}

/**
 * Site-wide announcements already shown, kept between runs. Unlike a poll,
 * an announcement isn't tied to a channel: the server sends the current one
 * on joining any channel, so without this it showed up again in every
 * channel, every visit, for as long as it stayed up (often days). Once
 * shown, it isn't shown again anywhere; a new or edited one is.
 */
private object AnnouncementMemory {
    private const val PREFS = "announcements"
    private const val KEY_SEEN = "seen"
    private const val MAX = 20

    /** True the first time [key] is seen on this device, and remembers it. */
    @Synchronized fun firstSighting(context: android.content.Context, key: String): Boolean {
        val id = key.hashCode().toString()
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val seen = prefs.getString(KEY_SEEN, null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        if (id in seen) return false
        val updated = (seen + id).takeLast(MAX)
        prefs.edit().putString(KEY_SEEN, updated.joinToString(",")).apply()
        return true
    }
}

/** Identifies one poll in one channel, across rejoins (the server keeps its
 *  creation timestamp). */
private fun pollVoteKey(channel: String, poll: Poll) = "$channel|${poll.timestamp}|${poll.title}"

/** Private messages received that haven't been seen yet — see
 *  ChannelViewModel.markPmsRead. [from] is the most recent sender. */
data class UnreadPm(val from: String, val count: Int)

/** Progress/result of adding a video from the playlist panel's link box. */
data class QueueStatus(val message: String, val isError: Boolean = false, val inProgress: Boolean = false)

data class ChannelUiState(
    val channel: String = "",
    val connection: ConnectionState = ConnectionState.CONNECTING,
    val statusMessage: String? = null,
    val needsPassword: Boolean = false,
    val passwordWasWrong: Boolean = false,
    val media: MediaFrame? = null,
    // PersistentList rather than plain List: a plain List/Map parameter is
    // exactly the case Compose's compiler can't prove is safe to skip (it
    // could secretly be a MutableList mutated in place), so every composable
    // taking one — ChatPanel, PlaylistPanel, UsersPanel, NekoChatOverlay —
    // was forced non-skippable and fully recomposed on EVERY emission of
    // this state, not just when its own field actually changed. A single
    // incoming chat message was enough to force the playlist and user list
    // to redo their own composition too. PersistentList is in Compose's own
    // hardcoded list of known-stable types, so this fixes that; it's also a
    // genuine structural-sharing collection (see appendChat), so updates no
    // longer copy the whole list either.
    val playlist: PersistentList<PlaylistItem> = persistentListOf(),
    val currentUid: Int = -1,
    val users: PersistentList<ChannelUser> = persistentListOf(),
    val userCount: Int = 0,
    val messages: PersistentList<ChatMessage> = persistentListOf(),
    val emotes: EmoteSet = EmoteSet.EMPTY,
    /** User list name colours from the channel's CSS (see ChannelStyle). */
    val nameColors: com.cytube.mobile.net.NameColors = com.cytube.mobile.net.NameColors.NONE,
    val showEmotes: Boolean = true,
    /** Mirrors the Settings toggle (off by default, same as Settings);
     *  MainActivity reads this (via PlaybackHost) to decide whether leaving
     *  the app should float the video in PiP. */
    val pipEnabled: Boolean = false,
    /** Mirrors the Settings toggle for the ambient glow behind the windowed
     *  player (see ChannelScreen's ambient-color capture). */
    val ambientGlowEnabled: Boolean = true,
    /** User-toggled audio mute, independent of play/pause. Applied to the
     *  active PlayerHandle whenever one is attached (see attachPlayer) so a
     *  media switch never silently un-mutes. */
    val muted: Boolean = false,
    val leader: String? = null,
    val localUser: String? = null,
    val localRank: Double = 0.0,
    val motd: String = "",
    val effectiveMode: CompatMode = CompatMode.AUTOMATIC,
    val player: MediaTypes.Player = MediaTypes.Player.NATIVE,
    /** True while media is actually playing; drives keep-screen-on. */
    val playing: Boolean = false,
    val motdExpanded: Boolean = false,
    val isFavourite: Boolean = false,
    val kicked: String? = null,
    /** Non-null when a mounted player backend failed and we're asking whether
     *  to fall back to WebView. Only reached when there's no single-video
     *  fallback to try instead — see ChannelViewModel.reportPlaybackFailure,
     *  which switches straight to EMBED with no prompt when one exists. */
    val playbackOffer: String? = null,
    /** Non-null when a freshly-selected item has no player in the app at all
     *  (e.g. Twitch) and we're asking whether to load it in WebView. Distinct from [playbackOffer], which is
     *  only for a backend that was actually running and then failed. */
    val compatOffer: String? = null,
    val refreshing: Boolean = false,
    /** Which entry of the current item's MediaFrame.direct (already sorted
     *  highest-to-lowest — see DirectSource.parse) the NATIVE backend should
     *  load; 0 matches MediaFrame.bestSource. Stepped down by
     *  onPlaybackStall on a real mid-playback stall, and latched there for
     *  the rest of the item to avoid decoder-reset flapping. Chosen afresh
     *  for every genuine item change (resolveInitialQualityIndex, which
     *  applies the session's quality cap and may step it back up).
     *  Meaningless for any player type other than NATIVE. */
    val nativeQualityIndex: Int = 0,
    /** The channel's current poll, or the last one after it closes (see
     *  Poll.closed) until the next opens or it's dismissed; null if none. */
    val poll: Poll? = null,
    /** Whether the channel's "pollvote" permission lets us vote. The server
     *  silently ignores votes from anyone below it, so the panel disables
     *  voting rather than showing a vote that was never counted. True until
     *  the channel's permissions arrive. */
    val canVotePoll: Boolean = true,
    /** Which option the local user tapped this session. The server has no
     *  "your vote" field (see CyTube's own client, which tracks this the same
     *  way — button state only), so this is reset whenever the poll changes. */
    val myPollVote: Int? = null,
    /** Mirrors the Settings "stay in sync" toggle (Prefs.syncEnabled). Off is
     *  what PlaylistPanel uses to switch a tap from the moderator-only
     *  jumpTo to personal picking — see ChannelViewModel.pickPersonal. */
    val syncEnabled: Boolean = true,
    /** True while [media] is something the LOCAL user personally picked from
     *  the playlist (see pickPersonal) rather than the channel's own current
     *  item — only ever true while [syncEnabled] is false. [channelCurrentMedia]
     *  keeps tracking the real thing under it the whole time, so turning sync
     *  back on (the only way a pick ends) can snap straight back. */
    val personalPickActive: Boolean = false,
    /** The playlist uid personal picking currently has loaded, or -1 when
     *  none — separate from [currentUid], which is always the channel's own
     *  real current item regardless of what's personally picked. */
    val personalPickUid: Int = -1,
    /** The channel's own real current item, kept up to date by every
     *  changeMedia even while [personalPickActive] is true and [media] is
     *  showing something else entirely — see onMediaChanged/stopPersonalPick. */
    val channelCurrentMedia: MediaFrame? = null,
    /** Whether this user may add videos / add them to play next, per the
     *  channel's permissions, their rank and whether the playlist is locked
     *  (see ChannelViewModel.refreshQueuePermissions). The server silently
     *  ignores a queue request from someone without permission, so the add
     *  box is only shown when this is true. */
    val canQueue: Boolean = false,
    val canQueueNext: Boolean = false,
    /** Whether the channel allows vote skipping and our rank may vote (the
     *  server ignores a vote otherwise, so the button is hidden). */
    val canVoteskip: Boolean = false,
    /** We voted to skip the channel's current item; cleared when it changes
     *  (the server resets the vote then) or when we reconnect (leaving the
     *  channel withdraws the vote server-side). */
    val votedSkip: Boolean = false,
    /** The tally, for ranks the server shows it to; null otherwise, or when
     *  no vote is running. */
    val voteskipTally: VoteskipTally? = null,
    /** Shown under the playlist panel's add box; null when there's nothing to say. */
    val queueStatus: QueueStatus? = null,
    /** Who the chat box is currently sending private messages to, or null
     *  for normal public chat — see startPm/cancelPm. Phone only; TV never
     *  sets it. */
    val pmTarget: String? = null,
    val unreadPm: UnreadPm? = null,
    /** The playing item's subtitles, for the CC button; see
     *  NativePlayerHandle.onSubtitlesChanged. */
    val subtitles: SubtitleOptions = SubtitleOptions.NONE
) {
    val isLeader: Boolean get() = leader != null && leader == localUser
}

/** [need] is what the server reports, which leaves out its own "at least one
 *  vote" floor — hence the max, so a quiet channel reads 0/1, not 0/0. */
data class VoteskipTally(val count: Int, val need: Int) {
    override fun toString(): String = "$count/${maxOf(1, need)}"
}

class ChannelViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = SettingsStore(app)
    private val client = Graph.newClient()
    private val sync = SyncEngine().apply { log = { Log.i(SYNC_TAG, it) } }
    /** TV chat shows spoilers revealed (no tap to reveal them with). */
    private val isTv = isTvDevice(app)

    private val _state = MutableStateFlow(ChannelUiState())
    val state: StateFlow<ChannelUiState> = _state.asStateFlow()

    /**
     * The player the surface attached last (attachPlayer), or null once that
     * player has been released. Surfaces never report "detached" as a
     * separate call: when the player type changes, Compose creates the new
     * surface (which attaches its player) before it disposes the old one,
     * so a "set to null on dispose" from the old surface would wipe out the
     * new player, leaving it with no sync corrections or mute.
     */
    private var player: PlayerHandle? = null
        get() = field?.takeUnless { it.isReleased }
    /** elapsedRealtime() of the last genuinely new player attach, so
     *  evaluateSync can tell SyncEngine to give a fresh item a moment before
     *  correcting it (see SYNC_GRACE_MS). */
    private var playerAttachedAtMs: Long = 0L
    /** The item [player] held when that was last set. The same player carries
     *  on from item to item, so a new item on it also counts as an attach. */
    private var attachedMediaId: String? = null
    /** elapsedRealtime() of the last quality step — the debounce for
     *  onPlaybackStall measures against this. Reset to 0 whenever
     *  ChannelUiState.nativeQualityIndex itself resets (a genuine item
     *  change), so a fresh item's first stall isn't held back by a cooldown
     *  that belonged to the previous video. */
    private var lastQualityChangeAtMs: Long = 0L
    private var qualityUpgradeAttempts: Int = 0
    /** The current item's sources (indices into media.direct) that failed
     *  as broken files; see tryNextSource. */
    private val failedSourceIndices = mutableSetOf<Int>()
    /** Session-remembered preferred resolution height (e.g. 720, 480).
     *  Maintains a realistic quality ceiling across playlist items so the player
     *  doesn't re-stall on every item on a bandwidth-constrained connection. */
    private var sessionPreferredQualityHeight: Int? = null
    /** When playback last started running stall-free at the current quality
     *  cap: set by a stall step-down or a step-up, and on the first item.
     *  NOT reset on every new item, or the "stable for 2 minutes" step-up
     *  check would only ever measure the previous item, and on a playlist of
     *  short videos quality would never come back up. */
    private var qualityStableSinceMs: Long = 0L
    private var settings: Settings = Settings(syncAccuracy = defaultSyncAccuracy(app))
    private var leaderTicker: Job? = null
    private var syncTicker: Job? = null
    private var syncJob: Job? = null
    /** A background load waiting on a resolver — see loadWhileInBackground. */
    private var backgroundLoadJob: Job? = null
    /** Socket.IO ran out of reconnection attempts (CyTubeEvent.ReconnectGaveUp)
     *  and nothing has reconnected since — see onAppVisibilityChanged. */
    private var reconnectGaveUp = false
    /** Reading the channel's latest CSS — see the ChannelCss branch. */
    private var channelCssJob: Job? = null
    /** The last announcement shown — see the Announcement branch. */
    private var lastAnnouncementKey: String? = null
    private var lastServerTimeSeconds: Double = 0.0
    private var lastServerTimeElapsedRealtimeMs: Long = 0L
    private var isServerPaused: Boolean = false
    private var joined = false
    private var chatSeq = 0L
    private var guestRetries = 0
    /** Fingerprints of recently-appended chat messages — see the dedupe check
     *  in applyChat. Bounded to MAX_CHAT_MESSAGES so
     *  a long session can't grow this without limit. */
    private val seenChatFingerprints = LinkedHashSet<String>()

    fun start(channel: String) {
        if (joined) return
        // The channel name reaches here from user typing (Home's direct-entry
        // box already validates it) but ALSO from a cytu.be/r/<name> deep link
        // — any other installed app can fire that Intent with any string,
        // since MainActivity is exported. socketconfig lookup below builds a
        // URL by string concatenation ("$baseUrl/socketconfig/$channel.json"),
        // so an unvalidated name could path-traverse within cytu.be's own
        // routes. Reject anything outside CyTube's own channel-name charset
        // before it reaches a single network call.
        if (!CHANNEL_NAME_REGEX.matches(channel)) {
            _state.value = _state.value.copy(
                connection = ConnectionState.FAILED,
                statusMessage = "\"$channel\" isn't a valid channel name"
            )
            return
        }
        joined = true
        _state.value = _state.value.copy(channel = channel)
        restoreLeadMemory()

        viewModelScope.launch {
            settings = settingsStore.settings.first()
            val perChannel = settingsStore.channelCompat(channel).first()
            _state.value = _state.value.copy(
                effectiveMode = perChannel ?: settings.compatMode,
                showEmotes = settings.showEmotes,
                pipEnabled = settings.pipEnabled,
                ambientGlowEnabled = settings.ambientGlowEnabled,
                syncEnabled = settings.syncEnabled,
                isFavourite = settingsStore.favourites.first().contains(channel)
            )
            settingsStore.noteVisit(channel)

            launch {
                AppVisibility.inBackground.collect { onAppVisibilityChanged(it) }
            }

            launch {
                settingsStore.settings.collect {
                    val wasSyncEnabled = settings.syncEnabled
                    val prevAccuracy = settings.syncAccuracy
                    settings = it
                    update { s ->
                        if (it.showEmotes == s.showEmotes && it.pipEnabled == s.pipEnabled &&
                            it.ambientGlowEnabled == s.ambientGlowEnabled &&
                            it.syncEnabled == s.syncEnabled
                        ) s
                        else s.copy(
                            showEmotes = it.showEmotes,
                            pipEnabled = it.pipEnabled,
                            ambientGlowEnabled = it.ambientGlowEnabled,
                            syncEnabled = it.syncEnabled
                        )
                    }
                    // Sync flipped back on while personally browsing: snap
                    // straight back to the channel's real current item — see
                    // stopPersonalPick. A personal pick left dangling here
                    // would keep showing a video the rest of the channel
                    // was never watching, now with sync silently back on.
                    if (it.syncEnabled && !wasSyncEnabled) {
                        stopPersonalPick()
                        evaluateSync()
                    } else if (it.syncAccuracy != prevAccuracy) {
                        evaluateSync()
                    }
                }
            }
            launch { observeEvents() }
            launch(Dispatchers.Default) { runChatPump() }
            startSyncTicker()

            connect(channel)
        }
    }

    private suspend fun connect(channel: String) {
        playerAttachedAtMs = SystemClock.elapsedRealtime()
        _state.value = _state.value.copy(connection = ConnectionState.CONNECTING)
        guestRetries = 0
        guestLoginRetryJob?.cancel()
        refusedRetryJob?.cancel()
        refusedRetryJob = null
        val credential = savedCredential() ?: CyTubeClient.Credential.Guest(guestName())
        try {
            // The channel password too, so the app's own reconnects (refresh,
            // retry, coming back after a long time away) don't ask again.
            client.connect(channel, credential, channelPassword)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                connection = ConnectionState.FAILED,
                statusMessage = e.message ?: "Could not reach the channel"
            )
        }
    }

    /** The channel password the user entered this visit, for rejoining. */
    private var channelPassword: String? = null

    /** The saved login was refused once already; see SessionExpired. */
    private var loginRefusedOnce = false

    /** Waiting out the server's one-guest-login-a-minute limit; see LoginResult. */
    private var guestLoginRetryJob: Job? = null

    /** A fresh connection scheduled after the server refused one; see
     *  onConnectionRefused. connect() cancels it, so a manual retry (tapping
     *  the channel name) doesn't lead to a second connection. */
    private var refusedRetryJob: Job? = null

    /**
     * The server turned the connection down, and Socket.IO won't retry by
     * itself after that (it only retries network failures): without this
     * the app would sit on "Reconnecting…" until the channel was left and
     * rejoined.
     *
     * The usual reason is CyTube's per-IP connection rate limit: each IP
     * address gets 5 connections, and one back every 10 s (ioserver.js
     * ipThrottleMiddleware). Every channel join and reconnect counts, from
     * every device on the same network, so hopping between a few channels
     * uses it up. Connect again once one is back. A ban is final: say so
     * and stop.
     */
    private fun onConnectionRefused(reason: String) {
        Log.w(TAG, "server refused the connection: $reason")
        refusedRetryJob?.cancel()
        if (reason.contains("banned", ignoreCase = true)) {
            update { it.copy(connection = ConnectionState.FAILED, statusMessage = reason) }
            return
        }
        val seconds = REFUSED_RETRY_MS / 1000
        update {
            it.copy(
                connection = ConnectionState.RECONNECTING,
                statusMessage = if (reason.contains("rate limit", ignoreCase = true)) {
                    "Too many connections from your network — retrying in ${seconds}s…"
                } else {
                    "$reason — retrying in ${seconds}s…"
                }
            )
        }
        refusedRetryJob = viewModelScope.launch {
            delay(REFUSED_RETRY_MS)
            // Cleared first: connect() cancels a pending retry, and this is it.
            refusedRetryJob = null
            val s = _state.value
            // Moved to Compatibility View or kicked meanwhile: stay away.
            if (s.effectiveMode == CompatMode.WEB || s.kicked != null ||
                s.connection == ConnectionState.CONNECTED
            ) return@launch
            client.disconnect()
            connect(s.channel)
        }
    }

    private suspend fun observeEvents() {
        client.events.collect { event ->
            // A bug in any single branch below must not kill this collector —
            // there's no restart, so an uncaught exception here would silently
            // stop all future chat/playlist/user updates for the rest of the
            // channel session (or crash the process outright). Cancellation
            // (the screen closing) is let through: some branches suspend.
            try {
            when (event) {
                is CyTubeEvent.Connected -> {
                    reconnectGaveUp = false
                    // Anything the server only announces as it happens may
                    // have changed while we were disconnected. The join that
                    // follows re-sends what's still true (setLeader if there
                    // is a leader, newPoll if a poll is open), but never what
                    // stopped being true — so forget the leader, and treat an
                    // open poll as closed until the server says otherwise.
                    update {
                        it.copy(
                            connection = ConnectionState.CONNECTED,
                            statusMessage = null,
                            leader = null,
                            poll = it.poll?.copy(closed = true),
                            votedSkip = false,
                            voteskipTally = null
                        )
                    }
                    retuneLeaderTicker()
                }

                is CyTubeEvent.Disconnected ->
                    update { it.copy(connection = ConnectionState.DISCONNECTED) }

                is CyTubeEvent.Reconnecting ->
                    update {
                        it.copy(
                            connection = ConnectionState.RECONNECTING,
                            statusMessage = "Reconnecting…"
                        )
                    }

                is CyTubeEvent.ConnectionFailed ->
                    update { it.copy(connection = ConnectionState.RECONNECTING) }

                is CyTubeEvent.ConnectionRefused -> onConnectionRefused(event.reason)

                // Used to leave the app on "Reconnecting…" for good. Reopening
                // the app retries (onAppVisibilityChanged); in the meantime,
                // say how to retry by hand (refresh() reconnects when not
                // connected).
                is CyTubeEvent.ReconnectGaveUp -> {
                    reconnectGaveUp = true
                    update {
                        it.copy(
                            connection = ConnectionState.FAILED,
                            statusMessage = "Can't reach the channel — tap its name to retry"
                        )
                    }
                }

                is CyTubeEvent.PartitionChanged -> {
                    // The channel moved to a different backend. The old socket
                    // URL is now wrong, so re-resolve socketconfig rather than
                    // letting Socket.IO retry a dead host forever.
                    update { it.copy(statusMessage = "Channel moved — reconnecting…") }
                    client.disconnect()
                    connect(_state.value.channel)
                }

                is CyTubeEvent.Kicked -> {
                    // Terminal. Notably "Duplicate login" must NOT trigger a
                    // reconnect, or the app fights the other session in a loop.
                    client.disconnect()
                    update {
                        it.copy(
                            connection = ConnectionState.DISCONNECTED,
                            kicked = event.reason
                        )
                    }
                }

                is CyTubeEvent.NeedPassword -> {
                    // Asked again after we sent one: it's wrong (or changed).
                    if (event.wrongPasswordTried) channelPassword = null
                    update { it.copy(needsPassword = true, passwordWasWrong = event.wrongPasswordTried) }
                }

                is CyTubeEvent.PasswordAccepted ->
                    update { it.copy(needsPassword = false, passwordWasWrong = false) }

                is CyTubeEvent.LoginResult -> {
                    update {
                        it.copy(
                            localUser = event.name ?: it.localUser,
                            statusMessage = if (!event.success) event.error else null
                        )
                    }
                    if (event.success) {
                        guestRetries = 0
                        loginRefusedOnce = false
                    } else if (savedCredential() == null) {
                        // Only guests are retried (an account's saved login
                        // either works or is dealt with by SessionExpired).
                        val waitSeconds = GuestLogin.cooldownSeconds(event.error)
                        if (waitSeconds != null) {
                            // The server allows one guest login per IP a
                            // minute, so a quick reconnect is refused however
                            // good the name. Wait it out and try the SAME name;
                            // a random one would be refused just the same.
                            if (guestLoginRetryJob?.isActive != true) {
                                update { it.copy(statusMessage = "Joining chat in ${waitSeconds}s…") }
                                guestLoginRetryJob = viewModelScope.launch {
                                    delay(waitSeconds * 1000L + 1_000L)
                                    client.retryGuestLogin()
                                }
                            }
                        } else if (guestRetries < MAX_GUEST_RETRIES) {
                            // The name itself (taken here, registered,
                            // reserved): try a fresh random one a few times.
                            // A one-off retry, not a saved preference — it
                            // never touches settingsStore, so it doesn't
                            // clobber a name chosen on the Account screen.
                            guestRetries++
                            client.retryGuestLogin("Guest" + (1000..9999).random())
                        }
                    }
                }

                is CyTubeEvent.SessionExpired -> {
                    // The saved login cookie was refused, so the server treats
                    // us as anonymous — shown as logged in, chat silently
                    // ignored. The server says the same for an expired cookie,
                    // a changed password, and a passing database error, so
                    // unless the cookie has visibly expired, try it once more
                    // before giving up on it.
                    val cookie = (savedCredential() as? CyTubeClient.Credential.Cookie)?.authCookie
                    val expired = cookie != null && SessionCookie.isExpired(cookie, System.currentTimeMillis())
                    if (!expired && !loginRefusedOnce) {
                        loginRefusedOnce = true
                        Log.i(TAG, "saved login was refused; retrying once")
                        client.disconnect()
                        delay(2_000)
                        connect(_state.value.channel)
                        return@collect
                    }
                    // Refused for good: forget it and rejoin as a guest.
                    Log.i(TAG, "saved login was refused; rejoining as a guest")
                    withContext(Dispatchers.IO) { runCatching { Graph.auth(getApplication()).logout() } }
                    client.disconnect()
                    connect(_state.value.channel)
                    appendLocalNotice(
                        ChatMessage(
                            username = "",
                            html = "Your login has expired, so you've joined as a guest. " +
                                "Log in again from Account to chat as yourself.",
                            timestamp = System.currentTimeMillis(),
                            addClass = null,
                            shadow = false
                        )
                    )
                }
                is CyTubeEvent.VoteskipVoteCleared -> update { it.copy(votedSkip = false) }

                is CyTubeEvent.RankChanged -> {
                    update { it.copy(localRank = event.rank) }
                    refreshPermissions()
                }
                is CyTubeEvent.PermissionsChanged -> {
                    channelPermissions = event.permissions
                    refreshPermissions()
                }
                is CyTubeEvent.VoteskipAllowed -> {
                    voteskipAllowed = event.allowed
                    refreshPermissions()
                }
                is CyTubeEvent.VoteskipCount -> update {
                    it.copy(
                        voteskipTally = if (event.need > 0 || event.count > 0) {
                            VoteskipTally(event.count, event.need)
                        } else null
                    )
                }
                is CyTubeEvent.PlaylistLocked -> {
                    playlistOpen = !event.locked
                    refreshPermissions()
                }
                is CyTubeEvent.QueueFailed ->
                    if (pendingQueueJob?.isActive == true) finishQueue(QueueStatus(event.message, isError = true))
                    else showTransientStatus(event.message)

                is CyTubeEvent.MediaChanged -> onMediaChanged(event.media, event.receivedAtMs)
                is CyTubeEvent.MediaTimeUpdate -> {
                    // Both of these describe the CHANNEL's own current item —
                    // meaningless while a personal pick (see pickPersonal) is
                    // what's actually on screen, and applying either would
                    // show/hide the wrong play state or fight the personal
                    // player's own position. (onTimeUpdate still records the
                    // time, for when the pick ends; evaluateSync is what
                    // skips applying it during a pick.)
                    if (!_state.value.personalPickActive && !pausedByUser &&
                        event.update.paused == _state.value.playing
                    ) {
                        update { it.copy(playing = !event.update.paused) }
                    }
                    onTimeUpdate(event.update, event.receivedAtMs)
                }

                is CyTubeEvent.PlaylistReplaced -> update { it.copy(playlist = event.items.toPersistentList()) }
                // Sent every time an item starts, including a one-item
                // playlist starting over, which changeMedia alone doesn't
                // show (same id) but which resets the server's skip vote.
                is CyTubeEvent.CurrentItemChanged -> update { it.copy(currentUid = event.uid, votedSkip = false) }
                is CyTubeEvent.ItemQueued -> {
                    update { s -> s.copy(playlist = insertAfter(s.playlist, event.item, event.afterUid)) }
                    // Our own add landing. Matched on who queued it rather
                    // than the media id: a YouTube playlist link arrives as
                    // many items, none with the playlist's id.
                    val me = _state.value.localUser
                    if (pendingQueueJob?.isActive == true && me != null &&
                        event.item.queueby.equals(me, ignoreCase = true)
                    ) {
                        finishQueue(QueueStatus("Added: ${event.item.title}"))
                    }
                }
                is CyTubeEvent.ItemMoved -> update { s ->
                    val item = s.playlist.firstOrNull { it.uid == event.uid } ?: return@update s
                    s.copy(playlist = insertAfter(s.playlist.removing(item), item, event.afterUid))
                }
                is CyTubeEvent.ItemDeleted -> update { s ->
                    s.copy(playlist = s.playlist.removingAll { it.uid == event.uid })
                }

                // Handed to the chat pipeline (runChatPump) rather than
                // handled here, so under a flood a time update or the next
                // video isn't left waiting behind chat.
                is CyTubeEvent.Chat -> chatInbox.trySend(ChatInboxItem.Message(event.message))
                // Through the pipeline too, so it lands after the messages
                // that came before it, not before.
                is CyTubeEvent.ChatCleared -> chatInbox.trySend(ChatInboxItem.Clear)

                is CyTubeEvent.UserListReplaced -> update { it.copy(users = event.users.toPersistentList()) }
                is CyTubeEvent.UserJoined -> update { s ->
                    s.copy(users = s.users.removingAll { it.name == event.user.name }.adding(event.user))
                }
                is CyTubeEvent.UserLeft -> update { s ->
                    s.copy(users = s.users.removingAll { it.name == event.name })
                }
                // replacingAt(idx, ...) rather than map{} over everyone: a plain map
                // would rebuild the whole list (and force a fresh
                // PersistentList, defeating the structural sharing this type
                // exists for) even though at most one entry changes here.
                is CyTubeEvent.UserAfkChanged -> update { s ->
                    val idx = s.users.indexOfFirst { it.name == event.name }
                    if (idx < 0) s else s.copy(users = s.users.replacingAt(idx, s.users[idx].copy(afk = event.afk)))
                }
                is CyTubeEvent.UserRankChanged -> update { s ->
                    val idx = s.users.indexOfFirst { it.name == event.name }
                    if (idx < 0) s else s.copy(users = s.users.replacingAt(idx, s.users[idx].copy(rank = event.rank)))
                }
                is CyTubeEvent.UserCount -> update { it.copy(userCount = event.count) }

                is CyTubeEvent.LeaderChanged -> {
                    update { it.copy(leader = event.name) }
                    retuneLeaderTicker()
                }

                is CyTubeEvent.Emotes ->
                    update { s -> s.copy(emotes = EmoteSet.from(event.emotes, s.emotes.effects)) }
                is CyTubeEvent.EmoteUpdated ->
                    update { s -> s.copy(emotes = s.emotes.withUpdated(event.emote)) }
                is CyTubeEvent.EmoteRenamed ->
                    update { s -> s.copy(emotes = s.emotes.withRenamed(event.oldName, event.emote)) }
                is CyTubeEvent.EmoteRemoved ->
                    update { s -> s.copy(emotes = s.emotes.withRemoved(event.name)) }
                // Read off the main thread: a channel's CSS can be long. A
                // newer copy (a moderator saving again) replaces any read
                // still in progress, so an older one can't finish last.
                is CyTubeEvent.ChannelCss -> {
                    channelCssJob?.cancel()
                    channelCssJob = viewModelScope.launch {
                        val style = withContext(Dispatchers.Default) {
                            com.cytube.mobile.net.ChannelStyle.parse(event.css)
                        }
                        update { s ->
                            s.copy(emotes = s.emotes.withEffects(style.effects), nameColors = style.nameColors)
                        }
                    }
                }

                is CyTubeEvent.MotdChanged -> update { it.copy(motd = event.html) }

                is CyTubeEvent.PollOpened -> {
                    update { it.copy(poll = event.poll, myPollVote = null) }
                    // Back in a poll we'd already voted in (rejoined, or the
                    // connection dropped): the server removed that vote when
                    // we left, so cast it again — see PollVoteMemory.
                    PollVoteMemory.get(pollVoteKey(_state.value.channel, event.poll))?.let { option ->
                        if (option in event.poll.options.indices && _state.value.canVotePoll) {
                            client.vote(option)
                            update { it.copy(myPollVote = option) }
                        }
                    }
                    // The website announces a new poll in chat; the app only
                    // showed a bottom-bar button, easy to miss while typing
                    // or in fullscreen. Once per poll per app session: the
                    // server re-sends the open poll on every (re)join.
                    val p = event.poll
                    if (PollVoteMemory.firstSighting(pollVoteKey(_state.value.channel, p))) {
                        appendLocalNotice(
                            ChatMessage(
                                username = "",
                                html = "${escapeHtml(p.initiator.ifBlank { "Someone" })} opened a poll: " +
                                    "\"${escapeHtml(unwrapImageTags(p.title))}\"",
                                timestamp = p.timestamp,
                                addClass = null,
                                shadow = false
                            )
                        )
                    }
                }
                is CyTubeEvent.PollUpdated -> update { s ->
                    val current = s.poll ?: return@update s
                    // Positional: server sends counts parallel to the options
                    // already on screen, so pad/truncate to match rather than
                    // trust the incoming length blindly.
                    val counts = List(current.options.size) { i -> event.counts.getOrElse(i) { -1 } }
                    s.copy(poll = current.copy(counts = counts, hiddenFromOthers = event.hiddenFromOthers))
                }
                // Kept, marked closed, rather than dropped: the server sends
                // a hidden poll's real counts right before closing it, and
                // dropping it here meant nobody in the app ever saw them.
                is CyTubeEvent.PollClosed -> update { it.copy(poll = it.poll?.copy(closed = true)) }

                is CyTubeEvent.ErrorMessage -> showTransientStatus(event.message)

                // Site-wide notices from the server's administrators, shown
                // in chat the way the website shows them above it. The
                // server re-sends the current one on every (re)connect and
                // in every channel, so each is shown once on this device
                // (see AnnouncementMemory), not once per channel.
                is CyTubeEvent.Announcement -> {
                    val key = "${event.title}|${event.html}"
                    if (key != lastAnnouncementKey) {
                        lastAnnouncementKey = key
                        if (AnnouncementMemory.firstSighting(getApplication<Application>(), key)) appendLocalNotice(
                            ChatMessage(
                                username = "",
                                html = "<strong>${escapeHtml(event.title.ifBlank { "Announcement" })}</strong>: ${event.html}",
                                timestamp = System.currentTimeMillis(),
                                addClass = null,
                                shadow = false
                            )
                        )
                    }
                }
            }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("CyTube", "Error handling ${event::class.simpleName}", e)
            }
        }
    }

    // ---- media ----

    /** The server's time for the item, capped at its length. */
    private fun serverTime(currentTime: Double, lengthSeconds: Int): Double =
        if (lengthSeconds > 0 && currentTime > lengthSeconds) lengthSeconds.toDouble() else currentTime

    /** When a server time was actually received: see CyTubeEvent.MediaTimeUpdate. */
    private fun receivedAt(receivedAtMs: Long): Long {
        val now = SystemClock.elapsedRealtime()
        return if (receivedAtMs in 1..now) receivedAtMs else now
    }

    private fun onMediaChanged(media: MediaFrame, receivedAtMs: Long = 0L) {
        // CyTube re-announces the current item verbatim sometimes (e.g. to
        // resync a client) — not just when the item actually changes. A real
        // capture showed the identical id/seconds arrive three times, ~15s
        // apart, for a video that had already failed to resolve: each one
        // tore down and rebuilt the whole ExoPlayer/MediaCodec pipeline and
        // popped the "Try WebView?" dialog again, for no reason. Only treat
        // this as a genuinely new item — and only then re-run player
        // selection and reset the offer dialogs — when the id or type
        // actually differs from what's already loaded. Compared against
        // channelCurrentMedia rather than state.media: while a personal pick
        // (see pickPersonal) is active those two diverge on purpose, and it's
        // the channel's own item this dedupe check (and channelCurrentMedia
        // itself) cares about, not whatever's personally on screen.
        val current = _state.value.channelCurrentMedia
        if (current != null && current.id == media.id && current.type == media.type) {
            lastServerTimeSeconds = serverTime(media.currentTime, media.seconds)
            lastServerTimeElapsedRealtimeMs = receivedAt(receivedAtMs)
            isServerPaused = media.paused
            update {
                if (it.personalPickActive) it.copy(channelCurrentMedia = media)
                else it.copy(
                    media = media,
                    channelCurrentMedia = media,
                    playing = if (pausedByUser) it.playing else !media.paused
                )
            }
            evaluateSync()
            return
        }

        // Personally browsing: the channel's real item genuinely changed
        // underneath us, but nothing about what's on screen should move —
        // just keep channelCurrentMedia current so stopPersonalPick (sync
        // flipping back on) lands on the right thing.
        if (_state.value.personalPickActive) {
            // Still record the new item's time, for when the pick ends (see
            // stopPersonalPick) — otherwise that starts from the previous
            // item's clock until the next mediaUpdate.
            lastServerTimeSeconds = serverTime(media.currentTime, media.seconds)
            lastServerTimeElapsedRealtimeMs = receivedAt(receivedAtMs)
            isServerPaused = media.paused
            update { it.copy(channelCurrentMedia = media, votedSkip = false, voteskipTally = null) }
            return
        }

        val (chosen, offer) = choosePlayerAndOffer(media)
        Log.i(TAG, "changeMedia type=${media.type} player=$chosen " +
            "seconds=${media.seconds} sources=${media.direct.size} id=${media.id}")
        val initialQualityIndex = resolveInitialQualityIndex(media)
        lastQualityChangeAtMs = 0L
        if (qualityStableSinceMs == 0L) qualityStableSinceMs = SystemClock.elapsedRealtime()
        playerAttachedAtMs = SystemClock.elapsedRealtime()
        lastServerTimeSeconds = serverTime(media.currentTime, media.seconds)
        lastServerTimeElapsedRealtimeMs = receivedAt(receivedAtMs)
        isServerPaused = media.paused
        // Player selection is re-run on every changeMedia, so a playlist moving
        // from YouTube to a film and back switches players by itself.
        update {
            it.copy(
                media = media,
                channelCurrentMedia = media,
                player = chosen,
                playing = !media.paused,
                playbackOffer = null,
                compatOffer = offer,
                nativeQualityIndex = initialQualityIndex,
                votedSkip = false,
                voteskipTally = null
            )
        }
        loadWhileInBackground()
        evaluateSync()
        // In the foreground the player surface sees state.media change, loads
        // the item and calls attachPlayer.
    }

    /**
     * Resolves the starting quality index for a new item.
     * Respects the session's adapted quality ceiling if bandwidth previously degraded,
     * but safely and unnoticeably tests the next higher quality tier at item boundaries
     * if previous items played smoothly without stalls for a sustained duration (>120s)
     * and measured bandwidth is sufficient. Mid-item auto-upgrades are avoided to prevent
     * decoder flushing and rebuffering loops during active playback.
     */
    private fun resolveInitialQualityIndex(media: MediaFrame): Int {
        // Called once for each new item, which starts with none of its
        // sources known to be broken (see tryNextSource).
        failedSourceIndices.clear()
        if (media.direct.isEmpty()) return 0
        val preferredHeight = sessionPreferredQualityHeight ?: return bandwidthBasedStartIndex(media)

        val now = SystemClock.elapsedRealtime()
        val stableDuration = if (qualityStableSinceMs > 0) now - qualityStableSinceMs else 0L
        var effectiveCap = preferredHeight

        // If playback has been stable for >2 minutes across items, cautiously test
        // the next tier up at the natural item boundary — but only if measured throughput
        // supports it with a safety margin.
        if (stableDuration > 120_000L && qualityUpgradeAttempts > 0) {
            val candidateCap = when {
                preferredHeight < 480 -> 480
                preferredHeight < 720 -> 720
                preferredHeight < 1080 -> 1080
                // Up to 4K too: a measured start (bandwidthBasedStartIndex)
                // can now begin below a 1440p/2160p source, and without these
                // two rungs the session could never climb back to it.
                preferredHeight < 1440 -> 1440
                preferredHeight < 2160 -> 2160
                else -> preferredHeight
            }
            val requiredBps = requiredBitrateForHeight(candidateCap)
            // Real measurements only — see BandwidthEstimate. Before anything
            // has downloaded, Media3 reports a per-country default instead.
            val currentBitrate = BandwidthEstimate.measuredBps()
            // Only bump if bandwidth estimate is absent (unknown) or satisfies target with 30% headroom
            val bandwidthOk = currentBitrate == null || currentBitrate >= (requiredBps * 1.3).toLong()
            if (bandwidthOk) {
                qualityUpgradeAttempts = (qualityUpgradeAttempts - 1).coerceAtLeast(0)
                effectiveCap = candidateCap
                sessionPreferredQualityHeight = effectiveCap
                qualityStableSinceMs = now
                Log.i(TAG, "quality: network stable for ${stableDuration / 1000}s (estBitrate=${currentBitrate?.let { "${it / 1000}kbps" } ?: "unknown"}); stepped initial quality cap up to ${effectiveCap}p for new item")
            } else {
                Log.i(TAG, "quality: network stable but estimated bandwidth (${currentBitrate / 1000}kbps) insufficient for ${candidateCap}p (needs ${requiredBps / 1000}kbps); holding at ${preferredHeight}p")
            }
        }

        val matchIndex = media.direct.indexOfFirst { src ->
            val h = src.quality.toIntOrNull() ?: Int.MAX_VALUE
            h <= effectiveCap
        }
        return if (matchIndex >= 0) matchIndex else 0
    }

    /**
     * Starting quality for an item when this session has no quality history
     * yet (nothing has stalled and been stepped down). Used to be "always the
     * highest source", which on a slower connection meant starting a 1080p
     * or 4K source, stalling, and only then stepping down mid-item.
     *
     * Now: the highest source whose typical bitrate the measured bandwidth
     * covers with the same 30% headroom the step-up check uses. Only a REAL
     * measurement counts (BandwidthEstimate.measuredBps), so the first video
     * after the app starts, before anything has downloaded, still starts at
     * the top exactly as before, and the stall step-down still covers it.
     *
     * Choosing a lower source here records it as the session's quality cap,
     * with one step-up allowed, so the existing step-up at the next item
     * boundary can raise it again once playback proves stable and bandwidth
     * allows. Without that, a conservative first pick could never go back up.
     */
    private fun bandwidthBasedStartIndex(media: MediaFrame): Int {
        if (media.direct.size <= 1) return 0
        val estimate = BandwidthEstimate.measuredBps() ?: return 0
        // FLV is sorted last and nothing on Android plays it; never pick it.
        val playable = media.direct.indices.filter { media.direct[it].contentType != "video/flv" }
        if (playable.isEmpty()) return 0
        val chosen = playable.firstOrNull { i ->
            // An unrecognised quality label can't be judged, so it's allowed.
            val height = media.direct[i].quality.toIntOrNull() ?: return@firstOrNull true
            estimate >= (requiredBitrateForHeight(height) * 1.3).toLong()
        } ?: playable.last()
        if (chosen > 0) {
            media.direct[chosen].quality.toIntOrNull()?.let { height ->
                sessionPreferredQualityHeight = height
                qualityUpgradeAttempts = maxOf(qualityUpgradeAttempts, 1)
            }
            Log.i(TAG, "quality: measured ${estimate / 1000}kbps; starting at " +
                "${media.direct[chosen].quality}p instead of ${media.direct[0].quality}p")
        }
        return chosen
    }

    private fun requiredBitrateForHeight(height: Int): Long {
        return when {
            height >= 2160 -> 16_000_000L // 16 Mbps
            height >= 1440 -> 9_000_000L  // 9 Mbps
            height >= 1080 -> 4_500_000L  // 4.5 Mbps
            height >= 720 -> 2_200_000L   // 2.2 Mbps
            height >= 480 -> 1_000_000L   // 1.0 Mbps
            else -> 500_000L              // 500 kbps
        }
    }

    private fun startSyncTicker() {
        syncTicker?.cancel()
        syncTicker = viewModelScope.launch {
            while (true) {
                delay(1_000)
                evaluateSync()
            }
        }
    }

    private fun evaluateSync() {
        val s = _state.value
        val p = player ?: return
        if (s.effectiveMode == CompatMode.WEB) return   // the web page syncs itself
        if (pausedByUser && !p.isPaused) {
            // Resumed from some other control (in-app controls, a headset).
            pausedByUser = false
            holdUntilReopened = false
            if (!s.personalPickActive) update { it.copy(playing = !isServerPaused) }
        }
        if (s.personalPickActive) {
            sync.standDown(p, resume = false)   // the pick's own load starts it
            return
        }
        if (s.isLeader || !settings.syncEnabled) {
            // SyncEngine may have left a rate nudge running, or be holding
            // the player paused for the room to catch up; don't let it carry
            // on at 1.1x, or sit paused, once sync stops being applied.
            sync.standDown(p, resume = true)
            return
        }
        if (pausedByUser) {
            sync.standDown(p, resume = false)
            return
        }
        val currentServerTime = roomTimeNow(s.media?.seconds ?: 0) ?: return
        val now = SystemClock.elapsedRealtime()
        val withinGrace = now - playerAttachedAtMs < SYNC_GRACE_MS
        val update = TimeUpdate(currentTime = currentServerTime, paused = isServerPaused)

        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            try {
                sync.apply(
                    player = p,
                    update = update,
                    newMediaId = s.media?.id,
                    accuracySeconds = settings.syncAccuracy,
                    withinGracePeriod = withinGrace
                )
                saveLeadMemoryIfLearned()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "sync check failed", e)
            }
        }
    }

    private fun onTimeUpdate(update: TimeUpdate, receivedAtMs: Long = 0L) {
        // The channel's item, not state.media: during a personal pick
        // they differ, and this time belongs to the channel's.
        val length = _state.value.channelCurrentMedia?.seconds ?: 0
        lastServerTimeSeconds = serverTime(update.currentTime, length)
        // When it arrived, not when this got to it: under a chat flood the
        // gap was big enough to make the room look seconds behind.
        val previousUpdateMs = lastServerTimeElapsedRealtimeMs
        lastServerTimeElapsedRealtimeMs = receivedAt(receivedAtMs)
        val sinceLastMs = lastServerTimeElapsedRealtimeMs - previousUpdateMs
        if (previousUpdateMs > 0L && sinceLastMs > SERVER_UPDATE_LATE_MS) {
            Log.i(SYNC_TAG, "no time update from the server for ${sinceLastMs}ms")
        }
        isServerPaused = update.paused
        evaluateSync()
    }

    /**
     * Reported by PlayerSurface's ExoSurface (via its onStall callback)
     * whenever a mid-playback rebuffer — never the item's own initial
     * buffer-up, see onStall's own doc for why that distinction matters —
     * lasts long enough to look like a genuine, sustained bandwidth
     * shortfall rather than a brief blip. Steps
     * [ChannelUiState.nativeQualityIndex] down one level (into
     * MediaFrame.direct, already sorted highest-to-lowest).
     * No amount of LoadControl/buffer-size
     * tuning can fix a SUSTAINED throughput shortfall (a bigger buffer only
     * buys more runway to absorb a short dip) — this is the actual lever for
     * that case. Quality stays latched at this lower level for the rest of the
     * current media item to prevent mid-stream decoder reset flapping.
     */
    fun onPlaybackStall(stalledMs: Long) {
        if (stalledMs < QUALITY_DOWNGRADE_STALL_THRESHOLD_MS) return
        val s = _state.value
        if (s.player != MediaTypes.Player.NATIVE) return
        val media = s.media ?: return
        if (media.direct.size <= 1) return   // nothing lower to step down to
        val now = SystemClock.elapsedRealtime()
        if (now - lastQualityChangeAtMs < QUALITY_CHANGE_COOLDOWN_MS) return
        if (s.nativeQualityIndex >= media.direct.lastIndex) return   // already at the lowest available
        val next = s.nativeQualityIndex + 1
        qualityUpgradeAttempts++
        lastQualityChangeAtMs = now
        qualityStableSinceMs = now
        val nextQuality = media.direct[next]
        sessionPreferredQualityHeight = nextQuality.quality.toIntOrNull()
        Log.i(TAG, "quality: stepping down to ${nextQuality.quality}p after a ${stalledMs}ms stall")
        reloadAtQuality(next)
    }

    /**
     * Quality step-down: updates [ChannelUiState.nativeQualityIndex] so
     * PlayerSurface switches quality in place on the existing player.
     */
    private fun reloadAtQuality(index: Int) {
        val st = _state.value
        val m = st.media ?: return
        if (st.player != MediaTypes.Player.NATIVE) return
        update { it.copy(nativeQualityIndex = index) }
        // The surface does the reload when it sees the new index, but it
        // can't while the app is in the background (Compose doesn't run),
        // so do it here; the surface then finds it already loaded.
        if (AppVisibility.inBackground.value) {
            val handle = player as? NativePlayerHandle ?: return
            if (handle.mediaId == m.id) handle.load(m, index)
        }
    }

    /**
     * A newly-attached backend (fresh media, or a player-type switch) starts
     * unmuted at the ExoPlayer/NewPipe level regardless of what the user had
     * chosen before — PlayerHandle has no memory of it. Re-apply the current
     * mute state here so switching items, or falling back to a different
     * player, never silently un-mutes audio the user turned off.
     */
    fun attachPlayer(handle: PlayerHandle) {
        if (handle !== player || handle.mediaId != attachedMediaId) {
            playerAttachedAtMs = SystemClock.elapsedRealtime()
        }
        if (handle !== player) (player as? NativePlayerHandle)?.onSubtitlesChanged = null
        player = handle
        attachedMediaId = handle.mediaId
        handle.setVolume(if (_state.value.muted) 0f else 1f)
        (handle as? NativePlayerHandle)?.setVideoEnabled(!AppVisibility.inBackground.value)
        val native = handle as? NativePlayerHandle
        if (native != null) {
            native.onSubtitlesChanged = { options -> update { it.copy(subtitles = options) } }
        } else {
            // A web or embedded player: its subtitles are its own business.
            update { it.copy(subtitles = SubtitleOptions.NONE) }
        }
        client.signalPlayerReady()
        evaluateSync()
    }

    /** From the CC button: show the [index]th subtitle track, or none, of
     *  the tracks [key] was read from (see SubtitleOptions.key). */
    fun selectSubtitle(index: Int?, key: String) {
        (player as? NativePlayerHandle)?.selectSubtitle(index, key)
    }

    // ---- audio / PiP playback control ----

    /**
     * Background playback (Home/Recents) is intentionally NOT paused here —
     * see MainActivity: leaving the foreground without entering PiP now just
     * lets ExoPlayer/NewPipe keep running, the same as PiP already did, so
     * audio (and video, once the user returns) continues rather than being
     * artificially stopped. This toggle is purely the user's own mute
     * preference, independent of that.
     */
    fun toggleMute() {
        val next = !_state.value.muted
        update { it.copy(muted = next) }
        player?.setVolume(if (next) 0f else 1f)
    }

    /**
     * Set when the user pauses from the PiP window or closes it. Synced
     * playback otherwise restarts a paused player within a second (SyncEngine
     * plays it whenever the room is playing), so the pause button did
     * nothing and closing the window left the sound playing with no screen.
     * While set, sync leaves the player alone; it clears as soon as playback
     * resumes by any means (see evaluateSync), or — for a closed window
     * ([holdUntilReopened]) — when the app is reopened.
     */
    private var pausedByUser = false
    /** The hold came from closing the PiP window, so reopening the app lifts
     *  it. A pause from the PiP button isn't lifted that way (turning the
     *  screen off and on with the PiP window up also counts as reopening). */
    private var holdUntilReopened = false

    /** Wired to the PiP window's own play/pause action. */
    fun togglePlaybackFromPip() {
        val p = player ?: return
        // Held for the room to catch up (SyncEngine.isHolding) is paused
        // underneath but "playing" as far as the window shows, so a tap
        // then means pause.
        if (p.isPaused && !sync.isHolding) {
            p.play()
            releasePauseHold()
        } else {
            sync.standDown(p, resume = false)
            holdPaused()
            p.pause()
        }
    }

    /** The PiP window was closed ([closed]) or expanded back into the app.
     *  Closing stops playback until the app is opened again; expanding
     *  lifts a pause made from the PiP window (the full-screen view it
     *  expands into has no play button). See [pausedByUser]. */
    fun onPipLeft(closed: Boolean) {
        if (closed) {
            holdPaused()
            holdUntilReopened = true
            backgroundLoadJob?.cancel()
            player?.pause()
        } else {
            releasePauseHold()
        }
    }

    private fun holdPaused() {
        pausedByUser = true
        // Also what the PiP window's play/pause icon shows.
        update { if (it.playing) it.copy(playing = false) else it }
    }

    private fun releasePauseHold() {
        if (!pausedByUser) return
        val wasClosedWindow = holdUntilReopened
        pausedByUser = false
        holdUntilReopened = false
        val s = _state.value
        if (!s.personalPickActive) update { it.copy(playing = !isServerPaused) }
        if (wasClosedWindow && s.isLeader && !s.personalPickActive) {
            // The room carried on without us (see retuneLeaderTicker), and
            // sync doesn't move a leader's player: catch it up, and start
            // it again, before it goes back to being the room's clock.
            val p = player
            val channelItem = s.channelCurrentMedia
            if (p != null && channelItem != null && p.mediaId == channelItem.id) {
                roomTimeNow(channelItem.seconds)?.let { p.seekTo(it) }
                if (!isServerPaused) p.play()
            }
        }
        evaluateSync()
    }

    /**
     * Loads the current item into the existing player while the app is in
     * the background.
     *
     * Normally the player surface loads each new item, but Compose doesn't
     * run while the app is out of sight, so without this a playlist advance
     * in the background would leave the finished video sitting in the
     * player. This does the surface's job for that case, on the same player: a
     * plain file directly; YouTube, Drive, Streamable and PeerTube after
     * resolving the stream (cached, so the surface finds it already
     * resolved when the app comes back, and the handle's loadedKey tells it
     * not to load it again). Items that can only play in a WebView (Vimeo,
     * Dailymotion, YouTube live…) can't be loaded without the UI, so the
     * old video is just paused and the new one loads when the app is
     * reopened.
     */
    private fun loadWhileInBackground() {
        if (!AppVisibility.inBackground.value) return
        if (pausedByUser) return   // the PiP window was closed: stay stopped
        val s = _state.value
        if (s.effectiveMode == CompatMode.WEB) return   // the web page runs its own player
        val media = s.media ?: return
        val current = player ?: return
        if (current.mediaId == media.id && current.mediaType == media.type) return
        backgroundLoadJob?.cancel()
        val handle = current as? NativePlayerHandle
        if (handle == null) {
            current.pause()
            return
        }
        when (val kind = s.player) {
            MediaTypes.Player.NATIVE -> {
                Log.i(TAG, "background: loading next item type=${media.type} id=${media.id}")
                val url = NativePlayerHandle.sourceUrl(media, s.nativeQualityIndex)
                handle.load(plannedStart(media.copy(currentTime = backgroundStartTime(media)), url), s.nativeQualityIndex)
                playerAttachedAtMs = SystemClock.elapsedRealtime()
                attachedMediaId = media.id
            }
            else -> {
                if (!StreamResolvers.handles(kind)) {
                    current.pause()
                    return
                }
                backgroundLoadJob = viewModelScope.launch {
                    val stream = StreamResolvers.resolve(kind, media.id).getOrNull()
                    // Still the item, player and situation this was started for?
                    val now = _state.value
                    if (now.media?.id != media.id || now.media.type != media.type ||
                        now.player != kind || player !== handle ||
                        !AppVisibility.inBackground.value
                    ) return@launch
                    if (stream == null) {
                        // The surface will try again (and report the error)
                        // when the app is reopened.
                        handle.pause()
                        return@launch
                    }
                    Log.i(TAG, "background: loading next item type=${media.type} id=${media.id} (${stream.variant})")
                    handle.loadUrl(
                        plannedStart(media.copy(currentTime = backgroundStartTime(media)), stream.url),
                        stream.url, stream.mimeType, stream.headers, stream.variant
                    )
                    playerAttachedAtMs = SystemClock.elapsedRealtime()
                    attachedMediaId = media.id
                }
            }
        }
    }

    /** Where [media] should start when loaded in the background: where the
     *  room is now for the channel's item (the changeMedia frame's own time
     *  goes stale while a stream resolves), or the frame's own start for a
     *  personal pick, which the server's clock has nothing to do with. */
    private fun backgroundStartTime(media: MediaFrame): Double {
        if (_state.value.personalPickActive) return media.currentTime
        return roomTimeNow(media.seconds) ?: media.currentTime
    }

    /**
     * Where to open [media], streamed from [streamUrl], when it's loaded fresh.
     *
     *  - A personal pick: [media] as it is, i.e. from its own start.
     *  - The channel's own item while synced (and not leading it): the room's
     *    time now, plus — when joining partway through — what opening from
     *    that server has been costing (SyncEngine.planStart), so a big file
     *    that takes 10 s to open doesn't arrive 10 s behind and have to jump
     *    again.
     *  - The channel's own item otherwise (leading, or sync off): the room's
     *    time now. [media]'s own time is from its changeMedia frame, which is
     *    stale by however long a stream lookup took. During the server's
     *    lead-in countdown (a negative time) it starts from the top.
     */
    fun plannedStart(media: MediaFrame, streamUrl: String): MediaFrame {
        val s = _state.value
        val channelItem = s.channelCurrentMedia
        if (s.personalPickActive || channelItem == null ||
            channelItem.id != media.id || channelItem.type != media.type
        ) return media
        val room = roomTimeNow(media.seconds) ?: return media
        if (s.isLeader || !settings.syncEnabled) {
            return if (media.currentTime < 0 || room < 0) media else media.copy(currentTime = room)
        }
        val start = sync.planStart(media.id, streamUrl, room, media.seconds, isServerPaused)
        return media.copy(currentTime = start)
    }

    /** Where the channel's item is now: the last server time plus however
     *  long it's been playing since. Null before any time has arrived. */
    private fun roomTimeNow(lengthSeconds: Int): Double? {
        if (lastServerTimeElapsedRealtimeMs == 0L) return null
        val elapsed = if (isServerPaused) 0.0
            else (SystemClock.elapsedRealtime() - lastServerTimeElapsedRealtimeMs) / 1000.0
        val t = lastServerTimeSeconds + elapsed
        return if (lengthSeconds > 0) t.coerceAtMost(lengthSeconds.toDouble()) else t
    }

    /**
     * The app went out of sight (Home, Recents, screen off — not
     * picture-in-picture) or came back; from MainActivity via AppVisibility,
     * because Compose doesn't run while the app is in the background.
     *
     * Going: stop decoding video nobody can see (audio carries on), and
     * load the current item if the channel moved on just before, when the UI
     * hadn't got to it yet.
     *
     * Coming back: video back on, and reconnect if Socket.IO gave up while
     * we were away (a few minutes without a connection is enough). The
     * reconnection is a fresh one, the same as refresh() does when not
     * connected: calling connect() on the old socket mid-backoff can leave
     * Socket.IO believing it's still reconnecting, after which it never
     * retries again.
     */
    private fun onAppVisibilityChanged(background: Boolean) {
        (player as? NativePlayerHandle)?.setVideoEnabled(!background)
        if (background) {
            saveLeadMemory()   // the process may not come back
            loadWhileInBackground()
            return
        }
        if (holdUntilReopened) releasePauseHold()
        val s = _state.value
        if (reconnectGaveUp && s.kicked == null && s.effectiveMode != CompatMode.WEB) {
            reconnectGaveUp = false
            Log.i(TAG, "reconnecting after Socket.IO gave up while in the background")
            viewModelScope.launch {
                client.disconnect()
                connect(s.channel)
            }
        }
    }

    /**
     * Leader mode inverts the protocol: we become the clock and push upward.
     * Every 5s, not 1s — that matches CyTube's own broadcast cadence (see
     * Frames.kt's TimeUpdate doc comment and CyTubeClient.signalPlayerReady's
     * "~5s broadcast interval"), which every other CyTube client, native or
     * web, already builds its own drift tolerance around. Firing 5x more
     * often than that bought no tighter sync for anyone in the room, just
     * 5x the socket emits (and radio wake-ups) while leading.
     */
    private fun retuneLeaderTicker() {
        leaderTicker?.cancel()
        if (!_state.value.isLeader) return
        leaderTicker = viewModelScope.launch {
            while (true) {
                delay(5_000)
                // Being the room's leader means BEING its clock — but a
                // personal pick (see pickPersonal) has this client playing
                // something else entirely, with no handle on the channel's
                // real current item to read a position from at all (it was
                // torn down when the pick was made). Broadcasting the
                // personal player's position as the channel's own currentTime
                // would corrupt sync for everyone else in the room, so this
                // just sits out each tick instead — no worse than any other
                // leader going briefly idle between items.
                if (_state.value.personalPickActive) continue
                val current = _state.value.channelCurrentMedia ?: continue
                if (holdUntilReopened) {
                    // The PiP window was closed, stopping our player. With a
                    // leader the server keeps no clock of its own, so sending
                    // the stopped player's time paused the whole room until
                    // we came back. Keep the room's clock going instead: the
                    // last time the server confirmed (it echoes our own
                    // updates) plus the time since.
                    val roomTime = roomTimeNow(current.seconds) ?: continue
                    client.sendMediaUpdate(current.id, current.type, roomTime, isServerPaused)
                    continue
                }
                val p = player ?: continue
                // Only ever the channel's own current item: right after the
                // playlist moves on, the player can still hold the previous
                // one, and its time means nothing for the new item. The
                // server checks the id too and ignores a mismatch.
                if (p.mediaId != current.id || p.mediaType != current.type) continue
                client.sendMediaUpdate(current.id, current.type, p.currentTimeSeconds(), p.isPaused)
            }
        }
    }

    /**
     * Compatibility decision, in one place.
     *
     * AUTOMATIC prefers native, then the sandboxed embed, and only falls back to
     * the full web page when the item genuinely cannot be driven — which keeps
     * native chat and a single session for the overwhelming majority of items.
     */
    private fun resolvePlayer(media: MediaFrame): MediaTypes.Player =
        when (_state.value.effectiveMode) {
            CompatMode.WEB -> MediaTypes.Player.WEB
            else -> MediaTypes.playerFor(media.type, media.hasDirect, media.embedPlayableSrc, media.isLivestream)
        }

    /**
     * Turns a raw player decision into what we actually apply. When the item
     * genuinely needs WebView and the user has not explicitly forced Web mode,
     * we never switch on our own — the item is marked UNAVAILABLE and a reason
     * is returned so the caller can ask first. This is for types with no
     * native, resolver-backed or single-video embed path at all (Twitch,
     * Livestream.com, SoundCloud...); see MediaTypes.playerFor.
     */
    private fun choosePlayerAndOffer(media: MediaFrame): Pair<MediaTypes.Player, String?> {
        val chosen = resolvePlayer(media)
        val forcedWeb = _state.value.effectiveMode == CompatMode.WEB
        return if (chosen == MediaTypes.Player.WEB && !forcedWeb) {
            MediaTypes.Player.UNAVAILABLE to compatOfferReason(media)
        } else {
            chosen to null
        }
    }

    // Google Drive never gets here: it resolves natively (GoogleDriveResolver),
    // so choosePlayerAndOffer never sees Player.WEB for it. If that lookup
    // fails at runtime, it goes through reportPlaybackFailure/playbackOffer
    // like any other player that started and then failed.
    private fun compatOfferReason(media: MediaFrame): String =
        "${MediaTypes.label(media.type)} needs Compatibility View"

    /**
     * A player backend actually started and then failed. We never switch to the
     * WebView on our own — the user is asked, because a silent jump to a
     * different player is exactly the kind of thing that makes the app feel
     * like it is fighting you.
     *
     * Google Drive is not special-cased: it gets the same playbackOffer
     * dialog as every other backend that started and failed.
     *
     * When the failed item also has a URL its own single-video WebView
     * surface could load (media.embedPlayableSrc — null for Google Drive,
     * which has no clean "just the video" page to fall back to), that swap
     * happens immediately, with no dialog. That's deliberately different
     * from the WebView case above: EMBED isn't "a different player" from the
     * user's seat — chat, playlist, sync, tilt-triggered fullscreen and the
     * Nico overlay all keep working exactly as they did a moment ago, only
     * the decoder behind the video itself changed, the same way switching
     * between NATIVE/NEWPIPE/GDRIVE already happens without asking. WebView
     * mode is the one that actually costs something (it drops the socket
     * entirely), so that's the one still worth a prompt. Guarded against the
     * backend that just failed *being* EMBED itself, so a broken
     * single-video URL can't silently re-select itself in a loop — that
     * case falls through to the WebView offer.
     */
    fun reportPlaybackFailure(reason: String) {
        val m = _state.value.media
        Log.w(TAG, "playback failed backend=${_state.value.player} " +
            "type=${m?.type} id=${m?.id}: $reason")
        // A resolved stream URL that just failed (expired, 403, taken down)
        // would otherwise stay cached for 5-10 minutes, so rejoining or the
        // item coming round again would retry the same dead link.
        if (m != null) StreamResolvers.invalidate(_state.value.player, m.id)
        if (_state.value.effectiveMode == CompatMode.WEB) return
        if (tryNextSource(m, reason)) return
        val embeddable = m?.embedPlayableSrc
        if (embeddable != null && _state.value.player != MediaTypes.Player.EMBED) {
            Log.d(TAG, "playback fallback: switching to single-video view at $embeddable")
            update { it.copy(player = MediaTypes.Player.EMBED) }
            return
        }
        update { it.copy(playbackOffer = reason) }
    }

    /**
     * A custom manifest can list a quality whose file was never uploaded or
     * has been taken down (seen live: a manifest's 720p link answered 404
     * while its 480p and 360p were fine). The browser's player only ever
     * tries the one it picked, but here a broken source moves on to another
     * quality instead of giving up on the item: the next one down first,
     * then, once everything below has failed too, the ones above (the item
     * may have started below the top after a slow connection). Each at most
     * once per item, so a manifest of nothing but dead links still ends in
     * the usual error.
     *
     * Only for the file itself being unplayable (PlaybackFailures): a
     * dropped connection has already been retried on the same file by the
     * player, and moving down a quality for it would only lower the picture
     * for the rest of the item. It's a broken file rather than a slow
     * connection either way, so the session's quality cap is left as it was.
     */
    private fun tryNextSource(media: MediaFrame?, reason: String): Boolean {
        val s = _state.value
        if (media == null || s.player != MediaTypes.Player.NATIVE) return false
        if (!PlaybackFailures.isBrokenSource(reason)) return false
        val current = s.nativeQualityIndex
        val failed = media.direct.getOrNull(current) ?: return false
        failedSourceIndices += current
        val next = ((current + 1)..media.direct.lastIndex).firstOrNull { it !in failedSourceIndices }
            ?: (current - 1 downTo 0).firstOrNull { it !in failedSourceIndices }
            ?: return false
        Log.i(TAG, "quality: ${failed.quality}p failed ($reason); trying ${media.direct[next].quality}p")
        reloadAtQuality(next)
        return true
    }

    fun acceptWebPlayback() {
        update { it.copy(playbackOffer = null) }
        setMode(CompatMode.WEB, persist = false)
    }

    fun declinePlaybackOffer() = update { it.copy(playbackOffer = null) }

    /** Same idea as [acceptWebPlayback]/[declinePlaybackOffer], for an item that
     *  was never native-playable in the first place (see [choosePlayerAndOffer]). */
    fun acceptCompatOffer() {
        update { it.copy(compatOffer = null) }
        setMode(CompatMode.WEB, persist = false)
    }

    fun declineCompatOffer() = update { it.copy(compatOffer = null) }

    /** elapsedRealtime() of the last [refresh] that actually ran — see its
     *  cooldown check below. */
    private var lastRefreshAtMs = 0L

    /**
     * Reconcile with the server rather than tearing the connection down.
     * Only re-resolves the socket if we are actually adrift, and never
     * touches the player — requestPlaylist/signalPlayerReady alone re-syncs
     * playlist and leader state over the existing connection, without
     * restarting whatever is already playing.
     *
     * Reached by tapping the channel name in the TopAppBar (see
     * ChannelScreen) — somewhere deliberate, rather than a pull gesture one
     * swipe away from scrolling chat.
     * `refreshing` alone only blocks overlap with a call already in flight,
     * not a second tap the moment it clears — someone tapping as fast as
     * they can would still fire a `requestPlaylist`/`signalPlayerReady`
     * pair (or, worse, a full `disconnect()` + reconnect when not
     * currently connected) roughly every 600ms. [REFRESH_COOLDOWN_MS] below
     * is the actual rate limit: a tap inside the cooldown window is just
     * silently ignored rather than queued or shown an error, so it can't be
     * turned into a way to flood the channel server with requests.
     */
    fun refresh() {
        if (_state.value.refreshing) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRefreshAtMs < REFRESH_COOLDOWN_MS) return
        lastRefreshAtMs = now
        update { it.copy(refreshing = true) }
        viewModelScope.launch {
            if (_state.value.connection == ConnectionState.CONNECTED) {
                client.requestPlaylist()
                client.signalPlayerReady()
            } else {
                // Only reached when we're actually not connected, so
                // rebuilding everything here is a real reconnect, not a
                // gratuitous one — there's no "current playback" to protect.
                client.disconnect()
                connect(_state.value.channel)
            }
            delay(600)
            update { it.copy(refreshing = false) }
        }
    }

    /**
     * [persist] is false when this is the result of accepting a "Try WebView?"
     * prompt (see acceptWebPlayback/acceptCompatOffer) rather than an explicit
     * choice from the Compatibility menu. A prompt answered "yes" should only
     * apply to the item that's actually broken right now — persisting it would
     * silently pin the channel to Web mode forever, so the very next
     * unrelated item (or the same item on a later visit, once whatever failed
     * is fixed) would load straight into WebView with no explanation and no
     * prompt.
     *
     * Choosing the same mode as the global default (Settings) clears this
     * channel's own choice instead of saving it, so the channel follows the
     * default again — the only way back from a per-channel choice.
     */
    fun setMode(mode: CompatMode, persist: Boolean = true) {
        viewModelScope.launch {
            if (persist) {
                settingsStore.setChannelCompat(
                    _state.value.channel,
                    mode.takeIf { it != settings.compatMode }
                )
            }
            update { it.copy(effectiveMode = mode, compatOffer = null) }

            if (mode == CompatMode.WEB) {
                // Compatibility View loads the real CyTube page, which opens its
                // own session. Two sessions on one account means the server kicks
                // the older one with "Duplicate login", so ours stands down first.
                leaderTicker?.cancel()
                player?.release()
                player = null
                client.disconnect()
                update { it.copy(player = MediaTypes.Player.WEB, connection = ConnectionState.DISCONNECTED) }
            } else {
                if (_state.value.connection != ConnectionState.CONNECTED) connect(_state.value.channel)
                _state.value.media?.let { m ->
                    val (chosen, offer) = choosePlayerAndOffer(m)
                    update { it.copy(player = chosen, compatOffer = offer) }
                }
            }
        }
    }

    fun toggleMotd() = update { it.copy(motdExpanded = !it.motdExpanded) }

    // ---- actions ----

    /** Public chat, or a private message while a PM target is set. */
    fun sendChat(text: String) {
        if (text.isBlank()) return
        val target = _state.value.pmTarget
        if (target != null) client.sendPm(target, text.trim())
        else client.sendChat(text.trim())
    }

    // ---- private messages ----

    /** Switches the chat box to private messages to [name]. Replying to or
     *  opening a PM also counts as reading that person's unread ones. */
    fun startPm(name: String) {
        val me = _state.value.localUser ?: return   // not joined with a name yet
        if (name.isBlank() || name.equals(me, ignoreCase = true)) return
        update {
            it.copy(
                pmTarget = name,
                unreadPm = it.unreadPm?.takeUnless { u -> u.from.equals(name, ignoreCase = true) }
            )
        }
    }

    fun cancelPm() = update { it.copy(pmTarget = null) }

    fun markPmsRead() = update { if (it.unreadPm == null) it else it.copy(unreadPm = null) }

    private fun nextUnreadPm(s: ChannelUiState, message: ChatMessage): UnreadPm? {
        if (!message.isPm) return s.unreadPm
        val me = s.localUser
        if (me != null && message.username.equals(me, ignoreCase = true)) return s.unreadPm   // our own, echoed
        val previous = s.unreadPm
        return if (previous != null && previous.from.equals(message.username, ignoreCase = true)) {
            previous.copy(count = previous.count + 1)
        } else {
            UnreadPm(message.username, (previous?.count ?: 0) + 1)
        }
    }

    fun submitPassword(pw: String) {
        channelPassword = pw
        client.sendPassword(pw)
    }
    fun jumpTo(uid: Int) = client.jumpTo(uid)
    fun deleteItem(uid: Int) = client.deleteItem(uid)

    // ---- personal/unsynced playback ----
    //
    // Turning "stay in sync" off is more than just disabling SyncEngine's
    // corrective seeking (that part already existed) — it also lets the
    // user open the playlist and pick any (resolvable — see
    // MediaTypes.canResolveIndependently) item to watch on their own,
    // completely independent of whatever the channel's real current item
    // is, with the same personal choice auto-advancing to the next
    // resolvable item once it finishes. Nothing here ever emits anything to
    // the server (contrast client.jumpTo, a moderator action that changes
    // the item for EVERYONE) — it only ever touches this client's own
    // media/player state. See onMediaChanged/onTimeUpdate/retuneLeaderTicker
    // for the guards that keep the channel's real, still-arriving updates
    // from fighting a personal pick while one is active.

    /**
     * Loads [item] just for this client. Only reachable while sync is off
     * (PlaylistPanel disables the tap entirely otherwise) and only for an
     * item [MediaTypes.canResolveIndependently] allows — both are checked
     * defensively here too, in case a stale composition or the auto-advance
     * path below ever calls this with something it shouldn't. Reuses
     * exactly the same player-selection/offer-dialog machinery a real
     * changeMedia goes through (choosePlayerAndOffer) so
     * a personal pick behaves identically to the channel's own current item
     * in every way except who it's visible to and what drives it forward.
     */
    fun pickPersonal(item: PlaylistItem) {
        if (_state.value.syncEnabled) return
        if (!MediaTypes.canResolveIndependently(item.type)) return
        val frame = MediaFrame.fromPlaylistItem(item)
        val (chosen, offer) = choosePlayerAndOffer(frame)
        Log.i(TAG, "personal pick type=${frame.type} player=$chosen id=${frame.id} uid=${item.uid}")
        val initialQualityIndex = resolveInitialQualityIndex(frame)
        // A genuinely different item from whatever was loaded before — see
        // ChannelUiState.nativeQualityIndex's own doc comment.
        lastQualityChangeAtMs = 0L
        if (qualityStableSinceMs == 0L) qualityStableSinceMs = SystemClock.elapsedRealtime()
        update {
            it.copy(
                media = frame,
                player = chosen,
                playing = !frame.paused,
                playbackOffer = null,
                compatOffer = offer,
                personalPickActive = true,
                personalPickUid = item.uid,
                nativeQualityIndex = initialQualityIndex
            )
        }
        // Personal auto-advance (onPlaybackEnded) lands here too, and that
        // can happen with the app in the background.
        loadWhileInBackground()
    }

    /**
     * Clears a personal pick and snaps back to whatever the channel's real
     * current item actually is (kept up to date the whole time by
     * onMediaChanged, even while it wasn't what was on screen). Called when
     * sync is switched back on (see the settings collector in start()).
     */
    private fun stopPersonalPick() {
        if (!_state.value.personalPickActive) return
        val channelMedia = _state.value.channelCurrentMedia
        if (channelMedia == null) {
            update { it.copy(personalPickActive = false, personalPickUid = -1) }
            return
        }
        val (chosen, offer) = choosePlayerAndOffer(channelMedia)
        val initialQualityIndex = resolveInitialQualityIndex(channelMedia)
        // Snapping back to the channel's own item is also a genuine item
        // change from whatever was personally loaded — see
        // ChannelUiState.nativeQualityIndex's own doc comment.
        lastQualityChangeAtMs = 0L
        if (qualityStableSinceMs == 0L) qualityStableSinceMs = SystemClock.elapsedRealtime()
        // The channel's time kept being recorded during the pick
        // (onTimeUpdate); channelMedia's own currentTime is from its
        // changeMedia, which can be minutes old. Start where the room is now.
        val resumeAt = roomTimeNow(channelMedia.seconds)
        if (resumeAt == null) {
            lastServerTimeSeconds = channelMedia.currentTime
            lastServerTimeElapsedRealtimeMs = SystemClock.elapsedRealtime()
            isServerPaused = channelMedia.paused
        }
        val resumed = if (resumeAt != null) channelMedia.copy(currentTime = resumeAt) else channelMedia
        // Starts the grace period as soon as the switch is made; attachPlayer
        // restarts it again once the surface has the channel's item loaded.
        playerAttachedAtMs = SystemClock.elapsedRealtime()
        update {
            it.copy(
                media = resumed,
                player = chosen,
                playing = !isServerPaused,
                playbackOffer = null,
                compatOffer = offer,
                personalPickActive = false,
                personalPickUid = -1,
                nativeQualityIndex = initialQualityIndex
            )
        }
        evaluateSync()
    }

    /**
     * Wired to PlayerSurface's onEnded unconditionally (see ChannelScreen) —
     * fires whenever ANY item finishes on its own, synced or not. Ignored
     * outright unless a personal pick is actually active: normal synced
     * playback is the server's job to advance, and this also has to cover a
     * stray onEnded firing from a player instance that was mid-teardown
     * right as sync got switched back on.
     * Walks forward from the current pick's playlist position to the next
     * item canResolveIndependently allows — same rule PlaylistPanel greys
     * rows out with — so auto-advance can never land on something that
     * would need server meta a personal pick doesn't have. If nothing
     * further down the list qualifies, playback just stops there rather
     * than looping back to the top or falling back to the channel's own item.
     */
    fun onPlaybackEnded() {
        val s = _state.value
        if (!s.personalPickActive) return
        val idx = s.playlist.indexOfFirst { it.uid == s.personalPickUid }
        val startIdx = if (idx >= 0) idx + 1 else 0
        val next = s.playlist.asSequence().drop(startIdx)
            .firstOrNull { MediaTypes.canResolveIndependently(it.type) }
        if (next != null) pickPersonal(next) else update { it.copy(playing = false) }
    }

    /**
     * Optimistic like the site's own poll UI: highlight the tapped option
     * immediately rather than waiting on the round trip, since the server's
     * updatePoll broadcast (counts only) is the actual source of truth for
     * the numbers and will correct this if the vote is rejected.
     */
    fun votePoll(option: Int) {
        val s = _state.value
        val poll = s.poll ?: return
        if (poll.closed || !s.canVotePoll || option !in poll.options.indices) return
        PollVoteMemory.put(pollVoteKey(s.channel, poll), option)
        update { it.copy(myPollVote = option) }
        client.vote(option)
    }

    /** Votes to skip the channel's current item, once per item. */
    fun voteSkip() {
        val s = _state.value
        if (!s.canVoteskip || s.votedSkip || s.channelCurrentMedia == null ||
            s.connection != ConnectionState.CONNECTED
        ) return
        update { it.copy(votedSkip = true) }
        client.voteSkip()
    }

    fun toggleFavourite() {
        viewModelScope.launch {
            settingsStore.toggleFavourite(_state.value.channel)
            update { it.copy(isFavourite = !it.isFavourite) }
        }
    }

    fun retry() {
        viewModelScope.launch {
            update { it.copy(kicked = null) }
            connect(_state.value.channel)
        }
    }

    /** Identity for chat-replay dedup — the fields CyTube's server sends back
     *  unchanged when it resends a message (original username/text/time),
     *  never anything this client assigns itself like [ChatMessage.seq]. */
    private fun ChatMessage.fingerprint(): String = "$username $timestamp $isPm $html"

    /**
     * Appends [messages] (a whole batch — see applyChat) and trims the buffer
     * to MAX_CHAT_MESSAGES, in one pass. Trimming from the front of a
     * PersistentList shifts what's left, so doing it once per batch rather
     * than once per message matters in a flood.
     */
    private fun appendChat(current: PersistentList<ChatMessage>, messages: List<ChatMessage>): PersistentList<ChatMessage> {
        if (messages.isEmpty()) return current
        val tagged = messages.map { it.copy(seq = ++chatSeq) }
        val all = current.addingAll(tagged)
        val drop = all.size - MAX_CHAT_MESSAGES
        return if (drop <= 0) all else all.subList(drop, all.size).toPersistentList()
    }

    /**
     * A fresh random name every connect, UNLESS the user has explicitly set
     * one on the Account screen. This app is open source and meant to work
     * the moment someone installs it and opens a channel — no account, no
     * setup screen to visit first — so the default has to be "just works,
     * anonymously" like the CyTube site itself, not a name we invent once and
     * silently start remembering behind the user's back.
     */
    private fun guestName(): String {
        val saved = settings.guestName
        return if (saved.isNotBlank()) saved else "Guest" + (1000..9999).random()
    }

    /** Graph.auth() may still be building EncryptedSharedPreferences on a
     *  cold start (deep link straight into a channel), so never on Main. */
    private suspend fun savedCredential(): CyTubeClient.Credential? =
        withContext(Dispatchers.IO) { Graph.auth(getApplication()).credentialForSession() }

    private var transientStatusJob: Job? = null

    // ---- adding videos ----

    private var channelPermissions: Permissions? = null
    /** The channel's allow_voteskip option (channelOpts); on by default, as
     *  on the server. */
    private var voteskipAllowed = true
    /** CyTube's "open playlist" (unlocked) state; see Permissions.allowsPlaylistAction. */
    private var playlistOpen = false
    /** Waiting on the server's answer to our last queue request; also the timeout. */
    private var pendingQueueJob: Job? = null
    private var queueStatusClearJob: Job? = null

    private fun refreshPermissions() {
        val perms = channelPermissions
        val rank = _state.value.localRank
        val canAdd = perms?.allowsPlaylistAction("playlistadd", rank, playlistOpen) == true
        val canNext = canAdd && perms.allowsPlaylistAction("playlistnext", rank, playlistOpen)
        val canVote = perms?.allows("pollvote", rank) ?: true
        val canSkip = voteskipAllowed && perms?.allows("voteskip", rank) == true
        update {
            if (it.canQueue == canAdd && it.canQueueNext == canNext &&
                it.canVotePoll == canVote && it.canVoteskip == canSkip
            ) it
            else it.copy(
                canQueue = canAdd, canQueueNext = canNext,
                canVotePoll = canVote, canVoteskip = canSkip
            )
        }
    }

    /** Clears a closed poll from the panel (a running one can't be dismissed). */
    fun dismissPoll() = update {
        if (it.poll?.closed == true) it.copy(poll = null, myPollVote = null) else it
    }

    private sealed interface ChatInboxItem {
        class Message(val message: ChatMessage) : ChatInboxItem
        data object Clear : ChatInboxItem
    }

    /** Chat on its way from the event loop to the screen; see runChatPump.
     *  Bounded well above what the panel keeps, dropping the oldest. */
    private val chatInbox = Channel<ChatInboxItem>(
        capacity = MAX_CHAT_MESSAGES * 2,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * Gets chat onto the screen in batches: parses each message off the main
     * thread (so its row finds it ready), then applies everything that
     * arrived meanwhile in one state change, at most every
     * CHAT_BATCH_INTERVAL_MS. At 50–100 messages a second, one state change
     * — and one recomposition, one chat scroll, one Niconico enqueue — per
     * message was a steady load on the main thread, which is also the thread
     * the video's TextureView and the sync checks depend on. A quiet channel
     * sees no delay: the first message after a lull goes straight through.
     */
    private suspend fun runChatPump() {
        val batch = ArrayList<ChatInboxItem>()
        while (true) {
            batch += chatInbox.receive()
            while (batch.size < CHAT_BATCH_MAX) batch += chatInbox.tryReceive().getOrNull() ?: break
            val s = _state.value
            for (item in batch) {
                if (item !is ChatInboxItem.Message) continue
                runCatching {
                    ChatHtml.prewarm(
                        raw = item.message.html,
                        greentext = item.message.addClass == "greentext",
                        showImages = s.showEmotes,
                        emotes = s.emotes,
                        revealSpoilers = isTv
                    )
                }
            }
            val items = batch.toList()
            batch.clear()
            withContext(Dispatchers.Main) { runCatching { applyChat(items) } }
            delay(CHAT_BATCH_INTERVAL_MS)
        }
    }

    /** One state change for a whole batch; main thread only. */
    private fun applyChat(items: List<ChatInboxItem>) {
        update { s0 ->
            var s = s0
            var messages = s.messages
            val accepted = ArrayList<ChatMessage>(items.size)
            for (item in items) {
                when (item) {
                    ChatInboxItem.Clear -> {
                        accepted.clear()
                        messages = persistentListOf()
                    }
                    is ChatInboxItem.Message -> {
                        val m = item.message
                        // CyTube resends the channel's recent chat backlog on
                        // every joinChannel — which runs on every socket
                        // reconnect — so without this a network hiccup
                        // replayed old messages as new: duplicated in the
                        // panel and flown across the video again. Deduped on
                        // fields CyTube preserves verbatim on replay.
                        if (!seenChatFingerprints.add(m.fingerprint())) continue
                        if (seenChatFingerprints.size > MAX_CHAT_MESSAGES) {
                            seenChatFingerprints.remove(seenChatFingerprints.first())
                        }
                        // Shadow-muted messages are only meant for moderators; the
                        // server already filters delivery, but drop them defensively.
                        if (m.shadow && s.localRank < 2) continue
                        accepted += m
                        if (m.isPm) s = s.copy(unreadPm = nextUnreadPm(s, m))
                    }
                }
            }
            messages = appendChat(messages, accepted)
            if (messages === s0.messages && s === s0) s0 else s.copy(messages = messages)
        }
    }

    /** Puts a message generated by the app itself (not the server) into
     *  chat, through the same dedupe as real messages. */
    private fun appendLocalNotice(message: ChatMessage) {
        // Behind whatever chat is already on its way, not ahead of it.
        chatInbox.trySend(ChatInboxItem.Message(message))
    }

    /** Puts [item] after the item with uid [afterUid], or at the start / end
     *  for the PlaylistPosition markers (and at the end for an unknown uid,
     *  which is what the server's own client does too). */
    private fun insertAfter(
        list: PersistentList<PlaylistItem>,
        item: PlaylistItem,
        afterUid: Int
    ): PersistentList<PlaylistItem> {
        if (afterUid == PlaylistPosition.START) return list.addingAt(0, item)
        val idx = list.indexOfFirst { it.uid == afterUid }
        return if (idx >= 0) list.addingAt(idx + 1, item) else list.adding(item)
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /**
     * Adds a pasted link to the channel's playlist, at the end or to play
     * next. Returns false (and says why under the box) if the link isn't one
     * CyTube can take, so the panel keeps the text for the user to fix.
     */
    fun queueLink(link: String, playNext: Boolean): Boolean {
        val s = _state.value
        if (!s.canQueue || (playNext && !s.canQueueNext)) return false
        if (s.connection != ConnectionState.CONNECTED) {
            setQueueStatus(QueueStatus("Not connected to the channel.", isError = true))
            return false
        }
        val parsed = MediaLink.parse(link).getOrElse {
            setQueueStatus(QueueStatus(it.message ?: "Couldn't read that link.", isError = true))
            return false
        }
        queueStatusClearJob?.cancel()
        if (MediaLink.shouldFollowRedirects(parsed)) {
            // A short/redirect link (t.co, bit.ly, on.soundcloud.com…) from a
            // share sheet: find out where it really goes, then add THAT, so
            // it reaches the server as e.g. a YouTube video rather than as a
            // "raw file" it would reject.
            update { it.copy(queueStatus = QueueStatus("Checking link…", inProgress = true)) }
            viewModelScope.launch {
                val finalUrl = withContext(Dispatchers.IO) { finalUrlAfterRedirects(parsed.id) }
                val target = finalUrl?.let { MediaLink.parse(it).getOrNull() } ?: parsed
                if (target != parsed) Log.i(TAG, "queue: ${parsed.id} redirects to ${target.type}:${target.id}")
                submitQueue(target, playNext)
            }
        } else {
            submitQueue(parsed, playNext)
        }
        return true
    }

    /** Where [url] ends up after its redirects, or null if that couldn't be
     *  found out (offline, timeout...) — the caller then uses the link as is.
     *  HEAD, so no page or file body is downloaded; OkHttp follows the
     *  redirects itself and the final request's address is the answer. */
    private fun finalUrlAfterRedirects(url: String): String? = runCatching {
        val request = Request.Builder().url(url).head().build()
        redirectHttp.newCall(request).execute().use { it.request.url.toString() }
    }.getOrNull()

    private val redirectHttp by lazy {
        Graph.http.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
    }

    private fun submitQueue(parsed: MediaLink.Parsed, playNext: Boolean) {
        client.queue(parsed.id, parsed.type, atEnd = !playNext)
        update { it.copy(queueStatus = QueueStatus("Adding…", inProgress = true)) }
        pendingQueueJob?.cancel()
        pendingQueueJob = viewModelScope.launch {
            delay(QUEUE_TIMEOUT_MS)
            // The server answers every accepted or refused request, EXCEPT a
            // refusal for lacking permission, which it drops silently.
            finishQueue(QueueStatus(
                "No reply from the channel. You may not be allowed to add videos right now.",
                isError = true
            ))
        }
    }

    private fun finishQueue(status: QueueStatus) {
        pendingQueueJob?.cancel()
        pendingQueueJob = null
        setQueueStatus(status)
    }

    /** Sets the add box's status line; results clear themselves after a while. */
    private fun setQueueStatus(status: QueueStatus) {
        update { it.copy(queueStatus = status) }
        queueStatusClearJob?.cancel()
        queueStatusClearJob = viewModelScope.launch {
            delay(if (status.isError) QUEUE_ERROR_VISIBLE_MS else QUEUE_RESULT_VISIBLE_MS)
            update { if (it.queueStatus == status) it.copy(queueStatus = null) else it }
        }
    }

    /**
     * Server error notices (errorMsg / validationError / queueFail — "queue
     * failed", chat rate limits and the like) share statusMessage with the
     * header's "N connected" line (see ChannelScreen's ConnectionLine), so
     * each is shown for [TRANSIENT_STATUS_MS] and then cleared — but only if
     * it's still the message on screen, so a newer notice or a connection
     * status set in the meantime is left alone.
     */
    private fun showTransientStatus(message: String) {
        update { it.copy(statusMessage = message) }
        transientStatusJob?.cancel()
        transientStatusJob = viewModelScope.launch {
            delay(TRANSIENT_STATUS_MS)
            update { if (it.statusMessage == message) it.copy(statusMessage = null) else it }
        }
    }

    private fun update(block: (ChannelUiState) -> ChannelUiState) {
        _state.value = block(_state.value)
    }

    /** LeadMemory.shared is kept between runs: the first join after a
     *  restart then starts from what opening from that server cost before. */
    private fun restoreLeadMemory() {
        if (leadMemoryRestored) return
        leadMemoryRestored = true
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val saved = leadPrefs().getString(KEY_OPEN_LEADS, null) ?: return@runCatching
                val json = org.json.JSONObject(saved)
                // Android's JSONObject.keys() is an untyped Iterator.
                val restored = LinkedHashMap<String, Double>()
                val keys: Iterator<*> = json.keys()
                while (keys.hasNext()) {
                    val server = keys.next() as? String ?: continue
                    restored[server] = json.optDouble(server)
                }
                LeadMemory.shared.restore(restored)
            }
        }
    }

    /** LeadMemory.learnedCount as of the last save. */
    private var leadsSavedAt = LeadMemory.shared.learnedCount

    /** Saves as soon as something new is learned, not only on leaving: an
     *  app killed while on screen (a crash, a force stop, a reinstall from
     *  Android Studio) never gets to leave, and lost what it had learned. */
    private fun saveLeadMemoryIfLearned() {
        val learned = LeadMemory.shared.learnedCount
        if (learned == leadsSavedAt) return
        leadsSavedAt = learned
        saveLeadMemory()
    }

    private fun saveLeadMemory() {
        val snapshot = LeadMemory.shared.snapshot()
        if (snapshot.isEmpty()) return
        runCatching {
            val json = org.json.JSONObject()
            snapshot.forEach { (server, seconds) -> json.put(server, seconds) }
            leadPrefs().edit().putString(KEY_OPEN_LEADS, json.toString()).apply()
        }
    }

    private fun leadPrefs() =
        getApplication<Application>().getSharedPreferences("sync_memory", android.content.Context.MODE_PRIVATE)

    override fun onCleared() {
        saveLeadMemory()
        syncTicker?.cancel()
        syncJob?.cancel()
        leaderTicker?.cancel()
        player?.release()
        client.disconnect()
        super.onCleared()
    }

    private companion object {
        /** See runChatPump. */
        const val CHAT_BATCH_INTERVAL_MS = 100L
        const val CHAT_BATCH_MAX = 200
        const val KEY_OPEN_LEADS = "open_leads"
        /** Once per process; see restoreLeadMemory. */
        @Volatile var leadMemoryRestored = false
        const val MAX_CHAT_MESSAGES = 300
        const val MAX_GUEST_RETRIES = 3
        const val TAG = "CyTubeChannel"
        /** SyncEngine's corrections and the player's seeks and buffering. */
        const val SYNC_TAG = "CyTubeSync"
        /** The server sends the room's time every 5s; much longer than that
         *  between them means it (or the connection) is running behind. */
        const val SERVER_UPDATE_LATE_MS = 8_000L

        /** How long a server error notice stays in the header — see
         *  showTransientStatus. */
        const val TRANSIENT_STATUS_MS = 6_000L

        /** See queueLink. The server normally answers within a second or two
         *  (YouTube lookups included). */
        const val QUEUE_TIMEOUT_MS = 12_000L
        const val QUEUE_RESULT_VISIBLE_MS = 4_000L
        const val QUEUE_ERROR_VISIBLE_MS = 8_000L

        /** Minimum time between actual [refresh] runs — the anti-spam gate
         *  on tapping the channel name. Comfortably longer than the 600ms
         *  the in-flight `refreshing` flag itself is held for, so a tap
         *  right as the previous refresh clears is still rejected, not just
         *  a tap that lands mid-refresh. */
        const val REFRESH_COOLDOWN_MS = 4_000L

        /** How long after the server refuses a connection to try again:
         *  just over the 10 s CyTube takes to give an IP address another
         *  connection (see onConnectionRefused). */
        const val REFUSED_RETRY_MS = 11_000L

        /** How long after a new item or player SyncEngine holds off on all
         *  position correction (seeks and speed nudges) — see
         *  SyncEngine.apply. Long enough to cover Google Drive / NewPipe resolution +
         *  initial container parsing and buffering; short enough that a
         *  channel that's genuinely out of sync still gets corrected quickly. */
        const val SYNC_GRACE_MS = 3_500L

        /** [onPlaybackStall] ignores anything shorter than this — a quick
         *  rebuffer after an ordinary seek (a manual scrub, or SyncEngine's
         *  own hard-seek correction) settles in well under this on a fine
         *  connection.
         *
         *  2_000L, not PlayerSurface's own 3_000L STALL_TRIGGER_MS: PlayerSurface
         *  calls onStall for two different reasons — one sustained stall of at
         *  least 3s, OR 2+ smaller stalls in its recent window totaling at
         *  least 2s — and reports the measured duration for either. 2_000L is
         *  the true minimum PlayerSurface can
         *  ever report when it has decided a stall is worth acting on, so this
         *  still only screens out call sites this function doesn't control. */
        const val QUALITY_DOWNGRADE_STALL_THRESHOLD_MS = 2_000L

        /** Minimum time between quality downgrades — gives the player enough
         *  time to establish a stable buffer on the new quality before evaluating
         *  whether another step down is needed. */
        const val QUALITY_CHANGE_COOLDOWN_MS = 20_000L
    }
}
