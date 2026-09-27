package com.cytube.mobile.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Links as people actually paste or share them, and what CyTube needs. */
class MediaLinkTest {

    private fun parsed(input: String): MediaLink.Parsed = MediaLink.parse(input).getOrThrow()
    private fun fails(input: String) = assertTrue("expected failure for $input", MediaLink.parse(input).isFailure)

    @Test fun youtubeWatch() =
        assertEquals(MediaLink.Parsed("yt", "dQw4w9WgXcQ"), parsed("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))

    @Test fun youtubeMobileWithExtraParams() =
        assertEquals(MediaLink.Parsed("yt", "dQw4w9WgXcQ"), parsed("https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=42s"))

    @Test fun youtubeMusic() =
        assertEquals(MediaLink.Parsed("yt", "dQw4w9WgXcQ"), parsed("https://music.youtube.com/watch?v=dQw4w9WgXcQ&feature=share"))

    @Test fun youtuBeWithTrackingParam() =
        assertEquals(MediaLink.Parsed("yt", "dQw4w9WgXcQ"), parsed("https://youtu.be/dQw4w9WgXcQ?si=AbCdEf123"))

    @Test fun youtubeShorts() =
        assertEquals(MediaLink.Parsed("yt", "dQw4w9WgXcQ"), parsed("https://youtube.com/shorts/dQw4w9WgXcQ?feature=share"))

    @Test fun youtubeLiveAndEmbedPaths() {
        assertEquals(MediaLink.Parsed("yt", "dQw4w9WgXcQ"), parsed("https://www.youtube.com/live/dQw4w9WgXcQ?si=x"))
        assertEquals(MediaLink.Parsed("yt", "dQw4w9WgXcQ"), parsed("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ"))
    }

    @Test fun youtubePlaylist() =
        assertEquals(MediaLink.Parsed("yp", "PL1234567890"), parsed("https://www.youtube.com/playlist?list=PL1234567890"))

    @Test fun youtubeChannelPageIsRejected() = fails("https://www.youtube.com/@somechannel")

    @Test fun linkInsideSharedText() =
        assertEquals(
            MediaLink.Parsed("yt", "dQw4w9WgXcQ"),
            parsed("Never Gonna Give You Up https://youtu.be/dQw4w9WgXcQ?si=zz via the app")
        )

    @Test fun linkWithoutSchemeOrWrappedInPunctuation() {
        assertEquals(MediaLink.Parsed("yt", "dQw4w9WgXcQ"), parsed("youtu.be/dQw4w9WgXcQ"))
        assertEquals(MediaLink.Parsed("yt", "dQw4w9WgXcQ"), parsed("(https://youtu.be/dQw4w9WgXcQ)."))
    }

    @Test fun shorthand() = assertEquals(MediaLink.Parsed("yt", "dQw4w9WgXcQ"), parsed("yt:dQw4w9WgXcQ"))

    @Test fun vimeo() {
        assertEquals(MediaLink.Parsed("vi", "123456"), parsed("https://vimeo.com/123456"))
        assertEquals(MediaLink.Parsed("vi", "123456"), parsed("https://vimeo.com/channels/staffpicks/123456"))
        assertEquals(MediaLink.Parsed("vi", "123456"), parsed("https://player.vimeo.com/video/123456"))
    }

    @Test fun dailymotion() {
        assertEquals(MediaLink.Parsed("dm", "x7tgad0"), parsed("https://www.dailymotion.com/video/x7tgad0"))
        assertEquals(MediaLink.Parsed("dm", "x7tgad0"), parsed("https://dai.ly/x7tgad0"))
    }

    @Test fun soundcloudMobileBecomesDesktopUrl() =
        assertEquals(
            MediaLink.Parsed("sc", "https://soundcloud.com/artist/track"),
            parsed("https://m.soundcloud.com/artist/track")
        )

    @Test fun twitch() {
        assertEquals(MediaLink.Parsed("tw", "somestreamer"), parsed("https://www.twitch.tv/somestreamer"))
        assertEquals(MediaLink.Parsed("tv", "v123456"), parsed("https://www.twitch.tv/videos/123456"))
        assertEquals(MediaLink.Parsed("tc", "FunnyClipName"), parsed("https://clips.twitch.tv/FunnyClipName"))
    }

    @Test fun googleDrive() {
        assertEquals(MediaLink.Parsed("gd", "1AbC_dEf-123"), parsed("https://drive.google.com/file/d/1AbC_dEf-123/view?usp=sharing"))
        assertEquals(MediaLink.Parsed("gd", "1AbC_dEf-123"), parsed("https://drive.google.com/open?id=1AbC_dEf-123"))
    }

    @Test fun streamable() = assertEquals(MediaLink.Parsed("sb", "abc12"), parsed("https://streamable.com/abc12"))

    @Test fun peertube() =
        assertEquals(
            MediaLink.Parsed("pt", "framatube.org;9c9de5e8-0a1e-484a-b099-e80766180a6d"),
            parsed("https://framatube.org/videos/watch/9c9de5e8-0a1e-484a-b099-e80766180a6d")
        )

    @Test fun streamsAndManifests() {
        assertEquals("hl", parsed("https://example.com/live/stream.m3u8").type)
        assertEquals("cm", parsed("https://example.com/manifest.json").type)
        assertEquals("rt", parsed("rtmp://example.com/live/key").type)
    }

    @Test fun rawFile() {
        val p = parsed("https://example.com/movies/film.mp4")
        assertEquals(MediaLink.Parsed("fi", "https://example.com/movies/film.mp4"), p)
        assertFalse(MediaLink.shouldFollowRedirects(p))
    }

    @Test fun shortLinkIsFollowed() {
        val p = parsed("https://t.co/AbC123xyz")
        assertEquals("fi", p.type)
        assertTrue(MediaLink.shouldFollowRedirects(p))
    }

    @Test fun recognisedLinkIsNotFollowed() =
        assertFalse(MediaLink.shouldFollowRedirects(parsed("https://youtu.be/dQw4w9WgXcQ")))

    @Test fun emptyInputFails() {
        fails("")
        fails("   ")
    }

    @Test fun aStrayPercentSignIsNotACrash() {
        // URLDecoder throws on an incomplete escape; parse must not.
        assertTrue(MediaLink.parse("https://youtube.com/watch?v=abc%").isFailure)
        assertTrue(MediaLink.parse("https://www.youtube.com/watch?v=%zz").isFailure)
    }
}
