package com.cytube.mobile

import android.graphics.drawable.Animatable
import android.os.Build
import coil.ImageLoader
import coil.decode.DecodeResult
import coil.decode.DecodeUtils
import coil.decode.Decoder
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.ImageSource
import coil.decode.isGif
import coil.fetch.SourceResult
import coil.request.Options
import kotlinx.coroutines.CancellationException
import okio.BufferedSource
import okio.buffer
import okio.source
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * GIF emotes. From Android 9, Android's own decoder (ImageDecoder) animates
 * them, decoding each frame off the main thread. The old Movie decoder
 * (GifDecoder) draws every frame in software on the main thread, which adds up
 * on a busy, emote-heavy channel, so it's kept only for:
 *  - Android 8, which has no ImageDecoder;
 *  - the GIFs ImageDecoder shows still though they have more than one frame:
 *    some emote packs' odd frame metadata makes it quietly give the first
 *    frame alone, so a still result is checked against the GIF's own frame
 *    count (see GifFrames).
 */
internal class EmoteGifDecoder(private val source: ImageSource, private val options: Options) : Decoder {

    override suspend fun decode(): DecodeResult {
        val bytes = source.use { GifBytes.read(it.source()) }
        // Read from [bytes] as each decoder goes, not from a whole copy made
        // up front: a decoder that needs one (ImageDecoder, on Android 11+)
        // makes its own.
        fun copy() = ImageSource(ByteArrayInputStream(bytes).source().buffer(), options.context)
        if (Build.VERSION.SDK_INT >= 28) {
            val decoded = try {
                ImageDecoderDecoder(copy(), options).decode()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (decoded != null && (decoded.drawable is Animatable || GifFrames.count(bytes, atMost = 2) < 2)) return decoded
        }
        return GifDecoder(copy(), options).decode()
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceResult, options: Options, imageLoader: ImageLoader): Decoder? =
            if (DecodeUtils.isGif(result.source.source())) EmoteGifDecoder(result.source, options) else null
    }
}

/** A GIF read whole, as EmoteGifDecoder decodes it. */
internal object GifBytes {
    /** The biggest GIF decoded. It's held whole while it's decoded, and
     *  ImageDecoder takes a copy of its own, so one much bigger (an emote or
     *  poll picture pointing at a huge file) could run the app out of memory.
     *  Past this it fails to load like any broken image. */
    const val MAX_BYTES = 32L * 1024 * 1024

    /** All of [source], or an IOException, having read no more than about
     *  [max] bytes, if it's longer than that. */
    fun read(source: BufferedSource, max: Long = MAX_BYTES): ByteArray {
        if (source.request(max + 1)) throw IOException("GIF over $max bytes")
        return source.readByteArray()
    }
}

/** A GIF's frames counted from its blocks, without decoding it. */
internal object GifFrames {
    /** How many frames [gif] has, counting no further than [atMost]; 0 if
     *  it isn't a GIF, and as many as could be read if it's cut short. */
    fun count(gif: ByteArray, atMost: Int = Int.MAX_VALUE): Int {
        if (gif.size < 13 || gif[0] != 'G'.code.toByte() || gif[1] != 'I'.code.toByte() || gif[2] != 'F'.code.toByte()) return 0
        var i = 13 + colourTableSize(gif[10])
        var frames = 0
        while (i < gif.size) {
            when (gif[i].toInt() and 0xFF) {
                0x21 -> i = skipSubBlocks(gif, i + 2) ?: return frames                    // an extension
                0x2C -> {                                                                 // a frame
                    if (++frames >= atMost || i + 10 > gif.size) return frames
                    i = skipSubBlocks(gif, i + 10 + colourTableSize(gif[i + 9]) + 1) ?: return frames
                }
                else -> return frames                                                     // the end
            }
        }
        return frames
    }

    /** The colour table a block's packed byte says follows it, in bytes. */
    private fun colourTableSize(packed: Byte): Int {
        val p = packed.toInt() and 0xFF
        return if (p and 0x80 != 0) 3 * (1 shl ((p and 0x07) + 1)) else 0
    }

    /** Past the data sub-blocks from [from]: each a length byte and that
     *  many bytes, up to a zero length. Null if they run off the end. */
    private fun skipSubBlocks(b: ByteArray, from: Int): Int? {
        var i = from
        while (i < b.size) {
            val n = b[i].toInt() and 0xFF
            i += 1 + n
            if (n == 0) return i
        }
        return null
    }
}
