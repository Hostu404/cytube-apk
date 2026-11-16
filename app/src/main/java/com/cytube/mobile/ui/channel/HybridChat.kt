package com.cytube.mobile.ui.channel

import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import com.cytube.mobile.net.ChatMessage
import kotlinx.collections.immutable.PersistentList
import kotlin.math.roundToInt

/**
 * Hybrid chat (Hybrid, on the chat button in the channel's top bar, for that
 * visit as Nico is, and never on with it; phone only): in fullscreen, the
 * channel's own chat down the whole of one side of the video (the right, or
 * the left, as Settings has it), and fully usable (reading, scrolling back,
 * typing, emotes, PMs), with its message box in the middle of the bottom edge
 * (see ChatPanel's overVideo). While it's showing:
 *  - the video itself can't be touched: a tap on it brings the fullscreen
 *    controls (exit, title, Hide chat and the message box) up or down, and
 *    nothing reaches the player, its own controls or an embed's page;
 *  - Nico comments don't fly (FullscreenPlayer leaves them out);
 *  - links in messages are plain text (ChatPanel's overVideo);
 *  - a message that mentions you has a blue wash from the screen's edge,
 *    glowing brighter for a moment as it comes in;
 *  - with a physical keyboard, typing with nothing being typed in goes
 *    straight into the message box.
 * Hide chat (HybridChatToggle, in the controls' top row, on chat's side), or
 * Back, puts it away; Show chat, in the same place, brings it back, with how
 * many messages came in meanwhile (lit up if one mentioned you, and saying
 * so if a PM came). Chat slides in and out at its side, and is never shown in
 * picture-in-picture. Settings has its side, its opacity, its text size and
 * Fade old messages: each message fading back 15 seconds after it came in,
 * and scrolling the chat bringing them all back until 10 seconds after it
 * stops.
 *
 * Nothing sits behind it: the messages are drawn straight over the video.
 * Nothing here runs while chat just sits there: no animation but the slides
 * and fades, no layers, and no timers but the fading's, with it on.
 */
@Composable
internal fun HybridChatLayer(
    visible: Boolean,
    /** Gone at once rather than sliding out (into picture-in-picture). */
    instant: Boolean,
    /** Chat's down the right side of the screen (Settings), so it slides in
     *  and out there; else at the left. */
    onRight: Boolean,
    onTapVideo: () -> Unit,
    /** The chat itself (a ChatPanel), filling what it's given. */
    chat: @Composable (Modifier) -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = if (onRight) SLIDE_IN_FROM_RIGHT else SLIDE_IN_FROM_LEFT,
        exit = when {
            instant -> ExitTransition.None
            onRight -> SLIDE_OUT_TO_RIGHT
            else -> SLIDE_OUT_TO_LEFT
        }
    ) {
        val focusManager = LocalFocusManager.current
        val tapVideo by rememberUpdatedState(onTapVideo)
        Box(Modifier.fillMaxSize()) {
            // Over the whole screen, under the chat: a tap that isn't on chat
            // itself is a tap on the video, bringing the fullscreen controls
            // up or down as it always does (and putting the keyboard away if
            // it's up). Nothing gets through to the player underneath, which
            // is never touched while chat's up.
            Spacer(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures {
                        focusManager.clearFocus()
                        tapVideo()
                    }
                }
            )
            // The chat lays itself out over the screen (ChatPanel's
            // overVideo): taps beside its messages are passed back to the
            // video's own (ChannelScreen). A camera cutout at either end in
            // landscape mustn't sit over the messages.
            chat(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)))
        }
    }
}

/** Hybrid chat's column: [COLUMN_FRACTION] of the width, never under
 *  [MIN_COLUMN_WIDTH] (the minimum goes first, so fillMaxWidth keeps to
 *  it). The fullscreen controls' top row stops short of the same. */
internal fun Modifier.hybridChatWidth(): Modifier =
    widthIn(min = MIN_COLUMN_WIDTH).fillMaxWidth(COLUMN_FRACTION)

/** Hybrid chat's message box, in the middle of the bottom edge: as wide as
 *  fits between the chat's column and the same width kept clear on the left,
 *  so it never reaches a message and the messages can run all the way down;
 *  at most [BOX_MAX_FRACTION] of the width, and never under [MIN_BOX_WIDTH]. */
internal fun Modifier.hybridBoxWidth(): Modifier = layout { measurable, constraints ->
    val width = if (constraints.hasBoundedWidth) {
        val full = constraints.maxWidth
        (full - 2 * (hybridColumnPx(full) + BOX_GAP.roundToPx()))
            .coerceAtMost((full * BOX_MAX_FRACTION).roundToInt())
            .coerceAtLeast(MIN_BOX_WIDTH.roundToPx())
            .coerceAtMost(full)
    } else {
        null
    }
    val placeable = measurable.measure(
        if (width == null) constraints else constraints.copy(minWidth = width, maxWidth = width)
    )
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/** Keeps what it's on (the fullscreen controls' top row) clear of hybrid
 *  chat's column, at its end ([atEnd]) or its start: the column's width
 *  (hybridChatWidth) is left there, worked out from the whole width as the
 *  column's own is, so the two meet exactly. */
internal fun Modifier.clearOfHybridChat(atEnd: Boolean): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val column = hybridColumnPx(constraints.maxWidth)
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = (constraints.minWidth - column).coerceAtLeast(0),
            maxWidth = constraints.maxWidth - column
        )
    )
    // Relative: the start's the right in a right-to-left language.
    layout(placeable.width + column, placeable.height) {
        placeable.placeRelative(if (atEnd) 0 else column, 0)
    }
}

/** Hybrid chat's column in pixels, out of [full], the width it's laid out
 *  in: as hybridChatWidth makes it. */
internal fun Density.hybridColumnPx(full: Int): Int =
    maxOf(MIN_COLUMN_WIDTH.roundToPx(), (full * COLUMN_FRACTION).roundToInt()).coerceAtMost(full)

/**
 * Hybrid chat's Hide chat; or, put away, Show chat, with how many people's
 * messages have come in since [since] (a ChatMessage.seq): lit up in blue if
 * one of them mentions you, and saying "PM" (in amber, unless a mention's
 * lit it already) if a private message came. It goes in the fullscreen
 * controls' top row (FullscreenPlayer), at chat's end of it: up with them,
 * beside the chat and never over it, and the same quiet look either way
 * unless it's lit.
 */
@Composable
internal fun HybridChatToggle(
    showing: Boolean,
    messages: State<PersistentList<ChatMessage>>,
    since: Long,
    /** Our own name: who a mention mentions, and whose own messages and
     *  PMs aren't news. */
    localName: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (showing) {
        HybridPill(Icons.Default.VisibilityOff, "Hide chat", onClick, modifier)
    } else {
        val unread by remember(messages, since) { derivedStateOf { unreadSince(messages.value, since) } }
        val mention = remember(localName) { localName?.takeIf { it.isNotEmpty() }?.let(::buildMentionRegex) }
        val alerts by remember(messages, since, localName, mention) {
            derivedStateOf { alertsSince(messages.value, since, localName, mention) }
        }
        val count = when {
            unread == 0 -> "Show chat"
            unread > 99 -> "Show chat · 99+"
            else -> "Show chat · $unread"
        }
        HybridPill(
            Icons.Default.Visibility,
            if (alerts.pm) "$count · PM" else count,
            onClick,
            modifier,
            accent = when {
                alerts.mentioned -> MENTION_ALERT
                alerts.pm -> PM_ALERT
                else -> null
            }
        )
    }
}

/** How many people's messages in [messages] came after [since] (a
 *  ChatMessage.seq): from the newest back to the first one already seen,
 *  not the whole list each time, as they're in seq order. */
internal fun unreadSince(messages: List<ChatMessage>, since: Long): Int {
    var n = 0
    for (i in messages.indices.reversed()) {
        val m = messages[i]
        if (m.seq <= since) break
        if (!m.isServerMessage) n++
    }
    return n
}

/** What's come in for you, for Show chat: someone mentioning you, and a
 *  private message. */
internal data class ChatAlerts(val mentioned: Boolean, val pm: Boolean)

/** Whether, of [messages] after [since] (a ChatMessage.seq), one mentions
 *  you ([mention], your name, as chat highlights it: in what the message
 *  says, not inside a link's address), and whether one's a PM: someone
 *  else's either way ([localName]'s are yours), not the server's. From the
 *  newest back, as unreadSince. */
internal fun alertsSince(messages: List<ChatMessage>, since: Long, localName: String?, mention: Regex?): ChatAlerts {
    var mentioned = false
    var pm = false
    for (i in messages.indices.reversed()) {
        val m = messages[i]
        if (m.seq <= since) break
        if (m.isServerMessage || (localName != null && m.username.equals(localName, ignoreCase = true))) continue
        if (m.isPm) {
            pm = true
        } else if (!mentioned && mention != null && mention.containsMatchIn(m.html.replace(HTML_TAG, " "))) {
            mentioned = true
        }
        if (mentioned && pm) break
    }
    return ChatAlerts(mentioned, pm)
}

private val HTML_TAG = Regex("<[^>]*>")

@Composable
private fun HybridPill(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Lit up in this, with something for you (see HybridChatToggle). */
    accent: Color? = null
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        // Quiet, so it isn't one more thing to look at, unless it's lit.
        color = accent ?: Color.Black.copy(alpha = 0.25f),
        contentColor = if (accent != null) Color.White else Color.White.copy(alpha = 0.5f),
        modifier = modifier
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The channel's chat colours made to sit over video: white text, names
 * coloured for a black background, and quiet see-through lines and
 * outlines. Goes inside CyTubeChannelTheme.
 */
@Composable
internal fun HybridChatTheme(content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val scheme = remember(base) {
        base.copy(
            background = Color.Black,
            onBackground = Color.White,
            onSurface = Color.White,
            onSurfaceVariant = Color(0xFFD0D4DA),
            outline = Color.White.copy(alpha = 0.35f),
            outlineVariant = Color.White.copy(alpha = 0.12f)
        )
    }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalContentColor provides Color.White, content = content)
    }
}

/**
 * Fade old messages: [msg]'s brightness over the video. Bright while it's
 * under HYBRID_FADE_AFTER_MS old by [clock] (moved on by ChatPanel as each
 * one's time comes up), or while the chat's [awake] (being scrolled, and for
 * a while after); else faded back. It fades slowly, and comes back quickly.
 * One already old when chat comes up starts faded: it doesn't fade as chat
 * opens.
 */
@Composable
internal fun rememberAgeAlpha(msg: ChatMessage, clock: LongState, awake: Boolean): State<Float> {
    val old by remember(msg.seq, msg.receivedAt) { derivedStateOf { isOld(msg, clock.longValue) } }
    val target = if (old && !awake) FADED_ALPHA else 1f
    return animateFloatAsState(
        targetValue = target,
        animationSpec = tween(if (target == 1f) UNFADE_MS else FADE_MS),
        label = "messageAge"
    )
}

/** Whether [msg] has had its time by [now] (both SystemClock.elapsedRealtime):
 *  it came in HYBRID_FADE_AFTER_MS ago or more. One never added to chat
 *  (receivedAt 0) is old. */
internal fun isOld(msg: ChatMessage, now: Long): Boolean =
    msg.receivedAt == 0L || msg.receivedAt + HYBRID_FADE_AFTER_MS <= now

/** When the next of [messages] still bright at [now] fades, or null with
 *  none left: the oldest one not yet old, found from the newest back, as
 *  they came in in order. */
internal fun nextFadeDue(messages: List<ChatMessage>, now: Long): Long? {
    var due: Long? = null
    for (i in messages.indices.reversed()) {
        val m = messages[i]
        if (isOld(m, now)) break
        due = m.receivedAt + HYBRID_FADE_AFTER_MS
    }
    return due
}

/**
 * A mention's glow as it comes in: 1 to 0 over [MENTION_GLOW_MS], from when
 * it came in, so one first drawn a moment later (chat coming up just after)
 * carries on from there, and one older has none. Read where it's drawn, so
 * only its wash is drawn again as it goes.
 */
@Composable
internal fun rememberMentionGlow(msg: ChatMessage): State<Float> {
    val glow = remember(msg.seq) {
        val age = SystemClock.elapsedRealtime() - msg.receivedAt
        Animatable(if (msg.receivedAt > 0L && age in 0 until MENTION_GLOW_MS) 1f - age.toFloat() / MENTION_GLOW_MS else 0f)
    }
    LaunchedEffect(glow) {
        if (glow.value > 0f) {
            glow.animateTo(0f, tween((glow.value * MENTION_GLOW_MS).roundToInt(), easing = LinearOutSlowInEasing))
        }
    }
    return glow.asState()
}

/**
 * A physical keyboard's keys that nothing on screen took (nothing being
 * typed in), to [onKey] while this is in the composition: it says whether it
 * took the key. Android's own way to hear them, so nothing needs to hold
 * focus for it.
 */
@Composable
internal fun UnhandledKeys(onKey: (KeyEvent) -> Boolean) {
    val view = LocalView.current
    val handle by rememberUpdatedState(onKey)
    DisposableEffect(view) {
        val listener = ViewCompat.OnUnhandledKeyEventListenerCompat { _, event -> handle(event) }
        ViewCompat.addOnUnhandledKeyEventListener(view, listener)
        onDispose { ViewCompat.removeOnUnhandledKeyEventListener(view, listener) }
    }
}

/** What a key puts in the message box, from its character ([unicodeChar],
 *  as KeyEvent.getUnicodeChar gives it with the keys held), or null for one
 *  that types nothing there: a shortcut ([shortcut]: Ctrl or Meta held), a
 *  dead key (an accent waiting for its letter), a control key, or Space or
 *  Tab, which aren't the start of a message. */
internal fun typedText(unicodeChar: Int, shortcut: Boolean): String? {
    if (shortcut || unicodeChar == 0 || (unicodeChar and KeyCharacterMap.COMBINING_ACCENT) != 0) return null
    if (!Character.isValidCodePoint(unicodeChar) || Character.isISOControl(unicodeChar) ||
        Character.isWhitespace(unicodeChar)
    ) return null
    return String(Character.toChars(unicodeChar))
}

/** [text] typed in where the cursor is, over whatever's selected. */
internal fun TextFieldValue.typedIn(text: String): TextFieldValue {
    val start = minOf(selection.start, selection.end).coerceIn(0, this.text.length)
    val end = maxOf(selection.start, selection.end).coerceIn(0, this.text.length)
    return TextFieldValue(this.text.substring(0, start) + text + this.text.substring(end), TextRange(start + text.length))
}

/** With hybrid chat on, how long the fullscreen controls stay up (and with
 *  them its message box and Hide chat): long enough to reach the box. */
internal const val HYBRID_CONTROLS_MS = 2_500L
/** How quickly hybrid chat's message box (ChatPanel) fades in, and out. */
internal const val HYBRID_FADE_IN_MS = 150
internal const val HYBRID_FADE_OUT_MS = 600
/** The message box in use but empty, and untouched this long, is put away
 *  (see ChatPanel's overVideo). */
internal const val HYBRID_BOX_IDLE_MS = 6_000L

/** Fade old messages: a message fades back once it's this old (by when it
 *  came in); scrolling the chat brings them all back until this long after
 *  it stops. */
internal const val HYBRID_FADE_AFTER_MS = 15_000L
internal const val HYBRID_AWAKE_MS = 10_000L
/** How faded back an old message is, and how quickly it fades (slowly) and
 *  comes back (quickly). */
private const val FADED_ALPHA = 0.35f
private const val FADE_MS = 1_000
private const val UNFADE_MS = 300

/** A mention's wash, from the screen's edge (CyTube Plus's blue: a little
 *  deeper than its bar, so it shows over a light picture too), and how much
 *  brighter it glows as it comes in, for how long. */
internal val MENTION_WASH = Color(0xFF4687D7)
internal const val MENTION_WASH_ALPHA = 0.36f
internal const val MENTION_GLOW_ALPHA = 0.3f
private const val MENTION_GLOW_MS = 2_400

/** Show chat lit up: blue for a mention, amber for a PM, as CyTube Plus. */
private val MENTION_ALERT = Color(0xD93468A0)
private val PM_ALERT = Color(0xD9966C30)

/** Chat slides in from its side as it fades in, and back out the same way:
 *  a short way, quickly, and only then (see HybridChatLayer). */
private val SLIDE_IN_FROM_RIGHT: EnterTransition =
    fadeIn(tween(200)) + slideInHorizontally(tween(240)) { it / 8 }
private val SLIDE_OUT_TO_RIGHT: ExitTransition =
    fadeOut(tween(160)) + slideOutHorizontally(tween(200)) { it / 8 }
private val SLIDE_IN_FROM_LEFT: EnterTransition =
    fadeIn(tween(200)) + slideInHorizontally(tween(240)) { -it / 8 }
private val SLIDE_OUT_TO_LEFT: ExitTransition =
    fadeOut(tween(160)) + slideOutHorizontally(tween(200)) { -it / 8 }

/** The chat column: this much of the screen's width, but never narrower
 *  than [MIN_COLUMN_WIDTH]. */
private const val COLUMN_FRACTION = 0.32f
private val MIN_COLUMN_WIDTH = 240.dp
/** The message box (hybridBoxWidth): at most this much of the width, never
 *  under the minimum, and this far clear of the chat's column. */
private const val BOX_MAX_FRACTION = 0.44f
private val MIN_BOX_WIDTH = 220.dp
private val BOX_GAP = 12.dp
