package com.cytube.mobile

import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class GifBytesTest {
    private fun bytes(n: Int) = ByteArray(n) { (it * 31).toByte() }

    /** [total] bytes, handed out [chunk] at a time, as a slow download would. */
    private class Trickle(private val total: Long, private val chunk: Int) : Source {
        var given = 0L
        override fun read(sink: Buffer, byteCount: Long): Long {
            if (given >= total) return -1
            val n = minOf(byteCount, chunk.toLong(), total - given)
            sink.write(ByteArray(n.toInt()) { 7 })
            given += n
            return n
        }
        override fun timeout() = Timeout.NONE
        override fun close() = Unit
    }

    @Test fun readsAGifUpToTheLimitWhole() {
        assertArrayEquals(bytes(100), GifBytes.read(Buffer().write(bytes(100)), max = 100))
        assertArrayEquals(bytes(99), GifBytes.read(Buffer().write(bytes(99)), max = 100))
        assertEquals(0, GifBytes.read(Buffer(), max = 100).size)
        // In many small reads, the way a download arrives.
        assertEquals(50_000, GifBytes.read(Trickle(50_000, 1_000).buffer(), max = 50_000).size)
    }

    @Test fun refusesOneByteOver() {
        try {
            GifBytes.read(Buffer().write(bytes(101)), max = 100)
            fail("read past the limit")
        } catch (_: IOException) {
        }
    }

    /** A source that never ends is read only a little past the limit, not
     *  until memory runs out. */
    @Test fun stopsReadingSoonAfterTheLimit() {
        val endless = Trickle(Long.MAX_VALUE, 8_192)
        try {
            GifBytes.read(endless.buffer(), max = 1_000_000)
            fail("read past the limit")
        } catch (_: IOException) {
        }
        assertTrue("read ${endless.given}", endless.given <= 1_000_000 + 1 + 8_192)
    }
}
