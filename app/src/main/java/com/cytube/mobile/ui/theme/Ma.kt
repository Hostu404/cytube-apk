package com.cytube.mobile.ui.theme

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.cytube.mobile.ui.isTvDevice

/*
 * The shared look of the home, settings and account pages, after a few ideas
 * from Japanese design:
 *  - more margin on the left than the right, 32dp to 16dp, so the column
 *    sits off-centre rather than centred by default;
 *  - every gap from one scale in 1:2 steps (8, 16, 32), the proportion of
 *    a tatami mat, which the margins follow too;
 *  - labels and secondary details in grey, so the content leads;
 *  - no divider lines or boxes: space does the separating.
 * Colours come from CyTubePageTheme: traditional Japanese colours.
 */
object Ma {
    val MarginStart = 32.dp
    val MarginEnd = 16.dp

    /** End padding for a row whose last item is a 48dp icon button: with
     *  the button's own 12dp around its icon, the icon lands on the 16dp
     *  right margin. */
    val MarginEndBeforeButton = 4.dp

    /** Section and field labels: small, spaced out, all caps. */
    val LabelStyle = TextStyle(fontSize = 11.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Medium)
}

/** A step quieter than onSurfaceVariant: labels, idle icons. */
@Composable
fun maFaint(): Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f)

/** An underline when it isn't focused. */
@Composable
fun maHairline(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)

/** True when the page is on the light (paper) palette. */
@Composable
fun maIsLight(): Boolean = MaterialTheme.colorScheme.background.luminance() >= 0.5f

/** The page background: the theme's background colour (pure black on dark, for
 *  OLED). */
@Composable
fun maPageBackground(): Modifier {
    val color = MaterialTheme.colorScheme.background
    return remember(color) { Modifier.background(color) }
}

/** A section's label, with an optional action (Clear, refresh) on the
 *  right. */
@Composable
fun MaSectionLabel(text: String, action: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            // With the row's 48dp height, 16 above reads as a 32dp gap.
            .padding(start = Ma.MarginStart, end = Ma.MarginEndBeforeButton, top = 16.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text.uppercase(), style = Ma.LabelStyle, color = maFaint(), modifier = Modifier.weight(1f))
        action()
    }
}

/**
 * A text field drawn as a single line to write on, not a box: an optional
 * small label above, an optional icon before and items after. The line
 * turns blue while focused, which is also what shows a TV remote's focus is
 * here. Its left edge is the text's left edge, so it lines up with
 * everything else on the page.
 *
 * On a phone, Back only leaves the field (the keyboard takes the first
 * Back itself; without this the next one left the page, or on home put the
 * app away), and leaving the app with the field focused doesn't bring it
 * back focused with the keyboard up. The same as the chat's message box.
 */
@Composable
fun MaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val isTv = remember { isTvDevice(context) }
    BackHandler(enabled = focused && !isTv) {
        keyboard?.hide()
        focusManager.clearFocus()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (focused && !isTv) focusManager.clearFocus()
    }
    val line = if (focused) colors.primary else maHairline()
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = 15.sp,
        color = if (enabled) colors.onSurface else colors.onSurfaceVariant
    )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(colors.primary),
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        interactionSource = interaction,
        modifier = modifier,
        decorationBox = { innerTextField ->
            Column(Modifier.fillMaxWidth()) {
                if (label != null) {
                    Text(label.uppercase(), style = Ma.LabelStyle, color = if (focused) colors.primary else maFaint())
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .drawBehind {
                            val y = size.height - 0.5.dp.toPx()
                            drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (leading != null) {
                        leading()
                        Spacer(Modifier.width(16.dp))
                    }
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(placeholder, style = textStyle, color = colors.onSurfaceVariant, maxLines = 1)
                        }
                        innerTextField()
                    }
                    trailing?.invoke(this)
                }
            }
        }
    )
}

/** The top bar of the settings and account pages: see-through over the
 *  page background, back arrow and title in the page's quieter colours. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}
