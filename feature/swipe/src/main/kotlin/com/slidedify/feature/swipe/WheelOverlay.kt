package com.slidedify.feature.swipe

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.slidedify.core.designsystem.CancelColor
import com.slidedify.core.designsystem.SkipColor
import com.slidedify.core.designsystem.color
import com.slidedify.core.domain.gesture.GestureState
import com.slidedify.core.domain.gesture.Sector
import com.slidedify.core.domain.gesture.SectorLayout
import kotlin.math.cos
import kotlin.math.sin

/** One coloured segment of the wheel ring. */
private data class Segment(
    val sector: Sector,
    val centerDegrees: Float,
    val halfWidth: Float,
    val color: Color,
    val label: String,
    val selected: Boolean,
)

/**
 * Joystick wheel drawn as a ring around the touch origin. Segments share the circle with the
 * same geometry as the gestures; gaps between them have a constant width in pixels so the
 * divisions look even at any size. Compose arcs go clockwise from 3 o'clock, so a math angle
 * `a` becomes `-a` here.
 */
@Composable
fun WheelOverlay(
    wheel: GestureState.Wheel,
    targets: List<SlotTarget>,
    layout: SectorLayout,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val half = layout.sectorWidth / 2f
    val segments = targets.map { target ->
        Segment(
            sector = Sector.Destination(target.slot),
            centerDegrees = layout.centerOf(target.slot),
            halfWidth = half,
            color = target.slot.color(),
            label = target.label,
            selected = target.slot in wheel.selected,
        )
    } + listOf(
        Segment(Sector.Skip, layout.wheelSkipCenter, half / 2f, SkipColor, "Omitir", false),
        Segment(Sector.Cancel, layout.wheelCancelCenter, half / 2f, CancelColor, "Cancelar", false),
    )

    Canvas(modifier) {
        val outer = WheelRadius.toPx()
        val inner = WheelActivationRadius.toPx() + 6.dp.toPx()
        // Keep the wheel fully visible near edges; gestures are still measured from the real origin.
        val center = Offset(
            wheel.origin.x.coerceIn(outer, (size.width - outer).coerceAtLeast(outer)),
            wheel.origin.y.coerceIn(outer, (size.height - outer).coerceAtLeast(outer)),
        )
        drawRect(Color.Black.copy(alpha = 0.6f))

        val ringWidth = outer - inner
        val midRadius = (outer + inner) / 2f
        val gapDegrees = Math.toDegrees((3.dp.toPx() / midRadius).toDouble()).toFloat()

        segments.forEach { segment ->
            val hovered = wheel.hovered == segment.sector
            val alpha = when {
                segment.selected -> 1f
                hovered -> 0.8f
                else -> 0.38f
            }
            // Hovered/selected segments grow outwards a little.
            val grow = if (hovered || segment.selected) 8.dp.toPx() else 0f
            drawRingSegment(
                center = center,
                radius = midRadius + grow / 2f,
                width = ringWidth + grow,
                centerDegrees = segment.centerDegrees,
                halfWidth = segment.halfWidth,
                gapDegrees = gapDegrees,
                color = segment.color.copy(alpha = alpha),
            )
        }

        // Centre: dark disc with a thin outline marking the "return here" zone.
        drawCircle(Color(0xFF0E0F12), radius = inner - 2.dp.toPx(), center = center)
        drawCircle(Color.White.copy(alpha = 0.18f), radius = inner - 2.dp.toPx(), center = center, style = Stroke(1.dp.toPx()))

        segments.forEach { segment ->
            val check = if (segment.selected) "✓ " else ""
            val emphasized = segment.selected || wheel.hovered == segment.sector
            drawLabel(textMeasurer, "$check${segment.label}", center, midRadius, segment.centerDegrees, emphasized, segment.halfWidth)
        }

        val count = wheel.selected.size
        drawLabel(textMeasurer, if (count == 0) "empujá" else "✓ $count", center, 0f, 0f, emphasized = false, halfWidth = 90f)

        // Finger position, relative to the drawn centre.
        val finger = center + Offset(wheel.current.x - wheel.origin.x, wheel.current.y - wheel.origin.y)
        drawCircle(Color.White.copy(alpha = 0.9f), radius = 8.dp.toPx(), center = finger)
    }
}

private fun DrawScope.drawRingSegment(
    center: Offset,
    radius: Float,
    width: Float,
    centerDegrees: Float,
    halfWidth: Float,
    gapDegrees: Float,
    color: Color,
) {
    val sweep = (halfWidth * 2 - gapDegrees).coerceAtLeast(1f)
    drawArc(
        color = color,
        startAngle = -(centerDegrees + halfWidth) + gapDegrees / 2f,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2, radius * 2),
        style = Stroke(width = width, cap = StrokeCap.Butt),
    )
}

private fun DrawScope.drawLabel(
    measurer: TextMeasurer,
    text: String,
    center: Offset,
    distance: Float,
    degrees: Float,
    emphasized: Boolean,
    halfWidth: Float,
) {
    val rad = Math.toRadians(degrees.toDouble())
    val anchor = Offset(center.x + (cos(rad) * distance).toFloat(), center.y - (sin(rad) * distance).toFloat())
    // Narrow segments get narrower labels so text never spills into the neighbours.
    val maxWidth = if (halfWidth < 25f) 56.dp else 76.dp
    val layout = measurer.measure(
        text = text,
        style = TextStyle(
            color = if (emphasized) Color.Black else Color.White,
            fontSize = if (halfWidth < 25f) 10.sp else 12.sp,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        ),
        overflow = TextOverflow.Ellipsis,
        maxLines = 2,
        constraints = Constraints(maxWidth = maxWidth.roundToPx()),
    )
    drawText(layout, topLeft = anchor - Offset(layout.size.width / 2f, layout.size.height / 2f))
}
