package com.cytube.mobile.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * The file [ChannelData] keeps its channels in, apart from the settings
 * (cytube_settings). Its first opening moves what the settings file had for
 * channels over (see [MovePerChannelSettings]).
 */
internal val Context.channelDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "cytube_channels",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    produceMigrations = { context -> listOf(MovePerChannelSettings(context.dataStore)) }
)

/**
 * What the app keeps about each channel: the compatibility mode chosen for
 * it, the picture last shown behind it (see ChannelHeader), and the emotes
 * used there lately (see RecentEmotes).
 *
 * In a file of its own. Every save to a preferences file writes the whole
 * file again, and every screen watching it is woken; with these in the
 * settings file, each message sent with an emote in it did that to the
 * settings. Here they touch neither the settings nor anything watching them.
 *
 * And kept small: only the [MAX_REMEMBERED_CHANNELS] channels most recently
 * saved to keep their picture and recent emotes, which before were kept for
 * every channel ever visited. A compatibility mode, chosen by hand, is kept
 * whatever. Channel names are canonical (see canonicalChannelName).
 *
 * A file that can't be read or written (full, spoilt) only means none of
 * this is kept this time: never a throw at a caller.
 */
class ChannelData(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.applicationContext.channelDataStore)

    private val data: Flow<Preferences> = store.data.catch { e ->
        if (e !is IOException) throw e
        Log.w(TAG, "channel data couldn't be read", e)
        emit(emptyPreferences())
    }

    fun compat(channel: String): Flow<CompatMode?> =
        data.map { p -> p[ChannelKeys.compat(channel)]?.let { CompatMode.parse(it) } }.distinctUntilChanged()

    /** False when it couldn't be saved. */
    suspend fun setCompat(channel: String, mode: CompatMode?): Boolean = save { p ->
        val key = ChannelKeys.compat(channel)
        if (mode == null) p.remove(key) else p[key] = mode.name
    }

    /** As ChannelBackground.encode() text; null for none yet. */
    fun backdrop(channel: String): Flow<String?> =
        data.map { p -> p[ChannelKeys.backdrop(channel)] }.distinctUntilChanged()

    suspend fun setBackdrop(channel: String, encoded: String?): Boolean = save { p ->
        val key = ChannelKeys.backdrop(channel)
        if (encoded == null) p.remove(key) else p[key] = encoded
        remember(p, channel)
    }

    /** Newest first. */
    suspend fun recentEmotes(channel: String): List<String> =
        data.first()[ChannelKeys.recentEmotes(channel)]?.split(EMOTE_SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty()

    suspend fun setRecentEmotes(channel: String, names: List<String>): Boolean = save { p ->
        p[ChannelKeys.recentEmotes(channel)] = names.joinToString(EMOTE_SEPARATOR)
        remember(p, channel)
    }

    /** [channel] to the front of the remembered ones; any pushed off the end
     *  lose their picture and recent emotes. */
    private fun remember(p: MutablePreferences, channel: String) {
        val (order, dropped) = ChannelKeys.touch(p[ChannelKeys.REMEMBERED], channel, MAX_REMEMBERED_CHANNELS)
        p[ChannelKeys.REMEMBERED] = order
        for (gone in dropped) {
            p.remove(ChannelKeys.backdrop(gone))
            p.remove(ChannelKeys.recentEmotes(gone))
        }
    }

    private suspend fun save(change: (MutablePreferences) -> Unit): Boolean = try {
        store.edit { change(it) }
        true
    } catch (e: IOException) {
        Log.w(TAG, "channel data couldn't be saved", e)
        false
    }

    companion object {
        /** Channels whose picture and recent emotes are kept. */
        const val MAX_REMEMBERED_CHANNELS = 100
        private const val EMOTE_SEPARATOR = "\n"
        private const val TAG = "CyTubeChannelData"
    }
}

/** The keys [ChannelData] keeps, and the rules for them, apart for testing. */
internal object ChannelKeys {
    // The same names the settings file used for these, so moving them over
    // is a copy (see MovePerChannelSettings).
    private const val COMPAT = "compat:"
    private const val BACKDROP = "backdrop:"
    private const val RECENT_EMOTES = "recent_emotes_"
    private val PREFIXES = listOf(COMPAT, BACKDROP, RECENT_EMOTES)

    /** The channels whose picture or emotes were saved, newest first, a
     *  line each: which to let go of first. */
    val REMEMBERED = stringPreferencesKey("remembered_channels")

    fun compat(channel: String) = stringPreferencesKey(COMPAT + canonicalChannelName(channel))
    fun backdrop(channel: String) = stringPreferencesKey(BACKDROP + canonicalChannelName(channel))
    fun recentEmotes(channel: String) = stringPreferencesKey(RECENT_EMOTES + canonicalChannelName(channel))

    /** Whether a settings-file key named [name] is one of these. */
    fun isPerChannel(name: String): Boolean = PREFIXES.any { name.startsWith(it) && name.length > it.length }

    /**
     * [order] (see [REMEMBERED]) with [channel] at its front, at most [max]
     * long; and the channels that fell off its end.
     */
    fun touch(order: String?, channel: String, max: Int): Pair<String, List<String>> {
        val first = canonicalChannelName(channel)
        val all = listOf(first) + order.orEmpty().split('\n').filter { it.isNotBlank() && it != first }
        return all.take(max).joinToString("\n") to all.drop(max)
    }

    /**
     * The per-channel entries of the settings file ([entries], by key name),
     * under the names they have here: the channel name canonical. Where two
     * spellings of one channel had one each (it was opened as "Foo" once,
     * from a link, and as "foo" from the channel list), the canonical
     * spelling's is kept: what the channel list, and so most visits, used.
     */
    fun fromSettings(entries: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val canonicalSpelling = HashSet<String>()
        for ((name, value) in entries) {
            val prefix = PREFIXES.firstOrNull { name.startsWith(it) } ?: continue
            val channel = name.removePrefix(prefix)
            if (channel.isEmpty()) continue
            val key = prefix + canonicalChannelName(channel)
            val isCanonical = channel == canonicalChannelName(channel)
            if (key !in out || (isCanonical && key !in canonicalSpelling)) {
                out[key] = value
                if (isCanonical) canonicalSpelling += key
            }
        }
        return out
    }

    /** The channels in [keys] (names as [fromSettings] gives them) that
     *  have a picture or recent emotes, each once. */
    fun channelsWithMemories(keys: Collection<String>): List<String> =
        keys.mapNotNull { name ->
            when {
                name.startsWith(BACKDROP) -> name.removePrefix(BACKDROP)
                name.startsWith(RECENT_EMOTES) -> name.removePrefix(RECENT_EMOTES)
                else -> null
            }
        }.distinct()
}

/**
 * Moves what the settings file kept per channel (compatibility modes,
 * pictures, recent emotes) into the channel file, channel names made
 * canonical on the way, then takes them out of the settings file. Run by
 * DataStore the first time the channel file is opened in each run; there's
 * nothing to move after the first.
 */
internal class MovePerChannelSettings(private val settings: DataStore<Preferences>) : DataMigration<Preferences> {

    override suspend fun shouldMigrate(currentData: Preferences): Boolean = settingsEntries().isNotEmpty()

    override suspend fun migrate(currentData: Preferences): Preferences {
        val moved = ChannelKeys.fromSettings(settingsEntries())
        val out = currentData.toMutablePreferences()
        for ((name, value) in moved) {
            val key = stringPreferencesKey(name)
            // One already here is newer: kept (in case an earlier move was
            // interrupted before the settings file was tidied up).
            if (out[key] == null) out[key] = value
        }
        // No telling which were the most recent: any of them, up to the cap.
        val channels = ChannelKeys.channelsWithMemories(moved.keys)
        var order = out[ChannelKeys.REMEMBERED]
        for (channel in channels.asReversed()) {
            val (next, dropped) = ChannelKeys.touch(order, channel, ChannelData.MAX_REMEMBERED_CHANNELS)
            order = next
            for (gone in dropped) {
                out.remove(ChannelKeys.backdrop(gone))
                out.remove(ChannelKeys.recentEmotes(gone))
            }
        }
        if (order != null) out[ChannelKeys.REMEMBERED] = order
        return out.toPreferences()
    }

    override suspend fun cleanUp() {
        settings.edit { p ->
            p.asMap().keys.filter { ChannelKeys.isPerChannel(it.name) }.forEach { p.remove(it) }
        }
    }

    private suspend fun settingsEntries(): Map<String, String> =
        settings.data.first().asMap().entries
            .filter { (key, value) -> ChannelKeys.isPerChannel(key.name) && value is String }
            .associate { (key, value) -> key.name to value as String }
}
