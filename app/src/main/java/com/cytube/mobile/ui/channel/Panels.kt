package com.cytube.mobile.ui.channel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.decode.BitmapFactoryDecoder
import coil.request.ImageRequest
import coil.size.Size
import com.cytube.mobile.net.ChannelUser
import com.cytube.mobile.net.Emote
import com.cytube.mobile.net.EmoteSet
import com.cytube.mobile.net.ChatMessage
import com.cytube.mobile.net.MediaTypes
import com.cytube.mobile.net.PlaylistItem
import com.cytube.mobile.net.Poll
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TIME_FMT = SimpleDateFormat("HH:mm", Locale.getDefault())

/**
 * Emote box height, in sp so it tracks the user's font scale. CyTube emotes are
 * typically 28-32px tall; 20sp rendered them noticeably smaller than the site.
 * Placeholders taller than the line box would inflate every chat row, so this is
 * the practical ceiling before rows start growing — used for an emote sitting
 * inline in a sentence, where a taller placeholder inflates that whole row.
 */
private const val EMOTE_HEIGHT = 28f

/**
 * A message that's nothing but emotes (see ChatHtml.Rendered.soloEmoteCount)
 * has no sentence around it for a taller row to crowd, so it can afford to
 * actually be seen — roughly double EMOTE_HEIGHT, the same move Discord/
 * Telegram/Slack make for an emoji-only message.
 */
private const val SOLO_EMOTE_HEIGHT = 56f

/**
 * Inline content needs a size before the image has loaded, but emotes are all
 * sorts of shapes. So we reserve a square, then record each emote's real aspect
 * ratio the first time it loads and reuse it everywhere after — which is why
 * emotes settle into the right width and stay there for the rest of the session.
 */
private object EmoteAspect {
    val ratios = mutableStateMapOf<String, Float>()
}

@Composable
private fun inlineEmotes(
    urls: List<String>,
    emoteHeight: Float = EMOTE_HEIGHT
): Map<String, InlineTextContent> {
    if (urls.isEmpty()) return emptyMap()
    val context = LocalContext.current
    val density = LocalDensity.current
    val heightPx = with(density) { emoteHeight.sp.roundToPx() }.coerceAtLeast(1)
    return urls.distinct().associateWith { url ->
        // Until the real aspect ratio is known a square is the least-wrong
        // guess; once loaded the true ratio is reused for the whole session so
        // wide emotes stop being squashed.
        val ratio = (EmoteAspect.ratios[url] ?: 1f).coerceIn(0.2f, 6f)
        val widthPx = (heightPx * ratio).toInt().coerceAtLeast(1)
        InlineTextContent(
            Placeholder(
                width = (emoteHeight * ratio).sp,
                height = emoteHeight.sp,
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
            )
        ) {
            AsyncImage(
                model = remember(url, widthPx, heightPx) {
                    // Emotes render at a fixed, tiny on-screen size. A source
                    // image bigger than that (some channels host oversized
                    // emote GIFs) would otherwise get decoded — every frame,
                    // now that they animate in chat — at full native
                    // resolution just to be scaled down on every draw.
                    // Capping the decode target to the actual display size
                    // fixes exactly the case that got more expensive when
                    // GIF emotes started animating.
                    ImageRequest.Builder(context)
                        .data(url)
                        .size(Size(widthPx, heightPx))
                        .build()
                },
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { state ->
                    val size = state.painter.intrinsicSize
                    if (size.width > 0f && size.height > 0f &&
                        size.width.isFinite() && size.height.isFinite()
                    ) {
                        EmoteAspect.ratios[url] = size.width / size.height
                    }
                }
            )
        }
    }
}

@Composable
fun ChatPanel(
    messages: List<ChatMessage>,
    canSend: Boolean,
    showEmotes: Boolean,
    emotes: EmoteSet,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    // TV only (see TvChatView) — the message list has no business capturing
    // D-pad focus at all there: the whole point of that screen is a fixed
    // video -> Nico -> chat bar stop order, and a chat message list that can
    // steal focus (and, via Compose's default focus-into-view behavior,
    // scroll itself away from the latest message) breaks both halves of
    // that at once. False everywhere else, since phone/touch chat obviously
    // still needs to scroll by hand.
    messagesFocusable: Boolean = true,
    // TV only — lets TvChatView attach its own FocusRequester/key handling
    // (jump here from Nico on Down, jump back to Nico on Up) to the actual
    // input field, without ChatPanel needing to know anything about Nico or
    // focus requesters itself.
    inputFieldModifier: Modifier = Modifier,
    // TV only — there's no touch to pick an emote with, and the emote
    // picker's own grid is a whole separate focus surface this screen's
    // fixed video -> Nico -> chat bar stop order was never built to host.
    showEmotePickerButton: Boolean = true
) {
    val context = LocalContext.current
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    var showEmotePicker by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Only follow new messages when the user is already at the bottom. Yanking
    // the list down while someone is reading back is the single most annoying
    // thing a chat client can do.
    val pinnedToBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 2
        }
    }

    // The very first time this channel's history actually has anything in
    // it, land on the newest message unconditionally — not gated on
    // pinnedToBottom, which reads the LazyColumn's own layoutInfo and can
    // still be settling from the empty-list state at the exact moment the
    // whole chat backlog arrives in one burst right after joining. There is
    // no way the user could have scrolled away from a chat that had nothing
    // in it yet, so this is always correct for a fresh join. Once that has
    // happened once, later messages go back to only following if the user
    // is already at the bottom, same as always — this is a ChatPanel
    // instance is recreated fresh per channel (see ChannelScreen's note on
    // switching channels getting a new vm/state entirely), so this flag
    // naturally resets on the next channel too.
    var hasJumpedToInitialBottom by remember { mutableStateOf(false) }

    // Keyed on the last message's own id, not messages.size: once the chat
    // buffer is full (MAX_CHAT_MESSAGES), appending trims the oldest message
    // at the same rate, so size never changes again and a key of messages.size
    // would silently stop autoscrolling for the rest of the session.
    LaunchedEffect(messages.lastOrNull()?.seq) {
        if (messages.isEmpty()) return@LaunchedEffect
        if (!hasJumpedToInitialBottom) {
            hasJumpedToInitialBottom = true
            listState.scrollToItem(messages.lastIndex)
        } else if (pinnedToBottom) {
            listState.scrollToItem(messages.lastIndex)
        }
    }

    Column(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth()
                // Swallowing Up/Down here (rather than relying on every
                // ChatRow's clickable bits somehow not being focusable) is
                // what actually guarantees this: it stops D-pad focus from
                // ever entering the list in the first place, so there is
                // nothing here for Compose's default arrow-key focus search
                // to land on or scroll into view.
                .then(
                    if (!messagesFocusable) {
                        // Swallow both phases of Up/Down unconditionally —
                        // not just KeyUp — so nothing here ever gets a
                        // chance to treat the press as a focus-search or
                        // scroll trigger.
                        Modifier.onPreviewKeyEvent { event ->
                            event.key == Key.DirectionUp || event.key == Key.DirectionDown
                        }
                    } else {
                        Modifier
                    }
                ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(messages, key = { it.seq }) { msg ->
                ChatRow(
                    msg = msg,
                    showEmotes = showEmotes,
                    emotes = emotes,
                    onUsernameClick = { name -> draft = draft.insertReply(name) },
                    onLinkClick = { url -> openInBrowser(context, url) },
                    // Tapping an emote someone already posted drops the same
                    // shortcode into the draft as picking it from the emote
                    // picker would — a quick way to reuse one you just saw.
                    onEmoteClick = { code -> draft = draft.insertAtCursor(code) },
                    // TV: the username's own clickable is what was actually
                    // grabbing D-pad focus out from under Up/Down swallowing
                    // above — that only stops key events that reach the list
                    // as an ancestor of the focused node, which isn't the
                    // case when focus is sitting in the input field just
                    // below it. Dropping the modifier entirely here (rather
                    // than trying to keep it clickable-but-unfocusable) is
                    // also just correct: there's no touch to tap a name with
                    // on TV in the first place.
                    usernameClickable = messagesFocusable
                )
            }
        }

        HorizontalDivider()

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (emotes.all.isNotEmpty() && showEmotePickerButton) {
                IconButton(onClick = { showEmotePicker = true }) {
                    Icon(Icons.Default.Mood, contentDescription = "Emotes")
                }
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text(if (canSend) "Message" else "Log in to chat") },
                enabled = canSend,
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    onSend(draft.text); draft = TextFieldValue("")
                }),
                modifier = Modifier.weight(1f).then(inputFieldModifier)
            )
            FilledIconButton(
                onClick = { onSend(draft.text); draft = TextFieldValue("") },
                enabled = canSend && draft.text.isNotBlank(),
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send")
            }
        }
    }

    if (showEmotePicker) {
        EmotePicker(
            emotes = emotes.all,
            onPick = { name -> draft = draft.insertAtCursor(name) },
            onDismiss = { showEmotePicker = false }
        )
    }
}

/**
 * Click-to-reply, matching the site: the username plus a colon is inserted at
 * the cursor and nothing is sent. Existing input is preserved.
 */
private fun TextFieldValue.insertReply(username: String): TextFieldValue =
    insertAtCursor("$username: ")

private fun TextFieldValue.insertAtCursor(fragment: String): TextFieldValue {
    val at = selection.start.coerceIn(0, text.length)
    val needsSpace = at > 0 && !text[at - 1].isWhitespace()
    val insert = (if (needsSpace) " " else "") + fragment
    val next = text.substring(0, at) + insert + text.substring(at)
    val caret = at + insert.length
    return TextFieldValue(next, TextRange(caret))
}

/**
 * Links always leave the app. The in-app WebView is for players only.
 * Internal rather than private so the channel notice (MotdSection in
 * ChannelScreen.kt) can send its links to the browser the same way chat does.
 */
internal fun openInBrowser(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { Log.w("CyTube", "No handler for chat link") }
}

/**
 * Everything NekoChatOverlay remembers, hoisted out and owned by the caller
 * (ChannelScreen) instead of the overlay itself. The overlay now has two call
 * sites — the fullscreen player's chrome and the windowed player above the
 * chat panel — and the same on/off switch drives both, so going into or out
 * of fullscreen unmounts one call site and mounts the other rather than
 * leaving a single one in place. If each call site remembered its own state
 * the way the old single-call-site version did, that swap would look exactly
 * like "just turned on" to the new mount: the one-time catch-up burst would
 * fire again and replay the same handful of messages. Passing one shared
 * instance to both call sites (created once, in ChannelScreen, for the whole
 * time the overlay stays switched on) is what makes the swap invisible.
 */
class NekoOverlayState {
    val active = mutableStateListOf<FlyingComment>()
    var lastSpawnedSeq = -1L
    var nextId = 0L
    val laneFreeAtMs = HashMap<Int, Long>()
    var hasCaughtUp = false

    /** Messages waiting for a lane to free up — see the drain loop in
     *  [NekoChatOverlay]. Every message that arrives while the overlay is on
     *  is queued here and animated as soon as a lane opens, rather than
     *  being silently skipped the moment it arrives just because all lanes
     *  happened to be busy that instant; that was making chat look like it
     *  was dropping messages that had actually just gone through fine.
     *  Bounded so a genuinely pathological sustained flood can't grow this
     *  without limit — under that extreme the OLDEST queued message is the
     *  one given up on, never one already animating or a brand-new arrival. */
    val pending = ArrayDeque<ChatMessage>()
}

/**
 * Niconico-style "danmaku" comments: each new chat message flies across the
 * screen right-to-left as its own line, rather than sitting in a fixed list.
 * No background box (transparent — text alone, readable via its own outline
 * glow rather than a scrim behind it), no username (message text only, same
 * as the real Niconico), and nothing on it responds to touch. Reuses
 * ChatHtml's render/inline-emote pipeline (and its cache) so this costs
 * nothing beyond what the real chat panel already pays.
 *
 * Only messages that arrive while this is on screen fly — turning it on
 * doesn't dump the whole existing backlog across the video at once. `state`
 * is shared with (and outlives) whichever other call site of this composable
 * is currently unmounted — see [NekoOverlayState].
 */
@Composable
fun NekoChatOverlay(
    messages: List<ChatMessage>,
    showEmotes: Boolean,
    emotes: EmoteSet,
    state: NekoOverlayState,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val laneHeightPx = remember(density) { with(density) { NEKO_LANE_HEIGHT.roundToPx() } }
        val laneCount = remember(maxHeight) {
            (maxHeight / NEKO_LANE_HEIGHT).toInt().coerceIn(1, NEKO_MAX_LANES)
        }

        // Real Niconico thins comments under load rather than letting them
        // pile up — this is that, in miniature. Each lane records when its
        // current occupant will have finished crossing; a message only
        // spawns if some lane is actually free, and is dropped (not queued)
        // otherwise. That's a hard ceiling on how many of these can ever be
        // animating and drawing at once — never more than laneCount, which
        // is already capped — instead of a chat flood spawning one more
        // blurred, independently-animating line per message with no limit,
        // which is what was eating frames on the video underneath.
        fun claimFreeLane(now: Long): Int? {
            for (lane in 0 until laneCount) {
                if (now >= (state.laneFreeAtMs[lane] ?: 0L)) return lane
            }
            return null
        }

        // Baseline for "what's new" (see the effect below), set exactly once
        // per on-cycle. This runs during composition — strictly before either
        // effect below gets a chance to start — so there is no race between
        // this and the catch-up effect over who reads `messages` first. A
        // fullscreen/windowed remount re-enters this same block, but by then
        // hasCaughtUp is already true (set on the very first mount, well
        // before a later fullscreen toggle), so it's skipped.
        remember(state) {
            if (!state.hasCaughtUp) {
                state.lastSpawnedSeq = messages.lastOrNull()?.seq ?: -1L
            }
        }

        // Turning this on with a quiet channel used to mean a blank screen
        // until someone happened to say something new — which, on a short
        // test, looks exactly like "it does nothing". This one-time,
        // staggered replay of whatever's already in the buffer means there's
        // always something on screen the moment it's enabled. hasCaughtUp is
        // what keeps this to exactly once per on-cycle rather than once per
        // mount — see [NekoOverlayState].
        LaunchedEffect(state) {
            if (state.hasCaughtUp) return@LaunchedEffect
            state.hasCaughtUp = true
            val catchUp = messages.filterNot { it.isServerMessage }.takeLast(NEKO_CATCHUP_COUNT)
            catchUp.forEach { msg ->
                val now = System.currentTimeMillis()
                val lane = claimFreeLane(now)
                if (lane != null) {
                    val durationMs = estimateNekoDurationMs(msg.html, screenWidthPx)
                    state.laneFreeAtMs[lane] = now + durationMs
                    state.active.add(
                        FlyingComment(id = state.nextId++, msg = msg, lane = lane, durationMs = durationMs)
                    )
                } else {
                    // Every lane already busy the instant this one wanted to
                    // spawn — queue it instead of dropping it; the drain
                    // loop below picks it up the moment a lane opens.
                    state.pending.addLast(msg)
                }
                delay(350)
            }
        }

        // Fresh arrivals are only ever enqueued here, never spawned
        // directly — claiming a lane and actually animating a message is
        // entirely the drain loop's job below. That way a message that
        // arrives the same instant every lane happens to be busy is simply
        // queued, not skipped: previously this dropped it outright the
        // moment claimFreeLane came back empty, which is exactly what made
        // some typed messages look like they "didn't go through" on the
        // overlay even though they were always in the real chat panel.
        LaunchedEffect(messages) {
            val fresh = messages.filter { !it.isServerMessage && it.seq > state.lastSpawnedSeq }
            for (msg in fresh) {
                if (state.pending.size >= NEKO_MAX_PENDING) state.pending.removeFirstOrNull()
                state.pending.addLast(msg)
            }
            if (messages.isNotEmpty()) state.lastSpawnedSeq = messages.last().seq
        }

        // Drains the queue as lanes free up, for as long as the overlay is
        // on. A short, fixed poll interval rather than reacting to lane
        // expiry directly — simple, and cheap enough (a HashMap scan over
        // at most NEKO_MAX_LANES entries) that it costs nothing noticeable
        // against the video it's drawn over.
        LaunchedEffect(state) {
            while (true) {
                if (state.pending.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    val lane = claimFreeLane(now)
                    if (lane != null) {
                        val msg = state.pending.removeFirst()
                        val durationMs = estimateNekoDurationMs(msg.html, screenWidthPx)
                        state.laneFreeAtMs[lane] = now + durationMs
                        state.active.add(
                            FlyingComment(id = state.nextId++, msg = msg, lane = lane, durationMs = durationMs)
                        )
                    }
                }
                delay(150)
            }
        }

        state.active.forEach { comment ->
            key(comment.id) {
                FlyingCommentItem(
                    comment = comment,
                    laneHeightPx = laneHeightPx,
                    screenWidthPx = screenWidthPx,
                    showEmotes = showEmotes,
                    emotes = emotes,
                    onFinished = { state.active.remove(comment) }
                )
            }
        }
    }
}

// Not private: NekoOverlayState.active (public, so ChannelScreen can hoist
// and share it) is a list of these, and a public property can't expose a
// private type.
data class FlyingComment(
    val id: Long,
    val msg: ChatMessage,
    val lane: Int,
    /** Spawn-time estimate (see [estimateNekoDurationMs]) — used only to book
     *  [NekoOverlayState.laneFreeAtMs] before the real text width is known,
     *  and as the very first frame's animation target before that.
     *  FlyingCommentItem recomputes the real value from the real measured
     *  width via the same [nekoDurationMs] formula once it has one. */
    val durationMs: Int
)

/**
 * Traditional niconico "naka" (scrolling) comments cross the screen in a
 * fixed duration regardless of length — confirmed against danmaku2ass, the
 * reference tool the danmaku community uses to precisely replicate real
 * niconico/bilibili comment movement for burning into video: it moves every
 * scrolling comment across (stageWidth + textWidth) in one fixed
 * "duration_marquee" window, never a length-dependent one. That fixed window
 * is exactly why a longer comment visibly moves FASTER on the real site —
 * it has farther to travel in the very same time — which is the "traditional
 * nico speed" this now emulates, per user request, in place of the constant-
 * speed model tried right before this (which kept every message at the same
 * pace and read as too slow for a long one by comparison).
 *
 * 4s (the literal traditional-nico figure) still read as a bit fast overall
 * once this was actually on a phone screen — scaled to 5s per follow-up user
 * feedback. NEKO_MAX_SPEED_PX_PER_MS and NEKO_MAX_DURATION_MS below are
 * scaled by the same 5/4 factor alongside it, so the relationship between
 * them — where the speed ceiling starts overriding the baseline — lands in
 * the same place relative to message length as before, just uniformly 25%
 * slower throughout instead of only for short messages.
 */
private const val NEKO_BASELINE_DURATION_MS = 5_000

/**
 * Speed ceiling — a safety net the real site doesn't need. Niconico itself
 * caps a single comment at a modest character count, so its fixed duration
 * above never has to cover more than a bounded distance. CyTube chat has no
 * such cap, so without this, a genuinely long message would still be
 * squeezed into the same fixed window and end up racing past unreadably
 * fast — the original "long messages get cut off" complaint this whole
 * thing started from. Anything within roughly niconico's own real-world
 * comment length crosses at the traditional fixed duration above,
 * untouched; only messages longer than that get a stretched-out (slower)
 * duration to stay legible.
 */
private const val NEKO_MAX_SPEED_PX_PER_MS = 0.72f

/** Absolute upper bound alongside the speed ceiling above — belt-and-braces
 *  against something pathological (a pasted wall of text) camping a lane for
 *  the better part of a minute; ordinary chat, even a long message, never
 *  comes close to this. */
private const val NEKO_MAX_DURATION_MS = 15_000

/** Rough px-per-character (deliberately a bit generous for NEKO_TEXT_STYLE's
 *  20sp bold) used only to ESTIMATE a not-yet-measured message's width at
 *  spawn time, so [NekoOverlayState.laneFreeAtMs] is booked for roughly the
 *  right length of time before FlyingCommentItem has actually measured it.
 *  Overestimating here means a lane frees a little later than strictly
 *  needed, never earlier while the real message is still on screen — this
 *  estimate never affects what's drawn, only how long a lane is
 *  provisionally held. */
private const val NEKO_ESTIMATED_PX_PER_CHAR = 16f

/** The actual traditional-nico duration formula: fixed baseline duration,
 *  stretched out only once the message is long enough that holding the
 *  speed ceiling would otherwise require less than that baseline. */
private fun nekoDurationMs(distancePx: Float): Int =
    (distancePx / NEKO_MAX_SPEED_PX_PER_MS).roundToInt()
        .coerceIn(NEKO_BASELINE_DURATION_MS, NEKO_MAX_DURATION_MS)

private fun estimateNekoDurationMs(rawHtml: String, screenWidthPx: Float): Int {
    val estimatedWidthPx = rawHtml.length * NEKO_ESTIMATED_PX_PER_CHAR
    return nekoDurationMs(screenWidthPx + estimatedWidthPx)
}

/** Vertical space each flying line gets — tall enough for NEKO_TEXT_STYLE's
 *  20sp bold plus a little breathing room between lines. */
private val NEKO_LANE_HEIGHT = 34.dp

/** However tall the video is, don't spread comments thinner than this —
 *  Niconico itself only ever uses a modest number of rows regardless of
 *  screen size. */
private const val NEKO_MAX_LANES = 10

/** Hard cap on [NekoOverlayState.pending] — protects against unbounded
 *  growth under a genuinely pathological sustained flood. Ordinary chat,
 *  even a busy one, never comes close: at ~150ms drain ticks and up to
 *  NEKO_MAX_LANES spawning per tick, the queue drains far faster than it
 *  could realistically fill. */
private const val NEKO_MAX_PENDING = 200

/** How many already-buffered messages replay immediately when the overlay
 *  is turned on — enough to feel alive right away without dumping the
 *  entire backlog across the video at once. */
private const val NEKO_CATCHUP_COUNT = 5

private val NEKO_LINK_COLOR = Color(0xFF80D8FF)

/** White with a soft black glow instead of a background box — legible over
 *  arbitrary video without ever needing to darken it. Bold for the same
 *  reason Niconico's own comments are bold: thin strokes wash out fastest
 *  against a bright, busy frame. Blur is deliberately modest — it's the
 *  single most expensive thing about redrawing one of these lines every
 *  frame while it moves, and now that the lane cap bounds how many can be
 *  on screen at once, it's the next easiest lever if it's still not smooth. */
private val NEKO_TEXT_STYLE = TextStyle(
    fontSize = 20.sp,
    fontWeight = FontWeight.Bold,
    shadow = Shadow(color = Color.Black, offset = Offset.Zero, blurRadius = 6f)
)

@Composable
private fun FlyingCommentItem(
    comment: FlyingComment,
    laneHeightPx: Int,
    screenWidthPx: Float,
    showEmotes: Boolean,
    emotes: EmoteSet,
    onFinished: () -> Unit
) {
    val rendered = remember(comment.id) {
        ChatHtml.render(comment.msg.html, comment.msg.addClass == "greentext", NEKO_LINK_COLOR, showEmotes, emotes)
    }
    // Same solo-emote sizing rule as the real chat panel — a message that's
    // nothing but emotes still gets to be seen at a glance here too.
    val emoteHeight = if (rendered.soloEmoteCount > 0) SOLO_EMOTE_HEIGHT else EMOTE_HEIGHT
    val inline = inlineEmotes(rendered.imageUrls, emoteHeight)

    // How far past the left edge counts as "fully off-screen" depends on the
    // message's own width, not just the screen's. This used to always be a
    // flat -screenWidthPx: fine for a short message, but a message wider
    // than the screen would still have its tail end visible at that point —
    // onFinished (below) removed it from the overlay right then anyway, so a
    // long comment visibly vanished mid-flight instead of sliding fully off.
    // 0f until the first layout pass below reports the real width, which
    // happens on the very first frame, before the comment has travelled any
    // visible distance — so retargeting the animation the moment it's known
    // isn't seen as a stutter.
    var textWidthPx by remember(comment.id) { mutableStateOf(0f) }

    val x = remember(comment.id) { Animatable(screenWidthPx) }
    LaunchedEffect(comment.id, screenWidthPx, textWidthPx) {
        // Duration is recomputed here from the REAL measured width via the
        // same nekoDurationMs formula, not read off comment.durationMs — that
        // field is only the spawn-time estimate used to book
        // NekoOverlayState.laneFreeAtMs before this layout pass happened, and
        // (being a bit generous by design, see NEKO_ESTIMATED_PX_PER_CHAR) it
        // frees the lane at or after this real duration finishes, never
        // before.
        val distance = screenWidthPx + textWidthPx
        val durationMs = nekoDurationMs(distance)
        x.animateTo(
            targetValue = -distance,
            animationSpec = tween(durationMillis = durationMs, easing = LinearEasing)
        )
        onFinished()
    }

    Text(
        text = rendered.text,
        inlineContent = inline,
        style = NEKO_TEXT_STYLE,
        color = Color.White,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible,
        onTextLayout = { layout -> textWidthPx = layout.size.width.toFloat() },
        modifier = Modifier.offset {
            IntOffset(x.value.roundToInt(), laneHeightPx * comment.lane)
        }
    )
}

/**
 * Browse the room's live emote set and drop codes into the input. Mirrors the
 * official emote list panel, minus the desktop table.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmotePicker(
    emotes: List<Emote>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(emotes, query) {
        if (query.isBlank()) emotes
        else emotes.filter { it.name.contains(query, ignoreCase = true) }
    }
    // A channel can have thousands of custom emotes. LazyVerticalGrid already
    // only composes what's on screen, but showing all of them at once still
    // means a long fling scrolls through thousands of Coil requests getting
    // fired off in a hurry, and the full list is just a lot to browse blind.
    // Paging keeps the working set small and lets a search narrow it further
    // — resets whenever the filter text changes, so a new search starts from
    // its own first page rather than wherever scrolling had gotten to.
    var visibleCount by remember(query) { mutableStateOf(EMOTE_PAGE_SIZE) }
    val page = remember(shown, visibleCount) { shown.take(visibleCount) }
    val context = LocalContext.current
    // The app's ImageLoader has an animated-GIF decoder registered globally
    // (see CyTubeApp) so chat emotes animate. This picker opts back out of
    // that per-request — decoding every visible tile's first frame only —
    // because a whole grid of emotes animating at once, just to pick one, is
    // decode/CPU cost with no benefit.
    val staticGifDecoder = remember { BitmapFactoryDecoder.Factory() }
    val tilePx = with(LocalDensity.current) { 48.dp.roundToPx() }.coerceAtLeast(1)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxHeight(0.7f)) {
            Text(
                if (shown.size == emotes.size) "Channel emotes (${emotes.size})"
                else "Channel emotes (${shown.size} of ${emotes.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Filter") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(8.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(88.dp),
                contentPadding = PaddingValues(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                gridItems(page, key = { it.name }) { emote ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .padding(6.dp)
                            // Insert only. Never send.
                            .clickable { onPick(emote.name) }
                            .padding(6.dp)
                    ) {
                        AsyncImage(
                            model = remember(emote.image) {
                                ImageRequest.Builder(context)
                                    .data(emote.image)
                                    .decoderFactory(staticGifDecoder)
                                    .size(Size(tilePx, tilePx))
                                    .build()
                            },
                            contentDescription = emote.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            emote.name,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (visibleCount < shown.size) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        TextButton(
                            onClick = { visibleCount += EMOTE_PAGE_SIZE },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        ) {
                            Text("Show more (${shown.size - visibleCount} remaining)")
                        }
                    }
                }
            }
        }
    }
}

/** How many emotes the picker shows before "Show more" — a channel with a
 *  few thousand custom emotes shouldn't hand LazyVerticalGrid the whole list
 *  (and a Coil request per tile) the moment the sheet opens. */
private const val EMOTE_PAGE_SIZE = 200

@Composable
private fun ChatRow(
    msg: ChatMessage,
    showEmotes: Boolean,
    emotes: EmoteSet,
    onUsernameClick: (String) -> Unit,
    onLinkClick: (String) -> Unit,
    onEmoteClick: (String) -> Unit,
    usernameClickable: Boolean = true
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val rendered = remember(msg.html, msg.addClass, linkColor, showEmotes, emotes) {
        ChatHtml.render(msg.html, msg.addClass == "greentext", linkColor, showEmotes, emotes)
    }
    // A message that's nothing but emotes gets to actually be seen; one
    // sitting mid-sentence stays at the compact inline size so it doesn't
    // inflate a normal chat row. See ChatHtml.Rendered.soloEmoteCount.
    val emoteHeight = if (rendered.soloEmoteCount > 0) SOLO_EMOTE_HEIGHT else EMOTE_HEIGHT
    val inline = inlineEmotes(rendered.imageUrls, emoteHeight)

    if (msg.isServerMessage) {
        Text(
            rendered.text,
            inlineContent = inline,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
        )
        return
    }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                msg.username,
                style = MaterialTheme.typography.labelLarge,
                color = if (msg.isPm) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.primary,
                // Only the name is clickable, not the row. The padding is
                // applied AFTER clickable (not before) specifically so it
                // grows the actual tap target instead of just adding
                // invisible dead space around a target that stays small —
                // short usernames were easy to miss otherwise.
                modifier = Modifier
                    .then(
                        if (usernameClickable) {
                            Modifier.clickable { onUsernameClick(msg.username) }
                        } else {
                            Modifier
                        }
                    )
                    .padding(vertical = 4.dp, horizontal = 2.dp)
            )
            Text(
                TIME_FMT.format(Date(msg.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (msg.isPm) {
                Text("PM", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary)
            }
        }
        // ClickableText cannot take inlineContent, and inline emotes are not
        // negotiable, so the tap is hit-tested against the laid-out text
        // instead. Taps that don't land on a link fall through untouched.
        var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
        Text(
            text = rendered.text,
            inlineContent = inline,
            style = MaterialTheme.typography.bodyMedium,
            onTextLayout = { layout = it },
            modifier = Modifier.pointerInput(rendered.text) {
                // Emotes render small (28sp inline, still only 56sp even for
                // a solo emote), and getOffsetForPosition only ever resolves
                // to the exact character the finger landed on — a tap a few
                // px off the actual glyph missed the emote's own char index
                // entirely and fell through as "no emote", which is exactly
                // the "requires excessive precision" complaint. This adds a
                // forgiving margin around each emote's own rendered box
                // before giving up on it, the same way a real touch target
                // is bigger than its visible icon.
                val tolerancePx = 10.dp.toPx()
                detectTapGestures { pos ->
                    val l = layout ?: return@detectTapGestures
                    val offset = l.getOffsetForPosition(pos)
                    // Emotes and links never overlap, but check emotes first
                    // since that's the more specific hit.
                    val emote = ChatHtml.emoteAt(rendered.text, offset) ?: run {
                        val spans = rendered.text.getStringAnnotations(
                            ChatHtml.EMOTE_TAG, 0, rendered.text.length
                        )
                        spans.firstOrNull { span ->
                            val box = l.getBoundingBox(span.start)
                            pos.x in (box.left - tolerancePx)..(box.right + tolerancePx) &&
                                pos.y in (box.top - tolerancePx)..(box.bottom + tolerancePx)
                        }?.item
                    }
                    if (emote != null) onEmoteClick(emote)
                    else ChatHtml.linkAt(rendered.text, offset)?.let(onLinkClick)
                }
            }
        )
    }
}

@Composable
fun PlaylistPanel(
    items: List<PlaylistItem>,
    currentUid: Int,
    canControl: Boolean,
    onJumpTo: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) {
        EmptyPanel("The playlist is empty, or you do not have permission to see it.", modifier)
        return
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(items, key = { it.uid }) { item ->
            val isCurrent = item.uid == currentUid
            Row(
                Modifier.fillMaxWidth()
                    .background(
                        if (isCurrent) MaterialTheme.colorScheme.surfaceContainerHigh
                        else Color.Transparent
                    )
                    .clickable(enabled = canControl) { onJumpTo(item.uid) }
                    .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isCurrent) {
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
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        buildString {
                            append(item.duration)
                            append(" · ")
                            append(MediaTypes.label(item.type))
                            if (item.queueby.isNotBlank()) { append(" · "); append(item.queueby) }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (canControl) {
                    IconButton(onClick = { onDelete(item.uid) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * Voting only — creating or closing a poll is a moderator action done from
 * the site itself. counts entries of -1 mean the owner obscured the poll
 * (see [Poll.isObscured]); those show "?" and no bar instead of a fake 0.
 */
@Composable
fun PollPanel(
    poll: Poll?,
    myVote: Int?,
    onVote: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (poll == null) {
        EmptyPanel("No poll is running right now.", modifier)
        return
    }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text(poll.title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            buildString {
                append("Opened by ")
                append(poll.initiator.ifBlank { "the channel" })
                if (poll.isObscured) append(" · results hidden until it closes")
                else {
                    append(" · ")
                    append(poll.totalVotes)
                    append(if (poll.totalVotes == 1) " vote" else " votes")
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        poll.options.forEachIndexed { i, option ->
            val count = poll.counts.getOrElse(i) { -1 }
            val fraction = if (poll.totalVotes > 0 && count >= 0) count.toFloat() / poll.totalVotes else 0f
            val selected = myVote == i

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                    .clickable { onVote(i) }
                    .padding(12.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        option,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (count < 0) "?" else count.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!poll.isObscured) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = fraction,
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                    )
                }
            }
        }
    }
}

@Composable
fun UsersPanel(users: List<ChannelUser>, modifier: Modifier = Modifier) {
    if (users.isEmpty()) {
        EmptyPanel("No users listed.", modifier)
        return
    }
    val sorted = remember(users) { users.sortedWith(compareByDescending<ChannelUser> { it.rank }.thenBy { it.name.lowercase() }) }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(sorted, key = { it.name }) { user ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape)
                        .background(
                            if (user.afk) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.primary
                        )
                )
                Text(
                    user.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (user.afk) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                rankLabel(user.rank)?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Rank thresholds follow src/user.js / Rank flags in the CyTube source. */
private fun rankLabel(rank: Double): String? = when {
    rank >= 255 -> "Site Admin"
    rank >= 4 -> "Owner"
    rank >= 3 -> "Admin"
    rank >= 2 -> "Moderator"
    else -> null
}

@Composable
private fun EmptyPanel(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
