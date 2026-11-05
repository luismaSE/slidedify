package com.slidedify.feature.swipe

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.slidedify.core.domain.session.LibraryEffect
import com.slidedify.core.domain.session.SessionUpdate
import com.slidedify.core.domain.session.SortSession
import com.slidedify.core.domain.settings.RecentSource
import com.slidedify.core.domain.settings.SettingsRepository
import com.slidedify.core.domain.settings.SortSettings
import com.slidedify.core.domain.suggest.PlaylistProfile
import com.slidedify.core.domain.suggest.Reason
import com.slidedify.core.domain.suggest.Suggester
import com.slidedify.core.domain.suggest.SuggestionEngine
import com.slidedify.core.domain.suggest.TrackFeatures
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Label and playlists behind one slot. */
data class SlotTarget(val slot: Slot, val playlists: List<Playlist>) {
    val label: String get() = playlists.joinToString(" + ") { it.name }
}

/** A guessed destination for the current song, with the reasons to show. */
data class SlotSuggestion(val slot: Slot, val trackId: TrackId, val label: String, val reasons: List<Reason>)

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
    val pendingSync: Int = 0,
    val settings: SortSettings = SortSettings(),
    val tutorialStep: TutorialStep? = null,
    val loadError: String? = null,
    /** More pages of the source are still being fetched. */
    val isLoadingMore: Boolean = false,
    /** Whole queue (for the track list) and which tracks were already reviewed. */
    val queue: List<Track> = emptyList(),
    val sortedIds: Set<TrackId> = emptySet(),
    /** Where this song probably goes, when one destination clearly fits best. */
    val suggestion: SlotSuggestion? = null,
    /** Sorting moves songs out of the source playlist instead of copying them. */
    val movesFromSource: Boolean = false,
    /** Order of [queue], chosen in the song list. */
    val queueOrder: TrackOrder = TrackOrder.Source,
    val queueDescending: Boolean = false,
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

    data object CreatePlaylistFailed : SwipeEvent
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
    private val suggester: Suggester,
    syncStatus: SyncStatus,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val provider: MusicProvider = registry.active.value
    private val player = provider.player
    private val source = SwipeArgs.sourceFrom(savedStateHandle)
    private val sourceName = savedStateHandle.get<String>(SwipeArgs.TITLE).orEmpty()

    private val session = MutableStateFlow<SortSession?>(null)
    private val playlists = MutableStateFlow<List<Playlist>>(emptyList())
    private val tutorialStep = MutableStateFlow<TutorialStep?>(null)
    private data class LoadState(val error: String? = null, val loadingMore: Boolean = false)

    private val loadState = MutableStateFlow(LoadState())
    private var loadJob: Job? = null
    private var started = false

    /** Whether the user left playback on; false at start (the app opens paused). */
    private var keepPlaying = false
    private var alreadySorted: Set<TrackId> = emptySet()

    /** The source is one of the user's own playlists, so songs can be moved out of it. */
    private var sourceWritable = false

    /** Songs taken out of the source playlist in this session (undo puts them back). */
    private val movedOut = mutableSetOf<TrackId>()
    private val settings: StateFlow<SortSettings> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, SortSettings())

    private val _events = Channel<SwipeEvent>(Channel.BUFFERED)
    val events: Flow<SwipeEvent> = _events.receiveAsFlow()

    /** Order of the queue: list order unless the user picked another one in the song list. */
    private val queueOrder = MutableStateFlow(TrackOrder.Source to false)

    /** Place of each track in the source as loaded, for "Orden de la lista". */
    private val loadIndex = mutableMapOf<TrackId, Int>()
    private val orderKey = "${provider.id.value}|${source.key}"

    private data class Extras(val pending: Int, val step: TutorialStep?, val load: LoadState, val order: Pair<TrackOrder, Boolean>)

    private val extras = combine(syncStatus.pendingCount, tutorialStep, loadState, queueOrder, ::Extras)

    /** Style profile of each destination playlist (name-only at first, then learnt from its songs). */
    private val profiles = MutableStateFlow<Map<PlaylistId, PlaylistProfile>>(emptyMap())
    private val profilesRequested = mutableSetOf<PlaylistId>()

    /** How the current and upcoming songs sound. */
    private val trackFeatures = MutableStateFlow<Map<TrackId, TrackFeatures>>(emptyMap())

    private val suggestion: Flow<SlotSuggestion?> =
        combine(session, playlists, settings, profiles, trackFeatures) { session, playlists, settings, profiles, features ->
            val track = session?.current
            if (!settings.suggestions || track == null) return@combine null
            val map = settings.gestureMapFor(provider.id, playlists)
            // Wait until every destination has a profile, so an unready one isn't unfairly beaten.
            val slots = map.slots.map { slot -> map.playlistsAt(slot).map { profiles[it] ?: return@combine null } }
            SuggestionEngine.suggest(track, features[track.id], slots)?.let { found ->
                SlotSuggestion(Slot(found.slotIndex), track.id, found.playlists.joinToString(" + ") { it.name }, found.reasons)
            }
        }

    private val baseState: Flow<SwipeUiState> =
        combine(session, playlists, settings, player.state, extras) { session, playlists, settings, playerState, extras ->
            val (pending, step, load, order) = extras
            val base = SwipeUiState(
                providerName = provider.displayName,
                sourceName = sourceName,
                canLike = provider.capabilities.canLike,
                player = playerState,
                pendingSync = pending,
                settings = settings,
                isLoadingMore = load.loadingMore,
                queueOrder = order.first,
                queueDescending = order.second,
                movesFromSource = sourceWritable && settings.moveFromSource,
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
                tutorialStep = step,
            )
        }

    val uiState: StateFlow<SwipeUiState> =
        combine(baseState, suggestion) { state, suggestion ->
            if (suggestion != null && suggestion.trackId == state.current?.id) state.copy(suggestion = suggestion) else state
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SwipeUiState())

    init {
        load()
        viewModelScope.launch { watchProfiles() }
        viewModelScope.launch { watchFeatures() }
    }

    /** Builds a profile for every destination playlist, once each, when suggestions are on. */
    private suspend fun watchProfiles() {
        combine(playlists, settings) { playlists, settings ->
            if (!settings.suggestions) emptySet() else settings.gestureMapFor(provider.id, playlists).destinations.flatten().toSet()
        }
            .distinctUntilChanged()
            .collect { ids ->
                ids.filter { profilesRequested.add(it) }.forEach { id ->
                    val playlist = playlists.value.firstOrNull { it.id == id } ?: return@forEach
                    // The name alone already gives a hint ("Gym", "Relax") while the songs load.
                    profiles.update { it + (id to PlaylistProfile(playlist)) }
                    viewModelScope.launch { buildProfile(playlist) }
                }
            }
    }

    private suspend fun buildProfile(playlist: Playlist) {
        try {
            val songs = mutableListOf<Track>()
            provider.tracks.pages(TrackSource.FromPlaylist(playlist.id))
                .takeWhile { songs.size < PROFILE_TRACKS }
                .collect { songs += it }
            val built = suggester.profile(playlist, songs)
            profiles.update { it + (playlist.id to built) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep the name-only profile.
        }
    }

    /** Analyses the current song, then the next two so they are ready when the user gets there. */
    private suspend fun watchFeatures() {
        combine(
            session.map { it?.current }.distinctUntilChanged { a, b -> a?.id == b?.id },
            settings.map { it.suggestions }.distinctUntilChanged(),
        ) { track, enabled -> if (enabled) track else null }
            .collectLatest { track ->
                if (track == null) return@collectLatest
                fetchFeatures(track)
                val current = session.value ?: return@collectLatest
                current.tracks.drop(current.position + 1).take(2).forEach { fetchFeatures(it) }
            }
    }

    private suspend fun fetchFeatures(track: Track) {
        val features = suggester.features(track)
        trackFeatures.update { known ->
            val updated = known + (track.id to features)
            if (updated.size > MAX_FEATURES_KEPT) updated.entries.drop(updated.size - MAX_FEATURES_KEPT).associate { it.key to it.value } else updated
        }
    }

    /**
     * Reorders the songs still to sort (from the song list). The current song stays; the next
     * ones follow the new order. Remembered for this source.
     */
    fun setQueueOrder(order: TrackOrder, descending: Boolean) {
        if (queueOrder.value == (order to descending)) return
        queueOrder.value = order to descending
        session.value = session.value?.inQueueOrder()
        viewModelScope.launch {
            settingsRepository.update { it.copy(queueOrders = it.queueOrders + (orderKey to encodeOrder(order, descending))) }
        }
    }

    private fun rememberLoadOrder(tracks: List<Track>) {
        tracks.forEach { track -> if (track.id !in loadIndex) loadIndex[track.id] = loadIndex.size }
    }

    private fun SortSession.inQueueOrder(): SortSession {
        val (order, descending) = queueOrder.value
        if (order == TrackOrder.Source && !descending) {
            // Back to list order (also after another order was used).
            return if (tracks.zipWithNext().all { (a, b) -> (loadIndex[a.id] ?: 0) <= (loadIndex[b.id] ?: 0) }) this
            else reordered(compareBy { loadIndex[it.id] ?: Int.MAX_VALUE })
        }
        return reordered(trackComparator(order, descending) { loadIndex[it.id] ?: Int.MAX_VALUE })
    }

    /** Before handing the song to the service's own app (full song): stop the preview. */
    fun onOpenExternally() {
        keepPlaying = false
        viewModelScope.launch { player.pause() }
    }

    /** Accepts the suggested destination, like swiping to it. */
    fun onAcceptSuggestion() {
        val suggestion = uiState.value.suggestion ?: return
        onSwipe(suggestion.slot)
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
                val all = provider.playlists.allPlaylists()
                val sourcePlaylist = (source as? TrackSource.FromPlaylist)?.let { s -> all.firstOrNull { it.id == s.id } }
                sourceWritable = sourcePlaylist?.canWrite == true
                // Only editable playlists are destinations, and never the source itself.
                playlists.value = all.filter { it.canWrite && it.id != sourcePlaylist?.id }
                rememberAsRecent(sourcePlaylist?.imageUrl, total = sourcePlaylist?.trackCount)
                alreadySorted = progress.sortedTracks(provider.id, source.key)
                loadIndex.clear()
                decodeOrder(settingsRepository.settings.first().queueOrders[orderKey])?.let { queueOrder.value = it }
                provider.tracks.pages(source).collect { page -> onPage(page) }
                if (session.value == null) onPage(emptyList())
                loadState.value = loadState.value.copy(loadingMore = false)
                rememberAsRecent(sourcePlaylist?.imageUrl, total = session.value?.tracks?.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (session.value == null) {
                    // The screen words it; this is only the technical detail.
                    loadState.value = LoadState(error = e.message.orEmpty())
                } else {
                    // Keep what was loaded; the user can keep sorting.
                    loadState.value = loadState.value.copy(loadingMore = false)
                }
            }
        }
    }

    /** Shows this source first in "continue sorting" on the home screen. */
    private suspend fun rememberAsRecent(imageUrl: String?, total: Int?) {
        val entry = RecentSource(provider.id, source, sourceName, imageUrl, total, openedAt = System.currentTimeMillis())
        settingsRepository.update { it.withRecent(entry) }
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
            rememberLoadOrder(tracks)
            session.value = SortSession(tracks, sorted = alreadySorted).inQueueOrder().startAtFirstPending()
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
            rememberLoadOrder(tracks)
            session.value = current.append(tracks).inQueueOrder()
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
                _events.trySend(SwipeEvent.CreatePlaylistFailed)
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
        val last = current.history.lastOrNull() as? HistoryEntry.Decided
        last?.takeUnless { it.wasSorted }?.let { undone ->
            viewModelScope.launch { progress.unmarkSorted(provider.id, source.key, undone.track.id) }
        }
        // A moved song goes back into the source playlist (at the end: services don't keep the old spot).
        val from = source as? TrackSource.FromPlaylist
        if (last != null && from != null && movedOut.remove(last.track.id)) {
            viewModelScope.launch { libraryRepository.apply(LibraryEffect.AddToPlaylists(last.track, setOf(from.id))) }
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

    /** "Varias": sends the song to any set of playlists, destinations or not. */
    fun onSendTo(ids: Set<PlaylistId>) {
        sendTo(ids.filterTo(mutableSetOf()) { id -> playlists.value.any { it.id == id } })
    }

    private fun addTo(slots: Set<Slot>) {
        sendTo(settings.value.gestureMapFor(provider.id, playlists.value).playlistsFor(slots))
    }

    private fun sendTo(ids: Set<PlaylistId>) {
        if (ids.isEmpty()) return
        val track = session.value?.current
        decide(Decision.AddTo(ids))
        // "Mover": the song leaves the playlist being sorted.
        val from = source as? TrackSource.FromPlaylist
        if (track != null && from != null && sourceWritable && settings.value.moveFromSource) {
            movedOut += track.id
            viewModelScope.launch { libraryRepository.apply(LibraryEffect.RemoveFromPlaylists(track, setOf(from.id))) }
        }
        // Each decision teaches the playlists what they are about.
        if (track != null) {
            val features = trackFeatures.value[track.id]
            profiles.update { current -> current + ids.mapNotNull { id -> current[id]?.let { id to it.learn(track, features) } } }
        }
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
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        /** Songs read from each destination playlist to learn its style. */
        const val PROFILE_TRACKS = 400
        const val MAX_FEATURES_KEPT = 30
    }
}
