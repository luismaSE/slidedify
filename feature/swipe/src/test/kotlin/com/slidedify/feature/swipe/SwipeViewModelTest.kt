package com.slidedify.feature.swipe

import androidx.lifecycle.SavedStateHandle
import com.slidedify.core.domain.outbox.LibraryWriter
import com.slidedify.core.domain.outbox.SyncStatus
import com.slidedify.core.domain.player.Player
import com.slidedify.core.domain.player.PlayerState
import com.slidedify.core.domain.provider.AuthState
import com.slidedify.core.domain.provider.MusicProvider
import com.slidedify.core.domain.provider.ProviderAuth
import com.slidedify.core.domain.provider.ProviderCapabilities
import com.slidedify.core.domain.provider.ProviderRegistry
import com.slidedify.core.domain.repository.LibraryRepository
import com.slidedify.core.domain.repository.PlaylistRepository
import com.slidedify.core.domain.repository.TrackRepository
import com.slidedify.core.domain.suggest.FeatureSource
import com.slidedify.core.domain.suggest.InMemoryFeatureCache
import com.slidedify.core.domain.suggest.Suggester
import com.slidedify.core.domain.suggest.TrackFeatures
import com.slidedify.core.domain.repository.TrackSource
import com.slidedify.core.domain.session.LibraryEffect
import com.slidedify.core.domain.settings.SettingsRepository
import com.slidedify.core.domain.settings.SortSettings
import com.slidedify.core.model.Album
import com.slidedify.core.model.GestureMap
import com.slidedify.core.model.Artist
import com.slidedify.core.model.Playlist
import com.slidedify.core.model.PlaylistId
import com.slidedify.core.model.ProviderId
import com.slidedify.core.model.Slot
import com.slidedify.core.model.Track
import com.slidedify.core.model.TrackId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SwipeViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val providerId = ProviderId("test")

    private val tracks = (1..3).map { n ->
        Track(
            id = TrackId("t$n"),
            provider = providerId,
            title = "Song $n",
            artists = listOf(Artist("a", "Artist")),
            album = Album("al$n", "Album", imageUrl = null, releaseYear = 2020),
            durationMs = 180_000,
        )
    }
    private val basePlaylists = listOf("gym", "chill", "focus", "party", "running")
        .map { Playlist(PlaylistId(it), providerId, it) }
    private val extraPlaylists = mutableListOf<Playlist>()
    private val playlists get() = basePlaylists + extraPlaylists

    private val library = RecordingLibrary()
    private val player = RecordingPlayer()
    private val settings = MemorySettings(SortSettings(tutorialCompleted = true, suggestions = false))
    /** Songs each playlist "contains", for suggestion profiles. */
    private val playlistContents = mutableMapOf<PlaylistId, List<Track>>()
    private var featureSources: List<FeatureSource> = emptyList()
    private val requestedSources = mutableListOf<TrackSource>()
    private val progress = MemoryProgress()
    /** When set, the provider returns tracks in these pages instead of a single list. */
    private var pagedTracks: kotlinx.coroutines.flow.Flow<List<Track>>? = null

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        caps: ProviderCapabilities = ProviderCapabilities(),
        args: Map<String, Any?> = emptyMap(),
    ): SwipeViewModel {
        val provider = object : MusicProvider {
            override val id = providerId
            override val displayName = "Test"
            override val capabilities = caps
            override val auth = object : ProviderAuth {
                override val state = MutableStateFlow<AuthState>(AuthState.SignedIn("me"))

                override suspend fun signOut() = Unit
            }
            override val tracks = object : TrackRepository {
                override suspend fun tracks(source: TrackSource): List<Track> {
                    requestedSources += source
                    return this@SwipeViewModelTest.tracks
                }

                override fun pages(source: TrackSource) =
                    (source as? TrackSource.FromPlaylist)?.let { playlistContents[it.id] }?.let { kotlinx.coroutines.flow.flowOf(it) }
                        ?: pagedTracks
                        ?: kotlinx.coroutines.flow.flow { emit(tracks(source)) }
            }
            override val playlists = object : PlaylistRepository {
                override suspend fun writablePlaylists() = this@SwipeViewModelTest.playlists

                override suspend fun createPlaylist(name: String) = Playlist(PlaylistId("new"), providerId, name)
            }
            override val writer: LibraryWriter get() = error("features must write through LibraryRepository")
            override val player: Player = this@SwipeViewModelTest.player
        }
        return SwipeViewModel(
            registry = ProviderRegistry(setOf(provider)),
            libraryRepository = library,
            settingsRepository = settings,
            progress = progress,
            suggester = Suggester(featureSources, InMemoryFeatureCache()),
            syncStatus = object : SyncStatus {
                override val pendingCount = MutableStateFlow(0)
            },
            savedStateHandle = SavedStateHandle(args),
        )
    }

    private fun id(value: String) = PlaylistId(value)

    /** Saves a gesture map with one destination per playlist id, in order. */
    private fun useDestinations(vararg ids: String) =
        settings.state.update { it.withGestureMap(providerId, GestureMap.of(ids.map(::PlaylistId))) }

    private fun TestScope.collect(vm: SwipeViewModel) {
        backgroundScope.launch(dispatcher) { vm.uiState.collect {} }
    }

    @Test
    fun `starts paused and the default circle has a single destination`() = runTest(dispatcher) {
        val vm = viewModel()
        collect(vm)
        assertEquals(tracks[0], vm.uiState.value.current)
        assertNull(player.state.value.track)
        assertEquals(listOf(listOf(playlists[0])), vm.uiState.value.targets.map { it.playlists })
    }

    @Test
    fun `next track keeps playing only if the previous one was playing`() = runTest(dispatcher) {
        val vm = viewModel()
        collect(vm)

        vm.onTogglePlayback() // play
        assertEquals(tracks[0], player.state.value.track)
        vm.onSkip()
        assertEquals(tracks[1], player.state.value.track) // continued automatically
        assertTrue(player.state.value.isPlaying)

        vm.onTogglePlayback() // pause
        vm.onSkip()
        assertEquals(tracks[1], player.state.value.track) // stays paused, next not started
        assertFalse(player.state.value.isPlaying)
    }

    @Test
    fun `wheel commit writes to every selected playlist and moves on`() = runTest(dispatcher) {
        useDestinations("gym", "chill", "focus")
        val vm = viewModel()
        collect(vm)

        vm.onWheelCommit(setOf(Slot(0), Slot(2)))

        assertEquals<List<LibraryEffect>>(
            listOf(LibraryEffect.AddToPlaylists(tracks[0], setOf(id("gym"), id("focus")))),
            library.effects,
        )
        assertEquals(tracks[1], vm.uiState.value.current)
    }

    @Test
    fun `a combo destination adds to all its playlists`() = runTest(dispatcher) {
        settings.state.update { it.withGestureMap(providerId, GestureMap(listOf(setOf(id("chill"), id("party"))))) }
        val vm = viewModel()
        collect(vm)

        vm.onSwipe(Slot(0))

        assertEquals<LibraryEffect>(LibraryEffect.AddToPlaylists(tracks[0], setOf(id("chill"), id("party"))), library.effects.single())
    }

    @Test
    fun `skip button moves on without writing`() = runTest(dispatcher) {
        val vm = viewModel()
        collect(vm)

        vm.onSkip()

        assertEquals(tracks[1], vm.uiState.value.current)
        assertTrue(library.effects.isEmpty())
    }

    @Test
    fun `undo reverts the last add`() = runTest(dispatcher) {
        val vm = viewModel()
        collect(vm)

        vm.onSwipe(Slot(0))
        vm.onUndo()

        assertEquals(tracks[0], vm.uiState.value.current)
        assertEquals<LibraryEffect>(LibraryEffect.RemoveFromPlaylists(tracks[0], setOf(id("gym"))), library.effects.last())
    }

    @Test
    fun `adding a destination re-splits the circle and is saved`() = runTest(dispatcher) {
        val vm = viewModel()
        collect(vm)

        vm.onAddDestination(playlists[2])

        assertEquals(listOf("gym", "focus"), vm.uiState.value.targets.map { it.label })
        assertEquals(GestureMap.of(listOf(id("gym"), id("focus"))), settings.state.value.gestureMaps[providerId])
        assertTrue(vm.uiState.value.addablePlaylists.none { it.id == id("focus") })
    }

    @Test
    fun `no more than eight destinations`() = runTest(dispatcher) {
        val many = (1..8).map { Playlist(id("p$it"), providerId, "p$it") }
        settings.state.update { it.withGestureMap(providerId, GestureMap.of(many.map { p -> p.id })) }
        extraPlaylists += many
        val vm = viewModel()
        collect(vm)

        vm.onAddDestination(playlists[0])

        assertEquals(8, vm.uiState.value.targets.size)
        assertFalse(vm.uiState.value.canAddDestination)
    }

    @Test
    fun `creating a playlist adds it as a destination`() = runTest(dispatcher) {
        val vm = viewModel()
        collect(vm)

        vm.onCreatePlaylist("  Road trip ")

        assertEquals(listOf("gym", "Road trip"), vm.uiState.value.targets.map { it.label })
    }

    @Test
    fun `double tap likes without advancing`() = runTest(dispatcher) {
        val vm = viewModel()
        collect(vm)

        vm.onDoubleTap()

        assertTrue(vm.uiState.value.isLiked)
        assertEquals(tracks[0], vm.uiState.value.current)
        assertEquals<LibraryEffect>(LibraryEffect.SetLiked(tracks[0], true), library.effects.single())
    }

    @Test
    fun `double tap is ignored when the provider cannot like`() = runTest(dispatcher) {
        val vm = viewModel(ProviderCapabilities(canLike = false))
        collect(vm)

        vm.onDoubleTap()

        assertFalse(vm.uiState.value.isLiked)
        assertTrue(library.effects.isEmpty())
    }

    @Test
    fun `playlist source is loaded and excluded from destinations`() = runTest(dispatcher) {
        val vm = viewModel(args = mapOf(SwipeArgs.KIND to "Playlist", SwipeArgs.PLAYLIST_ID to "gym", SwipeArgs.TITLE to "gym"))
        collect(vm)

        assertEquals(listOf<TrackSource>(TrackSource.FromPlaylist(PlaylistId("gym"))), requestedSources)
        assertEquals("gym", vm.uiState.value.sourceName)
        assertTrue(vm.uiState.value.targets.none { target -> target.playlists.any { it.id == PlaylistId("gym") } })
        assertTrue(vm.uiState.value.addablePlaylists.none { it.id == PlaylistId("gym") })
    }

    @Test
    fun `tutorial advances with each gesture and is saved when done`() = runTest(dispatcher) {
        settings.state.update { it.copy(tutorialCompleted = false) }
        val vm = viewModel()
        collect(vm)
        assertEquals(TutorialStep.Swipe, vm.uiState.value.tutorialStep)

        vm.onSkip()
        assertEquals(TutorialStep.Wheel, vm.uiState.value.tutorialStep)

        vm.onWheelOpened()
        assertEquals(TutorialStep.DoubleTap, vm.uiState.value.tutorialStep)

        vm.onDoubleTap()
        assertNull(vm.uiState.value.tutorialStep)
        assertTrue(settings.state.value.tutorialCompleted)
    }

    @Test
    fun `first page shows right away and later pages are appended`() = runTest(dispatcher) {
        val pages = kotlinx.coroutines.channels.Channel<List<Track>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        pagedTracks = kotlinx.coroutines.flow.flow { for (page in pages) emit(page) }
        val vm = viewModel()
        collect(vm)

        pages.send(listOf(tracks[0]))
        assertEquals(tracks[0], vm.uiState.value.current)
        assertTrue(vm.uiState.value.isLoadingMore)

        vm.onSkip()
        assertTrue(vm.uiState.value.isWaitingForTracks) // finished the loaded part, more coming

        pages.send(listOf(tracks[1], tracks[2]))
        assertEquals(tracks[1], vm.uiState.value.current)

        pages.close()
        assertFalse(vm.uiState.value.isLoadingMore)
    }

    @Test
    fun `reopening a source continues after the tracks already sorted`() = runTest(dispatcher) {
        progress.sorted += "liked" to tracks[0].id
        val vm = viewModel()
        collect(vm)

        assertEquals(tracks[1], vm.uiState.value.current)
        assertEquals(1, vm.uiState.value.sortedCount)
        assertEquals(3, vm.uiState.value.queue.size) // reviewed tracks stay in the list

        vm.onSkip()
        assertTrue(("liked" to tracks[1].id) in progress.sorted)
        vm.onUndo()
        assertFalse(("liked" to tracks[1].id) in progress.sorted)
    }

    @Test
    fun `restart forgets the progress of the source`() = runTest(dispatcher) {
        progress.sorted += "liked" to tracks[0].id
        val vm = viewModel()
        collect(vm)

        vm.restart()

        assertEquals(tracks[0], vm.uiState.value.current)
        assertEquals(0, vm.uiState.value.sortedCount)
    }

    @Test
    fun `jumping from the list continues from that track`() = runTest(dispatcher) {
        val vm = viewModel()
        collect(vm)

        vm.jumpTo(2)

        assertEquals(tracks[2], vm.uiState.value.current)
    }

    private class MemoryProgress : com.slidedify.core.domain.repository.SortProgressRepository {
        val sorted = mutableSetOf<Pair<String, TrackId>>()

        override suspend fun sortedTracks(provider: ProviderId, sourceKey: String) =
            sorted.filter { it.first == sourceKey }.mapTo(HashSet()) { it.second }

        override suspend fun markSorted(provider: ProviderId, sourceKey: String, track: TrackId) {
            sorted += sourceKey to track
        }

        override suspend fun unmarkSorted(provider: ProviderId, sourceKey: String, track: TrackId) {
            sorted -= sourceKey to track
        }

        override suspend fun clear(provider: ProviderId, sourceKey: String) {
            sorted.removeAll { it.first == sourceKey }
        }
    }

    @Test
    fun `suggests the playlist that fits the song and learns from accepting it`() = runTest(dispatcher) {
        settings.state.update { it.copy(suggestions = true) }
        useDestinations("gym", "chill")
        playlistContents[id("gym")] = emptyList()
        playlistContents[id("chill")] = emptyList()
        featureSources = listOf(
            object : FeatureSource {
                override val id = "calm"
                override suspend fun features(track: Track) = TrackFeatures(tags = mapOf("chill" to 1f), audioEnergy = 0.15f)
            },
        )
        val vm = viewModel()
        collect(vm)

        val suggestion = assertNotNull(vm.uiState.value.suggestion)
        assertEquals(Slot(1), suggestion.slot)
        assertEquals("chill", suggestion.label)

        vm.onAcceptSuggestion()
        assertEquals<List<LibraryEffect>>(listOf(LibraryEffect.AddToPlaylists(tracks[0], setOf(id("chill")))), library.effects)
        assertEquals(tracks[1], vm.uiState.value.current)
    }

    @Test
    fun `sending to several playlists works without the wheel, destinations or not`() = runTest(dispatcher) {
        useDestinations("gym")
        val vm = viewModel()
        collect(vm)

        vm.onSendTo(setOf(id("gym"), id("party"), id("unknown")))

        assertEquals<List<LibraryEffect>>(listOf(LibraryEffect.AddToPlaylists(tracks[0], setOf(id("gym"), id("party")))), library.effects)
        assertEquals(tracks[1], vm.uiState.value.current)
    }

    @Test
    fun `no suggestion when they are turned off`() = runTest(dispatcher) {
        useDestinations("gym", "chill")
        val vm = viewModel()
        collect(vm)
        assertNull(vm.uiState.value.suggestion)
    }

    private class MemorySettings(initial: SortSettings) : SettingsRepository {
        val state = MutableStateFlow(initial)
        override val settings = state

        override suspend fun update(transform: (SortSettings) -> SortSettings) = state.update(transform)
    }

    private class RecordingLibrary : LibraryRepository {
        val effects = mutableListOf<LibraryEffect>()

        override suspend fun apply(effect: LibraryEffect) {
            effects += effect
        }
    }

    private class RecordingPlayer : Player {
        private val _state = MutableStateFlow(PlayerState())
        override val state: StateFlow<PlayerState> = _state

        override suspend fun play(track: Track) {
            _state.value = PlayerState(track = track, isPlaying = true)
        }

        override suspend fun pause() {
            _state.value = _state.value.copy(isPlaying = false)
        }

        override suspend fun resume() {
            _state.value = _state.value.copy(isPlaying = true)
        }
    }
}
