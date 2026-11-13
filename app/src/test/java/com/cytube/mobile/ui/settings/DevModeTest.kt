package com.cytube.mobile.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevModeTest {
    @Test fun threeTapsOnTheVersionFindIt() {
        val taps = DevModeTaps()
        assertEquals(2, taps.tap())
        assertEquals(1, taps.tap())
        assertFalse(taps.found)
        assertEquals(0, taps.tap())
        assertTrue(taps.found)
        // Found: more taps change nothing.
        assertEquals(0, taps.tap())
    }

    @Test fun eachTapSaysHowManyToGoAsAndroidDoes() {
        assertEquals("You are now 2 steps away from being a developer.", devModeMessage(2))
        assertEquals("You are now 1 step away from being a developer.", devModeMessage(1))
        assertEquals("You are now a developer!", devModeMessage(0))
        assertEquals("No need, you are already a developer.", DEV_MODE_ALREADY)
    }
}
