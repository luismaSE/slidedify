package com.slidedify.feature.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slidedify.core.designsystem.AppIcons
import com.slidedify.core.designsystem.CoverArt
import com.slidedify.core.designsystem.EmptyState
import com.slidedify.core.domain.suggest.EnergyBand
import com.slidedify.core.domain.suggest.Misfit
import com.slidedify.core.domain.suggest.PlaylistIdea

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InsightsScaffold(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(AppIcons.Back, contentDescription = stringResource(R.string.insights_back)) } },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        content = content,
    )
}

@Composable
private fun Analysing(text: String, done: Int, total: Int, modifier: Modifier = Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator()
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (total > 0) LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
    }
}

// --- Review a playlist -------------------------------------------------------------------------

@Composable
fun ReviewRoute(onBack: () -> Unit, viewModel: ReviewViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    InsightsScaffold(stringResource(R.string.review_title, state.playlistName), onBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when {
                state.loading -> Analysing(stringResource(R.string.review_analysing, state.analysed, state.total), state.analysed, state.total)
                state.failed -> EmptyState(
                    AppIcons.Refresh,
                    stringResource(R.string.insights_load_failed),
                    stringResource(R.string.insights_load_failed_text),
                    actionLabel = stringResource(R.string.insights_retry),
                    onAction = viewModel::retry,
                )
                state.misfits.isEmpty() -> EmptyState(
                    AppIcons.Check,
                    stringResource(R.string.review_all_fit),
                    stringResource(R.string.review_all_fit_text, state.playlistName),
                )
                else -> LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Column(Modifier.padding(bottom = 6.dp)) {
                            Text(pluralStringResource(R.plurals.review_count, state.misfits.size, state.misfits.size), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.review_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(state.misfits, key = { it.track.id.value }) { misfit ->
                        MisfitCard(
                            misfit = misfit,
                            action = state.handled[misfit.track.id],
                            onMove = { viewModel.move(misfit) },
                            onRemove = { viewModel.remove(misfit) },
                            onKeep = { viewModel.keep(misfit) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MisfitCard(misfit: Misfit, action: ReviewAction?, onMove: () -> Unit, onRemove: () -> Unit, onKeep: () -> Unit) {
    val track = misfit.track
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoverArt(track.album.imageUrl, track.title, size = 48.dp, corner = 8.dp)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artistNames, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            val better = misfit.better
            Text(
                if (better != null) stringResource(R.string.review_better_in, better.name) else stringResource(R.string.review_odd_one),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 10.dp),
            )
            misfit.reasons.take(2).forEach { reason ->
                Text("· " + reasonText(reason), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (action == null) {
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (better != null) FilledTonalButton(onClick = onMove) { Text(stringResource(R.string.review_move_to, better.name), maxLines = 1) }
                    OutlinedButton(onClick = onRemove) { Text(stringResource(R.string.review_remove)) }
                    TextButton(onClick = onKeep) { Text(stringResource(R.string.review_keep)) }
                }
            } else {
                Text(
                    when (action) {
                        is ReviewAction.Moved -> stringResource(R.string.review_moved, action.to)
                        ReviewAction.Removed -> stringResource(R.string.review_removed)
                        ReviewAction.Kept -> stringResource(R.string.review_kept)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

// --- Playlist ideas ----------------------------------------------------------------------------

@Composable
fun IdeasRoute(onBack: () -> Unit, viewModel: IdeasViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    InsightsScaffold(stringResource(R.string.ideas_title), onBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when {
                state.loading -> Analysing(stringResource(R.string.ideas_analysing, state.analysed, state.total), state.analysed, state.total)
                state.failed -> EmptyState(
                    AppIcons.Refresh,
                    stringResource(R.string.insights_load_failed),
                    stringResource(R.string.insights_load_failed_text),
                    actionLabel = stringResource(R.string.insights_retry),
                    onAction = viewModel::retry,
                )
                state.ideas.isEmpty() -> EmptyState(AppIcons.Sparkle, stringResource(R.string.ideas_none), stringResource(R.string.ideas_none_text))
                else -> LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text(stringResource(R.string.ideas_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!state.canCreate) {
                            Text(stringResource(R.string.ideas_cannot_create), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        if (state.createFailed) {
                            Text(stringResource(R.string.ideas_create_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    itemsIndexed(state.ideas) { index, idea ->
                        IdeaCard(
                            idea = idea,
                            created = state.created[index],
                            creating = state.creating == index,
                            canCreate = state.canCreate && state.creating == null,
                            onCreate = { name -> viewModel.create(index, name) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IdeaCard(idea: PlaylistIdea, created: String?, creating: Boolean, canCreate: Boolean, onCreate: (String) -> Unit) {
    val suggested = ideaName(idea)
    var name by rememberSaveable(suggested) { mutableStateOf(suggested) }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(idea.tracks.take(8), key = { it.id.value }) { track ->
                    CoverArt(track.album.imageUrl, track.title, size = 52.dp, corner = 8.dp)
                }
            }
            val details = listOfNotNull(
                pluralStringResource(R.plurals.ideas_songs, idea.tracks.size, idea.tracks.size),
                idea.tag?.let { stringResource(R.string.ideas_style, it) },
                stringResource(
                    when (idea.energy) {
                        EnergyBand.Calm -> R.string.band_calm
                        EnergyBand.Medium -> R.string.band_medium
                        EnergyBand.Energetic -> R.string.band_energetic
                    },
                ),
            ).joinToString(" · ")
            Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
            Text(
                idea.tracks.take(4).joinToString(" · ") { it.title },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (created != null) {
                Text(stringResource(R.string.ideas_created, created), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
            } else {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.ideas_name)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                Button(onClick = { onCreate(name) }, enabled = canCreate && name.isNotBlank(), modifier = Modifier.padding(top = 8.dp)) {
                    if (creating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.ideas_create))
                }
            }
        }
    }
}

/** "Indie folk tranquilo", "Rock con energía", or just "Tranquilas" when only the energy groups them. */
@Composable
private fun ideaName(idea: PlaylistIdea): String {
    val tag = idea.tag?.replaceFirstChar { it.uppercase() }
        ?: return stringResource(if (idea.energy == EnergyBand.Calm) R.string.idea_name_calm_only else R.string.idea_name_energetic_only)
    return when (idea.energy) {
        EnergyBand.Calm -> stringResource(R.string.idea_name_calm, tag)
        EnergyBand.Energetic -> stringResource(R.string.idea_name_energetic, tag)
        EnergyBand.Medium -> stringResource(R.string.idea_name_medium, tag)
    }
}
