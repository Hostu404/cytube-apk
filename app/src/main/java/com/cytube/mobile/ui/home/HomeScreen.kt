package com.cytube.mobile.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cytube.mobile.BuildConfig
import com.cytube.mobile.data.ChannelIndexRepository.PublicChannel
import com.cytube.mobile.ui.isTvDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenChannel: (String) -> Unit,
    onOpenLogin: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: HomeViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val isTv = remember { isTvDevice(context) }

    LaunchedEffect(Unit) { vm.refreshSession() }

    Scaffold(
        topBar = {
            // A plain TopAppBar, not LargeTopAppBar — the large variant's
            // whole point is a tall, expanded title area meant to collapse
            // as the user scrolls, which just left a big empty gap above
            // "CyTube APK" here since nothing collapses it. This one sits
            // at the standard ~64dp height, so the title and everything
            // below it (search, favourites, the list) all sit higher.
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("CyTube APK", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 3.dp)
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onOpenLogin) {
                        Text(state.loggedInAs ?: "Log in", maxLines = 1)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                SearchField(
                    query = state.query,
                    onQueryChange = vm::setQuery,
                    onSubmit = { state.directEntryName?.let(onOpenChannel) },
                    isTv = isTv
                )
            }

            state.directEntryName?.let { name ->
                item {
                    DirectEntryCard(name = name, onClick = { onOpenChannel(name) })
                }
            }

            if (state.query.isBlank()) {
                if (state.favourites.isNotEmpty()) {
                    item { SectionHeader("Favourites", Icons.Default.Star) }
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
                    item {
                        RecentSectionHeader(onClear = vm::clearRecents)
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

                if (state.favourites.isNotEmpty() || recents.isNotEmpty()) {
                    item {
                        HorizontalDivider(
                            Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                        )
                    }
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Public,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Public channels",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { vm.refresh(force = true) }, enabled = !state.loading) {
                        if (state.loading) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                }
            }

            if (state.loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (state.indexUnavailable) {
                item { IndexUnavailableNote() }
            } else {
                items(state.filtered, key = { it.name }) { channel ->
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

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    isTv: Boolean
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search channels…") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear")
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onSubmit() }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
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
                    Modifier.onPreviewKeyEvent { event ->
                        if (event.key != Key.DirectionDown) {
                            false
                        } else {
                            if (event.type == KeyEventType.KeyDown) {
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

@Composable
private fun SectionHeader(text: String, icon: ImageVector) {
    Row(
        Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Recent gets a "Clear" action rather than reusing [SectionHeader] — it's
 *  the only section whose list a user might want to reset (favourites are
 *  each removed individually; the public list refreshes itself). */
@Composable
private fun RecentSectionHeader(onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Recent",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onClear) { Text("Clear") }
    }
}

@Composable
private fun DirectEntryCard(name: String, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(Icons.Default.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text("Join /$name", style = MaterialTheme.typography.titleMedium)
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
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                channel.pageTitle,
                style = MaterialTheme.typography.titleMedium,
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
        ViewerBadge(channel.userCount)
        IconButton(onClick = onToggleFavourite) {
            Icon(
                if (isFavourite) Icons.Default.Star else Icons.Outlined.StarBorder,
                contentDescription = "Favourite",
                tint = if (isFavourite) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("/$name", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onToggleFavourite) {
            Icon(
                if (isFavourite) Icons.Default.Star else Icons.Outlined.StarBorder,
                contentDescription = "Favourite",
                tint = if (isFavourite) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ViewerBadge(count: Int) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            Icons.Default.Person,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("$count", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun IndexUnavailableNote() {
    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Text("Channel list unavailable", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
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
    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Text("No listed channel matches \"$query\"", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Unlisted channels do not appear here — enter the exact name to join one.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
