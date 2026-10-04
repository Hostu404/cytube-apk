package com.cytube.mobile.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.webvtt.WebvttParser
import com.cytube.mobile.di.Graph
import com.cytube.mobile.net.TextTrackSource
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Largest subtitle file the app will read: real ones are well under 1 MB,
 *  and the whole file is held in memory. */
internal const val MAX_SUBTITLE_BYTES = 5L * 1024 * 1024

/** Subtitle files are tiny, so a server slow to send one is given up on
 *  quickly rather than given the media client's long limits. */
private val subtitleHttp by lazy {
    Graph.http.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
}

/**
 * Subtitle files the app fetches and draws itself rather than handing to
 * the player: CyTube's (a custom manifest's textTracks, Google Drive's) and
 * YouTube's captions (see YouTubeResolver.captionTracks). Only subtitles
 * inside the stream itself are left to the player.
 *
 * Handed to the player, every subtitle file has to be fetched before the
 * video can start, and one that fails (a dead link, YouTube rate limiting)
 * holds the start up and then restarts the video without it. Fetched here,
 * nothing is downloaded until a track is picked (or a manifest's default
 * one starts showing), the video never waits for it, and a file that can't
 * be had just leaves the CC menu.
 *
 * The picked file is parsed with Media3's own WebVTT parser and drawn into
 * the PlayerView's subtitle view ([output]) for the current position, so
 * it looks the same as the player's own subtitles. Main thread only.
 */
@OptIn(UnstableApi::class)
internal class ExternalCaptions(
    private val player: Player,
    /** Called when [tracks] or [selected] change by themselves: a track
     *  that couldn't be fetched is dropped. */
    private val onChanged: () -> Unit
) {
    /** The tracks on offer for what's loaded. */
    var tracks: List<TextTrackSource> = emptyList(); private set

    /** Index into [tracks] of the one showing (or loading), or -1. */
    var selected = -1; private set

    /** Where the cues go: the PlayerView's subtitle view. Set by the
     *  player surface; an empty list clears it. */
    var output: ((List<Cue>) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())

    /** Bumped on every change of track, so a fetch that finishes after
     *  the choice changed (or the item did) is ignored. */
    private var generation = 0
    private var call: Call? = null
    private var segments: List<CuesWithTiming> = emptyList()
    private var shown: List<Cue> = emptyList()
    private var lastPushMs = 0L
    private var released = false

    /** Redraws often only while the video plays; paused (or stopped, or
     *  in the background), once a second is plenty, just in case the view
     *  was replaced, so a showing subtitle costs next to nothing. */
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, if (player.isPlaying) TICK_MS else IDLE_TICK_MS)
        }
    }

    /** Back to the quick pace as soon as it plays, and redrawn at once on a
     *  seek rather than at the next tick. */
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (segments.isNotEmpty() && isPlaying) restartTicking()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (segments.isNotEmpty()) restartTicking()
        }
    }

    init {
        player.addListener(playerListener)
    }

    private fun restartTicking() {
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    /** A new item's tracks. The same tracks again (the same item reloaded
     *  after an error) keep the one showing; when [sameItem] (looked up
     *  again, which gives new addresses), the one with the same name is
     *  fetched afresh. */
    fun reset(newTracks: List<TextTrackSource>, sameItem: Boolean) {
        if (newTracks == tracks) return
        val showing = tracks.getOrNull(selected)?.name
        stop()
        tracks = newTracks
        selected = -1
        if (sameItem && showing != null) {
            newTracks.indexOfFirst { it.name == showing }.takeIf { it >= 0 }?.let(::select)
        }
    }

    /** Shows track [index] of [tracks], or none for null. */
    fun select(index: Int?) {
        if (released) return
        val track = index?.let { tracks.getOrNull(it) }
        if (track == null) {
            stop()
            selected = -1
            return
        }
        if (index == selected) return
        stop()
        selected = index
        fetch(track, generation, attempt = 0)
    }

    fun release() {
        released = true
        player.removeListener(playerListener)
        stop()
        output = null
    }

    /** Cancels any fetch, stops drawing and clears what's on screen. */
    private fun stop() {
        generation++
        call?.cancel()
        call = null
        handler.removeCallbacksAndMessages(null)
        segments = emptyList()
        if (shown.isNotEmpty()) {
            shown = emptyList()
            output?.invoke(emptyList())
        }
    }

    private fun fetch(track: TextTrackSource, gen: Int, attempt: Int) {
        val request = Request.Builder().url(track.url)
            .header("User-Agent", Graph.DEFAULT_USER_AGENT)
            .build()
        val newCall = subtitleHttp.newCall(request)
        call = newCall
        newCall.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                failed(track, gen, attempt, "${e.javaClass.simpleName}: ${e.message}", retryable = true)
            }

            override fun onResponse(call: Call, response: Response) {
                val parsed = response.use { r ->
                    if (!r.isSuccessful) {
                        // Rate limiting and server trouble can pass;
                        // anything else (gone, forbidden) won't.
                        val retryable = r.code == 429 || r.code == 408 || r.code >= 500
                        failed(track, gen, attempt, "HTTP ${r.code}", retryable)
                        return
                    }
                    runCatching { parse(r) }.getOrElse { e ->
                        failed(track, gen, attempt, "unreadable: ${e.message}", retryable = false)
                        return
                    }
                }
                handler.post { loaded(gen, parsed) }
            }
        })
    }

    /** Off the main thread (OkHttp's callback thread). */
    private fun parse(response: Response): List<CuesWithTiming> {
        val body = response.body ?: throw IOException("no body")
        if (body.contentLength() > MAX_SUBTITLE_BYTES) throw IOException("over 5 MB")
        val bytes = body.source().use { source ->
            source.request(MAX_SUBTITLE_BYTES + 1)
            if (source.buffer.size > MAX_SUBTITLE_BYTES) throw IOException("over 5 MB")
            source.readByteArray()
        }
        val out = ArrayList<CuesWithTiming>()
        WebvttParser().parse(bytes, SubtitleParser.OutputOptions.allCues()) { out += it }
        return out.sortedBy { it.startTimeUs }
    }

    /** From any thread. Retried twice, later each time, if it may pass;
     *  otherwise (or after that) the track is dropped from the menu. */
    private fun failed(track: TextTrackSource, gen: Int, attempt: Int, why: String, retryable: Boolean) {
        handler.post {
            if (gen != generation || released) return@post
            if (retryable && attempt < RETRY_DELAYS_MS.size) {
                Log.i(TAG, "caption fetch failed ($why), retrying: ${track.name}")
                handler.postDelayed({
                    if (gen == generation && !released) fetch(track, gen, attempt + 1)
                }, RETRY_DELAYS_MS[attempt])
                return@post
            }
            Log.w(TAG, "caption track dropped ($why): ${track.name}")
            stop()
            tracks = tracks.filterNot { it.url == track.url }
            selected = -1
            onChanged()
        }
    }

    private fun loaded(gen: Int, parsed: List<CuesWithTiming>) {
        if (gen != generation || released) return
        call = null
        segments = parsed
        restartTicking()
    }

    /** Puts up the cues for the current position. Re-sent now and then
     *  even when unchanged, in case the view was cleared or replaced. */
    private fun render() {
        val positionUs = player.currentPosition * 1000
        val current = segments.lastOrNull { it.startTimeUs <= positionUs }
            ?.takeIf { it.endTimeUs == androidx.media3.common.C.TIME_UNSET || positionUs < it.endTimeUs }
            ?.cues.orEmpty()
        val now = SystemClock.elapsedRealtime()
        if (current != shown || now - lastPushMs >= REFRESH_MS) {
            shown = current
            lastPushMs = now
            output?.invoke(current)
        }
    }

    private companion object {
        const val TAG = "CyTubeCaptions"
        const val TICK_MS = 100L
        const val IDLE_TICK_MS = 1_000L
        const val REFRESH_MS = 1_000L
        val RETRY_DELAYS_MS = longArrayOf(2_000L, 5_000L)
    }
}
