package com.cytube.mobile.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime
import kotlin.random.Random

class FireworksTest {
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) = LocalDateTime.of(y, mo, d, h, mi, s)

    @Test fun aMinuteToMidnightTillFivePast() {
        assertFalse(Fireworks.isOn(at(2026, 12, 31, 23, 58, 59)))
        assertTrue(Fireworks.isOn(at(2026, 12, 31, 23, 59)))
        assertTrue(Fireworks.isOn(at(2027, 1, 1, 0, 4, 59)))
        assertFalse(Fireworks.isOn(at(2027, 1, 1, 0, 5)))
        assertFalse(Fireworks.isOn(at(2026, 12, 30, 23, 59, 30)))
        assertFalse(Fireworks.isOn(at(2027, 1, 2, 0, 1)))
        assertFalse(Fireworks.isOn(at(2026, 11, 6, 12, 0)))
    }

    @Test fun looksAgainWhenThatChanges() {
        assertEquals(Duration.ofMinutes(59).toMillis(), Fireworks.untilChange(at(2026, 12, 31, 23, 0)))
        assertEquals(Duration.ofSeconds(330).toMillis(), Fireworks.untilChange(at(2026, 12, 31, 23, 59, 30)))
        assertEquals(Duration.ofDays(364).plusHours(23).plusMinutes(54).toMillis(), Fireworks.untilChange(at(2027, 1, 1, 0, 5)))
    }

    @Test fun aFewAtATimeSpacedOutAndNeverTheSameColourTwice() {
        val show = FireworksShowState(Random(1))
        var most = 0
        val launches = sortedMapOf<Int, Burst>()
        repeat(12 * 60) {                                                   // a minute of it
            show.step(); most = maxOf(most, show.bursts.size); show.bursts.forEach { launches[it.start] = it }
        }
        assertTrue("at most ${Fireworks.MAX_AT_ONCE} at once", most <= Fireworks.MAX_AT_ONCE)
        assertTrue("spaced out", launches.keys.zipWithNext { a, b -> b - a }.all { it >= Fireworks.GAP_MIN })
        assertTrue("about one every 1-2.5s: ${launches.size}", launches.size in 24..60)
        assertTrue("a new colour each time", launches.values.zipWithNext { a, b -> a.color != b.color }.all { it })
        assertTrue("some two-tone", launches.values.any { it.second != it.color })
        assertTrue("its second colour is another", launches.values.all { it.second == it.color || it.second != it.color && it.second in 0..7 })
    }
}
