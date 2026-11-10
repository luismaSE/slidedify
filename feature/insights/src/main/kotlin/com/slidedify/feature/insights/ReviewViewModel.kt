package com.slidedify.feature.insights

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slidedify.core.domain.provider.ProviderRegistry
import com.slidedify.core.domain.repository.LibraryRepository
import com.slidedify.core.domain.repository.TrackSource
import com.slidedify.core.domain.session.LibraryEffect
import com.slidedify.core.domain.settings.SettingsRepository
import com.slidedify.core.domain.suggest.Misfit
import com.slidedify.core.domain.suggest.PlaylistReview
import com.slidedify.core.domain.suggest.Suggester
import com.slidedify.core.domain.suggest.TrackFeatures
import com.slidedify.core.model.Playlist
import com.slidedify.core.model.PlaylistId
import com.slidedify.core.model.Track
import com.slidedify.core.model.TrackId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the user did with a flagged song. */
sealed interface ReviewAction {
    data class Moved(val to: String) : ReviewAction

    data object Removed : ReviewAction

    data object Kept : ReviewAction
}

data class ReviewUiState(
    val playlistName: String = "",
    val loading: Boolean = true,
    /** Songs analysed so far, out of [total]. */
    val analysed: Int = 0,
    val total: Int = 0,
    val misfits: List<Misfit> = emptyList(),
    val handled: Map<TrackId, ReviewAction> = emptyMap(),
    val failed: Boolean = false,
) {
    val pending: List<Misfit> get() = misfits.filter { it.track.id !in handled }
}

/** Arguments, read from [SavedStateHandle] so this module does not depend on the app's routes. */
object ReviewArgs {
    const val PLAYLIST_ID = "playlistId"
    const val TITLE = "title"
}

/**
 * "Revisar playlist": analyses every song of a playlist and lists those that don't fit it, with
 * the playlist where each would fit better. Moving or removing goes through the offline outbox.
 */
@HiltViewModel
class ReviewViewModel @Inject constructor(
    registry: ProviderRegistry,
    private val settingsRepository: SettingsRepository,
    private val suggester: Suggester,
    private val library: LibraryRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val provider = registry.active.value
    private val playlistId = PlaylistId(savedStateHandle.get<String>(ReviewArgs.PLAYLIST_ID).orEmpty())
    private val _state = MutableStateFlow(ReviewUiState(playlistName = savedStateHandle.get<String>(ReviewArgs.TITLE).orEmpty()))
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    fun retry() {
        _state.update { it.copy(loading = true, failed = false, analysed = 0) }
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        try {
            val all = provider.playlists.allPlaylists()
            val playlist = all.firstOrNull { it.id == playlistId } ?: Playlist(playlistId, provider.id, _state.value.playlistName)
            val songs = songsOf(playlist.id, MAX_SONGS)
            _state.update { it.copy(playlistName = playlist.name, total = songs.size) }

            val features = analyse(songs)
            val others = otherPlaylists(all, playlist).map { other -> suggester.profile(other, songsOf(other.id, MAX_OTHER_SONGS)) }
            val misfits = PlaylistReview.review(playlist, songs, features, others)
            _state.update { it.copy(loading = false, misfits = misfits) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, failed = true) }
        }
    }

    private suspend fun songsOf(id: PlaylistId, max: Int): List<Track> {
        val songs = mutableListOf<Track>()
        provider.tracks.pages(TrackSource.FromPlaylist(id)).takeWhile { songs.size < max }.collect { songs += it }
        return songs.take(max)
    }

    /** Cheap sources only (tags, tempo); audio is used if it was already analysed before. */
    private suspend fun analyse(songs: List<Track>): Map<TrackId, TrackFeatures> = coroutineScope {
        songs.map { song ->
            async {
                val features = suggester.features(song, includeExpensive = false)
                _state.update { it.copy(analysed = it.analysed + 1) }
                song.id to features
            }
        }.awaitAll().toMap()
    }

    /** The circle's destinations first (the playlists the user sorts into), then other editable ones. */
    private suspend fun otherPlaylists(all: List<Playlist>, self: Playlist): List<Playlist> {
        val writable = all.filter { it.canWrite && it.id != self.id }
        val destinations = settingsRepository.settings.first().gestureMapFor(provider.id, writable).destinations.flatten().toSet()
        return (writable.filter { it.id in destinations } + writable.filterNot { it.id in destinations }).take(MAX_OTHERS)
    }

    fun move(misfit: Misfit) {
        val to = misfit.better ?: return
        _state.update { it.copy(handled = it.handled + (misfit.track.id to ReviewAction.Moved(to.name))) }
        viewModelScope.launch {
            library.apply(LibraryEffect.AddToPlaylists(misfit.track, setOf(to.id)))
            library.apply(LibraryEffect.RemoveFromPlaylists(misfit.track, setOf(playlistId)))
        }
    }

    fun remove(misfit: Misfit) {
        _state.update { it.copy(handled = it.handled + (misfit.track.id to ReviewAction.Removed)) }
        viewModelScope.launch { library.apply(LibraryEffect.RemoveFromPlaylists(misfit.track, setOf(playlistId))) }
    }

    fun keep(misfit: Misfit) {
        _state.update { it.copy(handled = it.handled + (misfit.track.id to ReviewAction.Kept)) }
    }

    private companion object {
        const val MAX_SONGS = 300
        const val MAX_OTHER_SONGS = 200
        const val MAX_OTHERS = 8
    }
}
