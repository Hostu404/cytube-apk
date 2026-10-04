package com.cytube.mobile.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.cytube.mobile.di.Graph
import java.util.concurrent.TimeUnit

/** Largest subtitle file the app will read: real ones are well under 1 MB,
 *  and ExoPlayer holds the whole file in memory. */
internal const val MAX_SUBTITLE_BYTES = 5L * 1024 * 1024

/**
 * Subtitle files are tiny, so a server slow to send one is better skipped
 * than waited for: the video can't start until every subtitle file has
 * loaded, and the media client's limits (made long for big video files)
 * could hold it on the spinner for a minute.
 */
private val subtitleHttp by lazy {
    Graph.http.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
}

/**
 * The data source for an item's playback, with its subtitle files
 * ([subtitleUris]) fetched separately: through [subtitleHttp]'s short limits
 * and never past [MAX_SUBTITLE_BYTES], so a hanging subtitle server or a
 * huge file can neither hold up the video nor run the app out of memory.
 * Either fails as an error naming the subtitle's address, which
 * NativePlayerHandle.recoverFromSubtitleError then drops the file for.
 * Everything else goes to [media] as before.
 */
@OptIn(UnstableApi::class)
internal class SubtitleRoutingDataSourceFactory(
    private val media: DataSource.Factory,
    private val subtitleUris: Set<String>
) : DataSource.Factory {
    private val subtitles = OkHttpDataSource.Factory(subtitleHttp).setUserAgent(Graph.DEFAULT_USER_AGENT)

    override fun createDataSource(): DataSource =
        if (subtitleUris.isEmpty()) media.createDataSource()
        else Routing(media.createDataSource(), subtitles.createDataSource(), subtitleUris)

    private class Routing(
        private val media: DataSource,
        private val subtitles: DataSource,
        private val subtitleUris: Set<String>
    ) : DataSource {
        private var active: DataSource? = null
        private var subtitleSpec: DataSpec? = null
        private var subtitleBytes = 0L

        override fun addTransferListener(transferListener: TransferListener) {
            media.addTransferListener(transferListener)
            subtitles.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val isSubtitle = dataSpec.uri.toString() in subtitleUris
            val source = if (isSubtitle) subtitles else media
            active = source
            subtitleSpec = if (isSubtitle) dataSpec else null
            subtitleBytes = 0
            val length = source.open(dataSpec)
            if (isSubtitle && length != C.LENGTH_UNSET.toLong() && length > MAX_SUBTITLE_BYTES) throw tooLarge(dataSpec)
            return length
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = checkNotNull(active).read(buffer, offset, length)
            val spec = subtitleSpec
            if (spec != null && read > 0) {
                subtitleBytes += read
                if (subtitleBytes > MAX_SUBTITLE_BYTES) throw tooLarge(spec)
            }
            return read
        }

        override fun getUri(): Uri? = active?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

        override fun close() {
            try {
                active?.close()
            } finally {
                active = null
                subtitleSpec = null
            }
        }

        private fun tooLarge(spec: DataSpec) = HttpDataSource.HttpDataSourceException(
            "subtitle file is over ${MAX_SUBTITLE_BYTES / (1024 * 1024)} MB",
            spec,
            // Not a connection problem: a file to drop, not to fetch again.
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            HttpDataSource.HttpDataSourceException.TYPE_READ
        )
    }
}
