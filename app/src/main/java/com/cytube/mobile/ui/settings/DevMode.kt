package com.cytube.mobile.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cytube.mobile.ui.theme.Ma

/*
 * Developer mode, at the bottom of Settings (phone only): the purple guy's switch,
 * hidden. Till it's found, the bottom of Settings is just the app's
 * version, as apps have it; tapped [TAPS_TO_DEV] times it says, each time,
 * how many more it'll take, in Android's own words (its Settings' build
 * number toasts, word for word), and on the last it becomes
 * Developer mode: his switch, on (red, with its padlock; see PurpleGuyPadlock),
 * and he swings in. Turned off, it stays there, as Developer mode.
 */

/** Developer mode's name: on its switch, in place of the version. */
const val DEV_MODE_NAME = "Developer mode"

/** How many taps on the version it takes to find Developer mode. */
internal const val TAPS_TO_DEV = 3

/** The taps on the version. */
@Stable
internal class DevModeTaps {
    var taps by mutableIntStateOf(0)
        private set

    /** Tapped enough: Developer mode's found (taps from now on do nothing). */
    val found get() = taps >= TAPS_TO_DEV

    /** A tap on the version: how many more it'll take to be a dev, 0 if
     *  that was the last. */
    fun tap(): Int {
        if (taps < TAPS_TO_DEV) taps++
        return TAPS_TO_DEV - taps
    }
}

/** What a tap on the version says, with [left] taps to go: as Android's
 *  Settings has it (show_dev_countdown, show_dev_on). */
internal fun devModeMessage(left: Int): String = when {
    left <= 0 -> "You are now a developer!"
    left == 1 -> "You are now 1 step away from being a developer."
    else -> "You are now $left steps away from being a developer."
}

/** What a tap on the version says once Developer mode's found (till it's Dev
 *  mode, a moment later): as Android's Settings has it (show_dev_already). */
internal const val DEV_MODE_ALREADY = "No need, you are already a developer."

/** The app's version, at the bottom of Settings, sized as Android's own
 *  Settings has its version and build number: "Version" at 16sp, and the
 *  version itself under it at 14sp, in the secondary colour. */
@Composable
internal fun VersionRow(version: String, onTap: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(start = Ma.MarginStart, end = Ma.MarginEnd, top = 16.dp, bottom = 16.dp)
    ) {
        Text("Version", style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp))
        Text(
            version,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
