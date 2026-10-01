package com.cytube.mobile.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The cat's state. Kept by the home screen rather than by [CotCat] itself:
 * the search bar sits in the channel list, which drops it while it's
 * scrolled out of view, and the cat shouldn't start its day over each time
 * it scrolls back. It also carries where a finger is on the screen, from
 * [cotCatWatchesFingers].
 */
@Stable
class CotCatState {
    /** 0 = sitting, 1 = asleep; in between, the two poses crossfade. Each
     *  visit to the home page finds it at a random point in its day: some
     *  of the time already asleep. */
    internal val sleep = Animatable(if (Random.nextFloat() < START_ASLEEP_CHANCE) 1f else 0f)
    internal var started = false
    internal var lastWakeKey: Any? = null

    /** A finger on the screen, in root coordinates; null when nothing is
     *  touching. */
    internal var finger by mutableStateOf<Offset?>(null)
    internal var screen: LayoutCoordinates? = null
    internal var self: LayoutCoordinates? = null

    /** Head turn towards the finger, in degrees, and how far that has
     *  taken over from the idle head tilt (0..1). Both ease in and out. */
    internal var gaze by mutableFloatStateOf(0f)
    internal var follow by mutableFloatStateOf(0f)
}

@Composable
fun rememberCotCatState(): CotCatState = remember { CotCatState() }

/**
 * Lets the cat see fingers anywhere in this element (the whole home
 * screen). It only watches: nothing is consumed, so scrolling and taps work
 * exactly as before.
 */
fun Modifier.cotCatWatchesFingers(state: CotCatState): Modifier = this
    .onGloballyPositioned { state.screen = it }
    .pointerInput(state) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val down = event.changes.firstOrNull { it.pressed }
                val screen = state.screen
                state.finger = if (down != null && screen != null && screen.isAttached) {
                    screen.localToRoot(down.position)
                } else {
                    null
                }
            }
        }
    }

/**
 * The cat that lives in the home search bar with the Cot theme. A
 * silhouette drawn in a 100 x 120 box and scaled to fit. Sitting, it swishes
 * its tail, breathes, now and then flicks an ear and tilts its head, and
 * while a finger is on the screen it slowly turns its head to watch it.
 * Every so often it curls up and sleeps for a while. Purely decorative.
 *
 * Changing [wakeKey] (the search text) wakes it up and restarts its timer,
 * so it doesn't nod off while you're typing, and it stays awake while it's
 * watching a finger.
 */
@Composable
fun CotCat(
    state: CotCatState,
    modifier: Modifier = Modifier,
    wakeKey: Any? = null,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    val motion = rememberInfiniteTransition(label = "cotCat")
    val tail by motion.animateFloat(
        initialValue = -8f,
        targetValue = 10f,
        animationSpec = infiniteRepeatable(
            tween(1800, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "tail"
    )
    val breath by motion.animateFloat(
        initialValue = 1f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            tween(1600, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "breath"
    )
    // A quick double flick near the end of each 5.2s cycle.
    val ear by motion.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 5200
                0f at 4600
                -14f at 4700
                0f at 4850
                -14f at 4950
                0f at 5100
            }
        ),
        label = "ear"
    )
    // A slow, curious head tilt once every 9s.
    val tilt by motion.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 9000
                0f at 3000 using FastOutSlowInEasing
                8f at 3600
                8f at 5200 using FastOutSlowInEasing
                0f at 5800
            }
        ),
        label = "tilt"
    )
    // Drives the Zs rising while it sleeps.
    val zzz by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "zzz"
    )

    val sleep = state.sleep
    LaunchedEffect(state, wakeKey) {
        // This runs on arriving at the page, on the search bar scrolling
        // back into view, and on the search text changing. Only the last
        // wakes it.
        val typed = state.started && wakeKey != state.lastWakeKey
        state.started = true
        state.lastWakeKey = wakeKey
        var awakeFor = Random.nextLong(AWAKE_MIN_MS, AWAKE_MAX_MS)
        when {
            typed -> if (sleep.value > 0f) sleep.animateTo(0f, tween(400))
            sleep.value > 0f -> {
                // Found asleep: wakes some time into the rest of its nap.
                if (sleep.value < 1f) sleep.animateTo(1f, tween(700))
                delay(Random.nextLong(FIRST_CHANGE_MIN_MS, ASLEEP_MAX_MS))
                sleep.animateTo(0f, tween(400))
            }
            // Found awake: somewhere into its time awake.
            else -> awakeFor = Random.nextLong(FIRST_CHANGE_MIN_MS, AWAKE_MAX_MS)
        }
        while (true) {
            state.stayAwake(awakeFor)
            if (Random.nextFloat() < SLEEP_CHANCE) {
                sleep.animateTo(1f, tween(700))
                delay(Random.nextLong(ASLEEP_MIN_MS, ASLEEP_MAX_MS))
                sleep.animateTo(0f, tween(400))
            }
            awakeFor = Random.nextLong(AWAKE_MIN_MS, AWAKE_MAX_MS)
        }
    }

    // Eases the head towards the finger, and back once it lifts. Runs frame
    // by frame only while there's something to follow or to settle.
    LaunchedEffect(state) {
        var last = 0L
        while (true) {
            if (state.gaze == 0f && state.follow == 0f) {
                snapshotFlow { state.finger != null && sleep.value == 0f }.first { it }
                last = 0L
            }
            withFrameNanos { now ->
                val dt = if (last == 0L) 1f / 60f else ((now - last) / 1e9f).coerceAtMost(0.1f)
                last = now
                val target = if (sleep.value == 0f) state.gazeTarget() else null
                if (target != null) {
                    state.gaze = approach(state.gaze, target, dt, FOLLOW_TAU_S)
                    state.follow = approach(state.follow, 1f, dt, FOLLOW_TAU_S, maxPerSecond = 3f)
                } else {
                    state.gaze = approach(state.gaze, 0f, dt, RETURN_TAU_S)
                    state.follow = approach(state.follow, 0f, dt, RETURN_TAU_S, maxPerSecond = 3f)
                    if (abs(state.gaze) < 0.05f && state.follow < 0.01f) {
                        state.gaze = 0f
                        state.follow = 0f
                    }
                }
            }
        }
    }

    val parts = remember { CatParts() }
    val tailStroke = remember { Stroke(width = 7f, cap = StrokeCap.Round) }

    // One layer per pose, so each fades as a whole: fading the parts one by
    // one would show brighter seams where they overlap.
    Box(modifier.onGloballyPositioned { state.self = it }) {
        Canvas(Modifier.matchParentSize().graphicsLayer { alpha = 1f - sleep.value }) {
            inCatBox {
                // Sitting faces left: the shapes below face right, so
                // mirror them.
                scale(scaleX = -1f, scaleY = 1f, pivot = Offset(BOX_W / 2f, BOX_H / 2f)) {
                    // Tail first, so the body covers its base.
                    rotate(tail, pivot = Offset(30f, 113f)) {
                        drawPath(parts.tail, color, style = tailStroke)
                    }
                    scale(scaleX = 1f, scaleY = breath, pivot = Offset(0f, 118f)) {
                        drawPath(parts.haunch, color)
                        drawPath(parts.body, color)
                    }
                    drawPath(parts.backLeg, color)
                    drawPath(parts.frontLeg, color)
                    val head = tilt * (1f - state.follow) + state.gaze
                    rotate(head, pivot = NECK) {
                        drawPath(parts.head, color)
                        drawPath(parts.leftEar, color)
                        rotate(ear, pivot = Offset(76f, 24f)) {
                            drawPath(parts.rightEar, color)
                        }
                    }
                }
            }
        }
        Canvas(Modifier.matchParentSize().graphicsLayer { alpha = sleep.value }) {
            inCatBox {
                // Slower-looking, deeper breaths than sitting.
                scale(scaleX = 1f, scaleY = 1f + (breath - 1f) * 2f, pivot = Offset(0f, 118f)) {
                    drawPath(parts.curled, color)
                }
                drawPath(parts.curledTail, color, style = tailStroke)
                drawPath(parts.restingHead, color)
                drawPath(parts.restingEars, color)
                // Two Zs, half a cycle apart, rising and fading.
                for (i in 0..1) {
                    val p = (zzz + i * 0.5f) % 1f
                    drawZ(
                        x = 84f + 8f * p,
                        y = 84f - 26f * p,
                        h = 4f + 3f * p,
                        color = color.copy(alpha = color.alpha * sin(PI.toFloat() * p))
                    )
                }
            }
        }
    }
}

/**
 * The head turn, in degrees, that points the sitting cat's face at the
 * finger, or null with no finger (or no layout yet). Worked out in the
 * drawing's own facing-right space, where turning clockwise looks down.
 * A finger behind the cat is watched as if it were in front — it can only
 * tilt its head, not turn round — and the turn is limited to what a neck
 * can do.
 */
private fun CotCatState.gazeTarget(): Float? {
    val finger = finger ?: return null
    val self = self?.takeIf { it.isAttached } ?: return null
    val w = self.size.width.toFloat()
    val h = self.size.height.toFloat()
    val s = min(w / BOX_W, h / BOX_H)
    // The middle of the face, mirrored because sitting is drawn flipped.
    val face = self.localToRoot(
        Offset((w - BOX_W * s) / 2f + (BOX_W - 68f) * s, (h - BOX_H * s) / 2f + 34f * s)
    )
    val dx = abs(finger.x - face.x)
    val dy = finger.y - face.y
    val degrees = atan2(dy, dx) * (180f / PI.toFloat())
    return degrees.coerceIn(-GAZE_UP_MAX, GAZE_DOWN_MAX)
}

/**
 * Waits out [ms] of time awake before its next chance to sleep. It won't
 * doze off while it's watching a finger: a touch holds the wait, and
 * lifting the finger starts a fresh one.
 */
private suspend fun CotCatState.stayAwake(ms: Long) {
    var wait = ms
    while (true) {
        withTimeoutOrNull(wait) { snapshotFlow { finger != null }.first { it } } ?: return
        snapshotFlow { finger == null }.first { it }
        wait = Random.nextLong(AWAKE_MIN_MS, AWAKE_MAX_MS)
    }
}

/** Moves [current] towards [target], closing most of the gap within about
 *  [tau] seconds, and never faster than [maxPerSecond]. */
private fun approach(
    current: Float,
    target: Float,
    dt: Float,
    tau: Float,
    maxPerSecond: Float = GAZE_MAX_DEG_PER_S
): Float {
    val step = (target - current) * (1f - exp(-dt / tau))
    val limit = maxPerSecond * dt
    return current + step.coerceIn(-limit, limit)
}

/** Awake 20-45s between chances to sleep; half the time it takes one, for
 *  40-90s. On arriving at the home page it's already asleep 40% of the
 *  time, and its first change (waking, or its first chance to sleep) comes
 *  after anywhere from 3s up to the usual maximum. */
private const val START_ASLEEP_CHANCE = 0.4f
private const val FIRST_CHANGE_MIN_MS = 3_000L
private const val AWAKE_MIN_MS = 20_000L
private const val AWAKE_MAX_MS = 45_000L
private const val SLEEP_CHANCE = 0.5f
private const val ASLEEP_MIN_MS = 40_000L
private const val ASLEEP_MAX_MS = 90_000L

/** How the head follows a finger: an unhurried turn towards it, a gentler
 *  one back, never faster than 90 degrees a second, and no further than
 *  28 degrees down or 24 up. */
private const val FOLLOW_TAU_S = 0.35f
private const val RETURN_TAU_S = 0.6f
private const val GAZE_MAX_DEG_PER_S = 90f
private const val GAZE_DOWN_MAX = 28f
private const val GAZE_UP_MAX = 24f

private const val BOX_W = 100f
private const val BOX_H = 120f

/** Where the sitting cat's head turns, in the drawing's box. */
private val NECK = Offset(66f, 40f)

/** Runs [block] with the 100 x 120 cat box scaled to fit and centred. */
private inline fun DrawScope.inCatBox(block: DrawScope.() -> Unit) {
    val s = min(size.width / BOX_W, size.height / BOX_H)
    translate(left = (size.width - BOX_W * s) / 2f, top = (size.height - BOX_H * s) / 2f) {
        scale(s, pivot = Offset.Zero) { block() }
    }
}

/** A "Z" [h] units square with its top-left corner at ([x], [y]). */
private fun DrawScope.drawZ(x: Float, y: Float, h: Float, color: Color) {
    val w = 2.5f
    drawLine(color, Offset(x, y), Offset(x + h, y), w, StrokeCap.Round)
    drawLine(color, Offset(x + h, y), Offset(x, y + h), w, StrokeCap.Round)
    drawLine(color, Offset(x, y + h), Offset(x + h, y + h), w, StrokeCap.Round)
}

/** The cat's shapes, built once, in the 100 x 120 box with the ground at
 *  y = 118. Overlapping shapes are separate paths: one path holding shapes
 *  wound in opposite directions would leave a hole where they overlap. */
private class CatParts {
    // Sitting, drawn facing right (CotCat mirrors it to face left).
    val tail = Path().apply {
        moveTo(30f, 113f)
        cubicTo(10f, 115f, 4f, 97f, 14f, 83f)
        cubicTo(18f, 78f, 24f, 79f, 24f, 83f)
    }
    val haunch = Path().apply { addOval(Rect(22f, 78f, 70f, 118f)) }
    val body = Path().apply {
        moveTo(28f, 112f)                               // back, neck, chest
        cubicTo(26f, 84f, 44f, 60f, 58f, 40f)
        lineTo(76f, 40f)
        cubicTo(86f, 58f, 78f, 84f, 80f, 118f)
        lineTo(36f, 118f)
        close()
    }
    val backLeg = Path().apply {
        addRoundRect(RoundRect(62f, 86f, 70f, 118f, CornerRadius(4f)))
    }
    val frontLeg = Path().apply {
        addRoundRect(RoundRect(70f, 88f, 78f, 118f, CornerRadius(4f)))
    }
    val head = Path().apply { addOval(Rect(52f, 20.5f, 84f, 47.5f)) }
    val leftEar = Path().apply {
        moveTo(55f, 30f)
        lineTo(57f, 13f)
        lineTo(66f, 23f)
        close()
    }
    val rightEar = Path().apply {
        moveTo(71f, 22f)
        lineTo(80f, 11f)
        lineTo(82f, 30f)
        close()
    }

    // Curled up asleep, head resting on the right.
    val curled = Path().apply { addOval(Rect(20f, 88f, 82f, 118f)) }
    val curledTail = Path().apply {
        moveTo(24f, 110f)
        cubicTo(24f, 118f, 56f, 118f, 74f, 114f)
    }
    val restingHead = Path().apply { addOval(Rect(66f, 92f, 92f, 116f)) }
    val restingEars = Path().apply {
        moveTo(69f, 98f)
        lineTo(70f, 86f)
        lineTo(78f, 94f)
        close()
        moveTo(78f, 93f)
        lineTo(86f, 84f)
        lineTo(89f, 99f)
        close()
    }
}
