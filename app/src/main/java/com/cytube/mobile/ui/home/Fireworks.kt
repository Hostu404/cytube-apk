package com.cytube.mobile.ui.home

import android.provider.Settings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cytube.mobile.ui.theme.maIsLight
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * New Year's fireworks, from 23:59 on New Year's Eve to 00:05 on New Year's Day by the phone's own
 * clock: little pixel-art ones (in Prince's size of pixel, whether he's there or not) going up in the
 * home search bar's empty space after its hint. Kept small and quiet: one or two at a time, a second
 * or two apart, only while the bar's empty and not being typed in, only with the page on screen, and
 * not at all with the phone's animations off. The rest of the year there's just a check, a minute
 * apart at most while the page is open, for when they're due.
 */
@Composable
internal fun NewYearFireworks(shown: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val animations = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
    if (!animations) return
    val time = rememberFireworksTime()
    if (time && shown) FireworksShow(modifier)
}

/** Whether it's fireworks time, kept up to date: looked at again when that's next due to change, a
 *  minute later at most, and only once the page is on screen (there are no frames while it isn't). */
@Composable
private fun rememberFireworksTime(): Boolean {
    var on by remember { mutableStateOf(Fireworks.isOn(fireworksNow())) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = fireworksNow()
            on = Fireworks.isOn(now)
            delay(Fireworks.untilChange(now).coerceIn(1_000L, 60_000L))
            withFrameMillis { }
        }
    }
    return on
}

@Composable
private fun FireworksShow(modifier: Modifier) {
    val show = remember { FireworksShowState(Random(fireworksSeed())) }
    val light = maIsLight()
    // A frame every 1/12 s, as Prince's walk: with the page away, it waits for the next frame.
    LaunchedEffect(show) {
        while (true) {
            delay(Fireworks.FRAME_MS)
            withFrameMillis { show.step() }
        }
    }
    Spacer(modifier.drawBehind { drawShow(show, light) })
}

/** The clock and the dice, for tests to set. */
internal var fireworksNow: () -> LocalDateTime = { LocalDateTime.now() }
internal var fireworksSeed: () -> Long = { System.nanoTime() }

/** When they're on, and how the show goes. */
internal object Fireworks {
    private val START = LocalTime.of(23, 59)
    private val END = LocalTime.of(0, 5)
    const val FRAME_MS = 83L
    const val MAX_AT_ONCE = 2
    /** Frames from one going up to the next: 1 to 2.5 seconds. */
    const val GAP_MIN = 12
    const val GAP_MAX = 30
    const val FIRST_AFTER = 6

    fun isOn(now: LocalDateTime): Boolean = when {
        now.monthValue == 12 && now.dayOfMonth == 31 -> !now.toLocalTime().isBefore(START)
        now.monthValue == 1 && now.dayOfMonth == 1 -> now.toLocalTime().isBefore(END)
        else -> false
    }

    /** Milliseconds from [now] until [isOn] next changes. */
    fun untilChange(now: LocalDateTime): Long {
        val next = if (isOn(now)) {
            LocalDate.of(if (now.monthValue == 12) now.year + 1 else now.year, 1, 1).atTime(END)
        } else {
            val eve = LocalDate.of(now.year, 12, 31).atTime(START)
            if (now.isBefore(eve)) eve else eve.plusYears(1)
        }
        return Duration.between(now, next).toMillis()
    }
}

/** One firework: up from the bar for [RISE] frames, bursting for [BLAST], twinkling out for [FADE]. Where
 *  ([x], [y]) is a share of the room there is; its colours are places in the palette: every other spark
 *  [second] (the same as [color] for a one-colour one), and a ring of [inner] inside (none if -1). */
internal class Burst(val start: Int, val x: Float, val y: Float, val radius: Int, val color: Int, val second: Int, val inner: Int) {
    companion object {
        const val RISE = 5
        const val BLAST = 8
        const val FADE = 5
        const val LIFE = RISE + BLAST + FADE
    }
}

/** The show so far, a frame at a time. */
internal class FireworksShowState(private val random: Random) {
    var frame by mutableIntStateOf(0)
        private set
    val bursts = ArrayList<Burst>(Fireworks.MAX_AT_ONCE)
    private var next = Fireworks.FIRST_AFTER
    private var last = -1

    /** A colour other than [than]. */
    private fun other(than: Int): Int {
        var c: Int
        do c = random.nextInt(BRIGHT.size) while (c == than)
        return c
    }

    fun step() {
        val now = frame + 1
        bursts.removeAll { now - it.start >= Burst.LIFE }
        if (now >= next && bursts.size < Fireworks.MAX_AT_ONCE) {
            // Never the same colour twice running; some two-tone, some with another colour inside.
            val color = other(last)
            last = color
            bursts += Burst(
                start = now, x = random.nextFloat(), y = random.nextFloat(), radius = 4 + random.nextInt(3),
                color = color, second = if (random.nextBoolean()) other(color) else color,
                inner = if (random.nextInt(5) < 3) other(color) else -1
            )
            next = now + Fireworks.GAP_MIN + random.nextInt(Fireworks.GAP_MAX - Fireworks.GAP_MIN + 1)
        }
        frame = now
    }
}

/** Prince's pixel (see CotCat: 32 to a frame 46dp tall), so they look of a piece with him. */
private const val PIXEL_DP = 46f / 32f
private val BRIGHT = listOf(
    Color(0xFFFFC83C), Color(0xFFFF6EAA), Color(0xFF5AC8FF), Color(0xFF96E65A),
    Color(0xFFB482FF), Color(0xFFFF8C3C), Color(0xFFFF4B4B), Color(0xFFE6EBFF)
)
/** The same, deeper, to show up on the light page. */
private val DEEP = listOf(
    Color(0xFFE8A000), Color(0xFFF0407F), Color(0xFF1E9BE6), Color(0xFF4CB43C),
    Color(0xFF8C5AF0), Color(0xFFF06E14), Color(0xFFE02828), Color(0xFF7882A0)
)
private val SPARK_COS = FloatArray(12) { cos(it * PI / 6).toFloat() }
private val SPARK_SIN = FloatArray(12) { sin(it * PI / 6).toFloat() }

private fun DrawScope.drawShow(show: FireworksShowState, light: Boolean) {
    val frame = show.frame
    val palette = if (light) DEEP else BRIGHT
    val rocket = if (light) Color(0xFFD08A20) else Color(0xFFFFF0C8)
    val px = PIXEL_DP.dp.toPx().roundToInt().coerceAtLeast(1).toFloat()
    val w = (size.width / px).toInt()
    val h = (size.height / px).toInt()
    fun dot(x: Int, y: Int, c: Color) = drawRect(c, topLeft = Offset(x * px, y * px), size = Size(px, px))
    for (b in show.bursts) {
        val r = b.radius
        if (w < 2 * r + 3) continue
        // Across the room it has, and from a little above the hint's line to halfway down it.
        val cx = r + 1 + (b.x * (w - 2 * r - 3)).toInt()
        val cy = -2 + (b.y * (h * 0.45f + 2)).toInt()
        val age = frame - b.start
        if (age < Burst.RISE) {
            val y = h + (cy - h) * (age + 1) / Burst.RISE
            dot(cx, y, rocket); dot(cx, y + 1, rocket.copy(alpha = 0.5f)); dot(cx, y + 2, rocket.copy(alpha = 0.25f))
            continue
        }
        val t = age - Burst.RISE
        val k = (t + 1).coerceAtMost(Burst.BLAST) / Burst.BLAST.toFloat()
        val reach = 1f - (1f - k) * (1f - k)
        val fall = if (t > Burst.BLAST / 2) (t - Burst.BLAST / 2) / 3 else 0
        val alpha = if (t < Burst.BLAST) 1f else 1f - (t - Burst.BLAST + 1) / (Burst.FADE + 1f)
        val color = palette[b.color].copy(alpha = alpha)
        val second = palette[b.second].copy(alpha = alpha)
        for (i in 0 until 12) {
            if (t >= Burst.BLAST && (i + t) % 2 == 1) continue         // twinkling out
            val c = if (i % 2 == 0) color else second
            val d = reach * r
            dot(cx + (SPARK_COS[i] * d).roundToInt(), cy + (SPARK_SIN[i] * d).roundToInt() + fall, c)
            if (t < Burst.BLAST / 2 && d > 1.5f) {
                dot(cx + (SPARK_COS[i] * (d - 1)).roundToInt(), cy + (SPARK_SIN[i] * (d - 1)).roundToInt(), c.copy(alpha = 0.45f))
            }
        }
        if (b.inner >= 0 && t < Burst.BLAST + 2) {
            val inner = palette[b.inner].copy(alpha = alpha)
            for (i in 1 until 12 step 2) {
                dot(cx + (SPARK_COS[i] * reach * r / 2).roundToInt(), cy + (SPARK_SIN[i] * reach * r / 2).roundToInt() + fall, inner)
            }
        }
    }
}
