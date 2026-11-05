package com.slidedify.feature.swipe

import androidx.compose.ui.res.stringResource
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
 * Whole queue of the source: ✓ reviewed, ○ pending, ▶ current. The order chosen here is the
 * order songs come up for sorting (list order, recently added, title, artist, album, duration;
 * both directions). It can also be filtered and searched. Tapping a song continues from there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackListSheet(
    state: SwipeUiState,
    filters: TrackListOptions,
    onFiltersChange: (TrackListOptions) -> Unit,
    onOrderChange: (TrackOrder, Boolean) -> Unit,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // The queue itself is already in the chosen order; here it is only filtered and searched.
    val options = filters.copy(order = state.queueOrder, descending = state.queueDescending)
    val rows = remember(state.queue, state.sortedIds, filters) {
        arrangeTracks(state.queue, state.sortedIds, filters.copy(order = TrackOrder.Source, descending = false))
    }
    val listState = rememberLazyListState()
    // Open around the current song (it stays current when the order changes).
    LaunchedEffect(options) {
        val target = rows.indexOfFirst { it.index == state.position }.coerceAtLeast(0)
        listState.scrollToItem((target - 2).coerceAtLeast(0))
    }
    val onOptionsChange: (TrackListOptions) -> Unit = { changed ->
        if (changed.order != options.order || changed.descending != options.descending) onOrderChange(changed.order, changed.descending)
        onFiltersChange(changed.copy(order = TrackOrder.Source, descending = false))
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
                stringResource(R.string.list_reviewed, state.sortedCount, state.total) +
                    if (state.isLoadingMore) " " + stringResource(R.string.list_loading) else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = options.query,
                onValueChange = { onOptionsChange(options.copy(query = it)) },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.list_search)) },
                leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
                trailingIcon = {
                    if (options.query.isNotEmpty()) {
                        IconButton(onClick = { onOptionsChange(options.copy(query = "")) }) {
                            Icon(AppIcons.Close, contentDescription = stringResource(R.string.list_clear_search))
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
                val directionDescription = stringResource(if (options.descending) R.string.list_descending_a11y else R.string.list_ascending_a11y)
                IconButton(
                    onClick = { onOptionsChange(options.copy(descending = !options.descending)) },
                    modifier = Modifier.semantics { contentDescription = directionDescription },
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
                        label = { Text(stringResource(R.string.list_filter_count, filter.label(), count)) },
                    )
                }
            }
        }

        if (rows.isEmpty()) {
            Text(
                stringResource(R.string.list_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
        val noDate = stringResource(R.string.list_no_date)
        val addedOn = stringResource(R.string.list_added_on, "%s")
        val continueHere = stringResource(R.string.list_continue_here)
        val reviewedState = stringResource(R.string.list_reviewed_state)
        val pendingState = stringResource(R.string.list_pending_state)
        val currentState = stringResource(R.string.list_current_state)
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxWidth()) {
            items(rows, key = { it.value.id.value }) { (index, track) ->
                val isCurrent = index == state.position
                val reviewed = track.id in state.sortedIds
                val detail = when (options.order) {
                    TrackOrder.Added -> track.addedAt?.let { addedOn.format(dateFormat.format(Date(it))) } ?: noDate
                    TrackOrder.Album -> track.album.name
                    TrackOrder.Duration -> formatTime(track.durationMs)
                    else -> null
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = continueHere) { onPick(index) }
                        .background(if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "${track.title}, ${track.artistNames}, ${if (reviewed) reviewedState else pendingState}${if (isCurrent) currentState else ""}"
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
            Text(stringResource(R.string.list_order, options.order.label()), modifier = Modifier.padding(start = 6.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            availableOrders(state.queue).forEach { order ->
                DropdownMenuItem(
                    text = { Text(order.label()) },
                    trailingIcon = {
                        if (order == options.order) Icon(AppIcons.Check, contentDescription = stringResource(R.string.list_chosen), tint = MaterialTheme.colorScheme.primary)
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
@Composable
private fun directionLabel(options: TrackListOptions): String = stringResource(
    when (options.order) {
        TrackOrder.Source -> if (options.descending) R.string.direction_source_desc else R.string.direction_source_asc
        TrackOrder.Added -> if (options.descending) R.string.direction_added_desc else R.string.direction_added_asc
        TrackOrder.Duration -> if (options.descending) R.string.direction_duration_desc else R.string.direction_duration_asc
        else -> if (options.descending) R.string.direction_text_desc else R.string.direction_text_asc
    },
)

@Composable
private fun TrackOrder.label(): String = stringResource(
    when (this) {
        TrackOrder.Source -> R.string.order_source
        TrackOrder.Added -> R.string.order_added
        TrackOrder.Title -> R.string.order_title
        TrackOrder.Artist -> R.string.order_artist
        TrackOrder.Album -> R.string.order_album
        TrackOrder.Duration -> R.string.order_duration
    },
)

@Composable
private fun TrackFilter.label(): String = stringResource(
    when (this) {
        TrackFilter.All -> R.string.filter_all
        TrackFilter.Pending -> R.string.filter_pending
        TrackFilter.Reviewed -> R.string.filter_reviewed
    },
)

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
                    Text(stringResource(R.string.multi_title), style = MaterialTheme.typography.titleLarge)
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
                    Text(if (selected.isEmpty()) stringResource(R.string.multi_send) else stringResource(R.string.multi_send_count, selected.size))
                }
            }
            if (destinations.size + others.size > 8) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.multi_search)) },
                    leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
                    shape = CircleShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
            }
        }
        val destinationsTitle = stringResource(R.string.multi_destinations)
        val othersTitle = stringResource(R.string.multi_others)
        LazyColumn(Modifier.heightIn(max = 520.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            val shownDestinations = destinations.filter(::matches)
            if (shownDestinations.isNotEmpty()) {
                item { SheetSection(destinationsTitle) }
                items(shownDestinations, key = { "d-${it.id.value}" }) { playlist ->
                    PickRow(playlist, slotOf[playlist.id], playlist.id in selected, isSuggested = playlist.id in suggested) {
                        selected = if (it) selected + playlist.id else selected - playlist.id
                    }
                }
            }
            val shownOthers = others.filter(::matches)
            if (shownOthers.isNotEmpty()) {
                item { SheetSection(othersTitle) }
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
                Text(stringResource(R.string.multi_suggested), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        Checkbox(checked = checked, onCheckedChange = null)
    }
}
