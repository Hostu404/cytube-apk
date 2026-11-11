package com.cytube.mobile.ui.channel

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp

/**
 * With sync off, a soft amber glow just under the video (the phone's
 * windowed layout, see ChannelScreen): plain at a glance that you're
 * watching on your own rather than with the channel, with nothing to read
 * or tap. The amber is CyTube Plus's, what it marks the video you're
 * watching with when sync is off there.
 *
 * A faint hairline along the bottom of [video] (where it is on screen) and
 * a soft glow fading out below it: there to notice, not to look at. Drawn
 * over the page, so it takes no room and nothing moves for it.
 * [strength] runs from 0 (none) to 1 (full), so it can fade in and out as
 * sync is switched.
 */
internal fun DrawScope.drawSyncOffGlow(video: Rect, strength: Float) {
    if (strength <= 0f || video.isEmpty) return
    val glow = SYNC_OFF_GLOW_DP.dp.toPx()
    drawRect(
        brush = Brush.verticalGradient(
            0f to SyncOffAmber.copy(alpha = SYNC_OFF_GLOW_ALPHA * strength),
            1f to Color.Transparent,
            startY = video.bottom,
            endY = video.bottom + glow
        ),
        topLeft = Offset(video.left, video.bottom),
        size = Size(video.width, glow)
    )
    drawRect(
        color = SyncOffAmber.copy(alpha = SYNC_OFF_LINE_ALPHA * strength),
        topLeft = Offset(video.left, video.bottom),
        size = Size(video.width, SYNC_OFF_LINE_DP.dp.toPx())
    )
}

/** CyTube Plus's amber for sync off (#e5b25a in its content.css). */
internal val SyncOffAmber = Color(0xFFE5B25A)

/** How long the glow takes to fade in or out as sync is switched. */
internal const val SYNC_OFF_GLOW_FADE_MS = 400

private const val SYNC_OFF_LINE_DP = 1f
private const val SYNC_OFF_LINE_ALPHA = 0.4f
private const val SYNC_OFF_GLOW_DP = 12f
private const val SYNC_OFF_GLOW_ALPHA = 0.16f
