package com.slidedify.feature.transfer

import com.slidedify.core.domain.provider.ProviderCapabilities
import com.slidedify.core.domain.provider.ProviderRegistry
import com.slidedify.core.domain.repository.LibraryRepository
import com.slidedify.core.domain.repository.TrackSource
import com.slidedify.core.domain.session.LibraryEffect
import com.slidedify.core.domain.transfer.PlaylistTransfer
import com.slidedify.core.testing.FakeProvider
import com.slidedify.core.testing.testPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class TransferViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val followed = testPlaylist("Top Argentina", provider = com.slidedify.core.model.ProviderId("a"), canWrite = false)
    private val from = FakeProvider(id = "a", displayName = "A", library = listOf(followed))
    private val to = FakeProvider(id = "b", displayName = "B", capabilities = ProviderCapabilities(canSearch = true))
    private val registry = ProviderRegistry(setOf(from, to))
    private val library = object : LibraryRepository {
        override suspend fun apply(effect: LibraryEffect) = Unit
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `followed playlists can be the source and name the new playlist`() = runTest(dispatcher) {
        val vm = TransferViewModel(registry, PlaylistTransfer(registry, library))
        vm.selectFrom(from.id)

        val sources = vm.state.value.sources
        assertEquals(listOf<TrackSource>(TrackSource.LikedSongs, TrackSource.FromPlaylist(followed.id)), sources.map { it.source })

        vm.selectSource(sources[1])
        assertEquals("Top Argentina", vm.state.value.newName)
        vm.selectTo(to.id)
        assertEquals(true, vm.state.value.canStart)
    }

    @Test
    fun `a service whose playlists can't be read is named in the error`() = runTest(dispatcher) {
        from.failure = IllegalStateException("offline")
        val vm = TransferViewModel(registry, PlaylistTransfer(registry, library))
        vm.selectFrom(from.id)
        assertEquals("A", vm.state.value.readErrorService)

        from.failure = null
        vm.selectFrom(from.id)
        assertNull(vm.state.value.readErrorService)
    }
}
