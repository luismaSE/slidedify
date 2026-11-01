package com.slidedify.feature.swipe

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
import com.slidedify.core.designsystem.color
import com.slidedify.core.domain.gesture.GestureState
import com.slidedify.core.domain.gesture.Sector
import com.slidedify.core.domain.gesture.SectorLayout
import kotlin.math.cos
import kotlin.math.sin

/**
 * Joystick wheel drawn around the touch origin, split evenly between the destinations and the
 * cancel sector. Compose arcs go clockwise from 3 o'clock, so a math angle `a` becomes `-a` here.
 */
@Composable
fun WheelOverlay(
    wheel: GestureState.Wheel,
    targets: List<SlotTarget>,
    layout: SectorLayout,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier) {
        val radius = WheelRadius.toPx()
        // Keep the wheel fully visible near edges; gestures are still measured from the real origin.
        val center = Offset(
            wheel.origin.x.coerceIn(radius, (size.width - radius).coerceAtLeast(radius)),
            wheel.origin.y.coerceIn(radius, (size.height - radius).coerceAtLeast(radius)),
        )
        val halfWidth = layout.sectorWidth / 2f
        drawRect(Color.Black.copy(alpha = 0.55f))

        targets.forEach { target ->
            val slot = target.slot
            val alpha = when {
                slot in wheel.selected -> 0.9f
                wheel.hovered == Sector.Destination(slot) -> 0.55f
                else -> 0.25f
            }
            drawSector(center, radius, layout.centerOf(slot), halfWidth, slot.color().copy(alpha))
        }
        val cancelHovered = wheel.hovered == Sector.Cancel
        drawSector(center, radius, layout.cancelCenter, halfWidth, CancelColor.copy(alpha = if (cancelHovered) 0.75f else 0.18f))

        val activation = WheelActivationRadius.toPx()
        drawCircle(Color(0xFF0E0F12), radius = activation, center = center)
        drawCircle(Color.White.copy(alpha = 0.25f), radius = activation, center = center, style = Stroke(1.dp.toPx()))

        targets.forEach { target ->
            val check = if (target.slot in wheel.selected) "✓ " else ""
            drawLabel(textMeasurer, "$check${target.label}", center, radius * 0.68f, layout.centerOf(target.slot))
        }
        drawLabel(textMeasurer, "✕\nCancelar", center, radius * 0.66f, layout.cancelCenter)

        val count = wheel.selected.size
        drawLabel(textMeasurer, if (count == 0) "empujá\nel pulgar" else "✓ $count", center, 0f, 0f, small = count == 0)

        // Finger position, relative to the drawn centre.
        val finger = center + Offset(wheel.current.x - wheel.origin.x, wheel.current.y - wheel.origin.y)
        drawCircle(Color.White, radius = 9.dp.toPx(), center = finger)
    }
}

private fun DrawScope.drawSector(center: Offset, radius: Float, centerDegrees: Float, halfWidth: Float, color: Color) {
    val gap = minOf(1.5f, halfWidth / 4f)
    drawArc(
        color = color,
        startAngle = -(centerDegrees + halfWidth) + gap,
        sweepAngle = halfWidth * 2 - gap * 2,
        useCenter = true,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2, radius * 2),
    )
}

private fun DrawScope.drawLabel(
    measurer: TextMeasurer,
    text: String,
    center: Offset,
    distance: Float,
    degrees: Float,
    small: Boolean = false,
) {
    val rad = Math.toRadians(degrees.toDouble())
    val anchor = Offset(center.x + (cos(rad) * distance).toFloat(), center.y - (sin(rad) * distance).toFloat())
    val layout = measurer.measure(
        text = text,
        style = TextStyle(
            color = Color.White,
            fontSize = if (small) 11.sp else 12.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        ),
        overflow = TextOverflow.Ellipsis,
        maxLines = 2,
        constraints = Constraints(maxWidth = 76.dp.roundToPx()),
    )
    drawText(layout, topLeft = anchor - Offset(layout.size.width / 2f, layout.size.height / 2f))
}
