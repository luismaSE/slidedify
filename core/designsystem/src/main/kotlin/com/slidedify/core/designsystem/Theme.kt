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

private val SlotPalette = listOf(
    Color(0xFFFFB547),
    Color(0xFF3DDC97),
    Color(0xFFFF7A90),
    Color(0xFF5BC0FF),
    Color(0xFFC792EA),
    Color(0xFFF6E05E),
    Color(0xFF4FD1C5),
    Color(0xFFFF9F5A),
)

/** One distinct colour per destination, shared by swipe hints, the wheel and settings. */
fun Slot.color(): Color = SlotPalette[index % SlotPalette.size]

/** Closest of the 8 arrows for a screen angle (math convention: 0° right, 90° up). */
fun arrowFor(degrees: Float): String {
    val arrows = listOf("→", "↗", "↑", "↖", "←", "↙", "↓", "↘")
    val normalized = ((degrees % 360f) + 360f) % 360f
    return arrows[(((normalized + 22.5f) / 45f).toInt()) % arrows.size]
}

val CancelColor = Color(0xFFFF5A5A)

@Composable
fun SlidedifyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
