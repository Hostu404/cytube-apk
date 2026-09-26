package com.cytube.mobile.player

/**
 * One interface, two backends: NativePlayerHandle (Media3) and the embed
 * handle in PlayerSurface (a provider's player in a WebView). SyncEngine
 * talks only to this, so the synchronisation algorithm is written once.
 * Loading an item is backend-specific and isn't part of it.
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

    /** True when the player is actively playing with a valid playback position. */
    val isPlaying: Boolean get() = !isPaused && !isBuffering

    /** True for native ExoPlayer backend, false for WebView embed controllers. */
    val isNative: Boolean get() = true

    /** True once [release] has run. ChannelViewModel treats a released
     *  player as detached. */
    val isReleased: Boolean

    /** Seconds of media buffered ahead of the playhead, or NaN if the backend
     *  can't tell. SyncEngine won't speed-nudge a player that's nearly dry. */
    val bufferedAheadSeconds: Double get() = Double.NaN

    /** Small playback-rate adjustment SyncEngine uses to close moderate drift
     *  without a seek. Backends that can't do it just ignore it. */
    fun setPlaybackRate(rate: Float) {}

    fun play()
    fun pause()
    fun seekTo(seconds: Double)
    suspend fun currentTimeSeconds(): Double
    fun setVolume(volume: Float)
    fun release()
}
