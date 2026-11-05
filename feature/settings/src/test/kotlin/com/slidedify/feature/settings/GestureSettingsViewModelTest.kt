package com.slidedify.feature.settings

import com.slidedify.core.domain.provider.ProviderRegistry
import com.slidedify.core.domain.settings.SortSettings
import com.slidedify.core.model.GestureMap
import com.slidedify.core.model.Slot
import com.slidedify.core.testing.FakeProvider
import com.slidedify.core.testing.MemorySettings
import com.slidedify.core.testing.testPlaylist
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

@OptIn(ExperimentalCoroutinesApi::class)
class GestureSettingsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val gym = testPlaylist("Gym")
    private val chill = testPlaylist("Chill")
    private val party = testPlaylist("Party")
    private val spotify = FakeProvider(id = "spotify", displayName = "Spotify", library = listOf(gym, chill, party))
    private val other = FakeProvider(id = "other", displayName = "Other")
    private val registry = ProviderRegistry(setOf(spotify, other), preferred = spotify.id)
    private val settings = MemorySettings(
        SortSettings().withGestureMap(spotify.id, GestureMap.of(listOf(gym.id, chill.id, party.id))),
    )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `destinations can be moved, removed and the switches saved`() = runTest(dispatcher) {
        val vm = GestureSettingsViewModel(registry, settings)
        backgroundScope.launch(dispatcher) { vm.state.collect {} }

        vm.moveDestination(Slot(2), Slot(0))
        assertEquals(GestureMap.of(listOf(party.id, gym.id, chill.id)), vm.state.value.gestureMap)

        vm.removeDestination(Slot(1))
        assertEquals(GestureMap.of(listOf(party.id, chill.id)), vm.state.value.gestureMap)

        vm.setMoveFromSource(true)
        vm.setSuggestions(false)
        assertEquals(true, settings.state.value.moveFromSource)
        assertEquals(false, settings.state.value.suggestions)
    }

    @Test
    fun `follows the active service`() = runTest(dispatcher) {
        val vm = GestureSettingsViewModel(registry, settings)
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        assertEquals("Spotify", vm.state.value.providerName)

        registry.select(other.id)

        assertEquals("Other", vm.state.value.providerName)
        assertEquals(GestureMap.Empty, vm.state.value.gestureMap)
    }
}
