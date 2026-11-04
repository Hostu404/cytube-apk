package com.cytube.mobile.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PrinceSizeTest {
    /** Sitting, Prince is 22 of its 32-pixel-tall frame. */
    private fun sittingDp(scale: Int, density: Float) = 22f * scale / density

    /** What it's sized for: its frames 46dp tall. */
    private val target = 22f * 46f / 32f

    private val dpis = listOf(120, 160, 213, 240, 280, 320, 360, 400, 420, 440, 450, 480, 520, 540, 560, 640)

    @Test fun aboutTheSameSizeOnAnyPhone() {
        // Phones: hdpi up to xxxhdpi, and the in-betweens (420, 450, 560dpi...).
        for (dpi in dpis.filter { it >= 240 }) {
            val density = dpi / 160f
            val tall = sittingDp(princePixelSize(density), density)
            assertTrue("${dpi}dpi: ${princePixelSize(density)}x, ${"%.1f".format(tall)}dp tall", tall in 27f..38f)
        }
    }

    @Test fun theWholeScaleNearestItsSize() {
        for (dpi in dpis) {
            val density = dpi / 160f
            val scale = princePixelSize(density)
            val off = abs(sittingDp(scale, density) - target)
            for (other in listOf(scale - 1, scale + 1).filter { it >= 1 }) {
                assertTrue("${dpi}dpi: ${scale}x is nearer than ${other}x", off <= abs(sittingDp(other, density) - target))
            }
        }
    }

    @Test fun wholeScreenPixelsAndNeverNone() {
        assertEquals(4, princePixelSize(2.625f))   // 420dpi
        assertEquals(4, princePixelSize(3f))       // 480dpi
        assertEquals(1, princePixelSize(0.1f))
    }
}
