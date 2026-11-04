package com.cytube.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {
    @Test fun eachThemeReadsBackAsSaved() {
        for (mode in ThemeMode.entries) assertEquals(mode, ThemeMode.parse(mode.name))
    }

    @Test fun nothingSavedOrSomethingUnknownIsSystemDefault() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.parse(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.parse("PURPLE"))
    }

    @Test fun anOldCotThemeKeepsItsLookAndItsCat() {
        // Saved while cot was a theme: "COT" (dark), then "COT_B" and "COT_W".
        assertEquals(ThemeMode.DARK, ThemeMode.parse("COT"))
        assertEquals(ThemeMode.DARK, ThemeMode.parse("COT_B"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.parse("COT_W"))
        for (old in listOf("COT", "COT_B", "COT_W")) assertTrue(old, ThemeMode.wasCot(old))
    }

    @Test fun noOtherThemeTurnsCotOn() {
        assertFalse(ThemeMode.wasCot(null))
        assertFalse(ThemeMode.wasCot("PURPLE"))
        for (mode in ThemeMode.entries) assertFalse(mode.name, ThemeMode.wasCot(mode.name))
    }
}
