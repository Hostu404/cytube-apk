package com.cytube.mobile.data

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The per-channel file (ChannelData), on real DataStore files. */
class ChannelDataTest {

    private lateinit var dir: File
    private val scopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("channel-data").toFile()
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        dir.deleteRecursively()
    }

    private fun store(name: String, migrations: List<DataMigration<Preferences>> = emptyList()): DataStore<Preferences> {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        return PreferenceDataStoreFactory.create(null, migrations, scope) { File(dir, "$name.preferences_pb") }
    }

    @Test
    fun `the settings file's channel entries move over, lowercased, and leave it`() = runBlocking {
        val settings = store("settings")
        settings.edit {
            it[stringPreferencesKey("compat:Foo")] = "WEB"
            it[stringPreferencesKey("backdrop:foo")] = "picture"
            it[stringPreferencesKey("recent_emotes_foo")] = "pog\nkek"
            it[booleanPreferencesKey("sync_enabled")] = false
            it[stringPreferencesKey("compat_mode")] = "NATIVE"
            it[stringPreferencesKey("recents")] = "foo"
        }
        val channels = ChannelData(store("channels", listOf(MovePerChannelSettings(settings))))

        assertEquals(CompatMode.WEB, channels.compat("foo").first())
        assertEquals(CompatMode.WEB, channels.compat("FOO").first())
        assertEquals("picture", channels.backdrop("Foo").first())
        assertEquals(listOf("pog", "kek"), channels.recentEmotes("foo"))
        // Gone from the settings file, whose own settings stay as they were.
        val left = settings.data.first().asMap().mapKeys { it.key.name }
        assertEquals(mapOf("sync_enabled" to false, "compat_mode" to "NATIVE", "recents" to "foo"), left)
    }

    @Test
    fun `with nothing to move, the settings file is left alone`() = runBlocking {
        val settings = store("settings")
        settings.edit { it[booleanPreferencesKey("sync_enabled")] = true }
        val move = MovePerChannelSettings(settings)
        assertFalse(move.shouldMigrate(preferencesOf()))
        val channels = ChannelData(store("channels", listOf(move)))
        assertNull(channels.compat("foo").first())
        assertEquals(setOf("sync_enabled"), settings.data.first().asMap().keys.map { it.name }.toSet())
    }

    @Test
    fun `a move that was interrupted doesn't overwrite what's been saved since`() = runBlocking {
        val settings = store("settings")
        settings.edit { it[stringPreferencesKey("compat:foo")] = "NATIVE" }
        val move = MovePerChannelSettings(settings)
        val already = preferencesOf(stringPreferencesKey("compat:foo") to "WEB")
        assertTrue(move.shouldMigrate(already))
        assertEquals("WEB", move.migrate(already)[stringPreferencesKey("compat:foo")])
    }

    @Test
    fun `of two spellings of one channel, the lowercase one's is kept`() {
        val mixedFirst = linkedMapOf("compat:Foo" to "NATIVE", "compat:foo" to "WEB")
        val lowerFirst = linkedMapOf("compat:foo" to "WEB", "compat:FOO" to "NATIVE")
        assertEquals(mapOf("compat:foo" to "WEB"), ChannelKeys.fromSettings(mixedFirst))
        assertEquals(mapOf("compat:foo" to "WEB"), ChannelKeys.fromSettings(lowerFirst))
        // Only mixed spellings: one of them, under the lowercase name.
        assertEquals(setOf("backdrop:bar"), ChannelKeys.fromSettings(mapOf("backdrop:Bar" to "a", "backdrop:BAR" to "b")).keys)
    }

    @Test
    fun `only per-channel keys of the settings file are moved`() {
        assertTrue(ChannelKeys.isPerChannel("compat:foo"))
        assertTrue(ChannelKeys.isPerChannel("backdrop:foo"))
        assertTrue(ChannelKeys.isPerChannel("recent_emotes_foo"))
        assertFalse(ChannelKeys.isPerChannel("compat_mode"))
        assertFalse(ChannelKeys.isPerChannel("compat:"))
        assertFalse(ChannelKeys.isPerChannel("recents"))
        assertFalse(ChannelKeys.isPerChannel("alerts_found"))
    }

    @Test
    fun `any spelling of a channel finds what's kept for it`() = runBlocking {
        val channels = ChannelData(store("channels"))
        channels.setCompat("Foo", CompatMode.WEB)
        channels.setBackdrop("FOO", "picture")
        channels.setRecentEmotes("fOo", listOf("pog"))
        assertEquals(CompatMode.WEB, channels.compat("foo").first())
        assertEquals("picture", channels.backdrop("foo").first())
        assertEquals(listOf("pog"), channels.recentEmotes("Foo"))
        channels.setCompat("FOO", null)
        assertNull(channels.compat("foo").first())
    }

    @Test
    fun `only the channels saved to most recently keep their picture and emotes`() = runBlocking {
        val channels = ChannelData(store("channels"))
        channels.setCompat("c0", CompatMode.WEB)
        channels.setBackdrop("c0", "picture")
        for (i in 0..ChannelData.MAX_REMEMBERED_CHANNELS) channels.setRecentEmotes("c$i", listOf("e$i"))

        // c0 is the oldest of 101: let go of, except its chosen mode.
        assertEquals(emptyList<String>(), channels.recentEmotes("c0"))
        assertNull(channels.backdrop("c0").first())
        assertEquals(CompatMode.WEB, channels.compat("c0").first())
        assertEquals(listOf("e1"), channels.recentEmotes("c1"))
        assertEquals(listOf("e100"), channels.recentEmotes("c100"))
    }

    @Test
    fun `a channel saved to again goes back to the front`() {
        val (order, dropped) = ChannelKeys.touch("a\nb\nc", "C", max = 3)
        assertEquals("c\na\nb", order)
        assertEquals(emptyList<String>(), dropped)
        val (full, fell) = ChannelKeys.touch("a\nb\nc", "d", max = 3)
        assertEquals("d\na\nb", full)
        assertEquals(listOf("c"), fell)
    }

    @Test
    fun `favourites and recents keep each channel once, whatever the spelling`() {
        assertEquals("some_chan-1", canonicalChannelName("Some_Chan-1"))
        assertEquals(setOf("bar"), toggledFavourites(setOf("Foo", "bar"), "foo"))
        assertEquals(setOf("bar", "foo"), toggledFavourites(setOf("Bar"), "FOO"))
        assertEquals(listOf("bar", "foo"), favouritesOf(setOf("Foo", "foo", "Bar")))
        assertEquals(listOf("foo", "bar"), recentsOf("Foo\nfoo\n\nbar"))
        assertEquals("foo\nbar\nbaz", recentsAfterVisit("bar\nFoo\nbaz", "FOO", 4))
        assertEquals("e\na\nb\nc", recentsAfterVisit("a\nb\nc\nd", "E", 4))
    }

    // The home page's public list spells names as their links do; a starred
    // "American-Dad" is kept as "american-dad", and must show as starred.
    @Test
    fun aPublicChannelWithCapitalsShowsAsAFavourite() {
        val saved = favouritesOf(toggledFavourites(emptySet(), "American-Dad")).toSet()
        assertTrue(isFavouriteChannel("American-Dad", saved))
        assertTrue(isFavouriteChannel("american-dad", saved))
        assertFalse(isFavouriteChannel("Channel-Z", saved))
        // Starred again, it's no longer one, whichever way it's spelt.
        val unstarred = favouritesOf(toggledFavourites(saved, "American-Dad")).toSet()
        assertFalse(isFavouriteChannel("American-Dad", unstarred))
    }
}
