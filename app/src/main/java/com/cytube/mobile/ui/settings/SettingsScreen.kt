package com.cytube.mobile.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cytube.mobile.data.CompatMode
import com.cytube.mobile.data.Settings
import com.cytube.mobile.data.SettingsStore
import com.cytube.mobile.data.ThemeMode
import com.cytube.mobile.ui.channel.openInBrowser
import com.cytube.mobile.ui.defaultSyncAccuracy
import com.cytube.mobile.ui.isTvDevice
import com.cytube.mobile.ui.theme.CyTubePageTheme
import com.cytube.mobile.ui.theme.Ma
import com.cytube.mobile.ui.theme.MaSectionLabel
import com.cytube.mobile.ui.theme.MaTopBar
import com.cytube.mobile.ui.theme.maFaint
import com.cytube.mobile.ui.theme.maPageBackground
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    // Every other setting works the same on TV as on phone; these are the
    // explicit exceptions, hidden here: Appearance (the TV screens keep
    // their own dark palette — see MainActivity), Ambient glow (never
    // rendered on TV — see ChannelScreen) and PiP (no home-screen window to
    // float into).
    val isTv = remember { isTvDevice(context) }
    var settings by remember { mutableStateOf(Settings(syncAccuracy = defaultSyncAccuracy(context))) }

    LaunchedEffect(Unit) { store.settings.collect { settings = it } }

    // The same quiet page as home (see Ma.kt): paper and ink on light, with
    // its grain; pure black on dark.
    CyTubePageTheme {
    Scaffold(
        modifier = Modifier.fillMaxSize().then(maPageBackground()),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = { MaTopBar("Settings", onBack) }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
        ) {
            if (!isTv) {
                MaSectionLabel("Appearance")

                // Drives CyTubeSettingsTheme for home, this screen and the
                // account screen (see MainActivity) — System default just
                // follows the phone's own light/dark setting.
                ThemeMode.entries.forEach { mode ->
                    Row(
                        Modifier.fillMaxWidth().padding(start = RadioStart, end = Ma.MarginEnd),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = settings.themeMode == mode,
                            onClick = { scope.launch { store.setThemeMode(mode) } }
                        )
                        Text(
                            when (mode) {
                                ThemeMode.SYSTEM -> "System default"
                                ThemeMode.LIGHT -> "Light"
                                ThemeMode.DARK -> "Dark"
                                ThemeMode.COT -> "cot"
                            }
                        )
                    }
                }
            }

            MaSectionLabel("Playback")

            SwitchRow(
                title = "Stay in sync",
                subtitle = "Follow the channel's clock. Turn off to watch at your own pace.",
                checked = settings.syncEnabled
            ) { scope.launch { store.setSync(it) } }

            Column(Modifier.padding(start = Ma.MarginStart, end = Ma.MarginEnd, top = 8.dp, bottom = 8.dp)) {
                Text("Sync tolerance: ${settings.syncAccuracy.roundToInt()}s",
                    style = MaterialTheme.typography.bodyLarge)
                Text(
                    "How far the player may drift before it's corrected — by briefly " +
                        "speeding up or slowing down, or a seek if it's far out. Lower is " +
                        "tighter but corrects more often on a slow connection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    // Material3's Slider takes Up/Down as well as Left/Right
                    // to change its value, so a TV remote's D-pad could never
                    // leave it. Up/Down move focus instead; Left/Right still
                    // adjust.
                    modifier = Modifier.onPreviewKeyEvent { event ->
                        val direction = when (event.key) {
                            Key.DirectionUp -> FocusDirection.Up
                            Key.DirectionDown -> FocusDirection.Down
                            else -> return@onPreviewKeyEvent false
                        }
                        if (event.type == KeyEventType.KeyDown) focusManager.moveFocus(direction)
                        true
                    },
                    value = settings.syncAccuracy.toFloat(),
                    onValueChange = { scope.launch { store.setAccuracy(it.toDouble()) } },
                    valueRange = 1f..10f,
                    steps = 8
                )
            }

            if (!isTv) {
                SwitchRow(
                    title = "Picture-in-picture",
                    subtitle = "Keep the video playing in a small floating window when " +
                        "you leave the app. Off by default — turn it on to try it out.",
                    checked = settings.pipEnabled
                ) { scope.launch { store.setPip(it) } }

                SwitchRow(
                    title = "Ambient glow",
                    subtitle = "A soft glow behind the video, colored to match what's playing.",
                    checked = settings.ambientGlowEnabled
                ) { scope.launch { store.setAmbientGlow(it) } }
            }

            MaSectionLabel("Compatibility")

            Text(
                "Default mode for channels you have not set individually.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Ma.MarginStart, end = Ma.MarginEnd, bottom = 8.dp)
            )
            CompatMode.entries.forEach { mode ->
                Row(
                    Modifier.fillMaxWidth().padding(start = RadioStart, end = Ma.MarginEnd),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = settings.compatMode == mode,
                        onClick = { scope.launch { store.setCompat(mode) } }
                    )
                    Text(mode.name.lowercase().replaceFirstChar { it.uppercase() })
                }
            }

            MaSectionLabel("Chat")

            SwitchRow(
                title = "Show emotes",
                subtitle = "Render channel emotes inline instead of their names.",
                checked = settings.showEmotes
            ) { scope.launch { store.setEmotes(it) } }

            if (!isTv) {
                SwitchRow(
                    title = "Group messages",
                    subtitle = "Groups each user's chat messages together under their name.",
                    checked = settings.groupChat
                ) { scope.launch { store.setGroupChat(it) } }
            }

            MaSectionLabel("Support")

            // Plain rows that open the browser, not boxed buttons.
            LinkRow("Buy Hostu a dunkaccino! ❤️") { openInBrowser(context, "https://ko-fi.com/hostu") }
            LinkRow("View source on GitHub") { openInBrowser(context, "https://github.com/Hostu404/cytube-apk") }

            Spacer(Modifier.height(32.dp))
        }
    }
    }
}

/** Start padding for a radio row: the page's 32dp margin, less the 14dp
 *  a RadioButton's touch area leaves around its circle. */
private val RadioStart = 18.dp

/** A row that opens a link: the text, and a small grey arrow on the right. */
@Composable
private fun LinkRow(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = Ma.MarginStart, end = Ma.MarginEnd, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = maFaint(),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(start = Ma.MarginStart, end = Ma.MarginEnd, top = 16.dp, bottom = 16.dp),
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
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
