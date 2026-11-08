package com.cytube.mobile.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cytube.mobile.ui.defaultSyncAccuracy
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** A settings file that can't be read any more (spoilt on disk) is started
 *  afresh, with every setting back at its default, rather than throwing on
 *  every read — which, with the settings read as the app starts, would
 *  crash it on every launch. */
val Context.dataStore by preferencesDataStore(
    "cytube_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

/** Automatic / Native / Web, per the brief's compatibility toggle. */
enum class CompatMode { AUTOMATIC, NATIVE, WEB;
    companion object { fun parse(s: String?) = entries.firstOrNull { it.name == s } ?: AUTOMATIC }
}

/** System default / Light / Dark — drives CyTubeSettingsTheme, which is
 *  what the home and settings screens use (see MainActivity). SYSTEM is the
 *  default so a fresh install still just follows the phone's own setting.
 *  Prince, in the home search bar, has his own switch ([Settings.cot]). */
enum class ThemeMode { SYSTEM, LIGHT, DARK;
    companion object {
        fun parse(s: String?) = when (s) {
            // Saved while cot was a theme rather than a switch: "COT" (dark
            // with Prince), then for a while "COT_B" and "COT_W".
            "COT", "COT_B" -> DARK
            "COT_W" -> LIGHT
            else -> entries.firstOrNull { it.name == s } ?: SYSTEM
        }

        /** True when [s] is one of those old cot themes: cot is still on. */
        fun wasCot(s: String?) = s == "COT" || s == "COT_B" || s == "COT_W"
    }
}

data class Settings(
    val syncEnabled: Boolean = true,
    val syncAccuracy: Double = 2.0,
    val compatMode: CompatMode = CompatMode.AUTOMATIC,
    val showEmotes: Boolean = true,
    /** Off by default: leaving the app only floats the video in a window
     *  for people who turn this on in Settings. */
    val pipEnabled: Boolean = false,
    /** Someone's messages in a row go under their name once, like Discord,
     *  instead of each with its own name and time. Phone only; off by
     *  default. */
    val groupChat: Boolean = false,
    /** A channel's own background picture behind the top of its screen
     *  (see ChannelBackdrop). Phone only; on by default. */
    val channelBackgrounds: Boolean = true,
    /** The Niconico overlay flies only each message's emotes (messages
     *  without any don't fly). Off by default. */
    val nicoEmotesOnly: Boolean = false,
    /** Display name used to join chat as a guest, set on the Account
     *  screen. Blank means "not chosen": each connection then uses a fresh
     *  random GuestNNNN name, which isn't saved. */
    val guestName: String = "",
    /** Manual override for CyTubeSettingsTheme (home, settings and account
     *  screens, phone only). SYSTEM follows the phone's own light/dark
     *  setting. */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** "cot", under the themes: Prince lives in the home search bar (see
     *  Prince), on whichever theme is picked. Phone only; off by default. */
    val cot: Boolean = false,
    /** How fast Nico chat's comments fly across the video: 0.5 (half as
     *  fast) to 2 (twice as fast); 1 is the website's own speed. */
    val nicoSpeed: Float = 1f,
    /** How dark lights down makes everything round the video: 0.5 to 1
     *  (black). Phone only. */
    val lightsDownDim: Float = DEFAULT_LIGHTS_DOWN_DIM,
    /** The chat box's up arrow bringing back the last few messages you
     *  sent (see SentChat). Phone only; on by default. */
    val messageRecall: Boolean = true
)

/** Lights down's darkness as it always was, until changed in Settings. */
const val DEFAULT_LIGHTS_DOWN_DIM = 0.85f
val NICO_SPEED_RANGE = 0.5f..2f
val LIGHTS_DOWN_DIM_RANGE = 0.5f..1f

class SettingsStore(private val context: Context) {

    /**
     * Everything saved, as it changes. A disk that can't be read reads as
     * nothing saved (every setting at its default) rather than throwing at
     * whatever is collecting: never a crash over a settings file.
     */
    private val prefs: Flow<Preferences> = context.dataStore.data.catch { e ->
        if (e !is IOException) throw e
        Log.w(TAG, "settings couldn't be read; using the defaults", e)
        emit(emptyPreferences())
    }

    /** Worked out once: whether this is a TV doesn't change, and finding out
     *  asks the system, which used to happen on every write to the store. */
    private val defaultAccuracy by lazy { defaultSyncAccuracy(context) }

    /**
     * Saves [change]; false when it couldn't be (a full disk, say). Nothing
     * that saves a setting needs to handle that itself: a setting that wasn't
     * kept just reads back as it was.
     */
    private suspend fun save(change: (MutablePreferences) -> Unit): Boolean = try {
        context.dataStore.edit { change(it) }
        true
    } catch (e: IOException) {
        Log.w(TAG, "settings couldn't be saved", e)
        false
    }

    // Each only when its own value changes, not on every write to the store
    // (a channel's recent emotes, its background, a visit...).
    val settings: Flow<Settings> = prefs.map { p ->
        Settings(
            syncEnabled = p[SYNC] ?: true,
            syncAccuracy = p[ACCURACY] ?: defaultAccuracy,
            compatMode = CompatMode.parse(p[COMPAT]),
            showEmotes = p[EMOTES] ?: true,
            pipEnabled = p[PIP] ?: false,
            groupChat = p[GROUP_CHAT] ?: false,
            channelBackgrounds = p[CHANNEL_BACKGROUNDS] ?: true,
            nicoEmotesOnly = p[NICO_EMOTES_ONLY] ?: false,
            guestName = p[GUEST_NAME] ?: "",
            themeMode = ThemeMode.parse(p[THEME_MODE]),
            cot = p[COT] ?: ThemeMode.wasCot(p[THEME_MODE]),
            nicoSpeed = (p[NICO_SPEED] ?: 1f).coerceIn(NICO_SPEED_RANGE),
            lightsDownDim = (p[LIGHTS_DOWN_DIM] ?: DEFAULT_LIGHTS_DOWN_DIM).coerceIn(LIGHTS_DOWN_DIM_RANGE),
            messageRecall = p[MESSAGE_RECALL] ?: true
        )
    }.distinctUntilChanged()

    suspend fun setSync(v: Boolean) = save { it[SYNC] = v }
    suspend fun setAccuracy(v: Double) = save { it[ACCURACY] = v }
    suspend fun setCompat(v: CompatMode) = save { it[COMPAT] = v.name }
    suspend fun setEmotes(v: Boolean) = save { it[EMOTES] = v }
    suspend fun setPip(v: Boolean) = save { it[PIP] = v }
    suspend fun setGroupChat(v: Boolean) = save { it[GROUP_CHAT] = v }
    suspend fun setChannelBackgrounds(v: Boolean) = save { it[CHANNEL_BACKGROUNDS] = v }
    suspend fun setNicoEmotesOnly(v: Boolean) = save { it[NICO_EMOTES_ONLY] = v }
    suspend fun setGuestName(v: String) = save { it[GUEST_NAME] = guestNameToSave(v) }
    suspend fun setThemeMode(v: ThemeMode) = save { p ->
        // Replacing an old cot theme: Prince stays, on the cot switch.
        if (p[COT] == null && ThemeMode.wasCot(p[THEME_MODE])) p[COT] = true
        p[THEME_MODE] = v.name
    }
    suspend fun setCot(v: Boolean) = save { it[COT] = v }
    suspend fun setNicoSpeed(v: Float) = save { it[NICO_SPEED] = v.coerceIn(NICO_SPEED_RANGE) }
    suspend fun setLightsDownDim(v: Float) = save { it[LIGHTS_DOWN_DIM] = v.coerceIn(LIGHTS_DOWN_DIM_RANGE) }
    suspend fun setMessageRecall(v: Boolean) = save { it[MESSAGE_RECALL] = v }

    // ---- per-channel state ----

    val favourites: Flow<List<String>> =
        prefs.map { (it[FAVOURITES] ?: emptySet()).sorted() }.distinctUntilChanged()

    val recents: Flow<List<String>> =
        prefs.map { p ->
            (p[RECENTS] ?: "").split('\n').filter { it.isNotBlank() }
        }.distinctUntilChanged()

    /** False when it couldn't be saved, and so didn't change. */
    suspend fun toggleFavourite(channel: String) = save { p ->
        val cur = (p[FAVOURITES] ?: emptySet()).toMutableSet()
        if (!cur.add(channel)) cur.remove(channel)
        p[FAVOURITES] = cur
    }

    /** Waterfall: joining a channel bumps it to the front, and the list is
     *  capped at [MAX_RECENTS] — the oldest entry falls off the end rather
     *  than the list growing without bound. */
    suspend fun noteVisit(channel: String) = save { p ->
        val cur = (p[RECENTS] ?: "").split('\n').filter { it.isNotBlank() && it != channel }
        p[RECENTS] = (listOf(channel) + cur).take(MAX_RECENTS).joinToString("\n")
    }

    suspend fun clearRecents() = save { p -> p[RECENTS] = "" }

    // ---- ignored users ----

    /** People whose chat and private messages are hidden, as on the website:
     *  only on this device, nobody else is told, and one list for every
     *  channel. Kept as first written; CyTube names match ignoring case, so
     *  compare with [isIgnoredName]. */
    val ignoredUsers: Flow<Set<String>> =
        prefs.map { it[IGNORED] ?: emptySet() }.distinctUntilChanged()

    suspend fun setIgnored(name: String, ignored: Boolean) = save { p ->
        val rest = (p[IGNORED] ?: emptySet()).filterNot { it.equals(name, ignoreCase = true) }.toSet()
        p[IGNORED] = if (ignored && name.isNotBlank()) rest + name.trim() else rest
    }

    fun channelCompat(channel: String): Flow<CompatMode?> =
        prefs.map { p -> p[compatKey(channel)]?.let { CompatMode.parse(it) } }.distinctUntilChanged()

    suspend fun setChannelCompat(channel: String, v: CompatMode?) = save { p ->
        if (v == null) p.remove(compatKey(channel)) else p[compatKey(channel)] = v.name
    }

    /** The picture last shown behind this channel's top bar (see
     *  ChannelHeader), as ChannelBackground.encode() text: shown the moment
     *  the channel opens, before the channel has sent anything. Null when
     *  there's none yet. */
    fun channelBackdrop(channel: String): Flow<String?> =
        prefs.map { p -> p[backdropKey(channel)] }.distinctUntilChanged()

    suspend fun setChannelBackdrop(channel: String, v: String?) = save { p ->
        if (v == null) p.remove(backdropKey(channel)) else p[backdropKey(channel)] = v
    }

    private companion object {
        const val TAG = "CyTubeSettings"
        const val MAX_RECENTS = 4

        val SYNC = booleanPreferencesKey("sync_enabled")
        val ACCURACY = doublePreferencesKey("sync_accuracy")
        val COMPAT = stringPreferencesKey("compat_mode")
        val EMOTES = booleanPreferencesKey("show_emotes")
        val PIP = booleanPreferencesKey("pip_enabled")
        val GROUP_CHAT = booleanPreferencesKey("group_chat")
        val CHANNEL_BACKGROUNDS = booleanPreferencesKey("channel_backgrounds")
        val NICO_EMOTES_ONLY = booleanPreferencesKey("nico_emotes_only")
        val GUEST_NAME = stringPreferencesKey("guest_name")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val COT = booleanPreferencesKey("cot")
        val NICO_SPEED = floatPreferencesKey("nico_speed")
        val LIGHTS_DOWN_DIM = floatPreferencesKey("lights_down_dim")
        val MESSAGE_RECALL = booleanPreferencesKey("message_recall")
        val IGNORED = stringSetPreferencesKey("ignored_users")
        val FAVOURITES = stringSetPreferencesKey("favourites")
        val RECENTS = stringPreferencesKey("recents")

        fun compatKey(channel: String) = stringPreferencesKey("compat:$channel")
        fun backdropKey(channel: String) = stringPreferencesKey("backdrop:$channel")
    }
}

/** The guest name as it's kept: no spaces at either end, at most 20
 *  characters. The Account screen compares with this to save only a real
 *  change. */
fun guestNameToSave(name: String): String = name.trim().take(20)

/** True when [name] is in [ignoredLower] (names already lowercased), the
 *  way CyTube matches names: ignoring case. */
fun isIgnoredName(name: String, ignoredLower: Set<String>): Boolean =
    ignoredLower.isNotEmpty() && name.isNotBlank() && name.lowercase() in ignoredLower
