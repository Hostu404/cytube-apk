package com.cytube.mobile.ui.channel

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.IntOffset
import coil.compose.rememberAsyncImagePainter
import coil.imageLoader
import com.cytube.mobile.net.EmoteFrame
import com.cytube.mobile.net.EmoteStyle
import com.cytube.mobile.net.TextLook
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An emote drawn with one of the channel's modifiers (ChatHtml.EmoteFx):
 * the emote the modifier acts on, and for a stacking modifier ("/overlay
 * pepe hat") the second emote drawn over it, both centred in the space the
 * line of text keeps for them. [heightPx] is an ordinary emote's height,
 * which the channel's CSS px values are scaled to.
 */
@Composable
internal fun FxEmote(fx: ChatHtml.EmoteFx, heightPx: Int, onAspect: (String, Float) -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        StyledEmote(fx.urls[0], fx.modifier.target, fx.baseEmotePx, heightPx, onAspect)
        fx.urls.getOrNull(1)?.let { StyledEmote(it, fx.modifier.top, fx.baseEmotePx, heightPx, onAspect) }
    }
}

@Composable
private fun StyledEmote(
    url: String,
    style: EmoteStyle,
    baseEmotePx: Float,
    heightPx: Int,
    onAspect: (String, Float) -> Unit
) {
    val context = LocalContext.current
    var drawable by remember { mutableStateOf<android.graphics.drawable.Drawable?>(null) }
    HoldStill(drawable)
    val painter = rememberAsyncImagePainter(
        // The same request as a plain emote's, so one cached copy serves both.
        model = EmoteImages.request(context, url, heightPx),
        imageLoader = context.imageLoader,
        onSuccess = { state ->
            drawable = state.result.drawable
            val size = state.painter.intrinsicSize
            if (size.width > 0f && size.height > 0f && size.width.isFinite() && size.height.isFinite()) {
                onAspect(url, size.width / size.height)
            }
        }
    )
    val pxScale = heightPx / baseEmotePx.coerceAtLeast(1f)

    // When it first appeared, kept while it's scrolled off and back (a
    // one-off animation such as "/shrink" doesn't replay), and a clock that
    // ticks only while something is moving. The clock is read only while
    // drawing, so each tick redraws this emote alone, and it ticks at most
    // 30 times a second (MIN_TICK_MS): smooth enough for these effects, and
    // half the redrawing of following the screen's own 60+ a second, which
    // matters with a chat full of them beside a playing video.
    val startedAt = rememberSaveable { System.currentTimeMillis() }
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val anim = style.animation
    // Held still (no ticks) while LocalEmotesStill, like a GIF: see HoldStill.
    val holdStill = LocalEmotesStill.current
    if (anim != null) {
        LaunchedEffect(anim, holdStill) {
            if (holdStill) return@LaunchedEffect
            val endsAt = anim.endsAtMs
            while (true) {
                withFrameMillis { }
                val t = System.currentTimeMillis()
                if (t - now.longValue < MIN_TICK_MS) continue
                now.longValue = t
                if (endsAt != null && t - startedAt > endsAt) break
            }
        }
    }
    val still: EmoteFrame? = remember(style, heightPx) {
        if (anim == null) style.frameAt(0L, heightPx.toFloat(), heightPx.toFloat(), pxScale) else null
    }
    fun frame(width: Float, height: Float): EmoteFrame =
        still ?: style.frameAt(now.longValue - startedAt, width, height, pxScale)

    val localDensity = LocalDensity.current
    // Blur and drop shadows need Android 12's render effects; on older
    // phones the emote is simply drawn without them.
    val effectsSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val blurPx = if (effectsSupported) still?.blurPx ?: 0f else 0f
    val shadow = if (effectsSupported) still?.shadow else null
    val shadowColor = LocalContentColor.current

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val f = frame(size.width, size.height)
                translationX = f.translationX
                translationY = f.translationY
                scaleX = f.scaleX
                scaleY = f.scaleY
                rotationZ = f.rotationZ
                rotationX = f.rotationX
                rotationY = f.rotationY
                alpha = f.alpha
                transformOrigin = TransformOrigin(style.originX, style.originY)
                cameraDistance = 12f * density
            }
    ) {
        if (shadow != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .offset { IntOffset(shadow[0].roundToInt(), shadow[1].roundToInt()) }
                    .then(
                        if (shadow[2] > 0f) {
                            Modifier.blur(with(localDensity) { (shadow[2] / 2f).toDp() }, BlurredEdgeTreatment.Unbounded)
                        } else Modifier
                    )
                    .drawBehind { drawFit(painter, ColorFilter.tint(shadowColor, BlendMode.SrcIn)) }
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (blurPx > 0f) Modifier.blur(with(localDensity) { blurPx.toDp() }, BlurredEdgeTreatment.Unbounded)
                    else Modifier
                )
                .drawBehind {
                    val matrix = frame(size.width, size.height).colorMatrix
                    drawFit(painter, matrix?.let { ColorFilter.colorMatrix(ColorMatrix(it)) })
                }
        )
    }
}

/** The shortest time between redraws of an animated emote: 30 a second. */
private const val MIN_TICK_MS = 33L

/** The shortest time between recolourings of text whose colour changes: 15
 *  a second, smooth enough for a colour cycle, and each is a fresh layout
 *  of its line, unlike an emote's redraw. */
private const val TEXT_TICK_MS = 66L

/**
 * [text] with its text whose colour changes over time (a channel's rainbow,
 * see ChatHtml.TEXT_FX_TAG) coloured for now, from [fx], readable. It ticks
 * at most 15 times a second while on screen and [animate] is on (a chat
 * only animates its newest messages: a screenful of rainbows recolouring at
 * once would cost the video its smoothness), only until every animation in
 * it has finished, and holds still while emotes do (LocalEmotesStill). Not
 * animating, it shows each one's first colour.
 */
@Composable
internal fun animatedChatText(text: AnnotatedString, fx: List<TextLook>, animate: Boolean = true): AnnotatedString {
    if (fx.isEmpty()) return text
    val ranges = remember(text) { text.getStringAnnotations(ChatHtml.TEXT_FX_TAG, 0, text.length) }
    val startedAt = rememberSaveable { System.currentTimeMillis() }
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val holdStill = LocalEmotesStill.current || !animate
    LaunchedEffect(fx, holdStill) {
        if (holdStill) return@LaunchedEffect
        val endsAt = fx.map { it.animation?.endsAtMs }.let { ends -> if (null in ends) null else ends.filterNotNull().maxOrNull() }
        while (true) {
            withFrameMillis { }
            val t = System.currentTimeMillis()
            if (t - now.longValue < TEXT_TICK_MS) continue
            now.longValue = t
            if (endsAt != null && t - startedAt > endsAt) break
        }
    }
    val elapsed = if (animate) now.longValue - startedAt else 0L
    return remember(text, fx, elapsed) {
        buildAnnotatedString {
            append(text)
            for (range in ranges) {
                val look = range.item.toIntOrNull()?.let { fx.getOrNull(it) } ?: continue
                val color = look.colorAt(elapsed) ?: continue
                addStyle(SpanStyle(color = Color(ChatHtml.readableColor(look, color))), range.start, range.end)
            }
        }
    }
}

/** Draws [painter] fitted and centred in this box, as ContentScale.Fit. */
private fun DrawScope.drawFit(painter: Painter, colorFilter: ColorFilter?) {
    val intrinsic = painter.intrinsicSize
    if (intrinsic.isUnspecified || intrinsic.width <= 0f || intrinsic.height <= 0f) {
        with(painter) { draw(size, colorFilter = colorFilter) }
        return
    }
    val scale = min(size.width / intrinsic.width, size.height / intrinsic.height)
    val w = intrinsic.width * scale
    val h = intrinsic.height * scale
    translate((size.width - w) / 2f, (size.height - h) / 2f) {
        with(painter) { draw(Size(w, h), colorFilter = colorFilter) }
    }
}
