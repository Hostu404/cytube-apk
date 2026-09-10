package com.cytube.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val AccentDark = Color(0xFF8AA8FF)

// True black background/surface rather than the earlier dark-grey pair —
// on an OLED/AMOLED panel (most current phones) a black pixel draws
// meaningfully less power than a dark-grey one, and this screen sits on
// screen for as long as the app is open. surfaceContainer/surfaceContainerHigh
// stay barely lifted off black (not pure black themselves) purely so cards
// and sheets remain visually distinguishable from the background behind
// them — CyTubeChannelScheme below is untouched, it deliberately matches
// CyTube's own (non-black) web skin rather than chasing this.
private val DarkScheme = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF00204D),
    surface = Color(0xFF000000),
    surfaceContainer = Color(0xFF0A0A0C),
    surfaceContainerHigh = Color(0xFF141417),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE4E5E8)
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
)

/**
 * Always dark, deliberately — not driven by the system light/dark setting
 * or (on API 31+) Material You dynamic color the way this used to be. CyTube
 * itself only ships a dark skin (see CyTubeChannelScheme's own doc comment
 * below, which already committed the channel screen to this), and dynamic
 * color in particular was the reason this could come out looking nothing
 * like the app's own intended palette — it recolors everything from the
 * device wallpaper, light or dark, independent of what's defined here.
 */
@Composable
fun CyTubeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, typography = AppTypography, content = content)
}

/** Light counterpart to DarkScheme above, used only by CyTubeSettingsTheme
 *  below — everywhere else in the app is always dark, deliberately, per the
 *  doc comment on CyTubeTheme. */
private val LightScheme = lightColorScheme(
    primary = Color(0xFF3B5FC4),
    onPrimary = Color(0xFFFFFFFF)
)

/**
 * Settings-only theme, used for the home and settings screens (see
 * MainActivity). Unlike the channel screen (matching CyTube's own dark skin)
 * or the rest of the app (deliberately always dark — see CyTubeTheme's doc
 * comment), these are plain screens with no CyTube-branded look to protect,
 * so by default this just follows the phone's own light/dark setting.
 *
 * [darkTheme] defaults to the system setting but is a parameter, not read
 * internally, so a caller can resolve the user's ThemeMode preference
 * (Settings > Appearance — System default/Light/Dark) and pass the result
 * in instead of always trusting isSystemInDarkTheme().
 */
@Composable
fun CyTubeSettingsTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}

/**
 * Channel-only theme.
 *
 * CyTube's default skin is a dark bootstrap variant. These are its palette
 * anchors nudged for Android: text lightened for contrast against the panel
 * greys, and the accent brightened so links and usernames stay legible at
 * phone brightness. It is a palette, not the site's CSS — nothing is copied
 * or embedded, and it applies only while a channel is open.
 */
private val CyTubeChannelScheme = darkColorScheme(
    primary = Color(0xFF7FB2E5),
    onPrimary = Color(0xFF08243D),
    secondary = Color(0xFF9AA6B2),
    tertiary = Color(0xFFD2A76B),
    background = Color(0xFF1B1B1B),
    onBackground = Color(0xFFE8E8E8),
    surface = Color(0xFF222222),
    onSurface = Color(0xFFE8E8E8),
    surfaceContainer = Color(0xFF2A2A2A),
    surfaceContainerHigh = Color(0xFF333333),
    surfaceContainerHighest = Color(0xFF3A3A3A),
    onSurfaceVariant = Color(0xFFB6B6B6),
    outline = Color(0xFF4A4A4A),
    error = Color(0xFFE57373),
    errorContainer = Color(0xFF4A2222),
    onErrorContainer = Color(0xFFF6D5D5),
    secondaryContainer = Color(0xFF2F3A45),
    onSecondaryContainer = Color(0xFFD9E4EF)
)

/**
 * Wraps the channel screen (and, on phone, home too — see MainActivity's
 * "home" destination). Settings has its own CyTubeSettingsTheme above instead,
 * which follows the system setting rather than this always-dark palette. This
 * restores itself automatically when it leaves composition.
 */
@Composable
fun CyTubeChannelTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CyTubeChannelScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
