package com.cytube.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore("cytube_settings")

/** Automatic / Native / Web, per the brief's compatibility toggle. */
enum class CompatMode { AUTOMATIC, NATIVE, WEB;
    companion object { fun parse(s: String?) = entries.firstOrNull { it.name == s } ?: AUTOMATIC }
}

data class Settings(
    val syncEnabled: Boolean = true,
    val syncAccuracy: Double = 2.0,
    val compatMode: CompatMode = CompatMode.AUTOMATIC,
    val showEmotes: Boolean = true,
    /** Off by default — PiP-to-fullscreen still isn't reliable enough to
     *  turn on for everyone unasked; see the "Experimental" label on its
     *  Settings toggle. */
    val pipEnabled: Boolean = false,
    /** Display name used to join chat as a guest. Blank means "not chosen
     *  yet" — one is generated and saved the first time it's needed. */
    val guestName: String = ""
)

class SettingsStore(private val context: Context) {

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            syncEnabled = p[SYNC] ?: true,
            syncAccuracy = p[ACCURACY] ?: 2.0,
            compatMode = CompatMode.parse(p[COMPAT]),
            showEmotes = p[EMOTES] ?: true,
            pipEnabled = p[PIP] ?: false,
            guestName = p[GUEST_NAME] ?: ""
        )
    }

    suspend fun setSync(v: Boolean) = context.dataStore.edit { it[SYNC] = v }.let {}
    suspend fun setAccuracy(v: Double) = context.dataStore.edit { it[ACCURACY] = v }.let {}
    suspend fun setCompat(v: CompatMode) = context.dataStore.edit { it[COMPAT] = v.name }.let {}
    suspend fun setEmotes(v: Boolean) = context.dataStore.edit { it[EMOTES] = v }.let {}
    suspend fun setPip(v: Boolean) = context.dataStore.edit { it[PIP] = v }.let {}
    suspend fun setGuestName(v: String) =
        context.dataStore.edit { it[GUEST_NAME] = v.trim().take(20) }.let {}

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
        val GUEST_NAME = stringPreferencesKey("guest_name")
        val FAVOURITES = stringSetPreferencesKey("favourites")
        val RECENTS = stringPreferencesKey("recents")

        fun compatKey(channel: String) = stringPreferencesKey("compat:$channel")
    }
}
