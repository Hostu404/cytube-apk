package com.cytube.mobile.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The picture behind the top of the channel screen: the channel's
 *  background, else its notice's (ChannelStyle, ChannelHeader). */
class ChannelHeaderTest {

    private fun page(css: String) = ChannelStyle.parse(css).pageBackground?.url
    private fun motd(css: String) = ChannelStyle.parse(css).motdBackground?.url
    private fun position(css: String) = ChannelStyle.parse(css).let { it.pageBackground ?: it.motdBackground }
        ?.let { it.alignX to it.alignY }

    /** v4c's, as it is: its background is a fixed, full-size layer its
     *  script adds, at 40%, pixel art kept pixelated. */
    private val v4c = """
        @import 'https://v4c.xyz/v4c/v4c-script/v4c-script-release.css';
        @import 'https://use.fontawesome.com/releases/v5.2.0/css/all.css';
        .queue_active { background-image: none; }
        /*
        body {
        background: linear-gradient(rgba(0,0,0,0.8), rgba(0,0,0,0.8)), url("https://steamuserimages-a.akamaihd.net/x.jpg");
        }
        */
        .halloween #lights { background-image: url('https://anax.feralhosting.com/tetr4/v4c-assets/etc/lights_halloween.webp'); }
        #cv4_bg, #cv4_bg > div {
          image-rendering: pixelated;
        }
        #cv4_bg {
            background: url(https://anax.feralhosting.com/tetr4/v4c-assets/cv4/bg.png);
            height: 100%;
            background-size: 100% 100%!important;
            background-repeat: no-repeat!important;
            width: 100%;
            position: fixed;
            pointer-events: none;
            opacity: 0.4;
            z-index: -2;
        }
        body.synchtube #cv4_bg {
            background: url(https://anax.feralhosting.com/tetr4/v4c-assets/cv4/bg_rev.png);
        }
        body.halloween {
          background-color: black!important;
        }
        #cv4_bg > div {
            position: absolute;
            width: 100%;
            background-size: contain!important;
        }
        #cv4_top {
            background: url(https://anax.feralhosting.com/tetr4/v4c-assets/cv4/fog_top.png);
            height: 21.4285714285714%;
            top: 0;
        }
    """

    @Test
    fun `v4c's background layer`() {
        val style = ChannelStyle.parse(v4c)
        assertEquals(
            ChannelBackground("https://anax.feralhosting.com/tetr4/v4c-assets/cv4/bg.png", opacity = 0.4f, pixelated = true),
            style.pageBackground
        )
        // Its own stylesheet is to be looked in too; the icon set isn't.
        assertEquals(listOf("https://v4c.xyz/v4c/v4c-script/v4c-script-release.css"), style.imports)
    }

    @Test
    fun `page background from the ways channels set it`() {
        assertEquals("https://i.imgur.com/a.jpg", page("body { background: #000 url('https://i.imgur.com/a.jpg') fixed; background-size: cover }"))
        assertEquals("https://i.imgur.com/b.png", page("body{background-image:url(https://i.imgur.com/b.png)!important}"))
        assertEquals("https://i.imgur.com/c.gif", page("""html, body { background: url("https://i.imgur.com/c.gif") }"""))
        assertEquals("https://i.imgur.com/d.jpg", page("#wrap { background: url(https://i.imgur.com/d.jpg) no-repeat center }"))
        assertEquals("https://i.imgur.com/e.jpg", page("#mainpage { background-image: url('https://i.imgur.com/e.jpg') }"))
        assertEquals("https://i.imgur.com/f.jpg", page("BODY { background: url(https://i.imgur.com/f.jpg) }"))
        // A layer pinned to the window's edges rather than sized.
        assertEquals(
            "https://i.imgur.com/g.jpg",
            page("#bg { position: fixed; top: 0; right: 0; bottom: 0; left: 0; z-index: -1; background: url(https://i.imgur.com/g.jpg) }")
        )
        // Only inside an @media block.
        assertEquals("https://i.imgur.com/h.jpg", page("@media (min-width: 768px) { body { background: url(https://i.imgur.com/h.jpg) } }"))
    }

    @Test
    fun `a layer is drawn over the body's own background`() {
        assertEquals(
            "https://i.imgur.com/layer.png",
            page("""
                body { background: url(https://i.imgur.com/body.png) }
                .bg { position: fixed; width: 100%; height: 100%; z-index: -5; background: url(https://i.imgur.com/layer.png) }
            """)
        )
        // A top-level rule wins over one only inside @media.
        assertEquals(
            "https://i.imgur.com/top.png",
            page("@media (max-width: 600px) { body { background: url(https://i.imgur.com/small.png) } } body { background: url(https://i.imgur.com/top.png) }")
        )
    }

    @Test
    fun `not page backgrounds`() {
        assertNull(page(""))
        assertNull(page("body { background-color: #111; color: #eee }"))
        assertNull(page("body { background: linear-gradient(#000, #333) }"))
        assertNull(page("body { background: url(data:image/png;base64,AAAA) }"))
        assertNull(page("body { background: url(/img/bg.jpg) }"))
        assertNull(page(".chat-msg-bot { background: url(https://i.imgur.com/x.jpg) }"))
        assertNull(page("body .navbar { background: url(https://i.imgur.com/x.jpg) }"))
        // Fixed, but in front of the page (a pop-up, an effect over it)...
        assertNull(page("#popup { position: fixed; width: 100%; height: 100%; z-index: 1000; background: url(https://i.imgur.com/x.jpg) }"))
        // ...or not covering the window (a corner badge).
        assertNull(page("#badge { position: fixed; width: 64px; height: 64px; z-index: -1; background: url(https://i.imgur.com/x.jpg) }"))
        // A layer that's switched off.
        assertNull(page("#bg { display: none; position: fixed; width: 100%; height: 100%; z-index: -1; background: url(https://i.imgur.com/x.jpg) }"))
    }

    @Test
    fun `http and protocol-relative are asked for over https`() {
        assertEquals("https://example.com/bg.jpg", page("body { background: url(http://example.com/bg.jpg) }"))
        assertEquals("https://example.com/bg.jpg", page("body { background: url(//example.com/bg.jpg) }"))
    }

    @Test
    fun `a later rule wins`() {
        assertEquals(
            "https://i.imgur.com/2.jpg",
            page("body { background: url(https://i.imgur.com/1.jpg) } body { background-image: url(https://i.imgur.com/2.jpg) }")
        )
        assertNull(page("body { background: url(https://i.imgur.com/1.jpg) } body { background: #000 }"))
        assertEquals(
            "https://i.imgur.com/1.jpg",
            page("body { background: url(https://i.imgur.com/1.jpg) } body { background-color: #000 }")
        )
    }

    @Test
    fun `imported stylesheets`() {
        assertEquals(
            listOf("https://a.example/theme.css", "https://b.example/more.css"),
            ChannelStyle.parse("""
                @import url("https://a.example/theme.css");
                @import "http://b.example/more.css" screen;
                @import url(https://fonts.googleapis.com/css?family=Lato);
                @import '/relative.css';
                body { color: white }
            """).imports
        )
    }

    @Test
    fun `notice background from the CSS`() {
        assertEquals("https://i.imgur.com/spooky.png", motd("#motd { background: url(https://i.imgur.com/spooky.png) right center no-repeat }"))
        assertEquals("https://i.imgur.com/m.png", motd("#motdwrap { background-image: url('https://i.imgur.com/m.png') }"))
        assertNull(page("#motd { background: url(https://i.imgur.com/spooky.png) }"))
    }

    @Test
    fun `the channel's background first, then the notice's`() {
        val own = ChannelStyle.parse("#motd { background: url(https://i.imgur.com/motd-own.png) }")
        val imported = ChannelStyle.parse("body { background: url(https://i.imgur.com/imported.png) }")
        val notice = """<div style="background:url('https://i.imgur.com/motd-html.png')">Hi</div>"""
        // A background in an imported stylesheet beats the notice's.
        assertEquals("https://i.imgur.com/imported.png", ChannelHeader.image(listOf(own, imported), "", notice)?.url)
        // The channel's own beats an imported one.
        val ownPage = ChannelStyle.parse("body { background: url(https://i.imgur.com/own.png) }")
        assertEquals("https://i.imgur.com/own.png", ChannelHeader.image(listOf(ownPage, imported), "", notice)?.url)
        // One its script sets, before the notice's.
        val script = """${'$'}("body").css("background-image", "url('https://i.imgur.com/script.jpg')");"""
        assertEquals("https://i.imgur.com/script.jpg", ChannelHeader.image(listOf(own), script, notice)?.url)
        // Then the notice's: from the CSS, then from its own elements.
        assertEquals("https://i.imgur.com/motd-own.png", ChannelHeader.image(listOf(own), "", notice)?.url)
        assertEquals("https://i.imgur.com/motd-html.png", ChannelHeader.image(listOf(ChannelStyle.EMPTY), "", notice)?.url)
        assertNull(ChannelHeader.image(listOf(ChannelStyle.EMPTY), "", ""))
    }

    @Test
    fun `background set on the notice's elements`() {
        assertEquals(
            "https://i.imgur.com/bg.jpg",
            ChannelHeader.motdBackground("""<div style="color: orange; background-image: url(&quot;https://i.imgur.com/bg.jpg&quot;)"><b>WELCOME</b></div>""")?.url
        )
        // With its position.
        val banner = ChannelHeader.motdBackground("""<div style="background: #111 url('https://i.imgur.com/b.png') right center no-repeat">Hi</div>""")
        assertEquals(1f to 0f, banner?.let { it.alignX to it.alignY })
    }

    @Test
    fun `never a picture placed in the notice`() {
        assertNull(ChannelHeader.motdBackground("""<center><img src="https://i.imgur.com/top.png" width="800"></center><br>Welcome!"""))
        assertNull(ChannelHeader.motdBackground("""Welcome!<br><img src="https://i.imgur.com/poster.jpg" width="60">"""))
        assertNull(ChannelHeader.image(listOf(ChannelStyle.EMPTY), "", """<img src="https://i.imgur.com/banner.png" style="width:100%">"""))
    }

    @Test
    fun `script backgrounds`() {
        assertEquals("https://i.imgur.com/1.png", ChannelHeader.scriptBackground("""document.body.style.backgroundImage = "url(https://i.imgur.com/1.png)";"""))
        assertEquals("https://i.imgur.com/2.png", ChannelHeader.scriptBackground("""${'$'}('#bg').css({background: 'url(\"http://i.imgur.com/2.png\") center'});"""))
        // A url() that isn't a background.
        assertNull(ChannelHeader.scriptBackground("""var cursor = "url(https://i.imgur.com/cursor.png), auto"; el.style.cursor = cursor;"""))
        assertNull(ChannelHeader.scriptBackground(""))
    }

    @Test
    fun `a layer's opacity`() {
        val bg = ChannelStyle.parse("#bg { position: fixed; width: 100vw; height: 100vh; pointer-events: none; opacity: 25%; background: url(https://i.imgur.com/o.png) }").pageBackground
        assertEquals(0.25f, bg?.opacity ?: 0f, 0.001f)
        assertFalse(bg?.pixelated ?: true)
        assertTrue(ChannelStyle.parse("body { background: url(https://i.imgur.com/p.png) }").pageBackground?.opacity == 1f)
    }

    @Test
    fun `which part of the picture is kept in view`() {
        // Not given: left to the app (the busiest part).
        assertEquals(null to null, position("body { background: url(https://i.imgur.com/a.jpg) }"))
        assertEquals(1f to 0f, position("#motd { background: url(https://i.imgur.com/a.jpg) right center no-repeat }"))
        assertEquals(0f to -1f, position("body { background-image: url(https://i.imgur.com/a.jpg); background-position: center top }"))
        assertEquals(-1f to 1f, position("body { background: url(https://i.imgur.com/a.jpg); background-position: left bottom }"))
        assertEquals(0f to 1f, position("body { background: url(https://i.imgur.com/a.jpg) 50% 100% / cover fixed }"))
        position("body { background: url(https://i.imgur.com/a.jpg) right 30% }").let {
            assertEquals(1f, it?.first ?: 0f, 0.001f)
            assertEquals(-0.4f, it?.second ?: 0f, 0.001f)
        }
        // Only one keyword: the other axis is left to the app.
        assertEquals(null to -1f, position("body { background: url(https://i.imgur.com/a.jpg) top }"))
        // A size after the slash is no position.
        assertEquals(0f to null, position("body { background: url(https://i.imgur.com/a.jpg) center / 100% 100% }"))
        // A layer's own.
        assertEquals(
            1f to 1f,
            position("#bg { position: fixed; inset: 0; z-index: -1; background: url(https://i.imgur.com/a.jpg) right bottom }")
        )
    }

    @Test
    fun `whether a background repeats like a pattern or fills its box`() {
        fun repeats(css: String) = ChannelStyle.parse(css).let { it.pageBackground ?: it.motdBackground }?.repeat
        // CSS's default: it repeats.
        assertEquals(true, repeats("body { background: #222 url(https://i.imgur.com/noise.png) }"))
        assertEquals(true, repeats("body { background-image: url(https://i.imgur.com/noise.png); background-repeat: repeat-x }"))
        assertEquals(false, repeats("body { background: url(https://i.imgur.com/a.jpg) no-repeat center }"))
        assertEquals(false, repeats("body { background: url(https://i.imgur.com/a.jpg) center / cover }"))
        assertEquals(false, repeats("body { background-image: url(https://i.imgur.com/a.jpg); background-size: cover }"))
        assertEquals(false, repeats("#motd { background: url(https://i.imgur.com/m.png); background-repeat: no-repeat }"))
        // v4c's layer: no-repeat, stretched to 100% 100%.
        assertEquals(false, ChannelStyle.parse(v4c).pageBackground?.repeat)
        // A notice element's own.
        assertEquals(
            true,
            ChannelHeader.motdBackground("""<div style="background: url(https://i.imgur.com/tile.gif)">Hi</div>""")?.repeat
        )
        // One a script sets is cropped to fill.
        assertEquals(
            false,
            ChannelHeader.image(listOf(ChannelStyle.EMPTY), "document.body.style.background = 'url(https://i.imgur.com/s.png)'", "")?.repeat
        )
    }

    @Test
    fun `background settings in a theme script`() {
        // Robert's Kinoplex, configuring the Nexus theme script.
        val kinoplex = """
            //Name to display in the top left of the screen
            window.ChannelName = "Robert's Kinoplex";
            //Discord link
            window.Discord_URL = "https://discord.gg/7z2eZvUFDj";
            //Background image URL. Can also be a base64 image (must start with data:image/png;base64,)
            window.Background_Image = "https://cdn.imgchest.com/files/my8xcmrrzq4.png";
            window.ThemeOverride = "custtheme_glass_halloween";
            if (!window.RUNONCE) {
                window.RUNONCE = true;
                ${'$'}.getScript("https://checkthesedubs.github.io/js/Nexus-beta.js")
            }
        """
        assertEquals("https://cdn.imgchest.com/files/my8xcmrrzq4.png", ChannelHeader.scriptBackground(kinoplex))
        assertEquals(
            "https://cdn.imgchest.com/files/my8xcmrrzq4.png",
            ChannelHeader.image(listOf(ChannelStyle.EMPTY), kinoplex, "<p>Welcome to Robert's Kinoplex!</p>")?.url
        )
        // Other ways of naming it.
        assertEquals("https://i.imgur.com/a.jpg", ChannelHeader.scriptBackground("""var bgImage = 'https://i.imgur.com/a.jpg';"""))
        assertEquals("https://i.imgur.com/b.png", ChannelHeader.scriptBackground("""const theme = { channelBg: "https://i.imgur.com/b.png", accent: "#f00" };"""))
        assertEquals("https://i.imgur.com/c.png", ChannelHeader.scriptBackground("""settings["backgroundUrl"] = "https://i.imgur.com/c.png";"""))
        // Whichever comes first.
        assertEquals(
            "https://i.imgur.com/first.png",
            ChannelHeader.scriptBackground("""BG = "https://i.imgur.com/first.png"; ${'$'}("body").css("background", "url(https://i.imgur.com/second.png)");""")
        )
    }

    @Test
    fun `not background settings`() {
        // Not named as one.
        assertNull(ChannelHeader.scriptBackground("""window.Discord_URL = "https://discord.gg/x"; var debug = "https://example.com/log.png";"""))
        // Named as one, but no picture: a song, a video.
        assertNull(ChannelHeader.scriptBackground("""window.Background_Music = "https://example.com/theme.mp3"; bgVideo = "https://example.com/loop.webm?x=1";"""))
        // A picture given inline (data:), which the app doesn't load.
        assertNull(ChannelHeader.scriptBackground("""window.Background_Image = "data:image/png;base64,AAAA";"""))
        // A comment's "//" is no address.
        assertNull(ChannelHeader.scriptBackground("""//Background image URL. Can also be a base64 image"""))
    }

    @Test
    fun `remembered between visits`() {
        val v4cLayer = ChannelStyle.parse(v4c).pageBackground!!
        assertEquals(v4cLayer, ChannelBackground.decode(v4cLayer.encode()))
        val positioned = ChannelBackground("https://i.imgur.com/a.png", alignX = 1f, alignY = null, repeat = true)
        assertEquals(positioned, ChannelBackground.decode(positioned.encode()))
        // Anything else is nothing.
        assertNull(ChannelBackground.decode(""))
        assertNull(ChannelBackground.decode("https://i.imgur.com/a.png"))
        assertNull(ChannelBackground.decode("javascript:alert(1)\t1.0\tfalse\t\t\tfalse"))
        assertNull(ChannelBackground.decode("https://i.imgur.com/a.png\tlots\tfalse\t\t\tfalse"))
    }

    @Test
    fun `relative addresses, from the stylesheet they're in`() {
        val sheet = "https://someone.github.io/theme/theme.css"
        assertEquals("https://someone.github.io/theme/img/bg.jpg", ChannelStyle.parse("body { background: url(img/bg.jpg) }", sheet).pageBackground?.url)
        assertEquals("https://someone.github.io/bg.jpg", ChannelStyle.parse("body { background: url('../bg.jpg') }", sheet).pageBackground?.url)
        assertEquals("https://someone.github.io/root.png", ChannelStyle.parse("body { background: url(/root.png) }", sheet).pageBackground?.url)
        // The channel's own CSS: from the channel page.
        assertEquals("https://cytu.be/img/x.png", ChannelStyle.parse("body { background: url(/img/x.png) }", "https://cytu.be/r/test").pageBackground?.url)
        // Its @imports too.
        assertEquals(listOf("https://someone.github.io/theme/more.css"), ChannelStyle.parse("@import 'more.css';", sheet).imports)
        // Still never data: or the like.
        assertNull(ChannelStyle.parse("body { background: url(data:image/png;base64,AAAA) }", sheet).pageBackground)
        // And the notice's.
        assertEquals(
            "https://cytu.be/banners/b.png",
            ChannelHeader.motdBackground("""<div style="background: url(/banners/b.png)">Hi</div>""", "https://cytu.be/r/test")?.url
        )
    }

    @Test
    fun `CSS variables`() {
        assertEquals(
            "https://i.imgur.com/v.png",
            page(":root { --bg: url(https://i.imgur.com/v.png); --accent: #f80 } body { background: var(--bg) center / cover }")
        )
        assertEquals(false, ChannelStyle.parse(":root { --bg: url(https://i.imgur.com/v.png) } body { background: var(--bg) center / cover }").pageBackground?.repeat)
        // A variable that isn't there: its fallback.
        assertEquals("https://i.imgur.com/f.png", page("body { background-image: var(--missing, url(https://i.imgur.com/f.png)) }"))
        // One variable made of another.
        assertEquals("https://i.imgur.com/n.png", page(":root { --pic: url(https://i.imgur.com/n.png); --bg: var(--pic) } html { background: var(--bg) }"))
    }

    @Test
    fun `a layered background's picture is its bottom layer`() {
        assertEquals("https://i.imgur.com/photo.jpg", page("body { background: url(https://i.imgur.com/scanlines.png), url(https://i.imgur.com/photo.jpg) center / cover }"))
        assertEquals("https://i.imgur.com/photo.jpg", page("body { background: linear-gradient(rgba(0,0,0,.8), rgba(0,0,0,.8)), url(https://i.imgur.com/photo.jpg) }"))
        assertEquals(false, ChannelStyle.parse("body { background: url(https://i.imgur.com/s.png) repeat, url(https://i.imgur.com/p.jpg) no-repeat }").pageBackground?.repeat)
    }

    @Test
    fun `a body with a class, as a last resort`() {
        assertEquals("https://i.imgur.com/h.png", page("body.halloween { background: url(https://i.imgur.com/h.png) }"))
        assertEquals("https://i.imgur.com/d.png", page("html.dark body { background-image: url(https://i.imgur.com/d.png) }"))
        // A plain one wins.
        assertEquals(
            "https://i.imgur.com/plain.png",
            page("body.halloween { background: url(https://i.imgur.com/h.png) } body { background: url(https://i.imgur.com/plain.png) }")
        )
        // Not something inside the body.
        assertNull(page("body .well { background: url(https://i.imgur.com/w.png) }"))
    }

    @Test
    fun `every candidate, best first, for when one won't load`() {
        val own = ChannelStyle.parse("body { background: url(https://i.imgur.com/own.png) } #motd { background: url(https://i.imgur.com/motd.png) }")
        val imported = ChannelStyle.parse("body { background: url(https://i.imgur.com/imported.png) }")
        assertEquals(
            listOf("https://i.imgur.com/own.png", "https://i.imgur.com/imported.png", "https://i.imgur.com/script.png", "https://i.imgur.com/motd.png", "https://i.imgur.com/inline.png"),
            ChannelHeader.candidates(
                listOf(own, imported),
                "window.Background_Image = 'https://i.imgur.com/script.png';",
                """<div style="background:url(https://i.imgur.com/inline.png)">x</div>"""
            ).map { it.url }
        )
        // The same picture twice is one candidate.
        val again = ChannelStyle.parse("body { background: url(https://i.imgur.com/own.png) }")
        assertEquals(1, ChannelHeader.candidates(listOf(own, again), "", "").count { it.url == "https://i.imgur.com/own.png" })
    }

    @Test
    fun `stylesheets a script adds`() {
        assertEquals(
            listOf("https://someone.github.io/theme.css", "https://cdn.example/x.css?v=3"),
            ChannelHeader.scriptStylesheets("""
                ${'$'}("head").append('<link rel="stylesheet" href="https://someone.github.io/theme.css">');
                loadCSS("https://cdn.example/x.css?v=3");
                ${'$'}("head").append('<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Lato">');
                ${'$'}.getScript("https://someone.github.io/theme.js");
            """)
        )
        assertEquals(emptyList<String>(), ChannelHeader.scriptStylesheets(""))
    }
}
