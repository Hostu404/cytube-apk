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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cytube.mobile.data.CompatMode
import com.cytube.mobile.di.Graph
import com.cytube.mobile.ui.isTvDevice
import com.cytube.mobile.ui.theme.CyTubeChannelTheme
import kotlinx.coroutines.delay

private enum class Panel { PLAYLIST, USERS, POLL }

/** How far each new ambient-glow sample moves the glow toward itself, out of
 *  1.0 — see the onFrameSnapshot comment in ChannelScreen for why this
 *  exists. Low on purpose: a single sample should nudge the color, not set
 *  it, so a fast-cutting video's glow drifts with the overall footage
 *  instead of snapping to whatever one frame happened to look like. Kept
 *  gentle enough that, combined with the long near-continuous crossfade in
 *  WindowedAmbientGlow, the color's motion stays subtle rather than a
 *  series of visible steps. */
private const val AMBIENT_SAMPLE_BLEND = 0.22f

/**
 * What the hosting Activity needs to drive Picture-in-Picture for whatever
 * ChannelScreen currently has on screen. Reported fresh on every
 * recomposition via [onPlaybackHostChange], and cleared (null) when the
 * screen leaves composition entirely.
 *
 * Used to also carry onPauseForBackground/onResumeForForeground so the
 * Activity could pause playback on Home/Recents — removed along with that
 * behavior: leaving the app without entering PiP now simply lets playback
 * keep running in the background (see MainActivity's onStop/onStart, which
 * no longer do anything to it), same as PiP's own floating window already
 * did, instead of the screen going dark and silent every time you check
 * another app.
 */
data class PlaybackHost(
    val pipEnabled: Boolean,
    /** False for anything PiP doesn't make sense for — Compatibility View
     *  (a whole web page, not a video) or no media loaded yet. */
    val canPip: Boolean,
    val isPlaying: Boolean,
    val onTogglePlayPause: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelScreen(
    channel: String,
    onBack: () -> Unit,
    onFullscreenChange: (Boolean) -> Unit,
    isInPictureInPicture: Boolean = false,
    /** True while MainActivity is stopped (Home/Recents/screen off), from its
     *  onStop/onStart. Drives two battery-saving effects below: dropping the
     *  ExoPlayer video track (audioOnly) and backing off the socket's
     *  reconnect cadence (vm.onAppBackgroundChanged) — neither needs a new
     *  permission, unlike a real foreground service would. Always false in
     *  PiP, even while backgrounded: PiP's whole point is a visible video. */
    isAppInBackground: Boolean = false,
    onPlaybackHostChange: (PlaybackHost?) -> Unit = {},
    vm: ChannelViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val isTv = remember { isTvDevice(context) }

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

    // Lets the reconnect backoff back off while nobody's watching — see
    // CyTubeClient.setBackgrounded. Cheap to call on every flip; it's just a
    // couple of field writes on the socket.io Manager.
    LaunchedEffect(isAppInBackground) { vm.onAppBackgroundChanged(isAppInBackground) }

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

    // Same rememberUpdatedState pattern as pipModeState just above, for the
    // same reason: playerContent below is captured once by remember{}, so a
    // plain read of a changing parameter inside it would freeze at whatever
    // value was current on the very first composition. Never true while in
    // PiP — floating video with no video track would just show a blank
    // window, defeating the point of PiP.
    val audioOnlyState = rememberUpdatedState(isAppInBackground && !isInPictureInPicture)

    // Dominant color behind the windowed player (see WindowedAmbientGlow
    // below) — a single stable holder for the whole life of this screen, not
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
                onFailed = vm::reportPlaybackFailure,
                epoch = state.playerEpoch,
                modifier = Modifier.fillMaxSize(),
                // Each sample here is ONE instant of the video, and on
                // fast-cutting content (an action scene, a music video) two
                // consecutive samples 4s apart can land on wildly different
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
                // the previous item's leftover one.
                onFrameSnapshot = { bitmap ->
                    val sample = averageColor(bitmap)
                    ambientColor = ambientColor?.let { lerp(it, sample, AMBIENT_SAMPLE_BLEND) } ?: sample
                },
                audioOnly = audioOnlyState.value
            )
        }
    }

    // Reported on every recomposition so the Activity's PiP button/state
    // (play vs. pause, whether PiP is even applicable right now) stays
    // current, and cleared when this screen goes away.
    val onTogglePlayPause = remember(vm) { vm::togglePlaybackFromPip }
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
                onTogglePlayPause = onTogglePlayPause
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
    // `settlingFromPip` itself has to flip to true in the SAME synchronous
    // pass as `fullscreen` above, not inside the LaunchedEffect below — a
    // LaunchedEffect's body only starts running after this composition
    // commits, so if the "= true" lived there, there was exactly one
    // recomposition (the one where isInPictureInPicture first goes false)
    // where isInPictureInPicture was already false AND settlingFromPip was
    // still its old value of false. On that one frame the guard below is
    // false, so playerContent() got moved straight out of the PiP Box into
    // FullscreenPlayer's Box while the system's own PiP-exit window
    // animation was still running — and one recomposition later, once the
    // effect's `settlingFromPip = true` landed, it got moved straight back
    // into the PiP Box, then forward again 220ms after that. That extra
    // there-and-back move of the same movableContentOf-hoisted player
    // content (see playerContent's own comment above) is what was tripping
    // Compose's "Cannot insert LayoutNode... because it already has a
    // parent" crash on expand-from-PiP — not the single clean move this was
    // written to produce. Setting it synchronously here, exactly like
    // `fullscreen` just above, closes that gap: both flip in the same pass
    // isInPictureInPicture does, so the PiP Box stays the host without
    // interruption until the (still-async) timeout below hands it off.
    var settlingFromPip by remember { mutableStateOf(false) }
    if (justExitedPip) settlingFromPip = true
    LaunchedEffect(justExitedPip) {
        if (justExitedPip) {
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
        val videoFocusRequester = remember { FocusRequester() }

        BackHandler {
            if (tvShowingChat) tvShowingChat = false else onBack()
        }
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
        // Re-focus the video surface every time chat closes, so a plain
        // Down press works again immediately without first navigating back
        // to it — there's nothing else on the video screen to focus instead.
        LaunchedEffect(tvShowingChat) {
            if (!tvShowingChat) runCatching { videoFocusRequester.requestFocus() }
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
                    .focusRequester(videoFocusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (!tvShowingChat &&
                            event.type == KeyEventType.KeyUp &&
                            event.key == Key.DirectionDown
                        ) {
                            tvShowingChat = true
                            true
                        } else {
                            false
                        }
                    }
            ) {
                if (state.player == com.cytube.mobile.net.MediaTypes.Player.WEB) {
                    WebCompatView(
                        baseUrl = Graph.BASE_URL,
                        channel = channel,
                        authCookie = Graph.auth(context).savedSession()?.authCookie,
                        modifier = Modifier.fillMaxSize(),
                        paused = audioOnlyState.value
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

    if (fullscreen) {
        FullscreenPlayer(
            state = state,
            controlsVisible = controlsVisible,
            onToggleControls = { controlsVisible = !controlsVisible },
            onExit = { fullscreen = false },
            chatOverlayOn = chatOverlayOn,
            nekoState = nekoState,
            playerContent = playerContent
        )
        return
    }

    CyTubeChannelTheme {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // Tapping the channel name reconciles with the server —
                    // playlist, player and leader/sync state — the same
                    // thing pull-to-refresh used to do. Pull-to-refresh is
                    // gone: it lived right on top of the video/chat content,
                    // one swipe away from anyone scrolling chat, and there
                    // was nothing stopping it from being fired over and over
                    // as fast as a finger could swipe. This is the same
                    // action moved somewhere deliberate to reach, backed by
                    // ChannelViewModel.refresh()'s own cooldown (see there)
                    // so repeated taps can't be turned into a request flood
                    // against the channel server.
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
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
                                if (state.muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
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
        // Always the same Column — swapping in and out of a plain Box when a
        // panel opened used to tear down and rebuild everything below (the
        // player, its ExoPlayer instance, sync state) because Compose saw it
        // as a structurally different subtree. That was the cause of Users
        // and Playlist appearing to "reset" the video: opening either panel
        // silently killed and restarted the player underneath.
        Column(Modifier.fillMaxSize().padding(padding)) {

            val webMode = state.player == com.cytube.mobile.net.MediaTypes.Player.WEB

            // The fullscreen toggle used to just sit on screen forever — a
            // bare white icon with nothing behind it reads as a stray white
            // square parked over the video. Fading it out after a few
            // idle seconds, and back in the instant the player is touched,
            // matches how the true-fullscreen controls below already work.
            var windowedControlsVisible by remember { mutableStateOf(true) }
            LaunchedEffect(windowedControlsVisible, webMode) {
                if (windowedControlsVisible && !webMode) {
                    delay(3_000)
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
            // above — the actual "hypnotic" part. The color alone drifting
            // smoothly reads as calm; a steady, gentle breathing rhythm
            // layered on it is what reads as hypnotic rather than just
            // static. Small amplitude (0.85-1.0) and a slow, even pace
            // (4s each way, eased rather than linear) so it stays felt more
            // than seen — this should never be something a viewer notices
            // as "the corner is pulsing", just something that makes the
            // glow feel alive rather than a flat wash of color. Only
            // animated at all while the glow is actually shown.
            val glowPulse = if (ambientGlowActive) {
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
                    WebCompatView(
                        baseUrl = Graph.BASE_URL,
                        channel = channel,
                        authCookie = Graph.auth(context).savedSession()?.authCookie,
                        modifier = Modifier.fillMaxSize(),
                        paused = audioOnlyState.value
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
                    // and the two used to sit right on top of each other.
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
 * WebView?" offers. These used to live only in the phone Scaffold path
 * below, which the isTv branch above never reaches (it returns before
 * getting there). That meant a TV user hitting a password-protected
 * channel had no way to ever enter it — no prompt, just a channel that
 * silently never joined — and the same for any native-playback failure:
 * no offer to fall back to WebView, just a black screen. Pulling these out
 * into one shared composable, called from both places, fixes that without
 * keeping two copies in sync by hand.
 */
@Composable
private fun PlaybackDialogs(
    state: ChannelUiState,
    vm: ChannelViewModel,
    passwordDraft: String,
    onPasswordDraftChange: (String) -> Unit,
    onBack: () -> Unit
) {
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

/**
 * A standard NavigationBar reserves ~80dp for what is really just three
 * text-only buttons — most of that height is padding a label never needs.
 * This slim custom row keeps the same 48dp minimum touch target (Android's
 * own accessibility floor) while giving noticeably more of a phone screen
 * back to chat. Fire TV never calls this at all (see the isTv branch above),
 * so it only ever affects the touch UI.
 */
@Composable
private fun PanelBar(userCount: Int, playlistCount: Int, pollOpen: Boolean, onOpen: (Panel) -> Unit) {
    // The stock NavigationBar this replaced pads itself for the system nav
    // bar automatically; a plain Surface doesn't, so on 3-button navigation
    // this row was sitting flush against the bottom edge and getting
    // covered by the triangle/circle/square buttons themselves. Applying
    // the same NavigationBarDefaults.windowInsets Material3's own component
    // uses internally reserves that space back.
    Surface(
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
        modifier = Modifier.windowInsetsPadding(NavigationBarDefaults.windowInsets)
    ) {
        Row(Modifier.fillMaxWidth().height(48.dp)) {
            PanelBarButton(
                if (playlistCount > 0) "Playlist ($playlistCount)" else "Playlist",
                Modifier.weight(1f)
            ) { onOpen(Panel.PLAYLIST) }
            PanelBarButton("Users ($userCount)", Modifier.weight(1f)) { onOpen(Panel.USERS) }
            // Only shown while a poll is actually running — nothing to vote on
            // otherwise, so the button would just open an empty panel.
            if (pollOpen) {
                PanelBarButton("Poll", Modifier.weight(1f)) { onOpen(Panel.POLL) }
            }
        }
    }
}

@Composable
private fun PanelBarButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = modifier.fillMaxHeight(),
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

@Composable
private fun FullscreenPlayer(
    state: ChannelUiState,
    controlsVisible: Boolean,
    onToggleControls: () -> Unit,
    onExit: () -> Unit,
    chatOverlayOn: Boolean,
    nekoState: NekoOverlayState,
    playerContent: @Composable () -> Unit
) {
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { onToggleControls() } }
    ) {
        playerContent()

        if (chatOverlayOn) {
            // Fills the whole screen itself (danmaku-style comments fly the
            // full width, on lanes spanning the full height), so no
            // alignment/sizing to set here beyond the default. Independent
            // of controlsVisible on purpose: once turned on, the overlay is
            // meant to stay up while you watch, Neko/mpv-style — not blink
            // out the moment the exit button/title fade away on that same
            // idle timer. `chatOverlayOn`/`nekoState` are hoisted up to
            // ChannelScreen and shared with the windowed player's own call
            // site — this no longer has (or needs) an on/off control of its
            // own; that lives solely in the TopAppBar, above the video.
            NekoChatOverlay(
                messages = state.messages,
                showEmotes = state.showEmotes,
                emotes = state.emotes,
                state = nekoState
            )
        }

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
