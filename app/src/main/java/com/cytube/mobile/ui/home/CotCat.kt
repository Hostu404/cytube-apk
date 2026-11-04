package com.cytube.mobile.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.cytube.mobile.R
import kotlinx.coroutines.delay
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The cat's state. Kept by the home screen rather than by [CotCat] itself:
 * the search bar sits in the channel list, which drops it while it's
 * scrolled out of view, and the cat shouldn't start its day over each time
 * it scrolls back.
 */
@Stable
class CotCatState {
    /** The hour of the day (0-23) for its body clock, [Mood]. A test can
     *  set the time here. */
    internal var hour: () -> Int = { LocalTime.now().hour }

    /** Each visit to the home page finds it at a random point in its day:
     *  some of the time already asleep, more so at night. */
    internal var asleep by mutableStateOf(Random.nextFloat() < mood().startAsleep)

    /** Waking up: a stretch and a yawn. */
    internal var waking by mutableStateOf(false)

    /** A paw up for a quick scratch, when it's tapped awake (only then). */
    internal var scratching by mutableStateOf(false)

    /** Stopped partway through a stroll, thinking better of it. */
    internal var pondering by mutableStateOf(false)

    /** [LEFT] or [RIGHT] while it's walking, else 0. */
    internal var walking by mutableIntStateOf(0)

    /** How far it is to the left of its spot, in the sprite's own pixels. */
    internal var away by mutableFloatStateOf(0f)

    /** Taps on it so far (see [cotCatTaps]): each sets it scratching, or
     *  wakes it if it's asleep. */
    internal var taps by mutableIntStateOf(0)

    /** How far left of its spot it can go, in sprite pixels: to just past
     *  the search icon. And how much of that it'll stop and sit down in: the
     *  empty half of its row, clear of the search hint. Both worked out from
     *  where its spot is (see [CotCat]). */
    internal var room = 0f
    internal var restRoom = 0f

    /** Where it was last drawn, in its spot's coordinates (left of the spot
     *  while it's out walking), and where its spot and the bar are: for
     *  telling whether a tap is on it. */
    internal var drawn = Rect.Zero
    internal var spot: LayoutCoordinates? = null
    internal var bar: LayoutCoordinates? = null

    internal var started = false
    internal var lastWakeKey: Any? = null
    internal var lastTaps = 0
}

@Composable
fun rememberCotCatState(): CotCatState = remember { CotCatState() }

/**
 * A tap on the cat, wherever it's got to along the bar (out on a stroll it's
 * outside its own spot), sets it scratching, or wakes it if it's asleep, and
 * the bar doesn't take it as a tap of its own: the keyboard stays down. A tap
 * anywhere else, or a scroll that starts on the cat, goes to the bar as usual.
 * Goes on the bar it sits in.
 */
fun Modifier.cotCatTaps(state: CotCatState): Modifier = this
    .onPlaced { state.bar = it }
    .pointerInput(state) {
        val slop = TAP_SLOP.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!state.isUnder(down.position, slop)) return@awaitEachGesture
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes
                    .firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                // Moved off it: a scroll, not a tap.
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
                if (!change.pressed) {
                    change.consume()
                    state.taps++
                    return@awaitEachGesture
                }
            }
        }
    }

/** Whether [position], on the bar, is on the cat, give or take [slop] pixels. */
private fun CotCatState.isUnder(position: Offset, slop: Float): Boolean {
    val spot = spot?.takeIf { it.isAttached } ?: return false
    val bar = bar?.takeIf { it.isAttached } ?: return false
    return drawn.inflate(slop).contains(spot.localPositionOf(bar, position))
}

/**
 * The cat that lives in the home search bar when cot is on (Settings >
 * Appearance, light or dark): a pixel-art tuxedo cat, from the sprite sheet
 * res/drawable-nodpi/tuxedo_cat.png (38 x 32 pixel frames, eight to a row,
 * the cat's feet on each frame's bottom edge). It's drawn with each of its
 * pixels as a whole number of screen pixels, as many as fit, and not
 * smoothed, so it stays crisp; feet on the bottom edge, centred in its spot,
 * with a soft shadow under them.
 *
 * Sitting, it blinks and flicks an ear, (with [roam]) often goes for a stroll
 * along its row, back and forth and home again, now and then thinking better
 * of a leg of it partway and turning back, and every so often it dozes off
 * for a while, waking with a stretch and a yawn. All by its body clock: sleepy
 * at night, lively in the evening (see [Mood]). Only when it's tapped (see
 * [cotCatTaps]) does it have a scratch; tapped while it's asleep, it wakes,
 * with its stretch and yawn. Purely decorative.
 *
 * Changing [wakeKey] (the search text) wakes it up, sends it back to its
 * spot if it's out on a stroll, and restarts its timer, so it doesn't nod
 * off while you're typing.
 */
@Composable
fun CotCat(
    state: CotCatState,
    modifier: Modifier = Modifier,
    wakeKey: Any? = null,
    /** Whether it may go for a stroll: not over search text, say. It walks
     *  left of its spot, along the row it's in, outside its own bounds. */
    roam: Boolean = true,
    /** While true (the search box in use, say), it's up: woken if it's
     *  asleep, and no naps. */
    keepAwake: Boolean = false
) {
    val canRoam by rememberUpdatedState(roam)
    val mayNap by rememberUpdatedState(!keepAwake)
    LaunchedEffect(state, wakeKey, state.taps, keepAwake) {
        // This runs on arriving at the page, on the search bar scrolling
        // back into view, on the search text changing, on a tap, and on the
        // search box being taken up or left.
        val tapped = state.started && state.taps != state.lastTaps
        val typed = state.started && wakeKey != state.lastWakeKey
        state.started = true
        state.lastWakeKey = wakeKey
        state.lastTaps = state.taps
        var awakeFor = Random.nextLong(AWAKE_MIN_MS, AWAKE_MAX_MS)
        when {
            // Asleep, it wakes with a stretch and a yawn; awake (or already
            // waking), it has a scratch. Either where it is.
            tapped -> if (state.asleep) state.wakeUp() else state.scratch()
            typed || keepAwake -> state.wakeUp()
            state.asleep -> {
                // Found asleep: wakes some time into the rest of its nap.
                delay(Random.nextLong(FIRST_CHANGE_MIN_MS, state.mood().napMaxMs))
                state.wakeUp()
            }
            // Found awake: somewhere into its time awake.
            else -> awakeFor = Random.nextLong(FIRST_CHANGE_MIN_MS, AWAKE_MAX_MS)
        }
        // Out on a stroll when that happened: back to its spot.
        state.walkTo(0f)
        while (true) {
            state.stayAwake(awakeFor) { canRoam }
            val mood = state.mood()
            if (mayNap && Random.nextFloat() < mood.napChance) {
                state.asleep = true
                delay(Random.nextLong(mood.napMinMs, mood.napMaxMs))
                state.wakeUp()
            }
            awakeFor = Random.nextLong(AWAKE_MIN_MS, AWAKE_MAX_MS)
        }
    }

    val animation = when {
        state.asleep -> DOZE
        state.waking -> WAKE
        state.walking != 0 -> WALK
        state.scratching -> SCRATCH
        state.pondering -> PONDER
        else -> SIT
    }
    // Which of [animation]'s frames is showing. Read only while drawing, so
    // stepping it redraws the cat without recomposing anything.
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(animation) {
        step = 0
        while (true) {
            delay(animation.frameMs)
            step = (step + 1) % animation.frames.size
        }
    }

    val sheet = ImageBitmap.imageResource(R.drawable.tuxedo_cat)
    val feet = remember(sheet) { feetOf(sheet) }
    val shadow = MaterialTheme.colorScheme.onSurface.copy(alpha = SHADOW_ALPHA)
    val clearOfIcon = with(LocalDensity.current) { CLEAR_OF_ICON.toPx() }
    Canvas(
        modifier.onPlaced { spot ->
            state.spot = spot
            // Room for a stroll: from its spot, left along the row it's in to
            // just past the search icon at its start, sitting down only in
            // the row's empty half (the search hint is in the other).
            val row = spot.parentLayoutCoordinates ?: return@onPlaced
            val pixel = pixelSize(spot.size.width.toFloat(), spot.size.height.toFloat())
            val home = row.localPositionOf(spot, Offset.Zero).x + (spot.size.width - CELL_W * pixel) / 2f
            state.room = ((home - clearOfIcon) / pixel).coerceAtLeast(0f)
            state.restRoom = ((home - max(clearOfIcon, row.size.width / 2f)) / pixel).coerceAtLeast(0f)
        }
    ) {
        val frame = animation.frames[step % animation.frames.size]
        val pixel = pixelSize(size.width, size.height)
        val width = CELL_W * pixel
        val height = CELL_H * pixel
        // Whole sprite pixels away from its spot, so it stays crisp walking.
        val x = ((size.width - width) / 2f).roundToInt() - state.away.roundToInt() * pixel
        val y = (size.height - height).roundToInt()
        state.drawn = Rect(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat())
        // The walk faces right on the sheet: mirrored to walk left.
        val mirror = if (state.walking == LEFT) -1f else 1f
        scale(scaleX = mirror, scaleY = 1f, pivot = Offset(x + width / 2f, y + height / 2f)) {
            // Its shadow, two of its pixels deep: as wide as its feet in the
            // bottom row, a little narrower just below it.
            val (left, right) = feet[frame]
            if (right >= left) {
                fun band(row: Int, from: Int, to: Int) = drawRect(
                    shadow,
                    topLeft = Offset((x + from * pixel).toFloat(), (y + row * pixel).toFloat()),
                    size = Size(((to - from + 1) * pixel).toFloat(), pixel.toFloat())
                )
                band(CELL_H - 1, left - 1, right + 1)
                band(CELL_H, left + 1, right - 1)
            }
            drawImage(
                image = sheet,
                srcOffset = IntOffset(frame % SHEET_COLUMNS * CELL_W, frame / SHEET_COLUMNS * CELL_H),
                srcSize = IntSize(CELL_W, CELL_H),
                dstOffset = IntOffset(x, y),
                dstSize = IntSize(width, height),
                filterQuality = FilterQuality.None
            )
        }
    }
}

/** Screen pixels per sprite pixel in a spot [width] x [height]: as many
 *  whole ones as fit, at least one. */
private fun pixelSize(width: Float, height: Float): Int =
    max(1, min(width / CELL_W, height / CELL_H).toInt())

/** For each frame of [sheet], the first and last column with something in
 *  its bottom two rows, where its feet (and tail) are; or (0, -1) for none. */
private fun feetOf(sheet: ImageBitmap): Array<Pair<Int, Int>> {
    val pixels = IntArray(sheet.width * sheet.height)
    sheet.readPixels(pixels)
    val columns = sheet.width / CELL_W
    return Array(columns * (sheet.height / CELL_H)) { i ->
        val x0 = i % columns * CELL_W
        val y0 = i / columns * CELL_H
        var left = CELL_W
        var right = -1
        for (y in CELL_H - 2 until CELL_H) for (x in 0 until CELL_W) {
            if (pixels[(y0 + y) * sheet.width + x0 + x] ushr 24 != 0) {
                left = min(left, x)
                right = max(right, x)
            }
        }
        if (right < 0) 0 to -1 else left to right
    }
}

/**
 * Sits for [ms], every so often going for a stroll when [roam] says so and
 * there's room. Timed by the frame clock, as everything else about it is.
 */
private suspend fun CotCatState.stayAwake(ms: Long, roam: () -> Boolean) {
    val start = withFrameMillis { it }
    while (true) {
        val left = ms - (withFrameMillis { it } - start)
        val sit = Random.nextLong(SIT_MIN_MS, SIT_MAX_MS)
        if (sit >= left) {
            delay(left.coerceAtLeast(0L))
            return
        }
        delay(sit)
        if (roam() && room >= MIN_STROLL && Random.nextFloat() < mood().strollChance) stroll()
    }
}

/** Wakes up, if it's asleep, with a stretch and a yawn. */
private suspend fun CotCatState.wakeUp() {
    if (!asleep) return
    asleep = false
    waking = true
    try {
        delay(WAKE.frames.size * WAKE.frameMs)
    } finally {
        waking = false
    }
}

/** Twice through the scratching frames: what a tap gets while it's awake. */
private suspend fun CotCatState.scratch() {
    scratching = true
    try {
        delay(SCRATCH.frames.size * SCRATCH.frameMs * SCRATCH_LOOPS)
    } finally {
        scratching = false
    }
}

/** Off along the row: one to three legs back and forth, with a sit at the
 *  end of one in the row's empty half (where it isn't, only a stop to turn
 *  round, so it doesn't sit on the search hint), then back to its spot. Now
 *  and then it thinks better of a leg partway: stops, blinks, and goes back
 *  the way it came. */
private suspend fun CotCatState.stroll() {
    repeat(Random.nextInt(1, MAX_LEGS + 1)) {
        val from = away
        val target = somewhereElse()
        if (Random.nextFloat() < CHANGE_MIND_CHANCE) {
            val share = CHANGE_MIND_MIN + Random.nextFloat() * (CHANGE_MIND_MAX - CHANGE_MIND_MIN)
            walkTo((from + (target - from) * share).roundToInt().toFloat())
            ponder()
            walkTo(from)
        } else {
            walkTo(target)
            delay(if (target <= restRoom) Random.nextLong(REST_MIN_MS, REST_MAX_MS) else Random.nextLong(TURN_MIN_MS, TURN_MAX_MS))
        }
    }
    walkTo(0f)
}

/** A stop to think about it, with a blink. */
private suspend fun CotCatState.ponder() {
    pondering = true
    try {
        delay(PONDER.frames.size * PONDER.frameMs)
    } finally {
        pondering = false
    }
}

/** Somewhere along its row, in sprite pixels left of its spot, at least a
 *  short walk from where it is. */
private fun CotCatState.somewhereElse(): Float {
    repeat(8) {
        val target = (Random.nextFloat() * room).roundToInt().toFloat()
        if (abs(target - away) >= MIN_LEG) return target
    }
    return if (away < room / 2f) room.roundToInt().toFloat() else 0f
}

/** Walks at a steady pace until it's [target] sprite pixels left of its spot. */
private suspend fun CotCatState.walkTo(target: Float) {
    if (away == target) return
    walking = if (target > away) LEFT else RIGHT
    try {
        var last = 0L
        while (away != target) {
            withFrameNanos { now ->
                val seconds = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.1f)
                last = now
                val stride = WALK_SPEED * seconds
                away = if (target > away) min(target, away + stride) else max(target, away - stride)
            }
        }
    } finally {
        walking = 0
    }
}

/**
 * Its body clock, by the hour: how likely it is to be found asleep, how
 * often (at each chance to nap) and how long it naps, and how often it goes
 * for a stroll when it could. Sleepy at night (11pm to 7am), lively in the
 * evening (6 to 11pm), and in between those by day.
 */
internal class Mood(
    val startAsleep: Float,
    val napChance: Float,
    val napMinMs: Long,
    val napMaxMs: Long,
    val strollChance: Float
)

private val DAY = Mood(startAsleep = 0.25f, napChance = 0.35f, napMinMs = 30_000L, napMaxMs = 60_000L, strollChance = 0.65f)
private val EVENING = Mood(startAsleep = 0.1f, napChance = 0.15f, napMinMs = 20_000L, napMaxMs = 40_000L, strollChance = 0.85f)
private val NIGHT = Mood(startAsleep = 0.6f, napChance = 0.7f, napMinMs = 60_000L, napMaxMs = 120_000L, strollChance = 0.3f)

internal fun moodAt(hour: Int): Mood = when (hour) {
    in 7..17 -> DAY
    in 18..22 -> EVENING
    else -> NIGHT
}

private fun CotCatState.mood(): Mood = moodAt(hour())

/** One of the sheet's animations: its frames, by their place on the sheet
 *  (left to right, then down), and how fast they play, looping. */
private class CatAnimation(val frames: IntArray, fps: Int) {
    val frameMs = 1_000L / fps
}

// The sheet's frames: 0-3 walking, 4 sitting, 5 blinking, 6 an ear flicked,
// 7-9 scratching with the right paw, 10-12 with the left, 13-20 dozing,
// 21-22 stretching up (2, then 4 of its pixels taller), 23 yawning at full
// stretch.
private val WALK = CatAnimation(intArrayOf(0, 1, 2, 3), fps = 8)
private val SIT = CatAnimation(intArrayOf(4, 4, 4, 4, 4, 5, 4, 4, 6, 4), fps = 4)
private val SCRATCH = CatAnimation(intArrayOf(7, 8, 9, 10, 11, 12), fps = 9)
private val DOZE = CatAnimation(intArrayOf(13, 14, 15, 16, 17, 18, 19, 20), fps = 4)

/** Played once, waking up: eyes still shut, up into a stretch, a long
 *  yawn, back down, and eyes open. */
private val WAKE = CatAnimation(intArrayOf(5, 21, 22, 22, 23, 23, 23, 23, 22, 21, 5, 4), fps = 6)

/** Played once, stopping to think on a stroll: a blink. */
private val PONDER = CatAnimation(intArrayOf(4, 4, 5, 5, 4, 4), fps = 6)

private const val CELL_W = 38
private const val CELL_H = 32
private const val SHEET_COLUMNS = 8

private const val LEFT = -1
private const val RIGHT = 1

/** Awake 25-50s between chances to nap (how likely it is to take one, and
 *  for how long, is down to its body clock: see [Mood]). Its first change on
 *  arriving at the home page (waking, or its first chance to nap) comes after
 *  anywhere from 3s up to the usual maximum. */
private const val FIRST_CHANGE_MIN_MS = 3_000L
private const val AWAKE_MIN_MS = 25_000L
private const val AWAKE_MAX_MS = 50_000L

/** Awake, it sits 4-10s at a time, and then, if its body clock says so and
 *  there's room (two of its own lengths at least), goes for a stroll: one to
 *  three legs, at a walk of 24 of its pixels a second, with a sit of 1.5-4s,
 *  or a stop of a moment to turn round, at the end of each; one leg in four
 *  it gives up on 30-70% of the way along. A tap while it's awake gets a
 *  scratch: twice through its frames. */
private const val SIT_MIN_MS = 4_000L
private const val SIT_MAX_MS = 10_000L
private const val SCRATCH_LOOPS = 2
private const val MIN_STROLL = 2f * CELL_W
private const val MAX_LEGS = 3
private const val MIN_LEG = 24f
private const val REST_MIN_MS = 1_500L
private const val REST_MAX_MS = 4_000L
private const val TURN_MIN_MS = 300L
private const val TURN_MAX_MS = 700L
private const val WALK_SPEED = 24f
private const val CHANGE_MIND_CHANCE = 0.25f
private const val CHANGE_MIND_MIN = 0.3f
private const val CHANGE_MIND_MAX = 0.7f

/** How strong its shadow is: the theme's text colour, faint. */
private const val SHADOW_ALPHA = 0.16f

/** The start of the row it always stays out of: the search icon and the
 *  gap after it. */
private val CLEAR_OF_ICON = 36.dp

/** How far off the cat a tap can land and still count as on it. */
private val TAP_SLOP = 6.dp
