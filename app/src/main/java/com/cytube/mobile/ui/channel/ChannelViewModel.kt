package com.cytube.mobile.ui.channel

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.cytube.mobile.data.CHANNEL_NAME_REGEX
import com.cytube.mobile.data.CompatMode
import com.cytube.mobile.data.Settings
import com.cytube.mobile.data.SettingsStore
import com.cytube.mobile.di.Graph
import com.cytube.mobile.net.*
import com.cytube.mobile.player.BandwidthEstimate
import com.cytube.mobile.player.GoogleDriveResolver
import com.cytube.mobile.player.PeerTubeResolver
import com.cytube.mobile.player.StreamableResolver
import com.cytube.mobile.player.YouTubeResolver
import com.cytube.mobile.player.PlayerHandle
import com.cytube.mobile.ui.defaultSyncAccuracy
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    val showEmotes: Boolean = true,
    /** Mirrors the Settings toggle; MainActivity reads this (via PlaybackHost)
     *  to decide whether leaving the app should float the video in PiP or
     *  just pause it. */
    val pipEnabled: Boolean = true,
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
    /** Non-null when a freshly-selected item cannot be played natively at all
     *  (e.g. Google Drive without the userscript metadata) and we're asking
     *  whether to load it in WebView. Distinct from [playbackOffer], which is
     *  only for a backend that was actually running and then failed. */
    val compatOffer: String? = null,
    val refreshing: Boolean = false,
    /** A hook for forcibly rebuilding the player surface from scratch (see
     *  PlayerSurface's ExoSurface: `remember(epoch) { ... }`), and, for
     *  NEWPIPE/GDRIVE, re-resolving the stream URL too, since both key their
     *  resolve step on this same value (see NewPipeSurface/GDriveSurface's
     *  `LaunchedEffect(media.id, epoch)`). Deliberately never bumped by
     *  pull-to-refresh — that silently restarted playback on every refresh,
     *  even when nothing was actually wrong with the player. Now bumped by
     *  onPlaybackStall/maybeUpgradeQuality (see [nativeQualityIndex]) to
     *  reload at a new quality in place — still left available for a future
     *  "the player is genuinely stuck" recovery action too. */
    val playerEpoch: Int = 0,
    /** Which entry of the current item's MediaFrame.direct (already sorted
     *  highest-to-lowest — see DirectSource.parse) the NATIVE backend should
     *  load; 0 is the default, matching MediaFrame.bestSource exactly, same
     *  as before this field existed. Stepped by onPlaybackStall (down, on a
     *  real mid-playback stall) — see PlayerSurface's own qualityIndex param.
     *  Latches to the stable lower resolution for the duration of the media
     *  item to avoid mid-stream decoder reset flapping.
     *  Reset to 0 on every genuine item change (onMediaChanged/pickPersonal/
     *  stopPersonalPick) so a downgrade never outlives the item that caused
     *  it. Meaningless for any player type other than NATIVE. */
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
     *  back on (or manually clearing the pick) can snap straight back. */
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
    /** Shown under the playlist panel's add box; null when there's nothing to say. */
    val queueStatus: QueueStatus? = null,
    /** Who the chat box is currently sending private messages to, or null
     *  for normal public chat — see startPm/cancelPm. Phone only; TV never
     *  sets it. */
    val pmTarget: String? = null,
    val unreadPm: UnreadPm? = null
) {
    val isLeader: Boolean get() = leader != null && leader == localUser
}

class ChannelViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = SettingsStore(app)
    private val client = Graph.newClient(app)
    private val sync = SyncEngine()

    private val _state = MutableStateFlow(ChannelUiState())
    val state: StateFlow<ChannelUiState> = _state.asStateFlow()

    private var player: PlayerHandle? = null
    /** elapsedRealtime() of the last genuinely new player attach, so
     *  onTimeUpdate can tell SyncEngine "give this one a moment" right after
     *  a media switch — see SyncEngine.apply's withinGracePeriod doc. */
    private var playerAttachedAtMs: Long = 0L
    /** Anchor for embedSyntheticTimeSeconds() — see its own doc comment. Set
     *  wherever state.player becomes EMBED (onMediaChanged, setMode,
     *  reportPlaybackFailure's silent fallback); meaningless otherwise. */
    private var embedClockAnchorAtMs: Long = 0L
    private var embedClockAnchorSeconds: Double = 0.0
    /** elapsedRealtime() of the last quality step — the debounce for
     *  onPlaybackStall measures against this. Reset to 0 whenever
     *  ChannelUiState.nativeQualityIndex itself resets (a genuine item
     *  change), so a fresh item's first stall isn't held back by a cooldown
     *  that belonged to the previous video. */
    private var lastQualityChangeAtMs: Long = 0L
    private var qualityUpgradeAttempts: Int = 0
    /** Session-remembered preferred resolution height (e.g. 720, 480).
     *  Maintains a realistic quality ceiling across playlist items so the player
     *  doesn't re-stall on every item on a bandwidth-constrained connection. */
    private var sessionPreferredQualityHeight: Int? = null
    /** Timestamp when the current quality level started playing stall-free. */
    private var qualityStableSinceMs: Long = 0L
    private var settings: Settings = Settings(syncAccuracy = defaultSyncAccuracy(app))
    private var leaderTicker: Job? = null
    private var syncTicker: Job? = null
    private var syncJob: Job? = null
    private var lastServerTimeSeconds: Double = 0.0
    private var lastServerTimeElapsedRealtimeMs: Long = 0L
    private var isServerPaused: Boolean = false
    private var joined = false
    private var chatSeq = 0L
    private var guestRetries = 0
    /** Fingerprints of recently-appended chat messages — see the dedupe check
     *  in the CyTubeEvent.Chat branch below. Bounded to MAX_CHAT_MESSAGES so
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
                    // straight back to the channel's real current item, same
                    // as if the user had cleared the pick themselves — see
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
            launch { startSyncTicker() }

            connect(channel)
        }
    }

    private suspend fun connect(channel: String) {
        playerAttachedAtMs = SystemClock.elapsedRealtime()
        _state.value = _state.value.copy(connection = ConnectionState.CONNECTING)
        guestRetries = 0
        val credential = savedCredential() ?: CyTubeClient.Credential.Guest(guestName())
        runCatching { client.connect(channel, credential) }
            .onFailure {
                _state.value = _state.value.copy(
                    connection = ConnectionState.FAILED,
                    statusMessage = it.message ?: "Could not reach the channel"
                )
            }
    }

    private suspend fun observeEvents() {
        client.events.collect { event ->
            // A bug in any single branch below must not kill this collector —
            // there's no restart, so an uncaught exception here would silently
            // stop all future chat/playlist/user updates for the rest of the
            // channel session (or crash the process outright).
            runCatching {
            when (event) {
                is CyTubeEvent.Connected ->
                    update { it.copy(connection = ConnectionState.CONNECTED, statusMessage = null) }

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

                is CyTubeEvent.NeedPassword ->
                    update { it.copy(needsPassword = true, passwordWasWrong = event.wrongPasswordTried) }

                is CyTubeEvent.PasswordAccepted ->
                    update { it.copy(needsPassword = false, passwordWasWrong = false) }

                is CyTubeEvent.LoginResult -> {
                    update {
                        it.copy(
                            localUser = event.name ?: it.localUser,
                            statusMessage = if (!event.success) event.error else null
                        )
                    }
                    // Only guests can collide on a name (an account login failing
                    // means a bad password, which retrying can't fix). Try a
                    // fresh random name a few times before giving up and leaving
                    // the error on screen. This is a one-off retry, not a saved
                    // preference — it never touches settingsStore, so it doesn't
                    // clobber a name the user chose on the Account screen.
                    if (!event.success &&
                        savedCredential() == null &&
                        guestRetries < MAX_GUEST_RETRIES
                    ) {
                        guestRetries++
                        client.retryGuestLogin("Guest" + (1000..9999).random())
                    }
                }

                is CyTubeEvent.RankChanged -> {
                    update { it.copy(localRank = event.rank) }
                    refreshPermissions()
                }
                is CyTubeEvent.PermissionsChanged -> {
                    channelPermissions = event.permissions
                    refreshPermissions()
                }
                is CyTubeEvent.PlaylistLocked -> {
                    playlistOpen = !event.locked
                    refreshPermissions()
                }
                is CyTubeEvent.QueueFailed ->
                    if (pendingQueueJob?.isActive == true) finishQueue(QueueStatus(event.message, isError = true))
                    else showTransientStatus(event.message)

                is CyTubeEvent.MediaChanged -> onMediaChanged(event.media)
                is CyTubeEvent.MediaTimeUpdate -> {
                    // Both of these describe the CHANNEL's own current item —
                    // meaningless while a personal pick (see pickPersonal) is
                    // what's actually on screen, and applying either would
                    // show/hide the wrong play state or fight the personal
                    // player's own position. onTimeUpdate has its own,
                    // separate personalPickActive guard for the same reason.
                    if (!_state.value.personalPickActive && event.update.paused == _state.value.playing) {
                        update { it.copy(playing = !event.update.paused) }
                    }
                    onTimeUpdate(event.update)
                }

                is CyTubeEvent.PlaylistReplaced -> update { it.copy(playlist = event.items.toPersistentList()) }
                is CyTubeEvent.CurrentItemChanged -> update { it.copy(currentUid = event.uid) }
                is CyTubeEvent.ItemQueued -> {
                    update { s ->
                        val idx = s.playlist.indexOfFirst { it.uid == event.afterUid }
                        val next = if (idx >= 0) s.playlist.add(idx + 1, event.item) else s.playlist.add(event.item)
                        s.copy(playlist = next)
                    }
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
                is CyTubeEvent.ItemDeleted -> update { s ->
                    s.copy(playlist = s.playlist.removeAll { it.uid == event.uid })
                }

                is CyTubeEvent.Chat -> {
                    // CyTube resends the channel's recent chat backlog on
                    // every joinChannel — CyTubeClient.replaySession runs on
                    // every socket reconnect, not just the first one — so an
                    // ordinary mobile-network hiccup on an otherwise-quiet
                    // channel was replaying the same old messages back in as
                    // if they'd just been said: duplicating them in the chat
                    // panel, and making NekoChatOverlay fly them across the
                    // screen again, since each replay gets a fresh, higher
                    // seq than anything Neko has already spawned. Dedupe on
                    // the fields CyTube preserves verbatim on replay (not
                    // anything this client assigns itself) before any of
                    // that has a chance to happen.
                    if (seenChatFingerprints.add(event.message.fingerprint())) {
                        if (seenChatFingerprints.size > MAX_CHAT_MESSAGES) {
                            seenChatFingerprints.remove(seenChatFingerprints.first())
                        }
                        val currentEmotes = _state.value.emotes
                        val currentShowEmotes = _state.value.showEmotes
                        // Pre-warm parsing/sanitization on Dispatchers.Default so
                        // expensive HTML parsing & regex matching are kept off the Main thread.
                        withContext(Dispatchers.Default) {
                            ChatHtml.prewarm(
                                raw = event.message.html,
                                greentext = event.message.addClass == "greentext",
                                showImages = currentShowEmotes,
                                emotes = currentEmotes
                            )
                        }
                        update { s ->
                            // Shadow-muted messages are only meant for moderators; the
                            // server already filters delivery, but drop them defensively.
                            if (event.message.shadow && s.localRank < 2) s
                            else s.copy(
                                messages = appendChat(s.messages, event.message),
                                unreadPm = nextUnreadPm(s, event.message)
                            )
                        }
                    }
                }
                is CyTubeEvent.ChatCleared -> update { it.copy(messages = persistentListOf()) }

                is CyTubeEvent.UserListReplaced -> update { it.copy(users = event.users.toPersistentList()) }
                is CyTubeEvent.UserJoined -> update { s ->
                    s.copy(users = s.users.removeAll { it.name == event.user.name }.add(event.user))
                }
                is CyTubeEvent.UserLeft -> update { s ->
                    s.copy(users = s.users.removeAll { it.name == event.name })
                }
                is CyTubeEvent.UserMetaChanged -> update { s ->
                    // set(idx, ...) rather than map{} over everyone: a plain
                    // map would rebuild the whole list (and force a fresh
                    // PersistentList, defeating the structural sharing this
                    // type exists for) even though at most one entry ever
                    // actually changes here.
                    val idx = s.users.indexOfFirst { it.name == event.user.name }
                    if (idx < 0) s else s.copy(users = s.users.set(idx, event.user))
                }
                is CyTubeEvent.UserCount -> update { it.copy(userCount = event.count) }

                is CyTubeEvent.LeaderChanged -> {
                    update { it.copy(leader = event.name) }
                    retuneLeaderTicker()
                }

                is CyTubeEvent.Emotes ->
                    update { s -> s.copy(emotes = EmoteSet.from(event.emotes)) }
                is CyTubeEvent.EmoteUpdated ->
                    update { s -> s.copy(emotes = s.emotes.withUpdated(event.emote)) }
                is CyTubeEvent.EmoteRenamed ->
                    update { s -> s.copy(emotes = s.emotes.withRenamed(event.oldName, event.emote)) }
                is CyTubeEvent.EmoteRemoved ->
                    update { s -> s.copy(emotes = s.emotes.withRemoved(event.name)) }

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

                else -> Unit
            }
            }.onFailure { Log.e("CyTube", "Error handling ${event::class.simpleName}", it) }
        }
    }

    // ---- media ----

    /** RTT-compensated server time, consolidated: onMediaChanged (both of its
     *  call sites) and onTimeUpdate each used to hand-roll this exact same
     *  two-line calculation independently — three copies that could (and had
     *  started to) quietly drift apart. */
    private fun compensatedTime(paused: Boolean, currentTime: Double, lengthSeconds: Int): Double {
        val rttCompSeconds = if (paused || currentTime < 0) 0.0 else (client.estimatedRttMs / 2000.0).coerceIn(0.0, 0.5)
        return if (lengthSeconds > 0 && currentTime + rttCompSeconds > lengthSeconds) lengthSeconds.toDouble()
        else currentTime + rttCompSeconds
    }

    private fun onMediaChanged(media: MediaFrame) {
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
            lastServerTimeSeconds = compensatedTime(media.paused, media.currentTime, media.seconds)
            lastServerTimeElapsedRealtimeMs = SystemClock.elapsedRealtime()
            isServerPaused = media.paused
            update {
                if (it.personalPickActive) it.copy(channelCurrentMedia = media)
                else it.copy(media = media, channelCurrentMedia = media, playing = !media.paused)
            }
            evaluateSync()
            return
        }

        // Personally browsing: the channel's real item genuinely changed
        // underneath us, but nothing about what's on screen should move —
        // just keep channelCurrentMedia current so stopPersonalPick (a
        // manual clear, or sync flipping back on) lands on the right thing.
        if (_state.value.personalPickActive) {
            update { it.copy(channelCurrentMedia = media) }
            return
        }

        val (chosen, offer) = choosePlayerAndOffer(media)
        Log.i(TAG, "changeMedia type=${media.type} player=$chosen " +
            "seconds=${media.seconds} sources=${media.direct.size} id=${media.id}")
        if (chosen == MediaTypes.Player.EMBED) anchorEmbedClock(media.currentTime)
        val initialQualityIndex = resolveInitialQualityIndex(media)
        lastQualityChangeAtMs = 0L
        qualityStableSinceMs = SystemClock.elapsedRealtime()
        playerAttachedAtMs = SystemClock.elapsedRealtime()
        lastServerTimeSeconds = compensatedTime(media.paused, media.currentTime, media.seconds)
        lastServerTimeElapsedRealtimeMs = SystemClock.elapsedRealtime()
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
                nativeQualityIndex = initialQualityIndex
            )
        }
        evaluateSync()
        // The player composable observes state.media and rebuilds the backend;
        // once it is ready it calls playerReady() below.
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
            // Real measurements only — see BandwidthEstimate. Was the
            // player handle's estimate, which reported Media3's per-country
            // default before anything had downloaded, and was null whenever
            // no player happened to be attached at an item boundary.
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
        if (s.personalPickActive) return
        if (s.isLeader || !settings.syncEnabled) {
            // SyncEngine may have left a rate nudge running; don't let the
            // player carry on at 1.1x once sync stops being applied.
            sync.stopNudge(p)
            return
        }
        if (lastServerTimeElapsedRealtimeMs == 0L) return

        val now = SystemClock.elapsedRealtime()
        val elapsedSeconds = if (isServerPaused) 0.0 else (now - lastServerTimeElapsedRealtimeMs) / 1000.0
        val currentServerTime = (lastServerTimeSeconds + elapsedSeconds).let { time ->
            val length = s.media?.seconds ?: 0
            if (length > 0 && time > length) length.toDouble() else time
        }
        val withinGrace = now - playerAttachedAtMs < SYNC_GRACE_MS
        val update = TimeUpdate(currentTime = currentServerTime, paused = isServerPaused)

        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            runCatching {
                sync.apply(
                    player = p,
                    update = update,
                    newMediaId = s.media?.id,
                    isLeader = s.isLeader,
                    syncEnabled = settings.syncEnabled,
                    accuracySeconds = settings.syncAccuracy,
                    withinGracePeriod = withinGrace
                )
            }
        }
    }

    private fun onTimeUpdate(update: TimeUpdate) {
        val length = _state.value.media?.seconds ?: 0
        lastServerTimeSeconds = compensatedTime(update.paused, update.currentTime, length)
        lastServerTimeElapsedRealtimeMs = SystemClock.elapsedRealtime()
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
     * Shared by quality step-downs: updates
     * [ChannelUiState.nativeQualityIndex] so PlayerSurface can switch quality
     * in place on the existing player without tearing down ExoPlayer or bumping
     * playerEpoch.
     */
    private fun reloadAtQuality(index: Int) {
        update { st ->
            val m = st.media
            if (m == null || st.player != MediaTypes.Player.NATIVE) return@update st
            st.copy(
                nativeQualityIndex = index
            )
        }
    }

    /**
     * A newly-attached backend (fresh media, or a player-type switch) starts
     * unmuted at the ExoPlayer/NewPipe level regardless of what the user had
     * chosen before — PlayerHandle has no memory of it. Re-apply the current
     * mute state here so switching items, or falling back to a different
     * player, never silently un-mutes audio the user turned off.
     */
    fun attachPlayer(handle: PlayerHandle?) {
        if (handle != null && handle !== player) playerAttachedAtMs = android.os.SystemClock.elapsedRealtime()
        player = handle
        if (handle != null) {
            handle.setVolume(if (_state.value.muted) 0f else 1f)
            client.signalPlayerReady()
            evaluateSync()
        }
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

    /** Wired to the PiP window's own play/pause action. */
    fun togglePlaybackFromPip() {
        val p = player ?: return
        if (p.isPaused) p.play() else p.pause()
    }

    /** Wired to MainActivity's onStop/onStart via ChannelScreen's
     *  isAppInBackground param. Only touches the socket's reconnect cadence
     *  (see CyTubeClient.setBackgrounded) — the video-track/audio-only
     *  switch is handled entirely in ChannelScreen/PlayerSurface, since this
     *  ViewModel has no reference to the ExoPlayer instance itself. */
    fun onAppBackgroundChanged(background: Boolean) {
        client.setBackgrounded(background)
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
                // just sits out each tick instead — same as the existing
                // "nothing meaningful to broadcast" case below, and no worse
                // than any other leader going briefly idle between items.
                if (_state.value.personalPickActive) continue
                val p = player
                when {
                    p != null -> client.sendMediaUpdate(p.currentTimeSeconds(), p.isPaused)
                    // No PlayerHandle, but EMBED is still a real, watched
                    // item, not a torn-down player — see
                    // embedSyntheticTimeSeconds's own doc comment for why
                    // broadcasting an extrapolated position beats leaving
                    // the whole room's authoritative currentTime frozen for
                    // as long as this item plays.
                    _state.value.player == MediaTypes.Player.EMBED ->
                        client.sendMediaUpdate(embedSyntheticTimeSeconds(), false)
                    // Otherwise (WEB, or genuinely between items) there is
                    // nothing meaningful to broadcast — same as before.
                }
            }
        }
    }

    /** Sets the (wall-clock, position) anchor embedSyntheticTimeSeconds()
     *  extrapolates from. Call whenever state.player is about to become
     *  EMBED, with the best position already known for that item. */
    private fun anchorEmbedClock(startSeconds: Double) {
        embedClockAnchorAtMs = android.os.SystemClock.elapsedRealtime()
        embedClockAnchorSeconds = startSeconds.coerceAtLeast(0.0)
    }

    /**
     * EMBED has no PlayerHandle — no ExoPlayer, no currentTimeSeconds() to
     * read, since the video is inside a WebView running a provider's own JS
     * player (YouTube's, Dailymotion's, Vimeo's, or, for a custom cu/bc/bn
     * embed, whatever arbitrary page a channel operator pasted, with no
     * consistent API at all). Building a real per-provider JS bridge to read
     * true position back out isn't something one implementation could cover
     * for all of them.
     *
     * What this does instead, only for the room LEADER's own broadcast (see
     * retuneLeaderTicker): extrapolate forward from wall-clock time elapsed
     * since the item's own known starting position (embedClockAnchorSeconds
     * as of embedClockAnchorAtMs — see anchorEmbedClock), assuming ordinary
     * 1x playback. That's the common case; the one thing this can't detect
     * is the far rarer case of the viewer manually pausing the embed by
     * hand, which is also why retuneLeaderTicker always reports paused=false
     * here rather than guessing. Anyone actually watching natively already
     * corrects against small drift via the normal accuracySeconds tolerance
     * (see SyncEngine), so this costs nothing that slow network/buffering
     * wasn't already costing elsewhere — the alternative was the room's
     * authoritative currentTime simply freezing for as long as the leader's
     * item stayed on EMBED, which is worse for everyone in the channel, not
     * just the leader.
     */
    private fun embedSyntheticTimeSeconds(): Double {
        val elapsedSeconds = (android.os.SystemClock.elapsedRealtime() - embedClockAnchorAtMs) / 1000.0
        return embedClockAnchorSeconds + elapsedSeconds
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
     * is returned so the caller can ask first. This is for types with no native
     * or resolver-backed path at all (Vimeo/Dailymotion/Twitch/etc without
     * meta.direct) — YouTube and Google Drive have their own resolvers and so
     * never reach this branch; see MediaTypes.playerFor.
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

    // Google Drive is not handled here: it now resolves natively (see
    // GoogleDriveResolver), so it never reaches choosePlayerAndOffer with
    // Player.WEB. If that resolution itself fails at runtime, that goes
    // through reportPlaybackFailure/playbackOffer instead, same as any other
    // native player that started and then failed.
    private fun compatOfferReason(media: MediaFrame): String =
        "${MediaTypes.label(media.type)} needs Compatibility View"

    /**
     * A player backend actually started and then failed. We never switch to the
     * WebView on our own — the user is asked, because a silent jump to a
     * different player is exactly the kind of thing that makes the app feel
     * like it is fighting you.
     *
     * Google Drive is not special-cased here — it goes through the same
     * playbackOffer dialog as every other backend that started and failed
     * (see the comment on [compatOfferReason]). It used to be excluded from
     * this entirely and just got a status-line note instead — that note rode
     * on `statusMessage`, the same field the TopAppBar's connection subtitle
     * uses for "N connected" (see ConnectionLine), and nothing ever cleared
     * it afterward, so it sat there permanently, on connect, LOOKING like a
     * channel-set title rather than a one-off failure notice. Letting it
     * offer the fallback like everything else fixes both: it's the standard
     * dialog instead of a hijacked status line, and it actually goes away
     * once handled.
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
     * case falls through to the WebView offer like it always did.
     */
    fun reportPlaybackFailure(reason: String) {
        val m = _state.value.media
        Log.w(TAG, "playback failed backend=${_state.value.player} " +
            "type=${m?.type} id=${m?.id}: $reason")
        // A resolved stream URL that just failed (expired, 403, taken down)
        // would otherwise stay cached for 5-10 minutes, so rejoining or the
        // item coming round again would retry the same dead link.
        if (m != null) {
            when (_state.value.player) {
                MediaTypes.Player.NEWPIPE -> YouTubeResolver.invalidate(m.id)
                MediaTypes.Player.GDRIVE -> GoogleDriveResolver.invalidate(m.id)
                MediaTypes.Player.STREAMABLE -> StreamableResolver.invalidate(m.id)
                MediaTypes.Player.PEERTUBE -> PeerTubeResolver.invalidate(m.id)
                else -> Unit
            }
        }
        if (_state.value.effectiveMode == CompatMode.WEB) return
        val embeddable = m?.embedPlayableSrc
        if (embeddable != null && _state.value.player != MediaTypes.Player.EMBED) {
            Log.d(TAG, "playback fallback: switching to single-video view at $embeddable")
            // Best-effort: the failed backend's own handle is still readable
            // right up to this call in the common case, so anchor the
            // synthetic clock (see embedSyntheticTimeSeconds) from wherever
            // it actually got to rather than from the item's original,
            // possibly long-stale, load-time position. currentTimeSeconds()
            // is suspend, so the handle is captured synchronously now (before
            // the backend actually switches away under it) and read inside a
            // coroutine.
            val failedHandle = player
            val fallbackSeconds = m?.currentTime ?: 0.0
            viewModelScope.launch {
                val bestKnownSeconds = runCatching { failedHandle?.currentTimeSeconds() }.getOrNull()
                    ?: fallbackSeconds
                anchorEmbedClock(bestKnownSeconds)
                update { it.copy(player = MediaTypes.Player.EMBED) }
            }
            return
        }
        update { it.copy(playbackOffer = reason) }
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
     * playlist and leader state over the existing connection. This used to
     * also bump playerEpoch, which tears the whole ExoPlayer instance down
     * and rebuilds it from scratch (see PlayerSurface's ExoSurface:
     * `remember(epoch) { ExoPlayer.Builder(...).build() }`) — that meant
     * every refresh silently restarted whatever was already playing fine,
     * even when nothing was actually wrong with playback.
     *
     * Reached by tapping the channel name in the TopAppBar (see
     * ChannelScreen) rather than a pull gesture now — same action, moved
     * somewhere deliberate rather than one swipe away from scrolling chat.
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
        val now = android.os.SystemClock.elapsedRealtime()
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
     * prompt, indistinguishable from the old "just defaults to WebView" bug
     * this was built to fix.
     */
    fun setMode(mode: CompatMode, persist: Boolean = true) {
        viewModelScope.launch {
            if (persist) settingsStore.setChannelCompat(_state.value.channel, mode)
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
                    if (chosen == MediaTypes.Player.EMBED) anchorEmbedClock(m.currentTime)
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

    fun submitPassword(pw: String) = client.sendPassword(pw)
    fun voteSkip() = client.voteSkip()
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
     * changeMedia goes through (choosePlayerAndOffer, anchorEmbedClock) so
     * a personal pick behaves identically to the channel's own current item
     * in every way except who it's visible to and what drives it forward.
     */
    fun pickPersonal(item: PlaylistItem) {
        if (_state.value.syncEnabled) return
        if (!MediaTypes.canResolveIndependently(item.type)) return
        val frame = MediaFrame.fromPlaylistItem(item)
        val (chosen, offer) = choosePlayerAndOffer(frame)
        Log.i(TAG, "personal pick type=${frame.type} player=$chosen id=${frame.id} uid=${item.uid}")
        if (chosen == MediaTypes.Player.EMBED) anchorEmbedClock(frame.currentTime)
        val initialQualityIndex = resolveInitialQualityIndex(frame)
        // A genuinely different item from whatever was loaded before — see
        // ChannelUiState.nativeQualityIndex's own doc comment.
        lastQualityChangeAtMs = 0L
        qualityStableSinceMs = SystemClock.elapsedRealtime()
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
    }

    /**
     * Clears a personal pick and snaps back to whatever the channel's real
     * current item actually is (kept up to date the whole time by
     * onMediaChanged, even while it wasn't what was on screen). Called both
     * from an explicit user action (e.g. a "back to channel" control) and
     * automatically when sync is switched back on (see the settings
     * collector in start()).
     */
    fun stopPersonalPick() {
        if (!_state.value.personalPickActive) return
        val channelMedia = _state.value.channelCurrentMedia
        if (channelMedia == null) {
            update { it.copy(personalPickActive = false, personalPickUid = -1) }
            return
        }
        val (chosen, offer) = choosePlayerAndOffer(channelMedia)
        if (chosen == MediaTypes.Player.EMBED) anchorEmbedClock(channelMedia.currentTime)
        val initialQualityIndex = resolveInitialQualityIndex(channelMedia)
        // Snapping back to the channel's own item is also a genuine item
        // change from whatever was personally loaded — see
        // ChannelUiState.nativeQualityIndex's own doc comment.
        lastQualityChangeAtMs = 0L
        qualityStableSinceMs = SystemClock.elapsedRealtime()
        lastServerTimeSeconds = channelMedia.currentTime
        lastServerTimeElapsedRealtimeMs = SystemClock.elapsedRealtime()
        isServerPaused = channelMedia.paused
        update {
            it.copy(
                media = channelMedia,
                player = chosen,
                playing = !channelMedia.paused,
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
     * Appends one message and trims the buffer. Previously an ArrayList(current
     * size - keepFrom + 1) rebuilt from a manual copy loop — a full copy of up
     * to MAX_CHAT_MESSAGES items on EVERY single incoming chat message, plain
     * List having no way to share structure between the old and new list.
     * PersistentList.mutate {} uses a Builder (an efficient, temporarily-mutable
     * view over the same underlying structure) so add/removeAt here don't copy
     * the whole buffer — the common case (already at MAX_CHAT_MESSAGES) is one
     * structural-sharing add plus one removeAt(0), not a fresh 300-element copy
     * per message. This is what a chat flood was actually paying for, on top of
     * the Compose recomposition cost fixed by PersistentList's stability.
     */
    private fun appendChat(current: PersistentList<ChatMessage>, message: ChatMessage): PersistentList<ChatMessage> {
        val tagged = message.copy(seq = ++chatSeq)
        return current.mutate { list ->
            list.add(tagged)
            while (list.size > MAX_CHAT_MESSAGES) list.removeAt(0)
        }
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
    /** CyTube's "open playlist" (unlocked) state; see Permissions.allowsPlaylistAction. */
    private var playlistOpen = false
    /** Waiting on the server's answer to our last queue request; also the timeout. */
    private var pendingQueueJob: Job? = null
    private var queueStatusClearJob: Job? = null

    private fun refreshPermissions() {
        val perms = channelPermissions
        val rank = _state.value.localRank
        val canAdd = perms?.allowsPlaylistAction("playlistadd", rank, playlistOpen) == true
        val canNext = canAdd && perms?.allowsPlaylistAction("playlistnext", rank, playlistOpen) == true
        val canVote = perms?.allows("pollvote", rank) ?: true
        update {
            if (it.canQueue == canAdd && it.canQueueNext == canNext && it.canVotePoll == canVote) it
            else it.copy(canQueue = canAdd, canQueueNext = canNext, canVotePoll = canVote)
        }
    }

    /** Clears a closed poll from the panel (a running one can't be dismissed). */
    fun dismissPoll() = update {
        if (it.poll?.closed == true) it.copy(poll = null, myPollVote = null) else it
    }

    /** Puts a message generated by the app itself (not the server) into
     *  chat, through the same dedupe as real messages. */
    private fun appendLocalNotice(message: ChatMessage) {
        if (!seenChatFingerprints.add(message.fingerprint())) return
        if (seenChatFingerprints.size > MAX_CHAT_MESSAGES) {
            seenChatFingerprints.remove(seenChatFingerprints.first())
        }
        update { it.copy(messages = appendChat(it.messages, message)) }
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
     * header's "N connected" line (see ChannelScreen's ConnectionLine), and
     * nothing used to clear them, so one stayed in the header until the next
     * reconnect. Shown for [TRANSIENT_STATUS_MS], then cleared — but only if
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

    override fun onCleared() {
        syncTicker?.cancel()
        syncJob?.cancel()
        leaderTicker?.cancel()
        player?.release()
        client.disconnect()
        super.onCleared()
    }

    private companion object {
        const val MAX_CHAT_MESSAGES = 300
        const val MAX_GUEST_RETRIES = 3
        const val TAG = "CyTubeChannel"

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

        /** How long after a new player attaches SyncEngine holds off on
         *  hard seeks — see SyncEngine.apply's withinGracePeriod
         *  doc. Long enough to cover Google Drive / NewPipe resolution +
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
         *  least 2s — and now reports the true measured duration for either
         *  (it used to inflate every report to at least 3_000L, which happened
         *  to always clear whatever floor was set here, silently turning this
         *  check into a no-op). 2_000L is the true minimum PlayerSurface can
         *  ever report when it has decided a stall is worth acting on, so this
         *  still only screens out call sites this function doesn't control. */
        const val QUALITY_DOWNGRADE_STALL_THRESHOLD_MS = 2_000L

        /** Minimum time between quality downgrades — gives the player enough
         *  time to establish a stable buffer on the new quality before evaluating
         *  whether another step down is needed. */
        const val QUALITY_CHANGE_COOLDOWN_MS = 20_000L
    }
}
