package com.cytube.mobile.ui.channel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.cytube.mobile.data.CHANNEL_NAME_REGEX
import com.cytube.mobile.data.CompatMode
import com.cytube.mobile.data.Settings
import com.cytube.mobile.data.SettingsStore
import com.cytube.mobile.di.Graph
import com.cytube.mobile.net.*
import com.cytube.mobile.player.PlayerHandle
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class ConnectionState { CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, FAILED }

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
     *  real mid-playback stall) and maybeUpgradeQuality (back up, after a
     *  stall-free stretch) — see PlayerSurface's own qualityIndex param.
     *  Reset to 0 on every genuine item change (onMediaChanged/pickPersonal/
     *  stopPersonalPick) so a downgrade never outlives the item that caused
     *  it. Meaningless for any player type other than NATIVE. */
    val nativeQualityIndex: Int = 0,
    /** The channel's currently running poll, or null when none is active. */
    val poll: Poll? = null,
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
    val channelCurrentMedia: MediaFrame? = null
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
    /** elapsedRealtime() of the last quality step (either direction) — the
     *  debounce for onPlaybackStall and the recheck interval for
     *  maybeUpgradeQuality both measure against this. Reset to 0 whenever
     *  ChannelUiState.nativeQualityIndex itself resets (a genuine item
     *  change), so a fresh item's first stall isn't held back by a cooldown
     *  that belonged to the previous video. */
    private var lastQualityChangeAtMs: Long = 0L
    private var settings: Settings = Settings()
    private var leaderTicker: Job? = null
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
                    if (it.syncEnabled && !wasSyncEnabled) stopPersonalPick()
                }
            }
            launch { observeEvents() }

            connect(channel)
        }
    }

    private suspend fun connect(channel: String) {
        _state.value = _state.value.copy(connection = ConnectionState.CONNECTING)
        guestRetries = 0
        val credential = Graph.auth(getApplication()).credentialForSession()
            ?: CyTubeClient.Credential.Guest(guestName())
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
                        Graph.auth(getApplication()).credentialForSession() == null &&
                        guestRetries < MAX_GUEST_RETRIES
                    ) {
                        guestRetries++
                        client.retryGuestLogin("Guest" + (1000..9999).random())
                    }
                }

                is CyTubeEvent.RankChanged -> update { it.copy(localRank = event.rank) }

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
                is CyTubeEvent.ItemQueued -> update { s ->
                    val idx = s.playlist.indexOfFirst { it.uid == event.afterUid }
                    val next = if (idx >= 0) s.playlist.add(idx + 1, event.item) else s.playlist.add(event.item)
                    s.copy(playlist = next)
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
                        update { s ->
                            // Shadow-muted messages are only meant for moderators; the
                            // server already filters delivery, but drop them defensively.
                            if (event.message.shadow && s.localRank < 2) s
                            else s.copy(messages = appendChat(s.messages, event.message))
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

                is CyTubeEvent.PollOpened ->
                    update { it.copy(poll = event.poll, myPollVote = null) }
                is CyTubeEvent.PollUpdated -> update { s ->
                    val current = s.poll ?: return@update s
                    // Positional: server sends counts parallel to the options
                    // already on screen, so pad/truncate to match rather than
                    // trust the incoming length blindly.
                    val counts = List(current.options.size) { i -> event.counts.getOrElse(i) { -1 } }
                    s.copy(poll = current.copy(counts = counts))
                }
                is CyTubeEvent.PollClosed -> update { it.copy(poll = null, myPollVote = null) }

                is CyTubeEvent.ErrorMessage -> update { it.copy(statusMessage = event.message) }

                else -> Unit
            }
            }.onFailure { Log.e("CyTube", "Error handling ${event::class.simpleName}", it) }
        }
    }

    // ---- media ----

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
            update {
                if (it.personalPickActive) it.copy(channelCurrentMedia = media)
                else it.copy(media = media, channelCurrentMedia = media, playing = !media.paused)
            }
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
        // A genuinely new item — any quality step taken for whatever was
        // playing before has no bearing on this one. See
        // ChannelUiState.nativeQualityIndex's own doc comment.
        lastQualityChangeAtMs = 0L
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
                nativeQualityIndex = 0
            )
        }
        // The player composable observes state.media and rebuilds the backend;
        // once it is ready it calls playerReady() below.
    }

    private fun onTimeUpdate(update: TimeUpdate) {
        val s = _state.value
        // Piggybacks on this same ~1/s server-driven cadence rather than a
        // dedicated ticker — see maybeUpgradeQuality's own doc. Deliberately
        // ahead of every early-return below: it's independent of SyncEngine
        // entirely, and there's no reason a personal pick or Compatibility
        // View should also freeze quality re-adaptation for whatever's
        // actually loaded in the NATIVE backend.
        maybeUpgradeQuality(s)
        val p = player ?: return
        if (s.effectiveMode == CompatMode.WEB) return   // the web page syncs itself
        // A personal pick (see pickPersonal) is playing something the
        // server's own currentTime/lead-in/pause broadcasts know nothing
        // about — SyncEngine.apply assumes `update` describes whatever
        // `player` currently holds, which isn't true here. Its `waiting`
        // (lead-in) branch in particular runs unconditionally, even with
        // sync off, so applying this anyway risked a completely unrelated
        // channel-item lead-in pausing/resetting a video nobody else in the
        // room was ever watching.
        if (s.personalPickActive) return
        val withinGrace = android.os.SystemClock.elapsedRealtime() - playerAttachedAtMs < SYNC_GRACE_MS
        viewModelScope.launch {
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

    /**
     * The gentle half of the NATIVE backend's lightweight quality
     * auto-adaptation — see [onPlaybackStall] for the step down this climbs
     * back from. Piggybacks on onTimeUpdate's own ~1/s cadence (CyTube's own
     * currentTime broadcasts) rather than a dedicated ticker: after a
     * stall-free stretch at a downgraded quality, try stepping back up one
     * level. If that turns out to still be too much for the connection,
     * onPlaybackStall steps right back down again on the next real stall —
     * [QUALITY_CHANGE_COOLDOWN_MS] just keeps the two from fighting each
     * other on every single tick, and [QUALITY_UPGRADE_RECHECK_MS] keeps an
     * upgrade attempt itself rare rather than eager.
     */
    private fun maybeUpgradeQuality(s: ChannelUiState) {
        if (s.nativeQualityIndex == 0) return   // already at the default (highest) — nothing to climb back to
        if (s.player != MediaTypes.Player.NATIVE) return
        val media = s.media ?: return
        if (media.direct.size <= 1) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastQualityChangeAtMs < QUALITY_UPGRADE_RECHECK_MS) return
        val next = s.nativeQualityIndex - 1
        lastQualityChangeAtMs = now
        Log.i(TAG, "quality: trying back up to ${media.direct[next].quality}p after a stall-free window")
        update { it.copy(nativeQualityIndex = next, playerEpoch = it.playerEpoch + 1) }
    }

    /**
     * Reported by PlayerSurface's ExoSurface (via its onStall callback)
     * whenever a mid-playback rebuffer — never the item's own initial
     * buffer-up, see onStall's own doc for why that distinction matters —
     * lasts long enough to look like a genuine, sustained bandwidth
     * shortfall rather than a brief blip. Steps
     * [ChannelUiState.nativeQualityIndex] down one level (into
     * MediaFrame.direct, already sorted highest-to-lowest) and reloads at
     * the same position via a [ChannelUiState.playerEpoch] bump — the same
     * full ExoPlayer rebuild a manual "player is stuck" recovery would use,
     * just triggered automatically here. No amount of LoadControl/buffer-size
     * tuning can fix a SUSTAINED throughput shortfall (a bigger buffer only
     * buys more runway to absorb a short dip) — this is the actual lever for
     * that case. See [maybeUpgradeQuality] for the way back up.
     */
    fun onPlaybackStall(stalledMs: Long) {
        if (stalledMs < QUALITY_DOWNGRADE_STALL_THRESHOLD_MS) return
        val s = _state.value
        if (s.player != MediaTypes.Player.NATIVE) return
        val media = s.media ?: return
        if (media.direct.size <= 1) return   // nothing lower to step down to
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastQualityChangeAtMs < QUALITY_CHANGE_COOLDOWN_MS) return
        if (s.nativeQualityIndex >= media.direct.lastIndex) return   // already at the lowest available
        val next = s.nativeQualityIndex + 1
        lastQualityChangeAtMs = now
        Log.i(TAG, "quality: stepping down to ${media.direct[next].quality}p after a ${stalledMs}ms stall")
        update { it.copy(nativeQualityIndex = next, playerEpoch = it.playerEpoch + 1) }
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

    fun sendChat(text: String) {
        if (text.isBlank()) return
        client.sendChat(text.trim())
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
        // A genuinely different item from whatever was loaded before — see
        // ChannelUiState.nativeQualityIndex's own doc comment.
        lastQualityChangeAtMs = 0L
        update {
            it.copy(
                media = frame,
                player = chosen,
                playing = !frame.paused,
                playbackOffer = null,
                compatOffer = offer,
                personalPickActive = true,
                personalPickUid = item.uid,
                nativeQualityIndex = 0
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
        // Snapping back to the channel's own item is also a genuine item
        // change from whatever was personally loaded — see
        // ChannelUiState.nativeQualityIndex's own doc comment.
        lastQualityChangeAtMs = 0L
        update {
            it.copy(
                media = channelMedia,
                player = chosen,
                playing = !channelMedia.paused,
                playbackOffer = null,
                compatOffer = offer,
                personalPickActive = false,
                personalPickUid = -1,
                nativeQualityIndex = 0
            )
        }
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

    private fun update(block: (ChannelUiState) -> ChannelUiState) {
        _state.value = block(_state.value)
    }

    override fun onCleared() {
        leaderTicker?.cancel()
        player?.release()
        client.disconnect()
        super.onCleared()
    }

    private companion object {
        const val MAX_CHAT_MESSAGES = 300
        const val MAX_GUEST_RETRIES = 3
        const val TAG = "CyTubeChannel"

        /** Minimum time between actual [refresh] runs — the anti-spam gate
         *  on tapping the channel name. Comfortably longer than the 600ms
         *  the in-flight `refreshing` flag itself is held for, so a tap
         *  right as the previous refresh clears is still rejected, not just
         *  a tap that lands mid-refresh. */
        const val REFRESH_COOLDOWN_MS = 4_000L

        /** How long after a new player attaches SyncEngine holds off on
         *  position correction — see SyncEngine.apply's withinGracePeriod
         *  doc. Long enough to cover a slow NewPipe resolve + the CDN's own
         *  cold-start latency; short enough that a channel that's genuinely
         *  out of sync still gets corrected quickly. */
        const val SYNC_GRACE_MS = 6_000L

        /** [onPlaybackStall] ignores anything shorter than this — a quick
         *  rebuffer after an ordinary seek (a manual scrub, or SyncEngine's
         *  own hard-seek correction) settles in well under this on a fine
         *  connection; the genuinely network-bound stalls that motivated
         *  quality auto-adaptation ran several times longer than this in
         *  real testing. */
        const val QUALITY_DOWNGRADE_STALL_THRESHOLD_MS = 3_000L

        /** Minimum time between quality steps, either direction — a full
         *  ExoPlayer rebuild is itself a multi-second interruption (see
         *  playerEpoch's own doc), so onPlaybackStall and maybeUpgradeQuality
         *  both need to stay well clear of reacting to their own reload's
         *  side effects, or of each other. */
        const val QUALITY_CHANGE_COOLDOWN_MS = 20_000L

        /** How long a downgraded quality has to stay stall-free before
         *  [maybeUpgradeQuality] cautiously tries stepping back up. Long
         *  enough to be a real signal the connection recovered, not just a
         *  lull between stalls; short enough that a genuinely-improved
         *  connection doesn't stay stuck at a lower quality for the rest of
         *  the video. */
        const val QUALITY_UPGRADE_RECHECK_MS = 90_000L
    }
}
