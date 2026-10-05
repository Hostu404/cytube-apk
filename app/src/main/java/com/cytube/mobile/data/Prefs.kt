package com.cytube.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cytube.mobile.ui.defaultSyncAccuracy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore("cytube_settings")

/** Automatic / Native / Web, per the brief's compatibility toggle. */
enum class CompatMode { AUTOMATIC, NATIVE, WEB;
    companion object { fun parse(s: String?) = entries.firstOrNull { it.name == s } ?: AUTOMATIC }
}

/** System default / Light / Dark / Cot — drives CyTubeSettingsTheme, which
 *  is what the home and settings screens use (see MainActivity). SYSTEM is
 *  the default so a fresh install still just follows the phone's own
 *  setting. COT is Dark with a cat in the home search bar (CotCat). */
enum class ThemeMode { SYSTEM, LIGHT, DARK, COT;
    companion object { fun parse(s: String?) = entries.firstOrNull { it.name == s } ?: SYSTEM }
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
    /** Display name used to join chat as a guest, set on the Account
     *  screen. Blank means "not chosen": each connection then uses a fresh
     *  random GuestNNNN name, which isn't saved. */
    val guestName: String = "",
    /** Manual override for CyTubeSettingsTheme (home, settings and account
     *  screens, phone only). SYSTEM follows the phone's own light/dark
     *  setting. */
    val themeMode: ThemeMode = ThemeMode.SYSTEM
)

class SettingsStore(private val context: Context) {

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            syncEnabled = p[SYNC] ?: true,
            syncAccuracy = p[ACCURACY] ?: defaultSyncAccuracy(context),
            compatMode = CompatMode.parse(p[COMPAT]),
            showEmotes = p[EMOTES] ?: true,
            pipEnabled = p[PIP] ?: false,
            groupChat = p[GROUP_CHAT] ?: false,
            guestName = p[GUEST_NAME] ?: "",
            themeMode = ThemeMode.parse(p[THEME_MODE])
        )
    }

    suspend fun setSync(v: Boolean) = context.dataStore.edit { it[SYNC] = v }.let {}
    suspend fun setAccuracy(v: Double) = context.dataStore.edit { it[ACCURACY] = v }.let {}
    suspend fun setCompat(v: CompatMode) = context.dataStore.edit { it[COMPAT] = v.name }.let {}
    suspend fun setEmotes(v: Boolean) = context.dataStore.edit { it[EMOTES] = v }.let {}
    suspend fun setPip(v: Boolean) = context.dataStore.edit { it[PIP] = v }.let {}
    suspend fun setGroupChat(v: Boolean) = context.dataStore.edit { it[GROUP_CHAT] = v }.let {}
    suspend fun setGuestName(v: String) =
        context.dataStore.edit { it[GUEST_NAME] = v.trim().take(20) }.let {}
    suspend fun setThemeMode(v: ThemeMode) = context.dataStore.edit { it[THEME_MODE] = v.name }.let {}

    // ---- per-channel state ----

    val favourites: Flow<List<String>> =
        context.dataStore.data.map { (it[FAVOURITES] ?: emptySet()).sorted() }

    val recents: Flow<List<String>> =
        context.dataStore.data.map { p ->
            (p[RECENTS] ?: "").split('\n').filter { it.isNotBlank() }
        }

    suspend fun toggleFavourite(channel: String) = context.dataStore.edit { p ->
        val cur = (p[FAVOURITES] ?: emptySet()).toMutableSet()
        if (!cur.add(channel)) cur.remove(channel)
        p[FAVOURITES] = cur
    }.let {}

    /** Waterfall: joining a channel bumps it to the front, and the list is
     *  capped at [MAX_RECENTS] — the oldest entry falls off the end rather
     *  than the list growing without bound. */
    suspend fun noteVisit(channel: String) = context.dataStore.edit { p ->
        val cur = (p[RECENTS] ?: "").split('\n').filter { it.isNotBlank() && it != channel }
        p[RECENTS] = (listOf(channel) + cur).take(MAX_RECENTS).joinToString("\n")
    }.let {}

    suspend fun clearRecents() = context.dataStore.edit { p -> p[RECENTS] = "" }.let {}

    fun channelCompat(channel: String): Flow<CompatMode?> =
        context.dataStore.data.map { p -> p[compatKey(channel)]?.let { CompatMode.parse(it) } }

    suspend fun setChannelCompat(channel: String, v: CompatMode?) =
        context.dataStore.edit { p ->
            if (v == null) p.remove(compatKey(channel)) else p[compatKey(channel)] = v.name
        }.let {}

    private companion object {
        const val MAX_RECENTS = 4

        val SYNC = booleanPreferencesKey("sync_enabled")
        val ACCURACY = doublePreferencesKey("sync_accuracy")
        val COMPAT = stringPreferencesKey("compat_mode")
        val EMOTES = booleanPreferencesKey("show_emotes")
        val PIP = booleanPreferencesKey("pip_enabled")
        val GROUP_CHAT = booleanPreferencesKey("group_chat")
        val GUEST_NAME = stringPreferencesKey("guest_name")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val FAVOURITES = stringSetPreferencesKey("favourites")
        val RECENTS = stringPreferencesKey("recents")

        fun compatKey(channel: String) = stringPreferencesKey("compat:$channel")
    }
}
