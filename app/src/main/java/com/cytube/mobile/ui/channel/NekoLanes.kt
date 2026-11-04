package com.cytube.mobile.ui.channel

import kotlin.math.ceil
import kotlin.random.Random

/** One flying comment's motion, tracked per lane so new comments can trail
 *  safely behind earlier ones without colliding. */
class LaneOccupant(
    val spawnAtMs: Long,
    val speedPxPerMs: Float,
    val widthPx: Float,
    val clearAtMs: Long
)

/**
 * The Niconico overlay's lane rule, kept apart from the Compose code so it
 * can be unit-tested: how long until a new comment moving at
 * [candidateSpeedPxPerMs] may enter a lane whose newest comment is [last].
 * 0 means now.
 *
 * Two conditions:
 *  1. entry: [last] has moved clear of the right edge by [gapPx];
 *  2. overtaking: if the new comment is faster, it gains on [last] until
 *     [last] is off the screen, so by then the new one's front must still be
 *     [gapPx] short of the left edge.
 *
 * The new comment's own width doesn't come into it: only its front can run
 * into [last]. (The overtaking check used to allow for that width as well,
 * which let a long comment, being faster, set off soon enough to run into a
 * short one ahead of it.)
 */
fun laneWaitMs(
    last: LaneOccupant,
    nowMs: Long,
    candidateSpeedPxPerMs: Float,
    gapPx: Float,
    screenWidthPx: Float
): Long {
    if (last.speedPxPerMs <= 0f) return (last.clearAtMs - nowMs).coerceAtLeast(0L)
    val travelled = (nowMs - last.spawnAtMs) * last.speedPxPerMs
    val entryWaitMs = ceil(((last.widthPx + gapPx) - travelled) / last.speedPxPerMs)
        .toLong().coerceAtLeast(0L)
    var overtakeWaitMs = 0L
    if (candidateSpeedPxPerMs > last.speedPxPerMs) {
        // Rejected while (time left for [last]) × (new speed) is more than the
        // new one may travel by then; time left falls one-for-one with the
        // clock.
        val allowedRemainMs = (screenWidthPx - gapPx) / candidateSpeedPxPerMs
        overtakeWaitMs = ceil((last.clearAtMs - nowMs) - allowedRemainMs).toLong().coerceAtLeast(0L)
    }
    return maxOf(entryWaitMs, overtakeWaitMs)
}

/** A message waiting to fly, as the lanes see it: how wide its comment is,
 *  and how fast that makes it go. */
class LaneCandidate(val widthPx: Float, val speedPxPerMs: Float)

/** What [NekoLanes.pick] decided. */
sealed interface LanePick {
    /** Fly waiting message number [index] (oldest first) in [lane], now. */
    data class Fly(val index: Int, val lane: Int) : LanePick

    /** Nothing can go yet: look again in [ms], or sooner if a message arrives. */
    data class Wait(val ms: Long) : LanePick
}

/**
 * Which lane each Niconico comment flies in, and which waiting message goes
 * next. Kept apart from the Compose code, like [laneWaitMs], so it can be
 * unit-tested.
 *
 * Lanes are picked at random, so comments turn up all over the video rather
 * than working their way down it in order: an empty lane if there is one,
 * else any lane the comment can enter now without running into the one ahead
 * of it (see [laneWaitMs]). Never the lane the last comment took while
 * there's another to pick, so one comment doesn't follow another at the same
 * height.
 *
 * Messages fly in the order they were said, except that one which has to
 * wait for room (a long comment is fast, so it may have to wait until it
 * won't catch up with the comment ahead) doesn't hold up the whole queue: a
 * later one that fits now goes first. But never in the lane the oldest will
 * get first, so the oldest isn't kept waiting any longer than it would have
 * been anyway.
 */
class NekoLanes(private val random: Random = Random.Default) {
    /** Comments still on screen, per lane, oldest first. */
    private val occupants = HashMap<Int, MutableList<LaneOccupant>>()
    private var lastLane = -1

    /** [occupant] has set off in [lane]. */
    fun add(lane: Int, occupant: LaneOccupant) {
        occupants.getOrPut(lane) { mutableListOf() }.add(occupant)
    }

    /**
     * Which of [waiting] (oldest first) flies next, and in which of
     * [laneCount] lanes, on a screen [screenWidthPx] wide. [staggerMs] is the
     * pause the caller leaves after each comment sets off: nothing goes ahead
     * of the oldest when that would be ready within it, as the pause would
     * then hold the oldest up.
     */
    fun pick(
        nowMs: Long,
        waiting: List<LaneCandidate>,
        laneCount: Int,
        gapPx: Float,
        screenWidthPx: Float,
        staggerMs: Long
    ): LanePick {
        // Comments that have left the screen are forgotten.
        for (list in occupants.values) list.removeAll { nowMs >= it.clearAtMs }
        occupants.values.removeAll { it.isEmpty() }

        val oldest = waiting.firstOrNull() ?: return LanePick.Wait(MAX_WAIT_MS)
        laneFor(nowMs, oldest, laneCount, gapPx, screenWidthPx)?.let { return LanePick.Fly(0, it) }
        val (heldLane, heldWaitMs) = soonestLane(nowMs, oldest, laneCount, gapPx, screenWidthPx)
        var waitMs = heldWaitMs
        if (heldWaitMs > staggerMs) {
            for (i in 1 until waiting.size) {
                laneFor(nowMs, waiting[i], laneCount, gapPx, screenWidthPx, avoid = heldLane)
                    ?.let { return LanePick.Fly(i, it) }
                waitMs = minOf(waitMs, soonestLane(nowMs, waiting[i], laneCount, gapPx, screenWidthPx, avoid = heldLane).second)
            }
        }
        return LanePick.Wait(waitMs.coerceIn(MIN_WAIT_MS, MAX_WAIT_MS))
    }

    /** A random lane [c] can enter now, as described above (never [avoid]),
     *  or null if there's none. */
    private fun laneFor(
        nowMs: Long,
        c: LaneCandidate,
        laneCount: Int,
        gapPx: Float,
        screenWidthPx: Float,
        avoid: Int = -1
    ): Int? {
        val empty = ArrayList<Int>()
        val safe = ArrayList<Int>()
        for (lane in 0 until laneCount) {
            if (lane == avoid) continue
            val last = occupants[lane]?.lastOrNull()
            if (last == null) empty.add(lane)
            else if (laneWaitMs(last, nowMs, c.speedPxPerMs, gapPx, screenWidthPx) == 0L) safe.add(lane)
        }
        val lanes = empty.ifEmpty { safe }
        if (lanes.isEmpty()) return null
        val choices = lanes.filter { it != lastLane }.ifEmpty { lanes }
        return choices[random.nextInt(choices.size)].also { lastLane = it }
    }

    /** The lane that will take [c] soonest (never [avoid]), and how long
     *  until it will. */
    private fun soonestLane(
        nowMs: Long,
        c: LaneCandidate,
        laneCount: Int,
        gapPx: Float,
        screenWidthPx: Float,
        avoid: Int = -1
    ): Pair<Int, Long> {
        var best = -1 to Long.MAX_VALUE
        for (lane in 0 until laneCount) {
            if (lane == avoid) continue
            val last = occupants[lane]?.lastOrNull()
            val waitMs = if (last == null) 0L else laneWaitMs(last, nowMs, c.speedPxPerMs, gapPx, screenWidthPx)
            if (waitMs < best.second) best = lane to waitMs
        }
        return best
    }

    private companion object {
        /** While nothing can go, the loop looks again after at least this
         *  long and at most that (a new message wakes it sooner anyway). */
        const val MIN_WAIT_MS = 16L
        const val MAX_WAIT_MS = 1_000L
    }
}
