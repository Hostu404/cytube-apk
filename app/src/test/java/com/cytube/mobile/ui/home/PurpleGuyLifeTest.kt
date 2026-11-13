package com.cytube.mobile.ui.home

import com.cytube.mobile.data.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** His life (PurpleGuyState.live), on a stage that only takes notes, in no
 *  time. */
class PurpleGuyLifeTest {
    private val anims = PurpleGuyAnims.parse(File("src/main/assets/purple_guy/anims.json").readText())
    private val rest = PurpleGuyAnims.REST
    private val gone = PurpleGuyAnims.GONE

    private class Enough : RuntimeException()

    /** Notes every pose shown, and every animation got ready for, and
     *  stops him once [shows] poses have been shown. */
    private class NoteTaker(private val guy: PurpleGuyState, private val shows: Int) : PurpleGuyStage {
        val shown = mutableListOf<Int>()
        /** Each animation got ready for (so about to play), with the pose
         *  showing at the time. */
        val played = mutableListOf<Pair<IntArray, Int>>()
        var ms = 0L

        override suspend fun ready(animation: IntArray) {
            played += animation to guy.pose
        }

        override fun show(pose: Int) {
            guy.pose = pose
            shown += pose
            if (shown.size >= shows) throw Enough()
        }

        override suspend fun wait(ms: Long) {
            this.ms += ms
        }
    }

    private fun live(guy: PurpleGuyState, shows: Int, seed: Int = 1): NoteTaker {
        val stage = NoteTaker(guy, shows)
        try {
            runBlocking { guy.live(anims, stage, Random(seed)) }
        } catch (_: Enough) {
        }
        return stage
    }

    private fun poses(animation: IntArray) = animation.filterIndexed { i, _ -> i % 2 == 0 }

    @Test fun offTillHisSwitchIsTurnedOn() {
        assertFalse(Settings().purpleGuy)
    }

    @Test fun switchedOnHeSwingsIn() {
        val guy = PurpleGuyState()
        guy.switchedOn()
        val enter = poses(anims.enter)
        val stage = live(guy, enter.size + 2)
        assertEquals(gone, stage.shown.first())
        assertEquals(enter, stage.shown.subList(1, 1 + enter.size))
        assertEquals(rest, stage.shown.last())
        // And once he's in, that's it: no swinging in again.
        assertEquals(Arrival.THERE, guy.arrival())
    }

    @Test fun withTheAppOpeningHesAlreadyThere() {
        val guy = PurpleGuyState()
        val stage = live(guy, 3)
        assertEquals(rest, stage.shown.first())
        assertTrue(stage.played.none { it.first === anims.enter })
    }

    @Test fun leftBeforeHeGotInHeSwingsInNextTime() {
        val guy = PurpleGuyState()
        guy.switchedOn()
        live(guy, 4)   // gone from the page halfway through swinging in
        assertEquals(Arrival.SWING_IN, guy.arrival())
    }

    @Test fun swungOutHeSwingsBackIn() {
        val guy = PurpleGuyState()
        val exit = poses(anims.exit)
        val enter = poses(anims.enter)
        val shown = live(guy, 20_000).shown
        var swungOut = 0
        for (i in 0..shown.size - exit.size - enter.size - 2) {
            if (shown.subList(i, i + exit.size) != exit) continue
            swungOut++
            // Out, gone a moment, and straight back in.
            assertEquals(gone, shown[i + exit.size])
            assertEquals(enter, shown.subList(i + exit.size + 1, i + exit.size + 1 + enter.size))
        }
        assertTrue("he swung out ($swungOut)", swungOut > 3)
    }

    @Test fun goneFromThePageWhileOutHeSwingsBackInLater() {
        val guy = PurpleGuyState()
        // Live till he's swung out, and stop there.
        val stage = NoteTaker(guy, Int.MAX_VALUE)
        val exit = poses(anims.exit)
        val stopper = object : PurpleGuyStage by stage {
            override fun show(pose: Int) {
                stage.show(pose)
                val s = stage.shown
                if (pose == gone && s.size > exit.size && s.subList(s.size - 1 - exit.size, s.size - 1) == exit) throw Enough()
            }
        }
        try {
            runBlocking { guy.live(anims, stopper, Random(1)) }
        } catch (_: Enough) {
        }
        assertEquals(Arrival.SWING_IN_LATER, guy.arrival())
        val back = live(guy, 1 + poses(anims.enter).size)
        assertEquals(gone, back.shown.first())
        assertEquals(poses(anims.enter), back.shown.drop(1))
    }

    @Test fun everythingStartsWithHimAtRest() {
        val stage = live(PurpleGuyState(), 20_000)
        for ((animation, showing) in stage.played) {
            if (animation === PurpleGuyAnims.REST_ONLY) continue   // getting his resting pose ready, to show
            if (animation === anims.enter) assertEquals("swinging in, from gone", gone, showing)
            else assertEquals("from rest", rest, showing)
        }
    }

    @Test fun neverTheSameThingTwiceRunning() {
        val things = anims.activities.values.toSet()
        val done = live(PurpleGuyState(), 50_000).played.map { it.first }.filter { it in things }
        assertTrue("he did things (${done.size})", done.size > 50)
        for (i in 1 until done.size) assertNotSame(done[i - 1], done[i])
    }

    @Test fun hisFavouritesComeUpMoreOften() {
        val done = live(PurpleGuyState(), 200_000).played.map { it.first }
        val favourites = listOf("juggle", "shades", "read", "fart").map { anims.activities.getValue(it) }
        val others = anims.activities.values - favourites.toSet()
        val usual = others.sumOf { a -> done.count { it === a } }.toDouble() / others.size
        for (f in favourites) assertTrue(done.count { it === f } > 2 * usual)
    }

    @Test fun heIsMostlyDoingThings() {
        // Not standing about: doing his things, or swinging out and back in,
        // well over half the time (about 70%); between them, a moment at
        // rest, blinking now and then.
        val stage = live(PurpleGuyState(), 100_000)
        val busy = stage.played.map { it.first }
            .filter { it in anims.activities.values || it === anims.exit || it === anims.enter }
            .sumOf { anims.lengthMs(it) }
        assertTrue("busy ${100 * busy / stage.ms}% of the time", busy >= stage.ms * 6 / 10)
        assertTrue(stage.played.any { it.first === anims.blink })
    }

    @Test fun heSwingsOutOnlyEveryFewMinutes() {
        val stage = live(PurpleGuyState(), 300_000)
        val swungOut = stage.played.count { it.first === anims.exit }
        val everyS = stage.ms / 1000 / swungOut
        assertTrue("every ${everyS}s", everyS in 150L..420L)
    }

    @Test fun turnedOffHeSwingsOut() {
        val guy = PurpleGuyState()
        guy.pose = rest
        val stage = NoteTaker(guy, Int.MAX_VALUE)
        runBlocking { guy.leave(anims, stage) }
        assertEquals(poses(anims.exit) + gone, stage.shown)
        assertFalse(guy.away)
    }

    @Test fun turnedOffWhileOutHeStaysOut() {
        val guy = PurpleGuyState()
        guy.pose = gone
        guy.away = true
        val stage = NoteTaker(guy, Int.MAX_VALUE)
        runBlocking { guy.leave(anims, stage) }
        assertEquals(listOf(gone), stage.shown)
        assertEquals(Arrival.THERE, guy.arrival())
    }
}
