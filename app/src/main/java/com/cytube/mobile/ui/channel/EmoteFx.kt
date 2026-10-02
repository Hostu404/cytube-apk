package com.cytube.mobile.ui.channel

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import coil.compose.rememberAsyncImagePainter
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Dimension
import coil.size.Precision
import com.cytube.mobile.net.EmoteFrame
import com.cytube.mobile.net.EmoteStyle
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
    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(context)
            .data(url)
            .size(coil.size.Size(Dimension.Undefined, Dimension(heightPx.coerceAtLeast(1))))
            .precision(Precision.INEXACT)
            .build(),
        imageLoader = context.imageLoader,
        onSuccess = { state ->
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
    // drawing, so each frame redraws this emote alone.
    val startedAt = rememberSaveable { System.currentTimeMillis() }
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val anim = style.animation
    if (anim != null) {
        LaunchedEffect(anim) {
            val endsAt = anim.endsAtMs
            while (true) {
                withFrameMillis { }
                now.longValue = System.currentTimeMillis()
                if (endsAt != null && now.longValue - startedAt > endsAt) break
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
