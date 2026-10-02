package com.cytube.mobile.ui.channel

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cytube.mobile.data.CompatMode
import com.cytube.mobile.di.Graph
import com.cytube.mobile.net.MediaTypes
import com.cytube.mobile.ui.isTvDevice
import com.cytube.mobile.ui.theme.CyTubeChannelTheme
import com.cytube.mobile.ui.theme.ChannelTopBar
import kotlinx.coroutines.delay

private enum class Panel { PLAYLIST, USERS, POLL }

/** How far each new ambient-glow sample moves the glow toward itself, out of
 *  1.0 — see the onFrameSnapshot comment in ChannelScreen for why this
 *  exists. Low on purpose: a single sample should nudge the color, not set
 *  it, so a fast-cutting video's glow drifts with the overall footage
 *  instead of snapping to whatever one frame happened to look like. Kept
 *  gentle enough that, combined with the long near-continuous crossfade
 *  behind the windowed player, the color's motion stays subtle rather than a
 *  series of visible steps. */
private const val AMBIENT_SAMPLE_BLEND = 0.22f

/** How long the play/pause/fullscreen overlay controls stay on screen before
 *  fading out, both in fullscreen and windowed playback — reused by both
 *  auto-hide LaunchedEffects below, AND by ExoSurface's own PlayerView
 *  (see PlayerSurface.kt) so its native scrubber/controller overlay hides on
 *  the same schedule as this screen's own icons rather than lingering on
 *  Media3's separate 3s default. Not private, so PlayerSurface.kt (same
 *  package) can share it directly instead of duplicating the number, which
 *  would let the two drift out of step. Short on purpose: longer read as
 *  sluggish, the controls still sitting there well after playback had
 *  started. */
internal const val CONTROLS_AUTO_HIDE_MS = 1_000L

/**
 * What the hosting Activity needs to drive Picture-in-Picture for whatever
 * ChannelScreen currently has on screen. Reported fresh on every
 * recomposition via [onPlaybackHostChange], and cleared (null) when the
 * screen leaves composition entirely.
 *
 * Leaving the app without entering PiP doesn't pause anything: playback
 * carries on in the background for as long as Android lets the app run.
 */
data class PlaybackHost(
    val pipEnabled: Boolean,
    /** False for anything PiP doesn't make sense for — Compatibility View
     *  (a whole web page, not a video) or no media loaded yet. */
    val canPip: Boolean,
    val isPlaying: Boolean,
    val onTogglePlayPause: () -> Unit,
    /** The PiP window went away: closed by the user (true), or expanded
     *  back into the app (false). */
    val onPipLeft: (closed: Boolean) -> Unit
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChannelScreen(
    channel: String,
    onBack: () -> Unit,
    isInPictureInPicture: Boolean = false,
    onPlaybackHostChange: (PlaybackHost?) -> Unit = {},
    vm: ChannelViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val activity = context as? Activity
    val isTv = remember { isTvDevice(context) }

    LaunchedEffect(Unit) {
        focusManager.clearFocus()
    }

    var fullscreen by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }

    // The one on/off switch for the Niconico overlay, shared by both the
    // fullscreen and windowed player below — neither owns it, so toggling
    // fullscreen never itself turns the overlay on or off. `nekoState` is
    // its remembered comment state, recreated only when this actually flips
    // (a genuine new on-cycle); a fullscreen toggle while already on reuses
    // the same instance across both call sites instead of resetting it, so
    // it doesn't replay the catch-up burst just because the player swapped
    // from windowed to fullscreen chrome or back. See NekoOverlayState.
    var chatOverlayOn by remember { mutableStateOf(false) }
    val nekoState = remember(chatOverlayOn) { NekoOverlayState() }
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
    val onPlanStart = remember(vm) { vm::plannedStart }
    // Held like the others: a fresh function reference on every state
    // change counted as a new argument, so the player area couldn't skip.
    val onPlaybackFailed = remember(vm) { vm::reportPlaybackFailure }
    // Personal/unsynced playlist browsing — see ChannelViewModel's own doc
    // comment on pickPersonal for why this exists and what it does and does
    // not touch.
    val onPersonalPick = remember(vm) { vm::pickPersonal }
    val onPlaybackEnded = remember(vm) { vm::onPlaybackEnded }
    val onPlaybackStall = remember(vm) { vm::onPlaybackStall }
    val onQueueLink = remember(vm) { vm::queueLink }
    val onStartPm = remember(vm) { vm::startPm }
    val onCancelPm = remember(vm) { vm::cancelPm }

    // The single player surface, hoisted so it survives moving between the
    // compact layout and the fullscreen layout (which PiP also uses). Before
    // this, each of those was a structurally different composable subtree, so
    // toggling fullscreen made Compose tear down and rebuild the whole
    // ExoPlayer from scratch (see ExoSurface's remember { ExoPlayer... }) —
    // that rebuild-and-reconnect was the actual cause of the lag on
    // entering/exiting fullscreen. movableContentOf instead moves the same
    // already-playing instance to wherever it's called from.
    val pipModeState = rememberUpdatedState(isInPictureInPicture)

    val ambientGlowEnabledState = rememberUpdatedState(state.ambientGlowEnabled)

    // Dominant color behind the windowed player (the glow drawn behind it
    // in the windowed layout below) — a single stable holder for the whole life of this screen, not
    // re-remembered per media id, since the callback captured inside
    // playerContent (created exactly once, just below) needs to keep
    // writing to the SAME state object for as long as this screen exists.
    // Cleared back to null on every media change so a new item never
    // briefly glows with the PREVIOUS item's leftover color while its own
    // first frame is still on the way.
    var ambientColor by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(state.media?.id) { ambientColor = null }

    val playerContent = remember {
        movableContentOf {
            PlayerSurface(
                media = state.media,
                player = state.player,
                // TV never shows Media3's own touch scrubber/controller —
                // there's no touchscreen to drive it with, and it would
                // otherwise sit on screen fighting the D-pad Down/Up view
                // transition (video <-> chat) added for TV below.
                showControls = !fullscreen && !pipModeState.value && !isTv,
                onHandle = onAttachPlayer,
                onFailed = onPlaybackFailed,
                qualityIndex = state.nativeQualityIndex,
                planStart = onPlanStart,
                modifier = Modifier.fillMaxSize(),
                // Each sample here is ONE instant of the video, and on
                // fast-cutting content (an action scene, a music video) two
                // consecutive samples 3s apart can land on wildly different
                // frames — a dark shot, then an explosion, then a close-up.
                // Feeding each raw sample straight to the crossfade made the
                // glow visibly yank toward a new hue every few seconds,
                // which is what actually reads as "distracting", not the
                // crossfade itself. Blending each new sample partway toward
                // the PREVIOUS glow color (rather than replacing it outright)
                // turns that into a slow drift toward wherever the footage's
                // overall tone is trending, so one outlier frame can't swing
                // it on its own — it takes several samples in the same
                // direction to actually move the glow. Skipped for the very
                // first sample of a new item (ambientColor still null there,
                // per the LaunchedEffect above) so a fresh item still snaps
                // to its own color immediately rather than easing up from
                // the previous item's leftover one. Only when the glow is on
                // and can be seen: never on TV, which has no windowed layout
                // to show it in (and whose setting is hidden, so it stays at
                // its default of on), and not in fullscreen or PiP, where
                // it isn't drawn — sampling there was a frame grab every few
                // seconds for nothing.
                onFrameSnapshot = if (ambientGlowEnabledState.value && !isTv &&
                    !fullscreen && !pipModeState.value
                ) {
                    { bitmap ->
                        val sample = averageColor(bitmap)
                        ambientColor = ambientColor?.let { lerp(it, sample, AMBIENT_SAMPLE_BLEND) } ?: sample
                    }
                } else null,
                onEnded = onPlaybackEnded,
                onStall = onPlaybackStall
            )
        }
    }

    // Reported on every recomposition so the Activity's PiP button/state
    // (play vs. pause, whether PiP is even applicable right now) stays
    // current, and cleared when this screen goes away.
    val onTogglePlayPause = remember(vm) { vm::togglePlaybackFromPip }
    // Set when the PiP window is closed (not expanded); see justExitedPip.
    // A plain holder, not state: MainActivity reports this in the same
    // callback that ends PiP, before the recomposition that reads it.
    val pipWindowClosed = remember { booleanArrayOf(false) }
    val onPipLeft: (Boolean) -> Unit = remember(vm) {
        { closed: Boolean ->
            if (closed) pipWindowClosed[0] = true
            vm.onPipLeft(closed)
        }
    }
    SideEffect {
        onPlaybackHostChange(
            PlaybackHost(
                pipEnabled = state.pipEnabled,
                // Only Media3-played items: EMBED and WEB are web pages,
                // whose players and controls aren't set up for a PiP window.
                canPip = state.player != com.cytube.mobile.net.MediaTypes.Player.WEB &&
                    state.player != com.cytube.mobile.net.MediaTypes.Player.EMBED &&
                    state.media != null,
                isPlaying = state.playing,
                onTogglePlayPause = onTogglePlayPause,
                onPipLeft = onPipLeft
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
    // decides, further down, which branch to render.
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
    // Only when it was expanded, though. Closed with its X, the Activity is
    // stopped and doesn't recompose until the app is opened again, which
    // also lands here — and must not force landscape fullscreen on someone
    // holding the phone upright.
    if (justExitedPip) {
        if (!pipWindowClosed[0]) fullscreen = true
        pipWindowClosed[0] = false
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
        // Android throws IllegalStateException("Only fullscreen activities
        // can request orientation") if setRequestedOrientation is called
        // while the activity is in — or still transitioning out of —
        // Picture-in-Picture. That's exactly what could happen here: exiting
        // PiP changes `isInPictureInPicture`, which recomposes this screen,
        // which without this guard would immediately try to force an
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

            // Not while in PiP: the floating window has no system bars to
            // hide or show, and a request made then can leave a bar
            // animation started but never finished (see
            // rememberSystemBarInsets). Expanding it runs this again.
            if (!isInPictureInPicture) activity?.window?.let { window ->
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
    // The `return@LaunchedEffect` below comes BEFORE `lastOrientation` is
    // updated, so it stays frozen at the real pre-PiP value for the whole
    // time PiP is up. Updated to PiP's fake LANDSCAPE value, exiting PiP
    // onto a physically portrait phone would read as a genuine rotation and
    // reset fullscreen = false right as the effect above forces it true.
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
            delay(CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    // Fullscreen (and any open panel) intercepts back first, closing itself
    // instead of leaving the channel.
    BackHandler(enabled = fullscreen || openPanel != null) {
        when {
            fullscreen -> fullscreen = false
            openPanel != null -> openPanel = null
        }
    }

    // Fire TV / Android TV: no title bar, no bottom playlist/users/poll bar —
    // straight to full-bleed video the moment the channel opens, immersive
    // (system bars hidden) the same way the phone's own fullscreen is.
    // Down/Up move between fullscreen video and chat as a real view
    // transition (chat becomes the visible screen, not just focus while
    // video stays on top) — see tvShowingChat below. Back backs out of chat
    // first if it's open, then leaves the channel, same as the phone's
    // fullscreen Back handling backs out of fullscreen first.
    if (isTv) {
        // Whether chat is the visible view right now. playerContent() below
        // is still called unconditionally either way — see the
        // movableContentOf note on its declaration above for why it can
        // only ever be called from exactly one place in the composition —
        // so this can only ever be "draw chat as a sibling on top of the
        // always-mounted video Box", never a branch that swaps the video
        // composable out entirely. That's also exactly what keeps playback
        // (position, sync, connection, rate) completely undisturbed by
        // moving between the two: the video is never torn down or rebuilt,
        // only what's drawn over it changes.
        var tvShowingChat by remember { mutableStateOf(false) }
        // Personal/unsynced browsing (see ChannelViewModel.pickPersonal) has
        // no chat access on TV at all — Down goes straight to the playlist
        // instead, below, so this and tvShowingChat are mutually exclusive
        // by construction (only one Down handler ever sets either one).
        var tvShowingPlaylist by remember { mutableStateOf(false) }
        val videoFocusRequester = remember { FocusRequester() }

        BackHandler {
            when {
                tvShowingChat -> tvShowingChat = false
                tvShowingPlaylist -> tvShowingPlaylist = false
                else -> onBack()
            }
        }
        LaunchedEffect(Unit) {
            runCatching { videoFocusRequester.requestFocus() }
            runCatching {
                activity?.window?.let { window ->
                    val controller = WindowCompat.getInsetsController(window, window.decorView)
                    controller.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
        // Re-focus the video surface every time chat OR the playlist closes,
        // so a plain Down press works again immediately without first
        // navigating back to it — there's nothing else on the video screen
        // to focus instead.
        LaunchedEffect(tvShowingChat, tvShowingPlaylist) {
            if (!tvShowingChat && !tvShowingPlaylist) runCatching { videoFocusRequester.requestFocus() }
        }

        // CyTubeChannelTheme wraps this the same way it wraps the windowed
        // Scaffold below — without it, TvChatView's ChatPanel and its Nico
        // toggle square inherit whatever the ambient app theme happens to be
        // instead of the channel's own always-readable-on-dark palette, and
        // (worse) nothing here uses a Surface the way Scaffold does for the
        // windowed layout, so LocalContentColor never gets set at all and
        // falls back to its plain Compose default of black — black chat text
        // and a black on/off square on a near-black screen. TvChatView's own
        // Surface (below) is what actually fixes the content color; this is
        // what makes MaterialTheme.colorScheme resolve to the right palette
        // for it to use.
        CyTubeChannelTheme {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .focusGroup()
                    .focusRequester(videoFocusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (!tvShowingChat && !tvShowingPlaylist &&
                            event.type == KeyEventType.KeyUp &&
                            event.key == Key.DirectionDown
                        ) {
                            // Personally browsing (sync off): straight to the
                            // playlist, never chat — see tvShowingPlaylist's
                            // own comment above for why the two never both
                            // apply. Synced (the ordinary case): unchanged,
                            // same chat view as always.
                            if (state.syncEnabled) tvShowingChat = true else tvShowingPlaylist = true
                            true
                        } else {
                            false
                        }
                    }
            ) {
                if (state.player == com.cytube.mobile.net.MediaTypes.Player.WEB) {
                    // Left running when the app goes to the background,
                    // like the other players.
                    WebCompatView(
                        baseUrl = Graph.BASE_URL,
                        channel = channel,
                        authCookie = Graph.auth(context).savedSession()?.authCookie,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    playerContent()
                }

                // Exactly the same overlay the phone's fullscreen player
                // uses — reused as-is, not a separate TV implementation.
                // Independent of tvShowingChat on purpose: turning this on
                // from the TV chat view below and then pressing Up must
                // show it still active over the video, and moving back and
                // forth between video and chat must never turn it off by
                // itself — only the explicit toggle in TvChatView does.
                if (chatOverlayOn) {
                    NekoChatOverlay(
                        messages = state.messages,
                        showEmotes = state.showEmotes,
                        emotes = state.emotes,
                        state = nekoState
                    )
                }

                // No Column to slot this into on TV like the phone layout has —
                // an overlay is the only way a disconnect ever becomes visible
                // here at all. Without it a dropped connection on TV was just a
                // frozen black screen with no explanation and no way to retry.
                state.kicked?.let { reason ->
                    Box(Modifier.align(Alignment.BottomCenter).padding(32.dp)) {
                        DisconnectedNotice(reason = reason, onRetry = vm::retry, onBack = onBack)
                    }
                }
            }

            // Drawn as a sibling ON TOP of the video Box, filling the whole
            // screen — chat genuinely becomes the visible view, not a panel
            // squeezed in alongside a still-showing video.
            if (tvShowingChat) {
                TvChatView(
                    state = state,
                    onSendChat = onSendChat,
                    chatOverlayOn = chatOverlayOn,
                    onToggleChatOverlay = { chatOverlayOn = !chatOverlayOn },
                    onExit = { tvShowingChat = false },
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (tvShowingPlaylist) {
                TvPlaylistView(
                    state = state,
                    onPersonalPick = onPersonalPick,
                    onExit = { tvShowingPlaylist = false },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        }

        // See PlaybackDialogs' own doc comment: without this, a
        // password-protected channel was simply unjoinable on TV, and a
        // native playback failure was a silent black screen with no offer
        // to fall back to WebView.
        PlaybackDialogs(
            state = state,
            vm = vm,
            passwordDraft = passwordDraft,
            onPasswordDraftChange = { passwordDraft = it },
            onBack = onBack
        )
        return
    }

    // Fullscreen, and picture-in-picture too: one layout for both, so going
    // into PiP from fullscreen, or expanding PiP (which lands in fullscreen,
    // see justExitedPip above), never moves the player between layouts.
    // Moving it between layouts while the PiP window is still resizing
    // crashes Compose ("Cannot insert LayoutNode ... because it already has
    // a parent"). In PiP the
    // controls, chat overlay and PM notice are hidden — the system draws its
    // own play/pause (wired up in MainActivity).
    if (fullscreen || isInPictureInPicture) {
        FullscreenPlayer(
            inPip = isInPictureInPicture,
            state = state,
            controlsVisible = controlsVisible,
            onToggleControls = { controlsVisible = !controlsVisible },
            onExit = { fullscreen = false },
            chatOverlayOn = chatOverlayOn,
            nekoState = nekoState,
            playerContent = playerContent,
            // Chat isn't on screen in fullscreen, so a new PM gets a pill;
            // tapping it drops back to the windowed layout ready to reply.
            onOpenPm = {
                state.unreadPm?.from?.let(onStartPm)
                vm.markPmsRead()
                fullscreen = false
            }
        )
        return
    }

    // Windowed phone layout: chat is right there below the video, so a PM
    // that arrives now is seen as it lands.
    LaunchedEffect(state.unreadPm) {
        if (state.unreadPm != null) vm.markPmsRead()
    }

    // The system bars' real size, not Compose's animated copy — see
    // rememberSystemBarInsets.
    val barInsets = rememberSystemBarInsets()

    CyTubeChannelTheme {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                windowInsets = barInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                // The room's original top-bar grey, over the Slate page. The
                // action icons are grey so the channel name leads; the ones
                // that can be "on" (the favorite star) turn blue when they are.
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ChannelTopBar,
                    scrolledContainerColor = ChannelTopBar,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                title = {
                    // Tapping the channel name reconciles with the server —
                    // playlist, player and leader/sync state. It's here, not
                    // a pull-to-refresh gesture one swipe away from anyone
                    // scrolling chat, and ChannelViewModel.refresh() has its
                    // own cooldown (see there) so repeated taps can't be
                    // turned into a request flood against the channel server.
                    Column(
                        Modifier
                            .clickable(onClick = vm::refresh)
                            .semantics { contentDescription = "Refresh channel" }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(channel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (state.refreshing) {
                                Spacer(Modifier.width(8.dp))
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                        ConnectionLine(state)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Dedicated mute toggle, immediately left of the Nico
                    // square. Backed by PlayerHandle.setVolume (already
                    // implemented by every native/NewPipe/GDrive handle) via
                    // ChannelViewModel.toggleMute — purely an audio flag, so
                    // toggling it never pauses, seeks, or otherwise disrupts
                    // playback. EMBED/WEB have no PlayerHandle to mute (see
                    // PlayerSurface), so the button is hidden rather than
                    // shown greyed-out doing nothing.
                    if (state.player != com.cytube.mobile.net.MediaTypes.Player.WEB &&
                        state.player != com.cytube.mobile.net.MediaTypes.Player.EMBED
                    ) {
                        IconButton(onClick = vm::toggleMute) {
                            Icon(
                                if (state.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = if (state.muted) "Unmute" else "Mute"
                            )
                        }
                    }
                    // The sole on/off switch for the Niconico overlay — see
                    // chatOverlayOn's declaration above. Same idea as the
                    // favourite star right next to it — filled when on,
                    // outline when off — but drawn by hand rather than via a
                    // Material icon: CropSquare turned out to be the crop
                    // tool's corner-frame glyph, not a plain block, so its
                    // "filled" theme still rendered as an outline and the
                    // on/off states looked identical on device. A literal
                    // square Box can't have that problem.
                    IconButton(onClick = { chatOverlayOn = !chatOverlayOn }) {
                        val squareColor = LocalContentColor.current
                        Box(
                            Modifier
                                .size(20.dp)
                                .then(
                                    if (chatOverlayOn) {
                                        Modifier.background(squareColor)
                                    } else {
                                        Modifier.border(2.dp, squareColor)
                                    }
                                )
                                .semantics {
                                    contentDescription = if (chatOverlayOn) {
                                        "Turn off Niconico chat overlay"
                                    } else {
                                        "Turn on Niconico chat overlay"
                                    }
                                }
                        )
                    }
                    // Compatibility View drops this connection (the page has
                    // its own), and a vote while disconnected goes nowhere.
                    if (state.canVoteskip && !state.personalPickActive &&
                        state.channelCurrentMedia != null &&
                        state.connection == ConnectionState.CONNECTED &&
                        state.player != com.cytube.mobile.net.MediaTypes.Player.WEB
                    ) {
                        VoteskipButton(
                            voted = state.votedSkip,
                            tally = state.voteskipTally?.toString(),
                            onVote = vm::voteSkip
                        )
                    }
                    IconButton(onClick = vm::toggleFavourite) {
                        Icon(
                            if (state.isFavourite) Icons.Default.Star else Icons.Outlined.StarBorder,
                            contentDescription = "Favorite",
                            tint = if (state.isFavourite) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                    IconButton(onClick = { showModeSheet = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Channel options")
                    }
                }
            )
        },
        bottomBar = {
            // Scaffold reserves this bar's full height in the content padding
            // (see `) { padding -> ... }` below) at all times — it has no
            // idea the keyboard is up, so that reservation doesn't go away
            // just because the keyboard is now physically drawn on top of
            // this bar and it can't actually be seen or touched. Left alone,
            // that left ChatPanel's message row imePadding()-ing itself up
            // ABOVE an already-reserved-but-invisible PanelBar, i.e. a gap
            // between the input row and the keyboard exactly PanelBar's
            // height tall. Not drawing it at all while the keyboard is
            // visible removes the reservation instead of trying to patch
            // around it — nobody's reaching Playlist/Users/Poll while
            // actively typing anyway, and it reappears the instant the
            // keyboard is dismissed.
            if (!WindowInsets.isImeVisible) {
                PanelBar(
                    barInsets = barInsets,
                    userCount = state.userCount,
                    playlistCount = state.playlist.size,
                    pollOpen = state.poll != null,
                    pollClosed = state.poll?.closed == true,
                    onOpen = { openPanel = it }
                )
            }
        },
        // Scaffold's own default (WindowInsets.systemBars) reserves bottom
        // navigation-bar space in `padding` below UNCONDITIONALLY — on top of
        // whatever bottomBar's actual height is, not instead of it. Hiding
        // PanelBar while the keyboard is up (above) got rid of ITS
        // reservation, but this default was still separately reserving the
        // nav bar's own height underneath that, which is exactly what was
        // left of the gap between the input row and the keyboard. The bottom
        // edge doesn't need Scaffold's help here at all: PanelBar already
        // pads itself for the nav bar when it's visible (see PanelBar), and
        // ChatPanel's input row already pads itself for the keyboard
        // (imePadding, see ChatPanel) — so Scaffold is left with just the top
        // status bar and the sides, which are the only insets nothing
        // downstream already owns. (barInsets rather than
        // WindowInsets.systemBars: see rememberSystemBarInsets.)
        contentWindowInsets = barInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    ) { padding ->
        // Always the same Column: swapping in a different container when a
        // panel opens would make Compose see a structurally different
        // subtree and tear down and rebuild everything below it (the player,
        // its ExoPlayer instance, sync state) — opening Users or Playlist
        // would restart the video.
        Column(Modifier.fillMaxSize().padding(padding)) {

            val webMode = state.player == com.cytube.mobile.net.MediaTypes.Player.WEB

            // The fullscreen toggle fades out after a moment idle
            // (CONTROLS_AUTO_HIDE_MS) and back in the instant the player is
            // touched, like the true-fullscreen controls below — left up, a
            // bare white icon reads as a stray white square over the video.
            var windowedControlsVisible by remember { mutableStateOf(true) }
            LaunchedEffect(windowedControlsVisible, webMode) {
                if (windowedControlsVisible && !webMode) {
                    delay(CONTROLS_AUTO_HIDE_MS)
                    windowedControlsVisible = false
                }
            }

            // Reserved as soon as this item is eligible at all (setting on,
            // not Compatibility View or an embed) rather than waiting for a
            // color — that way the margin never pops in as a sudden layout
            // shift once the snapshot lands. Before a color exists (or when
            // the setting is off) it's just 16dp of ordinary background,
            // indistinguishable from normal spacing.
            val ambientGlowActive = state.ambientGlowEnabled && !webMode &&
                state.player != com.cytube.mobile.net.MediaTypes.Player.EMBED

            // Animated, not snapped: this crossfades from fully transparent
            // up to the real color (and directly between two colors on a
            // channel switch) over most of the gap between samples (see
            // AMBIENT_RESAMPLE_INTERVAL_MS in PlayerSurface — samples land
            // every 3s, this runs 2.8s of it), so the hue is nearly always
            // gently in motion rather than easing in and then sitting still
            // until the next sample. Combined with the sample blending in
            // onFrameSnapshot above (which keeps any one step small), the
            // color drifts continuously and slowly instead of visibly
            // "updating". Reading .value inside drawBehind below — rather
            // than destructuring this with `by` up here in the composable
            // body — is what keeps this cheap: that defers the read to the
            // draw phase, so each animation tick only re-runs this one
            // gradient draw, not a recomposition of the screen around it.
            val animatedGlow = animateColorAsState(
                targetValue = ambientColor ?: Color.Transparent,
                animationSpec = tween(durationMillis = 2_800),
                label = "ambientGlow"
            )

            // A slow, independent brightness pulse on top of the hue drift
            // above — the actual "hypnotic" part. Only animated when the glow
            // is visually active (playing, with a non-null color sample) to
            // avoid keeping the 60/120Hz render loop running endlessly during
            // pause or before the video starts. Nothing to check for the
            // background: Compose stops animating when the app isn't visible.
            val isGlowVisuallyActive = ambientGlowActive && state.playing &&
                ambientColor != null

            val glowPulse = if (isGlowVisuallyActive) {
                rememberInfiniteTransition(label = "ambientPulse").animateFloat(
                    initialValue = 0.85f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 4_000, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "ambientPulseAlpha"
                )
            } else null

            Box(
                Modifier.fillMaxWidth()
                    .then(
                        if (ambientGlowActive) Modifier.drawBehind {
                            val glow = animatedGlow.value
                            if (glow.alpha <= 0f) return@drawBehind
                            val pulse = glowPulse?.value ?: 1f
                            // Bottom-only: color hangs below the video and
                            // fades out toward the outer edge, like light
                            // spilling out from underneath rather than a
                            // halo all the way around. ~0.93 is where the
                            // video's own bottom edge lands for a typical
                            // phone width, given the 16dp margin below it —
                            // approximate, not pixel-exact, since the
                            // opaque video covers everything above that
                            // regardless of what the gradient does there.
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.93f to glow.copy(alpha = glow.alpha * 0.55f * pulse),
                                    1f to Color.Transparent
                                )
                            )
                        } else Modifier
                    )
                    .then(if (ambientGlowActive) Modifier.padding(bottom = 16.dp) else Modifier)
                    .then(
                        if (webMode) Modifier.weight(1f)
                        else Modifier.aspectRatio(16f / 9f)
                    )
                    .background(Color.Black)
                    .then(
                        if (webMode) Modifier else Modifier.pointerInput(Unit) {
                            // Only observing, never consuming: the native
                            // ExoPlayer controller underneath still needs
                            // every one of these touches for its own
                            // play/pause/seek handling. This just also
                            // notices "the player was touched" so the
                            // fullscreen button can reappear.
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                windowedControlsVisible = true
                            }
                        }
                    )
            ) {
                if (state.player == com.cytube.mobile.net.MediaTypes.Player.WEB) {
                    // Compatibility View is the whole CyTube page again, not a
                    // player surface. It owns the channel entirely while it is up.
                    // Left running when the app goes to the background,
                    // like the other players.
                    WebCompatView(
                        baseUrl = Graph.BASE_URL,
                        channel = channel,
                        authCookie = Graph.auth(context).savedSession()?.authCookie,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    playerContent()
                    // Same switch, same remembered comment state as the
                    // fullscreen player below — see chatOverlayOn/nekoState
                    // above. Confined to this Box (fillMaxSize of its own
                    // BoxWithConstraints, which only ever sees this Box's
                    // bounds), so it flies across the video area only, not
                    // the whole screen, while windowed.
                    if (chatOverlayOn) {
                        NekoChatOverlay(
                            messages = state.messages,
                            showEmotes = state.showEmotes,
                            emotes = state.emotes,
                            state = nekoState
                        )
                    }
                    // Top-right, not bottom-right: Media3's own PlayerView
                    // draws its settings/gear control in the bottom corner,
                    // and the two would sit right on top of each other.
                    WindowedFullscreenButton(
                        visible = windowedControlsVisible,
                        onClick = { fullscreen = true },
                        modifier = Modifier.align(Alignment.TopEnd)
                    )
                }
            }

            state.media?.let { NowPlayingBar(it.title, state.leader) }

            if (state.motd.isNotBlank()) {
                MotdSection(
                    html = state.motd,
                    emotes = state.emotes,
                    expanded = state.motdExpanded,
                    onToggle = vm::toggleMotd,
                    // Tucked up under the title it belongs with, rather than
                    // floating halfway between the title and the chat.
                    underTitle = state.media != null
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
                highlightName = state.localUser,
                // PMs need a name to send as (guests count, once joined).
                onStartPm = if (state.localUser != null) onStartPm else null,
                pmTarget = state.pmTarget,
                onCancelPm = onCancelPm,
                modifier = Modifier.weight(1f)
            )
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
                    syncEnabled = state.syncEnabled,
                    personalPickUid = state.personalPickUid,
                    onPersonalPick = onPersonalPick,
                    // Phone only: TV has its own playlist view and returns
                    // before this layout (a text box here would be a D-pad
                    // trap there, like the search box once was).
                    canAdd = state.canQueue,
                    canAddNext = state.canQueueNext,
                    queueStatus = state.queueStatus,
                    onAddLink = onQueueLink,
                    modifier = Modifier.fillMaxHeight(0.8f)
                )
                Panel.USERS -> UsersPanel(
                    users = state.users,
                    localName = state.localUser,
                    onStartPm = if (state.localUser != null) {
                        { name -> onStartPm(name); openPanel = null }
                    } else null,
                    modifier = Modifier.fillMaxHeight(0.8f)
                )
                Panel.POLL -> PollPanel(
                    poll = state.poll,
                    myVote = state.myPollVote,
                    onVote = vm::votePoll,
                    canVote = state.canVotePoll,
                    onDismiss = { vm.dismissPoll(); openPanel = null },
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

    PlaybackDialogs(
        state = state,
        vm = vm,
        passwordDraft = passwordDraft,
        onPasswordDraftChange = { passwordDraft = it },
        onBack = onBack
    )
    }
}

/**
 * The three dialogs that can interrupt joining or watching a channel —
 * a password prompt, and the two "native playback didn't work, try
 * WebView?" offers. Shared by the phone Scaffold path and the isTv branch
 * (which returns before reaching the Scaffold), so a TV user also gets the
 * password prompt and the fallback offers, without two copies to keep in
 * sync by hand.
 */
@Composable
private fun PlaybackDialogs(
    state: ChannelUiState,
    vm: ChannelViewModel,
    passwordDraft: String,
    onPasswordDraftChange: (String) -> Unit,
    onBack: () -> Unit
) {
    // Only shown when there's no single-video fallback to try instead —
    // ChannelViewModel.reportPlaybackFailure switches straight to EMBED with
    // no prompt whenever one exists, since that swap keeps chat, playlist,
    // sync, fullscreen and the Nico overlay all working exactly as they were
    // a moment ago. This dialog is for the case that actually costs
    // something: dropping the native socket for the whole CyTube page.
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
                        onValueChange = onPasswordDraftChange,
                        singleLine = true,
                        label = { Text("Password") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.submitPassword(passwordDraft); onPasswordDraftChange("") }) {
                    Text("Join")
                }
            },
            dismissButton = { TextButton(onClick = onBack) { Text("Leave") } }
        )
    }
}

@Composable
private fun WindowedFullscreenButton(visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        IconButton(onClick = onClick, modifier = Modifier.padding(8.dp)) {
            Icon(Icons.Default.Fullscreen, contentDescription = "Fullscreen", tint = Color.White)
        }
    }
}

@Composable
private fun ConnectionLine(state: ChannelUiState) {
    val (text, color) = when (state.connection) {
        ConnectionState.CONNECTED ->
            (state.statusMessage ?: "${state.userCount} connected") to
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
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
    onToggle: () -> Unit,
    underTitle: Boolean = false
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val context = LocalContext.current
    val rendered = remember(html, emotes) {
        ChatHtml.render(
            html, greentext = false, linkColor = linkColor,
            showImages = false, emotes = emotes, dropImages = true
        )
    }
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (underTitle) Modifier.pullUp(NOTICE_PULL_UP) else Modifier)
            .padding(start = 16.dp, end = 16.dp, top = if (underTitle) 0.dp else 6.dp, bottom = 6.dp)
    ) {
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

/** How far the channel notice tucks up into the title row's bottom
 *  padding (see MotdSection), so it reads as part of the title. */
private val NOTICE_PULL_UP = 4.dp

/** Draws this [by] higher and gives that space back below it, unlike
 *  offset(), which moves the drawing but leaves a gap where it was. */
private fun Modifier.pullUp(by: androidx.compose.ui.unit.Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val up = by.roundToPx().coerceAtMost(placeable.height)
    layout(placeable.width, placeable.height - up) { placeable.place(0, -up) }
}

@Composable
private fun NowPlayingBar(title: String, leader: String?) {
    Row(
        // Closer to what's above (the video, or its glow) than to the chat
        // below, so the title reads as the video's caption; the channel
        // notice tucks in right under it (see MotdSection).
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
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

/**
 * Vote to skip, in the top bar between the Niconico square and the star.
 * Only there when the channel allows it and our rank may vote (and not on a
 * personal pick — the vote is on the channel's item). Once voted it stays,
 * dimmed, until the next video: the server counts one vote per video. While
 * a vote is running the count sits above the icon like a fraction ("2/5");
 * the server only sends that to ranks allowed to see it (moderators, by
 * default).
 */
@Composable
private fun VoteskipButton(voted: Boolean, tally: String?, onVote: () -> Unit) {
    IconButton(
        onClick = onVote,
        enabled = !voted,
        modifier = Modifier.semantics {
            contentDescription = when {
                voted && tally != null -> "Voted to skip, $tally"
                voted -> "Voted to skip"
                tally != null -> "Vote to skip this video, $tally"
                else -> "Vote to skip this video"
            }
        }
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (tally != null) {
                Text(
                    tally,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 10.sp),
                    maxLines = 1
                )
            }
            Icon(
                Icons.Default.SkipNext,
                contentDescription = null,
                modifier = Modifier.size(if (tally != null) 18.dp else 24.dp)
            )
        }
    }
}

/**
 * A standard NavigationBar reserves ~80dp for what is really just three
 * text-only buttons — most of that height is padding a label never needs.
 * This slim custom row keeps the same 48dp minimum touch target (Android's
 * own accessibility floor) while giving noticeably more of a phone screen
 * back to chat. Fire TV never calls this at all (see the isTv branch above),
 * so it only ever affects the touch UI.
 */
@Composable
private fun PanelBar(
    /** See rememberSystemBarInsets. */
    barInsets: WindowInsets,
    userCount: Int,
    playlistCount: Int,
    pollOpen: Boolean,
    onOpen: (Panel) -> Unit,
    pollClosed: Boolean = false
) {
    // The stock NavigationBar this replaced pads itself for the system nav
    // bar automatically; a plain Surface doesn't, so on 3-button navigation
    // this row was sitting flush against the bottom edge and getting
    // covered by the triangle/circle/square buttons themselves. Padding by
    // the navigation bar (bottom and sides, like Material3's own
    // NavigationBar) reserves that space back.
    // The same grey as the page, with a hairline above, rather than a
    // raised slab of its own.
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.windowInsetsPadding(
            barInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
        )
    ) {
        Column {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().height(48.dp)) {
                PanelBarButton("Playlist", if (playlistCount > 0) playlistCount else null, Modifier.weight(1f)) {
                    onOpen(Panel.PLAYLIST)
                }
                PanelBarButton("Users", userCount, Modifier.weight(1f)) { onOpen(Panel.USERS) }
                // Only shown while there's a poll — running, or just closed
                // with its final results (until the next one or it's
                // dismissed). Blue while it's running: the one tab with
                // something happening in it.
                if (pollOpen) {
                    PanelBarButton(
                        if (pollClosed) "Poll results" else "Poll",
                        count = null,
                        modifier = Modifier.weight(1f),
                        active = !pollClosed
                    ) { onOpen(Panel.POLL) }
                }
            }
        }
    }
}

/** A tab label in the home page's label style: small, spaced-out capitals
 *  in grey, with its count a step fainter; blue when [active]. */
@Composable
private fun PanelBarButton(
    label: String,
    count: Int?,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val labelColor = if (active) colors.primary else colors.onSurfaceVariant
    val countColor = if (active) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.6f)
    TextButton(
        onClick = onClick,
        modifier = modifier.fillMaxHeight(),
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = labelColor)) { append(label.uppercase()) }
                if (count != null) {
                    withStyle(SpanStyle(color = countColor)) { append("  $count") }
                }
            },
            style = TextStyle(fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
        MediaTypes.Player.STREAMABLE -> "Playing $label natively via Streamable."
        MediaTypes.Player.PEERTUBE -> "Playing $label natively via PeerTube."
        com.cytube.mobile.net.MediaTypes.Player.EMBED ->
            "$label plays via the provider's own embed. Chat, playlist and sync stay native."
        com.cytube.mobile.net.MediaTypes.Player.WEB ->
            "$label needs Compatibility View. Chat and playlist come from the page."
        com.cytube.mobile.net.MediaTypes.Player.UNAVAILABLE ->
            "$label can't play natively. Choose Web mode below to load it."
    }
}

/**
 * TV's chat view, reached from fullscreen video with D-pad Down and left
 * with Up or Back (see the `isTv` branch above). This is deliberately NOT a
 * separate or simplified TV chat implementation — it wraps the exact same
 * ChatPanel the phone app uses for its own chat, with only the D-pad
 * plumbing added on top: filling the screen (a real view transition, not a
 * panel next to a still-visible video), treating Up as "back to fullscreen
 * video" from anywhere inside it via onPreviewKeyEvent, and a focusable,
 * D-pad-reachable stand-in for the phone's touch-only Nico square button in
 * its TopAppBar — same on/off visual, same behavior (toggles the shared
 * chatOverlayOn state hoisted in ChannelScreen), just reachable without a
 * touchscreen.
 */
@Composable
private fun TvChatView(
    state: ChannelUiState,
    onSendChat: (String) -> Unit,
    chatOverlayOn: Boolean,
    onToggleChatOverlay: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val nicoFocusRequester = remember { FocusRequester() }
    val chatInputFocusRequester = remember { FocusRequester() }
    var nicoFocused by remember { mutableStateOf(false) }
    // Focus the Nico toggle on entry so there's an immediate, visible focus
    // target — without this the chat view opened with nothing focused at
    // all, leaving the remote's first press to go nowhere.
    LaunchedEffect(Unit) { runCatching { nicoFocusRequester.requestFocus() } }

    // Surface, not a plain .background() modifier — this is what actually
    // sets LocalContentColor to a color that reads against this background
    // (contentColorFor(background), i.e. the channel theme's light
    // onBackground). A raw background() modifier only paints a color, it
    // doesn't touch LocalContentColor, which otherwise stays at Compose's
    // default of plain black — the cause of the chat text and the Nico
    // square both rendering dark-on-dark here.
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
    Column(
        Modifier
            .fillMaxSize()
            // Up is one stop at a time, not a straight exit from anywhere in
            // here: from the chat bar it goes to Nico, and only a *second*
            // Up — now with Nico actually focused — leaves to the video.
            // This has to live up here rather than on the input field itself
            // (see ChatPanel's inputFieldModifier) because preview key events
            // reach this Column before they reach whatever's focused below
            // it, so this is the first and only place that sees every Up
            // press regardless of which of the two stops currently has focus.
            //
            // Both KeyDown and KeyUp phases have to be swallowed here, not
            // just KeyUp (where the actual action below fires) — this Column
            // is not an ancestor of the message list, so ChatPanel's own
            // Up/Down swallow on it never runs when focus is sitting in the
            // chat input field a sibling below. Left unconsumed, the KeyDown
            // phase fell through to Compose's default arrow-key focus
            // search, which happily found the nearest focusable thing
            // upward — a message's username, focusable via its own
            // clickable — and jumped focus (and the list's scroll) there,
            // at the same time this handler was also moving focus to Nico
            // on the KeyUp that followed. That's what "chat should not be
            // scrollable" was actually seeing: not a real scroll gesture,
            // just a focus-search side effect nothing here was blocking.
            .onPreviewKeyEvent { event ->
                if (event.key != Key.DirectionUp) {
                    false
                } else {
                    if (event.type == KeyEventType.KeyUp) {
                        if (nicoFocused) onExit() else runCatching { nicoFocusRequester.requestFocus() }
                    }
                    true
                }
            }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                state.channel,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Explicit, not LocalContentColor — this square being legible in
            // both its on/off states is the whole point of it, so it doesn't
            // depend on ambient content color resolving correctly.
            val squareColor = MaterialTheme.colorScheme.onBackground
            Box(
                Modifier
                    .focusRequester(nicoFocusRequester)
                    .onFocusChanged { nicoFocused = it.isFocused }
                    .focusable()
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyUp) {
                            false
                        } else if (event.key == Key.Enter || event.key == Key.DirectionCenter || event.key == Key.NumPadEnter) {
                            onToggleChatOverlay()
                            true
                        } else if (event.key == Key.DirectionDown) {
                            // Straight to the chat bar, deliberately not a
                            // default focus search — the message list sits
                            // in between in the layout and must never be
                            // what a Down press from here lands on.
                            runCatching { chatInputFocusRequester.requestFocus() }
                            true
                        } else {
                            false
                        }
                    }
                    .border(
                        2.dp,
                        if (nicoFocused) MaterialTheme.colorScheme.primary else Color.Transparent
                    )
                    .padding(6.dp)
                    .semantics {
                        contentDescription = if (chatOverlayOn) {
                            "Turn off Niconico chat overlay"
                        } else {
                            "Turn on Niconico chat overlay"
                        }
                    }
            ) {
                Box(
                    Modifier
                        .size(20.dp)
                        .then(
                            if (chatOverlayOn) {
                                Modifier.background(squareColor)
                            } else {
                                Modifier.border(2.dp, squareColor)
                            }
                        )
                )
            }
        }

        // Same ChatPanel the phone layout uses (message list, input, send)
        // — no Polls/User List/Playlist content in it at all, so there's
        // nothing else TV-specific to hide here and no separate
        // implementation to keep in sync with the phone one.
        ChatPanel(
            messages = state.messages,
            canSend = state.connection == ConnectionState.CONNECTED,
            showEmotes = state.showEmotes,
            emotes = state.emotes,
            onSend = onSendChat,
            highlightName = state.localUser,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            // No D-pad interaction with individual messages on TV — the
            // list stays pinned to the latest message and is never a focus
            // stop, so it can't get in the way of Nico <-> chat bar <-> video.
            messagesFocusable = false,
            inputFieldModifier = Modifier.focusRequester(chatInputFocusRequester),
            // No touch to pick an emote with on TV, and the picker's grid
            // is its own separate focus surface this screen isn't built to
            // host — see ChatPanel's doc comment on the parameter.
            showEmotePickerButton = false
            // Spoiler reveal is handled inside ChatRow itself, keyed off
            // messagesFocusable (false here) — see its own comment. No
            // separate flag needed at this level.
        )
    }
    }
}

/**
 * TV's playlist view — the personal/unsynced-browsing counterpart to
 * TvChatView just above, reached the same way (D-pad Down from fullscreen
 * video, left with Up or Back) but ONLY while state.syncEnabled is false
 * (see the isTv branch's Down handler). Chat has no D-pad path at all in
 * that mode: turning sync off is a deliberate "I'm browsing on my own"
 * choice, and a synced channel-wide chat conversation sitting one Down
 * press away from a personal pick would be a strange mix, not a missing
 * feature — so this view replaces TvChatView entirely rather than sitting
 * alongside it. Same structural pattern as TvChatView throughout: a
 * fillMaxSize Surface (for real LocalContentColor, not just a painted
 * background), one Up-swallowing onPreviewKeyEvent that jumps focus to the
 * search field (the top stop) or exits from it, and reuse of what the phone
 * layout already has for a personal pick (ChannelViewModel.pickPersonal) —
 * MediaTypes.canResolveIndependently decides which rows are actually
 * selectable, the same rule PlaylistPanel greys rows out with, for the same
 * reason (see that function's own doc comment).
 */
@Composable
private fun TvPlaylistView(
    state: ChannelUiState,
    onPersonalPick: (com.cytube.mobile.net.PlaylistItem) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val searchFocusRequester = remember { FocusRequester() }
    val firstRowFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var searchFocused by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // Focuses the first row, not the search box — like TvChatView's own
    // LaunchedEffect(Unit) (see its comment), which focuses the non-text
    // Nico toggle first rather than the chat input field. Focusing a
    // text field pops Android TV's on-screen keyboard immediately — before
    // the user has navigated anywhere — and that overlay then eats D-pad
    // input itself: Down moves across keyboard keys instead of scrolling the
    // list below, and Up/Back presses meant for this screen's own exit
    // handling never reach it either, since the keyboard consumes them first.
    // Focusing the first row instead means the keyboard only appears when the
    // user deliberately navigates Up into search — exactly when they want it.
    LaunchedEffect(Unit) {
        if (state.playlist.isNotEmpty()) {
            runCatching { firstRowFocusRequester.requestFocus() }
        } else {
            // Nothing to scroll to — search is the only focusable thing here.
            runCatching { searchFocusRequester.requestFocus() }
        }
    }

    val filtered = remember(state.playlist, query) {
        if (query.isBlank()) state.playlist
        else state.playlist.filter { it.title.contains(query, ignoreCase = true) }
    }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
    Column(
        Modifier
            .fillMaxSize()
            // Same one-stop-at-a-time shape as TvChatView's own Up handler
            // (see its doc comment) — from the search field this exits, from
            // anywhere in the list below it this jumps straight back UP to
            // the search field rather than doing a real per-row focus
            // search, so Up always has one predictable meaning regardless of
            // how far down the list focus currently is.
            .onPreviewKeyEvent { event ->
                if (event.key != Key.DirectionUp) {
                    false
                } else {
                    if (event.type == KeyEventType.KeyUp) {
                        if (searchFocused) onExit() else runCatching { searchFocusRequester.requestFocus() }
                    }
                    true
                }
            }
    ) {
        Text(
            "Playlist — browsing unsynced",
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Search playlist") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .focusRequester(searchFocusRequester)
                // hasFocus, not isFocused: the text field keeps its focus on
                // a node inside itself, so isFocused here stayed false and Up
                // never saw the field as focused (it kept re-focusing it
                // instead of going back to the video).
                .onFocusChanged { searchFocused = it.hasFocus }
                // If the user does deliberately navigate up into search (or
                // this is the empty-playlist fallback above), the on-screen
                // keyboard shows and — same problem as the auto-focus case —
                // a soft keyboard's own Down normally just moves across its
                // keys rather than reaching this handler at all. That part
                // can't be fixed here; what this handles is the keyboard
                // NOT eating Down, which does happen (physical/TV-remote
                // D-pad presses reach Compose even while the IME is up,
                // unlike Back — see the LaunchedEffect comment above for why
                // Back can't be handled the same way). Explicitly hiding the
                // keyboard here means Down always dismisses it and continues
                // on to the first row in one press, rather than leaving the
                // keyboard sitting open on screen after focus has already
                // moved past it.
                //
                // A preview handler, seen before the text field itself: the
                // field takes Down (to move its cursor) before a plain key
                // handler gets it, and then nothing moved focus at all.
                // Down closes the keyboard and goes to the first row.
                .onPreviewKeyEvent { event ->
                    if (event.key != Key.DirectionDown) {
                        false
                    } else {
                        if (event.type == KeyEventType.KeyDown) {
                            keyboardController?.hide()
                            if (filtered.isNotEmpty()) runCatching { firstRowFocusRequester.requestFocus() }
                        }
                        true
                    }
                },
            // The keyboard's own action key does the same: close it and go
            // to the results, rather than leave the keyboard up.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                keyboardController?.hide()
                if (filtered.isNotEmpty()) runCatching { firstRowFocusRequester.requestFocus() }
            })
        )
        Spacer(Modifier.height(8.dp))

        if (filtered.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (state.playlist.isEmpty()) "The playlist is empty."
                    else "No matches for \"$query\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                // Same stable-key fix as PlaylistPanel in Panels.kt — see that
                // file's comment. On TV this one matters even more: rowFocused
                // is per-row remembered state, and an idx-suffixed key reset it
                // (losing the focus highlight) on every playlist mutation.
                itemsIndexed(filtered, key = { idx, item -> if (item.uid >= 0) "item_${item.uid}" else "pos_${idx}_${item.mediaId}" }) { idx, item ->
                    val personallyResolvable =
                        com.cytube.mobile.net.MediaTypes.canResolveIndependently(item.type)
                    val isPick = item.uid == state.personalPickUid
                    var rowFocused by remember { mutableStateOf(false) }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            // Only the very first row — this is purely the
                            // initial-focus target the LaunchedEffect above
                            // requests focus on when the screen opens; every
                            // other row is a completely ordinary focus target
                            // reached by normal D-pad navigation.
                            .then(if (idx == 0) Modifier.focusRequester(firstRowFocusRequester) else Modifier)
                            .background(
                                if (isPick) MaterialTheme.colorScheme.surfaceContainerHigh
                                else Color.Transparent
                            )
                            .alpha(if (personallyResolvable) 1f else 0.4f)
                            // Placed ahead of .clickable() below rather than
                            // a separate .focusable() — clickable already
                            // creates its own focus target for D-pad/keyboard
                            // input, and a second explicit .focusable() here
                            // would just add a redundant focus stop for the
                            // same row. onFocusChanged upstream of it still
                            // observes that same focus node's state.
                            .onFocusChanged { rowFocused = it.isFocused }
                            .border(
                                2.dp,
                                if (rowFocused) MaterialTheme.colorScheme.primary else Color.Transparent
                            )
                            .clickable(enabled = personallyResolvable) { onPersonalPick(item) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isPick) {
                            Icon(
                                Icons.Default.PlayArrow, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isPick) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                buildString {
                                    append(item.duration)
                                    append(" · ")
                                    append(com.cytube.mobile.net.MediaTypes.label(item.type))
                                    if (!personallyResolvable) append(" · unavailable unsynced")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun FullscreenPlayer(
    inPip: Boolean,
    state: ChannelUiState,
    controlsVisible: Boolean,
    onToggleControls: () -> Unit,
    onExit: () -> Unit,
    chatOverlayOn: Boolean,
    nekoState: NekoOverlayState,
    playerContent: @Composable () -> Unit,
    onOpenPm: () -> Unit = {}
) {
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(state.player) {
                if (state.player == com.cytube.mobile.net.MediaTypes.Player.EMBED) {
                    // EmbedSurface's WebView handles touch across its whole
                    // surface for its own scrolling/zoom/link taps, the same
                    // way PlayerView's controller owns part of its area — see
                    // the identical fix on the windowed fullscreen button's
                    // reveal-on-touch listener above. A default-pass
                    // detectTapGestures here never sees a tap the WebView
                    // already consumed first, which meant this exit-
                    // fullscreen control could fade out on an EMBED item and
                    // then never come back — nothing else would reveal it.
                    // Initial pass, left unconsumed, observes every touch
                    // without taking it away from the WebView underneath.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        onToggleControls()
                    }
                } else {
                    detectTapGestures { onToggleControls() }
                }
            }
    ) {
        playerContent()

        if (chatOverlayOn && !inPip) {
            // Fills the whole screen itself (danmaku-style comments fly the
            // full width, on lanes spanning the full height), so no
            // alignment/sizing to set here beyond the default. Independent
            // of controlsVisible on purpose: once turned on, the overlay is
            // meant to stay up while you watch, Neko/mpv-style — not blink
            // out the moment the exit button/title fade away on that same
            // idle timer. `chatOverlayOn`/`nekoState` are hoisted up to
            // ChannelScreen and shared with the windowed player's own call
            // site — this has no on/off control of its own; that lives
            // solely in the TopAppBar, above the video.
            NekoChatOverlay(
                messages = state.messages,
                showEmotes = state.showEmotes,
                emotes = state.emotes,
                state = nekoState
            )
        }

        AnimatedVisibility(visible = controlsVisible && !inPip, enter = fadeIn(), exit = fadeOut()) {
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

        // Stays up regardless of the auto-hiding controls: it's the only sign
        // of a new PM while chat is off screen.
        if (!inPip) state.unreadPm?.let { unread ->
            Surface(
                onClick = onOpenPm,
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.MailOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (unread.count > 1) "PM from ${unread.from} (+${unread.count - 1})" else "PM from ${unread.from}",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * The system bars' real current size, read straight from the window each
 * time it lays out, for the windowed layout's top bar, content and bottom
 * bar.
 *
 * Compose's own WindowInsets.systemBars follows the bars' show/hide
 * animations and only takes the new size once an animation completes; when
 * one is cut short — leaving fullscreen rotates the phone back and shows the
 * bars at the same moment, and bar changes asked for around picture-in-
 * picture can be dropped — it can be left believing the bars are hidden.
 * That was the whole windowed layout slid up under the status bar and down
 * under the navigation buttons after PiP → fullscreen → windowed, until the
 * next keyboard or bar change. Reading the window's own current value
 * can't get stuck like that. (The keyboard still uses Compose's insets:
 * opening it always starts a fresh animation.)
 */
@Composable
private fun rememberSystemBarInsets(): WindowInsets {
    val view = LocalView.current
    var bars by remember { mutableStateOf(currentSystemBars(view)) }
    DisposableEffect(view) {
        val observer = view.viewTreeObserver
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            val now = currentSystemBars(view)
            if (now != bars) bars = now
        }
        observer.addOnGlobalLayoutListener(listener)
        bars = currentSystemBars(view)
        onDispose {
            if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener)
            else view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
    }
    return remember(bars) { WindowInsets(bars.left, bars.top, bars.right, bars.bottom) }
}

private fun currentSystemBars(view: android.view.View): androidx.core.graphics.Insets =
    androidx.core.view.ViewCompat.getRootWindowInsets(view)
        ?.getInsets(WindowInsetsCompat.Type.systemBars())
        ?: androidx.core.graphics.Insets.NONE
