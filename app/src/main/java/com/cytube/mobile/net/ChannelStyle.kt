package com.cytube.mobile.net

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The parts of a channel's own CSS (Channel Settings > Edit > CSS, sent to
 * every viewer on joining as "channelCSSJS") that the app can honour:
 *
 *  - Emote modifiers. A channel adds emotes named like "/reverse" and CSS
 *    such as `[title="/reverse"] {display: none}` plus
 *    `[title="/reverse"] + .channel-emote {transform: scaleX(-1)}`: typing
 *    "/reverse pepe" hides the modifier and flips the emote after it. Read
 *    from the CSS itself, not a built-in list, so each channel's own
 *    modifiers (names, values, animations) work as they do on its page, and
 *    an emote that merely happens to be called "/flip" does nothing on a
 *    channel without a rule for it.
 *  - User list name colours (.userlist_owner, .userlist_op, ...).
 *
 * Everything else in the CSS is page layout for the website and is ignored.
 * Like the rest of net/, a channel's CSS is untrusted: parsing never throws
 * and only ever produces numbers and colours.
 */
@Immutable
class ChannelStyle(val effects: EmoteEffects, val nameColors: NameColors) {
    companion object {
        val EMPTY = ChannelStyle(EmoteEffects.NONE, NameColors.NONE)

        /** CSS longer than this is cut off before parsing. */
        private const val MAX_CSS_CHARS = 200_000

        fun parse(css: String): ChannelStyle = runCatching { CssReader(css.take(MAX_CSS_CHARS)).read() }
            .getOrDefault(EMPTY)
    }
}

/** User list name colours by rank, as ARGB, from the channel's CSS; null
 *  where it sets none. Rank classes follow CyTube's getNameColor (util.js). */
@Immutable
data class NameColors(
    val siteAdmin: Int? = null,
    val owner: Int? = null,
    val moderator: Int? = null,
    val guest: Int? = null,
    /** .userlist_item: every name without a colour of its own. */
    val everyone: Int? = null
) {
    fun forRank(rank: Double): Int? = when {
        rank >= 255 -> siteAdmin
        rank >= 3 -> owner
        rank >= 2 -> moderator
        rank <= 0.0 -> guest
        else -> null
    } ?: everyone

    companion object {
        val NONE = NameColors()
    }
}

/** A channel's emote modifiers, by the modifier emote's name ("/reverse"). */
@Immutable
class EmoteEffects(
    val modifiers: Map<String, EmoteModifier>,
    /** The channel's own emote size cap (`.channel-emote {max-height}`):
     *  what its px values in transforms are measured against. */
    val baseEmotePx: Float = DEFAULT_EMOTE_PX
) {
    companion object {
        /** CyTube's own default cap for .channel-emote. */
        const val DEFAULT_EMOTE_PX = 200f
        val NONE = EmoteEffects(emptyMap())
    }
}

/**
 * One modifier emote. [target] is how it changes the emote after it.
 * [stacks] (a negative margin-right, as "/overlay" uses) draws the emote
 * after that one on top of the target instead of beside it; [top] is any
 * style the CSS gives that second emote ("/fadeinto").
 */
@Immutable
data class EmoteModifier(
    val name: String,
    val hidden: Boolean,
    val stacks: Boolean,
    val target: EmoteStyle,
    val top: EmoteStyle = EmoteStyle()
)

/** A CSS number as written: 1.5, 20%, 4px, 90deg. */
data class CssValue(val number: Float, val unit: String) {
    fun degrees(): Float = when (unit) {
        "rad" -> Math.toDegrees(number.toDouble()).toFloat()
        "turn" -> number * 360f
        "grad" -> number * 0.9f
        else -> number
    }

    /** As a plain amount: 50% is 0.5. */
    fun amount(): Float = if (unit == "%") number / 100f else number
}

/** A CSS function call such as rotate(90deg) or hue-rotate(130deg). */
data class CssFn(val name: String, val args: List<CssValue>)

/** A CSS easing (animation-timing-function), applied per keyframe interval. */
sealed interface CssTiming {
    fun ease(x: Float): Float

    data object Linear : CssTiming {
        override fun ease(x: Float) = x
    }

    data class Bezier(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : CssTiming {
        override fun ease(x: Float): Float {
            if (x <= 0f) return 0f
            if (x >= 1f) return 1f
            // Find t with bezierX(t) = x by bisection (monotonic in x for
            // valid curves), then return bezierY(t).
            var lo = 0f
            var hi = 1f
            var t = x
            repeat(24) {
                val bx = curve(t, x1, x2)
                if (abs(bx - x) < 1e-4f) return curve(t, y1, y2)
                if (bx < x) lo = t else hi = t
                t = (lo + hi) / 2f
            }
            return curve(t, y1, y2)
        }

        private fun curve(t: Float, p1: Float, p2: Float): Float {
            val u = 1f - t
            return 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t
        }
    }

    data class Steps(val count: Int, val jumpStart: Boolean) : CssTiming {
        override fun ease(x: Float): Float {
            val n = count.coerceAtLeast(1)
            val step = if (jumpStart) ceil(x * n) else floor(x * n)
            return (step / n).coerceIn(0f, 1f)
        }
    }
}

data class CssKeyframe(
    val offset: Float,
    val transform: List<CssFn>? = null,
    val filter: List<CssFn>? = null,
    val opacity: Float? = null
)

data class CssAnimation(
    val keyframes: List<CssKeyframe>,
    val durationMs: Long,
    val delayMs: Long = 0L,
    val timing: CssTiming = CssTiming.Bezier(0.25f, 0.1f, 0.25f, 1f),
    /** Float.POSITIVE_INFINITY for `infinite`. */
    val iterations: Float = 1f,
    val fillForwards: Boolean = false,
    val alternate: Boolean = false
) {
    val isInfinite: Boolean get() = iterations.isInfinite()

    /** When it stops changing, from the start of the delay; null if never. */
    val endsAtMs: Long? get() = if (isInfinite) null else delayMs + (durationMs * iterations).toLong()
}

/** How a modifier draws an emote: CSS transform, filter, opacity, size and
 *  animation, as the channel's CSS gives them. */
@Immutable
data class EmoteStyle(
    val transform: List<CssFn> = emptyList(),
    val filter: List<CssFn> = emptyList(),
    val opacity: Float? = null,
    /** From a smaller max-height than the channel's usual ("/tiny"). */
    val sizeFactor: Float? = null,
    val originX: Float = 0.5f,
    val originY: Float = 0.5f,
    val animation: CssAnimation? = null
) {
    // Each property's keyframes, worked out once rather than on every frame
    // drawn (up to 30 a second per animated emote). Not part of equals: a
    // data class only compares its constructor's values.
    private val transformPoints by lazy { keyframePoints({ it.transform }, transform) }
    private val filterPoints by lazy { keyframePoints({ it.filter }, filter) }
    private val opacityPoints by lazy { keyframePoints({ it.opacity }, opacity ?: 1f) }

    /**
     * How the emote looks [elapsedMs] after it appeared, for a box of
     * [width] x [height] px where one of the channel's CSS px is [pxScale]
     * px on screen.
     */
    fun frameAt(elapsedMs: Long, width: Float, height: Float, pxScale: Float): EmoteFrame {
        var transformNow = transform
        var filterNow = filter
        var opacityNow = opacity ?: 1f
        val anim = animation
        val progress = anim?.let { progressAt(it, elapsedMs) }
        if (anim != null && progress != null) {
            transformNow = keyframeValue(anim, progress, transformPoints, ::lerpFns) ?: transform
            filterNow = keyframeValue(anim, progress, filterPoints, ::lerpFns) ?: filter
            opacityNow = keyframeValue(anim, progress, opacityPoints) { a, b, t -> a + (b - a) * t } ?: (opacity ?: 1f)
        }
        return buildFrame(transformNow, filterNow, opacityNow, width, height, pxScale)
    }

    /** Where in its keyframes [anim] is (0..1), or null when it isn't
     *  running (before its delay, or finished without fill-mode forwards). */
    private fun progressAt(anim: CssAnimation, elapsedMs: Long): Float? {
        if (anim.durationMs <= 0L) return null
        val t = elapsedMs - anim.delayMs
        if (t < 0L) return null
        val total = anim.durationMs * anim.iterations
        if (!anim.isInfinite && t >= total) {
            if (!anim.fillForwards) return null
            val lastIteration = ceil(anim.iterations).toLong() - 1
            val end = (anim.iterations - floor(anim.iterations)).takeIf { it > 0f } ?: 1f
            return if (anim.alternate && lastIteration % 2 == 1L) 1f - end else end
        }
        val iteration = t / anim.durationMs
        val p = (t % anim.durationMs).toFloat() / anim.durationMs
        return if (anim.alternate && iteration % 2 == 1L) 1f - p else p
    }

    /** The keyframes that set a property, as (offset, value), with [base]
     *  (the value outside the animation) at 0 and 1 where they don't. Empty
     *  when none set it, or there's no animation. */
    private fun <T : Any> keyframePoints(get: (CssKeyframe) -> T?, base: T): List<Pair<Float, T>> {
        val anim = animation ?: return emptyList()
        val points = anim.keyframes.mapNotNull { k -> get(k)?.let { k.offset to it } }.toMutableList()
        if (points.isEmpty()) return points
        if (points.first().first > 0f) points.add(0, 0f to base)
        if (points.last().first < 1f) points.add(1f to base)
        return points
    }

    private fun <T : Any> keyframeValue(
        anim: CssAnimation,
        progress: Float,
        points: List<Pair<Float, T>>,
        lerp: (T, T, Float) -> T
    ): T? {
        if (points.isEmpty()) return null
        for (i in 0 until points.size - 1) {
            val (o0, v0) = points[i]
            val (o1, v1) = points[i + 1]
            if (progress <= o1 || i == points.size - 2) {
                if (o1 <= o0) return v1
                val local = ((progress - o0) / (o1 - o0)).coerceIn(0f, 1f)
                return lerp(v0, v1, anim.timing.ease(local))
            }
        }
        return points.last().second
    }
}

/** One moment of a styled emote, ready to draw. Translation and blur are in
 *  screen px; rotations in degrees; the origin as fractions of the box. */
class EmoteFrame(
    val translationX: Float = 0f,
    val translationY: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotationZ: Float = 0f,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val alpha: Float = 1f,
    /** A 4x5 colour matrix (Android's layout, offsets 0..255), or null. */
    val colorMatrix: FloatArray? = null,
    val blurPx: Float = 0f,
    /** drop-shadow: x, y and blur, in screen px. */
    val shadow: FloatArray? = null
)

// ---- interpolation ----

private fun identityArgs(fn: CssFn): List<CssValue> = fn.args.map { v ->
    val one = when (fn.name) {
        "scale", "scalex", "scaley", "scale3d", "saturate", "brightness", "contrast", "opacity" -> 1f
        else -> 0f
    }
    CssValue(one, v.unit)
}

/** CSS interpolation of two transform or filter lists: function by function
 *  when they line up (a missing one counting as its identity), otherwise
 *  switching halfway. */
internal fun lerpFns(a: List<CssFn>, b: List<CssFn>, t: Float): List<CssFn> {
    if (a.isEmpty() && b.isEmpty()) return a
    val n = maxOf(a.size, b.size)
    val out = ArrayList<CssFn>(n)
    for (i in 0 until n) {
        val fa = a.getOrNull(i)
        val fb = b.getOrNull(i)
        val from = fa ?: CssFn(fb!!.name, identityArgs(fb))
        val to = fb ?: CssFn(fa!!.name, identityArgs(fa))
        if (from.name != to.name) return if (t < 0.5f) a else b
        val args = List(maxOf(from.args.size, to.args.size)) { j ->
            val va = from.args.getOrNull(j) ?: to.args.getOrNull(j)?.let { identityArgs(CssFn(to.name, listOf(it))).first() }
            val vb = to.args.getOrNull(j) ?: va
            CssValue(va!!.number + (vb!!.number - va.number) * t, vb.unit.ifEmpty { va.unit })
        }
        out.add(CssFn(from.name, args))
    }
    return out
}

// ---- turning a frame's CSS into numbers ----

private fun buildFrame(
    transform: List<CssFn>,
    filter: List<CssFn>,
    opacity: Float,
    width: Float,
    height: Float,
    pxScale: Float
): EmoteFrame {
    // Compose the 2D transform as an affine matrix [a c tx; b d ty], in the
    // order CSS applies the functions, then split it into what a graphics
    // layer takes (scale, rotation, translation about the origin).
    var a = 1f; var b = 0f; var c = 0f; var d = 1f; var tx = 0f; var ty = 0f
    var rotX = 0f; var rotY = 0f
    fun len(v: CssValue?, ref: Float): Float = when {
        v == null -> 0f
        v.unit == "%" -> v.number / 100f * ref
        else -> v.number * pxScale
    }
    fun translate(x: Float, y: Float) { tx += a * x + c * y; ty += b * x + d * y }
    fun linear(p: Float, q: Float, r: Float, s: Float) {
        // this = this * [p q; r s]
        val na = a * p + c * r; val nb = b * p + d * r
        val nc = a * q + c * s; val nd = b * q + d * s
        a = na; b = nb; c = nc; d = nd
    }
    fun rotate(deg: Float) {
        val rad = Math.toRadians(deg.toDouble())
        val cs = cos(rad).toFloat(); val sn = sin(rad).toFloat()
        linear(cs, -sn, sn, cs)
    }
    for (fn in transform) {
        val args = fn.args
        when (fn.name) {
            "translate" -> translate(len(args.getOrNull(0), width), len(args.getOrNull(1), height))
            "translatex" -> translate(len(args.getOrNull(0), width), 0f)
            "translatey" -> translate(0f, len(args.getOrNull(0), height))
            "translate3d" -> translate(len(args.getOrNull(0), width), len(args.getOrNull(1), height))
            "scale", "scale3d" -> {
                val sx = args.getOrNull(0)?.amount() ?: 1f
                linear(sx, 0f, 0f, args.getOrNull(1)?.amount() ?: sx)
            }
            "scalex" -> linear(args.getOrNull(0)?.amount() ?: 1f, 0f, 0f, 1f)
            "scaley" -> linear(1f, 0f, 0f, args.getOrNull(0)?.amount() ?: 1f)
            "rotate", "rotatez" -> rotate(args.getOrNull(0)?.degrees() ?: 0f)
            "rotatex" -> rotX += args.getOrNull(0)?.degrees() ?: 0f
            "rotatey" -> rotY += args.getOrNull(0)?.degrees() ?: 0f
        }
    }
    var scaleX = hypot(a, b)
    var rotation: Float
    val scaleY: Float
    if (scaleX < 1e-6f) {
        scaleX = 0f; rotation = 0f; scaleY = d
    } else {
        rotation = Math.toDegrees(atan2(b, a).toDouble()).toFloat()
        // A mirror image reads as a turn of over 90 degrees plus a flip;
        // keep it as the flip it was written as (scaleX(-1)), which looks
        // the same but doesn't spin when animated.
        if (abs(rotation) > 90f) {
            scaleX = -scaleX
            rotation -= if (rotation > 0f) 180f else -180f
        }
        scaleY = (a * d - b * c) / scaleX
    }

    // Filters: CSS applies them left to right, so the combined colour matrix
    // is the last one's times ... times the first's.
    var matrix: FloatArray? = null
    var alpha = opacity
    var blur = 0f
    var shadow: FloatArray? = null
    for (fn in filter) {
        val v = fn.args.firstOrNull()
        val m: FloatArray? = when (fn.name) {
            "grayscale" -> grayscale((v?.amount() ?: 1f).coerceIn(0f, 1f))
            "sepia" -> sepia((v?.amount() ?: 1f).coerceIn(0f, 1f))
            "saturate" -> saturate(v?.amount() ?: 1f)
            "hue-rotate" -> hueRotate(v?.degrees() ?: 0f)
            "invert" -> invert((v?.amount() ?: 1f).coerceIn(0f, 1f))
            "brightness" -> scaleRgb(v?.amount() ?: 1f, 0f)
            "contrast" -> (v?.amount() ?: 1f).let { k -> scaleRgb(k, 127.5f * (1f - k)) }
            "opacity" -> { alpha *= (v?.amount() ?: 1f).coerceIn(0f, 1f); null }
            "blur" -> { blur = (v?.number ?: 0f) * pxScale; null }
            "drop-shadow" -> {
                val args = fn.args
                shadow = floatArrayOf(
                    (args.getOrNull(0)?.number ?: 0f) * pxScale,
                    (args.getOrNull(1)?.number ?: 0f) * pxScale,
                    (args.getOrNull(2)?.number ?: 0f) * pxScale
                )
                null
            }
            else -> null
        }
        if (m != null) matrix = if (matrix == null) m else multiply(m, matrix)
    }
    // Kept within reach of the emote's own place in the line: a channel's
    // CSS mustn't be able to cover the chat or the message box with an
    // image (scale(100), translate(5000px)), or hand the renderer values it
    // can't use (1e40, NaN).
    val reach = MAX_REACH * maxOf(width, height, 1f)
    fun finite(v: Float, default: Float) = if (v.isFinite()) v else default
    val safeMatrix = matrix?.takeIf { m -> m.all { it.isFinite() && abs(it) < 1e6f } }
    val safeShadow = shadow?.takeIf { sh -> sh.all { it.isFinite() } }?.let { sh ->
        floatArrayOf(sh[0].coerceIn(-height, height), sh[1].coerceIn(-height, height), sh[2].coerceIn(0f, height / 2f))
    }
    return EmoteFrame(
        translationX = finite(tx, 0f).coerceIn(-reach, reach),
        translationY = finite(ty, 0f).coerceIn(-reach, reach),
        scaleX = finite(scaleX, 1f).coerceIn(-MAX_SCALE, MAX_SCALE),
        scaleY = finite(scaleY, 1f).coerceIn(-MAX_SCALE, MAX_SCALE),
        rotationZ = finite(rotation, 0f) % 360f,
        rotationX = finite(rotX, 0f) % 360f,
        rotationY = finite(rotY, 0f) % 360f,
        alpha = finite(alpha, 1f).coerceIn(0f, 1f),
        colorMatrix = safeMatrix,
        blurPx = finite(blur, 0f).coerceIn(0f, height / 2f),
        shadow = safeShadow
    )
}

/** How far (in emote sizes) and how big an emote may be drawn. */
private const val MAX_REACH = 4f
private const val MAX_SCALE = 4f

// The filter matrices from the CSS Filter Effects spec, in Android's 4x5
// layout (rows R, G, B, A; columns R, G, B, A, offset 0..255).

private fun rgb3(m: FloatArray, offset: Float = 0f) = floatArrayOf(
    m[0], m[1], m[2], 0f, offset,
    m[3], m[4], m[5], 0f, offset,
    m[6], m[7], m[8], 0f, offset,
    0f, 0f, 0f, 1f, 0f
)

private fun grayscale(amount: Float): FloatArray {
    val k = 1f - amount
    return rgb3(floatArrayOf(
        0.2126f + 0.7874f * k, 0.7152f - 0.7152f * k, 0.0722f - 0.0722f * k,
        0.2126f - 0.2126f * k, 0.7152f + 0.2848f * k, 0.0722f - 0.0722f * k,
        0.2126f - 0.2126f * k, 0.7152f - 0.7152f * k, 0.0722f + 0.9278f * k
    ))
}

private fun sepia(amount: Float): FloatArray {
    val k = 1f - amount
    return rgb3(floatArrayOf(
        0.393f + 0.607f * k, 0.769f - 0.769f * k, 0.189f - 0.189f * k,
        0.349f - 0.349f * k, 0.686f + 0.314f * k, 0.168f - 0.168f * k,
        0.272f - 0.272f * k, 0.534f - 0.534f * k, 0.131f + 0.869f * k
    ))
}

private fun saturate(s: Float) = rgb3(floatArrayOf(
    0.213f + 0.787f * s, 0.715f - 0.715f * s, 0.072f - 0.072f * s,
    0.213f - 0.213f * s, 0.715f + 0.285f * s, 0.072f - 0.072f * s,
    0.213f - 0.213f * s, 0.715f - 0.715f * s, 0.072f + 0.928f * s
))

private fun hueRotate(deg: Float): FloatArray {
    val rad = Math.toRadians(deg.toDouble())
    val cs = cos(rad).toFloat(); val sn = sin(rad).toFloat()
    return rgb3(floatArrayOf(
        0.213f + cs * 0.787f - sn * 0.213f, 0.715f - cs * 0.715f - sn * 0.715f, 0.072f - cs * 0.072f + sn * 0.928f,
        0.213f - cs * 0.213f + sn * 0.143f, 0.715f + cs * 0.285f + sn * 0.140f, 0.072f - cs * 0.072f - sn * 0.283f,
        0.213f - cs * 0.213f - sn * 0.787f, 0.715f - cs * 0.715f + sn * 0.715f, 0.072f + cs * 0.928f + sn * 0.072f
    ))
}

private fun invert(amount: Float) = scaleRgb(1f - 2f * amount, 255f * amount)

private fun scaleRgb(k: Float, offset: Float) = floatArrayOf(
    k, 0f, 0f, 0f, offset,
    0f, k, 0f, 0f, offset,
    0f, 0f, k, 0f, offset,
    0f, 0f, 0f, 1f, 0f
)

/** [m] applied after [n]: the 4x5 product m * n. */
private fun multiply(m: FloatArray, n: FloatArray): FloatArray {
    val out = FloatArray(20)
    for (row in 0 until 4) {
        for (col in 0 until 5) {
            var sum = if (col == 4) m[row * 5 + 4] else 0f
            for (k in 0 until 4) sum += m[row * 5 + k] * n[k * 5 + col]
            out[row * 5 + col] = sum
        }
    }
    return out
}

// ---- reading the CSS ----

private class CssReader(css: String) {
    private val text = stripComments(css)
    private val keyframes = HashMap<String, List<CssKeyframe>>()
    private val rules = ArrayList<Pair<List<String>, Map<String, String>>>()

    fun read(): ChannelStyle {
        collect()
        val self = LinkedHashMap<String, MutableMap<String, String>>()
        val next = LinkedHashMap<String, MutableMap<String, String>>()
        val nextNext = LinkedHashMap<String, MutableMap<String, String>>()
        val colors = HashMap<String, Int>()
        var baseEmotePx = EmoteEffects.DEFAULT_EMOTE_PX
        for ((selectors, decls) in rules) {
            for (selector in selectors) {
                MOD_SELF.matchEntire(selector)?.let { self.getOrPut(it.groupValues[1]) { LinkedHashMap() }.putAll(decls) }
                MOD_NEXT.matchEntire(selector)?.let { next.getOrPut(it.groupValues[1]) { LinkedHashMap() }.putAll(decls) }
                MOD_NEXT_NEXT.matchEntire(selector)?.let { nextNext.getOrPut(it.groupValues[1]) { LinkedHashMap() }.putAll(decls) }
                NAME_CLASS.matchEntire(selector)?.let { m ->
                    decls["color"]?.let(::parseColor)?.let { colors[m.groupValues[1]] = it }
                }
                if (selector == ".channel-emote" || selector == "img.channel-emote") {
                    decls["max-height"]?.let(::px)?.takeIf { it > 0f }?.let { baseEmotePx = it }
                }
            }
        }
        val modifiers = HashMap<String, EmoteModifier>()
        for (name in self.keys + next.keys) {
            if (name in modifiers) continue
            if (modifiers.size >= MAX_MODIFIERS) break
            val hidden = self[name]?.get("display") == "none"
            val nextDecls = next[name].orEmpty()
            if (!hidden && nextDecls.isEmpty()) continue
            // Rules that move the emote out of the line altogether (a giant
            // faded backdrop behind the chat, say) have no sensible
            // equivalent inside a line of chat on a phone, so the target is
            // just drawn plainly; the modifier is still hidden.
            val unsupported = nextDecls.containsKey("z-index") || nextDecls.containsKey("bottom") ||
                nextDecls.containsKey("top") || nextDecls["position"].let { it == "absolute" || it == "fixed" }
            val stacks = !unsupported && (nextDecls["margin-right"]?.let(::px) ?: 0f) < 0f
            modifiers[name] = EmoteModifier(
                name = name,
                hidden = hidden,
                stacks = stacks,
                target = if (unsupported) EmoteStyle() else style(nextDecls, baseEmotePx),
                top = if (stacks) style(nextNext[name].orEmpty(), baseEmotePx) else EmoteStyle()
            )
        }
        val nameColors = NameColors(
            siteAdmin = colors["siteadmin"],
            owner = colors["owner"],
            moderator = colors["op"],
            guest = colors["guest"],
            everyone = colors["item"]
        )
        return ChannelStyle(EmoteEffects(modifiers, baseEmotePx), nameColors)
    }

    /** Splits the stylesheet into rules and @keyframes blocks. */
    private fun collect() {
        var i = 0
        while (i < text.length) {
            if (text[i].isWhitespace()) { i++; continue }
            if (text[i] == '@') {
                // An at-rule ends at whichever of ; or { comes first; found
                // by walking forward, so a run of them stays linear.
                var j = i
                while (j < text.length && text[j] != ';' && text[j] != '{') j++
                if (j >= text.length || text[j] == ';') {
                    i = j + 1
                    continue
                }
                val brace = j
                val end = matchingBrace(brace)
                val prelude = text.substring(i, brace).trim()
                if (prelude.startsWith("@keyframes") || prelude.startsWith("@-webkit-keyframes")) {
                    val name = prelude.substringAfter(' ').trim()
                    if (name.isNotEmpty()) keyframes[name] = readKeyframes(text.substring(brace + 1, end))
                }
                i = end + 1
                continue
            }
            val brace = text.indexOf('{', i)
            if (brace < 0) break
            val close = text.indexOf('}', brace).let { if (it < 0) text.length else it }
            val selectors = text.substring(i, brace).split(',')
                .filter { it.length <= MAX_SELECTOR_CHARS }
                .map(::normalizeSelector).filter { it.isNotEmpty() }
            rules.add(selectors to declarations(text.substring(brace + 1, close)))
            i = close + 1
        }
    }

    private fun matchingBrace(open: Int): Int {
        var depth = 0
        for (j in open until text.length) {
            when (text[j]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return j
            }
        }
        return text.length
    }

    private fun readKeyframes(body: String): List<CssKeyframe> {
        val frames = ArrayList<CssKeyframe>()
        var pos = 0
        while (pos < body.length && frames.size < MAX_KEYFRAMES) {
            val open = body.indexOf('{', pos)
            if (open < 0) break
            val close = body.indexOf('}', open).let { if (it < 0) body.length else it }
            val selector = body.substring(pos, open)
            pos = close + 1
            if (selector.length > MAX_SELECTOR_CHARS) continue
            val decls = declarations(body.substring(open + 1, close))
            val transform = decls["transform"]?.let(::functions)
            val filter = decls["filter"]?.let(::functions)
            val opacity = decls["opacity"]?.let(::amount)
            for (sel in selector.split(',')) {
                val offset = when (val s = sel.trim().lowercase()) {
                    "from" -> 0f
                    "to" -> 1f
                    else -> s.removeSuffix("%").toFloatOrNull()?.div(100f)
                } ?: continue
                frames.add(CssKeyframe(offset.coerceIn(0f, 1f), transform, filter, opacity))
            }
        }
        return frames.sortedBy { it.offset }
    }

    private fun style(decls: Map<String, String>, baseEmotePx: Float): EmoteStyle {
        val maxHeight = (decls["max-height"] ?: decls["height"])?.let(::px)
        val (ox, oy) = decls["transform-origin"]?.let(::origin) ?: (0.5f to 0.5f)
        return EmoteStyle(
            transform = decls["transform"]?.let(::functions).orEmpty(),
            filter = decls["filter"]?.let(::functions).orEmpty(),
            opacity = decls["opacity"]?.let(::amount)?.coerceIn(0f, 1f),
            sizeFactor = maxHeight?.takeIf { it > 0f && it < baseEmotePx }?.let { it / baseEmotePx },
            originX = ox,
            originY = oy,
            animation = animation(decls)
        )
    }

    private fun animation(decls: Map<String, String>): CssAnimation? {
        var name: String? = null
        var duration: Long? = null
        var delay = 0L
        var easing: CssTiming = CssTiming.Bezier(0.25f, 0.1f, 0.25f, 1f)
        var iterations = 1f
        var forwards = false
        var alternate = false
        decls["animation"]?.let { shorthand ->
            // Several animations, comma separated: only the first is used.
            val first = shorthand.split(TOP_LEVEL_COMMA).first()
            for (token in ANIMATION_TOKEN.findAll(first).map { it.value }) {
                val ms = time(token)
                when {
                    ms != null -> if (duration == null) duration = ms else delay = ms
                    token == "infinite" -> iterations = Float.POSITIVE_INFINITY
                    token.toFloatOrNull() != null -> iterations = token.toFloat()
                    token == "forwards" || token == "both" -> forwards = true
                    token == "alternate" || token == "alternate-reverse" -> alternate = true
                    token in IGNORED_ANIMATION_WORDS -> Unit
                    timing(token) != null -> easing = timing(token)!!
                    else -> name = token
                }
            }
        }
        decls["animation-name"]?.let { name = it.split(',').first().trim() }
        decls["animation-duration"]?.let { time(it.split(',').first().trim())?.let { t -> duration = t } }
        decls["animation-delay"]?.let { time(it.split(',').first().trim())?.let { t -> delay = t } }
        decls["animation-timing-function"]?.let { timing(it.trim())?.let { t -> easing = t } }
        decls["animation-iteration-count"]?.let {
            iterations = if (it.trim() == "infinite") Float.POSITIVE_INFINITY else it.trim().toFloatOrNull() ?: iterations
        }
        decls["animation-fill-mode"]?.let { forwards = it.contains("forwards") || it.contains("both") }
        decls["animation-direction"]?.let { alternate = it.contains("alternate") }
        val frames = name?.let { keyframes[it] } ?: return null
        val length = duration ?: return null
        if (frames.isEmpty() || length <= 0L) return null
        // Within reason: no faster than 20 changes a second, no longer than
        // an hour, a delay of at most a minute.
        return CssAnimation(
            frames,
            length.coerceIn(MIN_ANIMATION_MS, MAX_ANIMATION_MS),
            delay.coerceIn(0L, MAX_DELAY_MS),
            easing,
            if (iterations.isNaN() || iterations <= 0f) 1f else iterations,
            forwards,
            alternate
        )
    }
}

// ---- CSS value helpers ----

// Limits on what's read from a channel's CSS. Everything below walks the
// text once rather than through patterns that can backtrack, so CSS made to
// be slow to read (a long run of unclosed comments, say) still reads fast.
private const val MAX_SELECTOR_CHARS = 300
private const val MAX_VALUE_CHARS = 1_000
private const val MAX_TOKEN_CHARS = 32
private const val MAX_FUNCTIONS = 16
private const val MAX_KEYFRAMES = 64
private const val MAX_MODIFIERS = 300
private const val MIN_ANIMATION_MS = 50L
private const val MAX_ANIMATION_MS = 3_600_000L
private const val MAX_DELAY_MS = 60_000L

/** The CSS with its comments taken out (an unclosed one runs to the end). */
private fun stripComments(css: String): String {
    val out = StringBuilder(css.length)
    var i = 0
    while (i < css.length) {
        val start = css.indexOf("/*", i)
        if (start < 0) { out.append(css, i, css.length); break }
        out.append(css, i, start).append(' ')
        val end = css.indexOf("*/", start + 2)
        if (end < 0) break
        i = end + 2
    }
    return out.toString()
}
private val NUMBER = Regex("^(-?\\d*\\.?\\d+(?:e-?\\d+)?)([a-z%]*)$")
private val ANIMATION_TOKEN = Regex("[a-zA-Z-]+\\([^)]*\\)|\\S+")
private val IGNORED_ANIMATION_WORDS = setOf(
    "normal", "reverse", "none", "backwards", "running", "paused"
)

private const val TITLE = """(?:img)?(?:\.channel-emote)?\[title="([^"]+)"]"""
private const val EMOTE = """(?:img)?\.channel-emote"""
private val MOD_SELF = Regex("^$TITLE$")
private val MOD_NEXT = Regex("^$TITLE\\+$EMOTE$")
private val MOD_NEXT_NEXT = Regex("^$TITLE\\+$EMOTE\\+$EMOTE$")
private val NAME_CLASS = Regex("^\\.userlist_(siteadmin|owner|op|guest|item)$")

// Built once here rather than on every use: some run for every selector in
// the stylesheet, and a channel's can have thousands.
private val COMBINATOR_SPACE = Regex("\\s*([+>~])\\s*")
private val WHITESPACE = Regex("\\s+")
/** A comma not inside brackets: between animations, not in cubic-bezier(). */
private val TOP_LEVEL_COMMA = Regex(",(?![^()]*\\))")
private val COLOR_ARG_SEPARATOR = Regex("[,\\s/]+")

private fun normalizeSelector(s: String): String = s.trim()
    .replace('\'', '"')
    .replace(COMBINATOR_SPACE, "$1")
    .replace(WHITESPACE, " ")

private fun declarations(body: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (decl in body.split(';')) {
        val colon = decl.indexOf(':')
        if (colon <= 0) continue
        val prop = decl.substring(0, colon).trim().lowercase()
        if (decl.length - colon > MAX_VALUE_CHARS) continue
        val value = decl.substring(colon + 1).replace("!important", "").trim()
        if (prop.isNotEmpty() && value.isNotEmpty()) out[prop] = value
    }
    return out
}

/** scale(1.5, 0.8) rotate(90deg) and the like, read left to right. */
private fun functions(value: String): List<CssFn> {
    if (value.trim() == "none") return emptyList()
    val out = ArrayList<CssFn>()
    var i = 0
    while (i < value.length && out.size < MAX_FUNCTIONS) {
        if (!value[i].isLetter()) { i++; continue }
        val start = i
        while (i < value.length && (value[i].isLetterOrDigit() || value[i] == '-')) i++
        if (i >= value.length || value[i] != '(') continue
        val close = value.indexOf(')', i)
        if (close < 0) break
        out.add(
            CssFn(
                value.substring(start, i).lowercase(),
                value.substring(i + 1, close).split(',', ' ', '\t', '\n').mapNotNull(::cssValue)
            )
        )
        i = close + 1
    }
    return out
}

private fun cssValue(token: String): CssValue? {
    if (token.length > MAX_TOKEN_CHARS) return null
    val m = NUMBER.matchEntire(token.trim().lowercase()) ?: return null
    return CssValue(m.groupValues[1].toFloatOrNull() ?: return null, m.groupValues[2])
}

private fun amount(token: String): Float? = cssValue(token)?.amount()

private fun px(token: String): Float? = cssValue(token.trim().split(WHITESPACE).first())?.number

private fun time(token: String): Long? {
    val t = token.trim().lowercase()
    return when {
        t.endsWith("ms") -> t.removeSuffix("ms").toFloatOrNull()?.toLong()
        t.endsWith("s") -> t.removeSuffix("s").toFloatOrNull()?.times(1000f)?.toLong()
        else -> null
    }
}

private fun timing(token: String): CssTiming? = when (val t = token.trim().lowercase()) {
    "linear" -> CssTiming.Linear
    "ease" -> CssTiming.Bezier(0.25f, 0.1f, 0.25f, 1f)
    "ease-in" -> CssTiming.Bezier(0.42f, 0f, 1f, 1f)
    "ease-out" -> CssTiming.Bezier(0f, 0f, 0.58f, 1f)
    "ease-in-out" -> CssTiming.Bezier(0.42f, 0f, 0.58f, 1f)
    "step-start" -> CssTiming.Steps(1, jumpStart = true)
    "step-end" -> CssTiming.Steps(1, jumpStart = false)
    else -> when {
        t.startsWith("cubic-bezier(") -> {
            val n = t.substringAfter('(').substringBefore(')').split(',').mapNotNull { it.trim().toFloatOrNull() }
            if (n.size == 4) CssTiming.Bezier(n[0], n[1], n[2], n[3]) else null
        }
        t.startsWith("steps(") -> {
            val parts = t.substringAfter('(').substringBefore(')').split(',').map { it.trim() }
            parts.firstOrNull()?.toIntOrNull()?.let { n ->
                CssTiming.Steps(n, jumpStart = parts.getOrNull(1) in setOf("start", "jump-start"))
            }
        }
        else -> null
    }
}

private fun origin(value: String): Pair<Float, Float> {
    val parts = value.trim().lowercase().split(WHITESPACE)
    fun one(p: String?, horizontal: Boolean): Float = when (p) {
        null, "center" -> 0.5f
        "left" -> if (horizontal) 0f else 0.5f
        "right" -> if (horizontal) 1f else 0.5f
        "top" -> if (horizontal) 0.5f else 0f
        "bottom" -> if (horizontal) 0.5f else 1f
        else -> cssValue(p)?.let { if (it.unit == "%") it.number / 100f else null } ?: 0.5f
    }
    // "top left" style keyword order is accepted either way round.
    val swap = parts.firstOrNull() in setOf("top", "bottom")
    val x = one(if (swap) parts.getOrNull(1) else parts.getOrNull(0), true)
    val y = one(if (swap) parts.getOrNull(0) else parts.getOrNull(1), false)
    return x to y
}

private val NAMED_COLORS = mapOf(
    "white" to 0xFFFFFF, "black" to 0x000000, "red" to 0xFF0000, "green" to 0x008000,
    "blue" to 0x0000FF, "yellow" to 0xFFFF00, "orange" to 0xFFA500, "purple" to 0x800080,
    "pink" to 0xFFC0CB, "gold" to 0xFFD700, "cyan" to 0x00FFFF, "magenta" to 0xFF00FF,
    "lime" to 0x00FF00, "grey" to 0x808080, "gray" to 0x808080, "darkgrey" to 0xA9A9A9,
    "darkgray" to 0xA9A9A9, "lightgrey" to 0xD3D3D3, "lightgray" to 0xD3D3D3,
    "silver" to 0xC0C0C0, "violet" to 0xEE82EE, "crimson" to 0xDC143C, "teal" to 0x008080
)

/** #rgb, #rgba, #rrggbb, #rrggbbaa, rgb()/rgba() (numbers or
 *  percentages) or a common colour name, as opaque ARGB; null for anything
 *  else. */
private fun parseColor(value: String): Int? {
    val v = value.trim().lowercase()
    if (v.startsWith("#")) {
        val hex = v.drop(1)
        val rgb = when (hex.length) {
            3, 4 -> hex.take(3).map { "$it$it" }.joinToString("")
            6, 8 -> hex.take(6)
            else -> return null
        }
        return rgb.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
    }
    if (v.startsWith("rgb")) {
        val n = v.substringAfter('(').substringBefore(')').split(COLOR_ARG_SEPARATOR)
            .filter { it.isNotBlank() }
            .take(3)
            .mapNotNull { part ->
                val t = part.trim()
                // 100% is full strength, as 255 is.
                if (t.endsWith("%")) t.removeSuffix("%").toFloatOrNull()?.times(2.55f)
                else t.toFloatOrNull()
            }
        if (n.size < 3) return null
        val (r, g, b) = n.map { it.roundToInt().coerceIn(0, 255) }
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    return NAMED_COLORS[v]?.let { (0xFF000000 or it.toLong()).toInt() }
}
