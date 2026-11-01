package com.slidedify.feature.swipe

import androidx.lifecycle.SavedStateHandle
import com.slidedify.core.domain.gesture.SwipeResult
import com.slidedify.core.domain.lyrics.LyricLine
import com.slidedify.core.domain.lyrics.Lyrics
import com.slidedify.core.domain.lyrics.LyricsRepository
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
import com.slidedify.core.domain.repository.TrackSource
import com.slidedify.core.domain.session.LibraryEffect
import com.slidedify.core.domain.settings.SettingsRepository
import com.slidedify.core.domain.settings.SortSettings
import com.slidedify.core.model.Album
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
    private val playlists = listOf("gym", "chill", "focus", "party", "running")
        .map { Playlist(PlaylistId(it), providerId, it) }

    private val library = RecordingLibrary()
    private val player = RecordingPlayer()
    private val settings = MemorySettings(SortSettings(tutorialCompleted = true))
    private val requestedSources = mutableListOf<TrackSource>()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        caps: ProviderCapabilities = ProviderCapabilities(),
        args: Map<String, Any?> = emptyMap(),
        lyrics: Lyrics? = null,
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
            }
            override val playlists = object : PlaylistRepository {
                override suspend fun writablePlaylists() = this@SwipeViewModelTest.playlists
            }
            override val writer: LibraryWriter get() = error("features must write through LibraryRepository")
            override val player: Player = this@SwipeViewModelTest.player
        }
        return SwipeViewModel(
            registry = ProviderRegistry(setOf(provider)),
            libraryRepository = library,
            settingsRepository = settings,
            lyricsRepository = object : LyricsRepository {
                override suspend fun lyricsFor(track: Track) = lyrics
            },
            syncStatus = object : SyncStatus {
                override val pendingCount = MutableStateFlow(0)
            },
            savedStateHandle = SavedStateHandle(args),
        )
    }

    private fun TestScope.collect(vm: SwipeViewModel) {
        backgroundScope.launch(dispatcher) { vm.uiState.collect {} }
    }

    @Test
    fun `first track starts playing automatically`() = runTest(dispatcher) {
        val vm = viewModel()
        collect(vm)
        assertEquals(tracks[0], vm.uiState.value.current)
        assertEquals(tracks[0], player.state.value.track)
        assertEquals(5, vm.uiState.value.targets.size)
    }

    @Test
    fun `autoplay off does not start playback`() = runTest(dispatcher) {
        settings.state.update { it.copy(autoplay = false) }
        val vm = viewModel()
        collect(vm)
        assertNull(player.state.value.track)
    }

    @Test
    fun `wheel commit writes to every selected playlist and moves on`() = runTest(dispatcher) {
        val vm = viewModel()
        collect(vm)

        vm.onWheelCommit(setOf(Slot.UP, Slot.RIGHT))

        assertEquals<List<LibraryEffect>>(
            listOf(LibraryEffect.AddToPlaylists(tracks[0], setOf(PlaylistId("gym"), PlaylistId("focus")))),
            library.effects,
        )
        assertEquals(tracks[1], vm.uiState.value.current)
        assertEquals(tracks[1], player.state.value.track)
    }

    @Test
    fun `saved gesture map with a combo is used`() = runTest(dispatcher) {
        settings.state.update {
            it.withGestureMap(
                providerId,
                com.slidedify.core.model.GestureMap(mapOf(Slot.DOWN to setOf(PlaylistId("chill"), PlaylistId("party")))),
            )
        }
        val vm = viewModel()
        collect(vm)

        vm.onSwipe(SwipeResult.ToSlot(Slot.DOWN))

        assertEquals(setOf(Slot.DOWN), vm.uiState.value.enabledSlots)
        assertEquals<LibraryEffect>(
            LibraryEffect.AddToPlaylists(tracks[0], setOf(PlaylistId("chill"), PlaylistId("party"))),
            library.effects.single(),
        )
    }

    @Test
    fun `undo reverts the last add`() = runTest(dispatcher) {
        val vm = viewModel()
        collect(vm)

        vm.onSwipe(SwipeResult.ToSlot(Slot.DOWN))
        vm.onUndo()

        assertEquals(tracks[0], vm.uiState.value.current)
        assertEquals<LibraryEffect>(LibraryEffect.RemoveFromPlaylists(tracks[0], setOf(PlaylistId("running"))), library.effects.last())
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
        assertTrue(vm.uiState.value.targets.values.none { target -> target.playlists.any { it.id == PlaylistId("gym") } })
    }

    @Test
    fun `lyrics are loaded for the current track`() = runTest(dispatcher) {
        val lyrics = Lyrics(listOf(LyricLine(0, "hello")))
        val vm = viewModel(lyrics = lyrics)
        collect(vm)
        assertEquals(LyricsState.Available(lyrics), vm.uiState.value.lyrics)
    }

    @Test
    fun `tutorial advances with each gesture and is saved when done`() = runTest(dispatcher) {
        settings.state.update { it.copy(tutorialCompleted = false) }
        val vm = viewModel()
        collect(vm)
        assertEquals(TutorialStep.Swipe, vm.uiState.value.tutorialStep)

        vm.onSwipe(SwipeResult.Skip)
        assertEquals(TutorialStep.Wheel, vm.uiState.value.tutorialStep)

        vm.onWheelOpened()
        assertEquals(TutorialStep.DoubleTap, vm.uiState.value.tutorialStep)

        vm.onDoubleTap()
        assertNull(vm.uiState.value.tutorialStep)
        assertTrue(settings.state.value.tutorialCompleted)
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
