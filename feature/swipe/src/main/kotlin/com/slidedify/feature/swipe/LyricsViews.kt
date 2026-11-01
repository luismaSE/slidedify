package com.slidedify.feature.swipe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slidedify.core.domain.lyrics.Lyrics

/** Three-line lyrics preview under the player; the current line is highlighted when synced. */
@Composable
internal fun LyricsPanel(state: LyricsState, positionMs: Long, onOpen: () -> Unit) {
    val available = state as? LyricsState.Available
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, bottom = 12.dp)
            .let { if (available != null) it.clickable(onClickLabel = "Ver letra completa", role = Role.Button, onClick = onOpen) else it },
    ) {
        Column(Modifier.padding(12.dp)) {
            when (state) {
                LyricsState.Loading -> Muted("Buscando letra…")
                LyricsState.Unavailable -> Muted("Letra no disponible")
                is LyricsState.Available -> {
                    val lyrics = state.lyrics
                    val current = if (lyrics.isSynced) lyrics.lineIndexAt(positionMs) else -1
                    val start = (current - 1).coerceAtLeast(0)
                    lyrics.lines.drop(start).take(3).forEachIndexed { offset, line ->
                        val isCurrent = start + offset == current
                        Text(
                            line.text.ifBlank { "♪" },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Muted("Letra: LRCLIB · tocá para verla completa")
                }
            }
        }
    }
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Full lyrics, auto-scrolling to the line being sung. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LyricsSheet(lyrics: Lyrics, positionMs: Long, onDismiss: () -> Unit) {
    val current = if (lyrics.isSynced) lyrics.lineIndexAt(positionMs) else -1
    val listState = rememberLazyListState()
    LaunchedEffect(current) {
        if (current >= 0) listState.animateScrollToItem((current - 2).coerceAtLeast(0))
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(state = listState, modifier = Modifier.padding(horizontal = 24.dp)) {
            itemsIndexed(lyrics.lines) { index, line ->
                val isCurrent = index == current
                Text(
                    line.text.ifBlank { "♪" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            item { Muted("Letra: LRCLIB") }
        }
    }
}
