package com.slidedify.feature.swipe

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slidedify.core.designsystem.SkipColor
import com.slidedify.core.designsystem.color
import com.slidedify.core.domain.gesture.Sector
import com.slidedify.core.domain.gesture.SectorLayout
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A swipe zone as seen from the centre of the screen. */
internal data class Zone(val sector: Sector, val centerDegrees: Float, val halfWidth: Float, val color: Color, val label: String)

internal fun swipeZones(targets: List<SlotTarget>, layout: SectorLayout): List<Zone> {
    val half = layout.sectorWidth / 2f
    return targets.map { Zone(Sector.Destination(it.slot), layout.centerOf(it.slot), half, it.slot.color(), it.label) } +
        Zone(Sector.Skip, layout.extraCenter, half, SkipColor, "Omitir")
}

/**
 * Lights up the border of the gesture area with the colour of each swipe zone, so the playlists
 * are visible before touching. While dragging, the zone under the finger glows brighter and its
 * name appears inside that zone, near the edge.
 */
@Composable
internal fun EdgeGlow(zones: List<Zone>, hovered: Sector?, modifier: Modifier = Modifier) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        // Soft wide glow, then a crisp thin border on top.
        drawBand(zones, hovered, center, band = 26.dp, idleAlpha = 0.10f, hoveredAlpha = 0.35f, dimmedAlpha = 0.04f)
        drawBand(zones, hovered, center, band = 5.dp, idleAlpha = 0.55f, hoveredAlpha = 1f, dimmedAlpha = 0.2f)

        val zone = zones.firstOrNull { it.sector == hovered } ?: return@Canvas
        drawZoneLabel(textMeasurer, zone, center)
    }
}

private fun DrawScope.drawBand(
    zones: List<Zone>,
    hovered: Sector?,
    center: Offset,
    band: Dp,
    idleAlpha: Float,
    hoveredAlpha: Float,
    dimmedAlpha: Float,
) {
    val inset = band.toPx()
    val corner = 24.dp.toPx()
    val inner = Path().apply {
        addRoundRect(RoundRect(inset, inset, size.width - inset, size.height - inset, CornerRadius(corner, corner)))
    }
    val reach = hypot(size.width, size.height)
    clipPath(inner, clipOp = ClipOp.Difference) {
        zones.forEach { zone ->
            val alpha = when {
                hovered == null -> idleAlpha
                hovered == zone.sector -> hoveredAlpha
                else -> dimmedAlpha
            }
            drawArc(
                color = zone.color.copy(alpha = alpha),
                startAngle = -(zone.centerDegrees + zone.halfWidth),
                sweepAngle = zone.halfWidth * 2,
                useCenter = true,
                topLeft = Offset(center.x - reach, center.y - reach),
                size = Size(reach * 2, reach * 2),
            )
        }
    }
}

/** Name of the zone, placed where its centre line meets the screen border (inset a bit). */
private fun DrawScope.drawZoneLabel(measurer: TextMeasurer, zone: Zone, center: Offset) {
    val inset = 64.dp.toPx()
    val rad = Math.toRadians(zone.centerDegrees.toDouble())
    val dx = cos(rad).toFloat()
    val dy = -sin(rad).toFloat()
    val halfW = (size.width / 2f - inset).coerceAtLeast(1f)
    val halfH = (size.height / 2f - inset).coerceAtLeast(1f)
    val t = minOf(if (abs(dx) > 1e-3f) halfW / abs(dx) else Float.MAX_VALUE, if (abs(dy) > 1e-3f) halfH / abs(dy) else Float.MAX_VALUE)
    val anchor = Offset(center.x + dx * t, center.y + dy * t)

    val text = measurer.measure(
        text = zone.label,
        style = TextStyle(color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
        overflow = TextOverflow.Ellipsis,
        maxLines = 2,
        constraints = Constraints(maxWidth = 120.dp.roundToPx()),
    )
    val padH = 14.dp.toPx()
    val padV = 8.dp.toPx()
    val boxSize = Size(text.size.width + padH * 2, text.size.height + padV * 2)
    val topLeft = Offset(
        (anchor.x - boxSize.width / 2f).coerceIn(8.dp.toPx(), size.width - boxSize.width - 8.dp.toPx()),
        (anchor.y - boxSize.height / 2f).coerceIn(8.dp.toPx(), size.height - boxSize.height - 8.dp.toPx()),
    )
    drawRoundRect(zone.color, topLeft, boxSize, CornerRadius(boxSize.height / 2f, boxSize.height / 2f))
    drawText(text, topLeft = topLeft + Offset(padH, padV))
}
