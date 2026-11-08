package com.cytube.mobile.data

import android.content.Context

/**
 * The emotes you've used lately, per channel, kept between visits for the top
 * of the emote sheet (see RecentEmotes): the names, newest first. Kept with
 * the rest of what the app knows about each channel (see ChannelData), not
 * in the settings file. A disk that can't be read or written (full, say, or
 * its file spoilt) only means they're not kept this time: never a crash in
 * the middle of chatting.
 */
class RecentEmotesStore(context: Context) {
    private val channels = ChannelData(context)

    suspend fun load(channel: String): List<String> = channels.recentEmotes(channel)

    suspend fun save(channel: String, names: List<String>) {
        channels.setRecentEmotes(channel, names)
    }
}
