package com.cytube.mobile.player

import java.util.concurrent.ConcurrentHashMap

/**
 * Small TTL cache shared by the resolvers (YouTubeResolver, GoogleDriveResolver,
 * PeerTubeResolver, StreamableResolver) — each of them used to hand-roll this
 * exact same ConcurrentHashMap<K, Pair<Long, V>> + size-triggered-eviction
 * pattern independently, four copies that could (and had started to) quietly
 * drift out of sync with each other. One shared implementation now.
 *
 * Not a long-term store: these resolvers cache signed, time-limited stream
 * URLs just long enough to survive a player-surface rebuild (a few minutes),
 * not across app restarts or beyond [ttlMs].
 */
class TimedCache<K : Any, V : Any>(
    private val ttlMs: Long,
    private val evictAboveSize: Int
) {
    private val entries = ConcurrentHashMap<K, Pair<Long, V>>()

    /** A still-fresh cached value for [key], or null on a miss/expiry.
     *  Opportunistically sweeps expired entries once the map has grown past
     *  [evictAboveSize], same as every resolver's own cache used to. */
    fun get(key: K, nowMs: Long = System.currentTimeMillis()): V? {
        if (entries.size > evictAboveSize) {
            entries.entries.removeIf { nowMs - it.value.first >= ttlMs }
        }
        val (at, value) = entries[key] ?: return null
        return if (nowMs - at < ttlMs) value else null
    }

    fun put(key: K, value: V, nowMs: Long = System.currentTimeMillis()) {
        entries[key] = nowMs to value
    }
}
