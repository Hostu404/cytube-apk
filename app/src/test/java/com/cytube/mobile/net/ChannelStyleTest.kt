package com.cytube.mobile.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Channel CSS as channels write it (excerpts from a real one), and the
 *  emote modifiers and name colours the app reads out of it. */
class ChannelStyleTest {

    private val css = """
        .userlist_owner {color:#FF5F1F !important}
        .userlist_op {color:#5D3FD3 !important}
        .userlist_item {color:#ECE3CA !important}
        @import url("https://example.invalid/anim.css");
        @font-face {font-family: 'Ring'; src: url('https://example.invalid/ring.ttf');}
        /* a comment */
        div[class^='chat-msg-spam'] {display:none}
        [title="/reverse"] {display: none;}
        [title="/reverse"] + .channel-emote {transform:scaleX(-1);}
        [title="/tiny"] {display: none;}
        [title="/tiny"] + .channel-emote {max-height: 24px; max-width: 30px;}
        [title="/wide"] {display: none;}
        [title="/wide"] + .channel-emote {transform: scale(1.5,0.8) translateX(20%);}
        [title="/negative"] {display: none;}
        [title="/negative"] + .channel-emote {filter: invert(1);}
        [title="/overlay"] {display: none;}
        [title="/overlay"] + .channel-emote {position: relative; margin-right: -90px;}
        [title="/translucent"] {display: none;}
        [title="/translucent"] + .channel-emote {position: relative; margin-right: -90px; opacity: 50%;}
        [title="/slide"] + .channel-emote {animation: slide 4s ease forwards;}
        @keyframes slide {
            from {transform:translate(0%)}
            to {transform:translate(150%)}
        }
        [title="/blastingoff"] + .channel-emote { animation: blastingoff 9s normal forwards cubic-bezier(0,0,0.4,1); animation-delay: 1s }
        @keyframes blastingoff {
            to {transform: translateX(300px) translateY(-80px) rotate(3640deg) scale(0);}
        }
        [title="/pendulum"] + .channel-emote { transform-origin: 50% -50%; animation: pendulum 3s infinite ease-in-out; }
        @keyframes pendulum {
            0% { transform: rotate(-20deg) }
            50% { transform: rotate(20deg) }
            100% { transform: rotate(-20deg) }
        }
        [title="/fadeinto"] {display: none;}
        [title="/fadeinto"] + .channel-emote {position: relative; margin-right: -96px; animation: fadeOut ease-in 7s; animation-fill-mode: forwards;}
        [title="/fadeinto"] + .channel-emote + .channel-emote {animation: fadeIn ease-in 5s ;animation-fill-mode: forwards;}
        @keyframes fadeOut {0% {opacity:1;} 100% {opacity:0;}}
        @keyframes fadeIn {0% {opacity:0;} 100% {opacity:1;}}
        [title="/backdrop"] {display: none;}
        [title="/backdrop"] + .channel-emote {height: 500px; opacity: 8%; position: relative; bottom: 500px; z-index: -10;}
        .channel-emote { max-height: 90px; max-width: 90px; }
        .snow { animation: fall 16s infinite linear, shake 4s infinite ease-in-out;
          */ text-shadow: 0 0 12px rgba(234,255,253,0.75); */
        }
    """.trimIndent()

    private val style = ChannelStyle.parse(css)
    private fun mod(name: String) = style.effects.modifiers.getValue(name)
    private fun frame(name: String, ms: Long) = mod(name).target.frameAt(ms, 90f, 90f, 1f)

    @Test fun readsTheChannelsEmoteSize() = assertEquals(90f, style.effects.baseEmotePx, 0f)

    @Test fun findsModifiersAndWhichAreHidden() {
        assertTrue(mod("/reverse").hidden)
        assertFalse(mod("/slide").hidden)
        assertNull(style.effects.modifiers["/unknown"])
    }

    @Test fun flipsStayFlips() {
        val f = frame("/reverse", 0)
        assertEquals(-1f, f.scaleX, 1e-4f)
        assertEquals(1f, f.scaleY, 1e-4f)
        assertEquals(0f, f.rotationZ, 1e-4f)
    }

    @Test fun transformsComposeInCssOrder() {
        // scale(1.5, 0.8) translateX(20%): the shift is scaled too, 30% of 90.
        val f = frame("/wide", 0)
        assertEquals(27f, f.translationX, 1e-3f)
        assertEquals(1.5f, f.scaleX, 1e-4f)
        assertEquals(0.8f, f.scaleY, 1e-4f)
    }

    @Test fun smallerMaxHeightShrinks() =
        assertEquals(24f / 90f, mod("/tiny").target.sizeFactor!!, 1e-4f)

    @Test fun filtersBecomeAColourMatrix() {
        val m = frame("/negative", 0).colorMatrix!!
        assertEquals(-1f, m[0], 1e-4f)
        assertEquals(255f, m[4], 1e-3f)
    }

    @Test fun negativeMarginStacks() {
        assertTrue(mod("/overlay").stacks)
        assertTrue(mod("/translucent").stacks)
        assertEquals(0.5f, frame("/translucent", 0).alpha, 1e-4f)
        assertFalse(mod("/reverse").stacks)
    }

    @Test fun oneOffAnimationHoldsItsEnd() {
        assertEquals(0f, frame("/slide", 0).translationX, 1e-3f)
        assertEquals(135f, frame("/slide", 60_000).translationX, 1e-3f)
    }

    @Test fun animationDelayAndImplicitFromKeyframe() {
        // Nothing moves during the 1s delay; at the end it's gone (scale 0).
        assertEquals(1f, frame("/blastingoff", 500).scaleX, 1e-4f)
        val end = frame("/blastingoff", 60_000)
        assertEquals(300f, end.translationX, 1e-2f)
        assertEquals(-80f, end.translationY, 1e-2f)
        assertEquals(0f, end.scaleX, 1e-4f)
    }

    @Test fun loopingAnimationWithOrigin() {
        assertEquals(-20f, frame("/pendulum", 0).rotationZ, 1e-2f)
        assertEquals(20f, frame("/pendulum", 1500).rotationZ, 1e-2f)
        assertEquals(-20f, frame("/pendulum", 3000).rotationZ, 1e-2f)
        assertEquals(-0.5f, mod("/pendulum").target.originY, 1e-4f)
        assertNull(mod("/pendulum").target.animation!!.endsAtMs)
    }

    @Test fun stackedSecondEmoteGetsItsOwnStyle() {
        val fade = mod("/fadeinto")
        assertTrue(fade.stacks)
        assertEquals(0f, fade.target.frameAt(8_000, 90f, 90f, 1f).alpha, 1e-4f)
        assertEquals(1f, fade.top.frameAt(8_000, 90f, 90f, 1f).alpha, 1e-4f)
        assertNotNull(fade.top.animation)
    }

    @Test fun rulesThatLeaveTheLineAreIgnored() {
        val backdrop = mod("/backdrop")
        assertTrue(backdrop.hidden)
        assertTrue(backdrop.target.isPlain)
        assertFalse(backdrop.stacks)
    }

    @Test fun nameColoursByRank() {
        val colors = style.nameColors
        assertEquals(0xFFFF5F1F.toInt(), colors.forRank(10.0))
        assertEquals(0xFF5D3FD3.toInt(), colors.forRank(2.0))
        assertEquals(0xFFECE3CA.toInt(), colors.forRank(1.0))
        assertEquals(0xFFECE3CA.toInt(), colors.forRank(0.0))
    }

    @Test(timeout = 5_000)
    fun cssMadeToBeSlowStillReadsFast() {
        // Each of these took time growing with the square of its length
        // through backtracking patterns; read linearly they're instant.
        ChannelStyle.parse("/*" + "/* x ".repeat(40_000))
        ChannelStyle.parse("@a;".repeat(60_000))
        ChannelStyle.parse("@keyframes k {" + "a".repeat(150_000))
        ChannelStyle.parse("[title=\"/x\"] + .channel-emote {transform: " + "scale".repeat(30_000) + "}")
        ChannelStyle.parse("[title=\"/x\"] + .channel-emote {transform: scale(" + "1".repeat(150_000) + "x)}")
        ChannelStyle.parse("[title=\"/x\"] + .channel-emote {animation: " + "a, ".repeat(40_000) + "}")
    }

    @Test fun emotesStayNearTheirPlaceInTheLine() {
        val big = ChannelStyle.parse(
            """
            [title="/huge"] + .channel-emote {transform: scale(100) translate(5000px, -5000px);}
            [title="/nan"] + .channel-emote {transform: scale(1e40); filter: blur(1e40px) brightness(1e40);}
            """.trimIndent()
        ).effects.modifiers
        val huge = big.getValue("/huge").target.frameAt(0, 90f, 90f, 1f)
        assertTrue(huge.scaleX <= 4f && huge.translationX <= 4 * 90f && huge.translationY >= -4 * 90f)
        val nan = big.getValue("/nan").target.frameAt(0, 90f, 90f, 1f)
        assertTrue(nan.scaleX.isFinite() && nan.scaleX <= 4f)
        assertTrue(nan.blurPx <= 45f)
        assertTrue(nan.colorMatrix == null || nan.colorMatrix.all { it.isFinite() })
    }

    @Test fun nothingUsefulMeansNothing() {
        val empty = ChannelStyle.parse("body { color: red } }}} {{ garbage")
        assertTrue(empty.effects.modifiers.isEmpty())
        assertNull(empty.nameColors.forRank(1.0))
    }
}
