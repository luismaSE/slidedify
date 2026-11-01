package com.slidedify.core.domain.settings

import com.slidedify.core.model.GestureMap
import com.slidedify.core.model.Playlist
import com.slidedify.core.model.PlaylistId
import com.slidedify.core.model.ProviderId
import com.slidedify.core.model.Slot
import kotlin.test.Test
import kotlin.test.assertEquals

class SortSettingsTest {
    private val provider = ProviderId("spotify")
    private val playlists = listOf("a", "b", "c", "d", "e", "f").map { Playlist(PlaylistId(it), provider, it) }

    @Test
    fun `without a saved map the first five playlists fill the slots in order`() {
        val map = SortSettings().gestureMapFor(provider, playlists)
        assertEquals(setOf(PlaylistId("a")), map.targets[Slot.UP])
        assertEquals(setOf(PlaylistId("e")), map.targets[Slot.DOWN])
        assertEquals(5, map.enabledSlots.size)
    }

    @Test
    fun `saved combos are kept and deleted playlists are dropped`() {
        val saved = GestureMap(mapOf(Slot.UP to setOf(PlaylistId("a"), PlaylistId("gone")), Slot.DOWN to setOf(PlaylistId("gone"))))
        val map = SortSettings().withGestureMap(provider, saved).gestureMapFor(provider, playlists)
        assertEquals(setOf(PlaylistId("a")), map.targets[Slot.UP])
        assertEquals(setOf(Slot.UP), map.enabledSlots)
    }

    @Test
    fun `hold timeout is clamped`() {
        assertEquals(200L, SortSettings().withHoldTimeout(50).holdTimeoutMs)
        assertEquals(600L, SortSettings().withHoldTimeout(5_000).holdTimeoutMs)
        assertEquals(350L, SortSettings().withHoldTimeout(350).holdTimeoutMs)
    }
}
