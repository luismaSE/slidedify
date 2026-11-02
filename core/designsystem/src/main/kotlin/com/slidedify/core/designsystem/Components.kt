package com.slidedify.core.designsystem

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

/**
 * Square cover art. Without an image it shows a soft gradient in a colour derived from [seed]
 * with an icon or the first letter, so lists never have blank holes.
 */
@Composable
fun CoverArt(
    imageUrl: String?,
    seed: String,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    corner: Dp = 12.dp,
    icon: ImageVector? = null,
) {
    val accent = accentFor(seed)
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(
                Brush.linearGradient(listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.18f))),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.42f))
        } else {
            Text(
                seed.trim().take(1).uppercase(),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.4f).sp,
            )
        }
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Brand-ish colour of a music service, for its avatar. */
fun providerColor(providerId: String): Color = when (providerId) {
    "spotify" -> Color(0xFF1ED760)
    "tidal" -> Color(0xFFE6E7EB)
    "youtube" -> Color(0xFFFF3B3B)
    else -> Color(0xFFC792EA)
}

/** Round avatar with the service's initial on its colour. */
@Composable
fun ProviderBadge(providerId: String, name: String, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    val color = providerColor(providerId)
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.take(1).uppercase(),
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.45f).sp,
        )
    }
}

/** Title of a block of content, with an optional action on the right ("Ver todo"). */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** Thin rounded progress line. */
@Composable
fun SlimProgress(progress: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier
            .height(6.dp)
            .clip(CircleShape),
        color = color,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

/** Centred message with an icon and an optional call to action. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(72.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
            }
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 4.dp)) { Text(actionLabel) }
        }
    }
}

/** Grey placeholder block that pulses while content loads. */
@Composable
fun Skeleton(modifier: Modifier = Modifier, corner: Dp = 8.dp) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "skeleton alpha",
    )
    Box(
        modifier
            .graphicsLayer { this.alpha = alpha }
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

/** Small rounded label ("Activo", "Última"). */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
