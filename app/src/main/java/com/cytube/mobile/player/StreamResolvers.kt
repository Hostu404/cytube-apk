package com.cytube.mobile.player

import com.cytube.mobile.di.Graph
import com.cytube.mobile.net.MediaTypes

/** A playable stream a resolver found for an item. */
data class ResolvedStream(
    val url: String,
    val mimeType: String?,
    /** Request headers the stream itself needs (only Google Drive's so far). */
    val headers: Map<String, String> = emptyMap(),
    /** Which format this is (the resolver's quality label, e.g. "360p"), so
     *  the on-disk media cache keeps different encodes of the same video
     *  apart — see NativePlayerHandle.loadUrl. */
    val variant: String = ""
)

/**
 * The player types whose stream has to be looked up before Media3 can play
 * it (YouTube, Google Drive, Streamable, PeerTube), behind one interface.
 * Used by the player surface and by ChannelViewModel, which loads the next
 * item itself while the app is in the background.
 */
object StreamResolvers {

    fun handles(player: MediaTypes.Player): Boolean = player in RESOLVED

    /** A fresh cached stream for [id], without any network work. */
    fun cached(player: MediaTypes.Player, id: String): ResolvedStream? = when (player) {
        MediaTypes.Player.NEWPIPE -> YouTubeResolver.cached(id)?.let { ResolvedStream(it.url, it.mimeType, variant = it.label) }
        MediaTypes.Player.GDRIVE -> GoogleDriveResolver.cached(id)?.let { driveStream(it) }
        MediaTypes.Player.STREAMABLE -> StreamableResolver.cached(id)?.let { ResolvedStream(it.url, it.mimeType, variant = it.label) }
        MediaTypes.Player.PEERTUBE -> PeerTubeResolver.cached(id)?.let { ResolvedStream(it.url, it.mimeType, variant = it.label) }
        else -> null
    }

    suspend fun resolve(player: MediaTypes.Player, id: String): Result<ResolvedStream> = when (player) {
        MediaTypes.Player.NEWPIPE -> YouTubeResolver.resolve(id).map { ResolvedStream(it.url, it.mimeType, variant = it.label) }
        MediaTypes.Player.GDRIVE -> GoogleDriveResolver.resolve(Graph.http, id).map { driveStream(it) }
        MediaTypes.Player.STREAMABLE -> StreamableResolver.resolve(Graph.http, id).map { ResolvedStream(it.url, it.mimeType, variant = it.label) }
        MediaTypes.Player.PEERTUBE -> PeerTubeResolver.resolve(Graph.http, id).map { ResolvedStream(it.url, it.mimeType, variant = it.label) }
        else -> Result.failure(IllegalArgumentException("$player has no stream resolver"))
    }

    /** Drops a cached stream after playback of it failed, so the next
     *  attempt (rejoining, or the item coming round again) resolves a fresh
     *  one instead of reusing the dead link until the cache expires. */
    fun invalidate(player: MediaTypes.Player, id: String) {
        when (player) {
            MediaTypes.Player.NEWPIPE -> YouTubeResolver.invalidate(id)
            MediaTypes.Player.GDRIVE -> GoogleDriveResolver.invalidate(id)
            MediaTypes.Player.STREAMABLE -> StreamableResolver.invalidate(id)
            MediaTypes.Player.PEERTUBE -> PeerTubeResolver.invalidate(id)
            else -> Unit
        }
    }

    /** For messages: "YouTube lookup failed: …". */
    fun providerName(player: MediaTypes.Player): String = when (player) {
        MediaTypes.Player.NEWPIPE -> "YouTube"
        MediaTypes.Player.GDRIVE -> "Google Drive"
        MediaTypes.Player.STREAMABLE -> "Streamable"
        MediaTypes.Player.PEERTUBE -> "PeerTube"
        else -> player.name
    }

    private fun driveStream(it: GoogleDriveResolver.Resolved) =
        ResolvedStream(it.url, it.mimeType, GoogleDriveResolver.STREAM_HEADERS, it.label)

    private val RESOLVED = setOf(
        MediaTypes.Player.NEWPIPE,
        MediaTypes.Player.GDRIVE,
        MediaTypes.Player.STREAMABLE,
        MediaTypes.Player.PEERTUBE
    )
}
