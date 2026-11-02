package com.slidedify.feature.swipe

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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

/** Distance of the contour line from the screen edge, and its corner radius (the card aligns to these). */
internal val ContourInset = 10.dp
internal val ContourCorner = 28.dp

/** A swipe zone as seen from the centre of the screen. */
internal data class Zone(val sector: Sector, val centerDegrees: Float, val halfWidth: Float, val color: Color, val label: String)

internal fun swipeZones(targets: List<SlotTarget>, layout: SectorLayout): List<Zone> {
    val half = layout.sectorWidth / 2f
    return targets.map { Zone(Sector.Destination(it.slot), layout.centerOf(it.slot), half, it.slot.color(), it.label) } +
        Zone(Sector.Skip, layout.extraCenter, half, SkipColor, "Omitir")
}

/**
 * Coloured line following the screen contour (slightly inset), one colour per swipe zone, on a
 * plain grey background. While dragging, the segment of the zone under the finger lights up and
 * its name appears inside that zone. Drawn in front of the card so it's never hidden.
 */
@Composable
internal fun EdgeGlow(zones: List<Zone>, hovered: Sector?, modifier: Modifier = Modifier) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val inset = ContourInset.toPx()
        val contour = RoundRect(inset, inset, size.width - inset, size.height - inset, CornerRadius(ContourCorner.toPx()))
        zones.forEach { zone ->
            val isHovered = zone.sector == hovered
            val alpha = when {
                hovered == null -> 0.85f
                isHovered -> 1f
                else -> 0.3f
            }
            clipToZone(zone, center) {
                if (isHovered) {
                    // Soft halo around the lit segment.
                    drawContour(contour, zone.color.copy(alpha = 0.25f), 14.dp.toPx())
                    drawContour(contour, zone.color, 6.dp.toPx())
                } else {
                    drawContour(contour, zone.color.copy(alpha = alpha), 3.dp.toPx())
                }
            }
        }
        zones.firstOrNull { it.sector == hovered }?.let { drawZoneLabel(textMeasurer, it, center) }
    }
}

/** Restricts drawing to the angular wedge of [zone] (seen from [center]), with a small gap at the ends. */
private fun DrawScope.clipToZone(zone: Zone, center: Offset, block: DrawScope.() -> Unit) {
    val reach = hypot(size.width, size.height)
    val gap = 1.2f
    val wedge = Path().apply {
        moveTo(center.x, center.y)
        arcTo(
            rect = androidx.compose.ui.geometry.Rect(center.x - reach, center.y - reach, center.x + reach, center.y + reach),
            startAngleDegrees = -(zone.centerDegrees + zone.halfWidth) + gap,
            sweepAngleDegrees = zone.halfWidth * 2 - gap * 2,
            forceMoveTo = false,
        )
        close()
    }
    clipPath(wedge) { block() }
}

private fun DrawScope.drawContour(contour: RoundRect, color: Color, width: Float) {
    drawPath(Path().apply { addRoundRect(contour) }, color, style = Stroke(width = width, cap = StrokeCap.Round))
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
