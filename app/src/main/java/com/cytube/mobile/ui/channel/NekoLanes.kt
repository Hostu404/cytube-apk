package com.cytube.mobile.ui.channel

import kotlin.math.ceil

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
 * can be unit-tested: how long until a new comment ([candidateWidthPx] wide,
 * moving at [candidateSpeedPxPerMs]) may enter a lane whose newest comment
 * is [last]. 0 means now.
 *
 * Two conditions, both of which the old wait only half accounted for — it
 * knew about the first, so a lane held back only by the second was checked
 * again every frame until it cleared, for up to a few seconds at a time:
 *  1. entry: [last] has moved clear of the right edge by [gapPx];
 *  2. overtaking: if the new comment is faster, [last] will be gone before
 *     the new one could reach it.
 */
fun laneWaitMs(
    last: LaneOccupant,
    nowMs: Long,
    candidateWidthPx: Float,
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
        // Rejected while (time left for [last]) × (new speed) > this limit;
        // time left falls one-for-one with the clock.
        val limitPx = screenWidthPx + candidateWidthPx - gapPx
        val allowedRemainMs = limitPx / candidateSpeedPxPerMs
        overtakeWaitMs = ceil((last.clearAtMs - nowMs) - allowedRemainMs).toLong().coerceAtLeast(0L)
    }
    return maxOf(entryWaitMs, overtakeWaitMs)
}
