package com.slidedify.core.domain.provider

import com.slidedify.core.domain.player.Player
import com.slidedify.core.domain.repository.LibraryRepository
import com.slidedify.core.domain.repository.PlaylistRepository
import com.slidedify.core.domain.repository.TrackRepository
import com.slidedify.core.model.ProviderId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProviderRegistryTest {

    private class StubProvider(id: String, override val displayName: String) : MusicProvider {
        override val id = ProviderId(id)
        override val capabilities = ProviderCapabilities()
        override val auth: ProviderAuth get() = error("unused")
        override val tracks: TrackRepository get() = error("unused")
        override val playlists: PlaylistRepository get() = error("unused")
        override val library: LibraryRepository get() = error("unused")
        override val player: Player get() = error("unused")
    }

    private val spotify = StubProvider("spotify", "Spotify")
    private val tidal = StubProvider("tidal", "Tidal")

    @Test
    fun `preferred provider is active when registered`() {
        val registry = ProviderRegistry(setOf(spotify, tidal), preferred = ProviderId("tidal"))
        assertEquals(tidal, registry.active.value)
    }

    @Test
    fun `falls back to the first provider by name`() {
        val registry = ProviderRegistry(setOf(tidal, spotify), preferred = ProviderId("youtube-music"))
        assertEquals(spotify, registry.active.value)
    }

    @Test
    fun `select switches the active provider`() {
        val registry = ProviderRegistry(setOf(spotify, tidal))
        registry.select(ProviderId("tidal"))
        assertEquals(tidal, registry.active.value)
        assertFailsWith<IllegalArgumentException> { registry.select(ProviderId("nope")) }
    }

    @Test
    fun `rejects empty or duplicated registrations`() {
        assertFailsWith<IllegalArgumentException> { ProviderRegistry(emptySet()) }
        assertFailsWith<IllegalArgumentException> {
            ProviderRegistry(setOf(spotify, StubProvider("spotify", "Spotify 2")))
        }
    }
}
