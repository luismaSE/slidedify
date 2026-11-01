package com.slidedify.feature.swipe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slidedify.core.domain.gesture.SwipeResult
import com.slidedify.core.domain.player.PlayerState
import com.slidedify.core.domain.provider.MusicProvider
import com.slidedify.core.domain.provider.ProviderRegistry
import com.slidedify.core.domain.repository.TrackSource
import com.slidedify.core.domain.session.Decision
import com.slidedify.core.domain.session.SessionUpdate
import com.slidedify.core.domain.session.SortSession
import com.slidedify.core.model.GestureMap
import com.slidedify.core.model.Playlist
import com.slidedify.core.model.PlaylistId
import com.slidedify.core.model.Slot
import com.slidedify.core.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Label and playlists behind one slot. */
data class SlotTarget(val slot: Slot, val playlists: List<Playlist>) {
    val label: String get() = playlists.joinToString(" + ") { it.name }
}

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

/**
 * Sorting screen logic. It only talks to the active [MusicProvider] through domain interfaces,
 * so it works the same for Spotify, YouTube Music, Tidal or the demo provider.
 */
@HiltViewModel
class SwipeViewModel @Inject constructor(
    registry: ProviderRegistry,
) : ViewModel() {

    private val provider: MusicProvider = registry.active.value
    private val player = provider.player

    private val session = MutableStateFlow<SortSession?>(null)
    private val gestureMap = MutableStateFlow(GestureMap(emptyMap()))
    private val playlistsById = MutableStateFlow<Map<PlaylistId, Playlist>>(emptyMap())

    private val _events = Channel<SwipeEvent>(Channel.BUFFERED)
    val events: Flow<SwipeEvent> = _events.receiveAsFlow()

    val uiState: StateFlow<SwipeUiState> =
        combine(session, gestureMap, playlistsById, player.state) { session, map, byId, playerState ->
            if (session == null) {
                SwipeUiState(isLoading = true, providerName = provider.displayName, player = playerState)
            } else {
                val current = session.current
                SwipeUiState(
                    isLoading = false,
                    providerName = provider.displayName,
                    sourceName = "Me gusta",
                    canLike = provider.capabilities.canLike,
                    current = current,
                    position = session.position,
                    total = session.tracks.size,
                    isLiked = current?.let(session::isLiked) ?: false,
                    canUndo = session.canUndo,
                    targets = map.targets
                        .mapValues { (slot, ids) -> SlotTarget(slot, ids.mapNotNull(byId::get)) }
                        .filterValues { it.playlists.isNotEmpty() },
                    player = playerState,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SwipeUiState())

    init {
        viewModelScope.launch {
            val playlists = provider.playlists.writablePlaylists()
            playlistsById.value = playlists.associateBy { it.id }
            gestureMap.value = GestureMap.inOrder(playlists.take(Slot.entries.size))
            session.value = SortSession(provider.tracks.tracks(TrackSource.LikedSongs))
            syncPlayback()
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
    }

    fun onWheelCommit(slots: Set<Slot>) = addTo(slots)

    fun onDoubleTap() {
        if (!provider.capabilities.canLike) return
        val current = session.value ?: return
        val update = current.toggleLike()
        apply(update)
        _events.trySend(SwipeEvent.LikeChanged(update.session.current?.let(update.session::isLiked) ?: false))
    }

    fun onTogglePlayback() {
        val state = player.state.value
        viewModelScope.launch {
            when {
                state.isPlaying -> player.pause()
                state.track?.id == session.value?.current?.id -> player.resume()
                else -> syncPlayback()
            }
        }
    }

    fun onUndo() {
        val current = session.value ?: return
        if (!current.canUndo) return
        apply(current.undo())
        _events.trySend(SwipeEvent.Undone)
        syncPlayback()
    }

    private fun addTo(slots: Set<Slot>) {
        val ids = gestureMap.value.playlistsFor(slots)
        if (ids.isEmpty()) return
        decide(Decision.AddTo(ids))
        _events.trySend(SwipeEvent.Added(ids.mapNotNull { playlistsById.value[it]?.name }))
    }

    private fun decide(decision: Decision) {
        val current = session.value ?: return
        apply(current.decide(decision))
        syncPlayback()
    }

    private fun apply(update: SessionUpdate) {
        session.value = update.session
        if (update.effects.isEmpty()) return
        viewModelScope.launch { update.effects.forEach { provider.library.apply(it) } }
    }

    /** Autoplay: whatever track is on screen should be the one playing. */
    private fun syncPlayback() {
        val track = session.value?.current
        viewModelScope.launch {
            when {
                track == null -> player.pause()
                player.state.value.track?.id != track.id -> player.play(track)
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
