package com.cytube.mobile.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cytube.mobile.data.CompatMode
import com.cytube.mobile.data.Settings
import com.cytube.mobile.data.SettingsStore
import com.cytube.mobile.ui.channel.openInBrowser
import com.cytube.mobile.ui.isTvDevice
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(Settings()) }
    // Every other setting works the same on TV as on phone; these two are
    // the sole, explicit exceptions (Ambient glow is never rendered on TV —
    // see ChannelScreen — and PiP has no meaning without a home-screen
    // window to float into), so they're the only rows hidden here.
    val isTv = remember { isTvDevice(context) }

    LaunchedEffect(Unit) { store.settings.collect { settings = it } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
        ) {
            SectionTitle("Playback")

            SwitchRow(
                title = "Stay in sync",
                subtitle = "Follow the channel's clock. Turn off to watch at your own pace.",
                checked = settings.syncEnabled
            ) { scope.launch { store.setSync(it) } }

            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("Sync tolerance: ${settings.syncAccuracy.roundToInt()}s",
                    style = MaterialTheme.typography.bodyMedium)
                Text(
                    "How far the player may drift before it seeks. Lower is tighter but " +
                        "seeks more often on a slow connection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = settings.syncAccuracy.toFloat(),
                    onValueChange = { scope.launch { store.setAccuracy(it.toDouble()) } },
                    valueRange = 1f..10f,
                    steps = 8
                )
            }

            if (!isTv) {
                SwitchRow(
                    title = "Picture-in-picture (Experimental)",
                    subtitle = "Keep the video playing when you leave the app. Still " +
                        "rough — expanding back out of it can misbehave or crash. " +
                        "Off by default until that's solid.",
                    checked = settings.pipEnabled
                ) { scope.launch { store.setPip(it) } }

                SwitchRow(
                    title = "Ambient glow",
                    subtitle = "A soft glow behind the video, colored to match what's playing.",
                    checked = settings.ambientGlowEnabled
                ) { scope.launch { store.setAmbientGlow(it) } }
            }

            SectionTitle("Compatibility")

            Text(
                "Default mode for channels you have not set individually.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
            CompatMode.entries.forEach { mode ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = settings.compatMode == mode,
                        onClick = { scope.launch { store.setCompat(mode) } }
                    )
                    Text(mode.name.lowercase().replaceFirstChar { it.uppercase() })
                }
            }

            SectionTitle("Chat")

            SwitchRow(
                title = "Show emotes",
                subtitle = "Render channel emotes inline instead of their names.",
                checked = settings.showEmotes
            ) { scope.launch { store.setEmotes(it) } }

            SectionTitle("Support")

            OutlinedButton(
                onClick = { openInBrowser(context, "https://ko-fi.com/hostu") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text("☕ Buy Hostu a dunkaccino! ❤️")
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 4.dp)
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
