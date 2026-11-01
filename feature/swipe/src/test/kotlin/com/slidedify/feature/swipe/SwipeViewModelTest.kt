package com.slidedify.feature.swipe

import com.slidedify.core.domain.gesture.SwipeResult
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SwipeViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val tracks = (1..3).map { n ->
        Track(
            id = TrackId("t$n"),
            provider = ProviderId("test"),
            title = "Song $n",
            artists = listOf(Artist("a", "Artist")),
            album = Album("al$n", "Album", imageUrl = null, releaseYear = 2020),
            durationMs = 180_000,
        )
    }
    private val playlists = listOf("gym", "chill", "focus", "party", "running")
        .map { Playlist(PlaylistId(it), ProviderId("test"), it) }

    private val library = RecordingLibrary()
    private val player = RecordingPlayer()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(caps: ProviderCapabilities = ProviderCapabilities()): SwipeViewModel {
        val provider = object : MusicProvider {
            override val id = ProviderId("test")
            override val displayName = "Test"
            override val capabilities = caps
            override val auth = object : ProviderAuth {
                override val state = MutableStateFlow<AuthState>(AuthState.SignedIn("me"))

                override suspend fun signOut() = Unit
            }
            override val tracks = object : TrackRepository {
                override suspend fun tracks(source: TrackSource) = this@SwipeViewModelTest.tracks
            }
            override val playlists = object : PlaylistRepository {
                override suspend fun writablePlaylists() = this@SwipeViewModelTest.playlists
            }
            override val library: LibraryRepository = this@SwipeViewModelTest.library
            override val player: Player = this@SwipeViewModelTest.player
        }
        return SwipeViewModel(ProviderRegistry(setOf(provider)))
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
    fun `wheel commit adds to every selected playlist and plays the next track`() = runTest(dispatcher) {
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

        assertEquals(false, vm.uiState.value.isLiked)
        assertTrue(library.effects.isEmpty())
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
