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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.unit.Density
import kotlin.math.roundToInt
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Size
import com.cytube.mobile.net.ChannelUser
import com.cytube.mobile.net.Emote
import com.cytube.mobile.net.EmoteSet
import com.cytube.mobile.net.ChatMessage
import com.cytube.mobile.net.MediaTypes
import com.cytube.mobile.net.PlaylistItem
import com.cytube.mobile.net.Poll
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One shared, immutable formatter rather than a new SimpleDateFormat for
 *  every chat row every time it's composed. */
private val CHAT_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun formatTime(timestamp: Long): String =
    CHAT_TIME_FORMAT.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))

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
    // A plain map, not Compose state. It used to be a mutableStateMapOf read
    // inside every row's remember{}, which meant each newly loaded emote
    // invalidated every visible chat row (state maps notify all readers on
    // any write), while the remember{} keys never changed, so none of those
    // rows actually picked the new ratio up either. Rows now watch their own
    // emotes instead — see inlineEmotes. Only touched on the main thread
    // (composition and Coil's onSuccess).
    private val ratios = HashMap<String, Float>()
    // Unbounded before this — every distinct emote URL seen all session (across
    // every channel visited) stayed in memory forever. A long session across a
    // few busy channels can rack up thousands of distinct emote URLs; this
    // caps it with a blunt but simple full-clear once it's clearly grown past
    // any single channel's real emote set. Losing cached ratios just means a
    // brief re-measure flicker next time those emotes render, not a crash.
    private const val MAX_ENTRIES = 500

    operator fun get(url: String): Float? = ratios[url]

    /** Returns true if this changed what's known for [url] enough to be worth
     *  re-laying-out the row (a new emote, or a real change in shape). */
    fun record(url: String, ratio: Float): Boolean {
        val previous = ratios[url]
        if (previous != null && kotlin.math.abs(previous - ratio) < 0.05f) return false
        if (ratios.size >= MAX_ENTRIES && previous == null) ratios.clear()
        ratios[url] = ratio
        return true
    }
}

@Composable
private fun inlineEmotes(
    urls: List<String>,
    emoteHeight: Float = EMOTE_HEIGHT
): Map<String, InlineTextContent> {
    if (urls.isEmpty()) return emptyMap()
    val context = LocalContext.current
    val density = LocalDensity.current
    // Bumped when one of THIS row's own emotes first reports its real shape,
    // so only this row re-lays-out with the right width (see EmoteAspect).
    var aspectVersion by remember(urls) { mutableIntStateOf(0) }
    return remember(urls, emoteHeight, density, aspectVersion) {
        val heightPx = with(density) { emoteHeight.sp.roundToPx() }.coerceAtLeast(1)
        urls.distinct().associateWith { url ->
            val ratio = (EmoteAspect[url] ?: 1f).coerceIn(0.2f, 6f)
            val widthPx = (heightPx * ratio).toInt().coerceAtLeast(1)
            InlineTextContent(
                Placeholder(
                    width = (emoteHeight * ratio).sp,
                    height = emoteHeight.sp,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                )
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(url)
                        .size(Size(widthPx, heightPx))
                        .precision(Precision.EXACT)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                    onSuccess = { state ->
                        val size = state.painter.intrinsicSize
                        if (size.width > 0f && size.height > 0f &&
                            size.width.isFinite() && size.height.isFinite()
                        ) {
                            if (EmoteAspect.record(url, size.width / size.height)) aspectVersion++
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun ChatPanel(
    messages: ImmutableList<ChatMessage>,
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

    // The app calls enableEdgeToEdge() (MainActivity), and nothing else in
    // the tree ever consumed the IME inset before this — confirmed via a
    // full search, no other imePadding()/WindowInsets.ime use anywhere — so
    // without it the keyboard just draws over the message input field below.
    // The actual .imePadding() call is scoped to just the input row further
    // down, not this whole Column: it only needs to move that one Row, and
    // the LazyColumn's own `Modifier.weight(1f)` already gives up room to
    // whatever that row's total height ends up being, so scoping it there
    // is equivalent to applying it here — minus the risk of it interacting
    // with anything else this fillMaxSize() Column doesn't already handle.
    // (AndroidManifest.xml also now declares windowSoftInputMode="adjustResize"
    // — with edge-to-edge that triggers no actual window resize, but it stops
    // some OS versions from ALSO panning the window on top of this, which is
    // what was pushing the input field far higher than the keyboard needs.)
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
            items(
                items = messages,
                key = { it.seq },
                contentType = { if (it.isServerMessage) "server" else "user" }
            ) { msg ->
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
                    usernameClickable = messagesFocusable,
                    // TV: same reasoning as usernameClickable just above —
                    // no tap to reveal a spoiler with on TV, and a D-pad has
                    // no real equivalent of one, so spoilers there just show
                    // as normal text instead of hidden-until-tapped. Reuses
                    // messagesFocusable rather than a separate isTv flag
                    // since the two have always meant the same thing here.
                    revealSpoilers = !messagesFocusable
                )
            }
        }

        HorizontalDivider()

        Row(
            // The one place this panel actually needs to react to the
            // keyboard — see this composable's note on imePadding() further
            // up. Scoped to just this row rather than the whole panel, right
            // where the padding needs to land: directly between this row and
            // the keyboard, with nothing else in between. Bottom padding is
            // wider than top/horizontal (16dp vs 8/12dp) rather than the
            // plain symmetric 8dp it used to be: sitting perfectly flush
            // against the keyboard left no room for the text cursor's own
            // drag handle, which draws a bit below the cursor line and was
            // getting visually clipped right at the keyboard's top edge the
            // moment it appeared. 16dp is enough room for that handle
            // without reading as a gap again.
            Modifier.fillMaxWidth().imePadding()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp),
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
                // Grows with the draft instead of staying pinned to one line
                // and scrolling the typed text sideways out of view — capped
                // so a genuinely long paste can't take over the screen; still
                // scrolls internally past that, same as any other multi-line
                // field. imeAction = Send below still shows the ordinary
                // "send" key instead of a return/newline glyph and fires
                // onSend the same as before, so hitting enter still sends
                // rather than adding a line — this only changes what happens
                // while there's more text than fits on one line.
                maxLines = 5,
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
    // url is untrusted here — it can come straight from another member's
    // chat message or a channel's MOTD. Only ever hand the system a plain
    // web link; anything else (a scheme registered by some other installed
    // app, tel:, sms:, market:, etc.) gets dropped rather than launched.
    val scheme = Uri.parse(url).scheme?.lowercase()
    if (scheme != "http" && scheme != "https") {
        Log.w("CyTube", "Refusing to open link with untrusted scheme: $scheme")
        return
    }
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

/** One flying comment's motion, tracked per lane so new comments can trail
 *  safely behind earlier ones without colliding. */
class LaneOccupant(
    val spawnAtMs: Long,
    val speedPxPerMs: Float,
    val widthPx: Float,
    val clearAtMs: Long
)

class NekoOverlayState {
    val active = mutableStateListOf<FlyingComment>()
    var lastSpawnedSeq = -1L
    var nextId = 0L

    /** Active occupants per lane, used for constant-velocity collision detection.
     *  Expired entries are pruned continuously as lanes are evaluated. */
    val laneOccupants = HashMap<Int, MutableList<LaneOccupant>>()
    var nextLaneIndex = 0
    var hasCaughtUp = false

    /** Messages waiting for a lane to free up. Never dropped under flood —
     *  the continuous trailing lane model provides high throughput at normal,
     *  comfortable reading speeds. */
    val pending = ArrayDeque<ChatMessage>()

    /** Wakes the drain loop in [NekoChatOverlay] the instant something is
     *  enqueued, instead of polling on a fixed timer. */
    val wakeSignal = Channel<Unit>(Channel.CONFLATED)
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
    messages: ImmutableList<ChatMessage>,
    showEmotes: Boolean,
    emotes: EmoteSet,
    state: NekoOverlayState,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val laneHeightPx = remember(density) { with(density) { NEKO_LANE_HEIGHT.roundToPx() } }
        val gapPx = remember(density) { with(density) { NEKO_MIN_GAP_DP.dp.toPx() } }
        val maxLanesByHeight = remember(maxHeight) {
            (maxHeight / NEKO_LANE_HEIGHT).toInt().coerceAtLeast(1)
        }
        val laneCount = remember(maxLanesByHeight) { maxLanesByHeight.coerceAtMost(NEKO_MAX_LANES) }

        SideEffect {
            if (!state.hasCaughtUp && state.lastSpawnedSeq == -1L) {
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
            for (msg in catchUp) {
                state.pending.addLast(msg)
            }
            if (catchUp.isNotEmpty()) {
                state.wakeSignal.trySend(Unit)
            }
        }

        // Fresh arrivals are enqueued here and drained smoothly. All messages
        // are preserved and displayed without dropping.
        LaunchedEffect(messages) {
            val fresh = ArrayList<ChatMessage>()
            for (i in messages.indices.reversed()) {
                val m = messages[i]
                if (m.seq <= state.lastSpawnedSeq) break
                if (!m.isServerMessage) fresh.add(m)
            }
            fresh.reverse()
            for (msg in fresh) {
                state.pending.addLast(msg)
            }
            if (fresh.isNotEmpty()) state.wakeSignal.trySend(Unit)
            if (messages.isNotEmpty()) state.lastSpawnedSeq = messages.last().seq
        }

        // Drains the queue using continuous non-collision lane allocation.
        // Multiple comments naturally trail each other in each lane with
        // guaranteed safe margins, producing high throughput at a steady,
        // comfortable reading pace without chaotic speed jumps or text walls.
        LaunchedEffect(state, laneCount, screenWidthPx) {
            while (true) {
                if (state.pending.isEmpty()) {
                    state.wakeSignal.receive()
                    continue
                }
                val now = System.currentTimeMillis()
                val msg = state.pending.first()
                val estimatedWidthPx = estimateCommentWidthPx(msg, showEmotes, emotes, density)
                val durationMs = nekoDurationMs(screenWidthPx + estimatedWidthPx)
                val speedPxPerMs = (screenWidthPx + estimatedWidthPx) / durationMs

                val lane = findBestLane(
                    now = now,
                    candidateWidthPx = estimatedWidthPx,
                    candidateSpeedPxPerMs = speedPxPerMs,
                    laneCount = laneCount,
                    gapPx = gapPx,
                    screenWidthPx = screenWidthPx,
                    state = state
                )

                if (lane != null) {
                    state.pending.removeFirst()
                    val clearAtMs = now + durationMs
                    val list = state.laneOccupants.getOrPut(lane) { mutableListOf() }
                    list.add(LaneOccupant(spawnAtMs = now, speedPxPerMs = speedPxPerMs, widthPx = estimatedWidthPx, clearAtMs = clearAtMs))
                    state.active.add(
                        FlyingComment(
                            id = state.nextId++,
                            msg = msg,
                            lane = lane,
                            widthPx = estimatedWidthPx,
                            durationMs = durationMs
                        )
                    )
                    // Gentle stagger so consecutive comments stream in with pleasant pacing
                    delay(NEKO_SPAWN_STAGGER_MS)
                } else {
                    val waitMs = calculateNextSafeWaitMs(now, laneCount, gapPx, state)
                    withTimeoutOrNull(waitMs) { state.wakeSignal.receive() }
                }
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
    val widthPx: Float,
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
 * nico speed" this emulates.
 */
private const val NEKO_BASELINE_DURATION_MS = 3_755

/**
 * Speed ceiling — a safety net so exceptionally long messages (which CyTube
 * permits without length caps) cross at a readable, stretched-out duration
 * rather than speeding past unreadably.
 */
private const val NEKO_MAX_SPEED_PX_PER_MS = 0.95634f

/** Absolute upper bound alongside the speed ceiling above. */
private const val NEKO_MAX_DURATION_MS = 11_265

/** Minimum horizontal clearance in dp between consecutive comments in the same lane. */
private const val NEKO_MIN_GAP_DP = 24

/** Inter-spawn stagger delay in ms to prevent simultaneous multi-lane vertical walls. */
private const val NEKO_SPAWN_STAGGER_MS = 75L

/** Vertical space each flying line gets. */
private val NEKO_LANE_HEIGHT = 34.dp

/** Maximum number of concurrent comment lanes on screen. */
private const val NEKO_MAX_LANES = 12

/** How many already-buffered messages replay immediately when the overlay
 *  is turned on. */
private const val NEKO_CATCHUP_COUNT = 5

private val NEKO_LINK_COLOR = Color(0xFF80D8FF)

/** White with a soft black glow instead of a background box. */
private val NEKO_TEXT_STYLE = TextStyle(
    fontSize = 20.sp,
    fontWeight = FontWeight.Bold,
    shadow = Shadow(color = Color.Black, offset = Offset.Zero, blurRadius = 6f)
)

/** Computes duration based on travel distance and speed ceiling. */
private fun nekoDurationMs(distancePx: Float): Int {
    val durationFromSpeedLimit = (distancePx / NEKO_MAX_SPEED_PX_PER_MS).roundToInt()
    return durationFromSpeedLimit.coerceIn(NEKO_BASELINE_DURATION_MS, NEKO_MAX_DURATION_MS)
}

/** Accurately estimates comment width using ChatHtml's cached parse. */
private fun estimateCommentWidthPx(
    msg: ChatMessage,
    showEmotes: Boolean,
    emotes: EmoteSet,
    density: Density
): Float {
    val rendered = ChatHtml.render(msg.html, msg.addClass == "greentext", NEKO_LINK_COLOR, showEmotes, emotes)
    val soloEmotePx = with(density) { SOLO_EMOTE_HEIGHT.sp.toPx() }
    val emotePx = with(density) { EMOTE_HEIGHT.sp.toPx() }
    val charWidthPx = with(density) { 13.dp.toPx() }

    return if (rendered.soloEmoteCount > 0) {
        rendered.soloEmoteCount * soloEmotePx + with(density) { 8.dp.toPx() }
    } else {
        val textWidth = rendered.text.length * charWidthPx
        val emoteWidth = rendered.imageUrls.size * (emotePx + with(density) { 4.dp.toPx() })
        (textWidth + emoteWidth + with(density) { 16.dp.toPx() }).coerceAtLeast(with(density) { 24.dp.toPx() })
    }
}

/** Finds the best available lane using constant-velocity non-collision physics
 *  and anti-clustering load balancing. */
private fun findBestLane(
    now: Long,
    candidateWidthPx: Float,
    candidateSpeedPxPerMs: Float,
    laneCount: Int,
    gapPx: Float,
    screenWidthPx: Float,
    state: NekoOverlayState
): Int? {
    for (lane in 0 until laneCount) {
        val occupants = state.laneOccupants[lane] ?: continue
        occupants.removeAll { now >= it.clearAtMs }
        if (occupants.isEmpty()) {
            state.laneOccupants.remove(lane)
        }
    }

    val safeEmptyLanes = ArrayList<Int>()
    val safeOccupiedLanes = ArrayList<Pair<Int, Float>>()

    for (lane in 0 until laneCount) {
        val occupants = state.laneOccupants[lane]
        if (occupants.isNullOrEmpty()) {
            safeEmptyLanes.add(lane)
            continue
        }

        val last = occupants.last()
        val elapsed = now - last.spawnAtMs
        val distanceTraveled = elapsed * last.speedPxPerMs

        // 1. Entry clearance: has previous occupant cleared the right edge by at least gapPx?
        if (distanceTraveled < last.widthPx + gapPx) {
            continue
        }

        // 2. Overtake check: if candidate is faster than previous occupant, will it catch up before previous exits?
        if (candidateSpeedPxPerMs > last.speedPxPerMs) {
            val remainMs = last.clearAtMs - now
            val candidateTravel = remainMs * candidateSpeedPxPerMs
            if (candidateTravel > screenWidthPx + candidateWidthPx - gapPx) {
                continue
            }
        }

        safeOccupiedLanes.add(lane to distanceTraveled)
    }

    if (safeEmptyLanes.isNotEmpty()) {
        val chosen = safeEmptyLanes.minByOrNull { (it - state.nextLaneIndex + laneCount) % laneCount } ?: safeEmptyLanes.first()
        state.nextLaneIndex = (chosen + 1) % laneCount
        return chosen
    }

    if (safeOccupiedLanes.isNotEmpty()) {
        val chosen = safeOccupiedLanes.maxByOrNull { it.second }!!.first
        state.nextLaneIndex = (chosen + 1) % laneCount
        return chosen
    }

    return null
}

/** Calculates the shortest time in ms until any lane could satisfy entry clearance. */
private fun calculateNextSafeWaitMs(
    now: Long,
    laneCount: Int,
    gapPx: Float,
    state: NekoOverlayState
): Long {
    var minWaitMs = Long.MAX_VALUE
    for (lane in 0 until laneCount) {
        val occupants = state.laneOccupants[lane]
        if (occupants.isNullOrEmpty()) return 0L
        val last = occupants.last()
        val neededDistance = last.widthPx + gapPx
        val elapsed = now - last.spawnAtMs
        val distanceTraveled = elapsed * last.speedPxPerMs
        if (distanceTraveled >= neededDistance) {
            return 16L
        }
        val remainingDist = neededDistance - distanceTraveled
        if (last.speedPxPerMs > 0f) {
            val waitMs = (remainingDist / last.speedPxPerMs).toLong()
            if (waitMs in 1 until minWaitMs) {
                minWaitMs = waitMs
            }
        }
    }
    return if (minWaitMs != Long.MAX_VALUE) minWaitMs.coerceIn(16L, 250L) else 150L
}

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
    val emoteHeight = if (rendered.soloEmoteCount > 0) SOLO_EMOTE_HEIGHT else EMOTE_HEIGHT
    val inline = inlineEmotes(rendered.imageUrls, emoteHeight)

    var measuredWidthPx by remember(comment.id) { mutableFloatStateOf(comment.widthPx) }

    val x = remember(comment.id) { Animatable(screenWidthPx) }
    LaunchedEffect(comment.id, screenWidthPx) {
        val targetX = -maxOf(measuredWidthPx, comment.widthPx)
        x.animateTo(
            targetValue = targetX,
            animationSpec = tween(durationMillis = comment.durationMs, easing = LinearEasing)
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
        onTextLayout = { layout -> measuredWidthPx = layout.size.width.toFloat() },
        modifier = Modifier.graphicsLayer {
            translationX = x.value
            translationY = (laneHeightPx * comment.lane).toFloat()
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
                gridItems(page, key = { "${it.name}_${it.image}" }) { emote ->
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
                                    .size(Size(tilePx, tilePx))
                                    .precision(Precision.INEXACT)
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
    usernameClickable: Boolean = true,
    /** True on TV — see the call site's own comment. Forces every spoiler in
     *  this row to render as plain, already-visible text; [revealedSpoilers]
     *  below is only ever consulted when this is false. */
    revealSpoilers: Boolean = false
) {
    val linkColor = MaterialTheme.colorScheme.primary
    // Which of THIS message's own spoilers (by index — see ChatHtml.SPOILER_TAG)
    // have been tapped open. Keyed on msg.seq (a stable per-message id, unlike
    // the row's own recomposition) so scrolling a spoiler off-screen and back
    // doesn't forget it was revealed, but a genuinely different message next
    // in the same row slot starts fresh rather than inheriting the previous
    // message's reveal state.
    var revealedSpoilers by remember(msg.seq) { mutableStateOf(emptySet<Int>()) }
    val rendered = remember(msg.html, msg.addClass, linkColor, showEmotes, emotes, revealSpoilers, revealedSpoilers) {
        ChatHtml.render(
            msg.html, msg.addClass == "greentext", linkColor, showEmotes, emotes,
            revealSpoilers = revealSpoilers, revealedSpoilers = revealedSpoilers
        )
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
                formatTime(msg.timestamp),
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
                    // Checked ahead of links deliberately: a spoiler can wrap
                    // a link (or anything else) inside it, and revealing it
                    // is the only thing a tap on still-hidden text should
                    // do — a second tap, now that it reads normally, is what
                    // reaches whatever's underneath. Skipped outright when
                    // revealSpoilers is on (TV) — nothing is ever hidden
                    // there, so a tap should behave as if this branch didn't
                    // exist rather than eating one for no visible effect.
                    val spoilerIndex = if (!revealSpoilers) ChatHtml.spoilerAt(rendered.text, offset) else null
                    when {
                        emote != null -> onEmoteClick(emote)
                        spoilerIndex != null -> revealedSpoilers =
                            if (spoilerIndex in revealedSpoilers) revealedSpoilers - spoilerIndex
                            else revealedSpoilers + spoilerIndex
                        else -> ChatHtml.linkAt(rendered.text, offset)?.let(onLinkClick)
                    }
                }
            }
        )
    }
}

@Composable
fun PlaylistPanel(
    items: ImmutableList<PlaylistItem>,
    currentUid: Int,
    canControl: Boolean,
    onJumpTo: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Mirrors the "stay in sync" setting (Prefs.syncEnabled). True is the
     *  ordinary channel-wide behavior below, unchanged. False is what
     *  unlocks personal picking instead — see [onPersonalPick]'s own doc
     *  comment for why it takes over the tap gesture entirely, even for a
     *  moderator who could otherwise jumpTo. */
    syncEnabled: Boolean = true,
    /** The uid personal picking has loaded right now (see
     *  ChannelViewModel.pickPersonal) — independent of [currentUid], which
     *  keeps tracking the channel's own real current item the whole time a
     *  personal pick is active, so switching sync back on can snap straight
     *  back to it. -1 while nothing is personally picked. */
    personalPickUid: Int = -1,
    /** Fires instead of [onJumpTo] when the user taps a row while sync is
     *  off. jumpTo is a moderator action that changes the item for
     *  EVERYONE on the channel — the whole point of turning sync off is to
     *  browse without affecting anyone else, so while it's off a tap always
     *  means "just for me", even for a moderator who could otherwise
     *  jumpTo. Never called for a row [MediaTypes.canResolveIndependently]
     *  rejects (see the disabled/greyed treatment below) — those items only
     *  ever get real playable data (direct sources, an embed URL) when
     *  they're the channel's actual current item, which a personal pick by
     *  definition isn't. */
    onPersonalPick: (PlaylistItem) -> Unit = {}
) {
    if (items.isEmpty()) {
        EmptyPanel("The playlist is empty, or you do not have permission to see it.", modifier)
        return
    }

    // Phone counterpart to TvPlaylistView's own search field — same
    // title-substring filter, just without the D-pad focus plumbing that
    // view needs and this one, being touch-driven, doesn't.
    var query by remember { mutableStateOf("") }
    val filtered = remember(items, query) {
        if (query.isBlank()) items
        else items.filter { it.title.contains(query, ignoreCase = true) }
    }

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Search playlist") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )

        if (filtered.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "No matches for \"$query\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                // uid is already unique and stable once assigned — appending idx
                // on top of it defeated Compose's recomposition-avoidance, since
                // any playlist mutation shifts every downstream idx and so
                // changes every key below it, forcing a full rebuild instead of
                // reusing existing item composition state. uid < 0 (no id yet)
                // is the one case idx is still needed for.
                itemsIndexed(filtered, key = { idx, item -> if (item.uid >= 0) "item_${item.uid}" else "pos_${idx}_${item.mediaId}" }) { _, item ->
                    val personallyResolvable = MediaTypes.canResolveIndependently(item.type)
                    val isCurrent = if (syncEnabled) item.uid == currentUid else item.uid == personalPickUid
                    val rowEnabled = if (syncEnabled) canControl else personallyResolvable
                    Row(
                        Modifier.fillMaxWidth()
                            .background(
                                if (isCurrent) MaterialTheme.colorScheme.surfaceContainerHigh
                                else Color.Transparent
                            )
                            // Greyed out, not hidden or error-on-tap — items that
                            // can't be resolved from playlist data alone (custom
                            // embeds like an 8chan/kinoplex stream, any "direct
                            // source" item) simply aren't tappable while sync is
                            // off, same as a row a non-moderator can't jumpTo today.
                            .alpha(if (rowEnabled) 1f else 0.4f)
                            .clickable(enabled = rowEnabled) {
                                if (syncEnabled) onJumpTo(item.uid) else onPersonalPick(item)
                            }
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
                                    if (!syncEnabled && !personallyResolvable) { append(" · unavailable unsynced") }
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
                        progress = { fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                    )
                }
            }
        }
    }
}

@Composable
fun UsersPanel(users: ImmutableList<ChannelUser>, modifier: Modifier = Modifier) {
    if (users.isEmpty()) {
        EmptyPanel("No users listed.", modifier)
        return
    }
    val sorted = remember(users) {
        users.distinctBy { it.name }.sortedWith(compareByDescending<ChannelUser> { it.rank }.thenBy { it.name.lowercase() })
    }

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
