package com.slidedify.feature.swipe

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slidedify.core.domain.gesture.SwipeResult
import com.slidedify.core.domain.lyrics.Lyrics
import com.slidedify.core.domain.lyrics.LyricsRepository
import com.slidedify.core.domain.outbox.SyncStatus
import com.slidedify.core.domain.player.PlayerState
import com.slidedify.core.domain.provider.MusicProvider
import com.slidedify.core.domain.provider.ProviderRegistry
import com.slidedify.core.domain.repository.LibraryRepository
import com.slidedify.core.domain.repository.TrackSource
import com.slidedify.core.domain.session.Decision
import com.slidedify.core.domain.session.SessionUpdate
import com.slidedify.core.domain.session.SortSession
import com.slidedify.core.domain.settings.SettingsRepository
import com.slidedify.core.domain.settings.SortSettings
import com.slidedify.core.model.Playlist
import com.slidedify.core.model.PlaylistId
import com.slidedify.core.model.Slot
import com.slidedify.core.model.Track
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
    val targets: Map<Slot, SlotTarget> = emptyMap(),
    val player: PlayerState = PlayerState(),
    val lyrics: LyricsState = LyricsState.Loading,
    val pendingSync: Int = 0,
    val settings: SortSettings = SortSettings(),
    val tutorialStep: TutorialStep? = null,
) {
    val isFinished: Boolean get() = !isLoading && current == null
    val enabledSlots: Set<Slot> get() = targets.keys
}

/** One-off messages for the snackbar. */
sealed interface SwipeEvent {
    data class Added(val playlistNames: List<String>) : SwipeEvent

    data object Skipped : SwipeEvent

    data class LikeChanged(val liked: Boolean) : SwipeEvent

    data object Undone : SwipeEvent
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
    private val settings: StateFlow<SortSettings> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, SortSettings())
    private var lyricsJob: Job? = null

    private val _events = Channel<SwipeEvent>(Channel.BUFFERED)
    val events: Flow<SwipeEvent> = _events.receiveAsFlow()

    private val extras = combine(lyrics, syncStatus.pendingCount, tutorialStep) { lyrics, pending, step ->
        Triple(lyrics, pending, step)
    }

    val uiState: StateFlow<SwipeUiState> =
        combine(session, playlists, settings, player.state, extras) { session, playlists, settings, playerState, extras ->
            val (lyricsState, pending, step) = extras
            val base = SwipeUiState(
                providerName = provider.displayName,
                sourceName = sourceName,
                canLike = provider.capabilities.canLike,
                player = playerState,
                pendingSync = pending,
                settings = settings,
            )
            if (session == null) return@combine base
            val current = session.current
            val byId = playlists.associateBy { it.id }
            base.copy(
                isLoading = false,
                current = current,
                position = session.position,
                total = session.tracks.size,
                isLiked = current?.let(session::isLiked) ?: false,
                canUndo = session.canUndo,
                targets = settings.gestureMapFor(provider.id, playlists).targets
                    .mapValues { (slot, ids) -> SlotTarget(slot, ids.mapNotNull(byId::get)) }
                    .filterValues { it.playlists.isNotEmpty() },
                lyrics = lyricsState,
                tutorialStep = step,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SwipeUiState())

    init {
        viewModelScope.launch {
            playlists.value = provider.playlists.writablePlaylists()
            val tracks = provider.tracks.tracks(source)
            // When sorting a playlist, it cannot also be a destination for its own tracks.
            if (source is TrackSource.FromPlaylist) playlists.value = playlists.value.filterNot { it.id == source.id }
            session.value = SortSession(tracks)
            // Wait for stored settings so autoplay/tutorial use the user's choice, not the defaults.
            val loaded = settingsRepository.settings.first()
            if (!loaded.tutorialCompleted) tutorialStep.value = TutorialStep.Swipe
            onTrackChanged(autoplay = loaded.autoplay)
        }
    }

    fun onSwipe(result: SwipeResult) {
        when (result) {
            SwipeResult.Skip -> {
                decide(Decision.Skip)
                _events.trySend(SwipeEvent.Skipped)
            }

            is SwipeResult.ToSlot -> addTo(setOf(result.slot))
        }
        advanceTutorial(TutorialStep.Swipe)
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
        viewModelScope.launch {
            when {
                state.isPlaying -> player.pause()
                state.track?.id == track.id -> player.resume()
                else -> player.play(track)
            }
        }
    }

    fun onUndo() {
        val current = session.value ?: return
        if (!current.canUndo) return
        val before = current.current
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
        apply(current.decide(decision))
        onTrackChanged()
    }

    private fun apply(update: SessionUpdate) {
        session.value = update.session
        if (update.effects.isEmpty()) return
        viewModelScope.launch { update.effects.forEach { libraryRepository.apply(it) } }
    }

    /** Autoplay (if enabled) and lyrics for whatever track is on screen. */
    private fun onTrackChanged(autoplay: Boolean = settings.value.autoplay) {
        val track = session.value?.current
        viewModelScope.launch {
            when {
                track == null -> player.pause()
                autoplay && player.state.value.track?.id != track.id -> player.play(track)
                !autoplay && player.state.value.isPlaying -> player.pause()
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
