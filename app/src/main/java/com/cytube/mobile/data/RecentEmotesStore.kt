package com.cytube.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import java.io.IOException

/**
 * The emotes you've used lately, per channel, kept between visits for the top
 * of the emote sheet (see RecentEmotes). In the app's settings store, one
 * entry a channel: the names, newest first, a line each. A disk that can't
 * be read or written (full, say, or its file spoilt) only means they're not
 * kept this time: never a crash in the middle of chatting.
 */
class RecentEmotesStore(private val context: Context) {
    suspend fun load(channel: String): List<String> = try {
        context.dataStore.data.first()[key(channel)]?.split(SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty()
    } catch (_: IOException) {
        emptyList()
    }

    suspend fun save(channel: String, names: List<String>) {
        try {
            context.dataStore.edit { it[key(channel)] = names.joinToString(SEPARATOR) }
        } catch (_: IOException) {
            // Not kept this time; the sheet still has them for this visit.
        }
    }

    private fun key(channel: String) = stringPreferencesKey("recent_emotes_" + channel.lowercase())

    private companion object {
        const val SEPARATOR = "\n"
    }
}
