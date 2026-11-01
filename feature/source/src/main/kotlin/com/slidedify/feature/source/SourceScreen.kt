package com.slidedify.feature.source

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.slidedify.core.domain.provider.ProviderRegistry
import com.slidedify.core.domain.repository.TrackSource
import com.slidedify.core.model.Playlist
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A source the user can pick, with the title to show while sorting it. */
data class SourceOption(val source: TrackSource, val title: String, val subtitle: String)

data class SourceUiState(
    val isLoading: Boolean = true,
    val providerName: String = "",
    val options: List<SourceOption> = emptyList(),
)

@HiltViewModel
class SourceViewModel @Inject constructor(registry: ProviderRegistry) : ViewModel() {
    private val provider = registry.active.value
    private val _state = MutableStateFlow(SourceUiState(providerName = provider.displayName))
    val state: StateFlow<SourceUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val kinds = provider.capabilities.sources
            val options = buildList {
                if (TrackSource.Kind.LikedSongs in kinds) {
                    add(SourceOption(TrackSource.LikedSongs, "Me gusta", "Tu biblioteca de canciones guardadas"))
                }
                if (TrackSource.Kind.Recommended in kinds) {
                    add(SourceOption(TrackSource.Recommended, "Recomendadas", "Según lo que más escuchás"))
                }
                if (TrackSource.Kind.Playlist in kinds) {
                    provider.playlists.writablePlaylists().forEach { playlist: Playlist ->
                        add(SourceOption(TrackSource.FromPlaylist(playlist.id), playlist.name, "Playlist"))
                    }
                }
            }
            _state.value = SourceUiState(isLoading = false, providerName = provider.displayName, options = options)
        }
    }
}

@Composable
fun SourceRoute(
    onSourceSelected: (SourceOption) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: SourceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SourceScreen(state, onSourceSelected, onOpenSettings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceScreen(state: SourceUiState, onSourceSelected: (SourceOption) -> Unit, onOpenSettings: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("¿Qué querés ordenar?") },
                actions = { TextButton(onClick = onOpenSettings) { Text("⚙ Ajustes") } },
            )
        },
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    "Desde ${state.providerName}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .semantics { heading() },
                )
            }
            items(state.options, key = { it.source.toString() }) { option ->
                ListItem(
                    headlineContent = { Text(option.title) },
                    supportingContent = { Text(option.subtitle) },
                    modifier = Modifier.clickable { onSourceSelected(option) },
                )
                HorizontalDivider()
            }
        }
    }
}
