package com.cytube.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hybrid chat's settings as saved: on the right, solid and not dimming until
 * changed; its opacity 20-100% in 5% steps.
 */
class HybridChatSettingsTest {

    @Test fun rightSolidAndNotDimmingUntilChanged() {
        assertTrue(Settings().hybridChatRight)
        assertFalse(Settings().hybridChatDims)
        assertEquals(1f, Settings().hybridChatOpacity)
    }

    @Test fun solidUntilChanged() {
        assertEquals(1f, hybridChatOpacityOf(null))
        assertEquals(1f, hybridChatOpacityOf(Float.NaN))
    }

    @Test fun staysBetweenTwentyAndAHundredPercent() {
        assertEquals(0.2f, hybridChatOpacityOf(0f))
        assertEquals(0.2f, hybridChatOpacityOf(-1f))
        assertEquals(1f, hybridChatOpacityOf(1.5f))
        assertEquals(HYBRID_CHAT_OPACITY_RANGE, 0.2f..1f)
    }

    // A dragged slider's value, kept on its 5% steps.
    @Test fun keptOnTheSlidersSteps() {
        assertEquals(0.35f, hybridChatOpacityOf(0.35000002f))
        assertEquals(0.55f, hybridChatOpacityOf(0.56f))
        assertEquals(0.8f, hybridChatOpacityOf(0.8f))
    }
}
