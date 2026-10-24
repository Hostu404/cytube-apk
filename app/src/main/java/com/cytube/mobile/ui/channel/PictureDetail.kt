package com.cytube.mobile.ui.channel

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Where a channel's picture has the most going on, for cropping it to the
 * top of the channel screen when its CSS doesn't say which part to keep
 * (see ChannelBackdrop): the edges in a small grey copy of it, summed along
 * each row ([rows]) and each column ([cols]). Blank sky and empty margins
 * have none, so a crop slides away from them, towards the picture's subject.
 */
internal class PictureDetail(val rows: FloatArray, val cols: FloatArray) {

    companion object {
        /** The longer side of the small copy it's worked out on. */
        private const val SAMPLE_PX = 128

        /** Worked out on a small copy of [bitmap] (a software one: its
         *  pixels are read); null if that fails. */
        fun of(bitmap: Bitmap): PictureDetail? = runCatching {
            val scale = (SAMPLE_PX.toFloat() / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1f)
            val w = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
            val h = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
            val pixels = IntArray(w * h)
            small.getPixels(pixels, 0, w, 0, 0, w, h)
            if (small !== bitmap) small.recycle()
            of(pixels, w, h)
        }.getOrNull()

        /** From [argb] pixels, [width] by [height], row by row. */
        fun of(argb: IntArray, width: Int, height: Int): PictureDetail {
            val grey = FloatArray(argb.size) { i ->
                val c = argb[i]
                0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
            }
            val rows = FloatArray(height)
            val cols = FloatArray(width)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val i = y * width + x
                    val across = if (x + 1 < width) abs(grey[i + 1] - grey[i]) else 0f
                    val down = if (y + 1 < height) abs(grey[i + width] - grey[i]) else 0f
                    rows[y] += across + down
                    cols[x] += across + down
                }
            }
            return PictureDetail(rows, cols)
        }
    }
}

/** A window this much less detailed than the best one is no real choice:
 *  the whole picture is about as busy everywhere (a texture, a gradient). */
private const val FLAT_SHARE = 0.05f

/**
 * Where along [energy] (one value per row or column) to start a window
 * [window] long (a share of the whole, 0 to 1) that takes in the most
 * detail, as a share of the whole; null when no part stands out, for the
 * middle.
 */
internal fun bestWindowStart(energy: FloatArray, window: Float): Float? {
    val n = energy.size
    if (n == 0) return null
    val k = (window * n).roundToInt().coerceIn(1, n)
    if (k >= n) return 0f
    var sum = 0f
    for (i in 0 until k) sum += energy[i]
    var best = sum
    var bestAt = 0
    var worst = sum
    for (start in 1..n - k) {
        sum += energy[start + k - 1] - energy[start - 1]
        if (sum > best) { best = sum; bestAt = start }
        if (sum < worst) worst = sum
    }
    val total = energy.sum()
    if (total <= 0f || best - worst < total * FLAT_SHARE) return null
    return bestAt.toFloat() / n
}
