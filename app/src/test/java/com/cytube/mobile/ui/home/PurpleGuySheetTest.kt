package com.cytube.mobile.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PurpleGuySheetTest {
    private val dir = File("src/main/assets/purple_guy")
    private val anims = PurpleGuyAnims.parse(File(dir, "anims.json").readText())
    private val poseCount = anims.poses.size / 7

    private val all = mapOf("blink" to anims.blink, "enter" to anims.enter, "exit" to anims.exit) + anims.activities

    private fun poses(animation: IntArray) = animation.filterIndexed { i, _ -> i % 2 == 0 }

    @Test fun everyAnimationRunsSeamlesslyIntoTheNext() {
        // Each starts where the last left him, at rest: but swinging in
        // starts with him gone, and swinging out ends with him gone.
        for ((name, animation) in all) {
            if (name != "enter") assertEquals("$name starts at rest", PurpleGuyAnims.REST, poses(animation).first())
            if (name != "exit") assertEquals("$name ends at rest", PurpleGuyAnims.REST, poses(animation).last())
        }
    }

    @Test fun hisFavouritesAreThere() {
        for (name in listOf("juggle", "shades", "read", "fart")) assertTrue(name, name in anims.activities)
    }

    @Test fun posesAndTimesAreAllThere() {
        for ((name, animation) in all) {
            assertTrue("$name: something in it", animation.isNotEmpty())
            for (i in animation.indices step 2) {
                assertTrue("$name: pose ${animation[i]}", animation[i] in 0 until poseCount)
                assertTrue("$name: ${animation[i + 1]}ms", animation[i + 1] > 0)
            }
        }
    }

    @Test fun drawnTheSizeHeIsOnTheSheets() {
        assertEquals(CELL_PIXELS_W, anims.cellW)
        assertEquals(CELL_PIXELS_H, anims.cellH)
    }

    @Test fun everyPoseIsOnItsSheetAndInItsCell() {
        val sheets = anims.sheets.map { webpSize(File(dir, it)) }
        for (p in 0 until poseCount) {
            val v = anims.poses.copyOfRange(p * 7, p * 7 + 7)
            val (s, x, y, w, h) = v.toList()
            val (sheetW, sheetH) = sheets[s]
            assertTrue("pose $p on sheet $s", x >= 0 && y >= 0 && x + w <= sheetW && y + h <= sheetH)
            assertTrue("pose $p in its cell", v[5] >= 0 && v[6] >= 0 && v[5] + w <= anims.cellW && v[6] + h <= anims.cellH)
        }
    }

    @Test fun sheetsAreSmallEnoughToLoadOneOrTwoAtATime() {
        for (name in anims.sheets) {
            val (w, h) = webpSize(File(dir, name))
            assertTrue("$name: ${w}x$h", w <= 1024 && h <= 512)
        }
    }

    @Test fun heTakesUpNoMoreThanHalfAMegabyte() {
        val bytes = dir.listFiles()!!.sumOf { it.length() }
        assertTrue("${bytes / 1024} KB", bytes <= 500 * 1024)
    }

    @Test fun switchedOnHeSwingsIn() {
        val guy = PurpleGuyState()
        // The app opening with him on: he's already there.
        assertEquals(Arrival.THERE, guy.arrival())
        guy.switchedOn()
        assertEquals(Arrival.SWING_IN, guy.arrival())
    }

    @Test fun swungOutHeSwingsBackIn() {
        val guy = PurpleGuyState()
        guy.away = true
        assertEquals(Arrival.SWING_IN_LATER, guy.arrival())
    }

    /** A WebP's width and height, from its header (javax.imageio can't
     *  read WebP). */
    private fun webpSize(file: File): Pair<Int, Int> {
        val b = file.readBytes()
        fun byte(i: Int) = b[i].toInt() and 0xff
        fun u24(i: Int) = byte(i) or (byte(i + 1) shl 8) or (byte(i + 2) shl 16)
        fun text(i: Int) = String(b, i, 4, Charsets.US_ASCII)
        assertTrue("${file.name}: a WebP", text(0) == "RIFF" && text(8) == "WEBP")
        return when (text(12)) {
            "VP8X" -> (u24(24) + 1) to (u24(27) + 1)
            "VP8L" -> {
                val bits = u24(21) or (byte(24) shl 24)
                ((bits and 0x3fff) + 1) to (((bits ushr 14) and 0x3fff) + 1)
            }
            else -> ((byte(26) or (byte(27) shl 8)) and 0x3fff) to ((byte(28) or (byte(29) shl 8)) and 0x3fff)
        }
    }
}
