package com.cytube.mobile.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.NetworkTypeObserver
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter

/**
 * The same bandwidth estimate ExoPlayer itself uses (the process-wide
 * DefaultBandwidthMeter every player and media data source here reports
 * into), but only once it's based on real downloads.
 *
 * Until something has actually been downloaded through it,
 * DefaultBandwidthMeter reports a per-country, per-network-type default, not a
 * measurement: 3.2 Mbps for UK Wi-Fi, 970 kbps for UK 4G. Choosing a quality
 * from that would put the first video after every app launch at 720p or lower
 * even on fast broadband, so [measuredBps] returns null until the meter has
 * reported at least one real sample. The meter also drops back to those
 * defaults when the network type changes (Wi-Fi to mobile data, say), so the
 * "measured" flag is cleared then too.
 */
@OptIn(UnstableApi::class)
object BandwidthEstimate {

    @Volatile private var measured = false
    @Volatile private var meter: DefaultBandwidthMeter? = null
    @Volatile private var lastNetworkType: Int? = null
    private var networkListener: NetworkTypeObserver.Listener? = null

    /** Idempotent; called from CyTubeApp.onCreate. */
    fun init(context: Context) {
        if (meter != null) return
        synchronized(this) {
            if (meter != null) return
            val appContext = context.applicationContext
            val m = DefaultBandwidthMeter.getSingletonInstance(appContext)
            m.addEventListener(Handler(Looper.getMainLooper())) { _, _, _ -> measured = true }
            // Called once straight away with the current type, then on every
            // change. Stored in a field on purpose: NetworkTypeObserver only
            // keeps a WeakReference to its listeners, so a bare lambda would
            // be garbage-collected and silently stop being called.
            val listener = NetworkTypeObserver.Listener { type ->
                val previous = lastNetworkType
                lastNetworkType = type
                if (previous != null && previous != type) measured = false
            }
            networkListener = listener
            NetworkTypeObserver.getInstance(appContext).register(listener)
            meter = m
        }
    }

    /** Bits per second from real downloads this session, or null if there's
     *  no such measurement yet (see the class doc). */
    fun measuredBps(): Long? {
        if (!measured) return null
        return meter?.bitrateEstimate?.takeIf { it > 0 }
    }
}
