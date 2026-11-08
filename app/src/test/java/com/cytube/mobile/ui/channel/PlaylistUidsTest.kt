package com.cytube.mobile.ui.channel

import com.cytube.mobile.net.PlaylistItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** The playlist panels key their rows on uid: a list with one twice can't
 *  be drawn at all. */
class PlaylistUidsTest {

    private fun item(uid: Int, id: String) = PlaylistItem(uid, "Video $id", "3:00", "yt", id, "someone")

    @Test
    fun `each uid is kept once, the first of any repeats`() {
        val list = listOf(item(1, "a"), item(2, "b"), item(1, "c"), item(3, "d"), item(2, "e"))
        assertEquals(listOf("a", "b", "d"), list.withUniqueUids().map { it.mediaId })
    }

    @Test
    fun `items without a uid are all kept`() {
        val list = listOf(item(-1, "a"), item(-1, "b"), item(4, "c"))
        assertEquals(listOf("a", "b", "c"), list.withUniqueUids().map { it.mediaId })
    }
}
