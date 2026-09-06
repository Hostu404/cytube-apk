package com.cytube.mobile.ui.channel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.cytube.mobile.data.CompatMode
import com.cytube.mobile.data.Settings
import com.cytube.mobile.data.SettingsStore
import com.cytube.mobile.di.Graph
import com.cytube.mobile.net.*
import com.cytube.mobile.player.PlayerHandle
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
    val playlist: List<PlaylistItem> = emptyList(),
    val currentUid: Int = -1,
    val users: List<ChannelUser> = emptyList(),
    val userCount: Int = 0,
    val messages: List<ChatMessage> = emptyList(),
    val emotes: EmoteSet = EmoteSet.EMPTY,
    val showEmotes: Boolean = true,
    /** Mirrors the Settings toggle; MainActivity reads this (via PlaybackHost)
     *  to decide whether leaving the app should float the video in PiP or
     *  just pause it. */
    val pipEnabled: Boolean = true,
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
     *  to fall back to WebView. */
    val playbackOffer: String? = null,
    /** Non-null when a freshly-selected item cannot be played natively at all
     *  (e.g. Google Drive without the userscript metadata) and we're asking
     *  whether to load it in WebView. Distinct from [playbackOffer], which is
     *  only for a backend that was actually running and then failed. */
    val compatOffer: String? = null,
    val refreshing: Boolean = false,
    /** Bumped to force the player surface to rebuild on an explicit refresh. */
    val playerEpoch: Int = 0,
    /** The channel's currently running poll, or null when none is active. */
    val poll: Poll? = null,
    /** Which option the local user tapped this session. The server has no
     *  "your vote" field (see CyTube's own client, which tracks this the same
     *  way — button state only), so this is reset whenever the poll changes. */
    val myPollVote: Int? = null
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
    private var settings: Settings = Settings()
    private var leaderTicker: Job? = null
    private var joined = false
    private var chatSeq = 0L
    private var guestRetries = 0

    /** Set only when [pauseForBackground] paused playback on the user's
     *  behalf, so [resumeForForeground] never un-pauses a video the user
     *  paused themselves right before backgrounding the app. */
    private var autoPaused = false

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
        if (!CHANNEL_NAME.matches(channel)) {
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
                isFavourite = settingsStore.favourites.first().contains(channel)
            )
            settingsStore.noteVisit(channel)

            launch {
                settingsStore.settings.collect {
                    settings = it
                    update { s ->
                        if (it.showEmotes == s.showEmotes && it.pipEnabled == s.pipEnabled) s
                        else s.copy(showEmotes = it.showEmotes, pipEnabled = it.pipEnabled)
                    }
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
                    if (event.update.paused == _state.value.playing) {
                        update { it.copy(playing = !event.update.paused) }
                    }
                    onTimeUpdate(event.update)
                }

                is CyTubeEvent.PlaylistReplaced -> update { it.copy(playlist = event.items) }
                is CyTubeEvent.CurrentItemChanged -> update { it.copy(currentUid = event.uid) }
                is CyTubeEvent.ItemQueued -> update { s ->
                    val idx = s.playlist.indexOfFirst { it.uid == event.afterUid }
                    val next = s.playlist.toMutableList()
                    if (idx >= 0) next.add(idx + 1, event.item) else next.add(event.item)
                    s.copy(playlist = next)
                }
                is CyTubeEvent.ItemDeleted -> update { s ->
                    s.copy(playlist = s.playlist.filterNot { it.uid == event.uid })
                }

                is CyTubeEvent.Chat -> update { s ->
                    // Shadow-muted messages are only meant for moderators; the
                    // server already filters delivery, but drop them defensively.
                    if (event.message.shadow && s.localRank < 2) s
                    else s.copy(messages = appendChat(s.messages, event.message))
                }
                is CyTubeEvent.ChatCleared -> update { it.copy(messages = emptyList()) }

                is CyTubeEvent.UserListReplaced -> update { it.copy(users = event.users) }
                is CyTubeEvent.UserJoined -> update { s ->
                    s.copy(users = (s.users.filterNot { it.name == event.user.name } + event.user))
                }
                is CyTubeEvent.UserLeft -> update { s ->
                    s.copy(users = s.users.filterNot { it.name == event.name })
                }
                is CyTubeEvent.UserMetaChanged -> update { s ->
                    s.copy(users = s.users.map { if (it.name == event.user.name) event.user else it })
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
        // actually differs from what's already loaded.
        val current = _state.value.media
        if (current != null && current.id == media.id && current.type == media.type) {
            update { it.copy(media = media, playing = !media.paused) }
            return
        }

        val (chosen, offer) = choosePlayerAndOffer(media)
        Log.i(TAG, "changeMedia type=${media.type} player=$chosen " +
            "seconds=${media.seconds} sources=${media.direct.size} id=${media.id}")
        // Player selection is re-run on every changeMedia, so a playlist moving
        // from YouTube to a film and back switches players by itself.
        update {
            it.copy(
                media = media,
                player = chosen,
                playing = !media.paused,
                playbackOffer = null,
                compatOffer = offer
            )
        }
        // The player composable observes state.media and rebuilds the backend;
        // once it is ready it calls playerReady() below.
    }

    private fun onTimeUpdate(update: TimeUpdate) {
        val p = player ?: return
        val s = _state.value
        if (s.effectiveMode == CompatMode.WEB) return   // the web page syncs itself
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

    fun attachPlayer(handle: PlayerHandle?) {
        if (handle != null && handle !== player) playerAttachedAtMs = android.os.SystemClock.elapsedRealtime()
        player = handle
        if (handle != null) client.signalPlayerReady()
    }

    // ---- background / PiP playback control ----

    /**
     * The activity calls this when it actually leaves the foreground without
     * entering PiP — feature disabled, not eligible, or the OS declined.
     * Without this, playback (and its audio) silently kept running while the
     * screen showed nothing at all, which is the "hear it, can't see it" bug
     * PiP was meant to fix. Never overrides a pause the user made themselves.
     */
    fun pauseForBackground() {
        val p = player ?: return
        if (p.isPaused) return
        autoPaused = true
        p.pause()
    }

    /** Only resumes what [pauseForBackground] itself paused. */
    fun resumeForForeground() {
        if (!autoPaused) return
        autoPaused = false
        player?.takeIf { it.isPaused }?.play()
    }

    /** Wired to the PiP window's own play/pause action. */
    fun togglePlaybackFromPip() {
        val p = player ?: return
        autoPaused = false
        if (p.isPaused) p.play() else p.pause()
    }

    /**
     * Leader mode inverts the protocol: we become the clock and push upward.
     */
    private fun retuneLeaderTicker() {
        leaderTicker?.cancel()
        if (!_state.value.isLeader) return
        leaderTicker = viewModelScope.launch {
            while (true) {
                delay(1_000)
                val p = player ?: continue
                client.sendMediaUpdate(p.currentTimeSeconds(), p.isPaused)
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
            else -> MediaTypes.playerFor(media.type, media.hasDirect, media.embedSrc)
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
     */
    fun reportPlaybackFailure(reason: String) {
        val m = _state.value.media
        Log.w(TAG, "playback failed backend=${_state.value.player} " +
            "type=${m?.type} id=${m?.id}: $reason")
        if (_state.value.effectiveMode == CompatMode.WEB) return
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

    /**
     * Pull-to-refresh: reconcile with the server rather than tearing the
     * connection down. Only re-resolves the socket if we are actually adrift.
     */
    fun refresh() {
        if (_state.value.refreshing) return
        update { it.copy(refreshing = true) }
        viewModelScope.launch {
            if (_state.value.connection == ConnectionState.CONNECTED) {
                // Authoritative state comes back on request; no reconnect needed.
                client.requestPlaylist()
                client.signalPlayerReady()
                update { it.copy(playerEpoch = it.playerEpoch + 1) }
            } else {
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

    /**
     * Appends one message and trims the buffer in a single allocation. The
     * previous (list + item).takeLast(N) built two throwaway lists per message,
     * which on a busy channel is a lot of garbage for no reason.
     */
    private fun appendChat(current: List<ChatMessage>, message: ChatMessage): List<ChatMessage> {
        val tagged = message.copy(seq = ++chatSeq)
        val keepFrom = (current.size + 1 - MAX_CHAT_MESSAGES).coerceAtLeast(0)
        val next = ArrayList<ChatMessage>(current.size - keepFrom + 1)
        for (i in keepFrom until current.size) next.add(current[i])
        next.add(tagged)
        return next
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

        /** How long after a new player attaches SyncEngine holds off on
         *  position correction — see SyncEngine.apply's withinGracePeriod
         *  doc. Long enough to cover a slow NewPipe resolve + the CDN's own
         *  cold-start latency; short enough that a channel that's genuinely
         *  out of sync still gets corrected quickly. */
        const val SYNC_GRACE_MS = 6_000L

        // CyTube's own channel names: letters, digits, underscore, hyphen.
        // Same charset HomeViewModel's direct-entry box already enforces.
        val CHANNEL_NAME = Regex("^[A-Za-z0-9_-]{1,100}$")
    }
}
