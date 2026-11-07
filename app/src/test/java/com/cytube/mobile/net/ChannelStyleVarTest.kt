package com.cytube.mobile.net

import org.junit.Test

class ChannelStyleVarTest {
    /** A var() with no closing bracket in a channel's CSS: given up on at
     *  once, where it used to take time growing with its length squared. */
    @Test(timeout = 5_000)
    fun anUnclosedVarIsQuick() {
        val tail = "b".repeat(40_000)
        ChannelStyle.parse(":root { --a: red } body { color: var(--a, $tail; background: var(--a, $tail }")
    }
}
