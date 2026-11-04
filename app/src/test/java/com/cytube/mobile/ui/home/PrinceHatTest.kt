package com.cytube.mobile.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class PrinceHatTest {
    private fun on(year: Int, month: Int, day: Int) = hatFor(LocalDate.of(year, month, day))

    @Test fun easterSundays() {
        assertEquals(LocalDate.of(2024, 3, 31), easterSunday(2024))
        assertEquals(LocalDate.of(2025, 4, 20), easterSunday(2025))
        assertEquals(LocalDate.of(2026, 4, 5), easterSunday(2026))
        assertEquals(LocalDate.of(2027, 3, 28), easterSunday(2027))
        assertEquals(LocalDate.of(2038, 4, 25), easterSunday(2038))
    }

    @Test fun thanksgivings() {
        assertEquals(LocalDate.of(2026, 11, 26), thanksgiving(2026))
        assertEquals(LocalDate.of(2027, 11, 25), thanksgiving(2027))
        assertEquals(LocalDate.of(2028, 11, 23), thanksgiving(2028))
    }

    @Test fun aHatForEachHoliday() {
        assertEquals(Hat.PARTY, on(2026, 12, 31)); assertEquals(Hat.PARTY, on(2027, 1, 2))
        assertEquals(Hat.HEARTS, on(2027, 2, 14))
        assertEquals(Hat.TOP_HAT, on(2027, 3, 17))
        // Easter 2027 is 28 March: Good Friday to Easter Monday.
        assertEquals(Hat.BUNNY, on(2027, 3, 26)); assertEquals(Hat.BUNNY, on(2027, 3, 29))
        assertEquals(Hat.PUMPKIN, on(2026, 10, 24)); assertEquals(Hat.PUMPKIN, on(2026, 10, 31))
        // Thanksgiving 2026 is 26 November: the Thursday to the Sunday.
        assertEquals(Hat.TURKEY, on(2026, 11, 26)); assertEquals(Hat.TURKEY, on(2026, 11, 29))
        assertEquals(Hat.SANTA, on(2026, 12, 1)); assertEquals(Hat.SANTA, on(2026, 12, 26))
    }

    @Test fun noHatOtherwise() {
        for ((m, d) in listOf(1 to 3, 2 to 12, 2 to 16, 3 to 19, 3 to 25, 3 to 30, 10 to 23, 11 to 1, 11 to 4,
                11 to 24, 11 to 29, 12 to 27, 12 to 30)) {
            assertEquals("$m/$d", null, on(2027, m, d))
        }
    }
}
