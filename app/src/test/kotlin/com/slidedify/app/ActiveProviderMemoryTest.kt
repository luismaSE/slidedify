package com.slidedify.app

import com.slidedify.core.domain.provider.ProviderRegistry
import com.slidedify.core.domain.settings.SortSettings
import com.slidedify.core.model.ProviderId
import com.slidedify.core.testing.FakeProvider
import com.slidedify.core.testing.MemorySettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveProviderMemoryTest {
    private val spotify = FakeProvider(id = "spotify", displayName = "Spotify")
    private val demo = FakeProvider(id = "demo", displayName = "Demo")

    @Test
    fun `reopens on the last service and remembers changes`() = runTest(UnconfinedTestDispatcher()) {
        val registry = ProviderRegistry(setOf(spotify, demo), preferred = spotify.id)
        val settings = MemorySettings(SortSettings(lastProvider = ProviderId("demo")))

        ActiveProviderMemory(registry, settings).start(backgroundScope)
        assertEquals(demo.id, registry.active.value.id)

        registry.select(spotify.id)
        assertEquals(spotify.id, settings.state.value.lastProvider)
    }

    @Test
    fun `a service that no longer exists is ignored`() = runTest(UnconfinedTestDispatcher()) {
        val registry = ProviderRegistry(setOf(spotify), preferred = spotify.id)
        val settings = MemorySettings(SortSettings(lastProvider = ProviderId("tidal")))

        ActiveProviderMemory(registry, settings).start(backgroundScope)
        assertEquals(spotify.id, registry.active.value.id)
    }
}
