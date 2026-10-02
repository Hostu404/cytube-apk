package com.cytube.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val AccentDark = Color(0xFF8AA8FF)

// Pure black for every background and surface, not a dark grey: on an
// OLED/AMOLED panel (most current phones) a black pixel is switched off and
// draws far less power. CyTubeChannelScheme below is untouched, it
// deliberately matches CyTube's own (non-black) web skin.
private val DarkScheme = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF00204D),
    surface = Color(0xFF000000),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF000000),
    surfaceContainer = Color(0xFF000000),
    surfaceContainerHigh = Color(0xFF000000),
    surfaceContainerHighest = Color(0xFF000000),
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
 * or (on API 31+) Material You dynamic color. CyTube itself only ships a
 * dark skin (see CyTubeChannelScheme's own doc comment below), and dynamic
 * color recolors everything from the device wallpaper, which can come out
 * looking nothing like the app's own palette.
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
 * After the greys of CyTube's default website theme (Slate): one blue-grey
 * for the page, the chat and the panel bar alike, a step darker than the
 * site's so it doesn't outshine a dark scene in the video beside it. Text
 * is a soft white rather than a bright one (bright white on dark seems to
 * glow at the edges, which tires eyes over a long watch), at the same
 * contrast as before thanks to the darker page (11:1; greys 7:1). The
 * accent is brightened so links and usernames stay legible at phone
 * brightness. It is a palette, not the site's CSS — nothing is copied or
 * embedded, and it applies only while a channel is open.
 */
private val SlatePage = Color(0xFF1F2226)
private val SlateRaised = Color(0xFF262A2E)
private val SlateHigh = Color(0xFF2D3136)
private val SlateText = Color(0xFFD4D7DA)

private val SlateHeader = Color(0xFF34393E)

/** The channel page's top bar: the room's original top-bar grey, neutral
 *  over the blue-grey Slate page. */
val ChannelTopBar = Color(0xFF222222)

private val CyTubeChannelScheme = darkColorScheme(
    primary = Color(0xFF7FB2E5),
    onPrimary = Color(0xFF08243D),
    secondary = Color(0xFF9AA6B2),
    tertiary = Color(0xFFD2A76B),
    background = SlatePage,
    onBackground = SlateText,
    surface = SlatePage,
    onSurface = SlateText,
    surfaceVariant = SlateHeader,
    surfaceContainerLowest = SlatePage,
    surfaceContainerLow = SlatePage,
    surfaceContainer = SlateRaised,
    surfaceContainerHigh = SlateHigh,
    surfaceContainerHighest = SlateHeader,
    onSurfaceVariant = Color(0xFFA9AEB4),
    outline = Color(0xFF4E545B),
    outlineVariant = SlateHeader,
    error = Color(0xFFE57373),
    errorContainer = Color(0xFF4A2222),
    onErrorContainer = Color(0xFFF6D5D5),
    secondaryContainer = Color(0xFF2F3A45),
    onSecondaryContainer = Color(0xFFD9E4EF)
)

/**
 * Wraps the channel screen. Home, settings and account (on phone) use
 * CyTubeSettingsTheme above instead, which follows the Appearance setting
 * rather than this always-dark palette. This restores itself automatically
 * when it leaves composition.
 */
@Composable
fun CyTubeChannelTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CyTubeChannelScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}

/**
 * The quieter palette of the home, settings and account pages, over
 * whichever of the above they sit in (see Ma.kt). Kept close to the app's
 * original colours so it still feels familiar, with a Japanese touch. Every
 * background and surface is the app's original very faintly lilac white on
 * light (Material's default) and pure black on dark (so an OLED screen's
 * pixels stay off). Text is near-white on dark (pure white
 * on pure black glares on an OLED screen) and near-black on light. Secondary
 * text is ginnezumi (silver grey) on dark and sumi (ink) on light. The one
 * accent, for what's yours (the login name and favorites), is the app's
 * original blue moved halfway towards indigo (ai-iro).
 */
@Composable
fun CyTubePageTheme(content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val scheme = if (base.background.luminance() < 0.5f) {
        base.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color.Black,
            surfaceContainer = Color.Black,
            surfaceContainerHigh = Color.Black,
            surfaceContainerHighest = Color.Black,
            onBackground = PageInkOnDark,
            onSurface = PageInkOnDark,
            onSurfaceVariant = Ginnezumi,
            primary = IndigoBlueOnDark
        )
    } else {
        base.copy(
            background = PageWhite,
            surface = PageWhite,
            surfaceContainerLowest = PageWhite,
            surfaceContainerLow = PageWhite,
            surfaceContainer = PageWhite,
            surfaceContainerHigh = PageWhite,
            surfaceContainerHighest = PageWhite,
            onBackground = PageInk,
            onSurface = PageInk,
            onSurfaceVariant = SumiIro,
            primary = IndigoBlue
        )
    }
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, content = content)
}

private val PageWhite = Color(0xFFFEF7FF)       // the original light background
private val PageInk = Color(0xFF1F1F1F)
private val SumiIro = Color(0xFF595857)       // 墨 ink
private val IndigoBlue = Color(0xFF285EA4)    // #3B5FC4 halfway to ai-iro 藍 #165E83
private val PageInkOnDark = Color(0xFFF5F5F5)
private val Ginnezumi = Color(0xFFAFAFB0)     // 銀鼠 silver grey
private val IndigoBlueOnDark = Color(0xFF85A4E2) // #8AA8FF halfway to usuhana-iro 薄花 #7F9FC4
