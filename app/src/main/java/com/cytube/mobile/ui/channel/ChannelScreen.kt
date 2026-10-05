package com.cytube.mobile.ui.channel

import androidx.compose.runtime.withFrameNanos
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.CompositionLocalProvider
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.outlined.Lightbulb as LightbulbOutlined
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Poll
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import com.cytube.mobile.player.SubtitleOptions
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
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
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
import kotlinx.coroutines.flow.first

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

/** The windowed player's glow as it's drawn; see [rememberAmbientGlow]. */
@Stable
private class AmbientGlow(initial: Color) {
    var color by mutableStateOf(initial)
    var pulse by mutableFloatStateOf(1f)
}

/**
 * The glow behind the windowed player: [target] faded in over
 * GLOW_FADE_MS, times a slow brightness pulse (0.85 to 1 and back, 4s each
 * way) while [pulsing]. Moved on about [stepsPerSecond] times a second
 * rather than every screen frame: it's a soft gradient that changes slowly, so it
 * looks the same, but animated every frame it kept the whole screen
 * redrawing 60-120 times a second for as long as a video played. Still
 * while there's nothing to fade or pulse, and not running at all unless
 * [active]. Read [AmbientGlow.color] and [AmbientGlow.pulse] while
 * drawing, so each step redraws the glow alone.
 */
@Composable
private fun rememberAmbientGlow(
    target: Color,
    pulsing: Boolean,
    active: Boolean,
    /** Fewer while it only tints lights down's dark wash, where each step's
     *  change is too faint to see. */
    stepsPerSecond: Int = GLOW_STEPS_PER_SECOND
): AmbientGlow {
    // Starts at [target], as the windowed layout comes back from
    // fullscreen with a color already known: shown at once, not faded in.
    val glow = remember { AmbientGlow(target) }
    val currentTarget by rememberUpdatedState(target)
    val currentPulsing by rememberUpdatedState(pulsing)
    val currentStepsPerSecond by rememberUpdatedState(stepsPerSecond)
    LaunchedEffect(glow, active) {
        if (!active) return@LaunchedEffect
        var from = glow.color
        var to = glow.color
        var fadeStartMs = 0L
        var fading = false
        var pulseStartMs = 0L
        var wasPulsing = false
        while (true) {
            val now = withFrameMillis { it }
            if (currentTarget != to) {
                from = glow.color
                to = currentTarget
                fadeStartMs = now
                fading = true
            }
            if (fading) {
                val t = ((now - fadeStartMs).toFloat() / GLOW_FADE_MS).coerceIn(0f, 1f)
                glow.color = lerp(from, to, FastOutSlowInEasing.transform(t))
                if (t >= 1f) fading = false
            }
            if (currentPulsing) {
                if (!wasPulsing) pulseStartMs = now
                // Up for one half, back down for the other.
                val phase = ((now - pulseStartMs) % (2 * GLOW_PULSE_MS)).toFloat() / GLOW_PULSE_MS
                val t = if (phase <= 1f) phase else 2f - phase
                glow.pulse = 0.85f + 0.15f * FastOutSlowInEasing.transform(t)
            } else {
                glow.pulse = 1f
            }
            wasPulsing = currentPulsing
            if (fading || currentPulsing) {
                delay(1_000L / currentStepsPerSecond)
            } else {
                snapshotFlow { currentTarget != to || currentPulsing }.first { it }
            }
        }
    }
    return glow
}

/** How dark lights down makes everything around the video (black at this
 *  opacity), how strongly the video's colour tints it, how long the fades
 *  take (going down, coming up), and how long a tap around the video
 *  brings the lights up for. */
private const val LIGHTS_DIM = 0.85f
private const val LIGHTS_TINT = 0.16f
private const val LIGHTS_DOWN_MS = 350
private const val LIGHTS_UP_MS = 220
/** A touch around the video: how much of the dark it lifts, and its fades
 *  up and back down, sine-shaped (gentler at both ends). */
private const val LIGHTS_PEEK_LIFT = 0.5f
/** The navigation bar's background while the lights are down: the dark
 *  layer's black, at its opacity (LIGHTS_DIM). */
private const val LIGHTS_NAV_BAR_SCRIM = 0xD9000000.toInt()
/** The navigation bar's background otherwise: the same half-see-through
 *  dark grey Android gives it in dark mode. */
private const val NAV_BAR_SCRIM = 0x801B1B1B.toInt()
private const val LIGHTS_PEEK_UP_MS = 450
private const val LIGHTS_PEEK_DOWN_MS = 600
private val LIGHTS_PEEK_EASING = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)
private const val LIGHTS_PEEK_MS = 4_000L
/** How often the dark wash follows the video's colour while the lights are
 *  down: its changes are tiny (a 16% tint under 85% black), so a few steps
 *  a second look the same as the glow's 20 and cost far less. */
private const val LIGHTS_TINT_STEPS_PER_SECOND = 4

private const val GLOW_FADE_MS = 2_800L
private const val GLOW_PULSE_MS = 4_000L
private const val GLOW_STEPS_PER_SECOND = 20

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
    // Lights down: everything around the windowed video dimmed (see
    // LIGHTS_DIM below). For this visit only: the next channel opens with
    // the lights up.
    var lightsDown by rememberSaveable { mutableStateOf(false) }
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
    // Lights down tints the dimmed screen with the video's colour, so its
    // frames are sampled then too, glow setting or not.
    val lightsDownState = rememberUpdatedState(lightsDown)

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
                // (or the lights are down, for their tint) and can be seen:
                // never on TV, which has no windowed layout
                // to show it in (and whose setting is hidden, so it stays at
                // its default of on), and not in fullscreen or PiP, where
                // it isn't drawn — sampling there was a frame grab every few
                // seconds for nothing.
                onFrameSnapshot = if ((ambientGlowEnabledState.value || lightsDownState.value) && !isTv &&
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

    // Whether the navigation bar is dimmed with the lights; see below.
    val navBarDimmedState = remember { mutableStateOf(false) }
    // Android's own status and navigation bars are drawn by the system, over
    // the app, and by default follow the phone's light/dark setting: on a
    // phone in light mode that meant dark, hard-to-read clock and icons at
    // the top and a light grey three-button bar at the bottom, against this
    // always-dark screen. Here they're always dark to match (light icons),
    // and back to the app's usual styling when this screen goes.
    //
    // The dark layer can't reach the navigation bar either, so while the
    // lights are fully down it gets the layer's black at its opacity, with
    // dark buttons, so it's dimmed too. Back still works the same; only how
    // the bars look changes (set by the windowed layout below; fullscreen
    // hides the bars). Re-applied after every configuration change, a
    // rotation included: androidx's enableEdgeToEdge puts the app's default
    // styling back on each one (MainActivity's first call leaves a listener
    // for that), so going fullscreen and back brought the light bars back.
    val componentActivity = activity as? ComponentActivity
    LaunchedEffect(componentActivity, configuration) {
        snapshotFlow { navBarDimmedState.value }.collect { navBarDimmed ->
            componentActivity?.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                navigationBarStyle = if (navBarDimmed) {
                    SystemBarStyle.light(LIGHTS_NAV_BAR_SCRIM, LIGHTS_NAV_BAR_SCRIM)
                } else {
                    SystemBarStyle.dark(NAV_BAR_SCRIM)
                }
            )
        }
    }
    DisposableEffect(componentActivity) {
        onDispose { componentActivity?.enableEdgeToEdge() }
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
                    onSelectSubtitle = vm::selectSubtitle,
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

    val webMode = state.player == com.cytube.mobile.net.MediaTypes.Player.WEB
    // Video the windowed layout can dim around: not Compatibility View,
    // which is the whole page rather than a video.
    val canDimAround = !webMode && state.media != null

    // Lights down stays down whatever playback does (pausing, a new video,
    // buffering, a dropped connection): only a touch around the video brings
    // the lights partly up, for a few seconds (peeking), or the bulb button turning
    // it off. The one exception is typing, which a touch starts: with the
    // keyboard open, the message box being written in stays lit.
    var peeking by remember { mutableStateOf(false) }
    var peeks by remember { mutableIntStateOf(0) }
    LaunchedEffect(peeks) {
        if (peeks == 0) return@LaunchedEffect
        peeking = true
        delay(LIGHTS_PEEK_MS)
        peeking = false
    }
    val lightsAreDown = lightsDown && canDimAround && !peeking && !WindowInsets.isImeVisible
    val currentLightsAreDown by rememberUpdatedState(lightsAreDown)
    // A touch around the video only lifts the dark part of the way
    // (LIGHTS_PEEK_LIFT), and that fade, and the one back down after it, is
    // a little slower and smoother than the bulb button's.
    var fadeFromTouch by remember { mutableStateOf(false) }
    val dimTarget = when {
        !lightsDown || !canDimAround || WindowInsets.isImeVisible -> 0f
        peeking -> LIGHTS_DIM * (1f - LIGHTS_PEEK_LIFT)
        else -> LIGHTS_DIM
    }
    val navBarDimmedNow = dimTarget == LIGHTS_DIM
    SideEffect { navBarDimmedState.value = navBarDimmedNow }
    // One short fade each way, then still: nothing animates while it sits.
    val dimAnimation = animateFloatAsState(
        targetValue = dimTarget,
        animationSpec = when {
            fadeFromTouch -> tween(
                if (peeking) LIGHTS_PEEK_UP_MS else LIGHTS_PEEK_DOWN_MS,
                easing = LIGHTS_PEEK_EASING
            )
            // The bulb button: down eases in and out; up starts at full
            // speed, so the tap is answered at once.
            dimTarget == LIGHTS_DIM -> tween(LIGHTS_DOWN_MS, easing = FastOutSlowInEasing)
            else -> tween(LIGHTS_UP_MS, easing = LinearOutSlowInEasing)
        },
        label = "lightsDown"
    )
    // Where the video is, in the Box around the Scaffold: left undimmed, and
    // taps there go to the player as usual.
    var videoInRoot by remember { mutableStateOf(Rect.Zero) }
    var screenInRoot by remember { mutableStateOf(Offset.Zero) }

    // Reserved as soon as this item is eligible at all (setting on,
    // not Compatibility View or an embed) rather than waiting for a
    // color — that way the margin never pops in as a sudden layout
    // shift once the snapshot lands. Before a color exists (or when
    // the setting is off) it's just 16dp of ordinary background,
    // indistinguishable from normal spacing.
    val ambientGlowActive = state.ambientGlowEnabled && !webMode &&
        state.player != com.cytube.mobile.net.MediaTypes.Player.EMBED

    // Crossfades to each new color over most of the gap between
    // samples (see AMBIENT_RESAMPLE_INTERVAL_MS in PlayerSurface:
    // samples land every 3s, the fade takes 2.8s), so the hue is
    // nearly always gently in motion. Combined with the sample
    // blending in onFrameSnapshot above (which keeps any one step
    // small), the color drifts slowly instead of visibly "updating".
    // On top of that, while playing with a color, a slow brightness
    // pulse: the "hypnotic" part. See rememberAmbientGlow for how
    // it's kept cheap. Also the colour lights down tints the dimmed
    // screen with, so it runs then too; the pulse doesn't, as the glow
    // itself is off while the lights are down.
    val isGlowVisuallyActive = ambientGlowActive && state.playing &&
        ambientColor != null && !lightsAreDown
    val glow = rememberAmbientGlow(
        target = ambientColor ?: Color.Transparent,
        pulsing = isGlowVisuallyActive,
        active = ambientGlowActive || (lightsDown && canDimAround),
        stepsPerSecond = if (lightsAreDown) LIGHTS_TINT_STEPS_PER_SECOND else GLOW_STEPS_PER_SECOND
    )

    CyTubeChannelTheme {
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { screenInRoot = it.positionInRoot() }
            // Touching the screen anywhere around the video brings the
            // lights partly up for a few seconds, and does whatever it would anyway
            // (everything stays usable while dimmed). Touching it again while
            // they're up, scrolling chat say, keeps them up a while longer.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (!currentLightsAreDown && !peeking) return@awaitEachGesture
                    if (videoInRoot.translate(-screenInRoot).contains(down.position)) return@awaitEachGesture
                    fadeFromTouch = true
                    peeks++
                }
            }
    ) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                windowInsets = barInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                // The room's original top-bar grey, over the Slate page. The
                // action icons are grey so the channel name leads; the ones
                // that can be "on" (the favorite star) show it by their shape.
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
                    // Compact buttons: Material's own small size (40dp) instead
                    // of the usual 48dp touch box, so the icons sit closer
                    // together and leave the channel name more room, while
                    // each is still easy to hit on its own.
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides TOP_BAR_BUTTON_SIZE) {
                        // Leftmost, with the CC button: the two that only show
                        // up once the channel or the video says so, so their
                        // arrival moves nothing else along. Compatibility View
                        // drops this connection (the page has its own), and a
                        // vote while disconnected goes nowhere.
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
                        // Only when the playing item has subtitles: a custom
                        // manifest's textTracks, Google Drive's, YouTube's
                        // captions, or ones inside the stream itself. Left of
                        // the mute toggle.
                        if (state.subtitles.available &&
                            state.player != com.cytube.mobile.net.MediaTypes.Player.WEB
                        ) {
                            SubtitleButton(options = state.subtitles, onSelect = vm::selectSubtitle)
                        }
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
                        // favourite star further along — filled when on,
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
                        // Lights down, left of the star: filled while on,
                        // like the star. There from the moment the channel
                        // opens (it dims once there is a video), so it doesn't
                        // pop in; not in Compatibility View, which has no
                        // video of its own to dim around.
                        if (!webMode) {
                            IconButton(onClick = {
                                lightsDown = !lightsDown
                                // The touch on this button also counted as
                                // bringing the lights up for a few seconds
                                // (see the Box around the Scaffold); cancelled,
                                // so they fade down or up at once, every tap.
                                peeks = 0
                                fadeFromTouch = false
                                peeking = false
                            }) {
                                Icon(
                                    if (lightsDown) Icons.Filled.Lightbulb else Icons.Outlined.LightbulbOutlined,
                                    contentDescription = if (lightsDown) "Lights up" else "Lights down"
                                )
                            }
                        }
                        IconButton(onClick = vm::toggleFavourite) {
                            Icon(
                                if (state.isFavourite) Icons.Default.Star else Icons.Outlined.StarBorder,
                                contentDescription = "Favorite"
                            )
                        }
                        IconButton(onClick = { showModeSheet = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Channel options")
                        }
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

            Box(
                Modifier.fillMaxWidth()
                    .then(
                        if (ambientGlowActive) Modifier.drawBehind {
                            // Fades out as the lights go down: it would
                            // light up the very area being darkened.
                            val lit = 1f - dimAnimation.value / LIGHTS_DIM
                            val color = glow.color.let { it.copy(alpha = it.alpha * lit) }
                            if (color.alpha <= 0f) return@drawBehind
                            val pulse = glow.pulse
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
                                    0.93f to color.copy(alpha = color.alpha * 0.55f * pulse),
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
                    .onGloballyPositioned { videoInRoot = it.boundsInRoot() }
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
                    // The Niconico comments over this video are drawn by
                    // the Box around the Scaffold, above lights down's dark
                    // layer: see there.
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
            // is the panel people actually keep open. Its animated emotes hold
            // still while the lights are down: behind the dark layer, moving
            // them would only cost battery.
            CompositionLocalProvider(LocalEmotesStill provides lightsAreDown) {
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
                users = state.users,
                nameColors = state.nameColors,
                modifier = Modifier.weight(1f)
            )
            }
        }
    }
    // Lights down's dark layer, over everything but the video: black, with
    // a faint wash of the video's own colour (as if the screen lit the
    // room). Black rather than grey, so an OLED screen switches those pixels
    // off. The status and navigation bars' backgrounds are the app's, so
    // they dim too; their icons are the system's.
    Spacer(
        Modifier.fillMaxSize().drawBehind {
            val dim = dimAnimation.value
            if (dim <= 0f) return@drawBehind
            val tint = glow.color
            val wash = lerp(Color.Black, tint.copy(alpha = 1f), LIGHTS_TINT * tint.alpha)
                .copy(alpha = dim)
            val video = videoInRoot.translate(-screenInRoot)
            // Not laid out yet (a frame, coming back from fullscreen):
            // nothing rather than dimming the video with everything.
            if (video.isEmpty) return@drawBehind
            // Above, below, left and right of the video.
            drawRect(wash, Offset.Zero, Size(size.width, video.top))
            drawRect(wash, Offset(0f, video.bottom), Size(size.width, size.height - video.bottom))
            drawRect(wash, Offset(0f, video.top), Size(video.left, video.height))
            drawRect(wash, Offset(video.right, video.top), Size(size.width - video.right, video.height))
        }
    )
    // The Niconico comments flying across the video, placed exactly over it.
    // Here rather than inside the video's own Box so they're drawn above the
    // dark layer: a comment in the bottom lane (a tall emote, say) runs a
    // little past the video's edge, and that part was being dimmed. Same
    // switch and remembered comment state as the fullscreen player's (see
    // chatOverlayOn/nekoState above).
    if (chatOverlayOn && !webMode) {
        Box(
            Modifier.layout { measurable, constraints ->
                val video = videoInRoot.translate(-screenInRoot)
                if (video.isEmpty) return@layout layout(0, 0) {}
                val placeable = measurable.measure(
                    Constraints.fixed(video.width.roundToInt(), video.height.roundToInt())
                )
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(video.left.roundToInt(), video.top.roundToInt())
                }
            }
        ) {
            NekoChatOverlay(
                messages = state.messages,
                showEmotes = state.showEmotes,
                emotes = state.emotes,
                state = nekoState
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
                    nameColors = state.nameColors,
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

/** Touch size of the top bar's action buttons: Material's compact size,
 *  down from the usual 48dp (see the TopAppBar actions). */
private val TOP_BAR_BUTTON_SIZE = 40.dp

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
 * Subtitles, in the top bar left of the mute toggle, only when the item has
 * some. Filled when showing, outlined when not, like the star and the Nico
 * square. With one track a tap switches it on and off; with several, a tap
 * opens a menu to pick one (or Off). The choice carries on to later items.
 */
@Composable
private fun SubtitleButton(options: SubtitleOptions, onSelect: (Int?, String) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    // The list the menu was opened on, so what it shows and what a pick
    // applies to are the same item's, even as the next item loads; and the
    // menu closes when that happens rather than switching under the finger.
    var menuOptions by remember { mutableStateOf(options) }
    LaunchedEffect(options.key) { if (options.key != menuOptions.key) menuOpen = false }
    Box {
        IconButton(
            onClick = {
                when {
                    options.names.size > 1 -> { menuOptions = options; menuOpen = true }
                    options.showing -> onSelect(null, options.key)
                    else -> onSelect(0, options.key)
                }
            },
            modifier = Modifier.semantics {
                contentDescription = when {
                    options.names.size > 1 -> "Subtitles"
                    options.showing -> "Turn off subtitles"
                    else -> "Turn on subtitles"
                }
            }
        ) {
            Icon(
                if (options.showing) Icons.Default.ClosedCaption else Icons.Outlined.ClosedCaption,
                contentDescription = null
            )
        }
        SubtitleMenu(
            expanded = menuOpen,
            options = menuOptions,
            onDismiss = { menuOpen = false },
            onSelect = onSelect
        )
    }
}

/**
 * The CC button's track menu: Off, then each track, the one showing
 * ticked. [options] is the list the menu was opened on (see SubtitleButton).
 * With [focusCurrent] (TV), the ticked entry takes focus as it opens, so
 * the remote starts from what's showing rather than from nothing, and the
 * focused entry is clearly highlighted: Material's own focus tint is too
 * faint to follow from across a room.
 */
@Composable
private fun SubtitleMenu(
    expanded: Boolean,
    options: SubtitleOptions,
    onDismiss: () -> Unit,
    onSelect: (Int?, String) -> Unit,
    focusCurrent: Boolean = false
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        val current = remember { FocusRequester() }
        // -1 for Off, else the track's index.
        var focusedEntry by remember { mutableStateOf<Int?>(null) }
        val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
        fun entry(index: Int, isCurrent: Boolean): Modifier {
            val base = if (isCurrent) Modifier.focusRequester(current) else Modifier
            if (!focusCurrent) return base
            return base
                .onFocusChanged { if (it.isFocused) focusedEntry = index else if (focusedEntry == index) focusedEntry = null }
                .background(if (focusedEntry == index) highlight else Color.Transparent)
        }
        if (focusCurrent) {
            LaunchedEffect(Unit) {
                // A frame for the popup to be laid out and focusable.
                withFrameNanos { }
                runCatching { current.requestFocus() }
            }
        }
        DropdownMenuItem(
            text = { Text("Off") },
            trailingIcon = { if (!options.showing) Icon(Icons.Default.Check, contentDescription = null) },
            onClick = { onDismiss(); onSelect(null, options.key) },
            modifier = entry(-1, isCurrent = !options.showing)
        )
        options.names.forEachIndexed { i, name ->
            DropdownMenuItem(
                text = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = { if (options.selected == i) Icon(Icons.Default.Check, contentDescription = null) },
                onClick = { onDismiss(); onSelect(i, options.key) },
                modifier = entry(i, isCurrent = options.selected == i)
            )
        }
    }
}

/**
 * Vote to skip, leftmost in the top bar.
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
                PanelBarButton(
                    "Playlist", Icons.Outlined.VideoLibrary,
                    if (playlistCount > 0) playlistCount else null, Modifier.weight(1f)
                ) {
                    onOpen(Panel.PLAYLIST)
                }
                PanelBarButton("Users", Icons.Outlined.Group, userCount, Modifier.weight(1f)) {
                    onOpen(Panel.USERS)
                }
                // Only shown while there's a poll — running, or just closed
                // with its final results (until the next one or it's
                // dismissed); the same grey as the other two.
                if (pollOpen) {
                    PanelBarButton(
                        if (pollClosed) "Poll results" else "Poll",
                        Icons.Outlined.Poll,
                        count = null,
                        modifier = Modifier.weight(1f)
                    ) { onOpen(Panel.POLL) }
                }
            }
        }
    }
}

/** A button along the bottom: an icon and its name, so it reads as
 *  something to tap rather than a caption, with its count a step fainter. */
@Composable
private fun PanelBarButton(
    label: String,
    icon: ImageVector,
    count: Int?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val labelColor = colors.onSurface
    val countColor = colors.onSurfaceVariant
    TextButton(
        onClick = onClick,
        modifier = modifier.fillMaxHeight(),
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        Icon(icon, contentDescription = null, tint = countColor, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = labelColor)) { append(label) }
                if (count != null) {
                    withStyle(SpanStyle(color = countColor)) { append("  $count") }
                }
            },
            style = MaterialTheme.typography.labelLarge,
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
 *
 * When the video has subtitles, the CC button sits left of Nico, as on the
 * phone: the two form the top row, Left/Right move between them, and Up
 * and Down behave the same from either (see TvSubtitleButton).
 */
@Composable
private fun TvChatView(
    state: ChannelUiState,
    onSendChat: (String) -> Unit,
    chatOverlayOn: Boolean,
    onToggleChatOverlay: () -> Unit,
    onSelectSubtitle: (Int?, String) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val nicoFocusRequester = remember { FocusRequester() }
    val ccFocusRequester = remember { FocusRequester() }
    val chatInputFocusRequester = remember { FocusRequester() }
    var nicoFocused by remember { mutableStateOf(false) }
    var ccFocused by remember { mutableStateOf(false) }
    val ccAvailable = state.subtitles.available &&
        state.player != com.cytube.mobile.net.MediaTypes.Player.WEB
    // Whether CC is where focus is (or its menu, opened from it): set when
    // it gains focus, cleared only when Nico or the chat bar does, so that
    // focus moving into the menu, or the button's removal clearing focus,
    // doesn't lose track of it.
    //
    // The next video having no subtitles takes the CC button away. If it
    // had focus (or its menu did), focus goes to its neighbour Nico rather
    // than to nothing, which would leave the remote's next press dead.
    LaunchedEffect(ccAvailable) {
        if (!ccAvailable && ccFocused) {
            ccFocused = false
            runCatching { nicoFocusRequester.requestFocus() }
        }
    }
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
                        // The top row (Nico, or CC beside it) leaves; from
                        // the chat bar, Up goes to Nico first.
                        if (nicoFocused || ccFocused) onExit() else runCatching { nicoFocusRequester.requestFocus() }
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

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
            if (ccAvailable) {
                TvSubtitleButton(
                    options = state.subtitles,
                    onSelect = onSelectSubtitle,
                    focusRequester = ccFocusRequester,
                    onFocused = { ccFocused = it },
                    onRight = { runCatching { nicoFocusRequester.requestFocus() } },
                    onDown = { runCatching { chatInputFocusRequester.requestFocus() } }
                )
            }
            // Explicit, not LocalContentColor — this square being legible in
            // both its on/off states is the whole point of it, so it doesn't
            // depend on ambient content color resolving correctly.
            val squareColor = MaterialTheme.colorScheme.onBackground
            Box(
                Modifier
                    .focusRequester(nicoFocusRequester)
                    .onFocusChanged {
                        nicoFocused = it.isFocused
                        if (it.isFocused) ccFocused = false
                    }
                    .focusable()
                    // Left to CC when it's there; Right has nowhere to go.
                    // Both phases taken, so the default focus search (which
                    // runs on KeyDown) never picks something else.
                    .onPreviewKeyEvent { event ->
                        when (event.key) {
                            Key.DirectionLeft -> {
                                if (event.type == KeyEventType.KeyUp && ccAvailable) {
                                    runCatching { ccFocusRequester.requestFocus() }
                                }
                                true
                            }
                            Key.DirectionRight -> true
                            else -> false
                        }
                    }
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
            users = state.users,
            nameColors = state.nameColors,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            // No D-pad interaction with individual messages on TV — the
            // list stays pinned to the latest message and is never a focus
            // stop, so it can't get in the way of Nico <-> chat bar <-> video.
            messagesFocusable = false,
            inputFieldModifier = Modifier
                .focusRequester(chatInputFocusRequester)
                .onFocusChanged { if (it.isFocused) ccFocused = false },
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
 * TV's CC button, in TvChatView's top row left of Nico, drawn and focused
 * the same way as Nico (a primary-coloured outline when focused). OK turns
 * the one track on or off, or with several opens the same menu as the
 * phone (SubtitleMenu), with what's showing focused; picking from it or
 * Back closes it and puts focus back here, where it started.
 *
 * Right goes to Nico and Down to the chat bar ([onRight], [onDown]); Left
 * has nowhere to go; Up is TvChatView's (back to the video). Moves happen
 * on KeyUp and both phases are taken, so Compose's own focus search (on
 * KeyDown) never lands on anything else, as with Nico.
 */
@Composable
private fun TvSubtitleButton(
    options: SubtitleOptions,
    onSelect: (Int?, String) -> Unit,
    focusRequester: FocusRequester,
    onFocused: (Boolean) -> Unit,
    onRight: () -> Unit,
    onDown: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var menuOptions by remember { mutableStateOf(options) }
    // As on the phone: the menu closes when the item changes under it.
    LaunchedEffect(options.key) { if (options.key != menuOptions.key) menuOpen = false }
    // Focus back here when the menu closes, whichever way: a pick, Back,
    // or the item changing.
    var menuWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(menuOpen) {
        if (menuOpen) menuWasOpen = true
        else if (menuWasOpen) {
            menuWasOpen = false
            withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
        }
    }
    Box {
        Box(
            Modifier
                .focusRequester(focusRequester)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused(true)
                }
                .focusable()
                .onPreviewKeyEvent { event ->
                    val up = event.type == KeyEventType.KeyUp
                    when (event.key) {
                        Key.DirectionRight -> { if (up) onRight(); true }
                        Key.DirectionDown -> { if (up) onDown(); true }
                        Key.DirectionLeft -> true
                        Key.Enter, Key.DirectionCenter, Key.NumPadEnter -> {
                            if (up) when {
                                options.names.size > 1 -> { menuOptions = options; menuOpen = true }
                                options.showing -> onSelect(null, options.key)
                                else -> onSelect(0, options.key)
                            }
                            true
                        }
                        else -> false
                    }
                }
                .border(2.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent)
                .padding(4.dp)
                .semantics {
                    contentDescription = when {
                        options.names.size > 1 -> "Subtitles"
                        options.showing -> "Turn off subtitles"
                        else -> "Turn on subtitles"
                    }
                }
        ) {
            Icon(
                if (options.showing) Icons.Default.ClosedCaption else Icons.Outlined.ClosedCaption,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(24.dp)
            )
        }
        SubtitleMenu(
            expanded = menuOpen,
            options = menuOptions,
            onDismiss = { menuOpen = false },
            onSelect = onSelect,
            focusCurrent = true
        )
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
