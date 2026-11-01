package com.slidedify.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.slidedify.core.model.Slot

private val DarkColors = darkColorScheme(
    primary = Color(0xFF3DDC97),
    onPrimary = Color(0xFF00210F),
    secondary = Color(0xFFB8C7FF),
    background = Color(0xFF0E0F12),
    onBackground = Color(0xFFE6E7EB),
    surface = Color(0xFF17191E),
    onSurface = Color(0xFFE6E7EB),
    surfaceVariant = Color(0xFF23262D),
    onSurfaceVariant = Color(0xFFA9AEB8),
    error = Color(0xFFFF6B6B),
)

/** One distinct colour per slot, shared by swipe hints and the wheel. */
fun Slot.color(): Color = when (this) {
    Slot.UP -> Color(0xFFFFB547)
    Slot.UP_RIGHT -> Color(0xFF5BC0FF)
    Slot.RIGHT -> Color(0xFF3DDC97)
    Slot.DOWN_RIGHT -> Color(0xFFC792EA)
    Slot.DOWN -> Color(0xFFFF7A90)
}

fun Slot.arrow(): String = when (this) {
    Slot.UP -> "↑"
    Slot.UP_RIGHT -> "↗"
    Slot.RIGHT -> "→"
    Slot.DOWN_RIGHT -> "↘"
    Slot.DOWN -> "↓"
}

val CancelColor = Color(0xFFFF5A5A)

@Composable
fun SlidedifyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
