package com.slidedify.feature.source

import com.slidedify.core.domain.provider.AuthState
import com.slidedify.core.domain.provider.ProviderRegistry
import com.slidedify.core.domain.repository.TrackSource
import com.slidedify.core.domain.settings.RecentSource
import com.slidedify.core.domain.settings.SortSettings
import com.slidedify.core.testing.FakeProvider
import com.slidedify.core.testing.MemoryProgress
import com.slidedify.core.testing.MemorySettings
import com.slidedify.core.testing.testPlaylist
import com.slidedify.core.testing.testTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class HomeViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val gym = testPlaylist("Gym", trackCount = 10)
    private val followed = testPlaylist("Top Argentina", canWrite = false)
    private val chill = testPlaylist("Chill", trackCount = 4)
    private val provider = FakeProvider(library = listOf(followed, gym, chill))
    private val settings = MemorySettings()
    private val progress = MemoryProgress()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): HomeViewModel =
        HomeViewModel(ProviderRegistry(setOf(provider)), emptySet(), settings, progress).also { vm ->
            backgroundScope.launch(dispatcher) { vm.state.collect {} }
        }

    @Test
    fun `own playlists come first and followed ones are marked`() = runTest(dispatcher) {
        val state = viewModel().state.value
        assertEquals(listOf("Gym", "Chill", "Top Argentina"), state.playlists.map { it.title })
        assertEquals(listOf(false, false, true), state.playlists.map { it.followed })
        assertEquals(listOf<TrackSource>(TrackSource.LikedSongs), state.shortcuts.map { it.source })
        assertEquals("Ana Pérez", state.userName)
    }

    @Test
    fun `the last sorted list is offered to continue, with its progress`() = runTest(dispatcher) {
        val gymSource = TrackSource.FromPlaylist(gym.id)
        settings.state.value = SortSettings().withRecent(RecentSource(provider.id, gymSource, "Gym", openedAt = 5))
        repeat(5) { progress.markSorted(provider.id, "playlist:gym", testTrack(it).id) }

        val card = viewModel().state.value.continueCard!!
        assertEquals(gymSource, card.source)
        assertEquals(5, card.sorted)
        assertEquals(0.5f, card.progress)
        assertFalse(card.isDone)
    }

    @Test
    fun `a loading failure is reported with its reason`() = runTest(dispatcher) {
        provider.failure = IllegalStateException("timeout")
        val state = viewModel().state.value
        assertTrue(state.loadFailed)
        assertEquals("timeout", state.errorDetail)
        assertTrue(state.playlists.isEmpty())
    }

    @Test
    fun `signed out shows no library`() = runTest(dispatcher) {
        provider.authState.value = AuthState.SignedOut
        val state = viewModel().state.value
        assertFalse(state.isLoading)
        assertTrue(state.playlists.isEmpty())
        assertNull(state.continueCard)
    }
}
