package com.slidedify.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The Compass logo: one coloured arc per zone (grey = skip) around the needle. */
@Composable
fun CompassMark(modifier: Modifier = Modifier, size: Dp = 28.dp) {
    Canvas(modifier.size(size)) {
        val unit = this.size.minDimension / 100f
        val stroke = Stroke(width = 9f * unit)
        val radius = 38f * unit
        val topLeft = Offset(center.x - radius, center.y - radius)
        val arc = Size(radius * 2, radius * 2)
        // Start angles are clockwise from 3 o'clock, matching the launcher icon.
        listOf(
            Triple(-90f, 60f, Color(0xFFFFB547)),
            Triple(-18.2f, 36.4f, Color(0xFF3DDC97)),
            Triple(30f, 60f, Color(0xFFFF7A90)),
            Triple(100.6f, 68.8f, SkipColor),
            Triple(-169.4f, 68.8f, Color(0xFF5BC0FF)),
        ).forEach { (start, sweep, color) ->
            drawArc(color, start, sweep, useCenter = false, topLeft = topLeft, size = arc, style = stroke)
        }
        fun p(x: Float, y: Float) = Offset(center.x + (x - 50f) * unit, center.y + (y - 50f) * unit)
        val needle = Path().apply {
            p(50f, 30f).let { moveTo(it.x, it.y) }
            p(60f, 50f).let { lineTo(it.x, it.y) }
            p(50f, 70f).let { lineTo(it.x, it.y) }
            p(40f, 50f).let { lineTo(it.x, it.y) }
            close()
        }
        drawPath(needle, Color.White)
        val tip = Path().apply {
            p(50f, 30f).let { moveTo(it.x, it.y) }
            p(60f, 50f).let { lineTo(it.x, it.y) }
            p(40f, 50f).let { lineTo(it.x, it.y) }
            close()
        }
        drawPath(tip, Color(0xFFFFB547))
    }
}
