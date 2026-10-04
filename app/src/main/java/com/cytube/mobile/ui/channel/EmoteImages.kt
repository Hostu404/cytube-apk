package com.cytube.mobile.ui.channel

import android.content.Context
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.util.TypedValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
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
/** True where emotes should hold still: the chat while the lights are down
 *  (ChannelScreen), where they're behind a dark layer and animating them
 *  would only cost battery. See [HoldStill]. */
internal val LocalEmotesStill = compositionLocalOf { false }

/**
 * Stops [drawable]'s animation (a GIF or animated WebP emote) on its current
 * frame while [LocalEmotesStill] says so, and starts it again after.
 */
@Composable
internal fun HoldStill(drawable: Drawable?) {
    val still = LocalEmotesStill.current
    LaunchedEffect(drawable, still) {
        val animation = drawable as? Animatable ?: return@LaunchedEffect
        if (still) animation.stop() else if (!animation.isRunning) animation.start()
    }
}

internal object EmoteImages {

    /** A chat emote's height mid-sentence. */
    const val INLINE_HEIGHT_SP = 28f

    /** A chat emote's height in a message that's only emotes. */
    const val SOLO_HEIGHT_SP = 56f

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

    /** [sp] in pixels, as a chat row works it out. */
    private fun heightPx(context: Context, sp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, context.resources.displayMetrics)
            .roundToInt().coerceAtLeast(1)

    // Recently asked for (each at its size), so a busy chat repeating the
    // same emotes doesn't queue the same request over and over (each would
    // only hit the cache, but still costs a trip through Coil). Small and
    // cleared when full.
    private val recent = HashSet<String>()
    private const val MAX_RECENT = 300

    /**
     * Starts loading [urls] (a message's emotes, as it arrives) into Coil's
     * caches, so by the time its chat row is drawn they're usually there
     * rather than only then fetched, and their shape is known (EmoteAspect)
     * so the row is laid out the right width at once. At the size the row
     * will ask for ([solo]: a message that's only emotes), as a copy
     * decoded smaller than that can't stand in for it. Any thread.
     */
    fun prefetch(context: Context, urls: List<String>, solo: Boolean) {
        if (urls.isEmpty()) return
        val sizeSp = if (solo) SOLO_HEIGHT_SP else INLINE_HEIGHT_SP
        val fresh = synchronized(recent) {
            if (recent.size > MAX_RECENT) recent.clear()
            urls.filter { recent.add("$sizeSp $it") }
        }
        if (fresh.isEmpty()) return
        val loader = context.imageLoader
        val heightPx = heightPx(context, sizeSp)
        for (url in fresh) {
            loader.enqueue(
                request(context, url, heightPx).newBuilder()
                    // Coil calls this on the main thread, where EmoteAspect lives.
                    .listener(onSuccess = { _, result ->
                        val w = result.drawable.intrinsicWidth
                        val h = result.drawable.intrinsicHeight
                        if (w > 0 && h > 0) EmoteAspect.record(url, w.toFloat() / h)
                    })
                    .build()
            )
        }
    }
}
