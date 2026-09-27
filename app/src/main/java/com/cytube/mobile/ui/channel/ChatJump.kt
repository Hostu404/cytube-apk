package com.cytube.mobile.ui.channel

/**
 * The sums behind the chat's "jump to latest" button, kept apart from the
 * Compose code so they can be unit-tested.
 *
 * The button is for when scrolling back down by hand would take more than
 * [JUMP_BUTTON_AFTER_SECONDS]. That depends on two things: how far up you
 * are, and how fast new messages are arriving — during a flood the bottom
 * moves away about as fast as you can scroll towards it, so you may never
 * get there at all.
 */
object ChatJump {
    /** Show the button when reaching the bottom would take longer than this. */
    const val JUMP_BUTTON_AFTER_SECONDS = 5f

    /** Roughly how fast a thumb scrolls a chat back down: screenfuls a second
     *  of steady swiping. */
    const val HAND_SCROLL_SCREENS_PER_SECOND = 3f

    /**
     * Seconds of scrolling by hand to reach the newest message, or
     * [Float.POSITIVE_INFINITY] if messages are arriving at least as fast as
     * a thumb can scroll.
     */
    fun secondsToBottom(itemsBelow: Int, visibleItems: Int, arrivalsPerSecond: Float): Float {
        if (itemsBelow <= 0) return 0f
        val scrollRate = visibleItems.coerceAtLeast(1) * HAND_SCROLL_SCREENS_PER_SECOND
        val closing = scrollRate - arrivalsPerSecond.coerceAtLeast(0f)
        return if (closing <= 0f) Float.POSITIVE_INFINITY else itemsBelow / closing
    }

    fun shouldShow(itemsBelow: Int, visibleItems: Int, arrivalsPerSecond: Float): Boolean =
        secondsToBottom(itemsBelow, visibleItems, arrivalsPerSecond) > JUMP_BUTTON_AFTER_SECONDS
}

/** Messages per second over the last [windowMs], from batches as they land. */
class ChatArrivals(private val windowMs: Long = 3_000L) {
    private val batches = ArrayDeque<Pair<Long, Int>>()

    fun record(nowMs: Long, count: Int) {
        if (count <= 0) return
        batches.addLast(nowMs to count)
        prune(nowMs)
    }

    fun perSecond(nowMs: Long): Float {
        prune(nowMs)
        return batches.sumOf { it.second } * 1000f / windowMs
    }

    private fun prune(nowMs: Long) {
        while (batches.isNotEmpty() && nowMs - batches.first().first > windowMs) batches.removeFirst()
    }
}
