package com.slidedify.feature.swipe

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slidedify.core.domain.lyrics.Lyrics
import com.slidedify.core.domain.lyrics.LyricsRepository
import com.slidedify.core.domain.outbox.SyncStatus
import com.slidedify.core.domain.player.PlayerState
import com.slidedify.core.domain.provider.MusicProvider
import com.slidedify.core.domain.provider.ProviderRegistry
import com.slidedify.core.domain.repository.LibraryRepository
import com.slidedify.core.domain.repository.SortProgressRepository
import com.slidedify.core.domain.repository.TrackSource
import com.slidedify.core.domain.repository.key
import com.slidedify.core.domain.session.Decision
import com.slidedify.core.domain.session.HistoryEntry
import com.slidedify.core.domain.session.SessionUpdate
import com.slidedify.core.domain.session.SortSession
import com.slidedify.core.domain.settings.SettingsRepository
import com.slidedify.core.domain.settings.SortSettings
import com.slidedify.core.model.GestureMap
import com.slidedify.core.model.Playlist
import com.slidedify.core.model.PlaylistId
import com.slidedify.core.model.Slot
import com.slidedify.core.model.Track
import com.slidedify.core.model.TrackId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Label and playlists behind one slot. */
data class SlotTarget(val slot: Slot, val playlists: List<Playlist>) {
    val label: String get() = playlists.joinToString(" + ") { it.name }
}

sealed interface LyricsState {
    data object Loading : LyricsState

    data class Available(val lyrics: Lyrics) : LyricsState

    data object Unavailable : LyricsState
}

/** Steps of the first-run tutorial; each one completes when the user performs the gesture. */
enum class TutorialStep { Swipe, Wheel, DoubleTap }

data class SwipeUiState(
    val isLoading: Boolean = true,
    val providerName: String = "",
    val sourceName: String = "",
    val canLike: Boolean = true,
    val current: Track? = null,
    val position: Int = 0,
    val total: Int = 0,
    val isLiked: Boolean = false,
    val canUndo: Boolean = false,
    /** Destinations in circle order; the circle is split evenly between them plus cancel. */
    val targets: List<SlotTarget> = emptyList(),
    /** Playlists that can still be added as a new destination. */
    val addablePlaylists: List<Playlist> = emptyList(),
    val canCreatePlaylists: Boolean = false,
    val player: PlayerState = PlayerState(),
    val lyrics: LyricsState = LyricsState.Loading,
    val pendingSync: Int = 0,
    val settings: SortSettings = SortSettings(),
    val tutorialStep: TutorialStep? = null,
    val loadError: String? = null,
    /** More pages of the source are still being fetched. */
    val isLoadingMore: Boolean = false,
    /** Whole queue (for the track list) and which tracks were already reviewed. */
    val queue: List<Track> = emptyList(),
    val sortedIds: Set<TrackId> = emptySet(),
) {
    val sortedCount: Int get() = queue.count { it.id in sortedIds }
    val isFinished: Boolean get() = !isLoading && current == null && loadError == null && !isLoadingMore
    val isWaitingForTracks: Boolean get() = !isLoading && current == null && isLoadingMore
    val canAddDestination: Boolean get() = targets.size < GestureMap.MAX_DESTINATIONS

    fun target(slot: Slot): SlotTarget? = targets.getOrNull(slot.index)
}

/** One-off messages for the snackbar. */
sealed interface SwipeEvent {
    data class Added(val playlistNames: List<String>) : SwipeEvent

    data object Skipped : SwipeEvent

    data class LikeChanged(val liked: Boolean) : SwipeEvent

    data object Undone : SwipeEvent

    data class DestinationAdded(val playlistName: String) : SwipeEvent

    data object DestinationsFull : SwipeEvent

    data class Error(val message: String) : SwipeEvent
}

/** Navigation arguments, read from [SavedStateHandle] so this module does not depend on the app's routes. */
object SwipeArgs {
    const val KIND = "kind"
    const val PLAYLIST_ID = "playlistId"
    const val TITLE = "title"

    fun sourceFrom(handle: SavedStateHandle): TrackSource {
        val playlistId = handle.get<String>(PLAYLIST_ID)
        return when (handle.get<String>(KIND)) {
            TrackSource.Kind.Playlist.name -> playlistId?.let { TrackSource.FromPlaylist(PlaylistId(it)) } ?: TrackSource.LikedSongs
            TrackSource.Kind.Recommended.name -> TrackSource.Recommended
            else -> TrackSource.LikedSongs
        }
    }
}

/**
 * Sorting screen logic. It only talks to the active [MusicProvider] and domain interfaces,
 * so it works the same for every provider. Writes go through [LibraryRepository] (the offline outbox).
 */
@HiltViewModel
class SwipeViewModel @Inject constructor(
    registry: ProviderRegistry,
    private val libraryRepository: LibraryRepository,
    private val settingsRepository: SettingsRepository,
    private val progress: SortProgressRepository,
    private val lyricsRepository: LyricsRepository,
    syncStatus: SyncStatus,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val provider: MusicProvider = registry.active.value
    private val player = provider.player
    private val source = SwipeArgs.sourceFrom(savedStateHandle)
    private val sourceName = savedStateHandle.get<String>(SwipeArgs.TITLE) ?: "Me gusta"

    private val session = MutableStateFlow<SortSession?>(null)
    private val playlists = MutableStateFlow<List<Playlist>>(emptyList())
    private val lyrics = MutableStateFlow<LyricsState>(LyricsState.Loading)
    private val tutorialStep = MutableStateFlow<TutorialStep?>(null)
    private data class LoadState(val error: String? = null, val loadingMore: Boolean = false)

    private val loadState = MutableStateFlow(LoadState())
    private var loadJob: Job? = null
    private var started = false

    /** Whether the user left playback on; false at start (the app opens paused). */
    private var keepPlaying = false
    private var alreadySorted: Set<TrackId> = emptySet()
    private val settings: StateFlow<SortSettings> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, SortSettings())
    private var lyricsJob: Job? = null

    private val _events = Channel<SwipeEvent>(Channel.BUFFERED)
    val events: Flow<SwipeEvent> = _events.receiveAsFlow()

    private data class Extras(val lyrics: LyricsState, val pending: Int, val step: TutorialStep?, val load: LoadState)

    private val extras = combine(lyrics, syncStatus.pendingCount, tutorialStep, loadState, ::Extras)

    val uiState: StateFlow<SwipeUiState> =
        combine(session, playlists, settings, player.state, extras) { session, playlists, settings, playerState, extras ->
            val (lyricsState, pending, step, load) = extras
            val base = SwipeUiState(
                providerName = provider.displayName,
                sourceName = sourceName,
                canLike = provider.capabilities.canLike,
                player = playerState,
                pendingSync = pending,
                settings = settings,
                isLoadingMore = load.loadingMore,
            )
            if (session == null) return@combine base.copy(isLoading = load.error == null, loadError = load.error)
            val current = session.current
            val byId = playlists.associateBy { it.id }
            val map = settings.gestureMapFor(provider.id, playlists)
            base.copy(
                isLoading = false,
                current = current,
                position = session.position,
                queue = session.tracks,
                sortedIds = session.sorted,
                total = session.tracks.size,
                isLiked = current?.let(session::isLiked) ?: false,
                canUndo = session.canUndo,
                targets = map.slots.map { slot -> SlotTarget(slot, map.playlistsAt(slot).mapNotNull(byId::get)) },
                addablePlaylists = playlists.filterNot { map.contains(it.id) },
                canCreatePlaylists = provider.capabilities.canCreatePlaylists,
                lyrics = lyricsState,
                tutorialStep = step,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SwipeUiState())

    init {
        load()
    }

    /**
     * Loads the source page by page: the first page is shown as soon as it arrives and the rest
     * is appended in the background. Tracks sorted in previous sessions are skipped, so the user
     * continues where they left off. Also used by the "Reintentar" button.
     */
    fun load() {
        loadJob?.cancel()
        loadState.value = LoadState(loadingMore = true)
        session.value = null
        loadJob = viewModelScope.launch {
            try {
                playlists.value = provider.playlists.writablePlaylists()
                    // When sorting a playlist, it cannot also be a destination for its own tracks.
                    .filterNot { source is TrackSource.FromPlaylist && it.id == source.id }
                alreadySorted = progress.sortedTracks(provider.id, source.key)
                provider.tracks.pages(source).collect { page -> onPage(page) }
                if (session.value == null) onPage(emptyList())
                loadState.value = loadState.value.copy(loadingMore = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (session.value == null) {
                    loadState.value = LoadState(error = "No se pudieron cargar las canciones (${e.message})")
                } else {
                    // Keep what was loaded; the user can keep sorting.
                    loadState.value = loadState.value.copy(loadingMore = false)
                }
            }
        }
    }

    /** "Empezar de nuevo": forget the progress of this source and reload it. */
    fun restart() {
        viewModelScope.launch {
            progress.clear(provider.id, source.key)
            load()
        }
    }

    private suspend fun onPage(tracks: List<Track>) {
        val current = session.value
        if (current == null) {
            session.value = SortSession(tracks, sorted = alreadySorted).startAtFirstPending()
            if (!started) {
                started = true
                // Wait for stored settings so autoplay/tutorial use the user's choice, not the defaults.
                val loaded = settingsRepository.settings.first()
                if (!loaded.tutorialCompleted) tutorialStep.value = TutorialStep.Swipe
                onTrackChanged()
            } else {
                onTrackChanged()
            }
        } else {
            val wasWaiting = current.isFinished
            session.value = current.append(tracks)
            if (wasWaiting && session.value?.isFinished == false) onTrackChanged()
        }
    }

    /** Swipe released over a destination. */
    fun onSwipe(slot: Slot) {
        addTo(setOf(slot))
        advanceTutorial(TutorialStep.Swipe)
    }

    /** "Omitir" button (bottom left). */
    fun onSkip() {
        if (session.value?.current == null) return
        decide(Decision.Skip)
        _events.trySend(SwipeEvent.Skipped)
        advanceTutorial(TutorialStep.Swipe)
    }

    /** Adds an existing playlist as a new destination; the circle is re-split evenly. */
    fun onAddDestination(playlist: Playlist) {
        viewModelScope.launch {
            var added = false
            settingsRepository.update { current ->
                val map = current.gestureMapFor(provider.id, playlists.value)
                if (map.isFull || map.contains(playlist.id)) {
                    current
                } else {
                    added = true
                    current.withGestureMap(provider.id, map.add(setOf(playlist.id)))
                }
            }
            _events.trySend(if (added) SwipeEvent.DestinationAdded(playlist.name) else SwipeEvent.DestinationsFull)
        }
    }

    /** Creates a playlist in the provider and adds it as a destination. */
    fun onCreatePlaylist(name: String) {
        if (name.isBlank() || !provider.capabilities.canCreatePlaylists) return
        viewModelScope.launch {
            val created = try {
                provider.playlists.createPlaylist(name.trim())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.trySend(SwipeEvent.Error("No se pudo crear la playlist"))
                return@launch
            }
            playlists.value = playlists.value + created
            onAddDestination(created)
        }
    }

    fun onWheelOpened() = advanceTutorial(TutorialStep.Wheel)

    fun onWheelCommit(slots: Set<Slot>) = addTo(slots)

    fun onDoubleTap() {
        if (!provider.capabilities.canLike) return
        val current = session.value ?: return
        val update = current.toggleLike()
        apply(update)
        _events.trySend(SwipeEvent.LikeChanged(update.session.current?.let(update.session::isLiked) ?: false))
        advanceTutorial(TutorialStep.DoubleTap)
    }

    fun onTogglePlayback() {
        val state = player.state.value
        val track = session.value?.current ?: return
        // Remember the choice: the next track starts playing only if this one was playing.
        keepPlaying = !state.isPlaying
        viewModelScope.launch {
            when {
                state.isPlaying -> player.pause()
                state.track?.id == track.id -> player.resume()
                else -> player.play(track)
            }
        }
    }

    /** From the track list: continue from any track (reviewed or not). */
    fun jumpTo(index: Int) {
        val current = session.value ?: return
        val moved = current.jumpTo(index)
        if (moved == current) return
        session.value = moved
        onTrackChanged()
    }

    fun onUndo() {
        val current = session.value ?: return
        if (!current.canUndo) return
        val before = current.current
        (current.history.lastOrNull() as? HistoryEntry.Decided)?.takeUnless { it.wasSorted }?.let { undone ->
            viewModelScope.launch { progress.unmarkSorted(provider.id, source.key, undone.track.id) }
        }
        apply(current.undo())
        _events.trySend(SwipeEvent.Undone)
        if (session.value?.current != before) onTrackChanged()
    }

    fun skipTutorial() = finishTutorial()

    private fun advanceTutorial(done: TutorialStep) {
        if (tutorialStep.value != done) return
        val next = TutorialStep.entries.getOrNull(done.ordinal + 1)
        if (next == null) finishTutorial() else tutorialStep.value = next
    }

    private fun finishTutorial() {
        tutorialStep.value = null
        viewModelScope.launch { settingsRepository.update { it.copy(tutorialCompleted = true) } }
    }

    private fun addTo(slots: Set<Slot>) {
        val ids = settings.value.gestureMapFor(provider.id, playlists.value).playlistsFor(slots)
        if (ids.isEmpty()) return
        decide(Decision.AddTo(ids))
        val names = playlists.value.filter { it.id in ids }.map { it.name }
        _events.trySend(SwipeEvent.Added(names))
    }

    private fun decide(decision: Decision) {
        val current = session.value ?: return
        val track = current.current ?: return
        apply(current.decide(decision))
        viewModelScope.launch { progress.markSorted(provider.id, source.key, track.id) }
        onTrackChanged()
    }

    private fun apply(update: SessionUpdate) {
        session.value = update.session
        if (update.effects.isEmpty()) return
        viewModelScope.launch { update.effects.forEach { libraryRepository.apply(it) } }
    }

    /** Autoplay (if enabled) and lyrics for whatever track is on screen. */
    /**
     * The app starts paused. After that, the next track follows the last choice: it starts
     * automatically if the previous one was playing, and stays paused if it was paused.
     */
    private fun onTrackChanged() {
        val track = session.value?.current
        viewModelScope.launch {
            when {
                track == null -> player.pause()
                keepPlaying && player.state.value.track?.id != track.id -> player.play(track)
                !keepPlaying && player.state.value.isPlaying -> player.pause()
            }
        }
        lyricsJob?.cancel()
        lyrics.value = LyricsState.Loading
        if (track == null) return
        lyricsJob = viewModelScope.launch {
            val found = try {
                lyricsRepository.lyricsFor(track)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // Lyrics are optional; never break the sorting flow.
            }
            lyrics.value = found?.let(LyricsState::Available) ?: LyricsState.Unavailable
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
