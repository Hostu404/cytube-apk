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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.cytube.mobile.data.LIGHTS_DOWN_DIM_RANGE
import com.cytube.mobile.data.NICO_SPEED_RANGE
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
    // their own dark palette — see MainActivity — and Prince is
    // phone only), PiP (no home-screen window to float into), grouped chat
    // messages and the ignored users list.
    val isTv = remember { isTvDevice(context) }
    var settings by remember { mutableStateOf(Settings(syncAccuracy = defaultSyncAccuracy(context))) }

    LaunchedEffect(Unit) { store.settings.collect { settings = it } }
    var ignoredUsers by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) { store.ignoredUsers.collect { ignoredUsers = it.sortedBy { n -> n.lowercase() } } }

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
                            }
                        )
                    }
                }

                // Prince in the home search bar (see Prince), on whichever
                // theme is picked above.
                SwitchRow(title = "cot", checked = settings.cot) {
                    scope.launch { store.setCot(it) }
                }

                SwitchRow(
                    title = "Channel backgrounds",
                    subtitle = "Shows a channel's own background picture behind the top of its page.",
                    checked = settings.channelBackgrounds
                ) { scope.launch { store.setChannelBackgrounds(it) } }
            }

            MaSectionLabel("Playback")

            SwitchRow(
                title = "Stay in sync",
                subtitle = "Follow the channel's clock. Turn off to watch at your own pace.",
                checked = settings.syncEnabled
            ) { scope.launch { store.setSync(it) } }

            SliderRow(
                label = { "Sync tolerance: ${it.roundToInt()}s" },
                subtitle = "How far the player may drift before it's corrected — by briefly " +
                    "speeding up or slowing down, or a seek if it's far out. Lower is " +
                    "tighter but corrects more often on a slow connection.",
                value = settings.syncAccuracy.toFloat(),
                range = 1f..10f,
                steps = 8
            ) { scope.launch { store.setAccuracy(it.toDouble()) } }

            if (!isTv) {
                SwitchRow(
                    title = "Picture-in-picture",
                    subtitle = "Keep the video playing in a small floating window when " +
                        "you leave the app. Off by default — turn it on to try it out.",
                    checked = settings.pipEnabled
                ) { scope.launch { store.setPip(it) } }
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

            // Needs emotes shown at all, so it's only offered while they are.
            if (settings.showEmotes) {
                SwitchRow(
                    title = "Nico chat: emotes only",
                    subtitle = "Comments flying over the video show just their emotes. " +
                        "Messages without any don't fly.",
                    checked = settings.nicoEmotesOnly
                ) { scope.launch { store.setNicoEmotesOnly(it) } }
            }

            SliderRow(
                label = { "Nico speed: ${java.math.BigDecimal(it.toDouble()).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()}×" },
                value = settings.nicoSpeed,
                range = NICO_SPEED_RANGE,
                steps = 5                                          // 0.5, 0.75 … 2
            ) { scope.launch { store.setNicoSpeed(it) } }

            // Lights down is phone only.
            if (!isTv) {
                SliderRow(
                    label = { "Lights down: ${(it * 100).roundToInt()}% dark" },
                    value = settings.lightsDownDim,
                    range = LIGHTS_DOWN_DIM_RANGE,
                    steps = 9                                      // 50%, 55% … 100%
                ) { scope.launch { store.setLightsDownDim(it) } }
            }

            if (!isTv) {
                SwitchRow(
                    title = "Group messages",
                    subtitle = "Groups each user's chat messages together under their name.",
                    checked = settings.groupChat
                ) { scope.launch { store.setGroupChat(it) } }
                SwitchRow(
                    title = "Message recall",
                    subtitle = "An up arrow in the empty message box brings back the last four messages you sent there.",
                    checked = settings.messageRecall
                ) { scope.launch { store.setMessageRecall(it) } }
            }

            if (!isTv) {
                MaSectionLabel("Ignored users")

                // Ignoring happens from a channel's user list; this is where
                // someone who's no longer in any channel can be let back in.
                Text(
                    if (ignoredUsers.isEmpty()) "Nobody yet."
                    else "Their chat and private messages are hidden, in every channel. " +
                        "Only you can tell.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Ma.MarginStart, end = Ma.MarginEnd, bottom = 8.dp)
                )
                ignoredUsers.forEach { name ->
                    Row(
                        Modifier.fillMaxWidth().padding(start = Ma.MarginStart, end = Ma.MarginEndBeforeButton),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { scope.launch { store.setIgnored(name, false) } }) {
                            Text("Unignore")
                        }
                    }
                }
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

/**
 * A setting picked on a slider, its value in its [label]. The handle and the label follow the
 * finger, and it's saved ([onChange]) once it's let go, not at every step on the way; or, however it
 * was moved (TalkBack's adjustments aren't "let go" of), once it's been still a moment. On TV the
 * remote's Left and Right change it (saved as each press is let go); Up and Down move on: Material3's
 * Slider would take Up and Down too, and a D-pad could never leave it.
 */
@Composable
private fun SliderRow(
    label: (Float) -> String,
    subtitle: String? = null,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit
) {
    val focusManager = LocalFocusManager.current
    var shown by remember(value) { mutableFloatStateOf(value) }
    val save by rememberUpdatedState(onChange)
    LaunchedEffect(shown) {
        if (shown == value) return@LaunchedEffect
        delay(SLIDER_SAVE_AFTER_MS)
        save(shown)
    }
    Column(Modifier.padding(start = Ma.MarginStart, end = Ma.MarginEnd, top = 8.dp, bottom = 8.dp)) {
        Text(label(shown), style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            modifier = Modifier.onPreviewKeyEvent { event ->
                when (event.key) {
                    Key.DirectionUp, Key.DirectionDown -> {
                        if (event.type == KeyEventType.KeyDown) {
                            focusManager.moveFocus(if (event.key == Key.DirectionUp) FocusDirection.Up else FocusDirection.Down)
                        }
                        true
                    }
                    Key.DirectionLeft, Key.DirectionRight -> {
                        if (event.type == KeyEventType.KeyUp) onChange(shown)
                        false
                    }
                    else -> false
                }
            },
            value = shown,
            onValueChange = { shown = it },
            onValueChangeFinished = { onChange(shown) },
            valueRange = range,
            steps = steps
        )
    }
}

/** How long a slider's been still before it's saved anyway (see SliderRow). */
private const val SLIDER_SAVE_AFTER_MS = 400L

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(start = Ma.MarginStart, end = Ma.MarginEnd, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
