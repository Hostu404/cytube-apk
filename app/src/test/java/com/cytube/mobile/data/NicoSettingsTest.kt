package com.cytube.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Nico chat: text only" and "Nico emote opacity" as saved, matching CyTube
 *  Plus: text only and emotes only never both on, opacity 10-100% in 5% steps. */
class NicoSettingsTest {

    @Test fun textOnlyIsOffUnlessTurnedOn() {
        assertFalse(nicoTextOnlyOf(null, null))
        assertFalse(nicoTextOnlyOf(false, false))
        assertTrue(nicoTextOnlyOf(true, null))
        assertTrue(nicoTextOnlyOf(true, false))
    }

    // Turning either on turns the other off (SettingsStore); should both
    // still be saved on, emotes only wins, as CyTube Plus checks it first.
    @Test fun neverTogetherWithEmotesOnly() {
        assertFalse(nicoTextOnlyOf(true, true))
    }

    @Test fun opacityIsSolidUntilChanged() {
        assertEquals(1f, nicoEmoteOpacityOf(null))
        assertEquals(1f, Settings().nicoEmoteOpacity)
        assertFalse(Settings().nicoTextOnly)
    }

    @Test fun opacityStaysBetweenTenAndAHundredPercent() {
        assertEquals(0.1f, nicoEmoteOpacityOf(0f))
        assertEquals(0.1f, nicoEmoteOpacityOf(-3f))
        assertEquals(1f, nicoEmoteOpacityOf(1.7f))
        assertEquals(1f, nicoEmoteOpacityOf(Float.NaN))
        assertEquals(NICO_EMOTE_OPACITY_RANGE, 0.1f..1f)
    }

    // A dragged slider's value, kept on its 5% steps.
    @Test fun opacityIsKeptOnTheSlidersSteps() {
        assertEquals(0.35f, nicoEmoteOpacityOf(0.35000002f))
        assertEquals(0.35f, nicoEmoteOpacityOf(0.3499f))
        assertEquals(0.5f, nicoEmoteOpacityOf(0.51f))
        assertEquals(0.8f, nicoEmoteOpacityOf(0.8f))
    }
}
