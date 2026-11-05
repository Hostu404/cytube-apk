package com.cytube.mobile.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Each playlist item's link to its page, as the website's formatURL() makes them. */
class MediaPageTest {
    @Test fun eachSourceLinksToItsPageAsTheWebsiteDoes() {
        assertEquals("https://youtube.com/watch?v=dQw4w9WgXcQ", MediaLink.pageOf("yt", "dQw4w9WgXcQ"))
        assertEquals("https://vimeo.com/76979871", MediaLink.pageOf("vi", "76979871"))
        assertEquals("https://dailymotion.com/video/x7tgad0", MediaLink.pageOf("dm", "x7tgad0"))
        assertEquals("https://livestream.com/accounts/123/events/456", MediaLink.pageOf("li", "123;456"))
        assertEquals("https://twitch.tv/lofigirl", MediaLink.pageOf("tw", "lofigirl"))
        assertEquals("https://docs.google.com/file/d/abc_DEF-123", MediaLink.pageOf("gd", "abc_DEF-123"))
        assertEquals("https://streamable.com/moo", MediaLink.pageOf("sb", "moo"))
        assertEquals("https://clips.twitch.tv/FunnyClip", MediaLink.pageOf("tc", "FunnyClip"))
        assertEquals("https://www.bitchute.com/video/abcDEF123/", MediaLink.pageOf("bc", "abcDEF123"))
        assertEquals("https://artist.bandcamp.com/track/a-song", MediaLink.pageOf("bn", "artist;a-song"))
        assertEquals("https://odysee.com/@someone/a-video", MediaLink.pageOf("od", "someone;a-video"))
        assertEquals("https://www.nicovideo.jp/watch/sm9", MediaLink.pageOf("nv", "sm9"))
        // Those whose id is the link: a SoundCloud page, a raw file, an HLS stream, a manifest.
        assertEquals("https://soundcloud.com/a/b", MediaLink.pageOf("sc", "https://soundcloud.com/a/b"))
        assertEquals("https://example.com/v.mp4", MediaLink.pageOf("fi", "https://example.com/v.mp4"))
        assertEquals("https://example.com/live.m3u8", MediaLink.pageOf("hl", "https://example.com/live.m3u8"))
        assertEquals("https://example.com/m.json", MediaLink.pageOf("cm", "https://example.com/m.json"))
    }

    @Test fun customEmbedsAndPeerTubeFromTheirMeta() {
        assertEquals("https://player.example/embed/1", MediaLink.pageOf("cu", "", MediaEmbed(src = "https://player.example/embed/1")))
        assertEquals("https://tube.example.org/w/abc123",
            MediaLink.pageOf("pt", "tube.example.org;abc123", MediaEmbed(domain = "tube.example.org", short = "abc123")))
        assertEquals("https://tube.example.org/videos/watch/9c9de5e8-0a1e-484a-b099-e80766180a6d",
            MediaLink.pageOf("pt", "x", MediaEmbed(domain = "tube.example.org", uuid = "9c9de5e8-0a1e-484a-b099-e80766180a6d", onlyLong = true)))
    }

    @Test fun nothingWhereThereIsNoPage() {
        assertNull("not a web page", MediaLink.pageOf("rt", "rtmp://live.example/app/key"))
        assertNull(MediaLink.pageOf("fi", "javascript:alert(1)"))
        assertNull(MediaLink.pageOf("yt", ""))
        assertNull("a part missing", MediaLink.pageOf("li", "123"))
        assertNull(MediaLink.pageOf("cu", "", null))
        assertNull("a type the website doesn't link", MediaLink.pageOf("zz", "whatever"))
    }

    @Test fun nothingCanBeSmuggledInFrontOfTheAddress() {
        assertNull(MediaLink.pageOf("bn", "evil.com/x?;song"))
        assertNull(MediaLink.pageOf("pt", "x", MediaEmbed(domain = "user@evil.com", short = "abc")))
        assertNull(MediaLink.pageOf("pt", "x", MediaEmbed(domain = "tube.example.org/../evil", short = "abc")))
        assertNull(MediaLink.pageOf("pt", "x", MediaEmbed(domain = "tube.example.org", short = "a/b")))
        assertNull(MediaLink.pageOf("fi", "https://user@evil.com/v.mp4"))
    }

    @Test fun aPlaylistItemKeepsWhatItsLinkNeeds() {
        val cu = PlaylistItem.from(JSONObject(
            """{"uid":1,"queueby":"amy","media":{"id":"x","title":"T","duration":"0:00","type":"cu","meta":{"embed":{"src":"https://player.example/e/1","tag":"iframe"}}}}"""
        ))
        assertEquals("https://player.example/e/1", cu.link)
        val yt = PlaylistItem.from(JSONObject(
            """{"uid":2,"queueby":"bob","media":{"id":"dQw4w9WgXcQ","title":"Song","duration":"03:33","type":"yt","meta":{"embed":{"src":"https://ignored.example"}}}}"""
        ))
        assertNull("only custom embeds and PeerTube keep their meta.embed", yt.embed)
        assertEquals("https://youtube.com/watch?v=dQw4w9WgXcQ", yt.link)
    }
}
