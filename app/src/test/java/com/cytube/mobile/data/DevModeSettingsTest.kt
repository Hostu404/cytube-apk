package com.cytube.mobile.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevModeSettingsTest {
    @Test fun hiddenTillFound() {
        assertFalse(Settings().devMode)
        assertFalse(devModeOf(found = null, purpleGuy = null))
        assertTrue(devModeOf(found = true, purpleGuy = false))
    }

    @Test fun foundAlreadyIfThePurpleGuyWasOnBeforeThereWasADevMode() {
        // His switch is there, to turn him off.
        assertTrue(devModeOf(found = null, purpleGuy = true))
    }
}
