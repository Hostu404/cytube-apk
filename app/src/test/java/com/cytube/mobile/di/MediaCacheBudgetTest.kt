package com.cytube.mobile.di

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaCacheBudgetTest {

    private val gb = 1_024L * 1_024L * 1_024L
    private val mb = 1_024L * 1_024L

    @Test
    fun `with plenty of room, each kind of device gets its ceiling`() {
        assertEquals(PHONE_MEDIA_CACHE_MAX_BYTES, mediaCacheBudgetBytes(freeBytes = 64 * gb, isTv = false))
        assertEquals(TV_MEDIA_CACHE_MAX_BYTES, mediaCacheBudgetBytes(freeBytes = 64 * gb, isTv = true))
    }

    @Test
    fun `never more than a quarter of the free space`() {
        assertEquals(500 * mb, mediaCacheBudgetBytes(freeBytes = 2_000 * mb, isTv = false))
        assertEquals(100 * mb, mediaCacheBudgetBytes(freeBytes = 400 * mb, isTv = true))
    }

    @Test
    fun `too little room for one to help means none`() {
        assertEquals(MIN_MEDIA_CACHE_BYTES, mediaCacheBudgetBytes(freeBytes = 4 * MIN_MEDIA_CACHE_BYTES, isTv = false))
        assertEquals(0L, mediaCacheBudgetBytes(freeBytes = 4 * MIN_MEDIA_CACHE_BYTES - 4, isTv = false))
        assertEquals(0L, mediaCacheBudgetBytes(freeBytes = 0L, isTv = true))
    }
}
