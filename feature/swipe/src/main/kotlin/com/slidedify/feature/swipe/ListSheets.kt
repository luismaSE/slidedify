package com.slidedify.feature.swipe

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slidedify.core.designsystem.AppIcons
import com.slidedify.core.designsystem.CoverArt
import com.slidedify.core.designsystem.color
import com.slidedify.core.model.Playlist
import com.slidedify.core.model.PlaylistId
import com.slidedify.core.model.Slot
import java.text.DateFormat
import java.util.Date

/**
 * Whole queue of the source: ✓ reviewed, ○ pending, ▶ current. Can be ordered (list order,
 * recently added, title, artist, album, duration; both directions), filtered and searched.
 * Tapping a song continues sorting from there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackListSheet(
    state: SwipeUiState,
    options: TrackListOptions,
    onOptionsChange: (TrackListOptions) -> Unit,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val rows = remember(state.queue, state.sortedIds, options) { arrangeTracks(state.queue, state.sortedIds, options) }
    val listState = rememberLazyListState()
    // In list order, open around the current song; otherwise from the top.
    LaunchedEffect(options) {
        val target = if (options.order == TrackOrder.Source && !options.descending) rows.indexOfFirst { it.index == state.position } else 0
        listState.scrollToItem((target - 2).coerceAtLeast(0))
    }
    val pending = state.queue.size - state.sortedCount
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                state.sourceName,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "✓ ${state.sortedCount} de ${state.total} revisadas${if (state.isLoadingMore) " · cargando…" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = options.query,
                onValueChange = { onOptionsChange(options.copy(query = it)) },
                singleLine = true,
                placeholder = { Text("Buscar canción, artista o álbum") },
                leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
                trailingIcon = {
                    if (options.query.isNotEmpty()) {
                        IconButton(onClick = { onOptionsChange(options.copy(query = "")) }) {
                            Icon(AppIcons.Close, contentDescription = "Borrar búsqueda")
                        }
                    }
                },
                shape = CircleShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OrderMenu(state, options, onOptionsChange)
                IconButton(
                    onClick = { onOptionsChange(options.copy(descending = !options.descending)) },
                    modifier = Modifier.semantics {
                        contentDescription = if (options.descending) "Orden descendente. Tocá para invertir" else "Orden ascendente. Tocá para invertir"
                    },
                ) {
                    Text(if (options.descending) "↓" else "↑", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Text(
                    directionLabel(options),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(TrackFilter.entries) { filter ->
                    val count = when (filter) {
                        TrackFilter.All -> state.queue.size
                        TrackFilter.Pending -> pending
                        TrackFilter.Reviewed -> state.sortedCount
                    }
                    FilterChip(
                        selected = options.filter == filter,
                        onClick = { onOptionsChange(options.copy(filter = filter)) },
                        label = { Text("${filter.label} · $count") },
                    )
                }
            }
        }

        if (rows.isEmpty()) {
            Text(
                "No hay canciones con estos filtros.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxWidth()) {
            items(rows, key = { it.value.id.value }) { (index, track) ->
                val isCurrent = index == state.position
                val reviewed = track.id in state.sortedIds
                val detail = when (options.order) {
                    TrackOrder.Added -> track.addedAt?.let { "Agregada ${dateFormat.format(Date(it))}" } ?: "Sin fecha"
                    TrackOrder.Album -> track.album.name
                    TrackOrder.Duration -> formatTime(track.durationMs)
                    else -> null
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = "Seguir desde esta canción") { onPick(index) }
                        .background(if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "${track.title}, ${track.artistNames}, ${if (reviewed) "revisada" else "pendiente"}${if (isCurrent) ", actual" else ""}"
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                        Text(
                            when {
                                isCurrent -> "▶"
                                reviewed -> "✓"
                                else -> "○"
                            },
                            color = when {
                                isCurrent -> MaterialTheme.colorScheme.primary
                                reviewed -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Column(
                        Modifier
                            .padding(start = 8.dp)
                            .weight(1f),
                    ) {
                        Text(
                            track.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(track.artistNames, detail).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OrderMenu(state: SwipeUiState, options: TrackListOptions, onOptionsChange: (TrackListOptions) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Icon(AppIcons.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Ordenar: ${options.order.label}", modifier = Modifier.padding(start = 6.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            availableOrders(state.queue).forEach { order ->
                DropdownMenuItem(
                    text = { Text(order.label) },
                    trailingIcon = {
                        if (order == options.order) Icon(AppIcons.Check, contentDescription = "Elegido", tint = MaterialTheme.colorScheme.primary)
                    },
                    onClick = {
                        open = false
                        if (order != options.order) onOptionsChange(options.withOrder(order))
                    },
                )
            }
        }
    }
}

/** What ↑/↓ means for the current order, in words. */
private fun directionLabel(options: TrackListOptions): String = when (options.order) {
    TrackOrder.Source -> if (options.descending) "Del final al principio" else "Del principio al final"
    TrackOrder.Added -> if (options.descending) "Más recientes primero" else "Más antiguas primero"
    TrackOrder.Duration -> if (options.descending) "Más largas primero" else "Más cortas primero"
    else -> if (options.descending) "Z → A" else "A → Z"
}

/**
 * Multiple selection without the wheel: tick any playlists (destinations or not) and send the
 * song to all of them at once. The suggested destination comes ticked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MultiSendSheet(state: SwipeUiState, onSend: (Set<PlaylistId>) -> Unit, onDismiss: () -> Unit) {
    val destinations = state.targets.flatMap { it.playlists }.distinctBy { it.id }
    val slotOf: Map<PlaylistId, Slot> = state.targets.flatMap { target -> target.playlists.map { it.id to target.slot } }.toMap()
    val others = state.addablePlaylists.filter { it.id !in slotOf }
    val suggested = state.suggestion?.let { s -> state.target(s.slot)?.playlists?.map { it.id }?.toSet() }.orEmpty()
    var selected by remember(state.current?.id) { mutableStateOf(suggested) }
    var query by remember { mutableStateOf("") }

    fun matches(playlist: Playlist) = query.isBlank() || playlist.name.contains(query.trim(), ignoreCase = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Enviar a varias", style = MaterialTheme.typography.titleLarge)
                    state.current?.let {
                        Text(
                            "${it.title} · ${it.artistNames}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Button(onClick = { onSend(selected) }, enabled = selected.isNotEmpty()) {
                    Text(if (selected.isEmpty()) "Enviar" else "Enviar (${selected.size})")
                }
            }
            if (destinations.size + others.size > 8) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Buscar playlist") },
                    leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
                    shape = CircleShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
            }
        }
        LazyColumn(Modifier.heightIn(max = 520.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            val shownDestinations = destinations.filter(::matches)
            if (shownDestinations.isNotEmpty()) {
                item { SheetSection("Destinos del círculo") }
                items(shownDestinations, key = { "d-${it.id.value}" }) { playlist ->
                    PickRow(playlist, slotOf[playlist.id], playlist.id in selected, isSuggested = playlist.id in suggested) {
                        selected = if (it) selected + playlist.id else selected - playlist.id
                    }
                }
            }
            val shownOthers = others.filter(::matches)
            if (shownOthers.isNotEmpty()) {
                item { SheetSection("Otras playlists") }
                items(shownOthers, key = { "o-${it.id.value}" }) { playlist ->
                    PickRow(playlist, null, playlist.id in selected, isSuggested = false) {
                        selected = if (it) selected + playlist.id else selected - playlist.id
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetSection(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun PickRow(playlist: Playlist, slot: Slot?, checked: Boolean, isSuggested: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onToggle)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            CoverArt(playlist.imageUrl, playlist.name, size = 44.dp, corner = 8.dp)
            if (slot != null) {
                // Colour of its zone on the circle.
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(slot.color()),
                )
            }
        }
        Column(
            Modifier
                .padding(start = 14.dp)
                .weight(1f),
        ) {
            Text(playlist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (isSuggested) {
                Text("✦ Sugerida", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        Checkbox(checked = checked, onCheckedChange = null)
    }
}
