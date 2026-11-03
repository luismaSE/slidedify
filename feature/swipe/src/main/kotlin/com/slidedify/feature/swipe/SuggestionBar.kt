package com.slidedify.feature.swipe

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slidedify.core.designsystem.AppIcons
import com.slidedify.core.designsystem.color

/**
 * Strip above the card with the suggested playlist, its main reason and a one-tap "send".
 * It keeps its height when there is no suggestion, so the card never jumps.
 */
@Composable
internal fun SuggestionBar(suggestion: SlotSuggestion?, onAccept: () -> Unit, modifier: Modifier = Modifier) {
    var showWhy by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 12.dp, vertical = 2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        AnimatedContent(
            targetState = suggestion,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            contentKey = { it?.let { s -> s.trackId to s.slot } },
            label = "suggestion",
        ) { current ->
            if (current == null) {
                Text(
                    "✦ Sin sugerencia clara para esta canción",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            } else {
                val color = current.slot.color()
                Surface(
                    onClick = { showWhy = true },
                    shape = CircleShape,
                    color = color.copy(alpha = 0.14f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Sugerido: ${current.label}. ${current.reasons.joinToString(". ")}. Tocá para ver por qué" },
                ) {
                    Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.Sparkle, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                        Column(
                            Modifier
                                .padding(start = 8.dp)
                                .weight(1f),
                        ) {
                            Text(
                                "Sugerido: ${current.label}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            current.reasons.firstOrNull()?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        FilledTonalButton(onClick = onAccept, modifier = Modifier.height(36.dp)) { Text("Enviar") }
                    }
                }
                if (showWhy) {
                    WhyDialog(current, onAccept = { showWhy = false; onAccept() }, onDismiss = { showWhy = false })
                }
            }
        }
    }
}

@Composable
private fun WhyDialog(suggestion: SlotSuggestion, onAccept: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.Sparkle, contentDescription = null, tint = suggestion.slot.color()) },
        title = { Text("¿Por qué ${suggestion.label}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                suggestion.reasons.forEach { reason ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(suggestion.slot.color()),
                        )
                        Text(reason, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp))
                    }
                }
                Text(
                    "La sugerencia mejora con cada canción que ordenás. Podés apagarla en Ajustes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("Enviar a ${suggestion.label}") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}
