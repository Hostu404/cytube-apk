package com.cytube.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Hybrid chat's settings as saved: on the left, solid, the usual text size
 * and not fading until changed; its opacity 20-100% in 5% steps, its text
 * size 80-200% in 10% steps.
 */
class HybridChatSettingsTest {

    @Test fun leftSolidNormalSizeAndNotFadingUntilChanged() {
        assertFalse(Settings().hybridChatRight)
        assertFalse(Settings().hybridChatDims)
        assertEquals(1f, Settings().hybridChatOpacity)
        assertEquals(1f, Settings().hybridChatTextSize)
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

    @Test fun usualTextSizeUntilChanged() {
        assertEquals(1f, hybridChatTextSizeOf(null))
        assertEquals(1f, hybridChatTextSizeOf(Float.NaN))
    }

    @Test fun textSizeBetweenEightyAndTwoHundredPercent() {
        assertEquals(0.8f, hybridChatTextSizeOf(0f))
        assertEquals(0.8f, hybridChatTextSizeOf(0.5f))
        assertEquals(2f, hybridChatTextSizeOf(3f))
        assertEquals(HYBRID_CHAT_TEXT_SIZE_RANGE, 0.8f..2f)
    }

    // A dragged slider's value, kept on its 10% steps.
    @Test fun textSizeKeptOnTheSlidersSteps() {
        assertEquals(1.3f, hybridChatTextSizeOf(1.3000001f))
        assertEquals(1.5f, hybridChatTextSizeOf(1.46f))
        assertEquals(1.2f, hybridChatTextSizeOf(1.2f))
    }
}
