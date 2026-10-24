package com.cytube.mobile.ui.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cropping a channel's picture to the part with the most going on. */
class PictureDetailTest {

    /** [width] by [height], plain dark where [busy] says no, a fine
     *  checkerboard where it says yes. */
    private fun picture(width: Int, height: Int, busy: (x: Int, y: Int) -> Boolean): PictureDetail {
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            if (busy(x, y) && (x + y) % 2 == 0) 0xFFE0E0E0.toInt() else 0xFF101018.toInt()
        }
        return PictureDetail.of(pixels, width, height)
    }

    @Test
    fun `a strip goes to the busy part, not the empty sky above it`() {
        // Like v4c's: dark sky over the top 60%, the scene below.
        val detail = picture(128, 112) { _, y -> y >= 67 }
        val start = bestWindowStart(detail.rows, 0.25f)
        assertNotNull(start)
        assertTrue("starts in the scene, at $start", start!! >= 0.55f)
    }

    @Test
    fun `a wide banner keeps its busy end`() {
        // Empty on the left, the picture on the right.
        val detail = picture(128, 20) { x, _ -> x >= 80 }
        val start = bestWindowStart(detail.cols, 0.3f)
        assertNotNull(start)
        assertTrue("starts in the right end, at $start", start!! >= 0.55f)
    }

    @Test
    fun `nothing stands out, so the middle`() {
        assertNull(bestWindowStart(picture(64, 64) { _, _ -> false }.rows, 0.3f))
        assertNull(bestWindowStart(picture(64, 64) { _, _ -> true }.rows, 0.3f))
        assertNull(bestWindowStart(FloatArray(0), 0.5f))
    }

    @Test
    fun `a window as big as the whole starts at the start`() {
        assertEquals(0f, bestWindowStart(picture(32, 32) { _, y -> y > 10 }.rows, 1f))
    }
}
