package com.slidedify.feature.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slidedify.core.domain.provider.ProviderRegistry
import com.slidedify.core.domain.repository.LibraryRepository
import com.slidedify.core.domain.repository.TrackSource
import com.slidedify.core.domain.session.LibraryEffect
import com.slidedify.core.domain.suggest.PlaylistIdea
import com.slidedify.core.domain.suggest.PlaylistIdeas
import com.slidedify.core.domain.suggest.Suggester
import com.slidedify.core.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class IdeasUiState(
    val loading: Boolean = true,
    val analysed: Int = 0,
    val total: Int = 0,
    val ideas: List<PlaylistIdea> = emptyList(),
    /** Index of the idea being created, if any. */
    val creating: Int? = null,
    /** Ideas already turned into playlists: index to the playlist's name. */
    val created: Map<Int, String> = emptyMap(),
    val canCreate: Boolean = true,
    val failed: Boolean = false,
    val createFailed: Boolean = false,
)

/**
 * "Ideas de playlists": analyses the most recent liked songs, groups them by style and energy
 * and offers each big group as a new playlist, created in one tap.
 */
@HiltViewModel
class IdeasViewModel @Inject constructor(
    registry: ProviderRegistry,
    private val suggester: Suggester,
    private val library: LibraryRepository,
) : ViewModel() {
    private val provider = registry.active.value
    private val _state = MutableStateFlow(IdeasUiState(canCreate = provider.capabilities.canCreatePlaylists))
    val state: StateFlow<IdeasUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    fun retry() {
        _state.update { IdeasUiState(canCreate = it.canCreate) }
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        try {
            val liked = mutableListOf<Track>()
            provider.tracks.pages(TrackSource.LikedSongs).takeWhile { liked.size < MAX_SONGS }.collect { liked += it }
            val songs = liked.take(MAX_SONGS)
            _state.update { it.copy(total = songs.size) }
            val features = coroutineScope {
                songs.map { song ->
                    async {
                        val f = suggester.features(song, includeExpensive = false)
                        _state.update { it.copy(analysed = it.analysed + 1) }
                        song.id to f
                    }
                }.awaitAll().toMap()
            }
            val existing = provider.playlists.allPlaylists()
            _state.update { it.copy(loading = false, ideas = PlaylistIdeas.propose(songs, features, existing)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, failed = true) }
        }
    }

    /** Creates the playlist now and adds the songs through the outbox (works offline, in batches). */
    fun create(index: Int, name: String) {
        val idea = _state.value.ideas.getOrNull(index) ?: return
        if (name.isBlank() || _state.value.creating != null) return
        _state.update { it.copy(creating = index, createFailed = false) }
        viewModelScope.launch {
            try {
                val playlist = provider.playlists.createPlaylist(name.trim())
                idea.tracks.forEach { library.apply(LibraryEffect.AddToPlaylists(it, setOf(playlist.id))) }
                _state.update { it.copy(creating = null, created = it.created + (index to playlist.name)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(creating = null, createFailed = true) }
            }
        }
    }

    private companion object {
        const val MAX_SONGS = 200
    }
}
