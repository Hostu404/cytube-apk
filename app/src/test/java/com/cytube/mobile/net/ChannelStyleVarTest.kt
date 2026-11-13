package com.cytube.mobile.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelStyleVarTest {
    /** A var() with no closing bracket in a channel's CSS: given up on at
     *  once, where it used to take time growing with its length squared. */
    @Test(timeout = 5_000)
    fun anUnclosedVarIsQuick() {
        val tail = "b".repeat(40_000)
        ChannelStyle.parse(":root { --a: red } body { color: var(--a, $tail; background: var(--a, $tail }")
    }

    /** Variables that each name another several times grow a value
     *  exponentially, level by level: a few hundred characters of CSS like
     *  this ran the app out of memory. Past a size a value is left as
     *  written (reading as nothing), and the rest of the stylesheet is read
     *  as usual. */
    @Test(timeout = 5_000)
    fun varsCantGrowAValueWithoutLimit() {
        for (refs in listOf(30, 100)) {
            val style = ChannelStyle.parse(
                ":root { --a: ${List(refs) { "var(--a)" }.joinToString(" ")} } " +
                    ".userlist_owner { color: red } .x { color: var(--a) } body { background: var(--a) }"
            )
            assertEquals(0xFFFF0000.toInt(), style.nameColors.owner)
            assertNull(style.effects.textLooks["x"])
        }
    }

    /** Ordinary variables, nested a few deep, are filled in as before. */
    @Test fun nestedVarsAreFilledIn() {
        val style = ChannelStyle.parse(
            ":root { --c: #00ff00; --d: var(--c); --img: url(https://i.imgur.com/x.png) } " +
                ".greentext { color: var(--d) } body { background: var(--img) center / cover }"
        )
        assertEquals(0xFF00FF00.toInt(), style.effects.textLooks["greentext"]?.color)
        assertEquals("https://i.imgur.com/x.png", style.pageBackground?.url)
    }
}
