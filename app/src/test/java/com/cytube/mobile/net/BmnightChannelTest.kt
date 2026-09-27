package com.cytube.mobile.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BmnightChannelTest {

    @Test
    fun testBmnightMediaFrameParsing() {
        val json = JSONObject("""{
            "id": "cu:NqMgf/nH3Uo+jXBwsRBO+oLWXUDsJozwk2yvw9YlvEo=",
            "title": "Bad Movie Night",
            "seconds": 0,
            "duration": "00:00",
            "type": "cu",
            "currentTime": 0,
            "paused": false,
            "meta": {
                "embed": {
                    "src": "https://stream.zzzchan.xyz/"
                }
            }
        }""")

        val frame = MediaFrame.from(json)
        assertEquals("cu", frame.type)
        assertEquals("Bad Movie Night", frame.title)
        assertEquals("https://stream.zzzchan.xyz/", frame.embedPlayableSrc)

        val player = MediaTypes.playerFor(frame.type, frame.hasDirect, frame.embedPlayableSrc, frame.isLivestream)
        assertEquals(MediaTypes.Player.EMBED, player)
    }

    @Test
    fun testBmnightEmotesParsing() {
        val emoteJson = JSONObject("""{
            "name": "IHateCougars",
            "image": "https://bmnight.neocities.org/Emotes/jpg/fix2/l_1302.jpg",
            "source": "(^|\\s)IHateCougars(?!\\S)"
        }""")

        val emote = Emote.from(emoteJson)
        assertEquals("IHateCougars", emote.name)
        val emoteSet = EmoteSet.from(listOf(emote))
        assertNotNull(emoteSet)

        val transformed = emoteSet.apply("Hello IHateCougars world")
        assert(transformed.contains("channel-emote"))
    }
}
