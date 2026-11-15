package com.cytube.mobile.ui.channel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
 *  - links in messages are plain text (ChatPanel's overVideo).
 * Hide chat (HybridChatToggle, in the controls' top row, on chat's side), or
 * Back, puts it away; Show chat, in the same place, brings it back. Chat
 * slides in and out at its side, and is never shown in picture-in-picture.
 *
 * Nothing sits behind it: the messages are drawn straight over the video.
 * Nothing here runs while chat just sits there: no animation but the slides
 * and fades, no timers, no layers.
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
 * messages have come in since [since] (a ChatMessage.seq). It goes in the
 * fullscreen controls' top row (FullscreenPlayer), at chat's end of it: up
 * with them, beside the chat and never over it, and the same quiet look
 * either way.
 */
@Composable
internal fun HybridChatToggle(
    showing: Boolean,
    messages: State<PersistentList<ChatMessage>>,
    since: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (showing) {
        HybridPill(Icons.Default.VisibilityOff, "Hide chat", onClick, modifier)
    } else {
        val unread by remember(messages, since) { derivedStateOf { unreadSince(messages.value, since) } }
        HybridPill(
            Icons.Default.Visibility,
            when {
                unread == 0 -> "Show chat"
                unread > 99 -> "Show chat · 99+"
                else -> "Show chat · $unread"
            },
            onClick,
            modifier
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

@Composable
private fun HybridPill(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        // Quiet, so it isn't one more thing to look at.
        color = Color.Black.copy(alpha = 0.25f),
        contentColor = Color.White.copy(alpha = 0.5f),
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

/** With hybrid chat on, how long the fullscreen controls stay up (and with
 *  them its message box and Hide chat): long enough to reach the box. */
internal const val HYBRID_CONTROLS_MS = 2_500L
/** How quickly hybrid chat's message box (ChatPanel) fades in, and out. */
internal const val HYBRID_FADE_IN_MS = 150
internal const val HYBRID_FADE_OUT_MS = 600

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
