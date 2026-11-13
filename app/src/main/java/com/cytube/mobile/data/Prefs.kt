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
import kotlin.math.roundToInt
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
    /** The opposite: the Niconico overlay flies only each message's text,
     *  its emotes left out (a message that's only emotes doesn't fly). Off
     *  by default, and never on with [nicoEmotesOnly] (see
     *  nicoTextOnlyOf), as in CyTube Plus. */
    val nicoTextOnly: Boolean = false,
    /** How solid the Niconico overlay's emotes are: 0.1 (barely there) to
     *  1 (solid, the default). Its text is always solid. */
    val nicoEmoteOpacity: Float = 1f,
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
    /** The purple guy, in the bottom right corner of every page but the
     *  channel page (see PurpleGuy): Developer mode's switch. Phone only; off by
     *  default. */
    val purpleGuy: Boolean = false,
    /** Developer mode found: the version at the bottom of Settings tapped three
     *  times (see DevMode). From then on the purple guy's switch is there
     *  instead, as Developer mode. Phone only. */
    val devMode: Boolean = false,
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
/** 10% to 100%, as CyTube Plus offers it. */
val NICO_EMOTE_OPACITY_RANGE = 0.1f..1f

/** Whether Developer mode's been found, from what's saved: or, from before there
 *  was a Developer mode, the purple guy on (so his switch is there to turn him
 *  off). */
internal fun devModeOf(found: Boolean?, purpleGuy: Boolean?): Boolean = found ?: (purpleGuy == true)

/** Whether the Niconico overlay is text only, from what's saved: never
 *  together with emotes only, which wins should both somehow be saved on
 *  (as in CyTube Plus, whose emotes only is checked first). */
internal fun nicoTextOnlyOf(textOnly: Boolean?, emotesOnly: Boolean?): Boolean =
    textOnly == true && emotesOnly != true

/** The Niconico overlay's emote opacity as kept: within range, on the
 *  slider's 5% steps (no 0.35000002 from a dragged slider). */
internal fun nicoEmoteOpacityOf(value: Float?): Float {
    val v = value?.takeIf { !it.isNaN() } ?: 1f
    return ((v.coerceIn(NICO_EMOTE_OPACITY_RANGE) * 20f).roundToInt() / 20f).coerceIn(NICO_EMOTE_OPACITY_RANGE)
}

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
            nicoTextOnly = nicoTextOnlyOf(p[NICO_TEXT_ONLY], p[NICO_EMOTES_ONLY]),
            nicoEmoteOpacity = nicoEmoteOpacityOf(p[NICO_EMOTE_OPACITY]),
            guestName = p[GUEST_NAME] ?: "",
            themeMode = ThemeMode.parse(p[THEME_MODE]),
            cot = p[COT] ?: ThemeMode.wasCot(p[THEME_MODE]),
            purpleGuy = p[PURPLE_GUY] ?: false,
            devMode = devModeOf(p[DEV_MODE], p[PURPLE_GUY]),
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
    // Emotes only and text only can't both be on: turning one on turns the
    // other off, as in CyTube Plus.
    suspend fun setNicoEmotesOnly(v: Boolean) = save {
        it[NICO_EMOTES_ONLY] = v
        if (v) it[NICO_TEXT_ONLY] = false
    }
    suspend fun setNicoTextOnly(v: Boolean) = save {
        it[NICO_TEXT_ONLY] = v
        if (v) it[NICO_EMOTES_ONLY] = false
    }
    suspend fun setNicoEmoteOpacity(v: Float) = save { it[NICO_EMOTE_OPACITY] = nicoEmoteOpacityOf(v) }
    suspend fun setGuestName(v: String) = save { it[GUEST_NAME] = guestNameToSave(v) }
    suspend fun setThemeMode(v: ThemeMode) = save { p ->
        // Replacing an old cot theme: Prince stays, on the cot switch.
        if (p[COT] == null && ThemeMode.wasCot(p[THEME_MODE])) p[COT] = true
        p[THEME_MODE] = v.name
    }
    suspend fun setCot(v: Boolean) = save { it[COT] = v }
    /** The purple guy on or off, by Developer mode's switch (which is how Dev
     *  Mode stays found, should he have been on before it was). */
    suspend fun setPurpleGuy(v: Boolean) = save {
        it[PURPLE_GUY] = v
        it[DEV_MODE] = true
    }
    /** Developer mode found: and with it, the purple guy on. */
    suspend fun findDevMode() = save {
        it[DEV_MODE] = true
        it[PURPLE_GUY] = true
    }
    suspend fun setNicoSpeed(v: Float) = save { it[NICO_SPEED] = v.coerceIn(NICO_SPEED_RANGE) }
    suspend fun setLightsDownDim(v: Float) = save { it[LIGHTS_DOWN_DIM] = v.coerceIn(LIGHTS_DOWN_DIM_RANGE) }
    suspend fun setMessageRecall(v: Boolean) = save { it[MESSAGE_RECALL] = v }

    // ---- per-channel state ----

    // Channel names canonical (see canonicalChannelName), as they're saved
    // now; an older save could have the same channel in two spellings.
    val favourites: Flow<List<String>> =
        prefs.map { favouritesOf(it[FAVOURITES]) }.distinctUntilChanged()

    val recents: Flow<List<String>> =
        prefs.map { recentsOf(it[RECENTS]) }.distinctUntilChanged()

    /** False when it couldn't be saved, and so didn't change. */
    suspend fun toggleFavourite(channel: String) = save { p ->
        p[FAVOURITES] = toggledFavourites(p[FAVOURITES].orEmpty(), channel)
    }

    /** Waterfall: joining a channel bumps it to the front, and the list is
     *  capped at [MAX_RECENTS] — the oldest entry falls off the end rather
     *  than the list growing without bound. */
    suspend fun noteVisit(channel: String) = save { p ->
        p[RECENTS] = recentsAfterVisit(p[RECENTS], channel, MAX_RECENTS)
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

    // ---- per channel, kept in a file of their own (see ChannelData) ----

    private val channels by lazy { ChannelData(context) }

    fun channelCompat(channel: String): Flow<CompatMode?> = channels.compat(channel)

    /** False when it couldn't be saved, and so didn't change. */
    suspend fun setChannelCompat(channel: String, v: CompatMode?) = channels.setCompat(channel, v)

    /** The picture last shown behind this channel's top bar (see
     *  ChannelHeader), as ChannelBackground.encode() text: shown the moment
     *  the channel opens, before the channel has sent anything. Null when
     *  there's none yet. */
    fun channelBackdrop(channel: String): Flow<String?> = channels.backdrop(channel)

    suspend fun setChannelBackdrop(channel: String, v: String?) = channels.setBackdrop(channel, v)

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
        val NICO_TEXT_ONLY = booleanPreferencesKey("nico_text_only")
        val NICO_EMOTE_OPACITY = floatPreferencesKey("nico_emote_opacity")
        val GUEST_NAME = stringPreferencesKey("guest_name")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val COT = booleanPreferencesKey("cot")
        val PURPLE_GUY = booleanPreferencesKey("purple_guy")
        val DEV_MODE = booleanPreferencesKey("dev_mode")
        val NICO_SPEED = floatPreferencesKey("nico_speed")
        val LIGHTS_DOWN_DIM = floatPreferencesKey("lights_down_dim")
        val MESSAGE_RECALL = booleanPreferencesKey("message_recall")
        val IGNORED = stringSetPreferencesKey("ignored_users")
        val FAVOURITES = stringSetPreferencesKey("favourites")
        val RECENTS = stringPreferencesKey("recents")
    }
}

/** The favourites as saved, as shown: each channel once, canonical, sorted. */
internal fun favouritesOf(saved: Set<String>?): List<String> =
    saved.orEmpty().map(::canonicalChannelName).distinct().sorted()

/** The favourites [saved] with [channel] added, or taken out when it's
 *  there in any spelling; every name canonical. */
internal fun toggledFavourites(saved: Set<String>, channel: String): Set<String> {
    val all = saved.mapTo(LinkedHashSet(), ::canonicalChannelName)
    val wanted = canonicalChannelName(channel)
    if (!all.remove(wanted)) all.add(wanted)
    return all
}

/** The recent channels as saved (a line each, newest first), as shown:
 *  each once, canonical. */
internal fun recentsOf(saved: String?): List<String> =
    saved.orEmpty().split('\n').filter { it.isNotBlank() }.map(::canonicalChannelName).distinct()

/** The recent channels after a visit to [channel]: it first, then the rest
 *  as they were, at most [max]. */
internal fun recentsAfterVisit(saved: String?, channel: String, max: Int): String =
    (listOf(canonicalChannelName(channel)) + recentsOf(saved)).distinct().take(max).joinToString("\n")

/** The guest name as it's kept: no spaces at either end, at most 20
 *  characters. The Account screen compares with this to save only a real
 *  change. */
fun guestNameToSave(name: String): String = name.trim().take(20)

/** True when [name] is in [ignoredLower] (names already lowercased), the
 *  way CyTube matches names: ignoring case. */
fun isIgnoredName(name: String, ignoredLower: Set<String>): Boolean =
    ignoredLower.isNotEmpty() && name.isNotBlank() && name.lowercase() in ignoredLower
