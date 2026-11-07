package com.cytube.mobile.ui.channel

import com.cytube.mobile.net.PlaylistItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LookAheadTest {
    private fun item(uid: Int) = PlaylistItem(uid, "Video $uid", "5:00", "yt", "id$uid", "someone")
    private val list = listOf(item(1), item(2), item(3))

    @Test fun theNextOneAMinuteFromTheEnd() = assertEquals(2, LookAhead.next(list, 1, 300, 241.0, -1)?.uid)

    @Test fun notBeforeThat() = assertNull(LookAhead.next(list, 1, 300, 239.0, -1))

    @Test fun onlyOnce() = assertNull(LookAhead.next(list, 1, 300, 250.0, alreadyUid = 2))

    @Test fun noneAfterTheLastForALivestreamOrAnUnknownCurrent() {
        assertNull(LookAhead.next(list, 3, 300, 290.0, -1))
        assertNull(LookAhead.next(list, 1, 0, 0.0, -1))
        assertNull(LookAhead.next(list, 9, 300, 290.0, -1))
    }
}
