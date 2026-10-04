package com.cytube.mobile.ui.channel

import android.content.Context
import android.util.TypedValue
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Dimension
import coil.size.Precision
import coil.size.Size
import kotlin.math.roundToInt

/**
 * How chat asks Coil for an emote image, in one place, so a chat row and
 * the prefetch when its message arrives ask the same way: Coil's memory
 * cache only hands back an image for a request it fits.
 */
internal object EmoteImages {

    /** A chat emote's height mid-sentence (a message that's only emotes
     *  shows them bigger). */
    const val INLINE_HEIGHT_SP = 28f

    /**
     * [url] at [heightPx] tall. Height only: the width follows the image's
     * own shape, so learning that shape (see Panels' EmoteAspect) doesn't
     * change the request, and the emote isn't decoded a second time and
     * blanked meanwhile, which a width in it did on every emote's first
     * showing. INEXACT, so a copy already decoded bigger (a solo emote's)
     * serves a smaller one too.
     */
    fun request(context: Context, url: String, heightPx: Int): ImageRequest =
        ImageRequest.Builder(context)
            .data(url)
            .size(Size(Dimension.Undefined, Dimension(heightPx.coerceAtLeast(1))))
            .precision(Precision.INEXACT)
            .build()

    /** [INLINE_HEIGHT_SP] in pixels, as a chat row works it out. */
    fun inlineHeightPx(context: Context): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, INLINE_HEIGHT_SP, context.resources.displayMetrics)
            .roundToInt().coerceAtLeast(1)

    // Recently asked for, so a busy chat repeating the same emotes doesn't
    // queue the same request over and over (each would only hit the cache,
    // but still costs a trip through Coil). Small and cleared when full.
    private val recent = HashSet<String>()
    private const val MAX_RECENT = 300

    /**
     * Starts loading [urls] (a message's emotes, as it arrives) into Coil's
     * caches, so by the time its chat row is drawn they're usually there
     * rather than only then fetched. Any thread.
     */
    fun prefetch(context: Context, urls: List<String>) {
        if (urls.isEmpty()) return
        val fresh = synchronized(recent) {
            if (recent.size > MAX_RECENT) recent.clear()
            urls.filter { recent.add(it) }
        }
        if (fresh.isEmpty()) return
        val loader = context.imageLoader
        val heightPx = inlineHeightPx(context)
        for (url in fresh) loader.enqueue(request(context, url, heightPx))
    }
}
