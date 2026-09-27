package com.cytube.mobile.net

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Server frames as CyTube sends them (shapes from its source), and what the app reads out of them. */
class FramesTest {

    private fun json(s: String) = JSONObject(s)

    @Test fun userFromUserlist() {
        val u = ChannelUser.from(json("""{"name":"alice","rank":2,"meta":{"afk":true,"muted":false},"profile":{"image":"","text":""}}"""))
        assertEquals(ChannelUser("alice", 2.0, afk = true), u)
    }

    @Test fun playlistItem() {
        val item = PlaylistItem.from(json(
            """{"uid":7,"temp":false,"queueby":"bob","media":{"id":"dQw4w9WgXcQ","title":"Song","seconds":213,"duration":"03:33","type":"yt"}}"""
        ))
        assertEquals(PlaylistItem(7, "Song", "03:33", "yt", "dQw4w9WgXcQ", "bob"), item)
    }

    @Test fun mediaFrameSortsSourcesBestFirstAndFlvLast() {
        val m = MediaFrame.from(json("""
            {"id":"https://x/manifest.json","title":"Film","seconds":6000,"duration":"1:40:00","type":"cm",
             "currentTime":12.5,"paused":false,
             "meta":{"direct":{
                "720":[{"link":"https://x/720.mp4","contentType":"video/mp4"}],
                "1080":[{"link":"https://x/1080.flv","contentType":"video/flv"},{"link":"https://x/1080.mp4","contentType":"video/mp4"}],
                "480":[{"link":"https://x/480.mp4","contentType":"video/mp4"}]}}}
        """))
        assertEquals(
            listOf("https://x/1080.mp4", "https://x/720.mp4", "https://x/480.mp4", "https://x/1080.flv"),
            m.direct.map { it.link }
        )
        assertEquals("https://x/1080.mp4", m.bestSource?.link)
        assertEquals(12.5, m.currentTime, 0.0)
        assertFalse(m.isLivestream)
    }

    @Test fun zeroLengthIsLive() =
        assertTrue(MediaFrame.from(json("""{"id":"x","type":"hl","seconds":0}""")).isLivestream)

    @Test fun personalPickFrameParsesDuration() {
        val item = PlaylistItem(1, "T", "1:02:03", "yt", "abc", "q")
        assertEquals(3723, MediaFrame.fromPlaylistItem(item).seconds)
        assertEquals(0, MediaFrame.fromPlaylistItem(item.copy(duration = "??:??")).seconds)
    }

    @Test fun timeUpdate() =
        assertEquals(TimeUpdate(42.0, true), TimeUpdate.from(json("""{"currentTime":42,"paused":true}""")))

    @Test fun chatAndPrivateMessages() {
        val chat = ChatMessage.from(json("""{"username":"carol","msg":"hi &amp; bye","time":1000,"meta":{"addClass":"greentext"}}"""))
        assertEquals("carol", chat.username)
        assertEquals("greentext", chat.addClass)
        assertFalse(chat.isPm)
        assertNull(chat.to)

        val pm = ChatMessage.from(json("""{"username":"me","to":"dave","msg":"psst","time":1}"""), isPm = true)
        assertTrue(pm.isPm)
        assertEquals("dave", pm.to)

        assertTrue(ChatMessage.from(json("""{"username":"[server]","msg":"x"}""")).isServerMessage)
    }

    @Test fun pollTextIsDecoded() {
        val poll = Poll.from(json("""
            {"initiator":"eve","title":"Who&#39;s best? &#40;vote&#41;",
             "options":["Tom &amp; Jerry","<a href=\"https://x\">a link</a>"],"counts":[1,2],"timestamp":5}
        """))
        assertEquals("Who's best? (vote)", poll.title)
        assertEquals(listOf("Tom & Jerry", "a link"), poll.options)
        assertEquals(3, poll.totalVotes)
        assertFalse(poll.isObscured)
        assertFalse(poll.hiddenFromOthers)
    }

    @Test fun hiddenPollCounts() {
        assertEquals(listOf(-1, -1), Poll.parseCounts(JSONArray("""["?","?"]"""), 2))
        assertEquals(listOf(3, 0), Poll.parseCounts(JSONArray("""["3?","0?"]"""), 2))
        assertTrue(Poll.countsHiddenFromOthers(JSONArray("""["3?","0?"]""")))
        assertFalse(Poll.countsHiddenFromOthers(JSONArray("""["?","?"]""")))
        assertFalse(Poll.countsHiddenFromOthers(JSONArray("""[3,0]""")))
    }

    @Test fun pollCountsAlwaysMatchTheOptions() {
        assertEquals(listOf(4, -1, -1), Poll.parseCounts(JSONArray("[4]"), 3))
        assertEquals(listOf(4), Poll.parseCounts(JSONArray("[4,5,6]"), 1))
        assertEquals(listOf(-1, -1), Poll.parseCounts(null, 2))
    }

    @Test fun imageTagsInPollTitles() {
        val title = "Best pic? [img](https://i.example/a.png) or [IMG](https://i.example/b.gif)"
        assertEquals(listOf("https://i.example/a.png", "https://i.example/b.gif"), imageTagUrls(title))
        assertEquals("Best pic? or", removeImageTags(title))
        assertEquals("Best pic? https://i.example/a.png or https://i.example/b.gif", unwrapImageTags(title))
    }

    @Test fun playlistPermissions() {
        val p = Permissions(json("""{"playlistadd":1.5,"oplaylistadd":0}"""))
        assertFalse(p.allowsPlaylistAction("playlistadd", 1.0, playlistOpen = false))
        assertTrue(p.allowsPlaylistAction("playlistadd", 1.0, playlistOpen = true))
        assertTrue(p.allowsPlaylistAction("playlistadd", 2.0, playlistOpen = false))
        assertFalse("unknown permission is never allowed", p.allows("nosuchthing", 5.0))
    }

    @Test fun playlistPositions() {
        assertEquals(PlaylistPosition.START, PlaylistPosition.parse("prepend"))
        assertEquals(PlaylistPosition.END, PlaylistPosition.parse("append"))
        assertEquals(PlaylistPosition.END, PlaylistPosition.parse(null))
        assertEquals(12, PlaylistPosition.parse(12))
        assertEquals(12, PlaylistPosition.parse("12"))
    }

    @Test fun mediaUrlsAreResolvedOrRejected() {
        assertEquals("https://cdn.example/e.gif", resolveMediaUrl("//cdn.example/e.gif"))
        assertEquals("https://cytu.be/emotes/e.gif", resolveMediaUrl("/emotes/e.gif"))
        assertEquals("data:image/png;base64,AA", resolveMediaUrl("data:image/png;base64,AA"))
        assertEquals("", resolveMediaUrl("javascript:alert(1)"))
        assertEquals("", resolveMediaUrl("file:///data/data/x"))
        assertEquals("", resolveMediaUrl("content://evil/provider"))
    }
}
