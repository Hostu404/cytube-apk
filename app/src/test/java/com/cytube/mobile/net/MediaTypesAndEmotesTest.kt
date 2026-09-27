package com.cytube.mobile.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTypesAndEmotesTest {

    @Test fun playerChoice() {
        assertEquals(MediaTypes.Player.NATIVE, MediaTypes.playerFor("fi", hasDirect = false))
        assertEquals(MediaTypes.Player.NATIVE, MediaTypes.playerFor("cm", hasDirect = true))
        assertEquals(MediaTypes.Player.NEWPIPE, MediaTypes.playerFor("yt", hasDirect = false))
        assertEquals(
            "live YouTube goes to YouTube's own player",
            MediaTypes.Player.EMBED,
            MediaTypes.playerFor("yt", hasDirect = false, embedPlayableSrc = "https://www.youtube.com/embed/x", isLive = true)
        )
        assertEquals(MediaTypes.Player.GDRIVE, MediaTypes.playerFor("gd", hasDirect = false))
        assertEquals(MediaTypes.Player.STREAMABLE, MediaTypes.playerFor("sb", hasDirect = false))
        assertEquals(MediaTypes.Player.PEERTUBE, MediaTypes.playerFor("pt", hasDirect = false))
        assertEquals(MediaTypes.Player.EMBED, MediaTypes.playerFor("vi", hasDirect = false, embedPlayableSrc = "https://player.vimeo.com/video/1"))
        assertEquals(MediaTypes.Player.WEB, MediaTypes.playerFor("tw", hasDirect = false))
    }

    @Test fun peertubeEmbedUrlRejectsSmuggledHosts() {
        assertEquals(
            "https://framatube.org/videos/embed/9c9de5e8-0a1e-484a-b099-e80766180a6d",
            MediaTypes.knownEmbedUrl("pt", "framatube.org;9c9de5e8-0a1e-484a-b099-e80766180a6d")
        )
        assertNull(MediaTypes.knownEmbedUrl("pt", "evil.example@framatube.org;abc"))
        assertNull(MediaTypes.knownEmbedUrl("pt", "framatube.org;abc/../x"))
        assertNull(MediaTypes.knownEmbedUrl("pt", "no-separator"))
    }

    @Test fun whatAPersonalPickCanPlay() {
        assertTrue(MediaTypes.canResolveIndependently("yt"))
        assertTrue(MediaTypes.canResolveIndependently("fi"))
        assertFalse("custom manifests need the server's meta", MediaTypes.canResolveIndependently("cm"))
    }

    private val kappa = Emote("Kappa", "https://e.example/kappa.png", "(^|\\s)Kappa(?!\\S)")

    @Test fun emotesAreSubstitutedByWholeWord() {
        val set = EmoteSet.from(listOf(kappa))
        val out = set.apply("hello Kappa world NotKappa")
        assertTrue(out.contains("""<img class="channel-emote" title="Kappa" src="https://e.example/kappa.png">"""))
        assertTrue("partial words are left alone", out.endsWith(" NotKappa"))
        assertTrue(set.mightMatch("hello Kappa"))
        assertFalse(set.mightMatch("hello NotKappa"))
    }

    @Test fun emoteNamesWithSpacesUseTheirPattern() {
        val spaced = Emote("big smile", "https://e.example/bs.png", "(^|\\s)big smile(?!\\S)")
        val set = EmoteSet.from(listOf(spaced))
        assertTrue(set.apply("so big smile ok").contains("title=\"big smile\""))
        assertFalse(set.mightMatch("big frown"))
    }

    @Test fun oneBadPatternDoesNotBreakTheRest() {
        val broken = Emote("bad one", "https://e.example/x.png", "(unclosed")
        val set = EmoteSet.from(listOf(broken, kappa))
        assertTrue(set.apply("Kappa").contains("title=\"Kappa\""))
    }

    @Test fun emoteUpdatesReplaceByName() {
        val set = EmoteSet.from(listOf(kappa))
        val renamed = set.withRenamed("Kappa", Emote("KappaPride", kappa.image, ""))
        assertEquals(listOf("KappaPride"), renamed.all.map { it.name })
        assertTrue(set.withRemoved("Kappa").isEmpty)
    }

    @Test fun noEmotesMeansTextUntouched() =
        assertEquals("plain <b>text</b>", EmoteSet.EMPTY.apply("plain <b>text</b>"))
}
