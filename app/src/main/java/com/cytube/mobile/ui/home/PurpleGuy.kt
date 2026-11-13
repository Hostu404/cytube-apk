package com.cytube.mobile.ui.home

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * The purple guy: one for the whole app, kept above its pages (see
 * MainActivity), so that he carries on as you go from one to another.
 *
 * He swings in on a vine when his switch is turned on ([switchedOn]): Dev
 * Mode, at the bottom of Settings, found by tapping the version there three
 * times. From then on he's just there, on every page but the channel page: on
 * going back to the home page, or to Settings again, he's where he was,
 * doing what he was. He's mostly busy: he does one of his things at random
 * (never the same one twice running; his [FAVOURITES] more often than the
 * rest), stands about a moment (blinking, maybe), and does another. Now and
 * then, instead, he swings out, is gone a moment, and swings back in.
 * Turned off, he swings out for good. Opening the app with him on, he's
 * already there.
 */
@Stable
class PurpleGuyState {
    /** The pose showing, or [PurpleGuyAnims.GONE]. */
    internal var pose by mutableIntStateOf(PurpleGuyAnims.GONE)
    internal var anims by mutableStateOf<PurpleGuyAnims?>(null)
    /** The sheets loaded, by number: the ones the animation showing needs. */
    internal val sheets = mutableStateMapOf<Int, ImageBitmap>()
    /** His switch has just been turned on: he swings in. */
    internal var swingInNext = false
    /** He's swung out for a moment, and swings back in in a while. */
    internal var away = false

    /** His switch has just been turned on (Developer mode, in Settings): he
     *  swings in there and then. */
    fun switchedOn() {
        swingInNext = true
    }
}

/** How he turns up where he's shown. */
internal enum class Arrival {
    /** Just switched on: he swings in. */
    SWING_IN,
    /** He'd swung out for a moment: he swings back in in a while. */
    SWING_IN_LATER,
    /** Otherwise (the app opening with him on, or back from the channel
     *  page): he's already there. */
    THERE
}

internal fun PurpleGuyState.arrival(): Arrival = when {
    swingInNext -> Arrival.SWING_IN
    away -> Arrival.SWING_IN_LATER
    else -> Arrival.THERE
}

/**
 * Draws [state]'s purple guy, at [modifier]'s place (the bottom right
 * corner), and keeps him going while [on] (his switch) and [here] (a page
 * he's on, not the channel page). While he isn't here, or the app is in the
 * background, he waits, and lets go of his sheets. Purely decorative: taps
 * go through him to the page underneath.
 */
@Composable
fun PurpleGuy(state: PurpleGuyState, on: Boolean, here: Boolean, modifier: Modifier = Modifier) {
    val assets = LocalContext.current.assets
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = here && lifecycle.isAtLeast(Lifecycle.State.STARTED)

    LaunchedEffect(state, on, active) {
        if (!active) {
            state.sheets.clear()
            return@LaunchedEffect
        }
        val stage = ScreenStage(state, assets)
        try {
            if (!on) {
                // Turned off: he swings out, if he's there.
                state.anims?.let { state.leave(it, stage) }
                state.sheets.clear()
                return@LaunchedEffect
            }
            val a = state.anims ?: withContext(Dispatchers.IO) {
                PurpleGuyAnims.parse(assets.open("$DIR/anims.json").bufferedReader().use { it.readText() })
            }.also { state.anims = it }
            state.live(a, stage, Random(System.nanoTime()))
        } catch (e: IOException) {
            // His files missing or unreadable: he just isn't there.
            state.pose = PurpleGuyAnims.GONE
        }
    }

    Canvas(modifier.size(CELL_WIDTH, CELL_HEIGHT)) {
        val a = state.anims ?: return@Canvas
        val p = state.pose
        if (p == PurpleGuyAnims.GONE) return@Canvas
        val sheet = state.sheets[a.sheetOf(p)] ?: return@Canvas
        drawPose(a, sheet, p, size.width / a.cellW)
    }
}

/** Where his life plays out: what's drawn, and the passing of time (a fake
 *  one, in the tests). */
internal interface PurpleGuyStage {
    /** Gets ready to show [animation]'s poses (loads their sheets). */
    suspend fun ready(animation: IntArray)
    /** Shows [pose], or nothing for [PurpleGuyAnims.GONE]. */
    fun show(pose: Int)
    suspend fun wait(ms: Long)
}

/** The real one: [state]'s pose, drawn by [PurpleGuy], from the sheets in
 *  [assets], with only those the animation showing needs loaded. */
private class ScreenStage(private val state: PurpleGuyState, private val assets: AssetManager) : PurpleGuyStage {
    override suspend fun ready(animation: IntArray) {
        val a = state.anims ?: return
        val want = a.sheetsOf(animation) + a.sheetOf(PurpleGuyAnims.REST)
        for (s in want) {
            if (s !in state.sheets) state.sheets[s] = withContext(Dispatchers.IO) { assets.sheet(a.sheets[s]) }
        }
        (state.sheets.keys - want).forEach { state.sheets.remove(it) }
    }

    override fun show(pose: Int) {
        state.pose = pose
    }

    override suspend fun wait(ms: Long) = delay(ms)
}

/** His life: turning up (see [arrival]), then standing about and doing
 *  things, for as long as he's shown. */
internal suspend fun PurpleGuyState.live(a: PurpleGuyAnims, stage: PurpleGuyStage, random: Random) {
    // What he's up to is noted before it's shown, so that wherever he's
    // stopped (gone from the page), he picks up from there next time.
    suspend fun swingIn() {
        stage.play(a.enter)
        away = false
        swingInNext = false
        stage.show(PurpleGuyAnims.REST)
    }
    when (arrival()) {
        Arrival.SWING_IN -> {
            stage.show(PurpleGuyAnims.GONE)
            stage.wait(SWITCHED_ON_DELAY_MS)
            swingIn()
        }
        Arrival.SWING_IN_LATER -> {
            stage.show(PurpleGuyAnims.GONE)
            stage.wait(random.nextLong(AWAY_MIN_MS, AWAY_MAX_MS))
            swingIn()
        }
        Arrival.THERE -> {
            stage.ready(PurpleGuyAnims.REST_ONLY)
            stage.show(PurpleGuyAnims.REST)
        }
    }
    // Each of his things, as many times over as it's to be likely.
    val bag = a.activities.flatMap { (name, animation) ->
        List(if (name in FAVOURITES) FAVOURITE_WEIGHT else 1) { animation }
    }
    var last: IntArray? = null
    while (true) {
        standAbout(a, stage, random, random.nextLong(STAND_MIN_MS, STAND_MAX_MS))
        if (random.nextFloat() < SWING_OUT_CHANCE) {
            stage.play(a.exit)
            away = true
            stage.show(PurpleGuyAnims.GONE)
            stage.wait(random.nextLong(AWAY_MIN_MS, AWAY_MAX_MS))
            swingIn()
        } else {
            val next = bag.filter { it !== last }.random(random)
            last = next
            stage.play(next)
            stage.show(PurpleGuyAnims.REST)
        }
    }
}

/** Turned off: he swings out, if he's there, and that's him. */
internal suspend fun PurpleGuyState.leave(a: PurpleGuyAnims, stage: PurpleGuyStage) {
    if (pose != PurpleGuyAnims.GONE && !away) stage.play(a.exit)
    away = false
    swingInNext = false
    stage.show(PurpleGuyAnims.GONE)
}

/** Stands at rest for [ms], blinking every few seconds. */
private suspend fun standAbout(a: PurpleGuyAnims, stage: PurpleGuyStage, random: Random, ms: Long) {
    stage.show(PurpleGuyAnims.REST)
    var left = ms
    while (left > 0) {
        val wait = random.nextLong(BLINK_MIN_MS, BLINK_MAX_MS).coerceAtMost(left)
        stage.wait(wait)
        left -= wait
        if (left > 0) {
            stage.play(a.blink)
            left -= a.lengthMs(a.blink)
        }
    }
    stage.show(PurpleGuyAnims.REST)
}

private suspend fun PurpleGuyStage.play(animation: IntArray) {
    ready(animation)
    for (i in animation.indices step 2) {
        show(animation[i])
        wait(animation[i + 1].toLong())
    }
}

private fun AssetManager.sheet(name: String): ImageBitmap =
    open("$DIR/$name").use { BitmapFactory.decodeStream(it) ?: throw IOException("Can't read $name") }
        .asImageBitmap()

/** Draws [pose], with a soft drop shadow: his shape in see-through black, a
 *  little below and to the right, drawn a few times round that spot to
 *  soften it (a blur that works on every Android version). */
private fun DrawScope.drawPose(a: PurpleGuyAnims, sheet: ImageBitmap, pose: Int, scale: Float) {
    val at = pose * 7
    val p = a.poses
    val src = IntOffset(p[at + 1], p[at + 2])
    val srcSize = IntSize(p[at + 3], p[at + 4])
    val x = p[at + 5] * scale
    val y = p[at + 6] * scale
    val dstSize = IntSize((srcSize.width * scale).roundToInt(), (srcSize.height * scale).roundToInt())
    val shadowX = x + SHADOW_X.toPx()
    val shadowY = y + SHADOW_Y.toPx()
    val soft = SHADOW_SOFTNESS.toPx()
    for (i in 0 until SHADOW_TAPS) {
        // One in the middle, the rest round it.
        val angle = 2 * PI * i / (SHADOW_TAPS - 1)
        val r = if (i == SHADOW_TAPS - 1) 0f else soft
        drawImage(
            image = sheet,
            srcOffset = src,
            srcSize = srcSize,
            dstOffset = IntOffset((shadowX + r * cos(angle)).roundToInt(), (shadowY + r * sin(angle)).roundToInt()),
            dstSize = dstSize,
            alpha = SHADOW_TAP_ALPHA,
            colorFilter = SHADOW_TINT,
            filterQuality = FilterQuality.Medium
        )
    }
    drawImage(
        image = sheet,
        srcOffset = src,
        srcSize = srcSize,
        dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
        dstSize = dstSize,
        filterQuality = FilterQuality.Medium
    )
}

/**
 * assets/purple_guy/anims.json (made by tools/purple_guy/make_sheet.py):
 * his poses, each where it is on which sheet and where it goes in his cell,
 * [cellW] x [cellH]; and his animations, each as pose, ms, pose, ms, ...
 */
internal class PurpleGuyAnims(
    val cellW: Int,
    val cellH: Int,
    val sheets: List<String>,
    /** Each pose: sheet, x, y, width, height, then x, y in the cell. */
    val poses: IntArray,
    val blink: IntArray,
    val enter: IntArray,
    val exit: IntArray,
    val activities: Map<String, IntArray>
) {
    fun sheetOf(pose: Int) = poses[pose * 7]
    fun sheetsOf(animation: IntArray) = (animation.indices step 2).mapTo(HashSet()) { sheetOf(animation[it]) }
    fun lengthMs(animation: IntArray) = (1 until animation.size step 2).sumOf { animation[it].toLong() }

    companion object {
        /** Him standing at rest: where every animation but [enter] starts
         *  and every one but [exit] ends. */
        const val REST = 0
        /** Just him at rest, as an animation. */
        val REST_ONLY = intArrayOf(REST, 1)
        /** Not a pose: him not there, nothing to draw. */
        const val GONE = -1

        fun parse(json: String): PurpleGuyAnims {
            val o = JSONObject(json)
            fun steps(arr: JSONArray) = IntArray(arr.length() * 2) { arr.getJSONArray(it / 2).getInt(it % 2) }
            val posesJson = o.getJSONArray("poses")
            val poses = IntArray(posesJson.length() * 7) { posesJson.getJSONArray(it / 7).getInt(it % 7) }
            val sheetsJson = o.getJSONArray("sheets")
            val acts = o.getJSONObject("activities")
            return PurpleGuyAnims(
                cellW = o.getInt("cellW"),
                cellH = o.getInt("cellH"),
                sheets = List(sheetsJson.length()) { sheetsJson.getString(it) },
                poses = poses,
                blink = steps(o.getJSONArray("blink")),
                enter = steps(o.getJSONArray("enter")),
                exit = steps(o.getJSONArray("exit")),
                activities = acts.keys().asSequence().associateWith { steps(acts.getJSONArray(it)) }
            )
        }
    }
}

private const val DIR = "purple_guy"

/** His favourite things, which he does [FAVOURITE_WEIGHT] times as often
 *  as any other. */
private val FAVOURITES = setOf("juggle", "shades", "read", "fart")
private const val FAVOURITE_WEIGHT = 4

/** His drop shadow: how far below and to the right of him it falls, how
 *  soft it is, and how dark where it's darkest; drawn [SHADOW_TAPS] times
 *  over, each that much fainter. */
private val SHADOW_X = 1.5.dp
private val SHADOW_Y = 2.5.dp
private val SHADOW_SOFTNESS = 1.2.dp
private const val SHADOW_DARKNESS = 0.25f
private const val SHADOW_TAPS = 9
private val SHADOW_TAP_ALPHA = 1f - (1f - SHADOW_DARKNESS).pow(1f / SHADOW_TAPS)
private val SHADOW_TINT = ColorFilter.tint(Color.Black, BlendMode.SrcIn)

/** His cell on the sheets, in pixels, as anims.json has it. */
internal const val CELL_PIXELS_W = 120
internal const val CELL_PIXELS_H = 95

/** How big his cell is drawn: he stands about 56dp tall in it. */
private val CELL_WIDTH = 100.dp
private val CELL_HEIGHT = CELL_WIDTH * CELL_PIXELS_H / CELL_PIXELS_W

/** On his switch being turned on, how long till he swings in: long enough
 *  to see him come. */
private const val SWITCHED_ON_DELAY_MS = 400L

/** How long he stands about between things: just a moment, so that he's
 *  doing something most of the time. */
private const val STAND_MIN_MS = 750L
private const val STAND_MAX_MS = 2_500L

/** How often he blinks while standing about. */
private const val BLINK_MIN_MS = 1_000L
private const val BLINK_MAX_MS = 4_000L

/** After standing about, the chance he swings out instead of doing
 *  something: every four minutes or so. */
private const val SWING_OUT_CHANCE = 0.03f

/** How long he's gone when he swings out. */
private const val AWAY_MIN_MS = 2_000L
private const val AWAY_MAX_MS = 6_000L
