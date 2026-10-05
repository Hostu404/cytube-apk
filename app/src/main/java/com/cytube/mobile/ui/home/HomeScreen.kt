package com.cytube.mobile.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cytube.mobile.BuildConfig
import com.cytube.mobile.data.ChannelIndexRepository.PublicChannel
import com.cytube.mobile.ui.isTvDevice
import com.cytube.mobile.ui.theme.CyTubePageTheme
import com.cytube.mobile.ui.theme.Ma
import com.cytube.mobile.ui.theme.MaSectionLabel
import com.cytube.mobile.ui.theme.MaTextField
import com.cytube.mobile.ui.theme.maFaint
import com.cytube.mobile.ui.theme.maIsLight
import com.cytube.mobile.ui.theme.maPageBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenChannel: (String) -> Unit,
    onOpenLogin: () -> Unit,
    onOpenSettings: () -> Unit,
    /** The Cot theme: a cat sits in the search bar (see CotCat). */
    showCat: Boolean = false,
    vm: HomeViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val isTv = remember { isTvDevice(context) }
    val focusManager = LocalFocusManager.current
    val initialFocusRequester = remember { FocusRequester() }
    val catShown = showCat && !isTv
    val cat = rememberCotCatState()

    // Runs each time Home enters composition -- which, under
    // Navigation-Compose, is every time the homepage is navigated to:
    // leaving "home" for a channel/login/settings screen tears this
    // composable down, and coming back rebuilds it fresh. (Not when the app
    // returns from the background to Home; the list is refreshed next time
    // Home is navigated to, or with the Refresh button.) That makes this
    // the one place responsible for keeping the public channel list current -- see
    // HomeViewModel's own init{} doc comment for why refresh() was moved
    // here instead of living there. vm.refresh() (no force) still respects
    // ChannelIndexRepository's 60s cache, so bouncing in and out of a
    // channel doesn't hammer the scrape every time -- the manual Refresh
    // button (force = true) is what's left for "no, really, right now".
    LaunchedEffect(Unit) {
        if (isTv) {
            runCatching { initialFocusRequester.requestFocus() }
        } else {
            focusManager.clearFocus()
        }
        vm.refreshSession()
        vm.refresh()
    }

    // The quieter page palette and layout (see Ma.kt): white on light,
    // pure black on dark for OLED.
    CyTubePageTheme {
    val colors = MaterialTheme.colorScheme
    Scaffold(
        // The cat watches fingers anywhere on the page (see CotCat).
        modifier = Modifier.fillMaxSize()
            .then(maPageBackground())
            .then(if (catShown) Modifier.cotCatWatchesFingers(cat) else Modifier),
        // The page's own background (and grain) is drawn above, so the
        // Scaffold and top bar are see-through over it.
        containerColor = Color.Transparent,
        // Set explicitly: for a transparent container Scaffold can't work
        // out a text colour of its own.
        contentColor = colors.onBackground,
        topBar = {
            // A plain TopAppBar, not LargeTopAppBar — the large variant's
            // whole point is a tall, expanded title area meant to collapse
            // as the user scrolls, which just left a big empty gap above
            // "CyTube APK" here since nothing collapses it. This one sits
            // at the standard ~64dp height, so the title and everything
            // below it (search, favorites, the list) all sit higher.
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = {
                    // 16dp of the bar's own inset + 12 = the page's 28dp
                    // left margin.
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.Bottom) {
                        Text("CyTube APK", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            BuildConfig.VERSION_NAME,
                            style = MaterialTheme.typography.labelSmall,
                            color = maFaint(),
                            modifier = Modifier.padding(bottom = 3.dp)
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = onOpenLogin,
                        modifier = Modifier.focusRequester(initialFocusRequester)
                    ) {
                        Text(state.loggedInAs ?: "Log in", maxLines = 1)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = colors.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
            )
        },
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            // The navigation bar is see-through (MainActivity), so the list
            // can scroll under it, but ends clear of its buttons.
            contentPadding = PaddingValues(
                bottom = 32.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            )
        ) {
            item {
                SearchField(
                    query = state.query,
                    onQueryChange = vm::setQuery,
                    onSubmit = { state.submitName?.let(onOpenChannel) },
                    isTv = isTv,
                    cat = cat.takeIf { catShown }
                )
            }

            state.directEntryName?.let { name ->
                item {
                    DirectEntryCard(name = name, onClick = { onOpenChannel(name) })
                }
            }

            if (state.query.isBlank()) {
                if (state.favourites.isNotEmpty()) {
                    item { MaSectionLabel("Favorites") }
                    items(state.favourites, key = { "fav-$it" }) { name ->
                        SimpleChannelRow(
                            name = name,
                            isFavourite = true,
                            onClick = { onOpenChannel(name) },
                            onToggleFavourite = { vm.toggleFavourite(name) }
                        )
                    }
                }

                val recents = state.recents.filterNot { it in state.favourites }
                if (recents.isNotEmpty()) {
                    // The only section a user might want to reset: favorites
                    // are each removed individually, and the public list
                    // refreshes itself.
                    item {
                        MaSectionLabel("Recent") {
                            TextButton(
                                onClick = vm::clearRecents,
                                colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant)
                            ) {
                                Text("Clear".uppercase(), style = Ma.LabelStyle)
                            }
                        }
                    }
                    items(recents, key = { "rec-$it" }) { name ->
                        SimpleChannelRow(
                            name = name,
                            isFavourite = false,
                            onClick = { onOpenChannel(name) },
                            onToggleFavourite = { vm.toggleFavourite(name) }
                        )
                    }
                }
            }

            item {
                MaSectionLabel("Public channels") {
                    // Small and grey, spinner included: the cat (on cot) is
                    // the only thing on this page that should draw the eye
                    // by moving.
                    IconButton(onClick = { vm.refresh(force = true) }, enabled = !state.loading) {
                        if (state.loading) {
                            CircularProgressIndicator(
                                Modifier.size(16.dp),
                                color = maFaint(),
                                strokeWidth = 1.5.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                tint = maFaint(),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            if (state.loading && state.filtered.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            Modifier.size(20.dp),
                            color = maFaint(),
                            strokeWidth = 1.5.dp
                        )
                    }
                }
            } else if (state.indexUnavailable && state.filtered.isEmpty()) {
                item { IndexUnavailableNote() }
            } else {
                items(state.filtered, key = { "pub-${it.name}" }) { channel ->
                    PublicChannelRow(
                        channel = channel,
                        isFavourite = channel.name in state.favourites,
                        onClick = { onOpenChannel(channel.name) },
                        onToggleFavourite = { vm.toggleFavourite(channel.name) }
                    )
                }
                if (state.filtered.isEmpty() && state.query.isNotBlank()) {
                    item { EmptyResults(state.query) }
                }
            }
        }
    }
    }
}

/** Channel names in the lists. */
@Composable
private fun nameStyle() = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp)

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    isTv: Boolean,
    /** Set with the Cot theme: the cat that sits in the bar. */
    cat: CotCatState? = null
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val colors = MaterialTheme.colorScheme
    MaTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = "Search channels…",
        // On TV the on-screen keyboard covers the screen and takes the
        // remote's D-pad itself, so after typing its action key is the way
        // out: it closes the keyboard and moves down to the results (the
        // "Join /name" card first, when the name is a valid channel). Before,
        // it tried to open the channel straight away, and when it couldn't
        // the keyboard just stayed up, leaving no way out but Back.
        keyboardOptions = KeyboardOptions(imeAction = if (isTv) ImeAction.Search else ImeAction.Go),
        keyboardActions = KeyboardActions(
            onGo = { onSubmit() },
            onSearch = {
                keyboard?.hide()
                focusManager.moveFocus(FocusDirection.Down)
            }
        ),
        leading = {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        },
        trailing = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear", tint = colors.onSurfaceVariant)
                }
            }
            if (cat != null) {
                CotCat(
                    state = cat,
                    modifier = Modifier.padding(start = 8.dp).size(width = 30.dp, height = 36.dp),
                    wakeKey = query
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Ma.MarginStart, end = Ma.MarginEnd, top = 16.dp, bottom = 8.dp)
            .then(
                if (isTv) {
                    // Focusing this field on TV pops the on-screen keyboard, and
                    // a bare D-pad Down press here was landing in the handoff
                    // between that IME window and the activity's own window —
                    // an "Input dispatching timed out (Application does not have
                    // a focused window)" ANR that reproduced every time on the
                    // TV emulator. onPreviewKeyEvent sees Down before the field
                    // (and the IME it triggers) ever gets a chance to touch it,
                    // so it's consumed here and turned into an explicit Compose
                    // focus move instead — no IME window transition involved.
                    // The keyboard is closed first, so focus doesn't move on
                    // with it still sitting over the list.
                    Modifier.onPreviewKeyEvent { event ->
                        if (event.key != Key.DirectionDown) {
                            false
                        } else {
                            if (event.type == KeyEventType.KeyDown) {
                                keyboard?.hide()
                                runCatching { focusManager.moveFocus(FocusDirection.Down) }
                            }
                            true
                        }
                    }
                } else {
                    Modifier
                }
            )
    )
}

/** "Join /name" for a typed channel name, as a raised card. Its colour is
 *  set here because the page's own surfaces are pure white or black. */
@Composable
private fun DirectEntryCard(name: String, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (maIsLight()) Color(0xFFF7F2FA) else Color(0xFF1D1B20)
        ),
        modifier = Modifier.fillMaxWidth().padding(start = Ma.MarginStart - 8.dp, end = Ma.MarginEnd, top = 8.dp, bottom = 8.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text("Join /$name", style = nameStyle())
                Text(
                    "Open this channel directly, whether or not it is listed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PublicChannelRow(
    channel: PublicChannel,
    isFavourite: Boolean,
    onClick: () -> Unit,
    onToggleFavourite: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(start = Ma.MarginStart, end = Ma.MarginEndBeforeButton, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                channel.pageTitle,
                style = nameStyle(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (channel.nowPlaying.isNotBlank()) {
                Text(
                    channel.nowPlaying,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        // Just the number: it reads as a viewer count without an icon.
        Text(
            "${channel.userCount}",
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp)
        )
        FavoriteButton(isFavourite, onToggleFavourite)
    }
}

@Composable
private fun SimpleChannelRow(
    name: String,
    isFavourite: Boolean,
    onClick: () -> Unit,
    onToggleFavourite: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(start = Ma.MarginStart, end = Ma.MarginEndBeforeButton, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("/$name", style = nameStyle(), modifier = Modifier.weight(1f))
        FavoriteButton(isFavourite, onToggleFavourite)
    }
}

/** Blue and filled for a favorite (blue marks what's yours), otherwise a
 *  quiet grey outline. */
@Composable
private fun FavoriteButton(isFavourite: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            if (isFavourite) Icons.Default.Star else Icons.Outlined.StarBorder,
            contentDescription = "Favorite",
            tint = if (isFavourite) MaterialTheme.colorScheme.primary else maFaint(),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun IndexUnavailableNote() {
    Column(Modifier.fillMaxWidth().padding(start = Ma.MarginStart, end = Ma.MarginEnd, top = 16.dp, bottom = 16.dp)) {
        Text("Channel list unavailable", style = nameStyle())
        Spacer(Modifier.height(8.dp))
        Text(
            "CyTube has no API for the public list, so it is read from the homepage. " +
                "You can still open any channel by typing its name above.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyResults(query: String) {
    Column(Modifier.fillMaxWidth().padding(start = Ma.MarginStart, end = Ma.MarginEnd, top = 16.dp, bottom = 16.dp)) {
        Text("No listed channel matches \"$query\"", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Unlisted channels do not appear here — enter the exact name to join one.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
