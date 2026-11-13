package com.cytube.mobile.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperModeSwitchTest {
    @Test fun aTapTurnsHimOn() {
        assertTrue(TurnOnGuard().mayTurnOn(now = 0))
    }

    @Test fun butNotJustAfterHesBeenTurnedOff() {
        val guard = TurnOnGuard()
        guard.turnedOff(now = 1_000)
        // The finger that held it, or a tap straight after.
        assertFalse(guard.mayTurnOn(now = 1_000))
        assertFalse(guard.mayTurnOn(now = 1_000 + TURN_ON_COOLDOWN_MS - 1))
        // A moment later, a tap turns him on as usual.
        assertTrue(guard.mayTurnOn(now = 1_000 + TURN_ON_COOLDOWN_MS))
    }

    @Test fun heldLongEnoughToMeanIt() {
        assertTrue(HOLD_MS in 500..1_500)
    }
}
