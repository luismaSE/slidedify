package com.slidedify.feature.connections

import com.slidedify.core.domain.provider.AuthState
import com.slidedify.core.domain.provider.ProviderRegistry
import com.slidedify.core.testing.FakeProvider
import com.slidedify.core.testing.MemorySyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val spotify = FakeProvider(id = "spotify", displayName = "Spotify")
    private val demo = FakeProvider(id = "demo", displayName = "Demo")
    private val registry = ProviderRegistry(setOf(spotify, demo), preferred = demo.id)
    private val sync = MemorySyncStatus(pending = 3)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `active service first, pending changes and services not in this build`() = runTest(dispatcher) {
        val vm = ConnectionsViewModel(registry, emptySet(), sync)
        backgroundScope.launch(dispatcher) { vm.state.collect {} }

        val state = vm.state.value
        assertEquals(listOf("demo", "spotify"), state.connections.map { it.id.value })
        assertEquals(true, state.connections.first().isActive)
        assertEquals(3, state.pendingSync)
        assertEquals(listOf("TIDAL", "YouTube Music"), state.unavailable)

        vm.use(spotify.id)
        assertEquals("spotify", vm.state.value.connections.first().id.value)
    }

    @Test
    fun `disconnecting signs out of that service`() = runTest(dispatcher) {
        val vm = ConnectionsViewModel(registry, emptySet(), sync)
        backgroundScope.launch(dispatcher) { vm.state.collect {} }

        vm.disconnect(spotify.id)

        assertEquals(1, spotify.signOutCalls)
        assertIs<AuthState.SignedOut>(vm.state.value.connections.first { it.id == spotify.id }.auth)
    }
}
