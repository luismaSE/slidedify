package com.slidedify.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.slidedify.core.model.Slot

/**
 * Dark, neutral grey base. Colour is reserved for meaning: mint for primary actions,
 * one colour per playlist zone, grey for skip.
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF3DDC97),
    onPrimary = Color(0xFF00210F),
    primaryContainer = Color(0xFF1F4A37),
    onPrimaryContainer = Color(0xFFB4F5D6),
    secondary = Color(0xFFB8C7FF),
    onSecondary = Color(0xFF1A2550),
    secondaryContainer = Color(0xFF3A3F4F),
    onSecondaryContainer = Color(0xFFDDE3FF),
    tertiary = Color(0xFFFFB547),
    onTertiary = Color(0xFF3A2500),
    background = Color(0xFF26282D),
    onBackground = Color(0xFFE6E7EB),
    surface = Color(0xFF26282D),
    onSurface = Color(0xFFE6E7EB),
    surfaceVariant = Color(0xFF3B3E45),
    onSurfaceVariant = Color(0xFFA9AEB8),
    surfaceContainerLowest = Color(0xFF1F2125),
    surfaceContainerLow = Color(0xFF2B2E33),
    surfaceContainer = Color(0xFF2F3237),
    surfaceContainerHigh = Color(0xFF363940),
    surfaceContainerHighest = Color(0xFF3E414A),
    surfaceBright = Color(0xFF444852),
    outline = Color(0xFF5C616C),
    outlineVariant = Color(0xFF41454D),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3B0000),
    errorContainer = Color(0xFF5C1E1E),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val base = Typography()

private val CompassTypography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium,
    labelSmall = base.labelSmall.copy(letterSpacing = 0.4.sp),
)

private val CompassShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Small caps-like label above a section ("SEGUÍ DONDE QUEDASTE"). */
val OverlineStyle: TextStyle
    @Composable get() = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)

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

/** A stable accent colour for anything named (placeholder covers, avatars). */
fun accentFor(seed: String): Color = SlotPalette[(seed.hashCode() and Int.MAX_VALUE) % SlotPalette.size]

/** Closest of the 8 arrows for a screen angle (math convention: 0° right, 90° up). */
fun arrowFor(degrees: Float): String {
    val arrows = listOf("→", "↗", "↑", "↖", "←", "↙", "↓", "↘")
    val normalized = ((degrees % 360f) + 360f) % 360f
    return arrows[(((normalized + 22.5f) / 45f).toInt()) % arrows.size]
}

val CancelColor = Color(0xFFFF5A5A)

/** "Omitir" (skip) zone: neutral grey so it never looks like a playlist. */
val SkipColor = Color(0xFF9AA3B2)

@Composable
fun SlidedifyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, typography = CompassTypography, shapes = CompassShapes, content = content)
}
