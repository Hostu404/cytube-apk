package com.cytube.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class GifFramesTest {
    /** A 1x1 GIF of [frames] frames, built by hand: header, a 2-colour table,
     *  then per frame a timing extension and an image (with its own colour
     *  table if [local]), then the end. */
    private fun gif(frames: Int, local: Boolean = false): ByteArray {
        val b = ByteArrayOutputStream()
        b.write("GIF89a".toByteArray())
        b.write(byteArrayOf(1, 0, 1, 0, 0x80.toByte(), 0, 0))
        b.write(byteArrayOf(0, 0, 0, -1, -1, -1))
        repeat(frames) {
            b.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0, 10, 0, 0, 0))
            b.write(byteArrayOf(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, if (local) 0x80.toByte() else 0))
            if (local) b.write(byteArrayOf(0, 0, 0, -1, -1, -1))
            b.write(byteArrayOf(2, 2, 0x4C, 0x01, 0))
        }
        b.write(0x3B)
        return b.toByteArray()
    }

    @Test fun countsTheFrames() {
        assertEquals(1, GifFrames.count(gif(1)))
        assertEquals(3, GifFrames.count(gif(3)))
        assertEquals(4, GifFrames.count(gif(4, local = true)))
    }

    @Test fun stopsAtTheLimit() = assertEquals(2, GifFrames.count(gif(5), atMost = 2))

    @Test fun notAGifOrCutShort() {
        assertEquals(0, GifFrames.count("not a gif at all".toByteArray()))
        assertEquals(0, GifFrames.count(ByteArray(0)))
        assertTrue(GifFrames.count(gif(3).copyOf(40)) <= 1)
    }
}
