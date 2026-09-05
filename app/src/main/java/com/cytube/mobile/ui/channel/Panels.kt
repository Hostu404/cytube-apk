package com.cytube.mobile.ui.channel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
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
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
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
 * the practical ceiling before rows start growing.
 */
private const val EMOTE_HEIGHT = 28f

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
private fun inlineEmotes(urls: List<String>): Map<String, InlineTextContent> {
    if (urls.isEmpty()) return emptyMap()
    val context = LocalContext.current
    val density = LocalDensity.current
    val heightPx = with(density) { EMOTE_HEIGHT.sp.roundToPx() }.coerceAtLeast(1)
    return urls.distinct().associateWith { url ->
        // Until the real aspect ratio is known a square is the least-wrong
        // guess; once loaded the true ratio is reused for the whole session so
        // wide emotes stop being squashed.
        val ratio = (EmoteAspect.ratios[url] ?: 1f).coerceIn(0.2f, 6f)
        val widthPx = (heightPx * ratio).toInt().coerceAtLeast(1)
        InlineTextContent(
            Placeholder(
                width = (EMOTE_HEIGHT * ratio).sp,
                height = EMOTE_HEIGHT.sp,
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
    modifier: Modifier = Modifier
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

    // Keyed on the last message's own id, not messages.size: once the chat
    // buffer is full (MAX_CHAT_MESSAGES), appending trims the oldest message
    // at the same rate, so size never changes again and a key of messages.size
    // would silently stop autoscrolling for the rest of the session.
    LaunchedEffect(messages.lastOrNull()?.seq) {
        if (messages.isNotEmpty() && pinnedToBottom) {
            listState.scrollToItem(messages.lastIndex)
        }
    }

    Column(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
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
                    onEmoteClick = { code -> draft = draft.insertAtCursor(code) }
                )
            }
        }

        HorizontalDivider()

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (emotes.all.isNotEmpty()) {
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
                modifier = Modifier.weight(1f)
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
    insertAtCursor(if (text.isBlank()) "$username: " else "$username: ")

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
    onEmoteClick: (String) -> Unit
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val rendered = remember(msg.html, msg.addClass, linkColor, showEmotes, emotes) {
        ChatHtml.render(msg.html, msg.addClass == "greentext", linkColor, showEmotes, emotes)
    }
    val inline = inlineEmotes(rendered.imageUrls)

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
                // Only the name is clickable, not the row.
                modifier = Modifier.clickable { onUsernameClick(msg.username) }
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
                detectTapGestures { pos ->
                    val l = layout ?: return@detectTapGestures
                    val offset = l.getOffsetForPosition(pos)
                    // Emotes and links never overlap, but check emotes first
                    // since that's the more specific hit.
                    val emote = ChatHtml.emoteAt(rendered.text, offset)
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
