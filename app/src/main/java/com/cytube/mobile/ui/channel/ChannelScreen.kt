package com.cytube.mobile.ui.channel

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cytube.mobile.data.CompatMode
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import com.cytube.mobile.di.Graph
import com.cytube.mobile.ui.theme.CyTubeChannelTheme
import kotlinx.coroutines.delay

private enum class Panel { PLAYLIST, USERS, POLL }

/**
 * Android TV / Fire TV — the actual runtime signal, not just "no touchscreen"
 * (a Chromebook or a phone in a desktop dock can be touchscreen-less too).
 * `UiModeManager.currentModeType` is what Android itself uses to decide this,
 * and it's what an Android TV/Fire TV emulator or device reports correctly.
 *
 * This is what decides whether ChannelScreen shows the phone-style chrome
 * (title bar, bottom playlist/users/poll bar) at all. Both assume a
 * touchscreen and a thumb — there is no way to reach them well with a D-pad
 * and a remote, and the README already calls out that this app has no
 * dedicated 10-foot UI yet. Until it does, a TV gets straight-to-fullscreen
 * video instead of a phone layout it can't really drive.
 */
private fun isTvDevice(context: Context): Boolean {
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}

/**
 * What the hosting Activity needs to drive Picture-in-Picture and
 * background/foreground playback for whatever ChannelScreen currently has on
 * screen. Reported fresh on every recomposition via [onPlaybackHostChange],
 * and cleared (null) when the screen leaves composition entirely.
 */
data class PlaybackHost(
    val pipEnabled: Boolean,
    /** False for anything PiP doesn't make sense for — Compatibility View
     *  (a whole web page, not a video) or no media loaded yet. */
    val canPip: Boolean,
    val isPlaying: Boolean,
    val onTogglePlayPause: () -> Unit,
    val onPauseForBackground: () -> Unit,
    val onResumeForForeground: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelScreen(
    channel: String,
    onBack: () -> Unit,
    onFullscreenChange: (Boolean) -> Unit,
    isInPictureInPicture: Boolean = false,
    onPlaybackHostChange: (PlaybackHost?) -> Unit = {},
    vm: ChannelViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val isTv = remember { isTvDevice(context) }

    var fullscreen by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var openPanel by remember { mutableStateOf<Panel?>(null) }
    var showModeSheet by remember { mutableStateOf(false) }
    var passwordDraft by remember { mutableStateOf("") }

    LaunchedEffect(channel) { vm.start(channel) }

    // Standard keep-screen-on: the view holds the flag while something is
    // actually playing and drops it the moment playback stops or this screen
    // leaves composition. No timers, nothing to leak.
    val view = LocalView.current
    DisposableEffect(state.playing, state.player) {
        val awake = state.playing || state.player == com.cytube.mobile.net.MediaTypes.Player.WEB
        view.keepScreenOn = awake
        onDispose { view.keepScreenOn = false }
    }

    val onSendChat = remember(vm) { vm::sendChat }
    val onJumpTo = remember(vm) { vm::jumpTo }
    val onDeleteItem = remember(vm) { vm::deleteItem }
    val onAttachPlayer = remember(vm) { vm::attachPlayer }

    // The single player surface, hoisted so it survives moving between the
    // compact layout, the fullscreen layout and the PiP layout below. Before
    // this, each of those was a structurally different composable subtree, so
    // toggling fullscreen made Compose tear down and rebuild the whole
    // ExoPlayer from scratch (see ExoSurface's remember(epoch) { ... }) —
    // that rebuild-and-reconnect was the actual cause of the lag on
    // entering/exiting fullscreen. movableContentOf instead moves the same
    // already-playing instance to wherever it's called from.
    val pipModeState = rememberUpdatedState(isInPictureInPicture)
    val playerContent = remember {
        movableContentOf {
            PlayerSurface(
                media = state.media,
                player = state.player,
                showControls = !fullscreen && !pipModeState.value,
                onHandle = onAttachPlayer,
                onFailed = vm::reportPlaybackFailure,
                epoch = state.playerEpoch,
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    // Reported on every recomposition so the Activity's PiP button/state
    // (play vs. pause, whether PiP is even applicable right now) stays
    // current, and cleared when this screen goes away.
    val onTogglePlayPause = remember(vm) { vm::togglePlaybackFromPip }
    val onPauseForBackground = remember(vm) { vm::pauseForBackground }
    val onResumeForForeground = remember(vm) { vm::resumeForForeground }
    SideEffect {
        onPlaybackHostChange(
            PlaybackHost(
                pipEnabled = state.pipEnabled,
                // EMBED has no PlayerHandle behind it (see PlayerSurface's
                // EmbedSurface) — nothing for the PiP overlay's play/pause
                // button to actually control — so it's excluded the same way
                // WEB already was.
                canPip = state.player != com.cytube.mobile.net.MediaTypes.Player.WEB &&
                    state.player != com.cytube.mobile.net.MediaTypes.Player.EMBED &&
                    state.media != null,
                isPlaying = state.playing,
                onTogglePlayPause = onTogglePlayPause,
                onPauseForBackground = onPauseForBackground,
                onResumeForForeground = onResumeForForeground
            )
        )
    }
    DisposableEffect(Unit) { onDispose { onPlaybackHostChange(null) } }

    // Detected once per composition, not inside an effect, so there is no
    // ordering dependency between the two effects below that both care
    // about "did we just exit PiP" — isInPictureInPicture alone only
    // exposes the current value, never the transition. Written and read
    // synchronously in the same pass (not via a LaunchedEffect) so
    // `fullscreen` is already true by the time this same composition
    // decides, further down, which branch to render — see the note by
    // settlingFromPip below for why a one-frame-later async flip was
    // actually making things worse, not better.
    var wasInPip by remember { mutableStateOf(isInPictureInPicture) }
    val justExitedPip = wasInPip && !isInPictureInPicture
    if (wasInPip != isInPictureInPicture) wasInPip = isInPictureInPicture

    // Expanding a PiP window reads to the user exactly like tapping the
    // in-app fullscreen button from the channel page: they want the big
    // immersive video back, not the compact chat-and-playlist layout
    // buried underneath it. Force it here rather than leaving the result
    // to whatever `fullscreen` happened to be before backgrounding, or to
    // the physical orientation at the moment (see the guard on
    // lastOrientation below for why that alone isn't reliable either).
    if (justExitedPip) fullscreen = true

    // The single biggest remaining suspect for "expand from PiP crashes or
    // closes the app": the moment isInPictureInPicture flips to false is
    // also the moment the SYSTEM starts its own window-resize animation
    // back to full size. Reparenting the player's TextureView between
    // Compose hosts (PiP's Box -> the compact layout -> FullscreenPlayer,
    // or straight to FullscreenPlayer once `fullscreen` above is already
    // true) is itself a real view-tree change, and doing that WHILE the
    // window is still being resized by the system stacks two independent
    // animations of the same surface on top of each other. A previous fix
    // here flipped `fullscreen` one async hop later (a LaunchedEffect), which
    // actually made this worse: it turned one structural move into two in
    // quick succession (PiP Box -> compact Box -> FullscreenPlayer Box)
    // instead of one. Freezing this screen on the PiP-only Box — the exact
    // same content, not reparented at all — for a short settle window after
    // isInPictureInPicture goes false means the player isn't touched until
    // the system's own transition has had time to finish, and by the time
    // it is touched, `fullscreen` (set synchronously above) is already
    // correct, so there is exactly one move, not two.
    var settlingFromPip by remember { mutableStateOf(false) }
    LaunchedEffect(justExitedPip) {
        if (justExitedPip) {
            settlingFromPip = true
            delay(220)
            settlingFromPip = false
        }
    }

    // Fullscreen: prefer landscape, hide chrome, restore cleanly on exit.
    // "Chrome" here includes Android's own system bars — without hiding
    // those too, the nav/status bars stayed visible over a fullscreen video,
    // which is a real problem on a TV where they're never supposed to
    // appear at all.
    LaunchedEffect(fullscreen, isInPictureInPicture) {
        // TV has its own dedicated immersive handling below (it never sets
        // `fullscreen` at all, since it skips the phone layout entirely) —
        // without this bail-out, this effect would see `fullscreen == false`
        // on TV and actively show the system bars back over top of the
        // video the TV branch just hid them for.
        if (isTv) return@LaunchedEffect

        // Exiting PiP and forcing fullscreen back on (the effect above) can
        // land in the very same recomposition: the window is still
        // mid-resize from the system's own PiP-exit animation right as this
        // effect also wants to lock the orientation and hide the system
        // bars. Giving that resize a moment to settle before stacking a
        // second layout change on top of it is cheap insurance against
        // whatever exact race was turning "expand from PiP" into "crash or
        // close the app".
        if (justExitedPip) delay(150)

        val immersive = fullscreen
        onFullscreenChange(immersive)
        // Android throws IllegalStateException("Only fullscreen activities
        // can request orientation") if setRequestedOrientation is called
        // while the activity is in — or still transitioning out of —
        // Picture-in-Picture. That's exactly what could happen here: exiting
        // PiP changes `isInPictureInPicture`, which recomposes this screen,
        // which (before this guard) would immediately try to force an
        // orientation lock while the system was still mid-transition. The
        // whole block is wrapped in runCatching, not just this call — never
        // let any OEM-specific quirk in here take the app down; worst case
        // the orientation lock or system-bar state is briefly stale until
        // the next recomposition fixes it.
        runCatching {
            if (!isInPictureInPicture) {
                activity?.requestedOrientation =
                    if (immersive) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            controlsVisible = true

            activity?.window?.let { window ->
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                if (immersive) {
                    controller.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                } else {
                    controller.show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
    }
    // Belt-and-braces: restore the system bars if this screen is left
    // (back navigation) while still fullscreen, so the bars can't get stuck
    // hidden on whatever screen comes next.
    DisposableEffect(Unit) {
        onDispose {
            activity?.window?.let { window ->
                WindowCompat.getInsetsController(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Physical rotation drives fullscreen directly, the way a typical video
    // app behaves: turn the phone sideways and the video takes over; turn it
    // back and the normal layout returns. Only a genuine orientation change
    // triggers this (not the initial value) so it never fires on a device
    // that's always one orientation — a tablet held landscape, or a TV —
    // which would otherwise force fullscreen open the moment the channel
    // loads. It also stands down whenever Compatibility View or the
    // provider's own fullscreen already owns the screen — and, critically,
    // while Picture-in-Picture is active: a PiP window is itself a small
    // landscape-shaped rectangle, so Configuration.orientation reports
    // LANDSCAPE for it regardless of how the phone is actually being held.
    // Without this guard, entering PiP looked like "the phone rotated" (sets
    // fullscreen = true) and — worse — exiting PiP back to a portrait phone
    // looked like "the phone rotated back" and reset fullscreen = false
    // right as PiP closed, which is what fed the crash above: it forced an
    // orientation-lock call at exactly the moment the activity was mid-way
    // out of PiP, and it silently threw away whatever fullscreen/compact
    // state the channel was actually in before PiP started.
    //
    // The `return@LaunchedEffect` below used to come AFTER `lastOrientation`
    // was already updated to PiP's fake LANDSCAPE value, so the moment PiP
    // closed this effect compared the phone's real orientation against that
    // fake value instead of whatever was recorded before PiP started —
    // exiting PiP onto a physically portrait phone then read as a genuine
    // rotation and reset fullscreen = false right as the effect above was
    // trying to force it back to true. Bailing out first, before touching
    // lastOrientation at all, freezes it at the real pre-PiP value for the
    // whole time PiP is up.
    val configuration = LocalConfiguration.current
    var lastOrientation by remember { mutableStateOf(configuration.orientation) }
    LaunchedEffect(configuration.orientation, isInPictureInPicture) {
        if (isTv) return@LaunchedEffect
        if (isInPictureInPicture) return@LaunchedEffect
        val previous = lastOrientation
        lastOrientation = configuration.orientation
        if (previous == configuration.orientation) return@LaunchedEffect
        if (state.player == com.cytube.mobile.net.MediaTypes.Player.WEB) return@LaunchedEffect
        when (configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> fullscreen = true
            Configuration.ORIENTATION_PORTRAIT -> fullscreen = false
            else -> Unit
        }
    }

    // Auto-hide overlay controls while fullscreen.
    LaunchedEffect(controlsVisible, fullscreen) {
        if (fullscreen && controlsVisible) {
            delay(3_000)
            controlsVisible = false
        }
    }

    BackHandler(enabled = fullscreen || openPanel != null) {
        when {
            fullscreen -> fullscreen = false
            openPanel != null -> openPanel = null
        }
    }

    // Picture-in-picture: just the video, full-bleed. The system draws its
    // own chrome (the play/pause action wired up in MainActivity) around
    // this, so there is nothing else to render here — and nothing from this
    // screen leaks into the floating window when the channel changes, since
    // switching channels recomposes ChannelScreen with a new vm/state
    // entirely.
    //
    // `settlingFromPip` keeps this exact branch (and therefore the exact
    // same host for playerContent()) active for a short window after PiP
    // actually ends too — see the comment above where it's set for why.
    if (isInPictureInPicture || settlingFromPip) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            playerContent()
        }
        return
    }

    // Fire TV / Android TV: no title bar, no bottom playlist/users/poll bar —
    // straight to full-bleed video the moment the channel opens, immersive
    // (system bars hidden) the same way the phone's own fullscreen is. The
    // system Back button (which a Fire TV remote's Back button dispatches
    // the same as anywhere else) leaves the channel entirely rather than
    // dropping into the phone layout underneath, since that layout is never
    // shown on TV in the first place — there's nothing to "exit fullscreen"
    // back into here.
    if (isTv) {
        BackHandler { onBack() }
        LaunchedEffect(Unit) {
            onFullscreenChange(true)
            runCatching {
                activity?.window?.let { window ->
                    val controller = WindowCompat.getInsetsController(window, window.decorView)
                    controller.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (state.player == com.cytube.mobile.net.MediaTypes.Player.WEB) {
                WebCompatView(
                    baseUrl = Graph.BASE_URL,
                    channel = channel,
                    authCookie = Graph.auth(context).savedSession()?.authCookie,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                playerContent()
            }
        }
        return
    }

    if (fullscreen) {
        FullscreenPlayer(
            state = state,
            controlsVisible = controlsVisible,
            onToggleControls = { controlsVisible = !controlsVisible },
            onExit = { fullscreen = false },
            playerContent = playerContent
        )
        return
    }

    CyTubeChannelTheme {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(channel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ConnectionLine(state)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = vm::toggleFavourite) {
                        Icon(
                            if (state.isFavourite) Icons.Default.Star else Icons.Outlined.StarBorder,
                            contentDescription = "Favourite"
                        )
                    }
                    IconButton(onClick = { showModeSheet = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Channel options")
                    }
                }
            )
        },
        bottomBar = {
            PanelBar(
                userCount = state.userCount,
                playlistCount = state.playlist.size,
                pollOpen = state.poll != null,
                onOpen = { openPanel = it }
            )
        }
    ) { padding ->
        // Always the same PullToRefreshBox — swapping in and out of a plain Box
        // when a panel opened used to tear down and rebuild everything below
        // (the player, its ExoPlayer instance, sync state) because Compose saw
        // it as a structurally different subtree. That was the cause of Users
        // and Playlist appearing to "reset" the video: opening either panel
        // silently killed and restarted the player underneath. The bottom
        // sheet's own scrim already blocks the pull-to-refresh gesture while a
        // panel is open, so there is nothing else to gate here.
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
        Column(Modifier.fillMaxSize()) {

            val webMode = state.player == com.cytube.mobile.net.MediaTypes.Player.WEB
            Box(
                Modifier.fillMaxWidth()
                    .then(
                        if (webMode) Modifier.weight(1f)
                        else Modifier.aspectRatio(16f / 9f)
                    )
                    .background(Color.Black)
            ) {
                if (state.player == com.cytube.mobile.net.MediaTypes.Player.WEB) {
                    // Compatibility View is the whole CyTube page again, not a
                    // player surface. It owns the channel entirely while it is up.
                    WebCompatView(
                        baseUrl = Graph.BASE_URL,
                        channel = channel,
                        authCookie = Graph.auth(context).savedSession()?.authCookie,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    playerContent()
                    // Top-right, not bottom-right: Media3's own PlayerView
                    // draws its settings/gear control in the bottom corner,
                    // and the two used to sit right on top of each other.
                    IconButton(
                        onClick = { fullscreen = true },
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                    ) {
                        Icon(Icons.Default.Fullscreen, contentDescription = "Fullscreen", tint = Color.White)
                    }
                }
            }

            state.media?.let { NowPlayingBar(it.title, state.leader) }

            if (state.motd.isNotBlank()) {
                MotdSection(
                    html = state.motd,
                    emotes = state.emotes,
                    expanded = state.motdExpanded,
                    onToggle = vm::toggleMotd
                )
            }

            state.kicked?.let { reason ->
                DisconnectedNotice(reason = reason, onRetry = vm::retry, onBack = onBack)
            }

            // In portrait the chat gets whatever is left below the video, which
            // is the panel people actually keep open.
            ChatPanel(
                messages = state.messages,
                canSend = state.connection == ConnectionState.CONNECTED,
                showEmotes = state.showEmotes,
                emotes = state.emotes,
                onSend = onSendChat,
                modifier = Modifier.weight(1f)
            )
        }
        }
    }

    // ---- overlays ----

    openPanel?.let { panel ->
        ModalBottomSheet(onDismissRequest = { openPanel = null }) {
            when (panel) {
                Panel.PLAYLIST -> PlaylistPanel(
                    items = state.playlist,
                    currentUid = state.currentUid,
                    canControl = state.localRank >= 2,
                    onJumpTo = onJumpTo,
                    onDelete = onDeleteItem,
                    modifier = Modifier.fillMaxHeight(0.8f)
                )
                Panel.USERS -> UsersPanel(
                    users = state.users,
                    modifier = Modifier.fillMaxHeight(0.8f)
                )
                Panel.POLL -> PollPanel(
                    poll = state.poll,
                    myVote = state.myPollVote,
                    onVote = vm::votePoll,
                    modifier = Modifier.fillMaxHeight(0.8f)
                )
            }
        }
    }

    if (showModeSheet) {
        CompatModeSheet(
            current = state.effectiveMode,
            backendNote = backendNote(state),
            onSelect = { vm.setMode(it); showModeSheet = false },
            onDismiss = { showModeSheet = false }
        )
    }

    state.playbackOffer?.let { reason ->
        AlertDialog(
            onDismissRequest = vm::declinePlaybackOffer,
            title = { Text("Playback isn't working natively") },
            text = { Text("Try WebView?\n\n$reason") },
            confirmButton = {
                TextButton(onClick = vm::acceptWebPlayback) { Text("Try WebView") }
            },
            dismissButton = {
                TextButton(onClick = vm::declinePlaybackOffer) { Text("Cancel") }
            }
        )
    }

    state.compatOffer?.let { reason ->
        AlertDialog(
            onDismissRequest = vm::declineCompatOffer,
            title = { Text("Can't play natively") },
            text = { Text("$reason. Try WebView?") },
            confirmButton = {
                TextButton(onClick = vm::acceptCompatOffer) { Text("Try WebView") }
            },
            dismissButton = {
                TextButton(onClick = vm::declineCompatOffer) { Text("Not now") }
            }
        )
    }

    if (state.needsPassword) {
        AlertDialog(
            onDismissRequest = onBack,
            title = { Text("Channel password") },
            text = {
                Column {
                    if (state.passwordWasWrong) {
                        Text(
                            "That password was not accepted.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedTextField(
                        value = passwordDraft,
                        onValueChange = { passwordDraft = it },
                        singleLine = true,
                        label = { Text("Password") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.submitPassword(passwordDraft); passwordDraft = "" }) {
                    Text("Join")
                }
            },
            dismissButton = { TextButton(onClick = onBack) { Text("Leave") } }
        )
    }
    }
}

@Composable
private fun ConnectionLine(state: ChannelUiState) {
    val (text, color) = when (state.connection) {
        ConnectionState.CONNECTED ->
            (state.statusMessage ?: "${state.userCount} connected") to MaterialTheme.colorScheme.onSurfaceVariant
        ConnectionState.CONNECTING -> "Connecting…" to MaterialTheme.colorScheme.onSurfaceVariant
        ConnectionState.RECONNECTING -> "Reconnecting…" to MaterialTheme.colorScheme.tertiary
        ConnectionState.DISCONNECTED -> "Disconnected" to MaterialTheme.colorScheme.error
        ConnectionState.FAILED -> (state.statusMessage ?: "Could not connect") to MaterialTheme.colorScheme.error
    }
    Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
}

/**
 * Channel MOTD, collapsed to a single tappable line. The MOTD is HTML from the
 * channel, so it goes through the same renderer chat uses — except emotes are
 * discarded outright (channels sometimes pad the notice with them as
 * decoration, which just spams a small text block) and, when expanded, the
 * text scrolls and its links open in the phone's browser like chat links do.
 */
@Composable
private fun MotdSection(
    html: String,
    emotes: com.cytube.mobile.net.EmoteSet,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val context = LocalContext.current
    val rendered = remember(html, emotes) {
        ChatHtml.render(
            html, greentext = false, linkColor = linkColor,
            showImages = false, emotes = emotes, dropImages = true
        )
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)
        ) {
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Hide channel notice" else "Show channel notice",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Channel notice",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
            Text(
                rendered.text,
                style = MaterialTheme.typography.bodySmall,
                onTextLayout = { layout = it },
                modifier = Modifier
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
                    .pointerInput(rendered.text) {
                        detectTapGestures { pos ->
                            val l = layout ?: return@detectTapGestures
                            val offset = l.getOffsetForPosition(pos)
                            ChatHtml.linkAt(rendered.text, offset)?.let { url ->
                                openInBrowser(context, url)
                            }
                        }
                    }
            )
        }
    }
}

@Composable
private fun NowPlayingBar(title: String, leader: String?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        leader?.let {
            AssistChip(onClick = {}, label = { Text("Leader: $it", maxLines = 1) })
        }
    }
}

@Composable
private fun PanelBar(userCount: Int, playlistCount: Int, pollOpen: Boolean, onOpen: (Panel) -> Unit) {
    NavigationBar {
        NavigationBarItem(
            selected = false, onClick = { onOpen(Panel.PLAYLIST) },
            icon = { Text(if (playlistCount > 0) "Playlist ($playlistCount)" else "Playlist") },
            label = null
        )
        NavigationBarItem(
            selected = false, onClick = { onOpen(Panel.USERS) },
            icon = { Text("Users ($userCount)") }, label = null
        )
        // Only shown while a poll is actually running — nothing to vote on
        // otherwise, so the button would just open an empty panel.
        if (pollOpen) {
            NavigationBarItem(
                selected = false, onClick = { onOpen(Panel.POLL) },
                icon = { Text("Poll") }, label = null
            )
        }
    }
}

@Composable
private fun DisconnectedNotice(reason: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(reason, style = MaterialTheme.typography.bodyMedium)
            if (reason.contains("Duplicate", true)) {
                Text(
                    "This account is already in the channel somewhere else. " +
                        "Close the other session before rejoining.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRetry) { Text("Rejoin") }
                TextButton(onClick = onBack) { Text("Leave") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompatModeSheet(
    current: CompatMode,
    backendNote: String,
    onSelect: (CompatMode) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text("Compatibility", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                backendNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            ModeOption(
                title = "Automatic",
                body = "Native player when the media allows it, the CyTube page when it does not.",
                selected = current == CompatMode.AUTOMATIC
            ) { onSelect(CompatMode.AUTOMATIC) }

            ModeOption(
                title = "Native",
                body = "Always use the app's own player. Media it cannot handle will not play.",
                selected = current == CompatMode.NATIVE
            ) { onSelect(CompatMode.NATIVE) }

            ModeOption(
                title = "Web",
                body = "Load the whole CyTube page. Everything comes from the site, not the app.",
                selected = current == CompatMode.WEB
            ) { onSelect(CompatMode.WEB) }
        }
    }
}

@Composable
private fun ModeOption(title: String, body: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun backendNote(state: ChannelUiState): String {
    val media = state.media ?: return "Nothing is playing yet."
    val label = com.cytube.mobile.net.MediaTypes.label(media.type)
    return when (state.player) {
        com.cytube.mobile.net.MediaTypes.Player.NATIVE -> "Playing $label natively."
        com.cytube.mobile.net.MediaTypes.Player.NEWPIPE -> "Playing $label natively via NewPipe."
        com.cytube.mobile.net.MediaTypes.Player.GDRIVE -> "Playing $label natively via Google Drive."
        com.cytube.mobile.net.MediaTypes.Player.EMBED ->
            "$label plays via the provider's own embed. Chat, playlist and sync stay native."
        com.cytube.mobile.net.MediaTypes.Player.WEB ->
            "$label needs Compatibility View. Chat and playlist come from the page."
        com.cytube.mobile.net.MediaTypes.Player.UNAVAILABLE ->
            "$label can't play natively. Choose Web mode below to load it."
    }
}

@Composable
private fun FullscreenPlayer(
    state: ChannelUiState,
    controlsVisible: Boolean,
    onToggleControls: () -> Unit,
    onExit: () -> Unit,
    playerContent: @Composable () -> Unit
) {
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { onToggleControls() } }
    ) {
        playerContent()

        AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color(0x66000000))) {
                Row(
                    Modifier.align(Alignment.TopStart).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Same control, same corner, whichever way you got into
                    // fullscreen — tap it again to leave, the way YouTube's
                    // own player does.
                    IconButton(onClick = onExit) {
                        Icon(Icons.Default.FullscreenExit, contentDescription = "Exit fullscreen", tint = Color.White)
                    }
                    Text(
                        state.media?.title.orEmpty(),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
