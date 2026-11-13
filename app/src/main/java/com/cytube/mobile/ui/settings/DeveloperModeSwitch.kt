package com.cytube.mobile.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.cytube.mobile.ui.theme.Ma
import com.cytube.mobile.ui.theme.maIsLight
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Developer mode's switch: the purple guy's (see DevMode). On, it's red and
 * locked, a padlock on its knob, and a tap doesn't turn it off: it says to
 * hold it. Held [HOLD_MS], a ring fills round the knob, the padlock springs
 * open, and the switch slides off; let go before then and the ring drains
 * away. Turning it on is a tap, as usual, and the padlock snaps shut. Just
 * after it's turned off, taps do nothing for a moment (see [TurnOnGuard]),
 * so it isn't turned straight back on by the finger that turned it off.
 */

/** How long it's held to turn him off. */
internal const val HOLD_MS = 800

/** How long after turning him off taps on the switch do nothing. */
internal const val TURN_ON_COOLDOWN_MS = 600L

/** How long "Hold to turn off" shows, after a tap. */
private const val HINT_MS = 2_500L

/** Whether a tap may turn him on: not in the moment after he's been
 *  turned off. */
internal class TurnOnGuard {
    private var offAt = Long.MIN_VALUE / 2

    fun turnedOff(now: Long = now()) {
        offAt = now
    }

    fun mayTurnOn(now: Long = now()): Boolean = now - offAt >= TURN_ON_COOLDOWN_MS
}

/** A steady clock, in ms. */
private fun now() = System.nanoTime() / 1_000_000

/** Developer mode's row: [title], and the switch, [on] or not. */
@Composable
internal fun DeveloperModeSwitchRow(
    title: String,
    on: Boolean,
    onTurnOn: () -> Unit,
    onTurnOff: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val turnOff by rememberUpdatedState(onTurnOff)
    val guard = remember { TurnOnGuard() }
    // The ring round the knob, filling as it's held: 0..1.
    val hold = remember { Animatable(0f) }
    // The padlock's loop: 0 shut, 1 open.
    val open = remember { Animatable(if (on) 0f else 1f) }
    // The padlock itself, fading out once he's off: shown while above 0.
    val padlock = remember { Animatable(if (on) 1f else 0f) }
    var hint by remember { mutableStateOf(false) }

    LaunchedEffect(on) {
        hold.snapTo(0f)
        if (on) {
            // Snaps shut as it turns red.
            launch { padlock.animateTo(1f, tween(150)) }
            open.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow))
        } else {
            hint = false
            padlock.animateTo(0f, tween(220))
        }
    }
    LaunchedEffect(hint) {
        if (hint) {
            delay(HINT_MS)
            hint = false
        }
    }

    // Held all the way: it springs open, and off.
    suspend fun unlock() {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        guard.turnedOff()
        open.animateTo(1f, tween(160))
        turnOff()
    }

    Row(
        Modifier.fillMaxWidth().padding(start = Ma.MarginStart, end = Ma.MarginEnd, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            AnimatedVisibility(visible = hint, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Text(
                    "Hold to turn off",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        val ringColour = Color.White
        Switch(
            checked = on,
            // Off: a tap turns him on (but not just after he's been turned
            // off). On: it's locked, and handled below.
            onCheckedChange = if (on) null else { { if (guard.mayTurnOn()) onTurnOn() } },
            modifier = Modifier
                // The same size on and off (a switch that can't be tapped
                // would otherwise be smaller), so the row doesn't jump.
                .minimumInteractiveComponentSize()
                .then(
                    if (on) Modifier.semantics {
                        role = Role.Switch
                        stateDescription = "On, locked. Hold to turn off"
                        onLongClick("Turn off") {
                            scope.launch { unlock() }
                            true
                        }
                    } else Modifier
                )
                .pointerInput(on) {
                    if (!on) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            val filling = scope.launch {
                                hold.animateTo(1f, tween(((1f - hold.value) * HOLD_MS).toInt(), easing = LinearEasing))
                                unlock()
                            }
                            tryAwaitRelease()
                            if (filling.isActive) {
                                filling.cancel()
                                scope.launch { hold.animateTo(0f, tween(220)) }
                            }
                        },
                        onTap = {
                            hint = true
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    )
                }
                // The ring, round the knob (at the switch's right end, on).
                .drawWithContent {
                    drawContent()
                    val h = hold.value
                    if (h <= 0f) return@drawWithContent
                    val r = 14.dp.toPx()
                    val c = Offset(size.width - 16.dp.toPx(), size.height / 2)
                    val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                    val box = Size(2 * r, 2 * r)
                    val corner = Offset(c.x - r, c.y - r)
                    drawArc(ringColour.copy(alpha = 0.35f * h.coerceAtMost(0.2f) / 0.2f), 0f, 360f, false, corner, box, style = stroke)
                    drawArc(ringColour, -90f, 360f * h, false, corner, box, style = stroke)
                },
            colors = purpleGuySwitchColors(),
            thumbContent = if (on || padlock.value > 0f) {
                { Padlock(open = open.value, alpha = padlock.value) }
            } else null
        )
    }
}

/** The padlock on the switch's knob: its loop [open] (0 shut, 1 open,
 *  lifted and swung out to the left), faded to [alpha]. */
@Composable
private fun Padlock(open: Float, alpha: Float) {
    val colour = LocalContentColor.current
    Canvas(Modifier.size(PADLOCK_SIZE)) {
        // On a 24 x 24 grid, as an icon is.
        val u = size.width / 24f
        val body = colour.copy(alpha = colour.alpha * alpha)
        // The loop: a U, lifted and swung open (clockwise) about its right leg.
        val lift = 2.6f * open
        rotate(24f * open, pivot = Offset(16.5f * u, 10f * u)) {
            translate(top = -lift * u) {
                val loop = Stroke(width = 2.4f * u, cap = StrokeCap.Butt)
                drawArc(body, 180f, 180f, false, Offset(7.5f * u, 3f * u), Size(9f * u, 9f * u), style = loop)
                drawLine(body, Offset(7.5f * u, 7.5f * u), Offset(7.5f * u, 10.5f * u), strokeWidth = 2.4f * u)
                drawLine(body, Offset(16.5f * u, 7.5f * u), Offset(16.5f * u, 10.5f * u), strokeWidth = 2.4f * u)
            }
        }
        // The body, and its keyhole.
        drawRoundRect(body, Offset(4.5f * u, 10f * u), Size(15f * u, 11.5f * u), CornerRadius(2.2f * u))
        drawCircle(Color.White.copy(alpha = alpha), radius = 1.7f * u, center = Offset(12f * u, 15.3f * u))
        drawRect(Color.White.copy(alpha = alpha), Offset(11.4f * u, 15.3f * u), Size(1.2f * u, 2.6f * u))
    }
}

private val PADLOCK_SIZE = 18.dp

/** Developer mode's switch's colours: red when it's on (a lighter red on
 *  dark), the knob white, and the padlock red. */
@Composable
internal fun purpleGuySwitchColors(): SwitchColors {
    val red = if (maIsLight()) Color(0xFFD32F2F) else Color(0xFFEF5350)
    return SwitchDefaults.colors(
        checkedTrackColor = red,
        checkedBorderColor = red,
        checkedThumbColor = Color.White,
        checkedIconColor = red,
        uncheckedIconColor = red
    )
}
