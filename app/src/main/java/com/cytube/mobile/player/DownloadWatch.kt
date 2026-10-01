package com.cytube.mobile.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Every media byte downloaded over the network, counted as it arrives, so
 * the log can say how fast a video is actually downloading. Bytes read back
 * from the disk cache don't pass through here.
 */
object MediaBytes {
    private val total = AtomicLong()

    fun total(): Long = total.get()

    /** [inner] (the bandwidth meter) with every network byte also counted
     *  here. */
    @OptIn(UnstableApi::class)
    fun counting(inner: TransferListener): TransferListener = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) =
            inner.onTransferInitializing(source, dataSpec, isNetwork)

        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) =
            inner.onTransferStart(source, dataSpec, isNetwork)

        override fun onBytesTransferred(
            source: DataSource,
            dataSpec: DataSpec,
            isNetwork: Boolean,
            bytesTransferred: Int
        ) {
            if (isNetwork) total.addAndGet(bytesTransferred.toLong())
            inner.onBytesTransferred(source, dataSpec, isNetwork, bytesTransferred)
        }

        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) =
            inner.onTransferEnd(source, dataSpec, isNetwork)
    }
}

/**
 * Download speed against what the video needs, for the log (see
 * PlayerSurface). Fed a sample a second: bytes downloaded so far, and how
 * far the player has buffered. The speed the video needs is worked out from
 * those too, as bytes downloaded per second of video buffered, since a
 * file's own bitrate is often missing from its header.
 */
class DownloadWatch {
    private class Sample(val atMs: Long, val bytes: Long, val bufferedMs: Long)

    private val samples = ArrayDeque<Sample>()

    fun sample(atMs: Long, bytes: Long, bufferedMs: Long) {
        // A seek or a new item moves the buffered position somewhere else:
        // the old samples no longer describe this download.
        val last = samples.lastOrNull()
        if (last != null && (bufferedMs < last.bufferedMs || bufferedMs - last.bufferedMs > JUMP_MS)) {
            samples.clear()
        }
        samples.addLast(Sample(atMs, bytes, bufferedMs))
        while (samples.size > KEEP) samples.removeFirst()
    }

    /** E.g. "downloading 6.2 Mbps (last 10s), 7.9 Mbps (last minute);
     *  video needs ~9.4 Mbps". [knownBps] is the file's own bitrate, when
     *  its header gives one. */
    fun report(knownBps: Long = 0L): String {
        val last = samples.lastOrNull() ?: return "no download measured yet"
        val recent = mbpsSince(last, 10_000L)
        val minute = mbpsSince(last, 60_000L)
        val needs = if (knownBps > 0) knownBps / 1e6 else neededMbps()
        return "downloading ${fmt(recent)} Mbps (last 10s), ${fmt(minute)} Mbps (last minute); " +
            "video needs ~${fmt(needs)} Mbps" + if (knownBps > 0) "" else " (estimated)"
    }

    private fun mbpsSince(last: Sample, windowMs: Long): Double? {
        val first = samples.firstOrNull { last.atMs - it.atMs <= windowMs } ?: return null
        val seconds = (last.atMs - first.atMs) / 1000.0
        if (seconds < 2.0) return null
        return (last.bytes - first.bytes) * 8 / seconds / 1e6
    }

    private fun neededMbps(): Double? {
        val first = samples.firstOrNull() ?: return null
        val last = samples.last()
        val mediaSeconds = (last.bufferedMs - first.bufferedMs) / 1000.0
        val bytes = last.bytes - first.bytes
        if (mediaSeconds < 2.0 || bytes < 256 * 1024) return null
        return bytes * 8 / mediaSeconds / 1e6
    }

    private fun fmt(mbps: Double?) = mbps?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "?"

    private companion object {
        const val KEEP = 61                 // a minute, at a sample a second
        const val JUMP_MS = 8_000L          // buffered further than this in a second: a seek
    }
}
