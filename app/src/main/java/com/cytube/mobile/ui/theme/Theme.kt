package com.cytube.mobile.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Accent = Color(0xFF4F7CFF)
private val AccentDark = Color(0xFF8AA8FF)

private val DarkScheme = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF00204D),
    surface = Color(0xFF121316),
    surfaceContainer = Color(0xFF1B1D21),
    surfaceContainerHigh = Color(0xFF232529),
    background = Color(0xFF0C0D0F),
    onBackground = Color(0xFFE4E5E8)
)

private val LightScheme = lightColorScheme(
    primary = Accent,
    surface = Color(0xFFFBFBFD),
    surfaceContainer = Color(0xFFF1F2F6),
    background = Color(0xFFFFFFFF)
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun CyTubeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
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
 * Wraps only the channel screen. Home, favourites and settings keep the normal
 * app theme, which restores itself automatically when this leaves composition.
 */
@Composable
fun CyTubeChannelTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CyTubeChannelScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
