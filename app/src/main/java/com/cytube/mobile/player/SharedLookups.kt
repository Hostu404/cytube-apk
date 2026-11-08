package com.cytube.mobile.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * At most one lookup per key at a time: asked for a key that's already
 * being looked up, it waits for that lookup's answer rather than starting a
 * second one. For StreamResolvers, whose lookups (a YouTube one is several
 * requests and some script) were otherwise run twice at once for the same
 * item: by the player surface, and by ChannelViewModel's own background
 * load or its look-ahead.
 *
 * Each lookup runs in [scope], not in whoever asked first, so one caller
 * giving up (its screen closing) doesn't cancel it for the others, and it
 * finishes even if they all give up: the resolvers keep what it found.
 * Nothing is kept here once a lookup is over, the next call for its key
 * looks again (or finds it in the resolver's own cache).
 */
internal class SharedLookups<K : Any, V>(private val scope: CoroutineScope) {

    private val running = HashMap<K, Deferred<V>>()

    suspend fun get(key: K, lookUp: suspend () -> V): V {
        val lookup = synchronized(running) {
            running[key] ?: scope.async(start = CoroutineStart.LAZY) { lookUp() }.also { started ->
                running[key] = started
                // Registered before it's started, so it can't be missed.
                started.invokeOnCompletion {
                    synchronized(running) { if (running[key] === started) running.remove(key) }
                }
            }
        }
        lookup.start()
        return lookup.await()
    }

    /** The next [get] for [key] starts a lookup of its own, even while one
     *  is under way (which carries on for whoever is waiting on it). */
    fun forget(key: K) {
        synchronized(running) { running.remove(key) }
    }

    /** Lookups under way. */
    val size: Int get() = synchronized(running) { running.size }
}
