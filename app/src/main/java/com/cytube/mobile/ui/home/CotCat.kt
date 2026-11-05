package com.cytube.mobile.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.cytube.mobile.R
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Prince's state. Kept by the home screen rather than by [Prince] itself:
 * the search bar sits in the channel list, which drops it while it's
 * scrolled out of view, and Prince shouldn't start its day over each time
 * it scrolls back.
 */
@Stable
class PrinceState {
    /** For all its choices (when to nap, where to go, which letter): a test
     *  can make them the same every time. */
    internal var random: Random = Random

    /** The hour of the day (0-23) for its body clock, [Mood]. A test can
     *  set the time here, or its moods themselves. */
    internal var hour: () -> Int = { LocalTime.now().hour }
    internal var moods: (hour: Int) -> Mood = { moodAt(it) }

    /** Today, for its hat ([hatFor]). A test can set the day. */
    internal var today: () -> LocalDate = { LocalDate.now() }

    /** Each visit to the home page finds it at a random point in its day:
     *  some of the time already asleep, more so at night. */
    internal var asleep by mutableStateOf(random.nextFloat() < mood().startAsleep)

    /** Waking up: a stretch and a yawn. */
    internal var waking by mutableStateOf(false)

    /** A paw up for a quick scratch, when it's tapped awake (only then). */
    internal var scratching by mutableStateOf(false)

    /** Stopped partway through a stroll, thinking better of it. */
    internal var pondering by mutableStateOf(false)

    /** A swish of its tail, sitting. */
    internal var wagging by mutableStateOf(false)

    /** [LEFT] or [RIGHT] while it's walking, else 0. */
    internal var walking by mutableIntStateOf(0)

    /** Which way it last walked: the way it faces side-on ([sideOn]). */
    internal var facing by mutableIntStateOf(RIGHT)

    /** Standing side-on rather than sitting: busy with a letter. */
    internal var sideOn by mutableStateOf(false)

    /** How far off the ground it is, in its own pixels: hopping up for a letter. */
    internal var hop by mutableFloatStateOf(0f)

    /** Cross: it's just had a letter knocked out of its mouth (a tap). */
    internal var angry by mutableStateOf(false)

    /** Hearts and sparkles round it: a tap it liked (see [love]). */
    internal val sparkles = mutableStateListOf<Sparkle>()

    /** Whether it was in the middle of waking when it was last tapped: no
     *  hearts for that, only a scratch. */
    internal var tappedWaking = false

    /** When its recent taps came (uptime ms), and how many in a row: enough
     *  and it may get up and go (see [fedUp]). */
    internal val tapTimes = ArrayDeque<Long>()
    internal var tapStreak = 0

    /** Found out along the bar on the page opening (see [seed]): how far
     *  along its room, as a share, once that's known; then null. */
    internal var opening: Float? = null

    /** How far it is to the left of its spot, in the sprite's own pixels: to
     *  a fraction of one while it walks, but drawn to the whole pixel, so it's
     *  [awayPx] (that whole pixel) that's watched for redrawing it. A walk
     *  then redraws it only as it visibly moves (24 times a second), not on
     *  every frame. */
    internal var away: Float = 0f
        set(value) {
            field = value
            val px = value.roundToInt()
            if (px != awayPx) awayPx = px
        }
    internal var awayPx by mutableIntStateOf(0)
        private set

    /** Counts the frames of a walk with a letter in its mouth: the letter
     *  swings every frame, so it's redrawn every frame then. */
    internal var swingFrames by mutableIntStateOf(0)

    /** Taps on it so far (see [princeTaps]): each sets it scratching, wakes
     *  it if it's asleep, or makes it drop the letter it's carrying. */
    internal var taps by mutableIntStateOf(0)

    /** How far left of its spot it can go, in sprite pixels: to just past
     *  the search icon. And how much of that it'll stop and sit down in: the
     *  empty half of its row, clear of the search hint. Both worked out from
     *  where its spot is (see [Prince]). */
    internal var room = 0f
    internal var restRoom = 0f

    /** Screen pixels per sprite pixel, and its spot's size, as last laid out. */
    internal var pixel = 1
    internal var spotSize = IntSize.Zero

    /** Where it was last drawn, in its spot's coordinates (left of the spot
     *  while it's out walking): the box round its own pixels, for telling
     *  whether a tap is on it. And where its spot and the bar are. */
    internal var drawn = Rect.Zero
    internal var spot: LayoutCoordinates? = null
    internal var bar: LayoutCoordinates? = null

    /** The search hint it takes letters from ([PrinceHint]): its text, laid
     *  out, and where that is. */
    internal var hint: TextLayoutResult? = null
    internal var hintAt: LayoutCoordinates? = null

    /** Letters it had already taken before the page opened (see [seed]),
     *  by their place in the hint, not yet laid on its pile. */
    internal val pretaken = mutableStateListOf<Int>()

    /** The hint's letter in its mouth (by its place in the hint), or null. */
    internal var carrying by mutableStateOf<Int?>(null)

    /** Letters it's taken from the hint and dropped: where they lie. */
    internal val dropped = mutableStateListOf<DroppedLetter>()

    /** Letters on their way back into the hint (by their place in it), and
     *  when each started fading back in. */
    internal val returning = mutableStateMapOf<Int, Long>()

    /** The frame clock, in ms: kept up to date while letters are falling or
     *  fading, for drawing them. */
    internal var clock by mutableLongStateOf(0L)

    internal var started = false
    internal var lastWakeKey: Any? = null
    internal var lastTaps = 0
}

/**
 * A letter it dropped: which one ([index] in the hint, [char]), where it
 * left its mouth and where it came to rest (its middle, and its baseline, in
 * Prince's spot's pixels: on the line, or on top of the pile), its place in
 * the pile ([slot], or -1 if it was dropped elsewhere), how it lies, when it
 * was dropped ([at]: five minutes on, it goes back), and when it last began
 * to fall ([fellAt]: from its mouth, or off the pile when what it lay on went
 * back).
 */
/** A heart or sparkle round it: which ([kind], in [LOVE_ART]), where its
 *  top left starts (in its spot's pixels), how far it rises (sprite pixels),
 *  and when it shows, for how long. */
internal class Sparkle(val kind: Int, val x: Int, val y: Int, val rise: Float, val at: Long, val life: Long)

internal class DroppedLetter(
    val index: Int,
    val char: Char,
    val slot: Int,
    val fromX: Float,
    val fromBaseline: Float,
    val fromTilt: Float,
    val x: Float,
    val baseline: Float,
    val tilt: Float,
    val width: Float,
    val height: Float,
    val at: Long,
    val fellAt: Long = at
)

/** Whether [a] lies on [b] (or would): over a third of the narrower one's width. */
private fun overlaps(a: DroppedLetter, b: DroppedLetter) =
    (a.width + b.width) / 2f - abs(a.x - b.x) > PILE_OVERLAP * min(a.width, b.width)

/** A fresh Prince for the page: given the search bar's [hint], he may
 *  already have had a letter or two of it while you were away (see [seed]). */
@Composable
fun rememberPrinceState(hint: String? = null): PrinceState =
    remember { PrinceState().apply { if (hint != null) seed(hint) } }

/** By his body clock, he may already have taken one or two letters of
 *  [hint]; and (if he's not asleep) be out along the bar, walking home, or
 *  bringing one more home in his mouth. */
internal fun PrinceState.seed(hint: String) {
    val mood = mood()
    val letters = hint.indices.filter { hint[it].isLetter() }.shuffled(random)
    if (random.nextFloat() < mood.startWithLetters) pretaken += letters.take(if (random.nextBoolean()) 1 else 2)
    if (asleep) return
    val r = random.nextFloat()
    if (r >= mood.startWalking + mood.startCarrying) return
    if (r >= mood.startWalking) carrying = letters.firstOrNull { it !in pretaken } ?: return
    opening = OPENING_MIN + random.nextFloat() * (1f - OPENING_MIN)
}

/**
 * A tap on Prince, wherever it's got to along the bar (out on a stroll it's
 * outside its own spot), sets it scratching, wakes it if it's asleep, or
 * makes it drop the letter it's carrying, and the bar doesn't take it as a
 * tap of its own: the keyboard stays down. A tap anywhere else, or a scroll
 * that starts on Prince, goes to the bar as usual. Goes on the bar it sits in.
 */
fun Modifier.princeTaps(state: PrinceState): Modifier = this
    .onPlaced { state.bar = it }
    .pointerInput(state) {
        val slop = TAP_SLOP.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!state.isUnder(down.position, slop)) return@awaitEachGesture
            // His from the start: the bar never sees the press, so it can't
            // take quick taps on him for a double tap, or a long one for a
            // press and hold, and focus itself. (A finger that moves off is
            // let go of, below, for the page to scroll.)
            down.consume()
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes
                    .firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                // Moved off it: a scroll, not a tap.
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
                if (!change.pressed) {
                    change.consume()
                    state.tappedWaking = state.waking
                    val t = change.uptimeMillis
                    state.tapTimes.addLast(t)
                    while (t - state.tapTimes.first() > TAP_STREAK_MS) state.tapTimes.removeFirst()
                    state.tapStreak = state.tapTimes.size
                    state.taps++
                    return@awaitEachGesture
                }
            }
        }
    }

/** Whether [position], on the bar, is on Prince, give or take [slop] pixels. */
private fun PrinceState.isUnder(position: Offset, slop: Float): Boolean {
    val spot = spot?.takeIf { it.isAttached } ?: return false
    val bar = bar?.takeIf { it.isAttached } ?: return false
    return drawn.inflate(slop).contains(spot.localPositionOf(bar, position))
}

/**
 * The search bar's hint, for Prince to take letters from: [text] in
 * [style] (the bar's own), in the hint colour, with a gap wherever it's
 * taken one (the other letters stay where they are), and a letter coming
 * back fading in. Goes where the bar shows its hint.
 */
@Composable
fun PrinceHint(state: PrinceState, text: String, style: TextStyle) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val shown = buildAnnotatedString {
        text.forEachIndexed { i, c ->
            val alpha = state.hintAlpha(i)
            if (alpha >= 1f) append(c) else withStyle(SpanStyle(color = color.copy(alpha = alpha))) { append(c) }
        }
    }
    Text(
        shown,
        style = style,
        color = color,
        maxLines = 1,
        onTextLayout = { state.hint = it },
        modifier = Modifier.onPlaced { state.hintAt = it }
    )
}

/** How much of the hint's letter [i] shows: none while Prince has it,
 *  fading in on its way back, else all of it. */
internal fun PrinceState.hintAlpha(i: Int): Float {
    if (carrying == i || i in pretaken || dropped.any { it.index == i }) return 0f
    val back = returning[i] ?: return 1f
    return ((clock - back) / RETURN_MS.toFloat()).coerceIn(0f, 1f)
}

/**
 * Prince, who lives in the home search bar when cot is on (Settings >
 * Appearance, light or dark): a pixel-art tuxedo cat, from the sprite sheet
 * res/drawable-nodpi/tuxedo_cat.png (38 x 32 pixel frames, eight to a row,
 * Prince's feet on each frame's bottom edge). It's drawn with each of its
 * pixels as a whole number of screen pixels, the nearest to it being about
 * 32dp tall sitting (see [princePixelSize]), and not smoothed, so it stays
 * crisp; feet on its spot's bottom edge, at the right of its spot, with a
 * soft shadow under them. It's free to reach out of its spot: up, and left
 * along the bar.
 *
 * Sitting, it blinks, flicks an ear and now and then swishes its tail, (with
 * [roam]) often goes for a stroll along its row, back and forth and home
 * again, now and then thinking better of a leg of it partway and turning
 * back, and every so often it dozes off for a while, waking with a stretch
 * and a yawn. Sometimes, instead of a stroll, it goes and takes a letter out
 * of the search hint ([PrinceHint]): hops up for it, carries it home in its
 * mouth and drops it beside its spot, where they pile up; each goes back
 * into the hint five minutes after it's dropped. All by its body clock:
 * sleepy at night, lively in the evening (see [Mood]). Only when it's tapped
 * (see [princeTaps]) does it have a scratch, or now and then hearts and
 * sparkles round its head and a swish of its tail; tapped while it's asleep, it
 * wakes, with its stretch and yawn; tapped carrying a letter, it drops it
 * there and then, cross. Purely decorative.
 *
 * Changing [wakeKey] (the search text) wakes it up, sends it back to its
 * spot if it's out on a stroll (with any letter it has, to its pile), and
 * restarts its timer, so it doesn't nod off while you're typing.
 */
@Composable
fun Prince(
    state: PrinceState,
    modifier: Modifier = Modifier,
    wakeKey: Any? = null,
    /** Whether it may go for a stroll, or for a letter: not over search
     *  text, say. It walks left of its spot, along the row it's in, outside
     *  its own bounds. */
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
        val random = state.random
        var awakeFor = random.nextLong(AWAKE_MIN_MS, AWAKE_MAX_MS)
        state.hop = 0f
        when {
            // Asleep, it wakes with a stretch and a yawn; carrying a letter,
            // it drops it, cross; else (awake, or already waking) it has a
            // scratch. Each where it is.
            tapped -> when {
                state.asleep -> state.wakeUp()
                state.carrying != null -> state.dropInAnger()
                !state.tappedWaking && canRoam && state.tapStreak >= FED_UP_TAPS &&
                    random.nextFloat() < state.mood().fedUpChance -> state.fedUp()
                !state.tappedWaking && random.nextFloat() < state.mood().loveChance -> state.love()
                else -> state.scratch()
            }
            typed || keepAwake -> state.wakeUp()
            state.asleep -> {
                // Found asleep: wakes some time into the rest of its nap.
                delay(random.nextLong(FIRST_CHANGE_MIN_MS, state.mood().napMaxMs))
                state.wakeUp()
            }
            // Found awake: somewhere into its time awake.
            else -> awakeFor = random.nextLong(FIRST_CHANGE_MIN_MS, AWAKE_MAX_MS)
        }
        // Found out along the bar on the page opening: placed there once it's
        // laid out (see seed), then on its way.
        while (state.opening != null) withFrameMillis { }
        // Out on a stroll when that happened: back to its spot, by way of
        // its pile with any letter it had.
        state.goHome()
        while (true) {
            state.stayAwake(awakeFor) { canRoam }
            val mood = state.mood()
            if (mayNap && random.nextFloat() < mood.napChance) {
                state.asleep = true
                delay(random.nextLong(mood.napMinMs, mood.napMaxMs))
                state.wakeUp()
            }
            awakeFor = random.nextLong(AWAKE_MIN_MS, AWAKE_MAX_MS)
        }
    }

    // The letters it's dropped: kept moving while they fall or fade, and
    // each back into the hint five minutes after it was dropped.
    LaunchedEffect(state, state.dropped.size, state.returning.size, state.sparkles.size, state.pretaken.size) {
        while (true) {
            val now = withFrameMillis { it }
            state.clock = now
            if (state.pretaken.isNotEmpty()) state.layPretaken(now)
            val wait = state.tidyLetters(now)
            if (wait == Long.MAX_VALUE) awaitCancellation()
            if (wait > 0) delay(wait)
        }
    }

    val animation = when {
        state.asleep -> DOZE
        state.waking -> WAKE
        state.walking != 0 -> WALK
        state.hop > 0f -> HOP
        state.angry || state.sideOn || state.carrying != null -> STAND
        state.scratching -> SCRATCH
        state.pondering -> PONDER
        state.wagging -> WAG
        else -> SIT
    }
    // Which of [animation]'s frames is showing. Read only while drawing, so
    // stepping it redraws Prince without recomposing anything.
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(animation) {
        step = 0
        while (true) {
            delay(animation.frameMs)
            step = (step + 1) % animation.frames.size
        }
    }

    val sheet = ImageBitmap.imageResource(R.drawable.tuxedo_cat)
    val shapes = remember(sheet) { shapesOf(sheet) }
    val shadow = MaterialTheme.colorScheme.onSurface.copy(alpha = SHADOW_ALPHA)
    val density = LocalDensity.current
    val clearOfIcon = with(density) { CLEAR_OF_ICON.toPx() }
    val pixel = princePixelSize(density.density)
    val hat = hatFor(state.today())
    val measurer = rememberTextMeasurer()
    val glyphs = remember(measurer) { HashMap<Pair<Char, TextStyle>, TextLayoutResult>() }
    Canvas(
        // On a layer of its own, so redrawing it doesn't redraw the bar
        // round it. Not clipped: it still reaches out of its spot.
        modifier.graphicsLayer().onPlaced { spot ->
            state.spot = spot
            state.pixel = pixel
            state.spotSize = spot.size
            // Room for a stroll: from its spot, left along the row it's in to
            // just past the search icon at its start, sitting down only in
            // the row's empty half (the search hint is in the other).
            val row = spot.parentLayoutCoordinates ?: return@onPlaced
            val home = row.localPositionOf(spot, Offset.Zero).x + state.homeLeft()
            state.room = ((home - clearOfIcon) / pixel).coerceAtLeast(0f)
            state.restRoom = ((home - max(clearOfIcon, row.size.width / 2f)) / pixel).coerceAtLeast(0f)
            state.opening?.let { share ->
                val from = if (state.carrying != null) PILE_AWAY + MIN_LEG else MIN_LEG
                state.away = (from + share * (state.room - from)).coerceIn(0f, state.room).roundToInt().toFloat()
                state.opening = null
            }
        }
    ) {
        val frame = animation.frames[step % animation.frames.size]
        val side = animation.sideOn
        val mirror = side && state.facing == LEFT
        val width = CELL_W * pixel
        val height = CELL_H * pixel
        // Whole sprite pixels away from its spot, so it stays crisp walking.
        val x = state.homeLeft() - state.awayPx * pixel
        val ground = size.height.roundToInt()
        val y = ground - height - (state.hop * pixel).roundToInt()
        val style = state.hint?.layoutInput?.style

        val shape = shapes[frame]
        val box = shape.box
        val (boxLeft, boxRight) = if (mirror) CELL_W - 1 - box.right to CELL_W - 1 - box.left else box.left to box.right
        state.drawn = Rect(
            (x + boxLeft * pixel).toFloat(), (y + box.top * pixel).toFloat(),
            (x + (boxRight + 1) * pixel).toFloat(), (y + (box.bottom + 1) * pixel).toFloat()
        )
        // Side-on, it faces right on the sheet: mirrored to face left.
        scale(scaleX = if (mirror) -1f else 1f, scaleY = 1f, pivot = Offset(x + width / 2f, (ground - height) + height / 2f)) {
            // Its shadow, two of its pixels deep and on the ground (it stays
            // there while it hops): as wide as its feet in the bottom row, a
            // little narrower just below it.
            val (left, right) = shape.feet
            if (right >= left) {
                fun band(row: Int, from: Int, to: Int) = drawRect(
                    shadow,
                    topLeft = Offset((x + from * pixel).toFloat(), (ground - height + row * pixel).toFloat()),
                    size = Size(((to - from + 1) * pixel).toFloat(), pixel.toFloat())
                )
                band(CELL_H - 1, left - 1, right + 1)
                band(CELL_H, left + 1, right - 1)
            }
            fun cell(index: Int) = drawImage(
                image = sheet,
                srcOffset = IntOffset(index % SHEET_COLUMNS * CELL_W, index / SHEET_COLUMNS * CELL_H),
                srcSize = IntSize(CELL_W, CELL_H),
                dstOffset = IntOffset(x, y),
                dstSize = IntSize(width, height),
                filterQuality = FilterQuality.None
            )
            cell(frame)
            // Its hat for the day, if there's one, on its head as it is in this frame.
            if (hat != null) {
                val art = if (side) hat.side else hat.front
                val (headX, headY) = hatAnchor(frame)
                drawImage(
                    image = sheet,
                    srcOffset = IntOffset(art.x, art.y),
                    srcSize = IntSize(art.w, art.h),
                    dstOffset = IntOffset(x + (headX - art.ax) * pixel, y + (headY - art.ay) * pixel),
                    dstSize = IntSize(art.w * pixel, art.h * pixel),
                    filterQuality = FilterQuality.None
                )
            }
            if (state.angry) cell(ANGER_MARK)
        }

        // The letters it's dropped, in front of it, so a falling one shows.
        if (style != null) for (letter in state.dropped) drawDropped(letter, state.clock, measurer, glyphs, style)

        // The letter in its mouth: hanging just below it, over its white
        // chest, swinging as it walks. Never mirrored, so it reads right.
        val carried = state.carrying
        val hint = state.hint
        if (carried != null && hint != null && style != null && side) {
            val mouthX = x + (if (mirror) CELL_W - MOUTH_X else MOUTH_X) * pixel
            val mouthY = y + MOUTH_Y * pixel
            val c = hint.layoutInput.text.text.getOrNull(carried)
            if (c != null) {
                val glyph = glyphs.getOrPut(c to style) { measurer.measure(c.toString(), style) }
                val ink = inkHeight(c, glyph)
                state.swingFrames   // swinging: redrawn every frame of the walk
                val swing = if (state.walking != 0) SWING_DEGREES * sin(state.away * 2f * PI.toFloat() / SWING_PIXELS) else 0f
                rotate(swing, pivot = Offset(mouthX, mouthY)) {
                    drawText(glyph, topLeft = Offset(mouthX - glyph.size.width / 2f, mouthY + ink - glyph.firstBaseline))
                }
            }
        }

        // Hearts and sparkles round it, over everything.
        for (sparkle in state.sparkles) drawSparkle(sparkle, state.clock, sheet, pixel)
    }
}

/** A dropped letter, where it's got to at [clock]: falling from its mouth to
 *  where it lies, lying there, or fading away before it goes back. */
private fun DrawScope.drawDropped(
    letter: DroppedLetter,
    clock: Long,
    measurer: TextMeasurer,
    glyphs: HashMap<Pair<Char, TextStyle>, TextLayoutResult>,
    style: TextStyle
) {
    val age = (clock - letter.at).coerceAtLeast(0L)
    val fall = ((clock - letter.fellAt) / FALL_MS.toFloat()).coerceIn(0f, 1f)
    val eased = fall * fall
    val x = letter.fromX + (letter.x - letter.fromX) * fall
    val baseline = letter.fromBaseline + (letter.baseline - letter.fromBaseline) * eased
    val tilt = letter.fromTilt + (letter.tilt - letter.fromTilt) * fall
    val alpha = 1f - ((age - LIE_MS) / FADE_MS.toFloat()).coerceIn(0f, 1f)
    if (alpha <= 0f) return
    val glyph = glyphs.getOrPut(letter.char to style) { measurer.measure(letter.char.toString(), style) }
    translate(left = x, top = baseline) {
        rotate(tilt, pivot = Offset.Zero) {
            drawText(glyph, topLeft = Offset(-glyph.size.width / 2f, -glyph.firstBaseline), alpha = alpha)
        }
    }
}

/** How tall [c]'s ink is above its baseline in [glyph]'s font (the hint's
 *  has no letters reaching below it): a capital's or a tall letter's height,
 *  or a small letter's. */
private fun inkHeight(c: Char, glyph: TextLayoutResult): Float {
    val size = glyph.layoutInput.style.fontSize.value * glyph.layoutInput.density.fontScale * glyph.layoutInput.density.density
    return size * if (c.isUpperCase() || c in TALL_LETTERS) TALL_INK else SMALL_INK
}

/**
 * Screen pixels per sprite pixel at [density] (screen pixels per dp): the
 * whole number nearest to its frames being [CELL_HEIGHT_DP] tall, so that
 * sitting it's about 32dp tall on any phone; at least one.
 */
internal fun princePixelSize(density: Float): Int = max(1, (CELL_HEIGHT_DP * density / CELL_H).roundToInt())

/** Where its frame's left edge is when it's home, in its spot: at the
 *  right, its furthest-reaching frame (walking, nose first) just inside. */
private fun PrinceState.homeLeft(): Int = spotSize.width - REACH_RIGHT * pixel

/** What each frame of the sheet has where: the first and last column with
 *  something in its bottom two rows, where its feet (and tail) are, or
 *  (0, -1) for none; and the box round all of it. */
private class FrameShape(val feet: Pair<Int, Int>, val box: BoxInt)

private class BoxInt(val left: Int, val top: Int, val right: Int, val bottom: Int)

private fun shapesOf(sheet: ImageBitmap): Array<FrameShape> {
    val pixels = IntArray(sheet.width * sheet.height)
    sheet.readPixels(pixels)
    val columns = sheet.width / CELL_W
    return Array(columns * (sheet.height / CELL_H)) { i ->
        val x0 = i % columns * CELL_W
        val y0 = i / columns * CELL_H
        var feetLeft = CELL_W
        var feetRight = -1
        var left = CELL_W
        var top = CELL_H
        var right = -1
        var bottom = -1
        for (y in 0 until CELL_H) for (x in 0 until CELL_W) {
            if (pixels[(y0 + y) * sheet.width + x0 + x] ushr 24 == 0) continue
            left = min(left, x); right = max(right, x); top = min(top, y); bottom = max(bottom, y)
            if (y >= CELL_H - 2) {
                feetLeft = min(feetLeft, x)
                feetRight = max(feetRight, x)
            }
        }
        FrameShape(
            feet = if (feetRight < 0) 0 to -1 else feetLeft to feetRight,
            box = if (right < 0) BoxInt(0, 0, -1, -1) else BoxInt(left, top, right, bottom)
        )
    }
}

/**
 * Sits for [ms], every so often going for a stroll (now and then for a
 * letter instead) when [roam] says so and there's room, or else maybe
 * swishing its tail. Timed by the frame clock, as everything else about it is.
 */
private suspend fun PrinceState.stayAwake(ms: Long, roam: () -> Boolean) {
    val start = withFrameMillis { it }
    while (true) {
        val left = ms - (withFrameMillis { it } - start)
        val sit = random.nextLong(SIT_MIN_MS, SIT_MAX_MS)
        if (sit >= left) {
            delay(left.coerceAtLeast(0L))
            return
        }
        delay(sit)
        val mood = mood()
        val roaming = roam() && room >= MIN_STROLL
        val letter = if (roaming && random.nextFloat() < mood.fetchChance) pickLetter() else null
        when {
            letter != null -> fetch(letter)
            roaming && random.nextFloat() < mood.strollChance -> stroll()
            random.nextFloat() < mood.wagChance -> wag()
        }
    }
}

/** Wakes up, if it's asleep, with a stretch and a yawn. */
private suspend fun PrinceState.wakeUp() {
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
private suspend fun PrinceState.scratch() {
    scratching = true
    try {
        delay(SCRATCH.frames.size * SCRATCH.frameMs * SCRATCH_LOOPS)
    } finally {
        scratching = false
    }
}

/** A swish of its tail, sitting: twice from side to side. */
private suspend fun PrinceState.wag() {
    wagging = true
    try {
        delay(WAG.frames.size * WAG.frameMs)
    } finally {
        wagging = false
    }
}

/** Off along the row: one or two legs back and forth, with a sit at the
 *  end of one in the row's empty half (where it isn't, only a stop to turn
 *  round, so it doesn't sit on the search hint), then back to its spot. Now
 *  and then it thinks better of a leg partway: stops, blinks, and goes back
 *  the way it came. */
private suspend fun PrinceState.stroll() {
    repeat(random.nextInt(1, MAX_LEGS + 1)) {
        val from = away
        val target = somewhereElse()
        if (random.nextFloat() < CHANGE_MIND_CHANCE) {
            val share = CHANGE_MIND_MIN + random.nextFloat() * (CHANGE_MIND_MAX - CHANGE_MIND_MIN)
            walkTo((from + (target - from) * share).roundToInt().toFloat())
            ponder()
            walkTo(from)
        } else {
            walkTo(target)
            delay(if (target <= restRoom) random.nextLong(REST_MIN_MS, REST_MAX_MS) else random.nextLong(TURN_MIN_MS, TURN_MAX_MS))
        }
    }
    walkTo(0f)
}

/** A stop to think about it, with a blink. */
private suspend fun PrinceState.ponder() {
    pondering = true
    try {
        delay(PONDER.frames.size * PONDER.frameMs)
    } finally {
        pondering = false
    }
}

/** Somewhere along its row, in sprite pixels left of its spot, at least a
 *  short walk from where it is. */
private fun PrinceState.somewhereElse(): Float {
    repeat(8) {
        val target = (random.nextFloat() * room).roundToInt().toFloat()
        if (abs(target - away) >= MIN_LEG) return target
    }
    return if (away < room / 2f) room.roundToInt().toFloat() else 0f
}

/** Walks at a steady pace until it's [target] sprite pixels left of its spot. */
private suspend fun PrinceState.walkTo(target: Float) {
    if (away == target) return
    walking = if (target > away) LEFT else RIGHT
    facing = walking
    try {
        var last = 0L
        while (away != target) {
            withFrameNanos { now ->
                val seconds = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.1f)
                last = now
                val stride = WALK_SPEED * seconds
                away = if (target > away) min(target, away + stride) else max(target, away - stride)
                if (carrying != null) swingFrames++
            }
        }
    } finally {
        walking = 0
    }
}

/** Home to its spot; first to its pile, with any letter it's carrying, to
 *  drop it there. */
private suspend fun PrinceState.goHome() {
    if (carrying != null) {
        sideOn = true
        try {
            walkTo(PILE_AWAY)
            dropLetter()
        } finally {
            sideOn = false
        }
    }
    walkTo(0f)
}

/**
 * Off for letter [index] of the hint: walks along under it, hops up and takes
 * it in its mouth (leaving a gap in the hint), carries it home and drops it on
 * its pile, beside its spot, and has a swish of its tail about it.
 */
private suspend fun PrinceState.fetch(index: Int) {
    val target = reachFor(index) ?: return
    sideOn = true
    try {
        walkTo(target.roundToInt().toFloat())
        facing = LEFT
        hopFor(index)
        delay(TURN_MIN_MS)
        walkTo(PILE_AWAY)
        dropLetter()
        delay(TURN_MIN_MS)
    } finally {
        sideOn = false
    }
    walkTo(0f)
    wag()
}

/** Up and down again, taking letter [index] at the top. */
private suspend fun PrinceState.hopFor(index: Int) {
    try {
        val start = withFrameMillis { it }
        var taken = false
        while (true) {
            val t = (withFrameMillis { it } - start) / HOP_MS.toFloat()
            if (t >= 1f) break
            hop = HOP_PIXELS * sin(PI.toFloat() * t)
            if (!taken && t >= 0.5f) {
                carrying = index
                taken = true
            }
        }
        if (!taken) carrying = index
    } finally {
        hop = 0f
    }
}

/** Tapped once too often: up and off, now and then for a letter, else
 *  somewhere else along the empty half of the bar for a sit. */
private suspend fun PrinceState.fedUp() {
    tapTimes.clear()
    tapStreak = 0
    val letter = if (random.nextFloat() < mood().fedUpFetch) pickLetter() else null
    if (letter != null) return fetch(letter)
    val target = somewhereToSit() ?: return scratch()
    walkTo(target)
    delay(random.nextLong(FED_UP_SIT_MIN_MS, FED_UP_SIT_MAX_MS))
}

/** A place to sit in the empty half of the row, a short walk at least from
 *  where it is; null if there's no such place. */
private fun PrinceState.somewhereToSit(): Float? {
    if (restRoom < MIN_LEG) return null
    repeat(8) {
        val target = (MIN_LEG + random.nextFloat() * (restRoom - MIN_LEG)).roundToInt().toFloat()
        if (abs(target - away) >= MIN_LEG) return target
    }
    return null
}

/** A tap it liked: hearts and sparkles round its head, and a swish of its tail. */
private suspend fun PrinceState.love() {
    val now = withFrameMillis { it }
    clock = now
    val cellLeft = homeLeft() - away.roundToInt() * pixel
    val cellTop = spotSize.height - CELL_H * pixel
    LOVE_BURST.forEachIndexed { i, (kind, dx, dy) ->
        val art = LOVE_ART[kind]
        val heart = kind < STAR
        sparkles += Sparkle(
            kind = kind,
            x = cellLeft + (HEAD_X + dx + random.nextInt(-1, 2) - art.w / 2) * pixel,
            y = cellTop + (HEAD_Y + dy + random.nextInt(-1, 2) - art.h / 2) * pixel,
            rise = if (heart) HEART_RISE else SPARKLE_RISE,
            at = now + i * LOVE_STAGGER_MS,
            life = if (heart) HEART_LIFE_MS else SPARKLE_LIFE_MS
        )
    }
    wag()
}

/** A heart or sparkle at [clock]: rising (a heart further), a sparkle
 *  twinkling, both fading out over the end of their time. Whole sprite
 *  pixels, like it. */
private fun DrawScope.drawSparkle(s: Sparkle, clock: Long, sheet: ImageBitmap, pixel: Int) {
    val t = clock - s.at
    if (t < 0 || t >= s.life) return
    val p = t / s.life.toFloat()
    val art = LOVE_ART[s.kind]
    var alpha = 1f - ((p - LOVE_FADE_FROM) / (1f - LOVE_FADE_FROM)).coerceIn(0f, 1f)
    if (s.kind >= STAR && (t / TWINKLE_MS) % 3 == 2L) alpha *= TWINKLE_DIM
    drawImage(
        image = sheet,
        srcOffset = IntOffset(LOVE_CELL % SHEET_COLUMNS * CELL_W + art.x, LOVE_CELL / SHEET_COLUMNS * CELL_H + art.y),
        srcSize = IntSize(art.w, art.h),
        dstOffset = IntOffset(s.x, s.y - (s.rise * p).toInt() * pixel),
        dstSize = IntSize(art.w * pixel, art.h * pixel),
        alpha = alpha,
        filterQuality = FilterQuality.None
    )
}

/** Drops the letter it's carrying, there and then, cross about it. */
private suspend fun PrinceState.dropInAnger() {
    dropLetter()
    angry = true
    try {
        delay(ANGRY_MS)
    } finally {
        angry = false
    }
}

/** Lets go of the letter in its mouth: it falls to the line where it is, or
 *  onto the letters already there, landing at a slant. */
private suspend fun PrinceState.dropLetter() {
    val index = carrying ?: return
    val now = withFrameMillis { it }
    clock = now
    carrying = null
    val hint = hint ?: return
    val c = hint.layoutInput.text.text.getOrNull(index) ?: return
    val cellLeft = homeLeft() - away.roundToInt() * pixel
    val mouthX = cellLeft + (if (facing == LEFT) CELL_W - MOUTH_X else MOUTH_X) * pixel
    val mouthY = spotSize.height - CELL_H * pixel - hop * pixel + MOUTH_Y * pixel
    val atPile = facing == RIGHT && abs(away - PILE_AWAY) < 1f
    lay(index, c, atPile, mouthX, fromBaseline = mouthY + inkOf(c, hint), at = now)
}

/** Lays the letters it had already taken before the page opened on its
 *  pile, once the hint and its spot are laid out: dropped a while ago. */
private fun PrinceState.layPretaken(now: Long) {
    val hint = hint ?: return
    if (spotSize == IntSize.Zero) return
    val text = hint.layoutInput.text.text
    val pileMouthX = homeLeft() - PILE_AWAY.roundToInt() * pixel + MOUTH_X * pixel
    for (index in pretaken.toList()) {
        val c = text.getOrNull(index) ?: continue
        lay(index, c, atPile = true, mouthX = pileMouthX, fromBaseline = null,
            at = now - random.nextLong(PRETAKEN_MIN_AGO_MS, PRETAKEN_MAX_AGO_MS))
    }
    pretaken.clear()
}

/**
 * Puts letter [index] ([c]) down, let go of at [mouthX]: at its pile, in the
 * first free place there (three side by side on the line, then on top
 * between them), give or take a little; anywhere else just under where it
 * was let go. On the line, or on a letter it lands mostly on. Falling from
 * [fromBaseline], or (null) already lying there.
 */
private fun PrinceState.lay(index: Int, c: Char, atPile: Boolean, mouthX: Float, fromBaseline: Float?, at: Long) {
    val hint = hint ?: return
    val ink = inkOf(c, hint)
    val width = hint.getBoundingBox(index).width
    val unit = hint.size.width.toFloat() / max(1, hint.layoutInput.text.length)
    val slot = if (atPile) PILE_SLOTS.indices.firstOrNull { s -> dropped.none { it.slot == s } } ?: -1 else -1
    val x = mouthX + (if (slot >= 0) PILE_SLOTS[slot] * unit else 0f) +
        (random.nextFloat() - 0.5f) * 2f * DROP_JITTER * unit
    val ground = spotSize.height.toFloat()
    var baseline = ground
    for (other in dropped) {
        val overlap = (other.width + width) / 2f - abs(other.x - x)
        if (overlap > PILE_OVERLAP * min(other.width, width)) {
            baseline = min(baseline, other.baseline - other.height * PILE_REST)
        }
    }
    val tilt = (random.nextFloat() - 0.5f) * 2f * (if (baseline < ground - 1f) TOP_TILT else GROUND_TILT)
    val falls = fromBaseline != null
    dropped += DroppedLetter(
        index = index, char = c, slot = slot,
        fromX = if (falls) mouthX else x, fromBaseline = fromBaseline ?: baseline, fromTilt = if (falls) 0f else tilt,
        x = x, baseline = baseline, tilt = tilt, width = width, height = ink, at = at
    )
}

/** How tall [c]'s ink is above its baseline in the [hint]'s font. */
private fun inkOf(c: Char, hint: TextLayoutResult): Float =
    with(hint.layoutInput.density) { hint.layoutInput.style.fontSize.toPx() } *
        if (c.isUpperCase() || c in TALL_LETTERS) TALL_INK else SMALL_INK

/** How far left of its spot to walk (sprite pixels) to have its mouth, facing
 *  left, under letter [index] of the hint; null if that's out of its reach,
 *  or not a short walk past its pile (it carries them home, rightwards). */
private fun PrinceState.reachFor(index: Int): Float? {
    val hint = hint ?: return null
    val at = hintAt?.takeIf { it.isAttached } ?: return null
    val spot = spot?.takeIf { it.isAttached } ?: return null
    val box = hint.getBoundingBox(index)
    val letterX = spot.localPositionOf(at, box.center).x
    val target = (homeLeft() + (CELL_W - MOUTH_X) * pixel - letterX) / pixel
    return target.takeIf { it in (PILE_AWAY + MIN_LEG)..room }
}

/** A letter of the hint for it to take: any it can reach that's there to be
 *  taken, while it has fewer than [MAX_TAKEN] out. */
private fun PrinceState.pickLetter(): Int? {
    val text = hint?.layoutInput?.text?.text ?: return null
    if (dropped.size + pretaken.size + (if (carrying != null) 1 else 0) >= MAX_TAKEN) return null
    return text.indices
        .filter { text[it].isLetter() && hintAlpha(it) >= 1f && returning[it] == null && reachFor(it) != null }
        .randomOrNull(random)
}

/** Moves the dropped letters on at [now]: those that have lain [LIE_MS] and
 *  faded go back into the hint, and those back in are done. Returns how long
 *  until something next changes: 0 while something's moving, Long.MAX_VALUE
 *  when there's nothing out at all. */
private fun PrinceState.tidyLetters(now: Long): Long {
    var next = Long.MAX_VALUE
    val gone = dropped.filter { now - it.at >= LIE_MS + FADE_MS }
    gone.forEach {
        dropped.remove(it)
        returning[it.index] = now
    }
    if (gone.isNotEmpty()) settle(now)
    returning.filter { now - it.value >= RETURN_MS }.keys.forEach { returning.remove(it) }
    for (letter in dropped) {
        val age = now - letter.at
        next = min(next, if (now - letter.fellAt < FALL_MS || age >= LIE_MS) 0L else LIE_MS - age)
    }
    if (returning.isNotEmpty()) next = 0L
    sparkles.removeAll { now - it.at >= it.life }
    if (sparkles.isNotEmpty() || pretaken.isNotEmpty()) next = 0L
    return next
}

/** Letters left lying on nothing, the ones under them gone back, fall onto
 *  what's still there or the line: the lowest first. */
private fun PrinceState.settle(now: Long) {
    val ground = spotSize.height.toFloat()
    for (letter in dropped.sortedByDescending { it.baseline }) {
        val i = dropped.indexOf(letter)
        val current = dropped[i]
        if (current.baseline >= ground - 1f) continue
        val under = dropped.filter { it !== current && it.baseline > current.baseline && overlaps(it, current) }
        val baseline = under.minOfOrNull { it.baseline - it.height * PILE_REST } ?: ground
        if (baseline <= current.baseline + 0.5f) continue
        dropped[i] = DroppedLetter(
            index = current.index, char = current.char, slot = current.slot,
            fromX = current.x, fromBaseline = current.baseline, fromTilt = current.tilt,
            x = current.x, baseline = baseline,
            tilt = if (baseline >= ground - 1f) current.tilt * GROUND_TILT / TOP_TILT else current.tilt,
            width = current.width, height = current.height, at = current.at, fellAt = now
        )
    }
}

/**
 * Its body clock, by the hour: how likely it is to be found asleep, how
 * often (at each chance to nap) and how long it naps, and after each sit how
 * often it goes for a letter when it could, else for a stroll when it
 * could, else swishes its tail; and how often a tap gets hearts and
 * sparkles rather than a scratch, and after three taps in a row how often
 * it gets up and goes (and of those, how many for a letter); and how likely it is, on the
 * page being opened, to have taken a letter or two already, and (awake) to
 * be out walking home, or bringing a letter home. Sleepy at night (11pm to 7am),
 * lively in the evening (6 to 11pm), and in between those by day.
 */
internal class Mood(
    val startAsleep: Float,
    val napChance: Float,
    val napMinMs: Long,
    val napMaxMs: Long,
    val strollChance: Float,
    val fetchChance: Float,
    val wagChance: Float,
    val loveChance: Float,
    val startWithLetters: Float,
    val startWalking: Float,
    val startCarrying: Float,
    val fedUpChance: Float,
    val fedUpFetch: Float
)

private val DAY = Mood(startAsleep = 0.25f, napChance = 0.35f, napMinMs = 30_000L, napMaxMs = 60_000L,
    strollChance = 0.45f, fetchChance = 0.15f, wagChance = 0.6f, loveChance = 0.35f, startWithLetters = 0.4f,
    startWalking = 0.2f, startCarrying = 0.1f, fedUpChance = 0.5f, fedUpFetch = 0.4f)
private val EVENING = Mood(startAsleep = 0.1f, napChance = 0.15f, napMinMs = 20_000L, napMaxMs = 40_000L,
    strollChance = 0.6f, fetchChance = 0.22f, wagChance = 0.7f, loveChance = 0.4f, startWithLetters = 0.5f,
    startWalking = 0.25f, startCarrying = 0.15f, fedUpChance = 0.6f, fedUpFetch = 0.5f)
private val NIGHT = Mood(startAsleep = 0.6f, napChance = 0.7f, napMinMs = 60_000L, napMaxMs = 120_000L,
    strollChance = 0.2f, fetchChance = 0.05f, wagChance = 0.35f, loveChance = 0.3f, startWithLetters = 0.25f,
    startWalking = 0.075f, startCarrying = 0.0375f, fedUpChance = 0.3f, fedUpFetch = 0.2f)

internal fun moodAt(hour: Int): Mood = when (hour) {
    in 7..17 -> DAY
    in 18..22 -> EVENING
    else -> NIGHT
}

private fun PrinceState.mood(): Mood = moods(hour())

/** One of the sheet's animations: its frames, by their place on the sheet
 *  (left to right, then down), how fast they play, looping, and whether
 *  they're side-on (Prince facing right on the sheet). */
private class CatAnimation(val frames: IntArray, fps: Int, val sideOn: Boolean = false) {
    val frameMs = 1_000L / fps
}

// The sheet's frames: 0-3 walking, 4 sitting, 5 blinking, 6 an ear flicked,
// 7-9 scratching with the right paw, 10-12 with the left, 13-20 dozing,
// 21-22 stretching up (2, then 4 of its pixels taller), 23 yawning at full
// stretch, 24-27 sitting with its tail's tip swung far right, up, left and
// far left (on 4 it's to the right), 28 the anger mark alone, drawn over
// it side-on, 29 hearts and sparkles, and 30-31 its holiday hats (see Hat).
private val WALK = CatAnimation(intArrayOf(0, 1, 2, 3), fps = 8, sideOn = true)
private val SIT = CatAnimation(intArrayOf(4, 4, 4, 4, 4, 5, 4, 4, 6, 4), fps = 4)
private val SCRATCH = CatAnimation(intArrayOf(7, 8, 9, 10, 11, 12), fps = 9)
private val DOZE = CatAnimation(intArrayOf(13, 14, 15, 16, 17, 18, 19, 20), fps = 4)

/** Played once, waking up: eyes still shut, up into a stretch, a long
 *  yawn, back down, and eyes open. */
private val WAKE = CatAnimation(intArrayOf(5, 21, 22, 22, 23, 23, 23, 23, 22, 21, 5, 4), fps = 6)

/** Played once, stopping to think on a stroll: a blink. */
private val PONDER = CatAnimation(intArrayOf(4, 4, 5, 5, 4, 4), fps = 6)

/** Played once, sitting: its tail's tip from side to side and back, twice. */
private val WAG = CatAnimation(intArrayOf(24, 4, 25, 26, 27, 26, 25, 4, 24, 4, 25, 26, 27, 26, 25, 4), fps = 9)

/** Side-on, standing still (busy with a letter, or cross), and mid-hop. */
private val STAND = CatAnimation(intArrayOf(0), fps = 1, sideOn = true)
private val HOP = CatAnimation(intArrayOf(1), fps = 1, sideOn = true)

private const val ANGER_MARK = 28

/**
 * Its hats, for the holidays: a party hat at New Year (31 Dec-2 Jan), heart
 * boppers for Valentine's (13-15 Feb), a green top hat for St Patrick's
 * (16-18 Mar), bunny ears for Easter (Good Friday to Easter Monday), a
 * pumpkin mask for Halloween (24-31 Oct), a roast turkey for (American)
 * Thanksgiving (its Thursday to the Sunday) and a Santa hat for Christmas
 * (1-26 Dec). On the sheet in cells 30 and 31 (the turkey, and the Santa hat
 * side-on, its tip falling behind it).
 */
internal enum class Hat(val front: HatArt, val side: HatArt = front) {
    PARTY(hatArt(30, 31, 0, 7, 8, 3, 7)),
    HEARTS(hatArt(30, 0, 15, 13, 9, 6, 8)),
    TOP_HAT(hatArt(30, 14, 15, 7, 7, 3, 6)),
    BUNNY(hatArt(30, 22, 15, 15, 13, 7, 11)),
    PUMPKIN(hatArt(30, 0, 0, 13, 11, 6, 2)),
    TURKEY(hatArt(31, 18, 0, 15, 11, 7, 10)),
    SANTA(hatArt(30, 14, 0, 17, 14, 8, 10), side = hatArt(31, 0, 0, 17, 14, 8, 10))
}

/** Where a hat is on the sheet ([x], [y], [w] x [h]), and its pixel ([ax],
 *  [ay]) that goes on the middle of its head (see [hatAnchor]). */
internal class HatArt(val x: Int, val y: Int, val w: Int, val h: Int, val ax: Int, val ay: Int)

private fun hatArt(cell: Int, x: Int, y: Int, w: Int, h: Int, ax: Int, ay: Int) =
    HatArt(cell % SHEET_COLUMNS * CELL_W + x, cell / SHEET_COLUMNS * CELL_H + y, w, h, ax, ay)

/** Its hat on [date], if it's a holiday. */
internal fun hatFor(date: LocalDate): Hat? {
    val day = date.monthValue * 100 + date.dayOfMonth
    val easter = easterSunday(date.year)
    val turkey = thanksgiving(date.year)
    return when {
        day >= 1231 || day <= 102 -> Hat.PARTY
        day in 213..215 -> Hat.HEARTS
        day in 316..318 -> Hat.TOP_HAT
        !date.isBefore(easter.minusDays(2)) && !date.isAfter(easter.plusDays(1)) -> Hat.BUNNY
        day in 1024..1031 -> Hat.PUMPKIN
        !date.isBefore(turkey) && !date.isAfter(turkey.plusDays(3)) -> Hat.TURKEY
        day in 1201..1226 -> Hat.SANTA
        else -> null
    }
}

/** (American) Thanksgiving in [year]: the fourth Thursday of November. */
internal fun thanksgiving(year: Int): LocalDate {
    val first = LocalDate.of(year, 11, 1)
    val toThursday = (DayOfWeek.THURSDAY.value - first.dayOfWeek.value + 7) % 7
    return first.plusDays(toThursday + 21L)
}

/** Easter Sunday in [year], by the Gregorian calendar (the anonymous algorithm). */
internal fun easterSunday(year: Int): LocalDate {
    val a = year % 19
    val b = year / 100
    val c = year % 100
    val h = (19 * a + b - b / 4 - (b - (b + 8) / 25 + 1) / 3 + 15) % 30
    val l = (32 + 2 * (b % 4) + 2 * (c / 4) - h - c % 4) % 7
    val m = (a + 11 * h + 22 * l) / 451
    val n = h + l - 7 * m + 114
    return LocalDate.of(year, n / 31, n % 31 + 1)
}

/** Where its hat goes in [frame], in its own pixels: the middle of its head
 *  between its ear tips. Side-on (walking, nose right) it bobs up a pixel
 *  every other step; stretching up it's 2, then 4, pixels higher. */
private fun hatAnchor(frame: Int): Pair<Int, Int> = when (frame) {
    in 0..3 -> 25 to (if (frame % 2 == 1) 15 else 16)
    21 -> 16 to 9
    22, 23 -> 16 to 7
    else -> 16 to 11
}

/** Cell 29: the hearts and sparkles, side by side along its top. */
private const val LOVE_CELL = 29
private class LoveArt(val x: Int, val y: Int, val w: Int, val h: Int)
private val LOVE_ART = listOf(
    LoveArt(0, 0, 7, 6),   // a big heart
    LoveArt(8, 0, 5, 4),   // a small one
    LoveArt(14, 0, 7, 7),  // a four-pointed star
    LoveArt(22, 0, 3, 3),  // a little cross
    LoveArt(26, 0, 3, 3),  // one with a blue middle
    LoveArt(30, 0, 1, 1)   // a dot
)
private const val STAR = 2

/** A burst round its head (sitting, its middle at column 18, row 13): what,
 *  and where in its own pixels from there, give or take one; one after
 *  another, 70ms apart. Hearts rise 6 of its pixels over 1.6s, sparkles 2
 *  over 1.1s, twinkling (dimmed one 110ms in three); each fading out over
 *  the last 40% of its time. */
private const val HEAD_X = 18
private const val HEAD_Y = 13
private val LOVE_BURST = listOf(
    Triple(0, -11, -7), Triple(1, 10, -9), Triple(2, 13, -2), Triple(3, -13, -1),
    Triple(4, 3, -13), Triple(5, -5, -12), Triple(5, 15, -8)
)
private const val LOVE_STAGGER_MS = 70L
private const val HEART_RISE = 6f
private const val SPARKLE_RISE = 2f
private const val HEART_LIFE_MS = 1_600L
private const val SPARKLE_LIFE_MS = 1_100L
private const val LOVE_FADE_FROM = 0.6f
private const val TWINKLE_MS = 110L
private const val TWINKLE_DIM = 0.35f

private const val CELL_W = 38
private const val CELL_H = 32
private const val SHEET_COLUMNS = 8

/** How far right any frame reaches (walking, its nose): one past its last
 *  column. Home, that's just inside its spot's right edge. */
private const val REACH_RIGHT = 34

/** About how tall its frames are drawn: see [princePixelSize]. */
private const val CELL_HEIGHT_DP = 46f

private const val LEFT = -1
private const val RIGHT = 1

/** Awake 25-50s between chances to nap (how likely it is to take one, and
 *  for how long, is down to its body clock: see [Mood]). Its first change on
 *  arriving at the home page (waking, or its first chance to nap) comes after
 *  anywhere from 3s up to the usual maximum. */
private const val FIRST_CHANGE_MIN_MS = 3_000L
private const val AWAKE_MIN_MS = 25_000L
private const val AWAKE_MAX_MS = 50_000L

/** Awake, it sits 6-14s at a time, and then, if its body clock says so and
 *  there's room (two of its own lengths at least), goes for a stroll: one or
 *  two legs, at a walk of 24 of its pixels a second, with a sit of 1.5-4s,
 *  or a stop of a moment to turn round, at the end of each; one leg in four
 *  it gives up on 30-70% of the way along. A tap while it's awake gets a
 *  scratch: twice through its frames. */
private const val SIT_MIN_MS = 6_000L
private const val SIT_MAX_MS = 14_000L
private const val SCRATCH_LOOPS = 2
private const val MIN_STROLL = 2f * CELL_W
private const val MAX_LEGS = 2
private const val MIN_LEG = 24f
private const val REST_MIN_MS = 1_500L
private const val REST_MAX_MS = 4_000L
private const val TURN_MIN_MS = 300L
private const val TURN_MAX_MS = 700L
private const val WALK_SPEED = 24f
private const val CHANGE_MIND_CHANCE = 0.25f
private const val CHANGE_MIND_MIN = 0.3f
private const val CHANGE_MIND_MAX = 0.7f

/** Taking letters: a hop 5 of its pixels high, over 0.42s, takes one; it
 *  carries it from its mouth (side-on, facing right: column 26.5, row 23.5,
 *  the bottom of its muzzle), swinging up to 14 degrees each way every 16
 *  of its pixels walked, and drops it at its pile, its mouth over the spot 1
 *  of its pixels out from its frame's left edge at home, a little way left
 *  of where it sits. Dropped, a letter takes 0.26s to fall. At its pile it goes to
 *  the first free place there, in the hint's average letter widths from the
 *  middle: 0, then one either side, then a half either side (on top); give
 *  or take 0.15 of a width, and anywhere else just under where it was let
 *  go. One that lands on another by over a third of the narrower one's
 *  width rests three quarters of the way up it, leaning up to 30 degrees;
 *  on the line, up to 15. It lies there five minutes, fades over
 *  1.5s and fades back into the hint over 1s. No more than 4 are ever out
 *  at once. A tap while it's carrying one: dropped there and then, and 1.5s
 *  of the anger mark. */
private const val HOP_PIXELS = 5f
private const val HOP_MS = 420L
private const val MOUTH_X = 26.5f
private const val MOUTH_Y = 23.5f
private const val SWING_DEGREES = 14f
private const val SWING_PIXELS = 16f
private const val PILE_X = -1f
private const val PILE_AWAY = MOUTH_X - PILE_X
private const val FALL_MS = 260L
private val PILE_SLOTS = floatArrayOf(0f, -1f, 1f, -0.5f, 0.5f)
private const val DROP_JITTER = 0.15f
private const val GROUND_TILT = 15f
private const val TOP_TILT = 30f
private const val PILE_OVERLAP = 0.35f
private const val PILE_REST = 0.75f
private const val LIE_MS = 5 * 60_000L
private const val FADE_MS = 1_500L
private const val RETURN_MS = 1_000L
private const val MAX_TAKEN = 4

/** Found out on the bar, on the page opening: at least 30% of the way along. */
private const val OPENING_MIN = 0.3f

/** Three taps within 6s: it may get up and go, and sit 5-10s where it goes. */
private const val TAP_STREAK_MS = 6_000L
private const val FED_UP_TAPS = 3
private const val FED_UP_SIT_MIN_MS = 5_000L
private const val FED_UP_SIT_MAX_MS = 10_000L

/** Taken before the page opened: dropped half a minute to four minutes before. */
private const val PRETAKEN_MIN_AGO_MS = 30_000L
private const val PRETAKEN_MAX_AGO_MS = 240_000L
private const val ANGRY_MS = 1_500L

/** A letter's ink above its baseline, as a share of the font's size: a
 *  capital's or a tall letter's (Roboto's are 0.71-0.75), else a small one's
 *  (0.53). */
private const val TALL_LETTERS = "bdfhklt"
private const val TALL_INK = 0.72f
private const val SMALL_INK = 0.53f

/** How strong its shadow is: the theme's text colour, faint. */
private const val SHADOW_ALPHA = 0.16f

/** The start of the row it always stays out of: the search icon and the
 *  gap after it. */
private val CLEAR_OF_ICON = 36.dp

/** How far off Prince a tap can land and still count as on it. */
private val TAP_SLOP = 6.dp
