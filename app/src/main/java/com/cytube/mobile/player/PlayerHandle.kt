package com.cytube.mobile.player

import com.cytube.mobile.net.MediaFrame

/**
 * One interface, three backends. SyncEngine talks only to this, so the
 * synchronisation algorithm is written once and is identical whether the item
 * is playing through Media3 or through a provider embed in a WebView.
 */
interface PlayerHandle {
    val mediaId: String?
    val mediaType: String?
    val mediaLengthSeconds: Int
    val isPaused: Boolean
    /** True while the backend is rebuffering after a seek or a network stall.
     *  SyncEngine uses this to avoid piling a new corrective seek on top of
     *  one that hasn't finished loading yet — the cause of the skip/jitter
     *  loop on large, high-bitrate files. */
    val isBuffering: Boolean

    /** [qualityIndex] indexes into media.direct (already sorted
     *  highest-to-lowest — see DirectSource.parse), for the NATIVE backend's
     *  own lightweight quality auto-adaptation (see ChannelViewModel's
     *  onPlaybackStall/maybeUpgradeQuality). Out of range, or a media with
     *  no [MediaFrame.direct] entries at all, falls back to
     *  [MediaFrame.bestSource] exactly like before this parameter existed —
     *  callers that don't care just pass 0. */
    fun load(media: MediaFrame, qualityIndex: Int = 0)
    fun play()
    fun pause()
    fun seekTo(seconds: Double)
    /** Nudges playback rate to close small drift without a hard seek (and
     *  therefore without forcing a rebuffer). 1.0 is normal speed. */
    fun setSpeed(speed: Float)
    suspend fun currentTimeSeconds(): Double
    fun setVolume(volume: Float)
    fun release()
}
